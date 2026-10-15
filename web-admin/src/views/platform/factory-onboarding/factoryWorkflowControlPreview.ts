export const FACTORY_WORKFLOW_CONTROL_SCHEMA = 'factory-workflow-control/v1' as const
export const FACTORY_WORKFLOW_CONTROL_COMPILER = 'factory-workflow-control-compiler/v1' as const
export const FACTORY_WORKFLOW_CONTROL_SIMULATOR = 'factory-workflow-control-simulator/v1' as const

export type BranchSelectionMode = 'EXACTLY_ONE' | 'AT_LEAST_ONE' | 'ALL_MATCHING'
export type JoinMode = 'ALL_SELECTED_COMPLETED' | 'ANY_SELECTED_COMPLETED' | 'MANUAL_RELEASE'
export type ReworkQuantityPolicy = 'FULL_LOT' | 'FAILED_QUANTITY_ONLY' | 'MANUAL_SPLIT'
export type ReworkGenealogyPolicy = 'SAME_LOT_ATTEMPT' | 'CHILD_LOT'
export type ReworkLimitDisposition = 'HOLD' | 'SCRAP' | 'MANUAL_REVIEW'
export type ControlTriState = 'TRUE' | 'FALSE' | 'UNKNOWN'

export interface WorkflowControlPreview {
  schemaVersion: typeof FACTORY_WORKFLOW_CONTROL_SCHEMA
  compilerVersion: typeof FACTORY_WORKFLOW_CONTROL_COMPILER
  status: 'BLOCKED' | 'PREVIEW_READY'
  contentHash: string
  issues: Array<{
    code: string
    severity: 'BLOCKING' | 'WARNING'
    path: string
    message: string
    recovery: string
  }>
  bundle: {
    factoryId: string
    sourceRouteDigest: string
    sourceWorkflowPreviewDigest: string
    branches: Array<{
      policyId: string
      sourceProcessNodeId: string
      selectionMode: BranchSelectionMode
      unknownDisposition: 'HOLD_FOR_REVIEW' | 'BLOCK'
      paths: Array<{ branchId: string; targetProcessNodeId: string; defaultPath: boolean }>
    }>
    joins: Array<{
      policyId: string
      joinProcessNodeId: string
      mode: JoinMode
      upstreamBranchIds: string[]
    }>
    reworkPolicies: Array<{
      policyVersionId: string
      sourceProcessNodeId: string
      returnProcessNodeId: string
      maxAttempts: number
      quantityPolicy: ReworkQuantityPolicy
      genealogyPolicy: ReworkGenealogyPolicy
      dispositionOnLimit: ReworkLimitDisposition
    }>
  } | null
  publicationState: 'NOT_PUBLISHED'
  activationState: 'NOT_ACTIVATED'
  runtimeBindingState: 'NOT_BOUND_TO_RUNTIME'
  businessWriteCount: 0
}

export interface WorkflowControlSimulationReceipt {
  schemaVersion: typeof FACTORY_WORKFLOW_CONTROL_SCHEMA
  simulatorVersion: typeof FACTORY_WORKFLOW_CONTROL_SIMULATOR
  previewHash: string
  status: 'BLOCKED' | 'SIMULATED' | 'SIMULATED_WITH_HOLDS'
  branchReceipts: Array<{
    policyId: string
    status: 'SELECTED' | 'HOLD_FOR_REVIEW' | 'BLOCKED'
    selectedBranchIds: string[]
    paths: Array<{
      branchId: string
      guardResult: ControlTriState
      selected: boolean
      reason: string
      ruleDisposition: string
      trace: string[]
    }>
    disposition: string
  }>
  joinReceipts: Array<{
    policyId: string
    status: 'RELEASED' | 'WAITING' | 'WAITING_CONTROL_DECISION'
    selectedBranchIds: string[]
    completedBranchIds: string[]
    ignoredUnselectedBranchIds: string[]
    released: boolean
    disposition: string
  }>
  reworkReceipts: Array<{
    eventId: string
    policyVersionId: string
    status: string
    triggerResult: ControlTriState
    releaseResult: ControlTriState
    attemptNumber: number
    attemptId: string
    attemptQuantity: number | null
    sourceLotId: string
    resultLotId: string
    genealogyPolicy: ReworkGenealogyPolicy | null
    disposition: string
    idempotentReplay: boolean
  }>
  publicationState: 'NOT_PUBLISHED'
  activationState: 'NOT_ACTIVATED'
  runtimeBindingState: 'NOT_BOUND_TO_RUNTIME'
  businessWriteCount: 0
}

export function summarizeWorkflowControlPreview(
  preview: WorkflowControlPreview,
  simulation?: WorkflowControlSimulationReceipt | null,
) {
  return {
    status: preview.status,
    branchPolicies: preview.bundle?.branches.length ?? 0,
    joinPolicies: preview.bundle?.joins.length ?? 0,
    reworkPolicies: preview.bundle?.reworkPolicies.length ?? 0,
    blockingIssues: preview.issues.filter(issue => issue.severity === 'BLOCKING').length,
    selectedBranches: simulation?.branchReceipts.reduce((sum, item) => sum + item.selectedBranchIds.length, 0) ?? 0,
    heldControls: simulation
      ? simulation.branchReceipts.filter(item => item.status !== 'SELECTED').length
        + simulation.joinReceipts.filter(item => !item.released).length
        + simulation.reworkReceipts.filter(item => ['BLOCKED', 'HOLD_FOR_REVIEW', 'LIMIT_REACHED', 'AWAITING_RELEASE'].includes(item.status)).length
      : 0,
    actualBusinessWrites: 0 as const,
    runtimeBoundary: '隔离控制语义模拟；未物化、未发布、未激活、未绑定运行时' as const,
  }
}

export function controlSimulationAllowsDraft(
  preview: WorkflowControlPreview,
  simulation: WorkflowControlSimulationReceipt,
): boolean {
  return preview.status === 'PREVIEW_READY'
    && simulation.previewHash === preview.contentHash
    && simulation.status === 'SIMULATED'
    && simulation.businessWriteCount === 0
}
