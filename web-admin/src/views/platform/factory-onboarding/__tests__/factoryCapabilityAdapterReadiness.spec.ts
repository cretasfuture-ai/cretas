import { describe, expect, it } from 'vitest'
import {
  canOpenCapabilityDraftConfirmation,
  summarizeCapabilityAdapterReadiness,
  type CapabilityAdapterReadiness,
  type CapabilityAdapterReadinessReport
} from '../factoryCapabilityAdapterReadiness'

const item = (
  capabilityId: string,
  availability: CapabilityAdapterReadiness['availability'],
  expectedBusinessWriteCount = 0
): CapabilityAdapterReadiness => ({
  capabilityId,
  availability,
  moduleOwner: capabilityId === 'workflow' ? 'ProductProcessWorkflow' : 'BomRecipe',
  artifactType: capabilityId === 'workflow' ? 'WORKFLOW_DRAFT' : 'BOM_DRAFT',
  targetSchemaVersion: `${capabilityId}-draft/v1`,
  requiredPermission: `${capabilityId}:draft:write`,
  dependencies: [],
  allowedTargetStates: ['DRAFT'],
  readbackAssertions: ['revision exact'],
  recoveryStrategy: 'create corrective DRAFT',
  dedicatedCommand: `${capabilityId}DraftCommand`,
  blockingCode: availability === 'BLOCKED_OWNER_COMMAND_MISSING'
    ? 'MODULE_DRAFT_COMMAND_NOT_REGISTERED'
    : '',
  nextStep: availability === 'BLOCKED_OWNER_COMMAND_MISSING'
    ? '由 module owner 实现专用命令'
    : '进入独立确认',
  expectedBusinessWriteCount,
  publishState: 'NOT_PUBLISHED',
  activationState: 'NOT_ACTIVATED'
})

const report = (): CapabilityAdapterReadinessReport => ({
  schemaVersion: 'factory-capability-adapter-readiness/v1',
  capabilities: [
    { ...item('factory', 'AVAILABLE_READ_ONLY'), allowedTargetStates: ['READ_ONLY'] },
    item('workflow', 'AVAILABLE_DRAFT', 1),
    item('bom', 'BLOCKED_OWNER_COMMAND_MISSING')
  ],
  capabilityCount: 26,
  availableReadOnlyCount: 2,
  availableDraftCount: 1,
  blockedCount: 23,
  expectedBusinessWriteCount: 0,
  publishState: 'NOT_PUBLISHED',
  activationState: 'NOT_ACTIVATED'
})

describe('factory capability adapter readiness', () => {
  it('states the exact partial-support truth instead of calling all 26 implemented', () => {
    const summary = summarizeCapabilityAdapterReadiness(report())

    expect(summary.headline).toContain('2 项只读')
    expect(summary.headline).toContain('1 项可进入模块草稿')
    expect(summary.headline).toContain('23 项待专用命令')
    expect(summary.writeBoundary).toContain('预计业务写入 0')
    expect(summary.publishBoundary).toBe('NOT_PUBLISHED · NOT_ACTIVATED')
  })

  it('keeps blocked capability owner, code and next step visible', () => {
    expect(summarizeCapabilityAdapterReadiness(report()).blockedCapabilities).toEqual([
      {
        capabilityId: 'bom',
        moduleOwner: 'BomRecipe',
        blockingCode: 'MODULE_DRAFT_COMMAND_NOT_REGISTERED',
        nextStep: '由 module owner 实现专用命令'
      }
    ])
  })

  it('opens confirmation only for a draft adapter with one explicit draft write', () => {
    expect(canOpenCapabilityDraftConfirmation(item('workflow', 'AVAILABLE_DRAFT', 1))).toBe(true)
    expect(canOpenCapabilityDraftConfirmation(item('workflow', 'AVAILABLE_DRAFT', 0))).toBe(false)
    expect(canOpenCapabilityDraftConfirmation(item('bom', 'BLOCKED_OWNER_COMMAND_MISSING'))).toBe(false)
    expect(canOpenCapabilityDraftConfirmation({
      ...item('workflow', 'AVAILABLE_DRAFT', 1),
      allowedTargetStates: ['DRAFT', 'READ_ONLY']
    })).toBe(false)
  })
})
