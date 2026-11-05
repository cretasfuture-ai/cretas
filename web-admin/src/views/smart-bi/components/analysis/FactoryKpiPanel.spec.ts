import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FactoryKpiPanel from './FactoryKpiPanel.vue'

const getMock = vi.hoisted(() => vi.fn())
const authMock = vi.hoisted(() => vi.fn(() => ({ factoryId: 'F-FACTORY-1' })))
vi.mock('@/api/request', () => ({ get: getMock }))
vi.mock('@/store/modules/auth', () => ({ useAuthStore: authMock }))

const fixture = {
  success: true,
  data: {
    oee: 91.2, overallQualityRate: 96.4, avgCycleTime: 12.5, throughput: 880,
    fpy: 94, reworkRate: 1.2, scrapRate: 0.8, unitCost: 3.25,
    materialCostRatio: 62, laborCostRatio: 18, overheadCostRatio: 20,
    onTimeDeliveryRate: 97, avgLeadTime: 2.5, inFullDeliveryRate: 93,
  },
}

function mountPanel() {
  setActivePinia(createPinia())
  return mount(FactoryKpiPanel, {
    global: {
      stubs: {
        ElButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
        ElCard: { template: '<div><slot name="header"/><slot /></div>' },
        ElProgress: { template: '<div class="progress">{{ percentage }}</div>', props: ['percentage'] },
        ElAlert: { template: '<div class="alert">{{ title }}</div>', props: ['title'] },
        ElEmpty: { template: '<div class="empty">{{ description }}</div>', props: ['description'] },
        ElDivider: { template: '<hr />' },
      },
    },
  })
}

describe('FactoryKpiPanel', () => {
  beforeEach(() => { getMock.mockReset(); authMock.mockClear() })

  it('loads the real factory endpoint and renders all nested KPI groups without flattening/null coercion', async () => {
    getMock.mockResolvedValue(fixture)
    const wrapper = mountPanel()
    await flushPromises()
    expect(getMock).toHaveBeenCalledWith('/F-FACTORY-1/reports/kpi-metrics')
    expect(wrapper.text()).toContain('设备综合效率 (OEE)')
    expect(wrapper.text()).toContain('91.2%')
    expect(wrapper.text()).toContain('一次合格率 (FPY)')
    expect(wrapper.text()).toContain('准时交付率')
    expect(wrapper.text()).toContain('成本结构')
    expect(wrapper.text()).toContain('¥3.25')
    expect(wrapper.text()).toContain('目标: 85%')
    expect(wrapper.text()).toContain('目标: 95%')
    expect(wrapper.text()).toContain('0.8%')
  })

  it('keeps nullable metrics as em dashes and exposes the empty state', async () => {
    getMock.mockResolvedValue({ success: true, data: { oee: null, fpy: null } })
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('暂无工厂 KPI 数据')
    expect(wrapper.text()).not.toContain('0.0%')
  })

  it('shows backend failures and clears stale metrics', async () => {
    getMock.mockRejectedValue(new Error('factory KPI unavailable'))
    const wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('factory KPI unavailable')
  })
})
