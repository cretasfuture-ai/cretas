import { describe, expect, it } from 'vitest'
import {
  summarizeDraftOperation,
  summarizeDraftReceipt,
  type WorkflowDraftMaterializationReceipt,
  type WorkflowDraftOperationPreview,
} from '../factoryWorkflowDraftMaterialization'

describe('factory Workflow DRAFT materialization contracts', () => {
  it('names the exact DRAFT action and keeps runtime effects at zero', () => {
    const preview: WorkflowDraftOperationPreview = {
      schemaVersion: 'factory-workflow-draft-materialization/v1',
      status: 'READY',
      sourceDigest: 'source',
      previewDigest: 'preview',
      operationDigest: 'operation',
      mode: 'CREATE_NEW_ONLY',
      targetFactoryId: 'F999',
      targetProductTypeId: 'finished-1',
      expectedLockVersion: null,
      expectedRevisionHash: '',
      bomTargets: [],
      expectedWorkflowDraftWrites: 1,
      expectedBomDraftWrites: 2,
      issues: [],
      publicationState: 'NOT_PUBLISHED',
      activationState: 'NOT_ACTIVATED',
      actualBusinessWriteCount: 0,
    }

    expect(summarizeDraftOperation(preview)).toEqual({
      ready: true,
      actionLabel: '创建 1 个 Workflow 草稿变更，固定 2 个 BOM 草稿',
      blockingIssues: 0,
      plannedDraftWrites: 3,
      actualBusinessWrites: 0,
      runtimeBoundary: '只写 DRAFT；不会发布、激活或绑定运行时',
    })
  })

  it('separates replay writes and returns the existing editor deep link', () => {
    const receipt: WorkflowDraftMaterializationReceipt = {
      schemaVersion: 'factory-workflow-draft-materialization/v1',
      receiptId: 'receipt-1',
      status: 'DRAFTED',
      requestDigest: 'request',
      previewDigest: 'preview',
      operationDigest: 'operation',
      mode: 'MERGE_EXISTING',
      workflow: {
        workflowId: 81,
        factoryId: 'F999',
        productTypeId: 'finished-1',
        status: 'DRAFT',
        definitionVersion: 3,
        lockVersion: 8,
        revisionId: 901,
        revisionHash: 'revision-901',
        businessDigest: 'preview',
        nodeCount: 7,
        edgeCount: 6,
      },
      bomDrafts: [],
      bomReadiness: 'NOT_REQUESTED',
      editorDeepLink: '/system/product-processes?productTypeId=finished-1',
      idempotentReplay: true,
      thisInvocationBusinessWriteCount: 0,
      originalBusinessWriteCount: 1,
      publicationState: 'NOT_PUBLISHED',
      activationState: 'NOT_ACTIVATED',
      runtimeBindingState: 'NOT_BOUND_TO_RUNTIME',
      recovery: '草稿未生效',
    }

    expect(summarizeDraftReceipt(receipt)).toMatchObject({
      workflowRevision: '3 / revision-901',
      bomPins: 0,
      replayed: true,
      thisInvocationBusinessWrites: 0,
      originalBusinessWrites: 1,
      editorDeepLink: '/system/product-processes?productTypeId=finished-1',
    })
  })

  it('does not present conflict or blocked previews as executable', () => {
    const blocked = {
      status: 'CONFLICT',
      issues: [{ code: 'VERSION_CONFLICT', target: 'workflow', message: 'changed', recovery: 'refresh' }],
      expectedWorkflowDraftWrites: 0,
      expectedBomDraftWrites: 0,
    } as WorkflowDraftOperationPreview

    expect(summarizeDraftOperation(blocked)).toMatchObject({
      ready: false,
      blockingIssues: 1,
      actualBusinessWrites: 0,
    })
  })
})
