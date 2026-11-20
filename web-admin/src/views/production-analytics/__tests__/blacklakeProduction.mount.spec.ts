import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElSelect } from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ExternalProductionData, ExternalProductionRow } from '@/api/smartbi/factoryGold';
import BlacklakeProductionAnalysis from '../BlacklakeProductionAnalysis.vue';
import FactoryCostAnalysis from '../FactoryCostAnalysis.vue';
import ProductionAnalysis from '../ProductionAnalysis.vue';

const { pythonFetch, nativeDashboard, setOption, session } = vi.hoisted(() => ({
  pythonFetch: vi.fn(),
  nativeDashboard: vi.fn(),
  setOption: vi.fn(),
  session: { factoryId: 'LIUSHANMEN', switchTo: (_factory: string) => {} },
}));
vi.mock('@/api/smartbi/common', () => ({ pythonFetch, PYTHON_LLM_TIMEOUT_MS: 300000 }));
vi.mock('@/api/productionAnalytics', () => ({ getProductionDashboard: nativeDashboard }));
vi.mock('@/api/request', () => ({ default: { get: vi.fn().mockResolvedValue({ data: [] }) } }));
vi.mock('@/store/modules/auth', async () => {
  const { reactive } = await vi.importActual<typeof import('vue')>('vue');
  const store = reactive(session);
  session.switchTo = factory => { store.factoryId = factory; };
  return { useAuthStore: () => store };
});
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: true }) }));
vi.mock('@/utils/echarts', () => ({
  default: {
    init: (element: HTMLElement) => ({ getDom: () => element, setOption, resize: vi.fn(), dispose: vi.fn() }),
  },
}));

const CODE = 'C P2026100002';
const createRow = (
  unit: string, goodQty: number, defectQty: number, processName = '成品入库', productCode = CODE,
): ExternalProductionRow => ({
  reportDate: '2026-11-18', productCode, productName: productCode === CODE ? '产品A' : '产品B',
  unit, processName, goodQty, defectQty, defectRate: null, reportCount: 3,
});
const snapshot: ExternalProductionData = {
  sourceSystem: 'blacklake', sourceLabel: '黑湖', snapshotCollectedAt: '2026-11-18T15:09:00+00:00',
  stale: true, cost: null, yieldRate: null, unavailableMessage: '黑湖数据不含投入量，无法计算',
  rows: [
    createRow('KG', 100.125, 10), createRow('KG', 30, 0, '成品入库', 'P2'),
    createRow('KG', 230, 6, '出料'), createRow('只', 60, 0),
    createRow('包', 81, 1), createRow('盒', 48, 2),
  ],
};

function mountOptions() {
  return { global: { plugins: [ElementPlus], stubs: { ChartInsight: true, ChartInsightProvider: true } } };
}

beforeEach(() => {
  session.factoryId = 'LIUSHANMEN';
  nativeDashboard.mockResolvedValue({ success: true, data: { kpis: [] } });
  pythonFetch.mockImplementation((path: string) => Promise.resolve({
    success: true,
    data: path.includes('external-production') && session.factoryId === 'LIUSHANMEN' ? snapshot : null,
  }));
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('黑湖生产分析页面', () => {
  it('由生产分析页调用真实客户端路径，并按单位、工序、产品隔离趋势和入库排行', async () => {
    const page = mount(ProductionAnalysis, mountOptions());
    await flushPromises();
    expect(pythonFetch).toHaveBeenCalledWith(expect.stringMatching(
      /^\/api\/smartbi\/factory\/external-production\?factory_id=LIUSHANMEN&startDate=.+&endDate=.+$/,
    ));
    expect(nativeDashboard).not.toHaveBeenCalled();
    const analysis = page.getComponent(BlacklakeProductionAnalysis);
    expect(analysis.text()).toContain('数据未更新');
    expect(analysis.get('time').attributes('datetime')).toBe(snapshot.snapshotCollectedAt);
    expect(analysis.text()).toContain('23:09');
    expect(analysis.text()).toContain('黑湖数据不含投入量，无法计算');
    expect(analysis.text()).toContain(CODE);
    expect(analysis.text()).toContain('130.125');
    expect(analysis.text()).toContain('7.14%');
    expect(analysis.findAll('td').map(cell => cell.text())).not.toContain('230');
    expect(setOption).toHaveBeenLastCalledWith(expect.objectContaining({
      yAxis: expect.objectContaining({ name: 'KG' }),
      series: [expect.objectContaining({ data: [130.125] }), expect.objectContaining({ data: [10] })],
    }));

    const [unit, process, product] = analysis.findAllComponents(ElSelect);
    process.vm.$emit('update:modelValue', '出料');
    await flushPromises();
    expect(analysis.findAll('td').map(cell => cell.text())).toContain('230');
    expect(analysis.text()).toContain('成品入库良品排行（KG）');
    expect(analysis.text()).toContain('100.125');
    process.vm.$emit('update:modelValue', '成品入库');
    await flushPromises();
    product.vm.$emit('update:modelValue', 'P2');
    await flushPromises();
    expect(setOption).toHaveBeenLastCalledWith(expect.objectContaining({
      series: [expect.objectContaining({ data: [30] }), expect.objectContaining({ data: [0] })],
    }));
    unit.vm.$emit('update:modelValue', '盒');
    await flushPromises();
    expect(analysis.text()).toContain('4%');
    expect(setOption).toHaveBeenLastCalledWith(expect.objectContaining({
      yAxis: expect.objectContaining({ name: '盒' }),
      series: [expect.objectContaining({ data: [48] }), expect.objectContaining({ data: [2] })],
    }));
    page.unmount();
  });

  it('成本页显示同一来源的不可计算说明，跳过成本端点', async () => {
    const page = mount(FactoryCostAnalysis, mountOptions());
    await flushPromises();
    expect(pythonFetch).toHaveBeenCalledTimes(1);
    expect(pythonFetch.mock.calls[0][0]).toContain('summaryOnly=true');
    expect(page.text()).toContain('黑湖数据不含投入量，无法计算');
    expect(page.text()).not.toContain('总成本');
    page.unmount();
  });

  it('F006 接口没有外部数据时加载既有报工路径', async () => {
    session.factoryId = 'F006';
    const page = mount(ProductionAnalysis, mountOptions());
    await flushPromises();
    expect(nativeDashboard).toHaveBeenCalledTimes(1);
    expect(pythonFetch.mock.calls.map(([path]) => path.split('?')[0])).toEqual([
      '/api/smartbi/factory/external-production',
      '/api/smartbi/factory/cost-structure',
      '/api/smartbi/factory/yield-trend',
      '/api/smartbi/factory/product-cost-compare',
    ]);
    expect(page.findComponent(BlacklakeProductionAnalysis).exists()).toBe(false);
    expect(page.text()).toContain('出成率趋势');
    page.unmount();
  });

  it('切换工厂后，较晚完成的旧工厂报工请求无法覆盖当前页面', async () => {
    pythonFetch.mockResolvedValue({ success: true, data: null });
    const kpi = (label: string) => ({
      success: true,
      data: { kpis: [{ key: 'test', label, value: 10, unit: '批', change: 0, changeType: 'flat' }] },
    });
    let resolveOld!: (data: ReturnType<typeof kpi>) => void;
    nativeDashboard
      .mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
      .mockResolvedValueOnce(kpi('当前工厂报工'));
    const page = mount(ProductionAnalysis, mountOptions());
    await flushPromises();
    expect(nativeDashboard).toHaveBeenCalledTimes(1);
    session.switchTo('F006');
    await flushPromises();
    expect(page.text()).toContain('当前工厂报工');
    resolveOld(kpi('旧工厂报工'));
    await flushPromises();
    expect(page.text()).toContain('当前工厂报工');
    expect(page.text()).not.toContain('旧工厂报工');
    page.unmount();
  });
});
