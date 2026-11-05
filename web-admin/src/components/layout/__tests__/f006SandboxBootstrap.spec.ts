import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
const fixture = vi.hoisted(() => ({
  get: vi.fn(),
  auth: { factoryId: 'F006' as string | undefined, currentRole: 'factory_super_admin', user: { id: 1309 } },
}))
vi.mock('@/api/request', () => ({ default: { get: fixture.get } }))
vi.mock('@/store/modules/auth', async () => {
  const { reactive } = await import('vue')
  fixture.auth = reactive(fixture.auth)
  return { useAuthStore: () => fixture.auth }
})
vi.mock('@/store/modules/app', () => ({ useAppStore: () => ({ initResponsive: vi.fn(), isMobile: false, currentSidebarWidth: 220 }) }))
vi.mock('@/store/modules/workspace', () => ({ useWorkspaceStore: () => ({ tabs: [], setScope: vi.fn(), openRoute: vi.fn(), cacheKeyFor: (s: string) => s }), workspaceRouteKey: () => 'demo' }))
vi.mock('vue-router', () => ({ useRoute: () => ({ path: '/demo-flow', fullPath: '/demo-flow', name: 'demo', meta: {}, query: {} }), useRouter: () => ({ replace: vi.fn() }) }))
import { sandboxSession } from '@/demo/f006Sandbox'
import Layout from '../AppLayout.vue'
const RouterView = defineComponent({ setup: () => () => h('div', { 'data-business-view': 'true' }) })
function create() { return mount(Layout, { shallow: true, global: { stubs: { 'router-view': RouterView } } }) }
beforeEach(() => { sandboxSession.value = null; localStorage.clear(); fixture.auth.factoryId = 'F006'; fixture.auth.user = { id: 1309 }; fixture.get.mockReset() })
it('keeps business views closed while F006 user identity is still loading', async () => {
  Reflect.set(fixture.auth, 'user', undefined)
  const wrapper = create(); await flushPromises()
  expect(wrapper.find('[data-business-view]').exists()).toBe(false)
  expect(fixture.get).not.toHaveBeenCalled()
  fixture.get.mockResolvedValue({ success: true, data: null })
  fixture.auth.user = { id: 1309 }; await flushPromises()
  expect(wrapper.find('[data-business-view]').exists()).toBe(true)
  wrapper.unmount()
})
it('does not mount a business view until server confirms there is no active sandbox', async () => {
  let resolve!: (value: unknown) => void
  fixture.get.mockReturnValue(new Promise(r => { resolve = r }))
  const wrapper = create()
  expect(wrapper.find('[data-business-view]').exists()).toBe(false)
  resolve({ success: true, data: null }); await flushPromises()
  expect(wrapper.find('[data-business-view]').exists()).toBe(true)
  wrapper.unmount()
})
it('recloses the boundary synchronously when switching into F006', async () => {
  fixture.auth.factoryId = 'F001'
  const wrapper = create(); await flushPromises()
  expect(wrapper.find('[data-business-view]').exists()).toBe(true)
  let resolve!: (value: unknown) => void
  fixture.get.mockReturnValue(new Promise(r => { resolve = r }))
  fixture.auth.factoryId = 'F006'; await nextTick()
  expect(wrapper.find('[data-business-view]').exists()).toBe(false)
  resolve({ success: true, data: null }); await flushPromises()
  expect(wrapper.find('[data-business-view]').exists()).toBe(true)
  wrapper.unmount()
})
it('blocks business views on an unknown server state rather than assuming production is safe', async () => {
  fixture.get.mockRejectedValue(new Error('manager unavailable'))
  const wrapper = create(); await flushPromises()
  expect(wrapper.find('[data-business-view]').exists()).toBe(false)
  wrapper.unmount()
})
