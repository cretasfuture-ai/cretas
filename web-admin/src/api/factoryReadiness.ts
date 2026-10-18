import request from './request'
import type { IndicatorApplicabilityReport } from './indicator'

export interface FactoryReadinessReport {
  schemaVersion: 'factory-readiness/v1'
  factoryId: string
  blueprintId: string
  blueprintVersion: number
  rowVersion: number
  lifecycleState: string
  capabilities: Array<{
    id: string; label: string; configurationState: string; acceptanceState: string
    stage: 'NOT_APPLICABLE' | 'BLOCKED' | 'WAITING_CONFIGURATION' | 'WAITING_REVIEW' | 'CONFIGURATION_ACCEPTED'
    nextStep: string; receiptCount: number
  }>
  currentRuntime: Array<{
    productId: string; productName: string; state: 'PUBLISHED' | 'NEEDS_REVIEW'
    workflowId: number | null; workflowVersion: number | null
    bomId: string | null; bomVersion: number | null; reason: string
  }>
  appliedVersion: { blueprintId: string | null; version: number | null; candidateDiffers: boolean; boundary: string }
  plannedIndicators: IndicatorApplicabilityReport
  currentIndicators: IndicatorApplicabilityReport
  businessAcceptanceState: 'UNVERIFIED'
  businessWriteCount: 0
  readAt: string
}

export async function getFactoryReadiness(factoryId: string, blueprintId: string): Promise<FactoryReadinessReport> {
  const response = await request.get<unknown, { success: boolean; data: FactoryReadinessReport; message?: string }>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/blueprints/${encodeURIComponent(blueprintId)}/readiness`,
    { baseURL: '' },
  )
  if (!response.success || !response.data) throw new Error(response.message || '建厂验收状态读取失败')
  return response.data
}
