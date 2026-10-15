/**
 * 建厂草稿(蓝图 → 各能力 DRAFT) API client.
 *
 * 后端: BlueprintDraftCatalogController / BlueprintDraftProposalController / BlueprintDraftController
 *       @ /api/platform/factories/{factoryId}/ai-initialize/blueprints/{blueprintId}/drafts
 *
 * 一条链:
 *   1. GET  {base}                               写入顺序 + 各能力入口是否开放(平台管理员也能读)
 *   2. GET  {base}/written                       已写入的草稿(只列当前操作者有权限的能力; 平台管理员 403)
 *   3. GET  {base}/{capabilityId}/proposal       按蓝图预填的请求 + 待填项(只读; 平台管理员 403)
 *   4. POST {base}/{capabilityId}/preview        预览(只读), 拿 previewDigest
 *   5. POST {base}/{capabilityId}/materialize    确认写入 DRAFT(带 confirmedPreviewDigest + 固定 idempotencyKey)
 *   6. GET  {base}/{capabilityId}/{draftId}      回读; 404 BLUEPRINT_DRAFT_READBACK_UNSUPPORTED = 该能力不支持回读
 *
 * ⚠️ 4 与 5 故意分开: 写入前必须先给人看过预览, 确认时带上那次预览的摘要。
 *
 * ⚠️ 所有 URL 都是绝对路径, 必须覆盖 baseURL(request 实例默认 `/api/mobile`), 写法同
 *    factoryOnboardingApi.ts 的 ABS。
 * ⚠️ 一律 `_silent: true`: 失败由面板自己就地显示后端原文 + 下一步, 不让拦截器再弹一层
 *    (平台管理员误调 written/proposal 会每个都弹一个 403 框)。
 *
 * @since 2026-10-04
 */
import request from './request'

const ABS_SILENT = { baseURL: '', _silent: true } as const

type Envelope<T> = { success: boolean; data: T; message?: string }

// ==================== 类型 (与后端 record 同步) ====================

/** BlueprintDraftCatalogController.CapabilityWriteView */
export interface DraftWriteOrderRow {
  capabilityId: string
  /** 只有 MODULE_DRAFT 能写草稿; factory / routes 等是只读能力 */
  adapterMode: string
  entryOpen: boolean
  proposalOpen: boolean
  requiredPermission: string
  writeDependencies: string[]
  /** 本能力依赖、而那个依赖自己的写入入口还没开放的能力 */
  dependenciesWithoutOpenEntry: string[]
}

export interface DraftWriteOrder {
  schemaVersion: string
  capabilities: DraftWriteOrderRow[]
}

export interface WrittenDraft {
  draftId: string
  naturalKey: string
  label: string
  status: string
  desiredDigest: string
  createdAt: string
}

export interface WrittenCapability {
  capabilityId: string
  /** false = 后端还列不出这类草稿; ⛔ 不等于「未写入」 */
  supported: boolean
  drafts: WrittenDraft[]
}

export interface WrittenDrafts {
  schemaVersion: string
  blueprintId: string
  blueprintVersion: number
  sourceDigest: string
  capabilities: WrittenCapability[]
}

export type DraftInputType =
  | 'TEXT'
  | 'INTEGER'
  | 'DECIMAL'
  | 'BOOLEAN'
  | 'ENUM'
  | 'MULTI_ENUM'
  | 'DATE'
  | 'STRUCTURE'

export interface DraftOption {
  /** 发给后端的值 */
  value: string
  /** 给人看的文字 */
  label: string
}

/** CapabilityDraftProposer.RequiredInput */
export interface DraftRequiredInput {
  /** 请求里的 JSON 路径, 语法 `a.b[0].c`(与后端 SEGMENT 一致) */
  path: string
  label: string
  reason: string
  /** 后端命令会接受什么(给人看的文字; 控件只看 inputType / options) */
  constraint: string
  /** 旧后端没有这个字段 —— 页面把缺失当契约错误显示, 不退化成文本框 */
  inputType?: DraftInputType | null
  /** 只有 ENUM / MULTI_ENUM 非空 */
  options?: DraftOption[] | null
  /** true = 用户可明确留空, 发 null */
  nullable?: boolean | null
  /** INTEGER / DECIMAL 的取值范围; MULTI_ENUM 时是可选个数 */
  min?: number | null
  max?: number | null
  /** TEXT 的最大长度 */
  maxLength?: number | null
  /** 只对 STRUCTURE: LIST = 若干行同形对象, OBJECT = 一个对象。旧后端没有 → 页面当契约错误 */
  structureKind?: DraftStructureKind | null
  /** 只对 STRUCTURE: 子字段定义, path 是相对路径(单段字段名), 可再嵌套 STRUCTURE */
  itemFields?: DraftRequiredInput[] | null
  /** 只对 LIST: 至少 / 至多几行(没有上限给 null) */
  minItems?: number | null
  maxItems?: number | null
  /** 只对 LIST: 每行标题, 如「岗位」→「岗位 1」 */
  itemLabel?: string | null
}

/** LIST = 若干行同形对象; OBJECT = 一个对象; VALUES = 若干个单值(纯值数组, 如 ["A","B"]) */
export type DraftStructureKind = 'LIST' | 'OBJECT' | 'VALUES'

/** CapabilityDraftProposer.Evidence —— 值以「未带入：」开头表示蓝图说了但这份草稿没带 */
export interface DraftEvidence {
  path: string
  value: string
}

export interface DraftBlocker {
  code: string
  message: string
  nextStep: string
}

export type DraftProposalStatus = 'READY_FOR_PREVIEW' | 'NEEDS_INPUT' | 'BLOCKED'

export interface DraftProposalItem {
  itemKey: string
  label: string
  status: DraftProposalStatus
  /** 待填字段是显式 null。⛔ 不要往里加任何字段, 后端拒绝未知字段 */
  request: Record<string, unknown>
  requiredInputs: DraftRequiredInput[]
  evidence: DraftEvidence[]
}

export interface DraftProposal {
  schemaVersion?: string
  capabilityId?: string
  status: DraftProposalStatus
  factoryId?: string
  blueprintId?: string
  blueprintVersion?: number | null
  /** BLOCKED 时可能为 null */
  sourceDigest: string | null
  items: DraftProposalItem[]
  blockers: DraftBlocker[]
}

export interface DraftIssue {
  code: string
  target: string
  message: string
  recovery: string
}

/** 预览结果形状因能力而异; 这里只声明公共字段, 其余原样展示 */
export interface DraftPreviewResult {
  status: string
  previewDigest?: string | null
  issues?: DraftIssue[] | null
  /**
   * 写入时要带回的额外确认字段(请求字段名 → 值), 例如 Workflow 的 confirmedOperationDigest。
   * 只合并请求里本来就有的字段, 不新增字段。
   */
  confirmation?: Record<string, string> | null
  [key: string]: unknown
}

export interface DraftMaterializeReceipt {
  draftId?: string | null
  replayed?: boolean
  status?: string
  [key: string]: unknown
}

export type DraftMaterializeBody = Record<string, unknown> & {
  confirmedPreviewDigest: string
  idempotencyKey: string
}

// ==================== 调用 ====================

function base(factoryId: string, blueprintId: string) {
  return `/api/platform/factories/${encodeURIComponent(factoryId)}/ai-initialize/blueprints/${encodeURIComponent(blueprintId)}/drafts`
}

export function getDraftWriteOrder(factoryId: string, blueprintId: string) {
  return request.get<unknown, Envelope<DraftWriteOrder>>(base(factoryId, blueprintId), ABS_SILENT)
}

export function getWrittenDrafts(factoryId: string, blueprintId: string) {
  return request.get<unknown, Envelope<WrittenDrafts>>(`${base(factoryId, blueprintId)}/written`, ABS_SILENT)
}

export function getDraftProposal(factoryId: string, blueprintId: string, capabilityId: string) {
  return request.get<unknown, Envelope<DraftProposal>>(
    `${base(factoryId, blueprintId)}/${encodeURIComponent(capabilityId)}/proposal`,
    ABS_SILENT
  )
}

export function previewDraft(
  factoryId: string,
  blueprintId: string,
  capabilityId: string,
  body: Record<string, unknown>
) {
  return request.post<unknown, Envelope<DraftPreviewResult>>(
    `${base(factoryId, blueprintId)}/${encodeURIComponent(capabilityId)}/preview`,
    body,
    ABS_SILENT
  )
}

export function materializeDraft(
  factoryId: string,
  blueprintId: string,
  capabilityId: string,
  body: DraftMaterializeBody
) {
  return request.post<unknown, Envelope<DraftMaterializeReceipt>>(
    `${base(factoryId, blueprintId)}/${encodeURIComponent(capabilityId)}/materialize`,
    body,
    ABS_SILENT
  )
}

export function readBackDraft(factoryId: string, blueprintId: string, capabilityId: string, draftId: string) {
  return request.get<unknown, Envelope<unknown>>(
    `${base(factoryId, blueprintId)}/${encodeURIComponent(capabilityId)}/${encodeURIComponent(draftId)}`,
    ABS_SILENT
  )
}
