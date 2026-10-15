export const FACTORY_WORKFLOW_PREVIEW_SCHEMA = 'factory-workflow-draft-preview/v1' as const
export const FACTORY_WORKFLOW_PREVIEW_COMPILER = 'factory-workflow-draft-compiler/v1' as const
export const FACTORY_WORKFLOW_LAYOUT_VERSION = 'factory-workflow-layered-layout/v1' as const

export type WorkflowPreviewStatus = 'BLOCKED' | 'CONTROL_SEMANTICS_REQUIRED' | 'PREVIEW_READY'
export type WorkflowPreviewDiffAction = 'CREATE' | 'UPDATE' | 'KEEP' | 'CONFLICT' | 'UNSUPPORTED'

export interface WorkflowPreviewNode {
  id: string
  kind: 'RAW_MATERIAL' | 'PROCESS' | 'SEMI_FINISHED' | 'FINISHED_GOOD'
  position: { x: number; y: number }
  data: Record<string, unknown>
}

export interface WorkflowPreviewEdge {
  id: string
  source: string
  sourceHandle: string
  target: string
  targetHandle: string
}

export interface WorkflowDraftDefinitionPreview {
  factoryId: string
  productTypeId: string
  schemaVersion: 1
  status: 'DRAFT_PREVIEW'
  nodes: WorkflowPreviewNode[]
  edges: WorkflowPreviewEdge[]
  viewport: { x: number; y: number; zoom: number }
}

export interface WorkflowPreviewIssue {
  code: string
  severity: 'BLOCKING' | 'WARNING'
  phase: 'Resolve' | 'Normalize' | 'Expand' | 'Bind' | 'CompileControl' | 'Validate' | 'Diff' | string
  target: string
  message: string
  recovery: string
}

export interface WorkflowDraftChangeSetPreview {
  targetFactoryId: string
  targetProductTypeId: string
  action: WorkflowPreviewDiffAction
  expectedLockVersion: number | null
  expectedRevisionHash: string
  previewDigest: string
  entityDiffs: Array<{
    entityType: 'NODE' | 'EDGE'
    entityId: string
    action: WorkflowPreviewDiffAction
    beforeDigest: string
    afterDigest: string
    changedFields: string[]
  }>
  expectedDraftMutationCount: number
  expectedActiveMutationCount: 0
  materializationState: 'NOT_MATERIALIZED'
  actualBusinessWriteCount: 0
}

export interface FactoryWorkflowDraftPreview {
  schemaVersion: typeof FACTORY_WORKFLOW_PREVIEW_SCHEMA
  compilerVersion: typeof FACTORY_WORKFLOW_PREVIEW_COMPILER
  status: WorkflowPreviewStatus
  sourceDigest: string
  businessDigest: string
  layoutVersion: typeof FACTORY_WORKFLOW_LAYOUT_VERSION
  layoutDigest: string
  desiredDefinition: WorkflowDraftDefinitionPreview | null
  changeSet: WorkflowDraftChangeSetPreview | null
  validation: {
    formalStructureValid: boolean
    validator: string
    assertions: string[]
    businessWriteCount: 0
  } | null
  issues: WorkflowPreviewIssue[]
  publicationState: 'NOT_PUBLISHED'
  activationState: 'NOT_ACTIVATED'
  runtimeBindingState: 'NOT_BOUND_TO_RUNTIME'
  actualBusinessWriteCount: 0
}

export function summarizeWorkflowDraftPreview(preview: FactoryWorkflowDraftPreview) {
  const nodes = preview.desiredDefinition?.nodes ?? []
  const processCount = nodes.filter(node => node.kind === 'PROCESS').length
  const materialCount = nodes.length - processCount
  return {
    status: preview.status,
    processCount,
    materialCount,
    edgeCount: preview.desiredDefinition?.edges.length ?? 0,
    blockingIssues: preview.issues.filter(issue => issue.severity === 'BLOCKING').length,
    plannedDraftMutations: preview.changeSet?.expectedDraftMutationCount ?? 0,
    actualBusinessWrites: 0 as const,
    runtimeBoundary: '只读 DRAFT 预览；未物化、未发布、未激活、未绑定运行时' as const,
  }
}

export function workflowPreviewIsDraftReady(preview: FactoryWorkflowDraftPreview): boolean {
  return preview.status === 'PREVIEW_READY'
    && preview.validation?.formalStructureValid === true
    && preview.changeSet !== null
    && preview.changeSet.action !== 'UNSUPPORTED'
}
