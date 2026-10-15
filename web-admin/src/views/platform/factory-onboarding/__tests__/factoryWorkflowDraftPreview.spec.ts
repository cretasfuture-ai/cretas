import { describe, expect, it } from 'vitest'
import {
  FACTORY_WORKFLOW_LAYOUT_VERSION,
  FACTORY_WORKFLOW_PREVIEW_COMPILER,
  FACTORY_WORKFLOW_PREVIEW_SCHEMA,
  summarizeWorkflowDraftPreview,
  workflowPreviewIsDraftReady,
  type FactoryWorkflowDraftPreview,
} from '../factoryWorkflowDraftPreview'

function preview(status: FactoryWorkflowDraftPreview['status']): FactoryWorkflowDraftPreview {
  return {
    schemaVersion: FACTORY_WORKFLOW_PREVIEW_SCHEMA,
    compilerVersion: FACTORY_WORKFLOW_PREVIEW_COMPILER,
    status,
    sourceDigest: 'source', businessDigest: 'a'.repeat(64),
    layoutVersion: FACTORY_WORKFLOW_LAYOUT_VERSION, layoutDigest: 'b'.repeat(64),
    desiredDefinition: {
      factoryId: 'F999', productTypeId: 'finished-1', schemaVersion: 1, status: 'DRAFT_PREVIEW',
      nodes: [
        { id: 'raw', kind: 'RAW_MATERIAL', position: { x: 0, y: 80 }, data: {} },
        { id: 'process', kind: 'PROCESS', position: { x: 280, y: 80 }, data: { ports: [] } },
        { id: 'finished', kind: 'FINISHED_GOOD', position: { x: 560, y: 80 }, data: {} },
      ],
      edges: [
        { id: 'in', source: 'raw', sourceHandle: 'output', target: 'process', targetHandle: 'in-port' },
        { id: 'out', source: 'process', sourceHandle: 'out-port', target: 'finished', targetHandle: 'input' },
      ],
      viewport: { x: 0, y: 0, zoom: 1 },
    },
    changeSet: {
      targetFactoryId: 'F999', targetProductTypeId: 'finished-1', action: 'CREATE',
      expectedLockVersion: null, expectedRevisionHash: '', previewDigest: 'a'.repeat(64), entityDiffs: [],
      expectedDraftMutationCount: 1, expectedActiveMutationCount: 0,
      materializationState: 'NOT_MATERIALIZED', actualBusinessWriteCount: 0,
    },
    validation: { formalStructureValid: true, validator: 'validateStructureComplete',
      assertions: ['bipartite'], businessWriteCount: 0 },
    issues: [], publicationState: 'NOT_PUBLISHED', activationState: 'NOT_ACTIVATED',
    runtimeBindingState: 'NOT_BOUND_TO_RUNTIME', actualBusinessWriteCount: 0,
  }
}

describe('B22 Workflow DRAFT preview contract', () => {
  it('summarizes the formal bipartite graph and separates planned from actual writes', () => {
    expect(summarizeWorkflowDraftPreview(preview('PREVIEW_READY'))).toEqual({
      status: 'PREVIEW_READY', processCount: 1, materialCount: 2, edgeCount: 2,
      blockingIssues: 0, plannedDraftMutations: 1, actualBusinessWrites: 0,
      runtimeBoundary: '只读 DRAFT 预览；未物化、未发布、未激活、未绑定运行时',
    })
  })

  it('keeps branch merge and rework previews ineligible until control semantics exist', () => {
    const blocked = preview('CONTROL_SEMANTICS_REQUIRED')
    blocked.changeSet!.action = 'UNSUPPORTED'
    blocked.changeSet!.expectedDraftMutationCount = 0
    blocked.issues.push({ code: 'B23_BRANCH_SEMANTICS_REQUIRED', severity: 'BLOCKING',
      phase: 'CompileControl', target: 'BRANCH', message: '缺少运行语义', recovery: '完成 B23' })

    expect(workflowPreviewIsDraftReady(blocked)).toBe(false)
    expect(summarizeWorkflowDraftPreview(blocked).plannedDraftMutations).toBe(0)
  })

  it('exposes no materialize publish activate or runtime action contract', () => {
    const wire = JSON.stringify(preview('PREVIEW_READY'))
    expect(wire).toContain('NOT_MATERIALIZED')
    expect(wire).not.toMatch(/materializeAction|publishAction|activateAction|runtimeEndpoint|sql/i)
    expect(workflowPreviewIsDraftReady(preview('PREVIEW_READY'))).toBe(true)
  })
})
