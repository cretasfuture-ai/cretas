import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import FactorySelfReadinessPage from '../index.vue'
import { getMyFactoryReadiness, loadMyFactoryReadiness, type FactorySelfReadiness } from '@/api/factorySelfReadiness'
import type { FactoryReadinessReport } from '@/api/factoryReadiness'
import { useAuthStore } from '@/store/modules/auth'
import { ApiError } from '@/types/api'

vi.mock('@/api/factorySelfReadiness', () => ({ getMyFactoryReadiness: vi.fn(), loadMyFactoryReadiness: vi.fn() }))

const FactoryReadinessStub = {
  name: 'FactoryReadiness',
  props: ['factoryId', 'blueprintId', 'rowVersion', 'refreshKey', 'loader'],
  template: '<div data-testid="report-stub">{{ factoryId }}/{{ blueprintId }}/{{ rowVersion }}</div>',
}
const ready = (id = 'bp-1', rowVersion = 4): FactorySelfReadiness => ({
  state: 'READY_TO_READ',
  blueprint: { id, version: 2, rowVersion, lifecycleState: 'PUBLISHED' },
  readiness: { factoryId: 'F006', blueprintId: id } as FactoryReadinessReport,
})
const noBlueprint: FactorySelfReadiness = { state: 'NO_CANONICAL_BLUEPRINT', blueprint: null, readiness: null }

function signIn(factoryId: string) {
  useAuthStore().setUser({ id: 9, username: 'sa', email: 'sa@example.test', isActive: true,
    createdAt: '2026-01-01T00:00:00', updatedAt: '2026-01-01T00:00:00', userType: 'factory',
    factoryUser: { role: 'factory_super_admin', factoryId, permissions: [] } })
}
const wrappers: VueWrapper[] = []
function render() {
  const wrapper = mount(FactorySelfReadinessPage, {
    global: { plugins: [ElementPlus], stubs: { FactoryReadiness: FactoryReadinessStub } },
  })
  wrappers.push(wrapper)
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  vi.mocked(getMyFactoryReadiness).mockReset()
  signIn('F006')
})
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

describe('工厂自己的建厂就绪页', () => {
  it('reads only the signed-in factory and hands the report its own loader', async () => {
    vi.mocked(getMyFactoryReadiness).mockResolvedValue(ready())
    const wrapper = render(); await flushPromises()

    expect(getMyFactoryReadiness).toHaveBeenCalledTimes(1)
    expect(getMyFactoryReadiness).toHaveBeenCalledWith('F006')
    const report = wrapper.findComponent(FactoryReadinessStub)
    expect(report.props('factoryId')).toBe('F006')
    expect(report.props('blueprintId')).toBe('bp-1')
    expect(report.props('rowVersion')).toBe(4)
    expect(report.props('loader')).toBe(loadMyFactoryReadiness)
  })

  it('never picks the factory from the URL and never calls a platform route or a write', () => {
    const here = dirname(fileURLToPath(import.meta.url))
    // Comments describe what the page does not do; only code and template are checked.
    const code = (text: string) => text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
    const page = code(readFileSync(resolve(here, '..', 'index.vue'), 'utf8'))
    const client = code(readFileSync(resolve(here, '..', '..', '..', 'api', 'factorySelfReadiness.ts'), 'utf8'))
    expect(page).not.toMatch(/useRoute|\$route|query/)
    expect(page).not.toContain('/api/platform')
    expect(page).not.toMatch(/getFactoryBuildReview|apply|publish|submit/i)
    expect(client).not.toContain('/api/platform')
    expect(client).not.toMatch(/request\.(post|put|patch|delete)/)
  })

  it('says formal materials are missing without a canonical blueprint and invents no report', async () => {
    vi.mocked(getMyFactoryReadiness).mockResolvedValue(noBlueprint)
    const wrapper = render(); await flushPromises()

    expect(wrapper.find('[data-testid="self-readiness-no-blueprint"]').text()).toContain('缺正式资料')
    expect(wrapper.findComponent(FactoryReadinessStub).exists()).toBe(false)
    expect(wrapper.text()).not.toMatch(/已完成|可以开展|READY/)
  })

  it('shows the feature as not open when the backend switch is off, and other failures as failures', async () => {
    vi.mocked(getMyFactoryReadiness).mockRejectedValueOnce(new ApiError('Not Found', 'NOT_FOUND', 404))
    const wrapper = render(); await flushPromises()
    expect(wrapper.find('[data-testid="self-readiness-unavailable"]').exists()).toBe(true)

    vi.mocked(getMyFactoryReadiness).mockRejectedValueOnce(new ApiError('当前工厂账号没有建厂蓝图写入权限', 'CANONICAL_FACTORY_ROLE_REQUIRED', 403))
    await wrapper.find('[data-testid="self-readiness-refresh"]').trigger('click'); await flushPromises()
    expect(wrapper.find('[data-testid="self-readiness-error"]').text()).toContain('没有建厂蓝图写入权限')
    expect(wrapper.findComponent(FactoryReadinessStub).exists()).toBe(false)
  })

  it('re-reads the server on refresh instead of keeping what it showed', async () => {
    vi.mocked(getMyFactoryReadiness).mockResolvedValueOnce(noBlueprint).mockResolvedValueOnce(ready('bp-2', 1))
    const wrapper = render(); await flushPromises()
    expect(wrapper.find('[data-testid="self-readiness-no-blueprint"]').exists()).toBe(true)

    await wrapper.find('[data-testid="self-readiness-refresh"]').trigger('click'); await flushPromises()
    expect(getMyFactoryReadiness).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="self-readiness-no-blueprint"]').exists()).toBe(false)
    expect(wrapper.findComponent(FactoryReadinessStub).props('blueprintId')).toBe('bp-2')
  })

  it('cannot start a second read while one is in flight', async () => {
    let release!: (value: FactorySelfReadiness) => void
    vi.mocked(getMyFactoryReadiness).mockImplementationOnce(() => new Promise(resolveRead => { release = resolveRead }))
    const wrapper = render(); await flushPromises()

    const refresh = wrapper.find('[data-testid="self-readiness-refresh"]')
    expect(refresh.classes()).toContain('is-loading')
    await refresh.trigger('click'); await flushPromises()
    expect(getMyFactoryReadiness).toHaveBeenCalledTimes(1)

    release(ready('bp-1', 4)); await flushPromises()
    expect(wrapper.findComponent(FactoryReadinessStub).props('blueprintId')).toBe('bp-1')
  })

  it('drops an answer for a factory the account no longer belongs to', async () => {
    let releaseOld!: (value: FactorySelfReadiness) => void
    vi.mocked(getMyFactoryReadiness)
      .mockImplementationOnce(() => new Promise(resolveOld => { releaseOld = resolveOld }))
      .mockResolvedValueOnce(noBlueprint)
    const wrapper = render(); await flushPromises()

    signIn('F007'); await flushPromises()
    releaseOld(ready('bp-of-F006', 1)); await flushPromises()

    expect(getMyFactoryReadiness).toHaveBeenLastCalledWith('F007')
    expect(wrapper.findComponent(FactoryReadinessStub).exists()).toBe(false)
    expect(wrapper.find('[data-testid="self-readiness-no-blueprint"]').exists()).toBe(true)
  })
})
