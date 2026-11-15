import { get, post, put, adminGet, adminPost, adminPut } from './request'

// === Workflow Node Schemas ===

/** JSON Schema-style field definition used by NodeSchema.configSchema.properties. */
export interface NodeSchemaFieldDef {
  type?: 'string' | 'number' | 'integer' | 'boolean' | 'array' | 'object'
  description?: string
  enum?: string[]
  default?: unknown
  [k: string]: unknown
}

export interface NodeSchema {
  nodeType: string
  displayName: string
  description: string
  icon: string
  color: string
  category: string
  configSchema: {
    properties?: Record<string, NodeSchemaFieldDef>
    required?: string[]
    [k: string]: unknown
  }
  defaultConfig: Record<string, unknown>
  allowedNextNodes: string[]
  availableGuards: string[]
}

// URL moved from /api/workflow/node-schemas to /api/mobile/workflow/node-schemas
// to be proxied by 139 nginx gateway (old URL returned index.html silently,
// causing R20-F2 "766 empty 📦 tiles" bug). Switched from adminGet (absolute)
// to get (relative to /api/mobile baseURL).
export function getNodeSchemas() {
  return get<NodeSchema[]>('/workflow/node-schemas')
}

export function getNodeSchemasByCategory(category: string) {
  return get<NodeSchema[]>('/workflow/node-schemas/by-category', { params: { category } })
}

// === State Machine ===

export interface StateMachineConfig {
  /** ⚠️ 后端 `StateMachine.id` 是 varchar(50)，⛔ 不是数字。原来标成 number 是类型谎言。 */
  id: string
  factoryId: string
  entityType: string
  version: number
  publishStatus: string
  statesJson: string
  transitionsJson: string
  createdAt: string
  updatedAt: string
}

export interface SMState {
  code: string
  name: string
  type: 'initial' | 'normal' | 'final'
  description?: string
  color?: string
  // Extended properties
  nodeSchemaType?: string
  nodeCategory?: string
  nodeConfig?: Record<string, unknown>
  assignedRoles?: string[]
  timeLimitMinutes?: number
  autoEscalate?: boolean
  entryGuard?: string
  onEnterAction?: string
  onExitAction?: string
}

export interface SMTransition {
  from: string
  to: string
  event: string
  guard?: string
  action?: string
  roles?: string[]
  description?: string
  notifyRoles?: string[]
}

export function getPublishedStateMachine(factoryId: string, entityType: string) {
  return get<StateMachineConfig>(`/${factoryId}/rules/state-machines/${entityType}`, { _silent: true } as never)
}

// 🔴 2026-08-28 生产实测: 下面这几条原本都少了 `/rules` 这一段。
//    RuleController 的类级映射是 `/api/mobile/{factoryId}/rules`，所以
//    保存 / 发布 / 版本历史三个动作**全部 404** —— 设计器实际上是只读的。
//    ⚠️ 而且这不止是前缀写错: `versions` 和 `publish` 那两个端点当时
//    **后端也没有**（service 实现着，没人暴露）。只补前缀不解决问题。
//    ⇒ 新增这一类调用时，先确认 controller 上真有对应映射，再对齐前缀。

export function getVersionHistory(factoryId: string, entityType: string) {
  return get<StateMachineConfig[]>(`/${factoryId}/rules/state-machines/${entityType}/versions`)
}

export function publishDraft(factoryId: string, entityType: string, draftId: string) {
  return post<StateMachineConfig>(`/${factoryId}/rules/state-machines/${entityType}/publish/${draftId}`)
}

/** 创建或更新 —— 后端是同一个 `POST /rules/state-machines/{entityType}`（upsert）。 */
export function saveStateMachine(factoryId: string, data: Partial<StateMachineConfig>) {
  const entityType = data.entityType
  if (!entityType) throw new Error('saveStateMachine 需要 entityType —— 它是后端路径的一部分')
  return post<StateMachineConfig>(`/${factoryId}/rules/state-machines/${entityType}`, data)
}

/** ⚠️ 后端没有「按 id 更新」这条路; upsert 走 entityType。保留此函数只为兼容调用点。 */
export function updateStateMachine(factoryId: string, _id: string, data: Partial<StateMachineConfig>) {
  return saveStateMachine(factoryId, data)
}
