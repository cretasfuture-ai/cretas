import { describe, expect, it } from 'vitest'
import {
  canApproveActivationPlan,
  summarizeActivationReceipt,
  type ActivationCenterReceipt,
  type ActivationPlan
} from '../factoryActivationCenter'

const plan = (): ActivationPlan => ({
  schemaVersion: 'factory-blueprint-activation-plan/v1',
  planId: 'activation-1',
  factoryId: 'F999',
  blueprintId: 'blueprint-1',
  blueprintVersion: 7,
  blueprintDigest: 'blueprint-digest',
  planDigest: 'plan-digest',
  requestedBy: 11,
  status: 'READY_FOR_APPROVAL',
  modules: [{
    capabilityId: 'workflow',
    moduleOwner: 'ProductProcessWorkflow',
    dedicatedCommand: 'FactoryWorkflowActivationCommand',
    draftId: 'draft-1',
    expectedRevision: 1,
    expectedDigest: 'draft-digest',
    dependencies: ['routes'],
    requiredActivationPermission: 'factory-blueprint:activation:workflow',
    readbackAssertions: ['revision exact'],
    recoveryStrategy: 'create corrective DRAFT'
  }],
  issues: [],
  expectedActivationCount: 1,
  businessWriteCount: 0,
  approvalState: 'NOT_APPROVED',
  activationState: 'NOT_ACTIVATED'
})

describe('factory activation center contract', () => {
  it('allows approval only for an exact zero-write preview with no issues', () => {
    expect(canApproveActivationPlan(plan())).toBe(true)
    expect(canApproveActivationPlan({ ...plan(), status: 'BLOCKED' })).toBe(false)
    expect(canApproveActivationPlan({ ...plan(), issues: [{
      code: 'VERSION_DRIFT', capabilityId: 'workflow', message: 'drift', recovery: 'refresh'
    }] })).toBe(false)
    expect(canApproveActivationPlan({ ...plan(), expectedActivationCount: 2 })).toBe(false)
  })

  it('keeps partial failures and dependency blocks visible with recovery', () => {
    const receipt: ActivationCenterReceipt = {
      schemaVersion: 'factory-blueprint-activation-receipt/v1',
      status: 'PARTIAL',
      modules: [
        {
          capabilityId: 'workflow', status: 'ACTIVATED', commandReceiptId: 'r1',
          targetRuntimeId: 'runtime-1', activeRevision: 1, activeDigest: 'digest',
          businessWriteCount: 1, failureCode: '', failureMessage: '', recovery: 'new draft'
        },
        {
          capabilityId: 'forms', status: 'FAILED', commandReceiptId: '', targetRuntimeId: '',
          activeRevision: 0, activeDigest: '', businessWriteCount: 0,
          failureCode: 'MODULE_ACTIVATION_FAILED', failureMessage: 'failed', recovery: 'fix draft'
        },
        {
          capabilityId: 'alerts', status: 'BLOCKED_DEPENDENCY', commandReceiptId: '', targetRuntimeId: '',
          activeRevision: 0, activeDigest: '', businessWriteCount: 0,
          failureCode: 'DEPENDENCY_ACTIVATION_FAILED', failureMessage: 'forms', recovery: 'retry plan'
        }
      ],
      successfulCount: 1,
      failedCount: 1,
      blockedCount: 1,
      originalBusinessWriteCount: 1,
      invocationBusinessWriteCount: 1,
      idempotentReplay: false,
      recoverySummary: '保留成功回执；只修复失败模块'
    }

    const summary = summarizeActivationReceipt(receipt)
    expect(summary.headline).toContain('1 项成功，1 项失败，1 项因依赖阻断')
    expect(summary.failures).toHaveLength(2)
    expect(summary.recovery).toContain('保留成功回执')
  })

  it('labels idempotent replay as zero new business writes', () => {
    const receipt: ActivationCenterReceipt = {
      schemaVersion: 'factory-blueprint-activation-receipt/v1',
      status: 'ACTIVATED', modules: [], successfulCount: 1, failedCount: 0, blockedCount: 0,
      originalBusinessWriteCount: 1, invocationBusinessWriteCount: 0,
      idempotentReplay: true, recoverySummary: 'new draft for changes'
    }
    expect(summarizeActivationReceipt(receipt).replay).toBe('幂等回放：本次业务写入 0')
  })
})
