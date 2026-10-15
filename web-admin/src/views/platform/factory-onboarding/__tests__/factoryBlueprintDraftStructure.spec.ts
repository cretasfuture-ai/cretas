import { describe, expect, it } from 'vitest'
import type { DraftProposalItem, DraftRequiredInput } from '@/api/factoryBlueprintDraftApi'
import {
  buildFilledDraftRequest,
  draftBoundText,
  draftConstraintText,
  draftFillSummary,
  draftSubFieldReason,
  draftErrorInputPath,
  draftFieldPaths,
  draftInputSpec,
  draftMissingDetails,
  draftRowBoundText,
  draftSerializeStruct,
  draftStructAddRow,
  draftStructCanAddRow,
  draftStructCanRemoveRow,
  draftStructFromValue,
  draftStructMissing,
  draftStructRemoveRow,
  draftStructSetAnswer,
  type DraftAnswers,
  type DraftInputSpec,
  type DraftStructState,
} from '../factoryBlueprintDrafts'

const field = (path: string, over: Partial<DraftRequiredInput> = {}): DraftRequiredInput => ({
  path, label: path, reason: '', constraint: '', inputType: 'TEXT', options: [], nullable: false,
  min: null, max: null, maxLength: null, structureKind: null, itemFields: [], minItems: null, maxItems: null,
  itemLabel: null, ...over,
})

const certificates = () => field('certificates', {
  label: '证书', inputType: 'STRUCTURE', structureKind: 'LIST', itemLabel: '证书', minItems: 0, maxItems: 2,
  itemFields: [field('name', { label: '证书名称' }), field('expiresOn', { label: '到期日', inputType: 'DATE', nullable: true })],
})

const positions = (over: Partial<DraftRequiredInput> = {}) => field('positions', {
  label: '岗位', inputType: 'STRUCTURE', structureKind: 'LIST', itemLabel: '岗位', minItems: 1, maxItems: 3,
  itemFields: [
    field('positionCode', { label: '岗位编号' }),
    field('minimumHeadcount', { label: '最少在岗人数', inputType: 'INTEGER', min: 1, max: 500 }),
    field('department', { label: '部门', inputType: 'ENUM',
      options: [{ value: 'QUALITY', label: '质量' }, { value: 'PROCESSING', label: '加工' }] }),
    certificates(),
  ],
  ...over,
})

const decision = () => field('decision', {
  label: '判定树', inputType: 'STRUCTURE', structureKind: 'OBJECT',
  itemFields: [field('q1', { label: '有控制措施', inputType: 'BOOLEAN' }), field('q2', { label: '专为消除危害', inputType: 'BOOLEAN' })],
})

const specOf = (input: DraftRequiredInput): DraftInputSpec => {
  const r = draftInputSpec(input)
  if (!r.ok) throw new Error(r.error)
  return r.spec
}
const val = (value: unknown) => ({ kind: 'value' as const, value })

function rowAt(state: DraftStructState, i: number) {
  if (state.kind === 'OBJECT') throw new Error('not a list')
  return state.rows[i]
}

describe('STRUCTURE spec is a contract: kind + itemFields, single-segment sub-paths, recursive', () => {
  it('accepts a well-formed nested LIST/OBJECT', () => {
    const s = specOf(positions())
    expect([s.structureKind, s.itemFields.length, s.minItems, s.maxItems, s.itemLabel]).toEqual(['LIST', 4, 1, 3, '岗位'])
    expect(specOf(decision()).structureKind).toBe('OBJECT')
  })

  it('an older backend STRUCTURE (no structureKind / no itemFields) is a contract error, not a JSON box', () => {
    for (const bad of [
      field('p', { inputType: 'STRUCTURE', itemFields: [field('a')] }),
      field('p', { inputType: 'STRUCTURE', structureKind: 'LIST', itemFields: [] }),
      field('p', { inputType: 'STRUCTURE', structureKind: 'LIST', itemFields: null }),
    ]) {
      const r = draftInputSpec(bad)
      expect(r.ok).toBe(false)
    }
    expect(draftInputSpec(field('p', { inputType: 'STRUCTURE', itemFields: [field('a')] })).error).toContain('structureKind')
    expect(draftInputSpec(field('p', { inputType: 'STRUCTURE', structureKind: 'OBJECT' })).error).toContain('itemFields')
  })

  it('rejects bad / duplicate sub-paths, a broken sub-field, and minItems > maxItems', () => {
    const list = (itemFields: DraftRequiredInput[], over: Partial<DraftRequiredInput> = {}) =>
      draftInputSpec(field('p', { inputType: 'STRUCTURE', structureKind: 'LIST', itemFields, ...over }))
    expect(list([field('a.b')]).error).toContain('不合法')
    expect(list([field('a[0]')]).error).toContain('不合法')
    expect(list([field('a'), field('a')]).error).toContain('重复')
    expect(list([field('a', { inputType: undefined })]).error).toContain('inputType')
    expect(list([field('a', { inputType: 'STRUCTURE', structureKind: 'LIST', itemFields: [] })]).error).toContain('itemFields')
    expect(list([field('a')], { minItems: 3, maxItems: 2 }).error).toContain('大于上限')
  })

  it('shows the row bounds up front', () => {
    expect(draftRowBoundText(specOf(positions()))).toBe('至少 1 行，至多 3 行')
    expect(draftRowBoundText(specOf(certificates()))).toBe('至多 2 行，可以不填')
    expect(draftRowBoundText(specOf(positions({ minItems: 2, maxItems: null })))).toBe('至少 2 行')
  })
})

describe('initial state', () => {
  it('null → minItems empty rows (≥1) or zero rows; OBJECT → empty object', () => {
    expect((draftStructFromValue(specOf(positions()), null) as { rows: unknown[] }).rows).toHaveLength(1)
    expect((draftStructFromValue(specOf(positions({ minItems: 2 })), null) as { rows: unknown[] }).rows).toHaveLength(2)
    expect((draftStructFromValue(specOf(certificates()), null) as { rows: unknown[] }).rows).toHaveLength(0)
    const obj = draftStructFromValue(specOf(decision()), null)
    expect(obj.kind).toBe('OBJECT')
    // 新行里的嵌套 LIST 也初始化好(证书 minItems 0 → 0 行)
    const firstRow = rowAt(draftStructFromValue(specOf(positions()), null), 0)
    expect(firstRow.answers.certificates).toEqual({ kind: 'value', value: { kind: 'LIST', rows: [] } })
  })

  it('a prefilled array becomes rows; null cells stay unanswered', () => {
    const s = draftStructFromValue(specOf(positions()), [
      { positionCode: 'P-QA', minimumHeadcount: null, department: 'QUALITY', certificates: [{ name: '健康证', expiresOn: null }] },
      { positionCode: 'P-OP', minimumHeadcount: 2, department: null, certificates: null },
    ])
    expect(s.kind === 'LIST' && s.rows.length).toBe(2)
    expect(rowAt(s, 0).answers.positionCode).toEqual(val('P-QA'))
    expect(rowAt(s, 0).answers.minimumHeadcount).toBeUndefined()
    const certs = rowAt(s, 0).answers.certificates as { value: DraftStructState }
    expect(rowAt(certs.value, 0).answers.name).toEqual(val('健康证'))
    expect(rowAt(s, 1).answers.minimumHeadcount).toEqual(val(2))
  })
})

describe('row bounds', () => {
  const spec = specOf(positions())
  it('add is refused at maxItems with a reason, remove at minItems with a reason', () => {
    let s = draftStructFromValue(spec, null)
    expect(draftStructCanRemoveRow(spec, s)).toEqual({ ok: false, reason: '至少要保留 1 行' })
    expect(draftStructRemoveRow(spec, s, rowAt(s, 0).id)).toBe(s)
    s = draftStructAddRow(spec, draftStructAddRow(spec, s))
    expect(s.kind === 'LIST' && s.rows.length).toBe(3)
    expect(draftStructCanAddRow(spec, s)).toEqual({ ok: false, reason: '最多 3 行，已经满了' })
    expect(draftStructAddRow(spec, s)).toBe(s)
    expect(draftStructCanRemoveRow(spec, s).ok).toBe(true)
    const removed = draftStructRemoveRow(spec, s, rowAt(s, 1).id)
    expect(removed.kind === 'LIST' && removed.rows.map((r) => r.id)).toEqual([rowAt(s, 0).id, rowAt(s, 2).id])
  })
})

describe('validation names every unfilled place; serialization keeps only sub-field keys', () => {
  const item = (): DraftProposalItem => ({
    itemKey: 'accounts', label: '岗位', status: 'NEEDS_INPUT', evidence: [],
    request: { factoryId: 'F006', positions: null, decision: null },
    requiredInputs: [positions(), decision()],
  })

  function filledAnswers(): DraftAnswers {
    const pSpec = specOf(positions())
    const cSpec = specOf(certificates())
    let certs = draftStructFromValue(cSpec, null)
    certs = draftStructAddRow(cSpec, draftStructAddRow(cSpec, certs))
    certs = draftStructSetAnswer(certs, rowAt(certs, 0).id, 'name', val('健康证'))
    certs = draftStructSetAnswer(certs, rowAt(certs, 0).id, 'expiresOn', { kind: 'empty' })
    certs = draftStructSetAnswer(certs, rowAt(certs, 1).id, 'name', val('HACCP'))
    certs = draftStructSetAnswer(certs, rowAt(certs, 1).id, 'expiresOn', val('2027-01-01'))
    let p = draftStructFromValue(pSpec, null)
    const id = rowAt(p, 0).id
    p = draftStructSetAnswer(p, id, 'positionCode', val('P-QA'))
    p = draftStructSetAnswer(p, id, 'minimumHeadcount', val(2))
    p = draftStructSetAnswer(p, id, 'department', val('QUALITY'))
    p = draftStructSetAnswer(p, id, 'certificates', val(certs))
    let d = draftStructFromValue(specOf(decision()), null)
    d = draftStructSetAnswer(d, null, 'q1', val(true))
    d = draftStructSetAnswer(d, null, 'q2', val(false))
    return { positions: val(p), decision: val(d) }
  }

  it('a fresh item names each missing sub-field by row', () => {
    const it0 = item()
    const answers: DraftAnswers = {
      positions: val(draftStructFromValue(specOf(positions()), null)),
      decision: val(draftStructFromValue(specOf(decision()), null)),
    }
    expect(draftMissingDetails(it0, answers).map((m) => m.label)).toEqual([
      '岗位 1 · 岗位编号', '岗位 1 · 最少在岗人数', '岗位 1 · 部门', '判定树 · 有控制措施', '判定树 · 专为消除危害',
    ])
  })

  it('nested rows and row-count shortfalls are named too; a filled item has nothing missing', () => {
    const answers = filledAnswers()
    expect(draftMissingDetails(item(), answers)).toEqual([])
    // 第 2 个岗位只填了编号; 第 1 个岗位的第 2 张证书名称清空
    const pSpec = specOf(positions())
    let p = (answers.positions as { value: DraftStructState }).value
    p = draftStructAddRow(pSpec, p)
    p = draftStructSetAnswer(p, rowAt(p, 1).id, 'positionCode', val('P-OP'))
    const certs = (rowAt(p, 0).answers.certificates as { value: DraftStructState }).value
    p = draftStructSetAnswer(p, rowAt(p, 0).id, 'certificates',
      val(draftStructSetAnswer(certs, rowAt(certs, 1).id, 'name', undefined)))
    const missing = draftMissingDetails(item(), { ...answers, positions: val(p) })
    expect(missing.map((m) => [m.path, m.label])).toEqual([
      ['positions[0].certificates[1].name', '岗位 1 · 证书 2 · 证书名称'],
      ['positions[1].minimumHeadcount', '岗位 2 · 最少在岗人数'],
      ['positions[1].department', '岗位 2 · 部门'],
    ])
    // 行数不够也算一处
    const short = draftMissingDetails(item(), { ...answers, positions: val({ kind: 'LIST', rows: [] }) })
    expect(short[0]).toMatchObject({ path: 'positions', label: '岗位', problem: '至少 1 行，现在 0 行' })
  })

  it('serializes LIST-in-LIST and OBJECT exactly, written back to the top-level paths', () => {
    const request = buildFilledDraftRequest(item(), filledAnswers())
    expect(request).toEqual({
      factoryId: 'F006',
      positions: [{
        positionCode: 'P-QA', minimumHeadcount: 2, department: 'QUALITY',
        certificates: [{ name: '健康证', expiresOn: null }, { name: 'HACCP', expiresOn: '2027-01-01' }],
      }],
      decision: { q1: true, q2: false },
    })
    expect(Object.keys((request.positions as Array<Record<string, unknown>>)[0]).sort())
      .toEqual(['certificates', 'department', 'minimumHeadcount', 'positionCode'])
  })

  it('refuses to build while any sub-field is unfilled', () => {
    const answers = filledAnswers()
    const p = (answers.positions as { value: DraftStructState }).value
    const broken = draftStructSetAnswer(p, rowAt(p, 0).id, 'minimumHeadcount', val(0))
    expect(() => buildFilledDraftRequest(item(), { ...answers, positions: val(broken) })).toThrow(/没填好/)
  })

  it('keeps prefilled row keys that are not sub-fields untouched (never invents new ones)', () => {
    const spec = specOf(certificates())
    const s = draftStructFromValue(spec, [{ name: '健康证', expiresOn: null, issuer: '卫健委' }])
    expect(draftSerializeStruct(spec, s)).toEqual([{ issuer: '卫健委', name: '健康证', expiresOn: null }])
  })

  it('field paths reach nested rows so a 400 can name positions[1].minimumHeadcount', () => {
    const answers = filledAnswers()
    const pSpec = specOf(positions())
    const p = draftStructAddRow(pSpec, (answers.positions as { value: DraftStructState }).value)
    const paths = draftFieldPaths(item(), { ...answers, positions: val(p) })
    expect(paths).toContain('positions[1].minimumHeadcount')
    expect(paths).toContain('positions[0].certificates[1].name')
    expect(paths).toContain('decision.q2')
    const e = Object.assign(new Error('字段 positions[1].minimumHeadcount 不能小于 1'), { code: 'BLUEPRINT_DRAFT_INPUT_INVALID' })
    expect(draftErrorInputPath(e, paths)).toBe('positions[1].minimumHeadcount')
  })
})

describe('VALUES: a list of plain values (not objects)', () => {
  const optionCodes = (path: string, over: Partial<DraftRequiredInput> = {}) => field(path, {
    label: '选项编码', inputType: 'STRUCTURE', structureKind: 'VALUES', itemLabel: '选项', minItems: 1, maxItems: 3,
    itemFields: [field('optionCodes', { label: '选项编码', maxLength: 40, constraint: '大写编码' })],
    ...over,
  })
  const fieldsList = () => field('fields', {
    label: '字段', inputType: 'STRUCTURE', structureKind: 'LIST', itemLabel: '字段', minItems: 1, maxItems: 5,
    itemFields: [field('fieldKey', { label: '字段标识' }), optionCodes('optionCodes', { minItems: 0, maxItems: 50, nullable: true })],
  })

  it('contract: exactly one itemField whose path is the list\'s own field name; never a nested structure', () => {
    expect(draftInputSpec(optionCodes('forms[0].fields[1].optionCodes')).ok).toBe(true)
    expect(draftInputSpec(optionCodes('optionCodes')).ok).toBe(true)
    expect(draftInputSpec(optionCodes('forms[0].fields[1].optionCodes', {
      itemFields: [field('optionCodes'), field('extra')] })).error).toContain('恰好 1 条')
    expect(draftInputSpec(optionCodes('forms[0].fields[1].optionCodes', {
      itemFields: [field('code')] })).error).toContain('应为「optionCodes」')
    expect(draftInputSpec(optionCodes('optionCodes', {
      itemFields: [field('optionCodes', { inputType: 'STRUCTURE', structureKind: 'LIST', itemFields: [field('a')] })] })).error)
      .toContain('不能再是结构')
    expect(draftRowBoundText(specOf(optionCodes('optionCodes')))).toBe('至少 1 行，至多 3 行')
  })

  it('prefill / initial rows, bounds, and serialization to a plain array', () => {
    const spec = specOf(optionCodes('forms[0].fields[1].optionCodes'))
    const pre = draftStructFromValue(spec, ['HIGH', 'LOW'])
    expect(pre.kind).toBe('VALUES')
    expect(draftSerializeStruct(spec, pre)).toEqual(['HIGH', 'LOW'])
    let s = draftStructFromValue(spec, null)
    expect(s.kind === 'VALUES' && s.rows.length).toBe(1)
    expect(draftStructCanRemoveRow(spec, s)).toEqual({ ok: false, reason: '至少要保留 1 行' })
    s = draftStructAddRow(spec, draftStructAddRow(spec, s))
    expect(draftStructCanAddRow(spec, s)).toEqual({ ok: false, reason: '最多 3 行，已经满了' })
    expect(draftStructAddRow(spec, s)).toBe(s)
    s = draftStructSetAnswer(s, rowAt(s, 0).id, 'optionCodes', val('A'))
    s = draftStructSetAnswer(s, rowAt(s, 2).id, 'optionCodes', val('C'))
    expect(draftSerializeStruct(spec, s)).toEqual(['A', null, 'C'])
    const missing = draftStructMissing(spec, s, 'forms[0].fields[1].optionCodes', '选项编码', null)
    expect(missing.map((m) => [m.path, m.label])).toEqual([['forms[0].fields[1].optionCodes[1]', '选项 2']])
  })

  it('a top-level VALUES is written back as a plain array; field paths name each value', () => {
    const it0: DraftProposalItem = {
      itemKey: 'forms', label: '表单', status: 'NEEDS_INPUT', evidence: [],
      request: { forms: [{ fields: [{ optionCodes: null }, { optionCodes: null }] }] },
      requiredInputs: [optionCodes('forms[0].fields[1].optionCodes')],
    }
    const spec = specOf(it0.requiredInputs[0])
    let s = draftStructAddRow(spec, draftStructFromValue(spec, null))
    s = draftStructSetAnswer(s, rowAt(s, 0).id, 'optionCodes', val('HIGH'))
    s = draftStructSetAnswer(s, rowAt(s, 1).id, 'optionCodes', val('LOW'))
    const answers: DraftAnswers = { 'forms[0].fields[1].optionCodes': val(s) }
    expect(draftMissingDetails(it0, answers)).toEqual([])
    expect(buildFilledDraftRequest(it0, answers)).toEqual({
      forms: [{ fields: [{ optionCodes: null }, { optionCodes: ['HIGH', 'LOW'] }] }],
    })
    expect(draftFieldPaths(it0, answers)).toContain('forms[0].fields[1].optionCodes[1]')
  })

  it('VALUES nested inside a LIST row: 0 rows → [], explicit empty → null, values → plain array', () => {
    const spec = specOf(fieldsList())
    let s = draftStructFromValue(spec, null)
    const id = rowAt(s, 0).id
    s = draftStructSetAnswer(s, id, 'fieldKey', val('temp'))
    expect(draftSerializeStruct(spec, s)).toEqual([{ fieldKey: 'temp', optionCodes: [] }])
    const ocSpec = specOf(spec.itemFields[1])
    let oc = draftStructAddRow(ocSpec, draftStructFromValue(ocSpec, null))
    oc = draftStructAddRow(ocSpec, oc)
    oc = draftStructSetAnswer(oc, rowAt(oc, 0).id, 'optionCodes', val('HIGH'))
    oc = draftStructSetAnswer(oc, rowAt(oc, 1).id, 'optionCodes', val('LOW'))
    const withValues = draftStructSetAnswer(s, id, 'optionCodes', val(oc))
    expect(draftSerializeStruct(spec, withValues)).toEqual([{ fieldKey: 'temp', optionCodes: ['HIGH', 'LOW'] }])
    expect(draftSerializeStruct(spec, draftStructSetAnswer(s, id, 'optionCodes', { kind: 'empty' })))
      .toEqual([{ fieldKey: 'temp', optionCodes: null }])
    // 嵌套的空值被逐处命名
    const blank = draftStructSetAnswer(s, id, 'optionCodes', val(draftStructAddRow(ocSpec, draftStructFromValue(ocSpec, null))))
    expect(draftStructMissing(spec, blank, 'forms[0].fields', '字段', null).map((m) => [m.path, m.label]))
      .toEqual([['forms[0].fields[0].optionCodes[0]', '字段 1 · 选项 1']])
  })
})

describe('readable text: enum labels in constraints, deduped reasons, consistent fill summary', () => {
  it('replaces whole-token option values with labels, longest first, never inside a longer word', () => {
    const options = [
      { value: 'COLD', label: '冷' },
      { value: 'COLD_CHAIN_VEHICLE', label: '冷藏车' },
      { value: 'FREEZER', label: '冷冻库' },
    ]
    expect(draftConstraintText('FREEZER、COLD_CHAIN_VEHICLE、COLD 之一（COLD_CHAIN_VEHICLE 可不填所在仓库；COLDROOM 不是选项）', options))
      .toBe('冷冻库、冷藏车、冷 之一（冷藏车 可不填所在仓库；COLDROOM 不是选项）')
    expect(draftConstraintText('至少选 1 项，可选：FOOD_SAFETY_BASIC、HACCP_AWARENESS', [
      { value: 'FOOD_SAFETY_BASIC', label: '食品安全基础' }, { value: 'HACCP_AWARENESS', label: 'HACCP 意识' },
    ])).toBe('至少选 1 项，可选：食品安全基础、HACCP 意识')
    // 值里带正则字符也只按字面换; 没有选项时原样返回
    expect(draftConstraintText('a.b 或 aXb', [{ value: 'a.b', label: '甲' }])).toBe('甲 或 aXb')
    expect(draftConstraintText('1–500 人', [])).toBe('1–500 人')
    expect(draftConstraintText('1–500 人', null)).toBe('1–500 人')
  })

  it('a sub-field reason equal to the parent reason is not repeated', () => {
    expect(draftSubFieldReason('建厂访谈没有问温度和冷链控制', '建厂访谈没有问温度和冷链控制')).toBe('')
    expect(draftSubFieldReason(' 建厂访谈没有问温度和冷链控制 ', '建厂访谈没有问温度和冷链控制')).toBe('')
    expect(draftSubFieldReason('访谈给的编号不合规', '建厂访谈没有问温度和冷链控制')).toBe('访谈给的编号不合规')
    expect(draftSubFieldReason('访谈没有问', null)).toBe('访谈没有问')
    expect(draftSubFieldReason('', '外层')).toBe('')
  })

  it('the row bound is not part of a STRUCTURE field bound (the editor shows it once)', () => {
    expect(draftBoundText(specOf(positions()))).toBe('')
    expect(draftRowBoundText(specOf(positions()))).toBe('至少 1 行，至多 3 行')
  })

  it('an optional empty list does not count as 需补充', () => {
    const cold = field('coldControls', {
      label: '冷链控制点', inputType: 'STRUCTURE', structureKind: 'LIST', itemLabel: '控制点', minItems: 0, maxItems: null,
      itemFields: [field('code', { label: '编码' })],
    })
    const it0: DraftProposalItem = {
      itemKey: 'warehouses', label: '仓库', status: 'NEEDS_INPUT', evidence: [],
      request: { name: null, coldControls: null }, requiredInputs: [field('name', { label: '名称' }), cold],
    }
    const empty = draftStructFromValue(specOf(cold), null)
    expect(draftFillSummary(it0, { coldControls: val(empty) })).toEqual({ blocking: ['名称'], optionalEmpty: ['冷链控制点（0 行）'] })
    expect(draftFillSummary(it0, { name: val('原料库'), coldControls: val(empty) }))
      .toEqual({ blocking: [], optionalEmpty: ['冷链控制点（0 行）'] })
    // 加了一行但没填: 它就挡着预览了
    const oneRow = draftStructAddRow(specOf(cold), empty)
    expect(draftFillSummary(it0, { name: val('原料库'), coldControls: val(oneRow) }))
      .toEqual({ blocking: ['冷链控制点'], optionalEmpty: [] })
  })
})
