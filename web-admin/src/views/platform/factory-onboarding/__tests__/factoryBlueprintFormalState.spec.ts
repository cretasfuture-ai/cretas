import { describe, expect, it } from 'vitest'
import type { FormalExecution } from '@/api/factoryBlueprintActivationApi'
import { formalAcceptanceLabel, formalConfigurationLabel, formalRuntimeCounts, formalWorkflowLabel } from '../factoryBlueprintFormalState'
const execution = (): FormalExecution => ({ status: 'COMPLETED', idempotentReplay: false, invocationBusinessWriteCount: 2,
  requestedActor: { type: 'FACTORY_USER', id: 7 }, approvedActor: { type: 'PLATFORM_ADMIN', id: 7 }, executedActor: { type: 'FACTORY_USER', id: 7 },
  modules: [{ capabilityId: 'workflow', status: 'ACTIVATED', failureMessage: '', recovery: '' }],
  verifiedRuntime: [{ capabilityId: 'workflow', targetRuntimeId: '12:bom-id', activeRevision: 2, activeDigest: 'digest', runtimeState: 'ACTIVE' }],
  approval: { approvalId: 'a', capabilityId: 'workflow', draftId: 'w=12;b=b;c=c', status: 'EXECUTED', planDigest: 'plan', approvalDigest: 'approval',
    requestedActor: { type: 'FACTORY_USER', id: 7 }, approvedActor: { type: 'PLATFORM_ADMIN', id: 7 }, expiresAt: null } })
describe('formal runtime readiness follows actual readbacks', () => {
  it('updates review wording after a verified runtime and does not create a human ACCEPTED decision', () => {
    expect(formalAcceptanceLabel('NOT_READY', {})).toBe('尚不可验收')
    expect(formalAcceptanceLabel('NOT_READY', { workflow: execution() })).toBe('已有 1 项正式运行态回读，待人工核对')
    expect(formalAcceptanceLabel('ACCEPTED', { workflow: execution() })).toBe('全部已通过')
  })
  it('ignores failed, inactive, unknown capability and missing runtime even if envelope says completed', () => {
    const failed = execution(); failed.modules[0].status = 'FAILED'
    expect(formalRuntimeCounts({ workflow: failed }).workflow).toBe(0)
    const inactive = execution(); inactive.verifiedRuntime[0].runtimeState = 'NOT_ACTIVE'
    expect(formalRuntimeCounts({ workflow: inactive }).workflow).toBe(0)
    const empty = execution(); empty.verifiedRuntime = []
    expect(formalAcceptanceLabel('NOT_READY', { workflow: empty })).toBe('尚不可验收')
    const other = execution(); other.verifiedRuntime[0].capabilityId = 'products'
    expect(formalRuntimeCounts({ workflow: other }).workflow).toBe(0)
  })
  it('updates both preview boundaries from verified actions without changing canonical metadata', () => {
    const modules = execution()
    modules.modules[0].capabilityId = 'modules'
    modules.verifiedRuntime[0].capabilityId = 'modules'
    modules.verifiedRuntime[0].targetRuntimeId = 'module-config'
    expect(formalConfigurationLabel('NOT_APPLIED', { modules })).toBe('配置已应用（已回读）')
    expect(formalWorkflowLabel('NOT_PUBLISHED', { workflow: execution() })).toBe('Workflow 已发布（已回读 1 条）')
    expect(formalWorkflowLabel('NOT_PUBLISHED', { modules })).toBe('Workflow 未发布')
    expect(formalConfigurationLabel('NOT_APPLIED', { workflow: execution() })).toBe('配置未应用')
  })
  it('keeps unpublished boundaries for failed or inactive runtime and preserves canonical applied state', () => {
    const inactive = execution()
    inactive.verifiedRuntime[0].runtimeState = 'NOT_ACTIVE'
    expect(formalWorkflowLabel('NOT_PUBLISHED', { workflow: inactive })).toBe('Workflow 未发布')
    const failed = execution()
    failed.modules[0].capabilityId = 'modules'
    failed.modules[0].status = 'FAILED'
    failed.verifiedRuntime[0].capabilityId = 'modules'
    expect(formalConfigurationLabel('NOT_APPLIED', { modules: failed })).toBe('配置未应用')
    expect(formalConfigurationLabel('APPLIED', {})).toBe('配置已应用')
  })
})
