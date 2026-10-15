/**
 * 草稿「转正」的纯函数: 哪些能力能转正、给人看的名词与字段名、固定幂等键、执行请求体、
 * 能不能确认、确认框文案、已转正判定。不碰组件, 全部可单测。
 */
import type {
  PromotableCapability,
  PromotionExecuteBody,
  PromotionPreview,
  PromotionRecord,
} from '@/api/factoryBlueprintPromotionApi'
import { hashedIdempotencyKey } from './factoryBlueprintDrafts'

export const PROMOTABLE_CAPABILITIES: readonly PromotableCapability[] = ['products', 'materials', 'processes', 'bom']

export function isPromotable(capabilityId: string): capabilityId is PromotableCapability {
  return (PROMOTABLE_CAPABILITIES as readonly string[]).includes(capabilityId)
}

const NOUN: Record<PromotableCapability, string> = { products: '产品', materials: '物料', processes: '工序', bom: '配方' }

/** 「产品 / 物料 / 工序」 */
export function promotionNoun(capabilityId: string): string {
  return isPromotable(capabilityId) ? NOUN[capabilityId] : capabilityId
}

/** 按钮文字必须说清动作: 「转正为正式产品」, ⛔ 不是「确认」 */
export function promotionActionLabel(capabilityId: string, action: unknown = 'CREATE'): string {
  if (action === 'UPDATE') return `应用修改到正式${promotionNoun(capabilityId)}`
  return `转正为正式${promotionNoun(capabilityId)}`
}

/** 预览卡片的效果句: 「将在正式产品表新建 1 条」 */
export function promotionEffectText(capabilityId: string, action = 'CREATE'): string {
  if (action === 'UPDATE') return `将更新现有正式${promotionNoun(capabilityId)}，不新建记录`
  return `将在正式${promotionNoun(capabilityId)}表新建 1 条`
}

export function promotionConfirmText(capabilityId: string, factoryName: string, factoryId: string, label: string, action = 'CREATE'): string {
  const noun = promotionNoun(capabilityId)
  if (action === 'UPDATE') return `${promotionActionLabel(capabilityId, action)} — ${factoryName || factoryId}（${factoryId}）· ${label}：将更新预览指定的现有记录，请核对修改前后的值，写入后不能在这里撤销`
  return `${promotionActionLabel(capabilityId)} — ${factoryName || factoryId}（${factoryId}）· ${label}：`
    + `将写入正式${noun}表，写入后不能在这里撤销`
}

// ==================== 字段名 ====================

const FIELD_LABELS: Record<PromotableCapability, Record<string, string>> = {
  products: { code: '产品编号', name: '产品名称', unit: '单位', productCategory: '产品类别' },
  materials: {
    id: '正式记录编号', code: '物料编码', businessCode: '业务编码', createdBy: '原创建人编号', updatedAt: '最后修改时间',
    taxIncludedUnitPrice: '含税采购参考价', unitPrice: '未税参考价', taxRate: '税率',
    taxTreatment: '计税方式', taxExemptionReason: '免税依据', associatedCustomerId: '关联客户编号', packQtyPerProduct: '每产品包材用量',
    name: '物料名称', category: '类别', primaryCode: '物料分类编码', unit: '单位', storageType: '正式储存代码',
    sourceStorageMode: '草稿储存方式',
    shelfLifeDays: '保质期（天）', isByproduct: '是否加工副产物',
  },
  processes: {
    processName: '工序名称', processCategory: '工序类别', description: '说明', estimatedMinutes: '一般用时（分钟）',
    needsInput: '报工时录投入量', defaultOutputMaterialKind: '默认产出', standardYieldMin: '出成率下限',
    standardYieldMax: '出成率上限',
  },
  bom: {
    productTypeId: '成品编号', outputQuantity: '产出数量', outputUnit: '产出单位',
    netContentQuantity: '净含量', netContentUnit: '净含量单位', workflowId: 'Workflow 编号',
    workflowRevisionId: 'Workflow 版本编号', workflowRevisionHash: 'Workflow 版本摘要',
    batchOutputQuantity: '草稿产出数量', batchOutputUnit: '草稿产出单位',
    items: '配方行', packagingRoles: '包材投入角色',
  },
}

/** 少数枚举值的中文(原值放括号里, 方便和正式数据对照) */
/** Live material dictionary values; codes are persisted, labels are display-only. */
export const LIVE_STORAGE_TYPE_LABELS = {
  frozen: '冻货',
  fresh: '新鲜',
  normal: '常温',
} as const

const VALUE_LABELS: Record<string, Record<string, string>> = {
  taxRate: { TAX_9: '9%', TAX_13: '13%' },
  taxTreatment: { TAXABLE: '正常计税', EXEMPT: '免税' },
  storageType: { FROZEN: '冷冻', REFRIGERATED: '冷藏', AMBIENT: '常温', ...LIVE_STORAGE_TYPE_LABELS },
  productCategory: { FINISHED_PRODUCT: '成品', PACKAGING: '包材', SEASONING: '调料', CUSTOMER_MATERIAL: '客供料' },
  defaultOutputMaterialKind: { SEMI_FINISHED: '半成品', FINISHED_GOOD: '成品' },
}

export interface PromotionFieldRow {
  key: string
  label: string
  value: string
}

function displayValue(key: string, value: unknown, after: Record<string, unknown>): string {
  if (value === null || value === undefined || value === '') return '（空）'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (key === 'storageType' && typeof value === 'string' && typeof after.sourceStorageMode === 'string') {
    const sourceLabel = VALUE_LABELS.storageType[after.sourceStorageMode] ?? after.sourceStorageMode
    const liveLabel = LIVE_STORAGE_TYPE_LABELS[value as keyof typeof LIVE_STORAGE_TYPE_LABELS] ?? value
    return `${sourceLabel}（正式存储代码 ${value}/字典${liveLabel}）`
  }
  if (typeof value === 'string' && VALUE_LABELS[key]?.[value]) return `${VALUE_LABELS[key][value]}（${value}）`
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

/** after → 可读列表: 认识的键给中文名, 不认识的原样显示键名(⛔ 不丢字段) */
export function promotionFieldRows(capabilityId: string, after: Record<string, unknown> | null | undefined): PromotionFieldRow[] {
  const labels = isPromotable(capabilityId) ? FIELD_LABELS[capabilityId] : {}
  return Object.entries(after ?? {}).map(([key, value]) => ({
    key,
    label: labels[key] ?? key,
    value: displayValue(key, value, after ?? {}),
  }))
}

// ==================== 幂等键与请求体 ====================

export interface PromotionIdempotencyParts {
  factoryId: string
  blueprintId: string
  blueprintVersion: number | string
  sourceDigest: string
  capabilityId: string
  draftId: string
}

/** 被哈希的原文: 六段原样拼接, 缺任何一段都抛错 */
export function promotionIdempotencyMaterial(parts: PromotionIdempotencyParts): string {
  const entries = Object.entries(parts) as Array<[keyof PromotionIdempotencyParts, unknown]>
  for (const [name, value] of entries) {
    if (value === null || value === undefined || String(value).trim() === '') {
      throw new Error(`无法生成转正幂等键：缺少 ${name}`)
    }
  }
  return ['promotion', parts.factoryId, parts.blueprintId, String(parts.blueprintVersion), parts.sourceDigest,
    parts.capabilityId, parts.draftId].join(':')
}

/** 每份草稿在一个蓝图血缘里一个固定键 `bp:{能力}:{40 位十六进制}`, 重试复用 ⇒ 不会转正两次 */
export async function promotionIdempotencyKey(parts: PromotionIdempotencyParts): Promise<string> {
  return hashedIdempotencyKey('bp', parts.capabilityId, promotionIdempotencyMaterial(parts))
}

/** 执行请求体只有这两个字段 */
export function promotionExecuteBody(confirmedPromotionDigest: string, idempotencyKey: string, taxIncludedUnitPrice?: number): PromotionExecuteBody {
  return { confirmedPromotionDigest, idempotencyKey, ...(taxIncludedUnitPrice === undefined ? {} : { taxIncludedUnitPrice }) }
}

// ==================== 状态 ====================

export function promotionRecordKey(capabilityId: string, draftId: string): string {
  return `${capabilityId}:${draftId}`
}

/** 只有 PROMOTED 才算「已转正」 */
export function indexPromotedRecords(records: readonly PromotionRecord[] | null | undefined): Map<string, PromotionRecord> {
  const out = new Map<string, PromotionRecord>()
  for (const r of records ?? []) {
    if (r?.status === 'PROMOTED') out.set(promotionRecordKey(r.capabilityId, r.draftId), r)
  }
  return out
}

/** 能不能点「转正为正式X」: 预览 READY、没有阻断项、有摘要、幂等键已算好、预览没过期 */
export function canExecutePromotion(preview: PromotionPreview | null, idempotencyKey: string, stale: boolean): boolean {
  return !!preview
    && preview.status === 'READY'
    && (preview.blockers ?? []).length === 0
    && !!preview.promotionPreviewDigest
    && !!idempotencyKey
    && !stale
    && (preview.action !== 'UPDATE' || (!!preview.before && typeof preview.before === 'object'
      && 'id' in preview.before && typeof preview.before.id === 'string' && preview.before.id.trim() !== ''))
}

/** 这几个 409 说明「已经转正 / 键已用过」: 刷新转正记录, 卡片会变成「已转正」 */
export const PROMOTION_REFRESH_CODES: readonly string[] = ['PROMOTION_ALREADY_DONE', 'PROMOTION_IDEMPOTENCY_KEY_CONFLICT']
export const PROMOTION_STALE_CODE = 'PROMOTION_PREVIEW_STALE'

/** 后端给的下一步(BusinessException.withHint → ApiError.actionHint); 没有就空串 */
export function promotionErrorNextStep(e: unknown): string {
  const hint = (e as { actionHint?: string | null } | null)?.actionHint
  return typeof hint === 'string' ? hint : ''
}
