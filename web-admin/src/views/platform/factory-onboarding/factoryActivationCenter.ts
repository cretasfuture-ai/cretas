export type ActivationPlanStatus = 'READY_FOR_APPROVAL' | 'BLOCKED'
export type ModuleExecutionStatus = 'ACTIVATED' | 'FAILED' | 'BLOCKED_DEPENDENCY'

export interface ActivationIssue {
  code: string
  capabilityId: string
  message: string
  recovery: string
}

export interface ModuleActivationPlan {
  capabilityId: string
  moduleOwner: string
  dedicatedCommand: string
  draftId: string
  expectedRevision: number
  expectedDigest: string
  dependencies: string[]
  requiredActivationPermission: string
  readbackAssertions: string[]
  recoveryStrategy: string
}

export interface ActivationPlan {
  schemaVersion: 'factory-blueprint-activation-plan/v1'
  planId: string
  factoryId: string
  blueprintId: string
  blueprintVersion: number
  blueprintDigest: string
  planDigest: string
  requestedBy: number
  status: ActivationPlanStatus
  modules: ModuleActivationPlan[]
  issues: ActivationIssue[]
  expectedActivationCount: number
  businessWriteCount: 0
  approvalState: 'NOT_APPROVED'
  activationState: 'NOT_ACTIVATED'
}

export interface ModuleActivationReceipt {
  capabilityId: string
  status: ModuleExecutionStatus
  commandReceiptId: string
  targetRuntimeId: string
  activeRevision: number
  activeDigest: string
  businessWriteCount: number
  failureCode: string
  failureMessage: string
  recovery: string
}

export interface ActivationCenterReceipt {
  schemaVersion: 'factory-blueprint-activation-receipt/v1'
  status: 'ACTIVATED' | 'PARTIAL' | 'FAILED'
  modules: ModuleActivationReceipt[]
  successfulCount: number
  failedCount: number
  blockedCount: number
  originalBusinessWriteCount: number
  invocationBusinessWriteCount: number
  idempotentReplay: boolean
  recoverySummary: string
}

export const canApproveActivationPlan = (plan: ActivationPlan): boolean =>
  plan.status === 'READY_FOR_APPROVAL'
  && plan.issues.length === 0
  && plan.modules.length > 0
  && plan.expectedActivationCount === plan.modules.length
  && plan.businessWriteCount === 0
  && plan.approvalState === 'NOT_APPROVED'
  && plan.activationState === 'NOT_ACTIVATED'

export function summarizeActivationReceipt(receipt: ActivationCenterReceipt) {
  const failures = receipt.modules
    .filter((item) => item.status !== 'ACTIVATED')
    .map((item) => ({
      capabilityId: item.capabilityId,
      status: item.status,
      failureCode: item.failureCode,
      failureMessage: item.failureMessage,
      recovery: item.recovery
    }))
  return {
    headline: receipt.status === 'ACTIVATED'
      ? `${receipt.successfulCount} 项模块已完成运行态回读`
      : `${receipt.successfulCount} 项成功，${receipt.failedCount} 项失败，${receipt.blockedCount} 项因依赖阻断`,
    replay: receipt.idempotentReplay ? '幂等回放：本次业务写入 0' : `本次业务写入 ${receipt.invocationBusinessWriteCount}`,
    recovery: receipt.recoverySummary,
    failures
  }
}
