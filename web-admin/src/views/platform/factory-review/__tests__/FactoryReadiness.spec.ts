import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import FactoryReadiness from '../components/FactoryReadiness.vue'
import { getFactoryReadiness, type FactoryReadinessReport } from '@/api/factoryReadiness'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/store/modules/auth'

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
  stages: [
    { key: 'CONFIRMATION', label: '资料确认', state: 'DONE', evidence: '蓝图确认记录' },
    { key: 'BLUEPRINT_PUBLISHED', label: '蓝图发布', state: 'PENDING', evidence: '蓝图状态 VALIDATED' },
    { key: 'CONFIGURATION_APPLIED', label: '配置应用到本厂', state: 'PENDING', evidence: '已应用的是第1版，不是当前候选' },
    { key: 'FORMAL_RUNTIME', label: '工艺与BOM正式生效', state: 'DONE', evidence: '1/1 个在用成品已生效并匹配' },
    { key: 'BUSINESS_READY', label: '采购/生产/发货就绪', state: 'PENDING', evidence: '采购：MISSING' },
    { key: 'FIRST_BUSINESS', label: '首笔业务', state: 'PENDING', evidence: '采购：NONE' },
  ],
  businessReadiness: [
    { domain: 'PURCHASE', label: '采购', applicability: 'APPLICABLE', applicabilityBasis: '蓝图已选择 materials', state: 'MISSING',
      checks: [
        { code: 'ACTIVE_SUPPLIER', label: '至少一个启用的供应商', state: 'MISSING', detail: '当前 0 个', remedyKey: 'SUPPLIERS', remedyLabel: '供应商管理' },
        { code: 'PURCHASE_INBOUND_WAREHOUSE', label: '采购入库仓可用', state: 'MET', detail: '使用「主仓」', remedyKey: 'WAREHOUSE_DEFAULTS', remedyLabel: '仓库默认用途' },
      ],
      notChecked: ['按基本单位采购无需包装换算，此处不要求'], firstRecord: { state: 'NONE', count: 0, completedCount: 0, detail: '尚无采购单' } },
    { domain: 'PRODUCTION', label: '生产', applicability: 'NOT_APPLICABLE', applicabilityBasis: '本次蓝图未选择 bom/processes', state: 'NOT_APPLICABLE',
      checks: [{ code: 'WORKSHOP_WAREHOUSE', label: '生产成品/报工仓可用', state: 'MISSING', detail: '未配置', remedyKey: 'WAREHOUSE_DEFAULTS', remedyLabel: '仓库默认用途' }],
      notChecked: ['不要求研发仓'], firstRecord: { state: 'NONE', count: 0, completedCount: 0, detail: '尚无生产计划' } },
    { domain: 'SHIPPING', label: '发货', applicability: 'APPLICABLE', applicabilityBasis: '蓝图已选择 products', state: 'READY',
      checks: [{ code: 'ACTIVE_CUSTOMER', label: '至少一个启用的客户', state: 'MET', detail: '当前 2 个', remedyKey: 'CUSTOMERS', remedyLabel: '客户管理' }],
      notChecked: [], firstRecord: { state: 'COMPLETED', count: 3, completedCount: 1, detail: '1 张发货单客户已签收（共 3 张）' } },
  ],
})
const wrappers: VueWrapper[] = []
function render() {
  const wrapper = mount(FactoryReadiness, { props: { factoryId: 'FA', blueprintId: 'candidate', rowVersion: 5 },
    global: { plugins: [ElementPlus], stubs: { IndicatorApplicability: true, RouterLink: { props: ['to'], template: '<a class="router-link" :data-to="to.path"><slot /></a>' } } } })
  wrappers.push(wrapper); return wrapper
}
// mockReset also drops queued mockResolvedValueOnce values, so a failed test cannot leak responses into the next.
beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); vi.clearAllMocks(); vi.mocked(getFactoryReadiness).mockReset(); vi.mocked(getFactoryReadiness).mockResolvedValue(fixture()) })
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
  it('keeps each stage on its own fact: a confirmed candidate is not shown as published or ready', async () => {
    const wrapper = render(); await flushPromises()
    const stages = wrapper.find('[data-testid="readiness-stages"]').text()
    expect(stages).toMatch(/资料确认\s*已完成/)
    expect(stages).toMatch(/蓝图发布\s*未完成/)
    expect(stages).toMatch(/采购\/生产\/发货就绪\s*未完成/)
  })
  it('names the missing purchase fact and, for a platform reviewer, who fills it where — without a dead link', async () => {
    const wrapper = render(); await flushPromises()
    const purchase = wrapper.find('[data-testid="domain-PURCHASE"]').text()
    expect(purchase).toContain('还差资料')
    expect(purchase).toContain('至少一个启用的供应商：缺少（当前 0 个）')
    expect(purchase).toContain('由工厂管理员在「供应商管理」补齐')
    expect(purchase).not.toContain('采购入库仓可用')
    expect(wrapper.find('.router-link').exists()).toBe(false)
    expect(wrapper.text()).toContain('补齐页面只显示登录账号所属工厂的数据')
  })
  it('links the factory\'s own admin straight to the existing remedy page', async () => {
    useAuthStore().setUser({ id: 9, username: 'fa_admin', email: 'fa@example.test', isActive: true,
      createdAt: '2026-01-01T00:00:00', updatedAt: '2026-01-01T00:00:00', userType: 'factory',
      factoryUser: { role: 'factory_super_admin', factoryId: 'FA', permissions: [] } })
    const wrapper = render(); await flushPromises()
    const link = wrapper.find('[data-testid="domain-PURCHASE"] .router-link')
    expect(link.attributes('data-to')).toBe('/procurement/suppliers')
    expect(link.text()).toContain('去「供应商管理」补齐')
    expect(wrapper.text()).not.toContain('补齐页面只显示登录账号所属工厂的数据')
  })
  it('does not demand anything for an unselected business and keeps first records separate from readiness', async () => {
    const wrapper = render(); await flushPromises()
    const production = wrapper.find('[data-testid="domain-PRODUCTION"]').text()
    expect(production).toContain('本次不适用')
    expect(production).not.toContain('生产成品/报工仓可用')
    expect(production).not.toContain('首笔业务')
    const shipping = wrapper.find('[data-testid="domain-SHIPPING"]').text()
    expect(shipping).toContain('可以开展')
    expect(shipping).toContain('首笔业务：已完成，1 张发货单客户已签收（共 3 张）')
  })
  it('shows documents that exist but never completed as not completed, never as done', async () => {
    const draftsOnly = fixture()
    draftsOnly.businessReadiness![2] = { ...draftsOnly.businessReadiness![2], firstRecord: { state: 'RECORDED', count: 2,
      completedCount: 0, detail: '已有 2 张发货单，没有一张客户已签收，首笔业务未完成' } }
    vi.mocked(getFactoryReadiness).mockResolvedValue(draftsOnly)
    const wrapper = render(); await flushPromises()
    const shipping = wrapper.find('[data-testid="domain-SHIPPING"]').text()
    expect(shipping).toContain('首笔业务：有单据，尚未完成，已有 2 张发货单，没有一张客户已签收，首笔业务未完成')
    expect(shipping).not.toContain('已完成')
  })

  it('gives the exact factory-config steps for a missing default warehouse instead of a link, then re-reads', async () => {
    useAuthStore().setUser({ id: 9, username: 'fa_admin', email: 'fa@example.test', isActive: true,
      createdAt: '2026-01-01T00:00:00', updatedAt: '2026-01-01T00:00:00', userType: 'factory',
      factoryUser: { role: 'factory_super_admin', factoryId: 'FA', permissions: [] } })
    const missing = fixture()
    missing.businessReadiness![0] = { ...missing.businessReadiness![0], checks: [
      { code: 'PURCHASE_INBOUND_WAREHOUSE', label: '采购入库仓可用', state: 'MISSING', detail: '未配置默认仓，也没有可回退的系统仓',
        remedyKey: 'WAREHOUSE_DEFAULTS', remedyLabel: '仓库默认用途' }] }
    const fixed = fixture()
    fixed.businessReadiness![0] = { ...fixed.businessReadiness![0], state: 'READY', checks: [
      { code: 'PURCHASE_INBOUND_WAREHOUSE', label: '采购入库仓可用', state: 'MET', detail: '使用「原料仓」',
        remedyKey: 'WAREHOUSE_DEFAULTS', remedyLabel: '仓库默认用途' }] }
    vi.mocked(getFactoryReadiness).mockResolvedValueOnce(missing).mockResolvedValueOnce(fixed)
    const wrapper = render(); await flushPromises()

    const steps = wrapper.find('[data-testid="warehouse-steps"]').text()
    expect(steps).toContain('「Canvas 配置编辑器」→「工厂配置」→「仓库管理」')
    expect(steps).toContain('「采购入库默认仓」')
    expect(steps).toContain('工厂 FA')
    // Even the factory's own admin gets steps, not a link that would land on the canvas home.
    expect(wrapper.find('[data-testid="domain-PURCHASE"] .router-link').exists()).toBe(false)

    await wrapper.findAll('button').find(button => button.text() === '刷新核对')!.trigger('click'); await flushPromises()
    expect(getFactoryReadiness).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="warehouse-steps"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="domain-PURCHASE"]').text()).toContain('可以开展')
  })

  it('shows unknown rather than ready when the backend omits stage and business facts', async () => {
    const response = fixture(); delete response.stages; delete response.businessReadiness
    vi.mocked(getFactoryReadiness).mockResolvedValue(response)
    const wrapper = render(); await flushPromises()
    expect(wrapper.find('[data-testid="stages-unavailable"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="business-unavailable"]').text()).toContain('不能据此判断可以开工')
    expect(wrapper.text()).not.toContain('可以开展')
  })
  it('re-reads the server after a fix instead of turning the row green locally', async () => {
    const fixed = fixture()
    fixed.businessReadiness![0] = { ...fixed.businessReadiness![0], state: 'READY',
      checks: fixed.businessReadiness![0].checks.map(check => ({ ...check, state: 'MET', detail: '当前 1 个' })) }
    vi.mocked(getFactoryReadiness).mockResolvedValueOnce(fixture()).mockResolvedValueOnce(fixed)
    const wrapper = render(); await flushPromises()
    expect(wrapper.find('[data-testid="domain-PURCHASE"]').text()).toContain('还差资料')
    await wrapper.findAll('button').find(button => button.text() === '刷新核对')!.trigger('click'); await flushPromises()
    expect(getFactoryReadiness).toHaveBeenCalledTimes(2)
    const purchase = wrapper.find('[data-testid="domain-PURCHASE"]').text()
    expect(purchase).toContain('可以开展')
    expect(purchase).not.toContain('补齐')
  })
})

describe('建厂交付核对 — injected loader', () => {
  it('reads through an injected loader and never calls the platform route', async () => {
    const loader = vi.fn().mockResolvedValue(fixture())
    const wrapper = mount(FactoryReadiness, { props: { factoryId: 'FA', blueprintId: 'candidate', rowVersion: 5, loader },
      global: { plugins: [ElementPlus], stubs: { IndicatorApplicability: true, RouterLink: true } } })
    wrappers.push(wrapper)
    await flushPromises()
    expect(loader).toHaveBeenCalledWith('FA', 'candidate')
    expect(getFactoryReadiness).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前候选：第2版')
    await wrapper.find('.readiness-header button').trigger('click')
    await flushPromises()
    expect(loader).toHaveBeenCalledTimes(2)
    expect(getFactoryReadiness).not.toHaveBeenCalled()
  })

  it('still refuses a report for another blueprint version from an injected loader', async () => {
    const loader = vi.fn().mockResolvedValue({ ...fixture(), rowVersion: 6 })
    const wrapper = mount(FactoryReadiness, { props: { factoryId: 'FA', blueprintId: 'candidate', rowVersion: 5, loader },
      global: { plugins: [ElementPlus], stubs: { IndicatorApplicability: true, RouterLink: true } } })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('工厂或配置版本已变化')
    expect(wrapper.text()).not.toContain('当前候选：第2版')
  })
})
