import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils';
import ElementPlus, { ElSelect } from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ExternalCostData, ExternalCostMetrics, ExternalProductionData, ExternalProductionRow } from '@/api/smartbi/factoryGold';
import BlacklakeCostAnalysis from '../BlacklakeCostAnalysis.vue';
import BlacklakeBarChart from '../BlacklakeBarChart.vue';
import BlacklakeProductionAnalysis from '../BlacklakeProductionAnalysis.vue';
import FactoryCostAnalysis from '../FactoryCostAnalysis.vue';
import ProductionAnalysis from '../ProductionAnalysis.vue';

const { pythonFetch, nativeDashboard, setOption, session } = vi.hoisted(() => ({
  pythonFetch: vi.fn(),
  nativeDashboard: vi.fn(),
  setOption: vi.fn(),
  session: { factoryId: 'LIUSHANMEN', switchTo: (_factory: string) => {} },
}));
vi.mock('@/api/smartbi/common', () => ({
  pythonFetch, PYTHON_LLM_TIMEOUT_MS: 300000, getPythonAuthHeaders: async () => ({ Authorization: 'Bearer fixture' }),
}));
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
  totalCost: 1234.56, materialCost: 1011.23, laborCost: 223.33, costMissingOrders: 0,
  materialCostMissingOrders: 0, laborCostMissingOrders: 0,
  unitCosts: [{ unit: '盒', cost: 12.3456, workOrderCount: 1, zeroMaterialCostOrders: 0 }],
  unitCostMissingOrders: 0,
  inputByUnit: [{ unit: 'kg', quantity: 8.2 }], issuedMaterialCost: 37.72,
  yieldRate: null, yieldComparableOrders: 0, workOrderCount: 1, priceMissingCount: 1, valuationIssueCount: 1,
  overheadMissingCount: 1,
};
const costSnapshot: ExternalCostData = {
  sourceSystem: 'blacklake', sourceLabel: '黑湖', costCollectedAt: snapshot.snapshotCollectedAt,
  issueCollectedAt: snapshot.snapshotCollectedAt, stale: false, permissionMessage: null, summary: costMetrics,
  daily: [{ costDate: '2026-11-18', hasMaterialCredit: false, ...costMetrics }],
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

function mountOptions(realInsight = false) {
  return { global: { plugins: [ElementPlus], stubs: {
    RouterLink: RouterLinkStub, ChartInsight: !realInsight, ChartInsightProvider: !realInsight,
  } } };
}

const latestChart = (metric: string) => [...setOption.mock.calls].reverse().find(([option]) =>
  option.series?.some((series: { name: string }) => series.name === metric))?.[0];

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
  vi.unstubAllGlobals();
});

describe('黑湖生产分析页面', () => {
  it.each([true, false])('四张图逐张使用图表分析接口，接口可用=%s', async (available) => {
    const request = vi.fn(async (_url: string, init: RequestInit) => {
      const body = JSON.parse(String(init.body));
      return {
        ok: available, status: available ? 200 : 503,
        json: async () => ({ success: true, data: {
          finding: `接口发现 ${body.y_metric}/${body.x_dim}`, suggestion: '接口建议',
          source: 'llm', tier: 2,
        } }),
      };
    });
    vi.stubGlobal('fetch', request);
    const costData = {
      ...costSnapshot,
      daily: [
        ...costSnapshot.daily, { ...costMetrics, costDate: '2026-11-04',
          totalCost: -140601.74, materialCost: -152933.11, hasMaterialCredit: true },
        { ...costMetrics, costDate: '2026-11-05', totalCost: 90, materialCost: 50, hasMaterialCredit: false },
      ],
      products: [...costSnapshot.products, {
        ...costMetrics, productCode: 'CREDIT', productName: '冲销样例', totalCost: 20,
      }],
      workOrders: [
        ...costSnapshot.workOrders,
        { ...costSnapshot.workOrders[0], costDate: '2026-11-05',
          workOrderCode: 'NORMAL-ORDER', productCode: 'CREDIT', materialCost: 60, totalCost: 100 },
        { ...costSnapshot.workOrders[0], costDate: '2026-11-05',
          workOrderCode: 'CREDIT-ORDER', productCode: 'CREDIT', materialCost: -10, totalCost: -10 },
      ],
    };
    pythonFetch.mockImplementation((path: string) => Promise.resolve({
      success: true, data: path.includes('external-cost') ? costData : snapshot,
    }));
    const cost = mount(FactoryCostAnalysis, mountOptions(true));
    const production = mount(ProductionAnalysis, mountOptions(true));
    await flushPromises();
    await vi.advanceTimersByTimeAsync(300);
    await flushPromises();

    for (const [page, wanted] of [
      [cost, ['cost/time', 'cost/product']],
      [production, ['quantity/time', 'quantity/product']],
    ] as const) {
      const blocks = page.findAll('.blacklake-chart-analysis');
      expect(blocks).toHaveLength(2);
      blocks.forEach((block, i) => {
        if (available) {
          expect(block.text()).toContain(`接口发现 ${wanted[i]}`);
          expect(block.text()).toContain('接口建议');
          expect(block.find('[role="status"]').exists()).toBe(false);
        } else {
          expect(block.get('[role="status"]').text()).toBe(
            '当前无法连接图表分析服务。可先查看图表数据，或联系管理员检查服务。',
          );
          expect(block.text()).not.toContain('请稍后重试');
          expect(block.find('.chart-insight-finding').exists()).toBe(false);
        }
      });
      const link = page.findAllComponents(RouterLinkStub).find(item => item.text() === '自由问数出图');
      expect(link?.props('to')).toEqual({
        path: '/smart-bi/analysis', query: { tab: 'query', mode: 'factory', source: 'business' },
      });
    }
    const bodies = request.mock.calls.map(([url, init]) => {
      expect(url).toContain('/api/smartbi/chart-insight');
      expect(init.headers).toEqual({ Authorization: 'Bearer fixture' });
      return JSON.parse(String(init.body));
    });
    expect(bodies).toHaveLength(4);
    expect(bodies.every(body => body.factory_id === 'LIUSHANMEN')).toBe(true);
    expect(bodies.find(body => body.y_metric === 'cost' && body.x_dim === 'time')?.series_labels)
      .toEqual(['2026-11-18']);
    expect(bodies.find(body => body.y_metric === 'cost' && body.x_dim === 'product')?.series_values)
      .toEqual([1234.56]);
    expect(cost.text()).toContain('不含制费');
    expect(cost.text()).toContain('有产出的零单价工单按缺失处理，未计入平均单位成本');
    cost.unmount();
    production.unmount();
  });

  it('产品成本图的坐标轴仅为同名产品保留编码，图表类别和分析请求保留完整标签', async () => {
    const fetchInsight = vi.fn(async (_url: string, _init: RequestInit) => ({
      ok: true,
      json: async () => ({ success: true, code: 'RESULT_UNAVAILABLE', data: null }),
    }));
    vi.stubGlobal('fetch', fetchInsight);
    const products = [
      { ...costMetrics, productCode: 'C P2026100020', productName: '进口原切牛腩块500g', totalCost: 30 },
      { ...costMetrics, productCode: 'C P2026100021', productName: '样品', totalCost: 20 },
      { ...costMetrics, productCode: 'P22', productName: '样品', totalCost: 10 },
      { ...costMetrics, productCode: 'P23', productName: '', totalCost: 0 },
    ];
    const page = mount(BlacklakeCostAnalysis, {
      ...mountOptions(true),
      props: { data: { ...costSnapshot, products }, factoryId: 'LIUSHANMEN', canViewPrice: true },
    });
    await flushPromises();
    await vi.advanceTimersByTimeAsync(300);
    await flushPromises();
    const chart = latestChart('产品工单总成本');
    const labels: string[] = chart.yAxis.data;
    expect(chart.tooltip.trigger).toBe('axis');
    expect(labels).toEqual(['进口原切牛腩块500g · C P2026100020', '样品 · C P2026100021', '样品 · P22', 'P23']);
    expect(labels.map(label => chart.yAxis.axisLabel.formatter(label)))
      .toEqual(['进口原切牛腩块500g', '样品 · C P2026100021', '样品 · P22', 'P23']);
    const request = fetchInsight.mock.calls.map(([, init]) => JSON.parse(String(init.body)))
      .find(body => body.y_metric === 'cost' && body.x_dim === 'product');
    expect(request?.series_labels).toEqual(labels);
    page.unmount();
  });

  it('产品成本图给滑块和坐标轴留出独立空间，并保留完整金额', async () => {
    const amount = 1330000.25;
    const products = Array.from({ length: 11 }, (_, index) => ({
      ...costMetrics, productCode: `P${index}`, productName: `样例产品 ${index + 1}`,
      totalCost: amount - index * 100000,
    }));
    const page = mount(BlacklakeCostAnalysis, {
      ...mountOptions(),
      props: { data: { ...costSnapshot, products }, factoryId: 'LIUSHANMEN', canViewPrice: true },
    });
    await flushPromises();
    const zoomed = latestChart('产品工单总成本');
    expect(page.findAll('h3').map(heading => heading.text())).toContain('产品成本对比（元）');
    expect(zoomed.xAxis.name).toBeUndefined();
    expect(zoomed.grid).toMatchObject({ outerBoundsMode: 'same', outerBoundsContain: 'axisLabel' });
    expect(zoomed.dataZoom).toEqual([expect.objectContaining({ type: 'slider', startValue: 0, endValue: 9 })]);
    expect(zoomed.tooltip.trigger).toBe('axis');
    expect(zoomed.series[0].data[0]).toBe(amount);

    await page.setProps({ data: { ...costSnapshot, products: products.slice(0, 10) } });
    const unzoomed = latestChart('产品工单总成本');
    expect(unzoomed.dataZoom).toEqual([]);
    const [{ width, right }] = zoomed.dataZoom;
    expect(unzoomed.grid.right).toBeGreaterThan(0);
    expect(zoomed.grid.right - unzoomed.grid.right).toBe(width + right);
    page.unmount();
  });

  it('两张柱图支持升序、降序和名称排序', async () => {
    const cost = mount(BlacklakeCostAnalysis, {
      ...mountOptions(),
      props: {
        data: { ...costSnapshot, products: [
          { ...costSnapshot.products[0], productCode: 'Z', productName: 'Z', totalCost: 30 },
          { ...costSnapshot.products[0], productCode: 'A', productName: 'A', totalCost: 20 },
        ] },
        factoryId: 'LIUSHANMEN', canViewPrice: true,
      },
    });
    const production = mount(ProductionAnalysis, mountOptions());
    await flushPromises();
    expect(production.findAll('h3').map(heading => heading.text())).toContain('成品入库良品排行（KG）');
    for (const [page, title, desc, asc] of [
      [cost, '产品工单总成本', [30, 20], [20, 30]],
      [production, '成品入库良品排行（KG）', [100.125, 30], [30, 100.125]],
    ] as const) {
      const control = page.getComponent(BlacklakeBarChart).getComponent(ElSelect);
      expect(latestChart(title)?.series[0].data).toEqual(desc);
      control.vm.$emit('update:modelValue', 'asc');
      await flushPromises();
      expect(latestChart(title)?.series[0].data).toEqual(asc);
      control.vm.$emit('update:modelValue', 'name');
      await flushPromises();
      const labels: string[] = latestChart(title)?.yAxis.data;
      expect(labels).toEqual([...labels].sort(new Intl.Collator('zh-CN', { numeric: true }).compare));
      control.vm.$emit('update:modelValue', 'desc');
      await flushPromises();
      expect(latestChart(title)?.series[0].data).toEqual(desc);
    }
    cost.unmount();
    production.unmount();
  });

  it('三个成本表所有列均可排序，工单和领料排序覆盖分页外记录', async () => {
    const page = mount(BlacklakeCostAnalysis, {
      ...mountOptions(),
      props: {
        data: {
          ...costSnapshot,
          products: [
            { ...costSnapshot.products[0], productCode: 'Z', productName: 'Z产品', totalCost: 30 },
            { ...costSnapshot.products[0], productCode: 'A', productName: 'A产品', totalCost: 20 },
          ],
          workOrders: Array.from({ length: 21 }, (_, i) => ({
            ...costSnapshot.workOrders[0], workOrderCode: `工单-${i}`, totalCost: i,
          })),
          materialIssues: Array.from({ length: 21 }, (_, i) => ({
            ...costSnapshot.materialIssues[0], lineId: String(i), materialName: `原料-${i}`, issueCost: i,
          })),
        },
        factoryId: 'LIUSHANMEN', canViewPrice: true,
      },
    });
    await flushPromises();
    const tables = page.findAll('.el-table');
    expect(tables).toHaveLength(3);
    for (const table of tables) {
      expect(table.findAll('th.is-sortable')).toHaveLength(table.findAll('th').length);
    }
    const textRows = (index: number) => tables[index].findAll('tbody tr').map(row => row.text());
    expect(textRows(0)[0]).toContain('Z产品');
    await tables[0].get('th.is-sortable').trigger('click');
    await flushPromises();
    expect(textRows(0)[0]).toContain('A产品');
    expect(textRows(1).join()).not.toContain('工单-20');
    expect(textRows(2).join()).not.toContain('原料-20');
    for (const [index, label, last] of [[1, '总成本', '工单-20'], [2, '计入金额', '原料-20']] as const) {
      const header = tables[index].findAll('th').find(column => column.text() === label);
      expect(header).toBeDefined();
      await header?.trigger('click');
      await header?.trigger('click');
      await flushPromises();
      expect(textRows(index)[0]).toContain(last);
    }
    page.unmount();
  });

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
    expect(latestChart('良品数量')).toEqual(expect.objectContaining({
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
    expect(latestChart('良品数量')).toEqual(expect.objectContaining({
      series: [expect.objectContaining({ data: [30] }), expect.objectContaining({ data: [0] })],
    }));
    unit.vm.$emit('update:modelValue', '盒');
    await flushPromises();
    expect(analysis.text()).toContain('4%');
    expect(latestChart('良品数量')).toEqual(expect.objectContaining({
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
    expect(external.text()).not.toContain('成本缺失');
    expect(external.text()).not.toContain('含冲销/退料');
    for (const text of ['黑湖', '1,234.56', '1,011.23', '223.33', '12.3456',
      '每日成本趋势', '产品成本对比', '工单成本明细', 'WO-BLACKLAKE-1', '领料原料 A',
      '单位不可比', '移动均价', '不含制费（黑湖未提供）', '缺价 1 行', '计价待核 1 行']) {
      expect(external.text()).toContain(text);
    }
    expect(latestChart('总成本')).toEqual(expect.objectContaining({
      series: [
        expect.objectContaining({ data: [1234.56] }), expect.objectContaining({ data: [1011.23] }),
        expect.objectContaining({ data: [223.33] }),
      ],
    }));
    page.unmount();
  });

  it('在总成本和平均单位成本卡片旁标出未计入的缺失工单', async () => {
    pythonFetch.mockResolvedValue({
      success: true,
      data: {
        ...costSnapshot,
        summary: { ...costMetrics, workOrderCount: 3, costMissingOrders: 1, unitCostMissingOrders: 2 },
      },
    });
    const page = mount(FactoryCostAnalysis, mountOptions());
    await flushPromises();
    const cards = page.getComponent(BlacklakeCostAnalysis).get('.kpis').findAll('.el-card');
    expect(cards[0].text()).toContain('1,234.56');
    expect(cards[0].text()).toContain('成本缺失 1 单，未计入合计');
    expect(cards[3].text()).toContain('12.3456');
    expect(cards[3].text()).toContain('单位成本缺失 2 单，未计入平均');
    page.unmount();
  });

  it('趋势标注有来源依据的材料冲销并保留负值，明细区分缺失与无产出的零单价', async () => {
    pythonFetch.mockResolvedValue({
      success: true,
      data: {
        ...costSnapshot,
        daily: [
          { ...costMetrics, costDate: '2026-11-04', materialCost: -152933.11, totalCost: -140601.74, hasMaterialCredit: true },
          { ...costMetrics, costDate: '2026-11-05', materialCost: -9, totalCost: -8, hasMaterialCredit: false },
        ],
        workOrders: [
          { ...costSnapshot.workOrders[0], workOrderCode: 'MISSING-UNIT-COST', unitCost: null },
          { ...costSnapshot.workOrders[0], workOrderCode: 'NO-OUTPUT-ZERO', unitCost: 0, totalCost: 0, outputQty: 0 },
        ],
      },
    });
    const page = mount(FactoryCostAnalysis, mountOptions());
    await flushPromises();
    const analysis = page.getComponent(BlacklakeCostAnalysis);
    expect(analysis.text()).toContain('含冲销/退料：2026-11-04。');
    expect(analysis.text()).not.toContain('2026-11-05');
    const rows = analysis.findAll('tr');
    expect(rows.find(row => row.text().includes('MISSING-UNIT-COST'))?.text()).toContain('缺失');
    expect(rows.find(row => row.text().includes('NO-OUTPUT-ZERO'))?.text()).toContain('0.0000');
    expect(latestChart('总成本')).toEqual(expect.objectContaining({
      series: [
        expect.objectContaining({ data: [-140601.74, -8] }),
        expect.objectContaining({
          data: [-152933.11, -9],
          markPoint: expect.objectContaining({ data: [{ name: '含冲销/退料', coord: ['2026-11-04', -152933.11] }] }),
        }),
        expect.objectContaining({ name: '人工成本' }),
      ],
    }));
    page.unmount();
  });

  it('总成本齐全时仍单独提示材料和人工合计的缺失工单', async () => {
    pythonFetch.mockResolvedValue({
      success: true,
      data: {
        ...costSnapshot,
        summary: { ...costMetrics, workOrderCount: 3, materialCostMissingOrders: 1, laborCostMissingOrders: 2 },
        products: [{ ...costSnapshot.products[0], materialCostMissingOrders: 1, laborCostMissingOrders: 2 }],
      },
    });
    const page = mount(FactoryCostAnalysis, mountOptions());
    await flushPromises();
    const analysis = page.getComponent(BlacklakeCostAnalysis);
    const cards = analysis.get('.kpis').findAll('.el-card');
    expect(cards[0].text()).toBe('总成本1,234.56');
    expect(cards[1].text()).toContain('材料成本缺失 1 单，未计入合计');
    expect(cards[2].text()).toContain('人工成本缺失 2 单，未计入合计');
    const productTable = analysis.get('.el-table');
    expect(productTable.text()).toContain('材料成本缺失 1 单');
    expect(productTable.text()).toContain('人工成本缺失 2 单');
    page.unmount();
  });

  it('每袋成本展示样本口径，产品出成率展示可比工单数，说明领料金额与材料成本的范围差异', async () => {
    const bag = { unit: '袋', cost: 0.181027, workOrderCount: 1, zeroMaterialCostOrders: 1 };
    const partial = { unit: '盒', cost: 12.3456, workOrderCount: 2, zeroMaterialCostOrders: 1 };
    pythonFetch.mockResolvedValue({
      success: true,
      data: {
        ...costSnapshot,
        summary: {
          ...costMetrics, issuedMaterialCost: 1700, workOrderCount: 5, unitCostMissingOrders: 2,
          unitCosts: [partial, bag],
        },
        products: [
          { ...costSnapshot.products[0], productName: '牛腩样例', yieldRate: 100, yieldComparableOrders: 2, workOrderCount: 2 },
          { ...costSnapshot.products[0], productCode: 'P2', productName: '不可比样例', yieldRate: null },
          { ...costSnapshot.products[0], productCode: 'BAG', productName: '袋装样例', unitCosts: [bag] },
        ],
      },
    });
    const page = mount(FactoryCostAnalysis, mountOptions());
    await flushPromises();
    const analysis = page.getComponent(BlacklakeCostAnalysis);
    const cards = analysis.findAll('.unit-cost');
    const laborOnly = '黑湖未计材料成本，仅含人工';
    expect(cards[1].text()).toContain('0.1810 / 袋');
    expect(cards[1].text()).toContain(`1 单 · ${laborOnly}`);
    expect(cards[0].text()).toContain('2 单');
    expect(cards[0].text()).not.toContain(laborOnly);
    expect(analysis.text()).toContain('单位成本缺失 2 单，未计入平均');
    expect(analysis.text()).toContain('黑湖单价按同单位的实际产出量加权');
    const rows = analysis.findAll('tr');
    const comparable = rows.find(row => row.text().includes('牛腩样例'));
    const incomparable = rows.find(row => row.text().includes('不可比样例'));
    expect(rows.find(row => row.text().includes('袋装样例'))?.text()).toContain(laborOnly);
    expect(comparable?.text()).not.toContain(laborOnly);
    expect(comparable?.text()).toContain('100.0%');
    expect(comparable?.text()).toContain('2 / 2 单可比');
    expect(incomparable?.text()).toContain('0 / 1 单可比');
    expect(analysis.text()).toContain('领料金额 1,700.00');
    expect(analysis.text()).toContain('材料成本按工单成本日期汇总');
    expect(analysis.text()).toContain('未纳入成本汇总或未关联工单的记录');
    expect(analysis.text()).toContain('所选工单的跨期领料');
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
