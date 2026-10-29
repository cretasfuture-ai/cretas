import request from './request'

/**
 * 对话建厂的已确认事实: 冲突逐条选择来源 + 版本历史。
 * 走 /api/mobile/{factoryId}/factory-build/facts, 工厂永远是登录账号所属工厂。
 */
export interface FactConflict {
  field: string
  label: string | null
  confirmedValue: string
  newValue: string
  confirmedSource: string | null
  newSource: string | null
  pendingId: string
}

export type FactStatus = 'CONFIRMED' | 'CONFLICT_PENDING' | 'PENDING_CONFIRM' | 'SUPERSEDED' | 'REJECTED'

export interface FactHistoryRow {
  id: string
  field: string
  label: string | null
  value: string
  status: FactStatus
  version: number
  sourceKind: string
  sourceRef: string | null
  supersedesId: string | null
  decidedBy: string | null
  decidedAt: string | null
  createdAt: string
}

export interface FactHistory {
  conflicts: FactConflict[]
  history: FactHistoryRow[]
}

export type FactChoice = 'KEEP_CONFIRMED' | 'USE_NEW'

const base = (factoryId: string) => `/${encodeURIComponent(factoryId)}/factory-build/facts`

export async function getFactHistory(factoryId: string): Promise<FactHistory> {
  const response = await request.get<unknown, { success: boolean; data: FactHistory; message?: string }>(base(factoryId))
  if (!response.success || !response.data) throw new Error(response.message || '已确认资料读取失败')
  return response.data
}

export async function resolveFactConflict(factoryId: string, field: string, choice: FactChoice): Promise<FactHistoryRow> {
  const response = await request.post<unknown, { success: boolean; data: FactHistoryRow; message?: string }>(
    `${base(factoryId)}/resolve`, { field, choice },
  )
  if (!response.success || !response.data) throw new Error(response.message || '来源选择保存失败')
  return response.data
}

export const FACT_STATUS_LABEL: Record<FactStatus, string> = {
  CONFIRMED: '当前生效',
  CONFLICT_PENDING: '待选择来源',
  PENDING_CONFIRM: '待确认',
  SUPERSEDED: '已被取代',
  REJECTED: '已否决',
}

/** conversation:<sessionId> → 人能读的来源说明。 */
export function describeFactSource(sourceKind: string, sourceRef: string | null): string {
  if (sourceKind === 'USER_ANSWER' && sourceRef?.startsWith('conversation:')) {
    return `建厂对话回答（会话 ${sourceRef.slice('conversation:'.length).slice(0, 8)}）`
  }
  return sourceRef ? `${sourceKind}：${sourceRef}` : sourceKind
}
