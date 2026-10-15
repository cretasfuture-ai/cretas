import { describe, expect, it } from 'vitest'
import {
  controlSimulationAllowsDraft,
  FACTORY_WORKFLOW_CONTROL_COMPILER,
  FACTORY_WORKFLOW_CONTROL_SCHEMA,
  FACTORY_WORKFLOW_CONTROL_SIMULATOR,
  summarizeWorkflowControlPreview,
  type WorkflowControlPreview,
  type WorkflowControlSimulationReceipt,
} from '../factoryWorkflowControlPreview'

function preview(): WorkflowControlPreview {
  return {
    schemaVersion: FACTORY_WORKFLOW_CONTROL_SCHEMA,
    compilerVersion: FACTORY_WORKFLOW_CONTROL_COMPILER,
    status: 'PREVIEW_READY',
    contentHash: 'control-hash',
    issues: [],
    bundle: {
      factoryId: 'F999', sourceRouteDigest: 'route-hash', sourceWorkflowPreviewDigest: 'workflow-hash',
      branches: [{
        policyId: 'branch-policy', sourceProcessNodeId: 'inspect', selectionMode: 'EXACTLY_ONE',
        unknownDisposition: 'HOLD_FOR_REVIEW', paths: [
          { branchId: 'hot', targetProcessNodeId: 'hot-pack', defaultPath: false },
          { branchId: 'else', targetProcessNodeId: 'cold-pack', defaultPath: true },
        ],
      }],
      joins: [{ policyId: 'join-policy', joinProcessNodeId: 'carton', mode: 'ALL_SELECTED_COMPLETED', upstreamBranchIds: ['hot', 'else'] }],
      reworkPolicies: [{
        policyVersionId: 'rework/v1', sourceProcessNodeId: 'inspect', returnProcessNodeId: 'repair',
        maxAttempts: 2, quantityPolicy: 'FAILED_QUANTITY_ONLY', genealogyPolicy: 'CHILD_LOT',
        dispositionOnLimit: 'MANUAL_REVIEW',
      }],
    },
    publicationState: 'NOT_PUBLISHED', activationState: 'NOT_ACTIVATED',
    runtimeBindingState: 'NOT_BOUND_TO_RUNTIME', businessWriteCount: 0,
  }
}

function simulation(): WorkflowControlSimulationReceipt {
  return {
    schemaVersion: FACTORY_WORKFLOW_CONTROL_SCHEMA,
    simulatorVersion: FACTORY_WORKFLOW_CONTROL_SIMULATOR,
    previewHash: 'control-hash', status: 'SIMULATED',
    branchReceipts: [{
      policyId: 'branch-policy', status: 'SELECTED', selectedBranchIds: ['hot'], disposition: 'NO_WRITE_SELECTION_PREVIEW',
      paths: [
        { branchId: 'hot', guardResult: 'TRUE', selected: true, reason: 'SELECTED_BY_RULE', ruleDisposition: 'NO_WRITE_PREVIEW', trace: ['EQ'] },
        { branchId: 'else', guardResult: 'FALSE', selected: false, reason: 'DEFAULT_NOT_NEEDED', ruleDisposition: 'NO_RULE_DEFAULT_PATH', trace: [] },
      ],
    }],
    joinReceipts: [{
      policyId: 'join-policy', status: 'RELEASED', selectedBranchIds: ['hot'], completedBranchIds: ['hot'],
      ignoredUnselectedBranchIds: ['else'], released: true, disposition: 'NO_WRITE_JOIN_RELEASE_PREVIEW',
    }],
    reworkReceipts: [],
    publicationState: 'NOT_PUBLISHED', activationState: 'NOT_ACTIVATED',
    runtimeBindingState: 'NOT_BOUND_TO_RUNTIME', businessWriteCount: 0,
  }
}

describe('factoryWorkflowControlPreview', () => {
  it('summarizes typed branch join and rework semantics without claiming runtime writes', () => {
    expect(summarizeWorkflowControlPreview(preview(), simulation())).toEqual({
      status: 'PREVIEW_READY', branchPolicies: 1, joinPolicies: 1, reworkPolicies: 1,
      blockingIssues: 0, selectedBranches: 1, heldControls: 0, actualBusinessWrites: 0,
      runtimeBoundary: '隔离控制语义模拟；未物化、未发布、未激活、未绑定运行时',
    })
  })

  it('allows the next draft stage only for matching successful zero-write simulation receipts', () => {
    expect(controlSimulationAllowsDraft(preview(), simulation())).toBe(true)
    expect(controlSimulationAllowsDraft(preview(), { ...simulation(), status: 'SIMULATED_WITH_HOLDS' })).toBe(false)
    expect(controlSimulationAllowsDraft(preview(), { ...simulation(), previewHash: 'stale' })).toBe(false)
  })

  it('counts UNKNOWN holds, incomplete joins and bounded rework dispositions explicitly', () => {
    const held = simulation()
    held.status = 'SIMULATED_WITH_HOLDS'
    held.branchReceipts[0] = { ...held.branchReceipts[0], status: 'HOLD_FOR_REVIEW', selectedBranchIds: [] }
    held.joinReceipts[0] = { ...held.joinReceipts[0], status: 'WAITING_CONTROL_DECISION', released: false }
    held.reworkReceipts.push({
      eventId: 'event-2', policyVersionId: 'rework/v1', status: 'LIMIT_REACHED',
      triggerResult: 'TRUE', releaseResult: 'UNKNOWN', attemptNumber: 3, attemptId: '', attemptQuantity: 2,
      sourceLotId: 'LOT-1', resultLotId: 'LOT-1', genealogyPolicy: 'CHILD_LOT',
      disposition: 'MANUAL_REVIEW_ON_LIMIT', idempotentReplay: false,
    })

    expect(summarizeWorkflowControlPreview(preview(), held).heldControls).toBe(3)
    expect(controlSimulationAllowsDraft(preview(), held)).toBe(false)
  })
})
