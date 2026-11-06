import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref, type Ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { executeStep, initializeDemo, navigation } = vi.hoisted(() => ({ executeStep: vi.fn(), initializeDemo: vi.fn(), navigation: { route: null as Ref<{path:string;fullPath:string}> | null, push: vi.fn() } }))
vi.mock('@/api/request', () => ({ default: { get: vi.fn().mockResolvedValue({ data: [] }) } }))
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006', currentRole: 'factory_super_admin', user: { id: 1309, username: 'f006_admin', factoryUser: { factoryId: 'F006' } } }) }))
vi.mock('vue-router', async () => { const {ref}=await import('vue'); navigation.route=ref({path:'/demo-flow',fullPath:'/demo-flow'}); return {useRouter:()=>({push:navigation.push,currentRoute:navigation.route,resolve:(path:string)=>({fullPath:path})})} })
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
import { presentationEnabled } from '@/demo/f006Presentation'

describe('F006 demo playback lifecycle', () => {
  beforeEach(() => {
    sessionStorage.clear()
    presentationEnabled.value = false
    navigation.route!.value = {path:'/demo-flow',fullPath:'/demo-flow'}
    navigation.push.mockReset().mockImplementation(async (path:string)=>{navigation.route!.value={path:path.split('?')[0]!,fullPath:path}})
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    vi.useFakeTimers()
    initializeDemo.mockReset().mockImplementation(async (state: unknown) => state)
    executeStep.mockReset().mockImplementation(async (state: { currentStep: number }) => ({ ...state, currentStep: state.currentStep + 1, readbacks: {}, entities: {}, completed: [] }))
  })

  it('disables continuous playback until live demo initialization finishes', async () => {
    let finish!: () => void
    initializeDemo.mockImplementationOnce((state: unknown) => new Promise(resolve => { finish = () => resolve(state) }))
    const wrapper = mount(DemoFlow, { global: { stubs: { 'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' } } } })
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
    expect(executeStep).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(9999)
    expect(executeStep).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(executeStep).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    vi.useRealTimers()
  })

  it('does not execute a scheduled next step after unmount', async () => {
    const wrapper = mount(DemoFlow, { global: { stubs: { 'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' } } } })
    await flushPromises()
    const playbackButton = wrapper.findAll('button').find(button => button.text().includes('连续演示'))
    expect(playbackButton).toBeDefined()
    await playbackButton!.trigger('click')
    await flushPromises()
    expect(executeStep).not.toHaveBeenCalled()
    wrapper.unmount()
    vi.advanceTimersByTime(30000)
    expect(executeStep).not.toHaveBeenCalled()
    vi.useRealTimers()
  })

  it('AI fill only changes the local form and never executes the business step', async () => {
    const wrapper = mount(DemoFlow, { global: { stubs: { 'el-button': { emits: ['click'], template: '<button><slot /></button>' } } } })
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
    const wrapper = mount(Host, { global: { stubs: { 'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' } } } })
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
  it('separates full form and result holds, and resumes paused playback with a fresh hold', async () => {
    const wrapper = mount(DemoFlow, { global: { stubs: { 'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' } } } })
    try {
      await flushPromises()
      const click = async (name: string) => { await wrapper.findAll('button').find(button => button.text().includes(name))!.trigger('click'); await flushPromises() }
      await click('连续演示')
      await vi.advanceTimersByTimeAsync(10000)
      expect(executeStep).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(500)
      expect(wrapper.text()).toContain('执行结果展示')
      await vi.advanceTimersByTimeAsync(5000)
      await click('暂停演示')
      await vi.advanceTimersByTimeAsync(60000)
      expect(executeStep).toHaveBeenCalledTimes(1)
      expect(wrapper.text()).toContain('已暂停')
      await click('继续演示')
      await vi.advanceTimersByTimeAsync(500)
      await vi.advanceTimersByTimeAsync(9999)
      expect(wrapper.text()).toContain('执行结果展示')
      expect(executeStep).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(501)
      expect(wrapper.text()).toContain('预填内容展示')
      await vi.advanceTimersByTimeAsync(9999)
      expect(executeStep).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(1)
      expect(executeStep).toHaveBeenCalledTimes(2)
    } finally { wrapper.unmount(); vi.useRealTimers() }
  })

  it('pauses unexpected menu navigation even while the intended business page is loading', async () => {
    let release!: () => void
    navigation.push.mockImplementationOnce((path:string)=>{navigation.route!.value={path,fullPath:path};return new Promise<void>(resolve=>{release=resolve})})
    const wrapper=mount(DemoFlow,{global:{stubs:{'el-button':{emits:['click'],template:'<button @click="$emit(\'click\')"><slot /></button>'}}}})
    try {
      await flushPromises()
      await wrapper.findAll('button').find(button=>button.text().includes('连续演示'))!.trigger('click')
      await vi.advanceTimersByTimeAsync(10000)
      expect(executeStep).toHaveBeenCalledTimes(1)
      navigation.route!.value={path:'/warehouse/materials',fullPath:'/warehouse/materials'}
      await nextTick()
      expect(wrapper.text()).toContain('已暂停')
      release(); await vi.advanceTimersByTimeAsync(30500)
      expect(executeStep).toHaveBeenCalledTimes(1)
    } finally { wrapper.unmount(); vi.useRealTimers() }
  })

})
