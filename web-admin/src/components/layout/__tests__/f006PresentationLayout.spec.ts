import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { reactive, defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { expect, it, vi } from 'vitest'
import { presentationEnabled } from '@/demo/f006Presentation'

const state = vi.hoisted(() => ({ auth: null as null | { factoryId: string; currentRole: string; user: { id: number } }, mounts: 0 }))
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => state.auth }))
vi.mock('@/store/modules/app', () => ({ useAppStore: () => ({ initResponsive() {}, isMobile: false, currentSidebarWidth: 220 }) }))
vi.mock('@/demo/f006Sandbox', async () => {
  const { ref } = await import('vue')
  return { sandboxSession: ref({sessionId:'owned-session',status:'READY'}), recoverActiveSandbox: vi.fn().mockResolvedValue(undefined), selectSandboxActor: vi.fn() }
})
vi.mock('@/views/demo-flow/index.vue', async () => {
  const { defineComponent, h, onMounted } = await import('vue')
  return { default: defineComponent({ props: { compact: Boolean }, setup(props) { onMounted(() => {state.mounts++}); return () => h('section', { 'data-testid':'persistent-guide', 'data-compact':String(props.compact) },'guide') } }) }
})
import Layout from '../AppLayout.vue'

it('keeps one visible controller across actual business menus and removes it on tenant change', async () => {
  sessionStorage.clear(); presentationEnabled.value = false; state.mounts = 0
  state.auth = reactive({factoryId:'F006',currentRole:'factory_super_admin',user:{id:1309}})
  const router = createRouter({history:createMemoryHistory(), routes:[
    {path:'/demo-flow',component:defineComponent({render:()=>h('p','should not duplicate guide')}),meta:{module:'production'}},
    {path:'/warehouse/materials',component:defineComponent({render:()=>h('p',{'data-testid':'actual-warehouse'},'actual warehouse menu')}),meta:{module:'warehouse'}},
  ]})
  await router.push('/demo-flow'); await router.isReady()
  const wrapper = mount(Layout,{global:{plugins:[createPinia(),router],stubs:{AppSidebar:true,AppHeader:true,WorkspaceTabs:true,WorkspaceReferencePane:true,ReturnBanner:true,InlineCustomerService:true,F006SandboxBanner:true}}})
  try {
    await flushPromises(); await flushPromises()
    expect(wrapper.findAll('[data-testid="persistent-guide"]')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('should not duplicate guide')
    presentationEnabled.value = true
    await router.push('/warehouse/materials'); await flushPromises()
    expect(wrapper.find('[data-testid="actual-warehouse"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="persistent-guide"]').attributes('data-compact')).toBe('true')
    expect(state.mounts).toBe(1)
    state.auth.factoryId = 'F007'; await flushPromises()
    expect(wrapper.find('[data-testid="persistent-guide"]').exists()).toBe(false)
    expect(presentationEnabled.value).toBe(false)
  } finally { wrapper.unmount(); presentationEnabled.value = false }
})
