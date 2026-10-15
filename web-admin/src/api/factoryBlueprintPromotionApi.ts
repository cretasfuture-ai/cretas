/**
 * 建厂草稿「转正」API client: 把一份已写入的主数据草稿(产品 / 物料 / 工序)新建为正式数据。
 *
 * 后端: MasterDataDraftPromotionController
 *       @ /api/platform/factory-assembly/factories/{factoryId}/ai-initialize/blueprints/{blueprintId}
 *
 *   POST drafts/{capabilityId}/{draftId}/promotion/preview   预览(只读), 拿 promotionPreviewDigest
 *   POST drafts/{capabilityId}/{draftId}/promotion           确认转正(只新建正式数据, 草稿行不变)
 *   GET  drafts/{capabilityId}/{draftId}/promotion           这份草稿的转正记录(404 PROMOTION_NOT_FOUND = 没有)
 *   GET  promotions                                          当前蓝图血缘下全部转正记录(进页面时用它标「已转正」)
 *
 * ⚠️ 预览与确认故意分开: 确认时必须带上那次预览的摘要, 预览过期 / 数据变了后端回 409 PROMOTION_PREVIEW_STALE。
 * ⚠️ 绝对路径 + baseURL: '' + _silent: true(失败由卡片就地显示后端原文和下一步), 同 factoryBlueprintDraftApi。
 */
import request from './request'

const ABS_SILENT = { baseURL: '', _silent: true } as const

type Envelope<T> = { success: boolean; data: T; message?: string }

/** 目前只开放这三项能力的转正 */
export type PromotableCapability = 'products' | 'materials' | 'processes' | 'bom'

export interface PromotionBlocker {
  code: string
  message: string
  nextStep: string
  /** 已占用同一自然键的那条正式数据(冲突时) */
  existingLiveId?: string | null
  existingLiveName?: string | null
}

export interface PromotionPreview {
  schemaVersion?: string
  status: 'READY' | 'BLOCKED' | string
  action: 'CREATE' | string
  capabilityId: string
  draftId: string
  naturalKey: string
  /** 修改时为当前正式记录，新建时为 null。 */
  before?: unknown
  /** 将写入正式表的字段(执行后会逐字段回读核对) */
  after: Record<string, unknown>
  blockers: PromotionBlocker[]
  promotionPreviewDigest: string | null
  expiresAt?: string | null
}

export interface PromotionExecuteBody {
  confirmedPromotionDigest: string
  idempotencyKey: string
  taxIncludedUnitPrice?: number
  packagingRoles?: Record<string, string>
}

export interface PromotionReceipt {
  schemaVersion?: string
  promotionId: string
  status: 'PROMOTED' | string
  capabilityId?: string
  draftId?: string
  liveTargetId: string
  replayed: boolean
  liveWriteCount?: number
  /** 从正式表回读的字段 */
  readback: Record<string, unknown> | null
  liveReadbackDigest?: string
}

export interface PromotionRecord {
  promotionId: string
  capabilityId: string
  draftId: string
  status: 'PROMOTED' | string
  action: string
  naturalKey: string
  liveTargetId: string | null
  createdAt?: string
  updatedAt?: string
}

export interface PromotionList {
  schemaVersion?: string
  blueprintId: string
  blueprintVersion: number
  sourceDigest: string
  promotions: PromotionRecord[]
}

function base(factoryId: string, blueprintId: string) {
  return `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/blueprints/${encodeURIComponent(blueprintId)}`
}

function draftPath(factoryId: string, blueprintId: string, capabilityId: string, draftId: string) {
  return `${base(factoryId, blueprintId)}/drafts/${encodeURIComponent(capabilityId)}/${encodeURIComponent(draftId)}/promotion`
}

export function previewPromotion(factoryId: string, blueprintId: string, capabilityId: string, draftId: string, body?: { taxIncludedUnitPrice?: number; packagingRoles?: Record<string, string> }) {
  return request.post<unknown, Envelope<PromotionPreview>>(
    `${draftPath(factoryId, blueprintId, capabilityId, draftId)}/preview`, body, ABS_SILENT)
}

export function executePromotion(
  factoryId: string,
  blueprintId: string,
  capabilityId: string,
  draftId: string,
  body: PromotionExecuteBody
) {
  return request.post<unknown, Envelope<PromotionReceipt>>(
    draftPath(factoryId, blueprintId, capabilityId, draftId), body, ABS_SILENT)
}

export function getPromotion(factoryId: string, blueprintId: string, capabilityId: string, draftId: string) {
  return request.get<unknown, Envelope<PromotionRecord>>(draftPath(factoryId, blueprintId, capabilityId, draftId), ABS_SILENT)
}

export function listPromotions(factoryId: string, blueprintId: string) {
  return request.get<unknown, Envelope<PromotionList>>(`${base(factoryId, blueprintId)}/promotions`, ABS_SILENT)
}
