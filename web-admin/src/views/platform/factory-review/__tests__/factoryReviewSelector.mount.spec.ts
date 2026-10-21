import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import type { FactoryUser, PlatformUser } from '@/types/auth'
import { useAuthStore } from '@/store/modules/auth'
import { getFactoryBuildReview, listFactories } from '@/api/factoryOnboardingApi'
import FactoryReview from '../index.vue'

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/api/factoryOnboardingApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/factoryOnboardingApi')>()),
  listFactories: vi.fn(),
  getFactoryBuildReview: vi.fn(),
  getCurrentCanonicalFactoryBlueprint: vi.fn(),
  countLiveRows: vi.fn(),
  countProductProcessRows: vi.fn(),
}))

// Same shape the production login stores for a platform super admin (2026-10-21 read-back).
const platformAdmin: PlatformUser = {
  id: 1, username: 'platform_admin', fullName: '平台超级管理员', email: 'admin@example.test', isActive: true,
  createdAt: '2026-01-01T00:00:00', updatedAt: '2026-01-01T00:00:00',
  userType: 'platform', platformUser: { role: 'super_admin', permissions: [] },
}
const factoryAdmin: FactoryUser = {
  id: 2, username: 'f006_admin', email: 'f006@example.test', isActive: true,
  createdAt: '2026-01-01T00:00:00', updatedAt: '2026-01-01T00:00:00',
  userType: 'factory', factoryUser: { role: 'factory_super_admin', factoryId: 'F006', permissions: [] },
}

const wrappers: VueWrapper[] = []
function render() {
  const wrapper = mount(FactoryReview, {
    global: { plugins: [ElementPlus], stubs: { FactoryReadiness: true, FactoryBlueprintAcceptance: true } },
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
  vi.mocked(listFactories).mockResolvedValue([{ id: 'F006', name: '六膳门食品科技' }])
  vi.mocked(getFactoryBuildReview).mockResolvedValue({ success: true, data: { planned: [], result: null } } as never)
})
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

describe('建厂复核的工厂选择器', () => {
  it('平台超管（登录态归一为 platform_admin）看得到选择器并加载工厂列表', async () => {
    useAuthStore().setUser(platformAdmin)
    expect(useAuthStore().currentRole).toBe('platform_admin')
    const wrapper = render(); await flushPromises()
    expect(listFactories).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.factory-select').exists()).toBe(true)
    expect(wrapper.find('.factory-context').exists()).toBe(false)
    // No factory chosen yet: nothing is read until the admin picks one.
    expect(getFactoryBuildReview).not.toHaveBeenCalled()
  })

  it('工厂账号没有选择器，只读取自己 token 所属的工厂', async () => {
    useAuthStore().setUser(factoryAdmin)
    const wrapper = render(); await flushPromises()
    expect(listFactories).not.toHaveBeenCalled()
    expect(wrapper.find('.factory-select').exists()).toBe(false)
    expect(wrapper.find('.factory-context').text()).toContain('F006')
    expect(getFactoryBuildReview).toHaveBeenCalledWith('F006')
  })
})
