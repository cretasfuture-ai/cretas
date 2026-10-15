import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
const api = vi.hoisted(() => ({ previewFormal: vi.fn(), requestFormal: vi.fn(), readFormal: vi.fn(), readFormalReceipt: vi.fn(), approveFormal: vi.fn(), executeFormal: vi.fn() }))
const dialog = vi.hoisted(() => ({ confirm: vi.fn() }))
vi.mock('@/api/factoryBlueprintActivationApi', () => api)
vi.mock('element-plus', () => ({ ElMessageBox: dialog }))
import Panel from '../components/FactoryBlueprintActivationPanel.vue'
const stubs = { 'el-button': { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' } }
const requestedActor = { type: 'PLATFORM_ADMIN' as const, id: 7 }
const approval = { approvalId: 'approval-id', capabilityId: 'modules', draftId: 'config-id', status: 'REQUESTED', planDigest: 'digest', approvalDigest: null, requestedActor, approvedActor: null, expiresAt: null }
const create = () => mount(Panel, { props: { factoryId: 'Ftest', blueprintId: 'blueprint', canConfirm: true,
  written: [{ capabilityId: 'modules', supported: true, drafts: [{ draftId: 'config-id', label: '配置', naturalKey: 'config', status: 'DRAFT' }] }] }, global: { stubs } })
beforeEach(() => { vi.clearAllMocks(); dialog.confirm.mockResolvedValue(undefined) })
describe('independent formal confirmations', () => {
  it('preview sends only selected IDs and never writes or publishes the other action', async () => {
    api.previewFormal.mockResolvedValue({ data: { status: 'READY_FOR_APPROVAL', planDigest: 'digest', issues: [], modules: [], requestedActor } })
    api.requestFormal.mockResolvedValue({ data: approval })
    const wrapper = create()
    await wrapper.get('[data-testid="formal-config-selection"]').setValue('config-id')
    await wrapper.get('[data-testid="formal-preview-modules"]').trigger('click'); await flushPromises()
    expect(api.previewFormal).toHaveBeenCalledWith('Ftest', 'blueprint', { capabilityId: 'modules', configDraftId: 'config-id' })
    expect(api.requestFormal).not.toHaveBeenCalled(); expect(api.executeFormal).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="formal-request-modules"]').trigger('click'); await flushPromises()
    expect(api.requestFormal).toHaveBeenCalledWith('Ftest', 'blueprint', { capabilityId: 'modules', configDraftId: 'config-id' }, 'digest')
    expect(api.requestFormal).toHaveBeenCalledTimes(1); expect(api.executeFormal).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="formal-approval-workflow"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('blocked prerequisites retain the server wording and cannot be confirmed', async () => {
    api.previewFormal.mockResolvedValue({ data: { status: 'BLOCKED', planDigest: '', issues: [{ code: 'BOM_NOT_PROMOTED', message: '配方尚未转正', recovery: '先转正所选配方' }], modules: [], requestedActor } })
    const wrapper = create()
    await wrapper.get('[data-testid="formal-preview-workflow"]').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="formal-plan-workflow"]').text()).toContain('配方尚未转正')
    expect(wrapper.get('[data-testid="formal-request-workflow"]').attributes('disabled')).toBeDefined()
    expect(api.requestFormal).not.toHaveBeenCalled(); expect(api.executeFormal).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('changing selections invalidates the confirmed preview', async () => {
    api.previewFormal.mockResolvedValue({ data: { status: 'READY_FOR_APPROVAL', planDigest: 'digest', issues: [], modules: [], requestedActor } })
    const wrapper = create()
    await wrapper.get('[data-testid="formal-config-selection"]').setValue('config-id')
    await wrapper.get('[data-testid="formal-preview-modules"]').trigger('click'); await flushPromises()
    expect(wrapper.find('[data-testid="formal-request-modules"]').exists()).toBe(true)
    await wrapper.get('[data-testid="formal-config-selection"]').setValue('')
    expect(wrapper.find('[data-testid="formal-request-modules"]').exists()).toBe(false)
    expect(api.requestFormal).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('refuses reading a Workflow approval into the configuration action', async () => {
    api.readFormal.mockResolvedValue({ data: { ...approval, capabilityId: 'workflow' } })
    const wrapper = create()
    await wrapper.get('[data-testid="formal-approval-id-modules"]').setValue('wrong-action')
    await wrapper.get('[data-testid="formal-read-modules"]').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="formal-error-modules"]').text()).toContain('另一项操作')
    expect(wrapper.find('[data-testid="formal-approval-modules"]').exists()).toBe(false)
    expect(api.approveFormal).not.toHaveBeenCalled(); expect(api.executeFormal).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('shows completion only from verified persisted runtime, and retains a stable replay key in the client API', async () => {
    api.readFormal.mockResolvedValue({ data: { ...approval, status: 'APPROVED', approvalDigest: 'approved' } })
    api.executeFormal.mockResolvedValue({ data: { status: 'COMPLETED', idempotentReplay: false, invocationBusinessWriteCount: 2,
      modules: [], verifiedRuntime: [{ capabilityId: 'modules', targetRuntimeId: 'runtime', activeRevision: 2, activeDigest: 'live', runtimeState: 'ACTIVE' }], approval: { ...approval, status: 'EXECUTED' } } })
    const wrapper = create()
    expect(wrapper.text()).not.toContain('已完成并回读')
    await wrapper.get('[data-testid="formal-approval-id-modules"]').setValue('approval-id')
    await wrapper.get('[data-testid="formal-read-modules"]').trigger('click'); await flushPromises()
    await wrapper.get('[data-testid="formal-execute-modules"]').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="formal-result-modules"]').text()).toContain('配置应用已完成并回读')
    expect(api.executeFormal).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
  it('reading a processed approval rechecks current runtime without re-executing or depending on approval expiry', async () => {
    api.readFormal.mockResolvedValue({ data: { ...approval, status: 'EXECUTED', expiresAt: '2000-01-01T00:00:00Z' } })
    api.readFormalReceipt.mockResolvedValue({ data: { status: 'COMPLETED', idempotentReplay: false, invocationBusinessWriteCount: 0, originalBusinessWriteCount: 2, readonlyReadback: true,
      modules: [], verifiedRuntime: [{ capabilityId: 'modules', targetRuntimeId: 'runtime', activeRevision: 2, activeDigest: 'live', runtimeState: 'ACTIVE' }], approval: { ...approval, status: 'EXECUTED' } } })
    const wrapper = create()
    await wrapper.get('[data-testid="formal-approval-id-modules"]').setValue('approval-id')
    await wrapper.get('[data-testid="formal-read-modules"]').trigger('click'); await flushPromises()
    expect(api.readFormalReceipt).toHaveBeenCalledWith('Ftest', 'blueprint', 'approval-id')
    expect(api.executeFormal).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="formal-result-modules"]').text()).toContain('配置应用已完成并回读')
    expect(wrapper.get('[data-testid="formal-result-modules"]').text()).toContain('本次写入 0 · 只读回读')
    expect(wrapper.get('[data-testid="formal-result-modules"]').text()).not.toContain('首次执行')
    wrapper.unmount()
  })
})
