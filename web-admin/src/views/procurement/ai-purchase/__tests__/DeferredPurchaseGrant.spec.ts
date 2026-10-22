import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, reactive, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const harness = vi.hoisted(() => ({
  factoryId: 'F006', userId: '42', get: vi.fn(), post: vi.fn(), push: vi.fn(),
}))
const factoryScope = ref(harness.factoryId)
const userScope = ref(harness.userId)

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => reactive({ factoryId: factoryScope, user: { id: userScope } }) }))
vi.mock('@/router', () => ({ useRouter: () => ({ push: harness.push }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: harness.push }) }))
vi.mock('@/api/request', () => ({ default: { get: (...args: unknown[]) => harness.get(...args), post: (...args: unknown[]) => harness.post(...args) }, get: (...args: unknown[]) => harness.get(...args), post: (...args: unknown[]) => harness.post(...args) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() } }))

import PurchasePage from '../index.vue'
import { executeDeferredControlledPurchase } from '@/api/controlledPurchase'

const ElButtonStub = defineComponent({ name: 'ElButton', props: { disabled: Boolean, loading: Boolean }, emits: ['click'], setup(props, { slots, emit }) {
  return () => h('button', { disabled: props.disabled, onClick: () => emit('click') }, slots.default?.())
} })
const ElInputStub = defineComponent({ name: 'ElInput', props: { modelValue: { type: String, default: '' }, disabled: Boolean }, emits: ['update:modelValue'], setup(props, { emit }) {
  return () => h('input', { value: props.modelValue, disabled: props.disabled, onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value) })
} })
const ElSelectStub = defineComponent({ name: 'ElSelect', props: { modelValue: { type: String, default: '' }, disabled: Boolean }, emits: ['update:modelValue'], setup(props, { emit, slots }) {
  return () => h('select', { value: props.modelValue, disabled: props.disabled, onChange: (e: Event) => emit('update:modelValue', (e.target as HTMLSelectElement).value) }, slots.default?.())
} })
const ElDateStub = defineComponent({ name: 'ElDatePicker', props: { modelValue: String, disabled: Boolean }, emits: ['update:modelValue'], setup(props, { emit }) { return () => h('input', { value: props.modelValue, disabled: props.disabled, onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value) }) } })
const Pass = (name: string) => defineComponent({ name, setup(_p, { slots }) { return () => h('div', slots.default?.()) } })
const ElAlertStub = defineComponent({ name: 'ElAlert', props: { title: String }, setup(props) { return () => h('div', { role: 'alert' }, props.title) } })
const AiCardStub = defineComponent({ name: 'AiOperationCard', setup() { return () => h('div', { 'data-ai-card': 'true' }) } })

const stubs = { ElButton: ElButtonStub, ElInput: ElInputStub, ElSelect: ElSelectStub, ElDatePicker: ElDateStub, ElOption: Pass('ElOption'), ElForm: Pass('ElForm'), ElFormItem: Pass('ElFormItem'), ElCard: Pass('ElCard'), ElAlert: ElAlertStub, AiOperationCard: AiCardStub }

const preview = { status: 'PREVIEW', intentCode: 'PURCHASE_ORDER_CREATE', resultData: { grantId: 'preview-7' }, confirmableAction: { confirmToken: 'proof', commandDigest: 'digest', expiresAt: '2026-10-20T23:59:00Z', previewData: { grantId: 'preview-7' } } }

async function mountPage() {
  const wrapper = mount(PurchasePage, { global: { stubs } })
  await flushPromises()
  const vm = wrapper.vm as any
  vm.form.supplierId = 'SUP-1'; vm.form.materialTypeId = 'MAT-1'; vm.form.quantity = '2'; vm.form.unitPrice = '10'; vm.form.amountCap = '22.60'
  await nextTick()
  return wrapper
}
function buttonWithText(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('button').find(candidate => candidate.text().includes(text))
  if (!button) throw new Error(`missing button: ${text}`)
  return button
}

beforeEach(() => {
  harness.factoryId = 'F006'; harness.userId = '42'; factoryScope.value = 'F006'; userScope.value = '42'; harness.get.mockReset(); harness.post.mockReset(); harness.push.mockReset()
  harness.get.mockImplementation((url: string) => Promise.resolve({ success: true, data: url.endsWith('/timer') ? null : [] }))
  harness.post.mockResolvedValue({ success: true, data: preview })
  localStorage.clear()
})

describe('deferred purchase grant page', () => {
  const binding = { bindingId: 'binding-7', grantId: 'preview-7', taskId: 'task-7', executeAt: '2099-01-01T01:02:03', expiresAt: '2099-01-01T01:03:00', state: 'PENDING', approvedSnapshot: '{"supplierId":"SUP-1","quantity":"2"}' }

  async function authorizePage() {
    const wrapper = await mountPage()
    await buttonWithText(wrapper, '生成预览').trigger('click'); await flushPromises()
    harness.post.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'PENDING', version: 1 } })
    await buttonWithText(wrapper, '仅授权，稍后执行').trigger('click'); await flushPromises()
    return wrapper
  }

  it('binds through real mounted time control with only version/time and displays persisted snapshot', async () => {
    const wrapper = await authorizePage()
    await wrapper.get('#purchase-timer-at').setValue('2099-01-01T01:02:03')
    harness.post.mockResolvedValueOnce({ success: true, data: binding })
    await buttonWithText(wrapper, '设置一次定时执行').trigger('click'); await flushPromises()
    expect(harness.post).toHaveBeenLastCalledWith('/F006/ai-purchase-task-grants/preview-7/timer', { expectedVersion: 1, executeAt: new Date('2099-01-01T01:02:03').toISOString() })
    expect(wrapper.text()).toContain('task-7')
    expect(wrapper.text()).toContain('SUP-1')
    expect(wrapper.text()).not.toContain('执行已授权采购')
    expect(wrapper.text()).not.toContain('设置一次定时执行')
    expect(wrapper.text()).toContain('撤销当前授权')
    wrapper.unmount()
  })

  it('never repeats an uncertain binding or falls back to manual execution before persisted readback', async () => {
    const wrapper = await authorizePage()
    await wrapper.get('#purchase-timer-at').setValue('2099-01-01T01:02:03')
    harness.post.mockRejectedValueOnce(new Error('connection outcome unknown'))
    const count = harness.post.mock.calls.length
    await buttonWithText(wrapper, '设置一次定时执行').trigger('click'); await flushPromises()
    expect(harness.post.mock.calls.length).toBe(count + 1)
    expect(wrapper.text()).not.toContain('执行已授权采购')
    expect(wrapper.text()).not.toContain('设置一次定时执行')
    harness.get.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'PENDING', version: 1 } })
    harness.get.mockResolvedValueOnce({ success: true, data: { ...binding, state: 'UNKNOWN', failureReason: '待持久结果核对' } })
    await buttonWithText(wrapper, '刷新授权状态').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('UNKNOWN')
    expect(wrapper.text()).not.toContain('执行已授权采购')
    expect(harness.post.mock.calls.length).toBe(count + 1)
    wrapper.unmount()
  })

  it('recovers the same timer after remount instead of offering another execution', async () => {
    localStorage.setItem('cretas:controlled-purchase:F006:42', JSON.stringify({ grantId: 'preview-7' }))
    harness.get.mockImplementation((url: string) => Promise.resolve({ success: true, data: url.endsWith('/timer') ? binding : url.endsWith('/preview-7') ? { grantId: 'preview-7', state: 'PENDING', version: 1 } : [] }))
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('task-7')
    expect(wrapper.text()).not.toContain('执行已授权采购')
    expect(wrapper.text()).not.toContain('设置一次定时执行')
    expect(harness.post).not.toHaveBeenCalled()
    factoryScope.value = 'F007'; await nextTick(); await flushPromises()
    expect(wrapper.text()).not.toContain('task-7')
    wrapper.unmount()
  })

  it('retains expired timer evidence across remount and uses explicit reconcile without execution', async () => {
    localStorage.setItem('cretas:controlled-purchase:F006:42', JSON.stringify({ grantId: 'preview-7' }))
    harness.get.mockImplementation((url: string) => Promise.resolve({ success: true, data: url.endsWith('/timer') ? { ...binding, state: 'UNKNOWN' } : url.endsWith('/preview-7') ? { grantId: 'preview-7', state: 'EXPIRED', version: 1 } : [] }))
    const wrapper = await mountPage()
    expect(localStorage.getItem('cretas:controlled-purchase:F006:42')).toContain('preview-7')
    expect(wrapper.text()).toContain('UNKNOWN')
    expect(wrapper.text()).not.toContain('执行已授权采购')
    harness.post.mockResolvedValueOnce({ success: true, data: { ...binding, state: 'REFUSED', failureReason: '授权已自然过期' } })
    await buttonWithText(wrapper, '核对定时执行结果（不重试）').trigger('click'); await flushPromises()
    expect(harness.post).toHaveBeenLastCalledWith('/F006/ai-purchase-task-grants/preview-7/timer/reconcile', {})
    expect(harness.post).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('授权已自然过期')
    await buttonWithText(wrapper, '已核对定时结果，开始新采购').trigger('click'); await flushPromises()
    expect(localStorage.getItem('cretas:controlled-purchase:F006:42')).toBeNull()
    expect(wrapper.text()).not.toContain('task-7')
    wrapper.unmount()
  })

  it('exposes explicit PENDING missed-task recovery and never invokes purchase execution', async () => {
    localStorage.setItem('cretas:controlled-purchase:F006:42', JSON.stringify({ grantId: 'preview-7' }))
    harness.get.mockImplementation((url: string) => Promise.resolve({ success: true, data: url.endsWith('/timer') ? binding : url.endsWith('/preview-7') ? { grantId: 'preview-7', state: 'PENDING', version: 1 } : [] }))
    const wrapper = await mountPage()
    harness.post.mockResolvedValueOnce({ success: true, data: { ...binding, state: 'REFUSED', failureReason: '已错过执行时刻；禁止补发采购' } })
    await buttonWithText(wrapper, '核对已错过的定时任务（不执行）').trigger('click'); await flushPromises()
    expect(harness.post).toHaveBeenCalledExactlyOnceWith('/F006/ai-purchase-task-grants/preview-7/timer/reconcile', {})
    expect(wrapper.text()).toContain('已错过执行时刻')
    expect(wrapper.text()).not.toContain('执行已授权采购')
    expect(wrapper.text()).not.toContain('设置一次定时执行')
    wrapper.unmount()
  })

  it('keeps a revoked timer reference until its real terminal result is explicitly acknowledged', async () => {
    const wrapper = await authorizePage()
    await wrapper.get('#purchase-timer-at').setValue('2099-01-01T01:02:03')
    harness.post.mockResolvedValueOnce({ success: true, data: binding })
    await buttonWithText(wrapper, '设置一次定时执行').trigger('click'); await flushPromises()
    harness.post.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'REVOKED', version: 2 } })
    await buttonWithText(wrapper, '撤销当前授权').trigger('click'); await flushPromises()
    expect(localStorage.getItem('cretas:controlled-purchase:F006:42')).toContain('preview-7')
    expect(wrapper.text()).toContain('task-7')
    expect(wrapper.text()).not.toContain('执行已授权采购')
    harness.get.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'REVOKED', version: 2 } })
    harness.get.mockResolvedValueOnce({ success: true, data: { ...binding, state: 'REFUSED', failureReason: 'grant is revoked' } })
    await buttonWithText(wrapper, '刷新定时结果').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('grant is revoked')
    expect(localStorage.getItem('cretas:controlled-purchase:F006:42')).not.toBeNull()
    await buttonWithText(wrapper, '已核对定时结果，开始新采购').trigger('click'); await flushPromises()
    expect(localStorage.getItem('cretas:controlled-purchase:F006:42')).toBeNull()
    wrapper.unmount()
  })

  it.each(['found', 'inconclusive', 'absent'] as const)('reads back uncertain binding on direct revoke and preserves trace unless definitely absent: %s', async readback => {
    const wrapper = await authorizePage()
    await wrapper.get('#purchase-timer-at').setValue('2099-01-01T01:02:03')
    harness.post.mockRejectedValueOnce(new Error('bind response lost after possible commit'))
    await buttonWithText(wrapper, '设置一次定时执行').trigger('click'); await flushPromises()
    expect(wrapper.text()).not.toContain('task-7')
    harness.post.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'REVOKED', version: 2 } })
    if (readback === 'inconclusive') harness.get.mockRejectedValueOnce(new Error('readback unavailable'))
    else harness.get.mockResolvedValueOnce({ success: true, data: readback === 'found' ? binding : null })
    const before = harness.post.mock.calls.length
    await buttonWithText(wrapper, '撤销当前授权').trigger('click'); await flushPromises()
    expect(harness.post.mock.calls.length).toBe(before + 1)
    expect(harness.get).toHaveBeenLastCalledWith('/F006/ai-purchase-task-grants/preview-7/timer')
    if (readback === 'absent') {
      expect(localStorage.getItem('cretas:controlled-purchase:F006:42')).toBeNull()
      expect(buttonWithText(wrapper, '生成预览').attributes('disabled')).toBeUndefined()
    } else {
      expect(localStorage.getItem('cretas:controlled-purchase:F006:42')).toContain('preview-7')
      expect(wrapper.text()).not.toContain('执行已授权采购')
      if (readback === 'found') expect(wrapper.text()).toContain('task-7')
      else {
        expect(wrapper.text()).toContain('readback unavailable')
        expect(wrapper.text()).toContain('刷新定时绑定状态')
        harness.get.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'REVOKED', version: 2 } })
        harness.get.mockResolvedValueOnce({ success: true, data: binding })
        await buttonWithText(wrapper, '刷新定时绑定状态').trigger('click'); await flushPromises()
        expect(wrapper.text()).toContain('task-7')
        expect(localStorage.getItem('cretas:controlled-purchase:F006:42')).toContain('preview-7')
        expect(harness.post.mock.calls.length).toBe(before + 1)
      }
    }
    wrapper.unmount()
  })

  it.each(['authorize', 'execute'] as const)('releases the new scope lock after a stale %s request', async operation => {
    const wrapper = await mountPage()
    await buttonWithText(wrapper, '生成预览').trigger('click'); await flushPromises()
    if (operation === 'execute') {
      harness.post.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'PENDING', version: 1 } })
      await buttonWithText(wrapper, '仅授权，稍后执行').trigger('click'); await flushPromises()
    }
    let resolveOld!: (value: unknown) => void
    harness.post.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve }))
    await buttonWithText(wrapper, operation === 'authorize' ? '仅授权，稍后执行' : '执行已授权采购').trigger('click')
    factoryScope.value = 'F007'; await nextTick(); await flushPromises()
    const vm = wrapper.vm as unknown as { form: Record<string, string> }
    Object.assign(vm.form, { supplierId: 'SUP-2', materialTypeId: 'MAT-2', quantity: '2', unitPrice: '10', amountCap: '22.60' })
    const newPreview = { ...preview, resultData: { grantId: 'preview-8' }, confirmableAction: { ...preview.confirmableAction, confirmToken: 'new-proof', previewData: { grantId: 'preview-8' } } }
    harness.post.mockResolvedValueOnce({ success: true, data: newPreview })
    await buttonWithText(wrapper, '生成预览').trigger('click'); await flushPromises()
    let resolveNew!: (value: unknown) => void
    harness.post.mockReturnValueOnce(new Promise(resolve => { resolveNew = resolve }))
    const before = harness.post.mock.calls.length
    await buttonWithText(wrapper, '仅授权，稍后执行').trigger('click'); await flushPromises()
    expect(harness.post.mock.calls.length).toBe(before + 1)
    expect(harness.post).toHaveBeenLastCalledWith('/F007/ai-purchase-task-grants/authorize-later', expect.objectContaining({ proofToken: 'new-proof' }))
    resolveOld({ success: true, data: { grantId: 'preview-7', state: operation === 'authorize' ? 'PENDING' : 'DRAFT', version: 2, purchaseOrderId: 'OLD-PO' } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('OLD-PO')
    await buttonWithText(wrapper, '仅授权，稍后执行').trigger('click'); await flushPromises()
    expect(harness.post.mock.calls.length).toBe(before + 1)
    resolveNew({ success: true, data: { grantId: 'preview-8', state: 'PENDING', version: 1 } }); await flushPromises()
    expect(wrapper.text()).toContain('执行已授权采购')
    expect(localStorage.getItem('cretas:controlled-purchase:F007:42')).toContain('preview-8')
    wrapper.unmount()
  })

  it('mounts the real page and locks the pending form after authorize-later', async () => {
    const wrapper = await mountPage()
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('仅授权，稍后执行')
    harness.post.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'PENDING', version: 1 } })
    await buttonWithText(wrapper, '仅授权，稍后执行').trigger('click')
    await flushPromises()
    expect(harness.post).toHaveBeenCalledWith('/F006/ai-purchase-task-grants/authorize-later', expect.objectContaining({ proofToken: 'proof', commandDigest: 'digest' }))
    expect(wrapper.text()).toContain('执行已授权采购')
    expect(wrapper.text()).not.toContain('仅授权，稍后执行')
    expect(localStorage.getItem('cretas:controlled-purchase:F006:42')).toContain('preview-7')
  })

  it('keeps UNKNOWN terminal and removes one-shot retry controls after authorize failure', async () => {
    const wrapper = await mountPage(); await wrapper.get('button').trigger('click'); await flushPromises()
    harness.post.mockRejectedValueOnce(new Error('网络状态未知'))
    await buttonWithText(wrapper, '仅授权，稍后执行').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('刷新授权状态')
    expect(wrapper.text()).not.toContain('仅授权，稍后执行')
    expect(wrapper.text()).not.toContain('执行已授权采购')
    expect(wrapper.text()).toContain('刷新授权状态')
  })

  it('executes pending only with the persisted version', async () => {
    const wrapper = await mountPage(); await wrapper.get('button').trigger('click'); await flushPromises()
    harness.post.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'PENDING', version: 1 } })
    await buttonWithText(wrapper, '仅授权，稍后执行').trigger('click'); await flushPromises()
    harness.post.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'DRAFT', version: 2, purchaseOrderId: 'PO-7' } })
    await wrapper.get('button').trigger('click'); await flushPromises()
    expect(harness.post).toHaveBeenLastCalledWith('/F006/ai-purchase-task-grants/preview-7/execute-skill', { expectedVersion: 1 })
  })

  it('retains the existing explicit HTTP adapter and fixed Tool adapter without accepting identity or command payload', async () => {
    harness.post.mockResolvedValue({ success: true, data: { grantId: 'preview-7', state: 'DRAFT', version: 2, purchaseOrderId: 'PO-7' } })
    await executeDeferredControlledPurchase('F006', 'preview-7', 1)
    expect(harness.post).toHaveBeenLastCalledWith('/F006/ai-purchase-task-grants/preview-7/execute', { expectedVersion: 1 })
    await executeDeferredControlledPurchase('F006', 'preview-7', 1, 'tool')
    expect(harness.post).toHaveBeenLastCalledWith('/F006/ai-purchase-task-grants/preview-7/execute-tool', { expectedVersion: 1 })
  })

  it('does not retry an uncertain Skill execution or fall back to direct HTTP', async () => {
    const wrapper = await mountPage(); await wrapper.get('button').trigger('click'); await flushPromises()
    harness.post.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'PENDING', version: 1 } })
    await buttonWithText(wrapper, '仅授权，稍后执行').trigger('click'); await flushPromises()
    harness.post.mockRejectedValueOnce(new Error('采购执行结果未知，请查询原授权状态'))
    const before = harness.post.mock.calls.length
    await buttonWithText(wrapper, '执行已授权采购').trigger('click'); await flushPromises()
    expect(harness.post.mock.calls.length).toBe(before + 1)
    expect(harness.post).toHaveBeenLastCalledWith('/F006/ai-purchase-task-grants/preview-7/execute-skill', { expectedVersion: 1 })
    expect(wrapper.text()).not.toContain('执行已授权采购')
    expect(wrapper.text()).toContain('刷新授权状态')
    expect(wrapper.text()).toContain('结果未知')
    wrapper.unmount()
  })

  it('drops stale preview responses after the authenticated factory scope changes', async () => {
    let resolvePreview!: (value: unknown) => void
    harness.post.mockReturnValueOnce(new Promise(resolve => { resolvePreview = resolve }))
    const wrapper = await mountPage(); await wrapper.get('button').trigger('click')
    factoryScope.value = 'F007'; await nextTick()
    resolvePreview({ success: true, data: preview }); await flushPromises()
    expect(wrapper.text()).not.toContain('仅授权，稍后执行')
  })

  it('does not execute a pending grant until its version is recovered', async () => {
    const wrapper = await mountPage(); await wrapper.get('button').trigger('click'); await flushPromises()
    harness.post.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'PENDING', version: null } })
    await buttonWithText(wrapper, '仅授权，稍后执行').trigger('click'); await flushPromises()
    const execute = buttonWithText(wrapper, '执行已授权采购')
    expect(execute.attributes('disabled')).toBeDefined()
    const postCount = harness.post.mock.calls.length
    await execute.trigger('click')
    expect(harness.post.mock.calls.length).toBe(postCount)
    expect(wrapper.text()).toContain('授权版本尚未恢复')
  })

  it('revokes the original pending preview after uncertain authorization recovers without a grant version', async () => {
    const wrapper = await mountPage(); await buttonWithText(wrapper, '生成预览').trigger('click'); await flushPromises()
    harness.post.mockRejectedValueOnce(new Error('授权传输状态未知'))
    await buttonWithText(wrapper, '仅授权，稍后执行').trigger('click'); await flushPromises()
    harness.get.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'PENDING', version: null } })
    await buttonWithText(wrapper, '刷新授权状态').trigger('click'); await flushPromises()
    expect(buttonWithText(wrapper, '执行已授权采购').attributes('disabled')).toBeDefined()
    const revoke = buttonWithText(wrapper, '撤销当前授权')
    expect(revoke.attributes('disabled')).toBeUndefined()
    harness.post.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'REVOKED', version: null } })
    await revoke.trigger('click'); await flushPromises()
    expect(harness.post).toHaveBeenLastCalledWith('/F006/ai-purchase-task-grants/preview-7/revoke', expect.objectContaining({ expectedVersion: null }))
    expect(localStorage.getItem('cretas:controlled-purchase:F006:42')).toBeNull()
    expect(buttonWithText(wrapper, '生成预览').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('drops stale authorize and execute responses after factory scope changes', async () => {
    const wrapper = await mountPage(); await wrapper.get('button').trigger('click'); await flushPromises()
    let resolveAuthorize!: (value: unknown) => void
    harness.post.mockReturnValueOnce(new Promise(resolve => { resolveAuthorize = resolve }))
    await buttonWithText(wrapper, '仅授权，稍后执行').trigger('click')
    factoryScope.value = 'F007'; await nextTick()
    resolveAuthorize({ success: true, data: { grantId: 'preview-7', state: 'PENDING', version: 1 } }); await flushPromises()
    expect(wrapper.text()).not.toContain('执行已授权采购')

    factoryScope.value = 'F006'; await nextTick(); await flushPromises()
    localStorage.clear()
    harness.post.mockReset(); harness.post.mockResolvedValue({ success: true, data: preview })
    const fresh = await mountPage(); await fresh.get('button').trigger('click'); await flushPromises()
    harness.post.mockResolvedValueOnce({ success: true, data: { grantId: 'preview-7', state: 'PENDING', version: 1 } })
    await buttonWithText(fresh, '仅授权，稍后执行').trigger('click'); await flushPromises()
    let resolveExecute!: (value: unknown) => void
    harness.post.mockReturnValueOnce(new Promise(resolve => { resolveExecute = resolve }))
    await buttonWithText(fresh, '执行已授权采购').trigger('click')
    factoryScope.value = 'F007'; await nextTick()
    resolveExecute({ success: true, data: { grantId: 'preview-7', state: 'DRAFT', version: 2, purchaseOrderId: 'PO-7' } }); await flushPromises()
    expect(fresh.text()).not.toContain('采购草稿已创建')
  })
})
