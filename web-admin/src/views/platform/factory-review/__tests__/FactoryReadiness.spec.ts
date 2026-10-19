import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import FactoryReadiness from '../components/FactoryReadiness.vue'
import { getFactoryReadiness, type FactoryReadinessReport } from '@/api/factoryReadiness'

vi.mock('@/api/factoryReadiness', () => ({ getFactoryReadiness: vi.fn() }))
const fixture = (): FactoryReadinessReport => ({
  schemaVersion: 'factory-readiness/v1', factoryId: 'FA', blueprintId: 'candidate', blueprintVersion: 2,
  rowVersion: 5, lifecycleState: 'DRAFT', capabilities: [
    { id: 'products', label: '成品资料', configurationState: 'APPLIED', acceptanceState: 'ACCEPTED', stage: 'CONFIGURATION_ACCEPTED', nextStep: '继续业务核对', receiptCount: 1 },
    { id: 'workflow', label: '工艺发布', configurationState: 'NOT_APPLIED', acceptanceState: 'NOT_READY', stage: 'WAITING_CONFIGURATION', nextStep: '另人复核发布', receiptCount: 0 },
  ],
  currentRuntime: [{ productId: 'p1', productName: '在用盒装', state: 'PUBLISHED', workflowId: 7, workflowVersion: 1, bomId: 'bom-old', bomVersion: 1, reason: '现有工艺及BOM匹配' }],
  appliedVersion: { blueprintId: 'old', version: 1, candidateDiffers: true, boundary: '预览不会替换既有工艺与历史单据' },
  plannedIndicators: { factoryId: 'FA', basis: 'PROPOSED_CONFIGURATION', overallStatus: 'PARTIAL', products: [], metrics: [] },
  currentIndicators: { factoryId: 'FA', basis: 'CONFIGURATION_ONLY', overallStatus: 'PARTIAL', products: [], metrics: [] },
  businessAcceptanceState: 'UNVERIFIED', businessWriteCount: 0, readAt: '2026-10-18T12:00:00',
})
const wrappers: VueWrapper[] = []
function render() {
  const wrapper = mount(FactoryReadiness, { props: { factoryId: 'FA', blueprintId: 'candidate', rowVersion: 5 },
    global: { plugins: [ElementPlus], stubs: { IndicatorApplicability: true } } })
  wrappers.push(wrapper); return wrapper
}
beforeEach(() => { vi.clearAllMocks(); vi.mocked(getFactoryReadiness).mockResolvedValue(fixture()) })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

describe('建厂交付核对', () => {
  it('keeps accepted configuration, current formal runtime and business signoff distinct', async () => {
    const wrapper = render(); await flushPromises()
    expect(getFactoryReadiness).toHaveBeenCalledWith('FA', 'candidate')
    expect(wrapper.text()).toContain('当前候选：第2版')
    expect(wrapper.text()).toContain('已应用：第1版')
    expect(wrapper.text()).toContain('配置已验收')
    expect(wrapper.text()).toContain('日常业务签署待确认')
    expect(wrapper.text()).toContain('在用盒装')
    expect(wrapper.text()).not.toContain('全部已通过')
  })
  it.each(['foreign-factory', 'stale-version', 'business-write'])('rejects %s rather than displaying a false acceptance', async (mode) => {
    const response = fixture()
    if (mode === 'foreign-factory') response.factoryId = 'FB'
    if (mode === 'stale-version') response.rowVersion = 4
    if (mode === 'business-write') Object.assign(response, { businessWriteCount: 1 })
    vi.mocked(getFactoryReadiness).mockResolvedValue(response)
    const wrapper = render(); await flushPromises()
    expect(wrapper.text()).toContain('工厂或配置版本已变化')
    expect(wrapper.text()).not.toContain('在用盒装')
  })
  it('does not resurrect another factory report from an older request', async () => {
    let finish!: (value: FactoryReadinessReport) => void
    vi.mocked(getFactoryReadiness).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = render()
    vi.mocked(getFactoryReadiness).mockRejectedValueOnce(new Error('目标工厂无权限'))
    await wrapper.setProps({ factoryId: 'FB', blueprintId: 'other' }); await flushPromises()
    finish(fixture()); await flushPromises()
    expect(wrapper.text()).toContain('目标工厂无权限')
    expect(wrapper.text()).not.toContain('在用盒装')
  })
  it('shows API errors and recovers with a real refresh', async () => {
    vi.mocked(getFactoryReadiness).mockRejectedValueOnce(new Error('数据库不可用'))
    const wrapper = render(); await flushPromises()
    expect(wrapper.text()).toContain('数据库不可用')
    expect(wrapper.text()).not.toContain('已应用：')
    await wrapper.findAll('button').find(button => button.text() === '重试')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('在用盒装')
    expect(wrapper.text()).not.toContain('数据库不可用')
  })
})
