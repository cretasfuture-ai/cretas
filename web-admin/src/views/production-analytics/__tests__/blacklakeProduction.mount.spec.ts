import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus, { ElSelect } from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ExternalCostData, ExternalCostMetrics, ExternalProductionData, ExternalProductionRow } from '@/api/smartbi/factoryGold';
import BlacklakeCostAnalysis from '../BlacklakeCostAnalysis.vue';
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

const costMetrics: ExternalCostMetrics = {
  totalCost: 1234.56, materialCost: 1011.23, laborCost: 223.33, unitCosts: [{ unit: '盒', cost: 12.3456 }],
  inputByUnit: [{ unit: 'kg', quantity: 8.2 }], issuedMaterialCost: 37.72,
  yieldRate: null, yieldComparableOrders: 0, workOrderCount: 1, priceMissingCount: 1, valuationIssueCount: 1,
  overheadMissingCount: 1,
};
const costSnapshot: ExternalCostData = {
  sourceSystem: 'blacklake', sourceLabel: '黑湖', costCollectedAt: snapshot.snapshotCollectedAt,
  issueCollectedAt: snapshot.snapshotCollectedAt, stale: false, permissionMessage: null, summary: costMetrics,
  daily: [{ costDate: '2026-11-18', ...costMetrics }],
  products: [{ productCode: CODE, productName: '产品A', ...costMetrics }],
  workOrders: [{
    workOrderCode: 'WO-BLACKLAKE-1', costDate: '2026-11-18', productCode: CODE, productName: '产品A',
    outputUnit: '盒', outputQty: 100, materialCost: 1011.23, laborCost: 223.33, totalCost: 1234.56,
    unitCost: 12.3456, inputByUnit: costMetrics.inputByUnit, yieldRate: null, yieldUnavailableReason: 'incompatible_units',
  }],
  materialIssues: [{
    lineId: '123', issueDate: '2026-11-18', workOrderCode: 'WO-BLACKLAKE-1', materialCode: 'RM-1',
    materialName: '领料原料 A', unit: 'kg', quantity: 7.2, effectiveUnitPrice: 5.239, issueCost: 37.72,
    priceSource: 'moving_avg',
  }, {
    lineId: '124', issueDate: '2026-11-18', workOrderCode: 'WO-BLACKLAKE-1', materialCode: 'RM-2',
    materialName: '领料原料 B', unit: 'kg', quantity: 1, effectiveUnitPrice: null, issueCost: null,
    priceSource: 'price_missing',
  }],
};

function mountOptions() {
  return { global: { plugins: [ElementPlus], stubs: { ChartInsight: true, ChartInsightProvider: true } } };
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  session.factoryId = 'LIUSHANMEN';
  nativeDashboard.mockResolvedValue({ success: true, data: { kpis: [] } });
  pythonFetch.mockImplementation((path: string) => Promise.resolve({
    success: true,
    data: session.factoryId !== 'LIUSHANMEN' ? null
      : path.includes('external-production') ? snapshot
        : path.includes('external-cost') ? costSnapshot : null,
  }));
});

afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
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

  it('成本页从外部成本端点渲染 KPI、趋势、产品、工单与领料', async () => {
    const page = mount(FactoryCostAnalysis, mountOptions());
    await flushPromises();
    expect(pythonFetch).toHaveBeenCalledTimes(1);
    expect(pythonFetch).toHaveBeenCalledWith(expect.stringMatching(
      /^\/api\/smartbi\/factory\/external-cost\?factory_id=LIUSHANMEN&startDate=.+&endDate=.+$/,
    ));
    const external = page.getComponent(BlacklakeCostAnalysis);
    expect(external.get('time').attributes('datetime')).toBe(snapshot.snapshotCollectedAt);
    for (const text of ['黑湖', '1,234.56', '1,011.23', '223.33', '12.3456',
      '每日成本趋势', '产品成本对比', '工单成本明细', 'WO-BLACKLAKE-1', '领料原料 A',
      '单位不可比', '移动均价', '不含制费（黑湖未提供）', '缺价 1 行', '计价待核 1 行']) {
      expect(external.text()).toContain(text);
    }
    expect(setOption).toHaveBeenLastCalledWith(expect.objectContaining({
      series: [
        expect.objectContaining({ data: [1234.56] }), expect.objectContaining({ data: [1011.23] }),
        expect.objectContaining({ data: [223.33] }),
      ],
    }));
    page.unmount();
  });

  it('成本页对 F006 保持原生成本端点与批次金额', async () => {
    session.factoryId = 'F006';
    pythonFetch.mockImplementation(async (path: string) => ({
      success: true,
      data: path.includes('cost-structure') ? {
        rows: [{ statDate: '2026-11-18', totalCost: 456, totalMaterialCost: 400, totalLaborCost: 56 }],
        summary: { totalCost: 456, totalMaterialCost: 400, totalLaborCost: 56, batchCount: 2 },
      } : null,
    }));
    const page = mount(FactoryCostAnalysis, mountOptions());
    await flushPromises();
    const paths = pythonFetch.mock.calls.map(([path]) => path.split('?')[0]);
    expect(paths).toEqual([
      '/api/smartbi/factory/external-cost',
      '/api/smartbi/factory/cost-structure', '/api/smartbi/factory/yield-trend', '/api/smartbi/factory/product-cost-compare',
      '/api/smartbi/factory/cost-structure', '/api/smartbi/factory/yield-trend', '/api/smartbi/factory/product-cost-compare',
    ]);
    expect(page.findComponent(BlacklakeCostAnalysis).exists()).toBe(false);
    expect(page.text()).toContain('生产批次');
    expect(page.text()).toContain('¥456');
    page.unmount();
  });

  it('成本页对黑湖空快照给出说明并且没有零金额', async () => {
    pythonFetch.mockResolvedValue({
      success: true,
      data: { ...costSnapshot, summary: null, daily: [], products: [], workOrders: [], materialIssues: [] },
    });
    const page = mount(FactoryCostAnalysis, mountOptions());
    await flushPromises();
    expect(page.getComponent(BlacklakeCostAnalysis).text()).toContain('所选期间暂无黑湖工单成本和生产领料');
    expect(page.text()).not.toContain('0.00');
    expect(pythonFetch).toHaveBeenCalledTimes(1);
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
