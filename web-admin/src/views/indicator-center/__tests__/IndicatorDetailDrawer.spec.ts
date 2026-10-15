import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { ref } from 'vue';
import Drawer from '../IndicatorDetailDrawer.vue';
import { getIndicatorDetail, getIndicatorValue, getIndicatorThresholds, listIndicatorVersions } from '@/api/indicator';

vi.mock('@/api/indicator', () => ({
  getIndicatorDetail: vi.fn(), getIndicatorValue: vi.fn(), getIndicatorThresholds: vi.fn(),
  listIndicatorVersions: vi.fn(), recomputeIndicator: vi.fn(),
}));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => false }) }));
vi.mock('@/utils/echarts', () => ({ default: { init: vi.fn() } }));
vi.mock('@/composables/useChartInsight', () => ({ useChartInsight: () => ({ insight: ref(null), loading: ref(false) }) }));

async function open() {
  const wrapper = shallowMount(Drawer, {
    props: { modelValue: false, factoryId: 'F-TEST', code: 'KPI' },
    global: { renderStubDefaultSlot: true },
  });
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(getIndicatorDetail).mockResolvedValue({ code: 'KPI', name: '测试指标', category: 'FACTORY',
    computeStrategy: 'PRECOMPUTED', isActive: true, lastValue: 123, factoryId: 'F-TEST' });
  vi.mocked(getIndicatorThresholds).mockResolvedValue([]);
  vi.mocked(listIndicatorVersions).mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 });
});

describe('current indicator evidence', () => {
  it('does not replace explicit no-data with a stale lastValue', async () => {
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'KPI', name: '测试指标', value: null,
      alertLevel: null, fromCache: false, status: 'NO_DATA', source: 'REAL_BUSINESS:quality' });
    const wrapper = await open();
    expect(wrapper.findComponent({ name: 'ThresholdGauge' }).props('currentValue')).toBeNull();
    expect(wrapper.text()).toContain('暂无业务数据');
    expect(wrapper.text()).toContain('业务数据');
    wrapper.unmount();
  });

  it('preserves a real zero and its computation time', async () => {
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'KPI', name: '测试指标', value: 0,
      alertLevel: null, fromCache: false, status: 'AVAILABLE', source: 'REAL_BUSINESS:quality',
      computedAt: '2026-10-15T09:00:00' });
    const wrapper = await open();
    expect(wrapper.findComponent({ name: 'ThresholdGauge' }).props('currentValue')).toBe(0);
    expect(wrapper.text()).toContain('业务数据');
    expect(wrapper.text()).toContain('2026');
    wrapper.unmount();
  });

  it('clears a previous value on a failed refresh and shows the error', async () => {
    vi.mocked(getIndicatorValue).mockRejectedValue(new Error('数据源连接失败'));
    const wrapper = await open();
    expect(wrapper.findComponent({ name: 'ThresholdGauge' }).props('currentValue')).toBeNull();
    expect(wrapper.text()).toContain('数据源连接失败');
    wrapper.unmount();
  });

  it('discloses the current-cost basis of the Python dish margin', async () => {
    vi.mocked(getIndicatorDetail).mockResolvedValue({ code: 'RESTAURANT_DISH_MARGIN', name: '菜品毛利率',
      category: 'RESTAURANT', computeStrategy: 'CACHED', isActive: true, factoryId: 'F-TEST' });
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'RESTAURANT_DISH_MARGIN', name: '菜品毛利率',
      value: null, alertLevel: null, fromCache: false, status: 'NO_DATA', source: 'PYTHON_ENDPOINT:RESTAURANT_DISH_MARGIN' });
    const wrapper = await open();
    expect(wrapper.text()).toContain('不代表历史成本还原');
    expect(wrapper.text()).toContain('成本未完整覆盖时不出值');
    expect(wrapper.findComponent({ name: 'ThresholdGauge' }).props('currentValue')).toBeNull();
    wrapper.unmount();
  });
});
