import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import Drawer from '../IndicatorDetailDrawer.vue';
import { getIndicatorDetail, getIndicatorValue, getIndicatorThresholds, listIndicatorVersions, recomputeIndicator } from '@/api/indicator';

vi.mock('@/api/indicator', () => ({
  getIndicatorDetail: vi.fn(), getIndicatorValue: vi.fn(), getIndicatorThresholds: vi.fn(),
  listIndicatorVersions: vi.fn(), recomputeIndicator: vi.fn(),
}));
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));
vi.mock('@/utils/echarts', () => ({ default: { init: vi.fn() } }));
vi.mock('@/composables/useChartInsight', () => ({ useChartInsight: () => ({ insight: ref(null), loading: ref(false) }) }));

async function open(code = 'KPI', initialPeriod?: [string, string]) {
  const wrapper = shallowMount(Drawer, {
    props: { modelValue: false, factoryId: 'F-TEST', code, initialPeriod },
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        'el-table': { template: '<div><slot /></div>' },
        'el-table-column': { template: '<div />' },
      },
    },
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
  it('retains the explicit initial period while the detail and value requests complete', async () => {
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'FACTORY_LU_YIELD_RATE', name: '值', value: 88, alertLevel: null, fromCache: false });
    const wrapper = shallowMount(Drawer, { props: { modelValue: true, factoryId: 'F-TEST', code: 'FACTORY_LU_YIELD_RATE', initialPeriod: ['2026-10-15', '2026-10-16'] } });
    await flushPromises();
    expect(getIndicatorValue).toHaveBeenLastCalledWith('F-TEST', 'FACTORY_LU_YIELD_RATE', { periodStart: '2026-10-15', periodEnd: '2026-10-16' });
    expect((wrapper.vm as unknown as { detail: unknown }).detail).not.toBeNull();
    wrapper.unmount();
  });
  it('does not revive a value from before close and reopen of the same indicator', async () => {
    let resolveOld!: (value: Awaited<ReturnType<typeof getIndicatorValue>>) => void;
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'KPI', name: '值', value: 20, alertLevel: null, fromCache: false });
    vi.mocked(getIndicatorValue).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }));
    const wrapper = await open();
    await wrapper.setProps({ modelValue: false }); await wrapper.setProps({ modelValue: true }); await flushPromises();
    resolveOld({ code: 'KPI', name: '旧值', value: 999, alertLevel: null, fromCache: false }); await flushPromises();
    expect(wrapper.findComponent({ name: 'ThresholdGauge' }).props('currentValue')).toBe(20);
    wrapper.unmount();
  });
  it('does not publish a recomputation after its reviewer context changes', async () => {
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'KPI', name: '值', value: 20, alertLevel: null, fromCache: false });
    let resolveOld!: (value: Awaited<ReturnType<typeof recomputeIndicator>>) => void;
    vi.mocked(recomputeIndicator).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }));
    const wrapper = await open();
    const button = wrapper.findAll('el-button, el-button-stub').find(node => node.text().includes('强制重算'))!;
    await button.trigger('click'); await button.trigger('click');
    expect(recomputeIndicator).toHaveBeenCalledTimes(1);
    await wrapper.setProps({ actorKey: 'different-reviewer' }); await flushPromises();
    await wrapper.findAll('el-button, el-button-stub').find(node => node.text().includes('强制重算'))!.trigger('click');
    expect(recomputeIndicator).toHaveBeenCalledTimes(1);
    resolveOld({ code: 'KPI', name: '旧值', value: 999, alertLevel: null, fromCache: false, status: 'AVAILABLE' }); await flushPromises();
    expect(wrapper.emitted('recomputed')).toBeUndefined(); expect(ElMessage.success).not.toHaveBeenCalled();
    expect(wrapper.findComponent({ name: 'ThresholdGauge' }).props('currentValue')).toBe(20);
    wrapper.unmount();
  });
  it('keeps the in-flight POST lock across close and reopen, then releases it after settlement', async () => {
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'KPI', name: '值', value: 20, alertLevel: null, fromCache: false });
    let resolveOld!: (value: Awaited<ReturnType<typeof recomputeIndicator>>) => void;
    vi.mocked(recomputeIndicator).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }));
    const wrapper = await open();
    const clickRecompute = () => wrapper.findAll('el-button, el-button-stub').find(node => node.text().includes('强制重算'))!.trigger('click');
    await clickRecompute();
    await wrapper.setProps({ modelValue: false }); await wrapper.setProps({ modelValue: true }); await flushPromises();
    await clickRecompute(); expect(recomputeIndicator).toHaveBeenCalledTimes(1);
    resolveOld({ code: 'KPI', name: '值', value: 21, alertLevel: null, fromCache: false, status: 'AVAILABLE' }); await flushPromises();
    expect(wrapper.emitted('recomputed')).toBeUndefined();
    vi.mocked(recomputeIndicator).mockResolvedValueOnce({ code: 'KPI', name: '值', value: 22, alertLevel: null, fromCache: false, status: 'AVAILABLE' });
    await clickRecompute(); await flushPromises(); expect(recomputeIndicator).toHaveBeenCalledTimes(2);
    expect(wrapper.emitted('recomputed')).toEqual([['KPI']]); wrapper.unmount();
  });
  it('does not query value or records for an explicitly empty actual-flow period', async () => {
    const wrapper = shallowMount(Drawer, { props: { modelValue: true, factoryId: 'F-TEST', code: 'FACTORY_LU_YIELD_RATE', initialPeriod: null } });
    await flushPromises();
    expect(getIndicatorValue).not.toHaveBeenCalled(); expect(listIndicatorVersions).not.toHaveBeenCalled();
    wrapper.unmount();
  });
  it('shows a records error rather than claiming there are no historical snapshots', async () => {
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'KPI', name: '值', value: 20, alertLevel: null, fromCache: false });
    vi.mocked(listIndicatorVersions).mockRejectedValueOnce(new Error('历史接口失败'));
    const wrapper = await open();
    expect((wrapper.vm as unknown as { versionsError: string }).versionsError).toContain('历史接口失败');
    expect(wrapper.text()).not.toContain('暂无历史快照');
    wrapper.unmount();
  });
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

  it.each(['FAILED', 'NO_DATA'] as const)('does not announce a %s recomputation as success', async (status) => {
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'KPI', name: '测试指标', value: 123,
      alertLevel: null, fromCache: false, status: 'AVAILABLE' });
    vi.mocked(recomputeIndicator).mockResolvedValue({ code: 'KPI', name: '测试指标', value: null,
      alertLevel: null, fromCache: false, status });
    const wrapper = await open();
    const button = wrapper.findAll('el-button, el-button-stub').find(node => node.text().includes('强制重算'));
    expect(button).toBeDefined();
    await button!.trigger('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'ThresholdGauge' }).props('currentValue')).toBeNull();
    expect(ElMessage.success).not.toHaveBeenCalled();
    expect(wrapper.emitted('recomputed')).toBeUndefined();
    expect(status === 'FAILED' ? ElMessage.error : ElMessage.warning).toHaveBeenCalledOnce();
    wrapper.unmount();
  });

  it('announces a successful real-zero recomputation and refreshes its snapshot', async () => {
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'KPI', name: '测试指标', value: null,
      alertLevel: null, fromCache: false, status: 'NO_DATA' });
    vi.mocked(recomputeIndicator).mockResolvedValue({ code: 'KPI', name: '测试指标', value: 0,
      alertLevel: null, fromCache: false, status: 'AVAILABLE' });
    const wrapper = await open();
    const button = wrapper.findAll('el-button, el-button-stub').find(node => node.text().includes('强制重算'));
    await button!.trigger('click');
    await flushPromises();
    expect(wrapper.findComponent({ name: 'ThresholdGauge' }).props('currentValue')).toBe(0);
    expect(ElMessage.success).toHaveBeenCalledWith('重算完成: 0');
    expect(wrapper.emitted('recomputed')).toEqual([['KPI']]);
    expect(listIndicatorVersions).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });
});

describe('actual production metric basis and period', () => {
  it('requests exact business periods and consistent ten-row server pages', async () => {
    vi.mocked(getIndicatorDetail).mockResolvedValue({ code: 'FACTORY_ACTUAL_TOTAL_COST', name: '完整成本', category: 'FACTORY',
      computeStrategy: 'CACHED', isActive: true, factoryId: 'F-TEST' });
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'FACTORY_ACTUAL_TOTAL_COST', name: '完整成本', value: 501.32,
      alertLevel: null, fromCache: false, status: 'AVAILABLE' });
    const wrapper = await open('FACTORY_ACTUAL_TOTAL_COST');
    expect(listIndicatorVersions).toHaveBeenLastCalledWith('F-TEST', 'FACTORY_ACTUAL_TOTAL_COST', expect.objectContaining({ page: 1, size: 10, exactPeriod: true }));
    const vm = wrapper.vm as unknown as { onPageChange: (page: number) => void };
    vm.onPageChange(2); await flushPromises();
    expect(listIndicatorVersions).toHaveBeenLastCalledWith('F-TEST', 'FACTORY_ACTUAL_TOTAL_COST', expect.objectContaining({ page: 2, size: 10, exactPeriod: true }));
    wrapper.unmount();
  });

  it('rejects history from a different business window instead of showing a monthly value under today', async () => {
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'FACTORY_ACTUAL_TOTAL_COST', name: '完整成本', value: null,
      alertLevel: null, fromCache: false, status: 'NO_DATA' });
    vi.mocked(listIndicatorVersions).mockResolvedValue({ content: [
      { id: 'month', indicatorId: 'i', periodStart: '2020-01-01', periodEnd: '2099-12-31', value: 501.32,
        computedAt: '2026-10-17T09:00:00', alertLevel: null, computeSource: 'REAL_BUSINESS:FACTORY_ACTUAL_TOTAL_COST:actual-flow-v2' },
    ], totalElements: 1, totalPages: 1, number: 0, size: 10 });
    const wrapper = await open('FACTORY_ACTUAL_TOTAL_COST');
    expect((wrapper.vm as unknown as { versions: unknown[] }).versions).toHaveLength(0);
    expect((wrapper.vm as unknown as { versionsError: string }).versionsError).toContain('期间');
    wrapper.unmount();
  });

  it('shows the Chinese formula basis and requests today by default', async () => {
    const now = new Date();
    const today = [now.getFullYear(), now.getMonth() + 1, now.getDate()]
      .map((part) => String(part).padStart(2, '0'));
    const end = `${today[0]}-${today[1]}-${today[2]}`;
    const start = end;
    vi.mocked(getIndicatorDetail).mockResolvedValue({ code: 'FACTORY_LU_YIELD_RATE', name: '流转出成率', category: 'FACTORY',
      computeStrategy: 'REALTIME', isActive: true, factoryId: 'F-TEST' });
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'FACTORY_LU_YIELD_RATE', name: '流转出成率', value: 80,
      alertLevel: null, fromCache: false, status: 'AVAILABLE', source: 'REAL_BUSINESS:FACTORY_LU_YIELD_RATE:actual-flow-v2' });
    vi.mocked(listIndicatorVersions).mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 200 });

    const wrapper = await open('FACTORY_LU_YIELD_RATE');

    expect(wrapper.text()).toContain('成品合格产出量（kg）÷实际首工序投入量（kg）×100%');
    expect(wrapper.text()).toContain('真实业务 · 实际流');
    expect(wrapper.text()).toContain('已完工且已结单');
    expect(getIndicatorValue).toHaveBeenCalledWith('F-TEST', 'FACTORY_LU_YIELD_RATE', {
      periodStart: start,
      periodEnd: end,
    });
    expect(listIndicatorVersions).toHaveBeenCalledWith('F-TEST', 'FACTORY_LU_YIELD_RATE', {
      page: 1,
      size: 10,
      windowStart: start,
      windowEnd: end,
      exactPeriod: true,
    });
    wrapper.unmount();
  });

  it('keeps older formulas in the table but excludes them from chart and AI insight', async () => {
    vi.mocked(getIndicatorDetail).mockResolvedValue({ code: 'FACTORY_LU_UNIT_COST', name: '流转单位成本', category: 'FACTORY',
      computeStrategy: 'REALTIME', isActive: true, factoryId: 'F-TEST' });
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'FACTORY_LU_UNIT_COST', name: '流转单位成本', value: 12,
      alertLevel: null, fromCache: false, status: 'AVAILABLE', source: 'REAL_BUSINESS:FACTORY_LU_UNIT_COST:actual-flow-v2' });
    vi.mocked(listIndicatorVersions).mockResolvedValue({ content: [
      { id: 'old', indicatorId: 'i', periodStart: '2026-10-01', periodEnd: '2026-10-15', value: 99, computedAt: '2026-10-14T09:00:00', alertLevel: null,
        computeSource: 'REAL_BUSINESS:FACTORY_LU_UNIT_COST:legacy' },
    ], totalElements: 2, totalPages: 1, number: 0, size: 200 });

    const wrapper = await open('FACTORY_LU_UNIT_COST', ['2026-10-01', '2026-10-15']);

    expect(wrapper.text()).toContain('真实业务 · 实际流');
    expect((wrapper.vm as unknown as { versions: unknown[] }).versions).toHaveLength(1);
    expect((wrapper.vm as unknown as { chartVersions: unknown[] }).chartVersions).toHaveLength(0);
    expect((wrapper.vm as unknown as { formulaSourceText: (source?: string) => string })
      .formulaSourceText('REAL_BUSINESS:FACTORY_LU_UNIT_COST:legacy'))
      .toContain('旧口径，仅供查看');
    expect(wrapper.findComponent({ name: 'ChartInsight' }).exists()).toBe(false);
    wrapper.unmount();
  });
});
