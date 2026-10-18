import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils';
import { reactive } from 'vue';
import Dashboard from '../IndicatorCenterDashboard.vue';
import { getIndicatorValue, listIndicators } from '@/api/indicator';

vi.mock('@/api/indicator', () => ({
  getIndicatorValue: vi.fn(),
  listIndicators: vi.fn(),
}));
const identity = reactive({ factoryId: 'F-TEST', user: { id: 7 }, currentRole: 'factory_super_admin' });
const route = reactive({ query: {} as Record<string, unknown> });
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => identity }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canAccess: () => true }),
}));
vi.mock('vue-router', () => ({ useRoute: () => route }));
vi.mock('@/utils/echarts', () => ({ default: { init: vi.fn() } }));
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus');
  return { ...actual, ElMessage: { warning: vi.fn() } };
});

const p0Codes = [
  'FACTORY_LU_YIELD_RATE',
  'FACTORY_GOOD_OUTPUT_RATE',
  'FACTORY_LU_UNIT_COST',
  'FACTORY_ACTUAL_TOTAL_COST',
] as const;

function listItem(code: string, lastValue: number | null, lastComputedAt: string | null = null) {
  return {
    id: code,
    code,
    name: code,
    category: 'FACTORY' as const,
    unit: 'kg',
    isActive: true,
    computeStrategy: 'REALTIME',
    lastValue,
    lastComputedAt,
  };
}

function today(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] }); vi.setSystemTime(new Date('2026-10-16T12:00:00'));
  vi.resetAllMocks();
  identity.factoryId = 'F-TEST'; identity.user.id = 7; route.query = {};
  vi.mocked(listIndicators).mockResolvedValue([
    listItem('FACTORY_LU_YIELD_RATE', 13.93),
    listItem('FACTORY_GOOD_OUTPUT_RATE', 17),
    listItem('FACTORY_LU_UNIT_COST', 4.2, '2026-10-15T09:00:00'),
    listItem('FACTORY_ACTUAL_TOTAL_COST', 9),
    listItem('OTHER_INDICATOR', 7, '2026-10-15T08:00:00'),
  ]);
});

const mounted: VueWrapper[] = [];
afterEach(() => { mounted.splice(0).forEach(wrapper => wrapper.unmount()); vi.useRealTimers(); });
async function open() {
  const wrapper = shallowMount(Dashboard, { global: { stubs: {
    IndicatorTreeViewer: { template: '<div />', methods: { loadTree: vi.fn() } },
  } } });
  mounted.push(wrapper);
  await flushPromises();
  return wrapper;
}

describe('IndicatorCenterDashboard real business snapshots', () => {
  it.each([
    { preset: 'today', before: '2026-10-16T23:59:00', after: '2026-10-17T00:01:00', expected: ['2026-10-17', '2026-10-17'] },
    { preset: 'month', before: '2026-10-31T23:59:00', after: '2026-11-01T00:01:00', expected: ['2026-11-01', '2026-11-01'] },
  ])('refreshes the $preset period after the calendar day rolls over', async scenario => {
    vi.setSystemTime(new Date(scenario.before));
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'KPI', name: '值', value: 4, alertLevel: null, fromCache: false });
    const wrapper = await open();
    const state = wrapper.vm as unknown as { periodPreset: string; refreshAll: () => Promise<void> };
    state.periodPreset = scenario.preset; await flushPromises(); vi.mocked(getIndicatorValue).mockClear();
    vi.setSystemTime(new Date(scenario.after)); await state.refreshAll(); await flushPromises();
    expect(getIndicatorValue).toHaveBeenCalledTimes(4);
    expect(getIndicatorValue).toHaveBeenLastCalledWith('F-TEST', p0Codes[3], { periodStart: scenario.expected[0], periodEnd: scenario.expected[1] });
    expect(wrapper.findComponent({ name: 'FinishedBatchEvidence' }).props('period')).toEqual(scenario.expected);
  });
  it('uses a bookmarked period consistently for all actual metrics and the drawer', async () => {
    route.query = { periodStart: '2026-10-15', periodEnd: '2026-10-16' };
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'KPI', name: '值', value: 4, alertLevel: null, fromCache: false, status: 'AVAILABLE' });
    const wrapper = await open();
    expect(getIndicatorValue).toHaveBeenCalledTimes(4);
    for (const code of p0Codes) expect(getIndicatorValue).toHaveBeenCalledWith('F-TEST', code, { periodStart: '2026-10-15', periodEnd: '2026-10-16' });
    expect(wrapper.findComponent({ name: 'IndicatorDetailDrawer' }).props('initialPeriod')).toEqual(['2026-10-15', '2026-10-16']);
    expect(wrapper.findComponent({ name: 'ProductProductionResults' }).props()).toMatchObject({
      factoryId: 'F-TEST', period: ['2026-10-15', '2026-10-16'],
    });
  });
  it.each([null, ['2026-02-31', '2026-10-16']])('clears stale values and stops queries for an invalid custom period %s', async range => {
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'KPI', name: '值', value: 4, alertLevel: null, fromCache: false });
    const wrapper = await open(); vi.mocked(getIndicatorValue).mockClear();
    const state = wrapper.vm as unknown as { periodPreset: string; customPeriodRange: unknown; indicators: unknown[] };
    state.periodPreset = 'custom'; state.customPeriodRange = range;
    await flushPromises();
    expect(getIndicatorValue).not.toHaveBeenCalled(); expect(state.indicators).toHaveLength(0);
    expect(wrapper.text()).toContain('日期为空或无效');
  });
  it('does not silently fall back to today for a malformed bookmark', async () => {
    route.query = { periodStart: ['2026-10-16'], periodEnd: '2026-10-16' };
    const wrapper = await open();
    expect(getIndicatorValue).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('日期为空或无效');
  });
  it('invalidates a pending value when a different user has the same role', async () => {
    let resolveOld!: (value: Awaited<ReturnType<typeof getIndicatorValue>>) => void;
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'KPI', name: '新值', value: 2, alertLevel: null, fromCache: false });
    vi.mocked(getIndicatorValue).mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }));
    const wrapper = await open();
    identity.user.id = 8; await flushPromises();
    resolveOld({ code: 'OLD', name: '旧值', value: 999, alertLevel: null, fromCache: false }); await flushPromises();
    const state = wrapper.vm as unknown as { indicators: Array<{ lastValue?: number | null }> };
    expect(state.indicators[0]?.lastValue).toBe(2);
    expect(wrapper.findComponent({ name: 'IndicatorDetailDrawer' }).props('actorKey')).toContain('8');
  });
  it('keeps a list error visible instead of showing an apparently empty factory', async () => {
    vi.mocked(listIndicators).mockRejectedValueOnce(new Error('业务接口不可用'));
    const wrapper = await open(); expect(wrapper.text()).toContain('指标读取失败：业务接口不可用');
  });
  it('requests the four factory metrics for today and preserves available zero without stale fallback', async () => {
    const currentDay = today();
    vi.mocked(getIndicatorValue).mockImplementation(async (_factoryId, code) => {
      if (code === 'FACTORY_LU_YIELD_RATE') {
        return { code, name: code, value: 13.93, alertLevel: null, fromCache: false, status: 'AVAILABLE', computedAt: '2026-10-16T09:00:00' };
      }
      if (code === 'FACTORY_GOOD_OUTPUT_RATE') {
        return { code, name: code, value: 0, alertLevel: null, fromCache: false, status: 'AVAILABLE', computedAt: '2026-10-16T09:01:00' };
      }
      if (code === 'FACTORY_LU_UNIT_COST') {
        return { code, name: code, value: null, alertLevel: null, fromCache: false, status: 'NO_DATA' };
      }
      return { code, name: code, value: 99, alertLevel: null, fromCache: false, status: 'FAILED' };
    });

    const wrapper = await open();
    const state = wrapper.vm as unknown as {
      indicators: Array<{ code: string; lastValue?: number | null; lastComputedAt?: string | null; status?: string }>;
      stats: { available: number; missing: number; failed: number };
    };

    expect(getIndicatorValue).toHaveBeenCalledTimes(4);
    for (const code of p0Codes) {
      expect(getIndicatorValue).toHaveBeenCalledWith('F-TEST', code, {
        periodStart: currentDay,
        periodEnd: currentDay,
      });
    }
    expect(state.indicators.find(item => item.code === 'FACTORY_LU_YIELD_RATE')).toMatchObject({
      lastValue: 13.93,
      status: 'AVAILABLE',
    });
    expect(state.indicators.find(item => item.code === 'FACTORY_GOOD_OUTPUT_RATE')).toMatchObject({
      lastValue: 0,
      status: 'AVAILABLE',
    });
    expect(state.indicators.find(item => item.code === 'FACTORY_LU_UNIT_COST')).toMatchObject({
      lastValue: null,
      lastComputedAt: null,
      status: 'NO_DATA',
    });
    expect(state.indicators.find(item => item.code === 'FACTORY_ACTUAL_TOTAL_COST')).toMatchObject({
      lastValue: null,
      lastComputedAt: null,
      status: 'FAILED',
    });
    expect(state.indicators.find(item => item.code === 'OTHER_INDICATOR')).toMatchObject({
      lastValue: 7,
      lastComputedAt: '2026-10-15T08:00:00',
    });
    expect(state.stats).toMatchObject({ available: 3, missing: 1, failed: 1 });
    wrapper.unmount();
  });

  it('marks a failed value request as failed instead of retaining the list snapshot', async () => {
    vi.mocked(getIndicatorValue).mockRejectedValue(new Error('数据源连接失败'));
    const wrapper = await open();
    const state = wrapper.vm as unknown as {
      indicators: Array<{ code: string; lastValue?: number | null; status?: string }>;
      stats: { failed: number };
    };
    expect(state.indicators.find(item => item.code === 'FACTORY_LU_YIELD_RATE')).toMatchObject({
      lastValue: null,
      status: 'FAILED',
    });
    expect(state.stats.failed).toBe(4);
    wrapper.unmount();
  });
});
