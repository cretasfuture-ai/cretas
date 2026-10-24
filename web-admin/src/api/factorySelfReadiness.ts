import request from './request'
import type { FactoryReadinessReport } from './factoryReadiness'

/**
 * The signed-in factory's own readiness, read under /api/mobile/{factoryId} so the backend's tenant
 * check applies. It never goes through /api/platform/**: the platform review keeps that route.
 */
export interface FactorySelfBlueprintRef {
  id: string
  version: number
  rowVersion: number
  lifecycleState: string
}

export type FactorySelfReadiness =
  | { state: 'READY_TO_READ'; blueprint: FactorySelfBlueprintRef; readiness: FactoryReadinessReport }
  | { state: 'NO_CANONICAL_BLUEPRINT'; blueprint: null; readiness: null }

export async function getMyFactoryReadiness(factoryId: string): Promise<FactorySelfReadiness> {
  const response = await request.get<unknown, { success: boolean; data: FactorySelfReadiness; message?: string }>(
    `/${encodeURIComponent(factoryId)}/factory-readiness/current`,
  )
  if (!response.success || !response.data) throw new Error(response.message || '建厂就绪状态读取失败')
  const data = response.data
  if (data.state === 'READY_TO_READ' ? !data.blueprint || !data.readiness : data.state !== 'NO_CANONICAL_BLUEPRINT') {
    throw new Error('建厂就绪状态格式不正确，请刷新后重试')
  }
  return data
}

/** Report loader for FactoryReadiness: re-reads the server and refuses a blueprint other than the one shown. */
export async function loadMyFactoryReadiness(factoryId: string, blueprintId: string): Promise<FactoryReadinessReport> {
  const current = await getMyFactoryReadiness(factoryId)
  if (current.state !== 'READY_TO_READ' || current.blueprint.id !== blueprintId) {
    throw new Error('本厂的正式建厂蓝图已变化，请刷新页面后重试')
  }
  return current.readiness
}
