import { createHash } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import {
  canExecutePromotion,
  indexPromotedRecords,
  isPromotable,
  promotionActionLabel,
  promotionConfirmText,
  promotionEffectText,
  promotionErrorNextStep,
  promotionExecuteBody,
  promotionFieldRows,
  promotionIdempotencyKey,
  promotionIdempotencyMaterial,
  LIVE_STORAGE_TYPE_LABELS,
} from '../factoryBlueprintPromotion'
import { draftIdempotencyKey } from '../factoryBlueprintDrafts'

const parts = {
  factoryId: 'F006', blueprintId: '3f2b9c1e-7a4d-4e8b-9c0a-1d2e3f4a5b6c', blueprintVersion: 3,
  sourceDigest: 'ab'.repeat(32), capabilityId: 'materials', draftId: 'd'.repeat(36),
}

describe('promotion wording', () => {
  it('pins the live storage dictionary codes and labels used by promoted materials', () => {
    expect(LIVE_STORAGE_TYPE_LABELS).toEqual({ frozen: '冻货', fresh: '新鲜', normal: '常温' })
    expect(promotionFieldRows('materials', { storageType: 'frozen' })[0]?.value).toBe('冻货（frozen）')
  })

  it('names the action and the exact effect; never a bare 确认', () => {
    expect(['products', 'materials', 'processes'].map(promotionActionLabel))
      .toEqual(['转正为正式产品', '转正为正式物料', '转正为正式工序'])
    expect(promotionEffectText('processes')).toBe('将在正式工序表新建 1 条')
    expect(promotionConfirmText('materials', '', 'F006', '猪蹄原料'))
      .toBe('转正为正式物料 — F006（F006）· 猪蹄原料：将写入正式物料表，写入后不能在这里撤销')
    expect(isPromotable('accounts')).toBe(false)
  })

  it('field rows: Chinese labels for known keys, booleans and enum values readable, unknown keys kept', () => {
    expect(promotionFieldRows('materials', { name: '猪蹄', storageType: 'FROZEN', isByproduct: false, shelfLifeDays: null, extra: 1 }))
      .toEqual([
        { key: 'name', label: '物料名称', value: '猪蹄' },
        { key: 'storageType', label: '正式储存代码', value: '冷冻（FROZEN）' },
        { key: 'isByproduct', label: '是否加工副产物', value: '否' },
        { key: 'shelfLifeDays', label: '保质期（天）', value: '（空）' },
        { key: 'extra', label: 'extra', value: '1' },
      ])
    expect(promotionFieldRows('materials', { sourceStorageMode: 'REFRIGERATED', storageType: 'fresh' }))
      .toEqual([
        { key: 'sourceStorageMode', label: '草稿储存方式', value: 'REFRIGERATED' },
        { key: 'storageType', label: '正式储存代码', value: '冷藏（正式存储代码 fresh/字典新鲜）' },
      ])
    expect(promotionFieldRows('products', null)).toEqual([])
  })
})

describe('promotion key, body and gate', () => {
  it('fixed key: bp: + capability + 40 hex of SHA-256 over the six parts, ≤128, differs from the draft key', async () => {
    const key = await promotionIdempotencyKey(parts)
    const oracle = `bp:materials:${createHash('sha256').update(promotionIdempotencyMaterial(parts)).digest('hex').slice(0, 40)}`
    expect(key).toBe(oracle)
    expect(promotionIdempotencyMaterial(parts)).toContain(parts.draftId)
    expect(key.length).toBeLessThanOrEqual(128)
    expect(await promotionIdempotencyKey({ ...parts })).toBe(key)
    expect(await promotionIdempotencyKey({ ...parts, draftId: 'other' })).not.toBe(key)
    const draftKey = await draftIdempotencyKey({ actorType: 'FACTORY_USER', actorId: 7, factoryId: 'F006', blueprintId: parts.blueprintId, blueprintVersion: 3,
      sourceDigest: parts.sourceDigest, capabilityId: 'materials', itemKey: parts.draftId })
    expect(draftKey.startsWith('bd:')).toBe(true)
    await expect(promotionIdempotencyKey({ ...parts, sourceDigest: '' })).rejects.toThrow(/sourceDigest/)
  })

  it('execute body has exactly the digest and the key', () => {
    expect(promotionExecuteBody('pd', 'k')).toEqual({ confirmedPromotionDigest: 'pd', idempotencyKey: 'k' })
  })

  it('executable only when READY, no blockers, digest present, key ready, not stale', () => {
    const ready = { status: 'READY', action: 'CREATE', capabilityId: 'products', draftId: 'd', naturalKey: 'P',
      after: {}, blockers: [], promotionPreviewDigest: 'pd' }
    expect(canExecutePromotion(ready, 'k', false)).toBe(true)
    expect(canExecutePromotion({ ...ready, status: 'BLOCKED' }, 'k', false)).toBe(false)
    expect(canExecutePromotion({ ...ready, blockers: [{ code: 'X', message: 'm', nextStep: 'n' }] }, 'k', false)).toBe(false)
    expect(canExecutePromotion({ ...ready, promotionPreviewDigest: null }, 'k', false)).toBe(false)
    expect(canExecutePromotion(ready, '', false)).toBe(false)
    expect(canExecutePromotion(ready, 'k', true)).toBe(false)
    expect(canExecutePromotion(null, 'k', false)).toBe(false)
  })

  it('only PROMOTED records count as promoted; next step read from actionHint', () => {
    const m = indexPromotedRecords([
      { promotionId: '1', capabilityId: 'products', draftId: 'a', status: 'PROMOTED', action: 'CREATE', naturalKey: 'A', liveTargetId: 'L1' },
      { promotionId: '2', capabilityId: 'products', draftId: 'b', status: 'CLAIMED', action: 'CREATE', naturalKey: 'B', liveTargetId: null },
    ])
    expect([...m.keys()]).toEqual(['products:a'])
    expect(promotionErrorNextStep({ actionHint: '重新预览' })).toBe('重新预览')
    expect(promotionErrorNextStep(new Error('x'))).toBe('')
  })
})
