export const FACTORY_WORKFLOW_DRAFT_MATERIALIZATION_SCHEMA = 'factory-workflow-draft-materialization/v1' as const

export type WorkflowDraftMaterializationMode =
  | 'CREATE_NEW_ONLY'
  | 'MERGE_EXISTING'
  | 'SNAPSHOT_AND_REPLACE'

export interface WorkflowDraftOperationPreview {
  schemaVersion: typeof FACTORY_WORKFLOW_DRAFT_MATERIALIZATION_SCHEMA
  status: 'READY' | 'CONFLICT' | 'BLOCKED'
  sourceDigest: string
  previewDigest: string
  operationDigest: string
  mode: WorkflowDraftMaterializationMode
  targetFactoryId: string
  targetProductTypeId: string
  expectedLockVersion: number | null
  expectedRevisionHash: string
  bomTargets: Array<{
    recipeId: string
    expectedWorkflowRevisionId: number | null
    expectedWorkflowRevisionHash: string
  }>
  expectedWorkflowDraftWrites: number
  expectedBomDraftWrites: number
  issues: Array<{ code: string; target: string; message: string; recovery: string }>
  publicationState: 'NOT_PUBLISHED'
  activationState: 'NOT_ACTIVATED'
  actualBusinessWriteCount: 0
}

export interface WorkflowDraftMaterializationReceipt {
  schemaVersion: typeof FACTORY_WORKFLOW_DRAFT_MATERIALIZATION_SCHEMA
  receiptId: string
  status: 'DRAFTED'
  requestDigest: string
  previewDigest: string
  operationDigest: string
  mode: WorkflowDraftMaterializationMode
  workflow: {
    workflowId: number
    factoryId: string
    productTypeId: string
    status: 'DRAFT'
    definitionVersion: number
    lockVersion: number
    revisionId: number
    revisionHash: string
    businessDigest: string
    nodeCount: number
    edgeCount: number
  }
  bomDrafts: Array<{
    recipeId: string
    productTypeId: string
    status: 'DRAFT'
    workflowId: number
    workflowRevisionId: number
    workflowRevisionHash: string
    workflowDefinitionVersion: number
  }>
  bomReadiness: 'NOT_REQUESTED' | 'PINNED_EXACT_REVISION'
  editorDeepLink: string
  idempotentReplay: boolean
  thisInvocationBusinessWriteCount: number
  originalBusinessWriteCount: number
  publicationState: 'NOT_PUBLISHED'
  activationState: 'NOT_ACTIVATED'
  runtimeBindingState: 'NOT_BOUND_TO_RUNTIME'
  recovery: string
}

export function summarizeDraftOperation(preview: WorkflowDraftOperationPreview) {
  return {
    ready: preview.status === 'READY' && preview.issues.length === 0,
    actionLabel: preview.status === 'READY'
      ? `创建 ${preview.expectedWorkflowDraftWrites} 个 Workflow 草稿变更，固定 ${preview.expectedBomDraftWrites} 个 BOM 草稿`
      : '草稿操作尚未满足执行条件',
    blockingIssues: preview.issues.length,
    plannedDraftWrites: preview.expectedWorkflowDraftWrites + preview.expectedBomDraftWrites,
    actualBusinessWrites: 0 as const,
    runtimeBoundary: '只写 DRAFT；不会发布、激活或绑定运行时' as const,
  }
}

export function summarizeDraftReceipt(receipt: WorkflowDraftMaterializationReceipt) {
  return {
    workflowRevision: `${receipt.workflow.definitionVersion} / ${receipt.workflow.revisionHash}`,
    bomPins: receipt.bomDrafts.length,
    replayed: receipt.idempotentReplay,
    thisInvocationBusinessWrites: receipt.thisInvocationBusinessWriteCount,
    originalBusinessWrites: receipt.originalBusinessWriteCount,
    editorDeepLink: receipt.editorDeepLink,
    runtimeBoundary: 'DRAFT 已回读；仍未发布、未激活、未绑定运行时' as const,
  }
}
