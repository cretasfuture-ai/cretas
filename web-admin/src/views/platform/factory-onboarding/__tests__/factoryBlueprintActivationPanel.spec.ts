import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
const api = vi.hoisted(() => ({ previewFormal: vi.fn(), requestFormal: vi.fn(), readFormal: vi.fn(), readFormalReceipt: vi.fn(), approveFormal: vi.fn(), executeFormal: vi.fn(), listRecoverableFormal: vi.fn() }))
const dialog = vi.hoisted(() => ({ confirm: vi.fn() }))
vi.mock('@/api/factoryBlueprintActivationApi', () => api)
vi.mock('element-plus', () => ({ ElMessageBox: dialog }))
import Panel from '../components/FactoryBlueprintActivationPanel.vue'
const stubs = { 'el-button': { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' } }
const requestedActor = { type: 'PLATFORM_ADMIN' as const, id: 7 }
const approval = { approvalId: 'approval-id', capabilityId: 'modules', draftId: 'config-id', status: 'REQUESTED', planDigest: 'digest', approvalDigest: null, requestedActor, approvedActor: null, expiresAt: null }
const create = () => mount(Panel, { props: { factoryId: 'Ftest', blueprintId: 'blueprint', canConfirm: true,
  written: [{ capabilityId: 'modules', supported: true, drafts: [{ draftId: 'config-id', label: '配置', naturalKey: 'config', status: 'DRAFT' }] }] }, global: { stubs } })
beforeEach(() => { vi.clearAllMocks(); dialog.confirm.mockResolvedValue(undefined)
  api.listRecoverableFormal.mockResolvedValue({ data: { currentBlueprintVersion: 3, approvals: [] } }) })
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

describe('resuming persisted approvals without typing their number', () => {
  const listed = (id: string, status: string, recoverability: string, extra: Record<string, unknown> = {}) => ({
    approval: { ...approval, approvalId: id, status, approvalDigest: status === 'REQUESTED' ? null : 'approved', ...extra },
    blueprintVersion: recoverability === 'HISTORICAL_VERSION' ? 2 : 3, recoverability })
  it('lists this blueprint requests from the server and resumes one by reading it, without a typed number', async () => {
    api.listRecoverableFormal.mockResolvedValue({ data: { currentBlueprintVersion: 3, approvals: [
      listed('waiting', 'REQUESTED', 'CONTINUE_APPROVAL'),
      listed('old-version', 'REQUESTED', 'HISTORICAL_VERSION'),
      listed('lapsed', 'APPROVED', 'EXPIRED', { expiresAt: '2026-10-24T07:00:00Z' }),
      listed('changed', 'APPROVED', 'BLUEPRINT_CHANGED'),
      listed('broken', 'REQUESTED', 'SNAPSHOT_UNREADABLE'),
    ] } })
    api.readFormal.mockResolvedValue({ data: { ...approval, approvalId: 'waiting' } })
    const wrapper = create(); await flushPromises()
    expect(api.listRecoverableFormal).toHaveBeenCalledWith('Ftest', 'blueprint')
    const list = wrapper.get('[data-testid="formal-recoverable-modules"]').text()
    expect(list).toContain('待审批'); expect(list).toContain('蓝图第3版')
    // Only the request the server judged continuable offers an action; the rest are read-only with their reason.
    expect(wrapper.find('[data-testid="formal-resume-waiting"]').exists()).toBe(true)
    for (const id of ['old-version', 'lapsed', 'changed', 'broken']) {
      expect(wrapper.find(`[data-testid="formal-resume-${id}"]`).exists()).toBe(false)
    }
    expect(wrapper.get('[data-testid="formal-readonly-old-version"]').text()).toContain('其他蓝图版本')
    expect(wrapper.get('[data-testid="formal-readonly-lapsed"]').text()).toContain('已过期')
    expect(wrapper.get('[data-testid="formal-readonly-changed"]').text()).toContain('蓝图已变更')
    expect(wrapper.get('[data-testid="formal-readonly-broken"]').text()).toContain('无法核对')
    await wrapper.get('[data-testid="formal-resume-waiting"]').trigger('click'); await flushPromises()
    // Resuming reads the persisted approval through the server like a typed number would; nothing is approved or executed.
    expect(api.readFormal).toHaveBeenCalledWith('Ftest', 'blueprint', 'waiting')
    expect(wrapper.get('[data-testid="formal-approval-modules"]').text()).toContain('waiting')
    expect(api.approveFormal).not.toHaveBeenCalled(); expect(api.executeFormal).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('an executed request only reopens its original receipt', async () => {
    api.listRecoverableFormal.mockResolvedValue({ data: { currentBlueprintVersion: 3, approvals: [listed('done', 'EXECUTED', 'RECEIPT_ONLY')] } })
    api.readFormal.mockResolvedValue({ data: { ...approval, approvalId: 'done', status: 'EXECUTED', approvalDigest: 'approved' } })
    api.readFormalReceipt.mockResolvedValue({ data: { status: 'COMPLETED', idempotentReplay: false, invocationBusinessWriteCount: 0,
      readonlyReadback: true, requestedActor, approvedActor: requestedActor, executedActor: requestedActor, modules: [], verifiedRuntime: [],
      approval: { ...approval, approvalId: 'done', status: 'EXECUTED' } } })
    const wrapper = create(); await flushPromises()
    expect(wrapper.get('[data-testid="formal-resume-done"]').text()).toContain('查看原执行回执')
    await wrapper.get('[data-testid="formal-resume-done"]').trigger('click'); await flushPromises()
    expect(api.readFormalReceipt).toHaveBeenCalledWith('Ftest', 'blueprint', 'done')
    expect(api.executeFormal).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('drops a list that arrives after the blueprint changed and shows a failed list as a failure', async () => {
    let releaseOld!: (value: unknown) => void
    api.listRecoverableFormal
      .mockImplementationOnce(() => new Promise(resolveOld => { releaseOld = resolveOld }))
      .mockResolvedValueOnce({ data: { currentBlueprintVersion: 4, approvals: [] } })
    const wrapper = create(); await flushPromises()
    await wrapper.setProps({ blueprintId: 'blueprint-next' }); await flushPromises()
    releaseOld({ data: { currentBlueprintVersion: 3, approvals: [listed('stale', 'REQUESTED', 'CONTINUE_APPROVAL')] } }); await flushPromises()
    expect(wrapper.find('[data-testid="formal-resume-stale"]').exists()).toBe(false)
    api.listRecoverableFormal.mockRejectedValueOnce(new Error('列表读取失败'))
    await wrapper.setProps({ blueprintId: 'blueprint-third' }); await flushPromises()
    expect(wrapper.get('[data-testid="formal-recoverable-modules"]').text()).toContain('列表读取失败')
    wrapper.unmount()
  })
})
