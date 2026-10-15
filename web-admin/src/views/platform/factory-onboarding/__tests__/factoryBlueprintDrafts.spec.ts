import { createHash } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import type { DraftProposalItem, DraftRequiredInput, DraftWriteOrderRow } from '@/api/factoryBlueprintDraftApi'
import {
  IDEMPOTENCY_KEY_MAX_LENGTH,
  allDraftInputsAnswered,
  buildFilledDraftRequest,
  buildMaterializeBody,
  canConfirmBlueprintDrafts,
  deriveDraftRowState,
  deriveWrittenLabel,
  draftAnswerProblem,
  draftBoundText,
  draftErrorCode,
  draftErrorInputPath,
  draftErrorMessage,
  draftIdempotencyKey,
  draftIdempotencyMaterial,
  draftPreviewIdempotencyKey,
  draftPreviewIdempotencyMaterial,
  draftInputSpec,
  draftItemContractErrors,
  missingDraftInputs,
  parseDraftPath,
  readAtDraftPath,
  splitDraftEvidence,
  writeAtDraftPath,
  type DraftAnswers,
  type DraftInputSpec,
} from '../factoryBlueprintDrafts'

const input = (path: string, over: Partial<DraftRequiredInput> = {}): DraftRequiredInput => ({
  path, label: path, reason: '访谈没有问', constraint: '见说明', inputType: 'TEXT', options: [],
  nullable: false, min: null, max: null, maxLength: null, ...over,
})

const specOf = (over: Partial<DraftRequiredInput>): DraftInputSpec => {
  const r = draftInputSpec(input('x', over))
  if (!r.ok) throw new Error(r.error)
  return r.spec
}
const val = (value: unknown) => ({ kind: 'value' as const, value })

describe('draft path write-back (same SEGMENT syntax as the backend)', () => {
  const request = {
    factoryId: 'F1',
    positions: [
      { title: 'A', minimumHeadcount: null },
      { title: 'B', minimumHeadcount: null, nested: { flag: null } },
    ],
    desired: { name: null },
  }

  it('writes through object keys and array indexes without mutating the input', () => {
    const out = writeAtDraftPath(request, 'positions[1].minimumHeadcount', 3)
    expect((out.positions as Array<Record<string, unknown>>)[1].minimumHeadcount).toBe(3)
    expect((out.positions as Array<Record<string, unknown>>)[0].minimumHeadcount).toBeNull()
    expect(request.positions[1].minimumHeadcount).toBeNull()
    expect(readAtDraftPath(writeAtDraftPath(request, 'positions[1].nested.flag', true), 'positions[1].nested.flag')).toBe(true)
    expect(readAtDraftPath(writeAtDraftPath(request, 'desired.name', '鸡腿'), 'desired.name')).toBe('鸡腿')
  })

  it('throws instead of silently creating a missing key, parent or out-of-range index', () => {
    expect(() => writeAtDraftPath(request, 'desired.unit', 'kg')).toThrow(/不存在/)
    expect(() => writeAtDraftPath(request, 'nope.name', 'x')).toThrow(/不存在/)
    expect(() => writeAtDraftPath(request, 'positions[5].minimumHeadcount', 1)).toThrow(/越界/)
    expect(() => writeAtDraftPath(request, 'factoryId.x', 1)).toThrow(/不存在/)
  })

  it('rejects malformed paths and a trailing index, like the backend', () => {
    expect(() => parseDraftPath('positions[0]')).toThrow(/末段不能带下标/)
    expect(() => parseDraftPath('a..b')).toThrow(/不合法/)
    expect(() => parseDraftPath('0abc')).toThrow(/不合法/)
    expect(() => parseDraftPath('')).toThrow()
    expect(parseDraftPath('a.b[2].c')).toEqual([
      { key: 'a', index: null }, { key: 'b', index: 2 }, { key: 'c', index: null },
    ])
  })
})

describe('input spec comes only from inputType (no constraint-wording heuristics)', () => {
  it('a missing / unknown inputType or an option-less choice is a contract error, not a text box', () => {
    const noType = draftInputSpec(input('a.b', { inputType: undefined }))
    expect(noType.ok).toBe(false)
    expect(!noType.ok && noType.error).toContain('缺少 inputType')
    expect(draftInputSpec(input('a', { inputType: null })).ok).toBe(false)
    expect(draftInputSpec(input('a', { inputType: 'COLOR' as never })).ok).toBe(false)
    expect(draftInputSpec(input('a', { inputType: 'ENUM', options: [] })).ok).toBe(false)
    expect(draftInputSpec(input('a', { inputType: 'MULTI_ENUM', options: null })).ok).toBe(false)
    const item = { itemKey: 'k', label: 'L', status: 'NEEDS_INPUT' as const, request: { a: null, b: null }, evidence: [],
      requiredInputs: [input('a'), input('b', { inputType: undefined })] }
    expect(draftItemContractErrors(item)).toHaveLength(1)
    // 契约错误的项永远算没填, 整条不能预览
    expect(allDraftInputsAnswered(item, { a: val('x'), b: val('y') })).toBe(false)
    expect(() => buildFilledDraftRequest(item, { a: val('x'), b: val('y') })).toThrow(/inputType/)
  })

  it('the constraint wording no longer decides anything', () => {
    // 旧启发式会把「是 / 否」认成布尔; 现在类型说了算
    expect(specOf({ inputType: 'TEXT', constraint: '是 / 否' }).type).toBe('TEXT')
    expect(specOf({ inputType: 'BOOLEAN', constraint: '随便写' }).type).toBe('BOOLEAN')
  })
})

describe('answer validation per inputType', () => {
  it('TEXT honours maxLength and rejects blank', () => {
    const s = specOf({ inputType: 'TEXT', maxLength: 3 })
    expect(draftAnswerProblem(s, val('abc'))).toBeNull()
    expect(draftAnswerProblem(s, val('abcd'))).toContain('最多 3 字')
    expect(draftAnswerProblem(s, val('  '))).toBe('未填写')
  })
  it('INTEGER needs a whole number inside min/max; DECIMAL allows fractions', () => {
    const i = specOf({ inputType: 'INTEGER', min: 1, max: 500 })
    expect(draftAnswerProblem(i, val(2))).toBeNull()
    expect(draftAnswerProblem(i, val(2.5))).toBe('只能填整数')
    expect(draftAnswerProblem(i, val(0))).toBe('不能小于 1')
    expect(draftAnswerProblem(i, val(501))).toBe('不能大于 500')
    expect(draftAnswerProblem(i, val('2'))).toBe('请填数字')
    const d = specOf({ inputType: 'DECIMAL', min: 0.0001, max: 99.9999 })
    expect(draftAnswerProblem(d, val(0.85))).toBeNull()
    expect(draftAnswerProblem(d, val(100))).not.toBeNull()
  })
  it('BOOLEAN: false is an answer, a string is not', () => {
    const b = specOf({ inputType: 'BOOLEAN' })
    expect(draftAnswerProblem(b, val(false))).toBeNull()
    expect(draftAnswerProblem(b, val('否'))).toBe('请选择')
  })
  it('ENUM stores option.value; the label is not a valid answer', () => {
    const e = specOf({ inputType: 'ENUM', options: [{ value: 'QUALITY', label: '质量' }, { value: 'PROCESSING', label: '加工' }] })
    expect(draftAnswerProblem(e, val('QUALITY'))).toBeNull()
    expect(draftAnswerProblem(e, val('质量'))).toBe('请从选项里选择')
  })
  it('MULTI_ENUM honours the min/max count, rejects duplicates and unknown values', () => {
    const m = specOf({ inputType: 'MULTI_ENUM', min: 1, max: 2,
      options: [{ value: 'A', label: 'a' }, { value: 'B', label: 'b' }, { value: 'C', label: 'c' }] })
    expect(draftAnswerProblem(m, val(['A']))).toBeNull()
    expect(draftAnswerProblem(m, val(['A', 'B']))).toBeNull()
    expect(draftAnswerProblem(m, val(['A', 'B', 'C']))).toContain('1–2 项')
    expect(draftAnswerProblem(m, val(['A', 'A']))).toBe('不能重复选择')
    expect(draftAnswerProblem(m, val(['Z']))).toBe('请从选项里选择')
    expect(draftAnswerProblem(m, val([]))).toBe('未选择')
  })
  it('DATE must be a real ISO yyyy-MM-dd day', () => {
    const d = specOf({ inputType: 'DATE' })
    expect(draftAnswerProblem(d, val('2026-10-04'))).toBeNull()
    expect(draftAnswerProblem(d, val('2026-02-30'))).toBe('请选择日期')
    expect(draftAnswerProblem(d, val('2026/10/04'))).toBe('请选择日期')
  })
  it('explicit empty is only accepted when nullable', () => {
    expect(draftAnswerProblem(specOf({ nullable: true }), { kind: 'empty' })).toBeNull()
    expect(draftAnswerProblem(specOf({ nullable: false }), { kind: 'empty' })).toBe('这一项不能留空')
    expect(draftAnswerProblem(specOf({}), undefined)).toBe('未填写')
  })
})

describe('request building', () => {
  const item: DraftProposalItem = {
    itemKey: 'accounts',
    label: '岗位与培训要求',
    status: 'NEEDS_INPUT',
    request: { factoryId: 'F1', positions: [{ minimumHeadcount: null, healthCertificateRequired: null, note: null }] },
    requiredInputs: [
      input('positions[0].minimumHeadcount', { inputType: 'INTEGER', min: 1, max: 500 }),
      input('positions[0].healthCertificateRequired', { inputType: 'BOOLEAN' }),
      input('positions[0].note', { inputType: 'TEXT', nullable: true, maxLength: 500 }),
    ],
    evidence: [],
  }

  it('counts every unanswered or invalid input', () => {
    expect(missingDraftInputs(item, {}).length).toBe(3)
    const answers: DraftAnswers = {
      'positions[0].minimumHeadcount': val(0),
      'positions[0].healthCertificateRequired': val(false),
      'positions[0].note': val('   '),
    }
    expect(missingDraftInputs(item, answers).map((i) => i.path))
      .toEqual(['positions[0].minimumHeadcount', 'positions[0].note'])
    expect(allDraftInputsAnswered(item, { ...answers, 'positions[0].minimumHeadcount': { kind: 'empty' } })).toBe(false)
  })

  it('false is an answer; explicit empty becomes null; key set never grows', () => {
    const answers: DraftAnswers = {
      'positions[0].minimumHeadcount': val(2),
      'positions[0].healthCertificateRequired': val(false),
      'positions[0].note': { kind: 'empty' },
    }
    expect(allDraftInputsAnswered(item, answers)).toBe(true)
    const filled = buildFilledDraftRequest(item, answers)
    expect(filled).toEqual({ factoryId: 'F1', positions: [{ minimumHeadcount: 2, healthCertificateRequired: false, note: null }] })
    expect(() => buildFilledDraftRequest(item, {})).toThrow(/未填写/)
    const body = buildMaterializeBody(filled, 'digest-1', 'key-1')
    expect(Object.keys(body).sort()).toEqual(['confirmedPreviewDigest', 'factoryId', 'idempotencyKey', 'positions'])
  })

  it('merges the preview confirmation fields only into fields the request already has', () => {
    const request = { factoryId: 'F1', confirmedOperationDigest: null, routeProductCode: 'P-1' }
    const body = buildMaterializeBody(request, 'digest-1', 'key-1', {
      confirmedOperationDigest: 'op-1',
      notInRequest: 'x',
    })
    expect(body.confirmedOperationDigest).toBe('op-1')
    expect(body).not.toHaveProperty('notInRequest')
    expect(Object.keys(body).sort()).toEqual(
      ['confirmedOperationDigest', 'confirmedPreviewDigest', 'factoryId', 'idempotencyKey', 'routeProductCode'])
    // 预览摘要与幂等键始终以面板自己的为准, 确认字段不能覆盖它们
    const guarded = buildMaterializeBody({ factoryId: 'F1', confirmedPreviewDigest: null }, 'digest-1', 'key-1',
      { confirmedPreviewDigest: 'evil' })
    expect(guarded.confirmedPreviewDigest).toBe('digest-1')
  })

  it('splits evidence the blueprint mentioned but this draft does not carry', () => {
    const { carried, notCarried } = splitDraftEvidence([
      { path: 'a', value: 'true' },
      { path: 'b', value: '未带入：冷库温度' },
    ])
    expect(carried.map((e) => e.path)).toEqual(['a'])
    expect(notCarried.map((e) => e.path)).toEqual(['b'])
  })
})

describe('idempotency key: short, hashed, stable', () => {
  const parts = {
    actorType: 'FACTORY_USER' as const,
    actorId: 7,
    factoryId: 'F006',
    blueprintId: '3f2b9c1e-7a4d-4e8b-9c0a-1d2e3f4a5b6c',
    blueprintVersion: 3,
    sourceDigest: 'abcdef0123456789'.repeat(4),
    capabilityId: 'products',
    itemKey: 'P'.repeat(200),
  }
  // 独立的对照: 用 node:crypto 算同一段原文, 不调被测函数里的哈希
  const oracle = (p: typeof parts) => `bd:${p.capabilityId}:${createHash('sha256')
    .update(`draft:${p.actorType}:${p.actorId}:${p.factoryId}:${p.blueprintId}:${p.blueprintVersion}:${p.sourceDigest}:${p.capabilityId}:${p.itemKey}`)
    .digest('hex').slice(0, 40)}`

  it('stays within the backend limit for a UUID blueprint id and a 200-char itemKey', async () => {
    expect(draftIdempotencyMaterial(parts).length).toBeGreaterThan(IDEMPOTENCY_KEY_MAX_LENGTH)
    const key = await draftIdempotencyKey(parts)
    expect(key.length).toBeLessThanOrEqual(IDEMPOTENCY_KEY_MAX_LENGTH)
    expect(key).toMatch(/^bd:products:[0-9a-f]{40}$/)
    expect(key).toBe(oracle(parts))
  })

  it('is stable for equal inputs and differs when itemKey or the digest differs', async () => {
    expect(await draftIdempotencyKey({ ...parts })).toBe(await draftIdempotencyKey(parts))
    expect(await draftIdempotencyKey({ ...parts, itemKey: 'P-02' })).not.toBe(await draftIdempotencyKey(parts))
    expect(await draftIdempotencyKey({ ...parts, sourceDigest: 'ff' + parts.sourceDigest.slice(2) }))
      .not.toBe(await draftIdempotencyKey(parts))
  })

  it('refuses a missing part instead of hashing an incomplete string', async () => {
    await expect(draftIdempotencyKey({ ...parts, sourceDigest: '' })).rejects.toThrow(/sourceDigest/)
  })

  it('binds the new materialize key to the actual READY preview digest', async () => {
    const first = await draftPreviewIdempotencyKey(parts, 'preview-a')
    expect(first).toBe(await draftPreviewIdempotencyKey(parts, 'preview-a'))
    expect(first).not.toBe(await draftPreviewIdempotencyKey(parts, 'preview-b'))
    expect(await draftPreviewIdempotencyKey({ ...parts, actorType: 'PLATFORM_ADMIN' }, 'preview-a')).not.toBe(first)
    expect(draftPreviewIdempotencyMaterial(parts, 'preview-a')).toContain(':preview:preview-a')
  })

  it('refuses to derive a preview-bound key without a real preview digest', async () => {
    for (const digest of ['', '   ']) {
      await expect(draftPreviewIdempotencyKey(parts, digest)).rejects.toThrow(/previewDigest/)
    }
  })

  it('isolates same-number factory/platform identities and different operators', async () => {
    const factoryKey = await draftIdempotencyKey(parts)
    expect(await draftIdempotencyKey({ ...parts, actorType: 'PLATFORM_ADMIN' })).not.toBe(factoryKey)
    expect(await draftIdempotencyKey({ ...parts, actorId: 8 })).not.toBe(factoryKey)
    expect(await draftIdempotencyKey({ ...parts })).toBe(factoryKey)
  })

  it('refuses absent or invalid actor ids instead of sharing a fallback key', async () => {
    for (const actorId of [0, -1, Number.NaN, 1.5]) {
      await expect(draftIdempotencyKey({ ...parts, actorId })).rejects.toThrow(/身份/)
    }
  })
})

describe('who can confirm', () => {
  const factoryAdmin = { isFactory: true, currentRole: 'factory_super_admin', factoryId: 'F006' }
  it('only the factory super admin of this factory', () => {
    expect(canConfirmBlueprintDrafts(factoryAdmin, 'F006')).toBe(true)
    expect(canConfirmBlueprintDrafts({ ...factoryAdmin, factoryId: 'F001' }, 'F006')).toBe(false)
    expect(canConfirmBlueprintDrafts({ ...factoryAdmin, currentRole: 'workshop_supervisor' }, 'F006')).toBe(false)
    expect(canConfirmBlueprintDrafts({ isFactory: false, currentRole: 'super_admin', factoryId: '' }, 'F006')).toBe(false)
    expect(canConfirmBlueprintDrafts({ ...factoryAdmin, factoryId: '' }, '')).toBe(false)
  })
  it('allows verified platform super/system namespace and rejects role-only or mixed identities', () => {
    const platform = { isFactory: false, isPlatform: true, currentRole: 'platform_admin', platformRole: 'super_admin', factoryId: '' }
    expect(canConfirmBlueprintDrafts(platform, 'F006')).toBe(true)
    expect(canConfirmBlueprintDrafts({ ...platform, platformRole: 'platform_admin' }, 'F006')).toBe(false)
    expect(canConfirmBlueprintDrafts({ ...platform, platformRole: undefined }, 'F006')).toBe(false)
    expect(canConfirmBlueprintDrafts({ ...platform, platformRole: 'system_admin' }, 'F006')).toBe(false)
    expect(canConfirmBlueprintDrafts({ ...platform, platformRole: 'support_admin' }, 'F006')).toBe(false)
    expect(canConfirmBlueprintDrafts({ ...platform, isPlatform: false }, 'F006')).toBe(false)
    expect(canConfirmBlueprintDrafts({ ...platform, isFactory: true }, 'F006')).toBe(false)
    expect(canConfirmBlueprintDrafts(platform, '')).toBe(false)
  })
})

describe('row state derivation', () => {
  const row = (over: Partial<DraftWriteOrderRow> = {}): DraftWriteOrderRow => ({
    capabilityId: 'products', adapterMode: 'MODULE_DRAFT', entryOpen: true, proposalOpen: true,
    requiredPermission: 'p', writeDependencies: [], dependenciesWithoutOpenEntry: [], ...over,
  })
  const ctx = { canConfirm: true }
  const proposal = (status: 'READY_FOR_PREVIEW' | 'NEEDS_INPUT' | 'BLOCKED', inputs = 0) => ({
    status, sourceDigest: 'd', blockers: status === 'BLOCKED' ? [{ code: 'X', message: '缺模块草稿', nextStep: '先写模块配置草稿' }] : [],
    items: status === 'BLOCKED' ? [] : [{ itemKey: 'i1', label: 'L', status, request: {}, evidence: [],
      requiredInputs: Array.from({ length: inputs }, (_, k) => input(`f${k}`)) }],
  })

  it('covers every branch in priority order', () => {
    expect(deriveDraftRowState(row({ adapterMode: 'READ_ONLY' }), ctx).kind).toBe('READ_ONLY')
    expect(deriveDraftRowState(row({ entryOpen: false }), ctx).label).toBe('入口未开放')
    expect(deriveDraftRowState(row({ dependenciesWithoutOpenEntry: ['workflow'] }), { ...ctx, labelOf: () => '工艺路线' }).label)
      .toBe('依赖「工艺路线」入口未开放')
    const noProposal = deriveDraftRowState(row({ proposalOpen: false }), ctx)
    expect([noProposal.label, noProposal.detail, noProposal.actionable]).toEqual(['无预填', '需手工填写，暂未提供', false])
    expect(deriveDraftRowState(row(), { canConfirm: false }).detail).toBe('草稿需由本厂工厂超级管理员确认写入')
    expect(deriveDraftRowState(row(), ctx).kind).toBe('LOADING')
    expect(deriveDraftRowState(row(), { ...ctx, proposalError: '后端原文' }).detail).toBe('后端原文')
    const blocked = deriveDraftRowState(row(), { ...ctx, proposal: proposal('BLOCKED') })
    expect([blocked.label, blocked.detail]).toEqual(['阻塞', '缺模块草稿（下一步：先写模块配置草稿）'])
    expect(deriveDraftRowState(row(), { ...ctx, proposal: proposal('NEEDS_INPUT', 3) }).label).toBe('待填 3 项')
    expect(deriveDraftRowState(row(), { ...ctx, proposal: proposal('NEEDS_INPUT', 3), missingByItem: { i1: 1 } }).label).toBe('待填 1 项')
    expect(deriveDraftRowState(row(), { ...ctx, proposal: proposal('NEEDS_INPUT', 3), missingByItem: { i1: 0 } }).label).toBe('可预览')
    expect(deriveDraftRowState(row(), { ...ctx, proposal: proposal('READY_FOR_PREVIEW') }).label).toBe('可预览')
  })

  it('written label: unsupported is never shown as 未写入, absent capability shows nothing', () => {
    expect(deriveWrittenLabel({ capabilityId: 'x', supported: false, drafts: [] })?.label).toBe('暂无法列出已写入')
    expect(deriveWrittenLabel({ capabilityId: 'x', supported: true, drafts: [] })?.label).toBe('未写入')
    expect(deriveWrittenLabel({ capabilityId: 'x', supported: true, drafts: [
      { draftId: '1', naturalKey: 'k', label: 'l', status: 'DRAFT', desiredDigest: 'd', createdAt: 'now' },
    ] })?.label).toBe('已写入 1 份')
    expect(deriveWrittenLabel(undefined)).toBeNull()
  })
})

describe('error text and field targeting', () => {
  it('prefers the backend code/message carried on 403/404', () => {
    const notFound = Object.assign(new Error('请求的接口不存在'), {
      code: 'NOT_FOUND', backendErrorCode: 'BLUEPRINT_DRAFT_READBACK_UNSUPPORTED', backendMessage: '这类草稿不支持回读',
    })
    expect(draftErrorCode(notFound)).toBe('BLUEPRINT_DRAFT_READBACK_UNSUPPORTED')
    expect(draftErrorMessage(notFound)).toBe('这类草稿不支持回读')
    const badRequest = Object.assign(new Error('还有必填项没填：desired.unit'), { code: 'BLUEPRINT_DRAFT_REQUIRED_INPUT_MISSING' })
    expect(draftErrorCode(badRequest)).toBe('BLUEPRINT_DRAFT_REQUIRED_INPUT_MISSING')
    expect(draftErrorMessage(badRequest)).toBe('还有必填项没填：desired.unit')
  })

  it('maps a field-bearing 400 to the longest matching required input path', () => {
    const item: DraftProposalItem = { itemKey: 'k', label: 'L', status: 'NEEDS_INPUT', request: {}, evidence: [],
      requiredInputs: [input('positions'), input('positions[0].department'), input('positions[1].department')] }
    const err = (code: string, message: string) => Object.assign(new Error(message), { code })
    const paths = item.requiredInputs.map((i) => i.path)
    expect(draftErrorInputPath(err('BLUEPRINT_DRAFT_INPUT_INVALID', '字段 positions[0].department 的值不在可选范围内'), paths))
      .toBe('positions[0].department')
    expect(draftErrorInputPath(err('BLUEPRINT_DRAFT_REQUIRED_INPUT_MISSING', '还有必填项没填：positions[1].department.'), paths))
      .toBe('positions[1].department')
    expect(draftErrorInputPath(err('BLUEPRINT_DRAFT_UNKNOWN_FIELD', '请求里有未知字段：positions'), paths)).toBe('positions')
    // 不是字段类错误码 / 路径不在待填项里: 不高亮
    expect(draftErrorInputPath(err('BLUEPRINT_DRAFT_BODY_INVALID', 'positions[0].department'), paths)).toBeNull()
    expect(draftErrorInputPath(err('BLUEPRINT_DRAFT_INPUT_INVALID', '字段 positions[0].title 太长'), paths)).toBeNull()
  })
})

describe('draftBoundText', () => {
  it('writes a bound like 0.00000001 / 1000000000000 as plain digits, never as 1e-8 / 1e+12', () => {
    const text = draftBoundText(specOf({ inputType: 'DECIMAL', min: 0.00000001, max: 1000000000000 }))
    expect(text).toBe('范围：0.00000001–1000000000000')
    expect(text).not.toMatch(/e[-+]?\d/i)
  })

  it('keeps ordinary bounds as they were', () => {
    expect(draftBoundText(specOf({ inputType: 'INTEGER', min: 1, max: 500 }))).toBe('范围：1–500（整数）')
    expect(draftBoundText(specOf({ inputType: 'DECIMAL', min: 0.5, max: null }))).toBe('范围：不小于 0.5')
  })
})
