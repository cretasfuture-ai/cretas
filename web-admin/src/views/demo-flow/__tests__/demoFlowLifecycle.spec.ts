import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { executeStep, initializeDemo } = vi.hoisted(() => ({ executeStep: vi.fn(), initializeDemo: vi.fn() }))
vi.mock('@/api/request', () => ({ default: { get: vi.fn().mockResolvedValue({ data: [] }) } }))
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006', currentRole: 'factory_super_admin', user: { id: 1309, username: 'f006_admin', factoryUser: { factoryId: 'F006' } } }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/demo/f006Sandbox', () => ({
  sandboxSession: { value: { sessionId: 'test-sandbox', status: 'READY' } },
  SANDBOX_FLOW_STORAGE_KEY: 'cretas:f006:sandbox-flow:v1',
  requireSandboxReady: () => ({ sessionId: 'test-sandbox', status: 'READY' }),
  beginSandbox: vi.fn(),
}))
vi.mock('@/demo/f006Flow', () => {
  const steps = [
    { id: 'purchase-create', label: '创建采购订单', entity: 'purchaseOrder', fields: [{ name: 'quantity', label: '数量' }, { name: 'unitPrice', label: '价格' }] },
    { id: 'purchase-submit', label: '提交采购审批', entity: 'purchaseOrder', fields: [] },
  ]
  return {
    DEMO_STEPS: steps,
    createDemoState: () => ({ schemaVersion: 2, runKey: 'DEMO-F006-FLOW-20261105-test', factoryId: 'F006', currentStep: 0, entities: {}, completed: [], readbacks: {} }),
    prepareStep: (state: { currentStep: number }, step: string) => ({ step, fields: [], entityIds: {} }),
    initializeDemo,
    assertF006Session: () => undefined,
    executeStep,
  }
})

import DemoFlow from '../index.vue'

describe('F006 demo playback lifecycle', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.useFakeTimers()
    initializeDemo.mockReset().mockImplementation(async (state: unknown) => state)
    executeStep.mockReset().mockImplementation(async (state: { currentStep: number }) => ({ ...state, currentStep: state.currentStep + 1, readbacks: {}, entities: {}, completed: [] }))
  })

  it('disables continuous playback until live demo initialization finishes', async () => {
    let finish!: () => void
    initializeDemo.mockImplementationOnce((state: unknown) => new Promise(resolve => { finish = () => resolve(state) }))
    const wrapper = mount(DemoFlow, { global: { stubs: { 'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' } } } })
    await flushPromises()
    const playback = () => wrapper.findAll('button').find(button => button.text().includes('连续演示'))!
    expect(playback().attributes('disabled')).toBeDefined()
    await playback().trigger('click')
    expect(executeStep).not.toHaveBeenCalled()
    finish()
    await flushPromises()
    expect(playback().attributes('disabled')).toBeUndefined()
    await playback().trigger('click')
    await flushPromises()
    expect(executeStep).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    vi.useRealTimers()
  })

  it('does not execute a scheduled next step after unmount', async () => {
    const wrapper = mount(DemoFlow, { global: { stubs: { 'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' } } } })
    await flushPromises()
    const playbackButton = wrapper.findAll('button').find(button => button.text().includes('连续演示'))
    expect(playbackButton).toBeDefined()
    await playbackButton!.trigger('click')
    await flushPromises()
    expect(executeStep).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    vi.advanceTimersByTime(2000)
    expect(executeStep).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })

  it('AI fill only changes the local form and never executes the business step', async () => {
    const wrapper = mount(DemoFlow, { global: { stubs: { 'el-button': { template: '<button><slot /></button>' } } } })
    await flushPromises()
    vi.useRealTimers()
    await (wrapper.vm as unknown as { handleAiFill: (params: Record<string, unknown>) => Promise<void> }).handleAiFill({ items: [{ quantity: 2, unitPrice: 46 }] })
    expect((wrapper.vm as unknown as { form: Record<string, unknown> }).form).toMatchObject({ quantity: 2, unitPrice: 46 })
    expect(executeStep).not.toHaveBeenCalled()
  })

  it('reconciles an in-flight step resolved while deactivated, without autoplay', async () => {
    let resolveStep!: (state: unknown) => void
    executeStep.mockImplementationOnce((state: { currentStep: number }) => new Promise(resolve => {
      resolveStep = (next: unknown) => resolve(next)
    }))
    const Host = defineComponent({
      components: { DemoFlow },
      setup: () => { const shown = ref(true); return { shown } },
      template: '<KeepAlive><DemoFlow v-if="shown" /></KeepAlive>',
    })
    const wrapper = mount(Host, { global: { stubs: { 'el-button': { template: '<button @click="$emit(\'click\')"><slot /></button>' } } } })
    await flushPromises()
    const executeButton = wrapper.findAll('button').find(button => button.text().includes('执行当前步骤'))
    await executeButton!.trigger('click')
    await nextTick()
    wrapper.vm.shown = false
    await nextTick()
    sessionStorage.setItem('cretas:f006:sandbox-flow:v1', JSON.stringify({ schemaVersion: 2, sandboxSessionId: 'test-sandbox', runKey: 'DEMO-F006-FLOW-20261105-test', factoryId: 'F006', currentStep: 1, entities: {}, completed: ['purchase-create'], readbacks: {} }))
    resolveStep({ schemaVersion: 2, runKey: 'DEMO-F006-FLOW-20261105-test', factoryId: 'F006', currentStep: 1, entities: {}, completed: ['purchase-create'], readbacks: {} })
    await flushPromises()
    wrapper.vm.shown = true
    await nextTick()
    await flushPromises()
    expect(wrapper.findAll('button').some(button => button.text().includes('连续演示'))).toBe(true)
    expect(executeStep).toHaveBeenCalledTimes(1)
    const nextExecuteButton = wrapper.findAll('button').find(button => button.text().includes('执行当前步骤'))
    await nextExecuteButton!.trigger('click')
    await flushPromises()
    expect(executeStep).toHaveBeenCalledTimes(2)
  })
})
