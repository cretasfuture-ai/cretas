import request from './request'

export type FormalCapability = 'modules' | 'workflow'
export interface FormalSelection {
  capabilityId: FormalCapability
  configDraftId?: string
  workflowDraftId?: number
  bomDraftId?: string
  criticalControlsDraftId?: string
}
export interface FormalActor { type: 'FACTORY_USER' | 'PLATFORM_ADMIN'; id: number }
export interface FormalPlan {
  planDigest: string
  status: 'READY_FOR_APPROVAL' | 'BLOCKED'
  issues: Array<{ code: string; message: string; recovery: string }>
  modules: Array<{ capabilityId: string; draftId: string; dedicatedCommand: string }>
  requestedActor: FormalActor
}
export interface FormalApproval {
  approvalId: string
  capabilityId: FormalCapability
  draftId: string
  status: 'REQUESTED' | 'APPROVED' | 'EXECUTED'
  planDigest: string
  approvalDigest: string | null
  requestedActor: FormalActor
  approvedActor: FormalActor | null
  expiresAt: string | null
}
export interface FormalExecution {
  status: string
  idempotentReplay: boolean
  invocationBusinessWriteCount: number
  originalBusinessWriteCount?: number
  readonlyReadback?: boolean
  requestedActor: FormalActor
  approvedActor: FormalActor
  executedActor: FormalActor
  modules: Array<{ capabilityId: string; status: string; failureMessage: string; recovery: string }>
  verifiedRuntime: Array<{ capabilityId: string; targetRuntimeId: string; activeRevision: number;
    activeDigest: string; runtimeState: string }>
  approval: FormalApproval
}
const options = { baseURL: '', _silent: true } as const
type Envelope<T> = { success: boolean; data: T; message?: string }
const base = (factoryId: string, blueprintId: string) =>
  `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/blueprints/${encodeURIComponent(blueprintId)}/formal-activation`

export function previewFormal(factoryId: string, blueprintId: string, selection: FormalSelection): Promise<Envelope<FormalPlan>> {
  return request.post(`${base(factoryId, blueprintId)}/preview`, selection, options)
}
export function requestFormal(factoryId: string, blueprintId: string, selection: FormalSelection, confirmedPlanDigest: string): Promise<Envelope<FormalApproval>> {
  return request.post(`${base(factoryId, blueprintId)}/requests`, { selection, confirmedPlanDigest }, options)
}
export function readFormal(factoryId: string, blueprintId: string, approvalId: string): Promise<Envelope<FormalApproval>> {
  return request.get(`${base(factoryId, blueprintId)}/requests/${encodeURIComponent(approvalId)}`, options)
}
export function readFormalReceipt(factoryId: string, blueprintId: string, approvalId: string): Promise<Envelope<FormalExecution>> {
  return request.get(`${base(factoryId, blueprintId)}/requests/${encodeURIComponent(approvalId)}/receipt`,
    { ...options, params: { idempotencyKey: `formal:${approvalId}` } })
}
export function approveFormal(factoryId: string, blueprintId: string, approval: FormalApproval, reason: string): Promise<Envelope<FormalApproval>> {
  return request.post(`${base(factoryId, blueprintId)}/requests/${encodeURIComponent(approval.approvalId)}/approve`,
    { confirmedPlanDigest: approval.planDigest, reason }, options)
}
export function executeFormal(factoryId: string, blueprintId: string, approval: FormalApproval): Promise<Envelope<FormalExecution>> {
  return request.post(`${base(factoryId, blueprintId)}/requests/${encodeURIComponent(approval.approvalId)}/execute`,
    { confirmedApprovalDigest: approval.approvalDigest, idempotencyKey: `formal:${approval.approvalId}` }, options)
}
