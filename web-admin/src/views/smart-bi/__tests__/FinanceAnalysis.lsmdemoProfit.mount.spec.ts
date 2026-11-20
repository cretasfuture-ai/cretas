/**
 * PROTO-DEMO-ARAP 追加项: 经营分析 → 财务 (FinanceAnalysis, 系统数据模式) 在 LSMDEMO 下
 * 显示「毛利润 0 元 / 毛利率 N/A / 系统财务数据暂不可用，请上传 Excel」。
 *
 * fixture 是 2026-11-18 用 lsmdemo_finance_mgr 只读 GET 抓的真实响应
 * (/api/mobile/LSMDEMO/smart-bi/analysis/finance?analysisType=profit, 730 天窗):
 * GROSS_PROFIT / NET_PROFIT 的 value 都是 null, description = 「所选期间缺少收入或成本数据，无法计算」。
 * 旧代码 Number(null) ⇒ 0 ⇒ 屏上「毛利润 0 元」(编出来的 0)。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import type { ShippedGrossProfit } from '@/api/financeReport';
import fixture from './fixtures-lsmdemo-finance-profit.json';

const mocks = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('echarts-wordcloud', () => ({}));
vi.mock('@/utils/echarts', () => ({ default: { init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), on: vi.fn(), off: vi.fn(), clear: vi.fn() })), getInstanceByDom: vi.fn(), graphic: { LinearGradient: vi.fn() } } }));
vi.mock('@/api/request', () => ({ get: mocks.get, post: vi.fn(), default: { get: vi.fn() } }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'LSMDEMO', factoryType: 'FACTORY', user: { id: 1 } }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: true, canWrite: () => true, canAccess: () => true }) }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { tab: 'profit' }, fullPath: '/x' }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock('@/api/smartbi', async (orig) => ({
  ...(await orig<typeof import('@/api/smartbi')>()),
  getUploadHistory: vi.fn(async () => ({ success: true, data: [] })),
}));
vi.mock('@/api/smartbi/dataRange', () => ({ getGoldDataRange: vi.fn(async () => { throw new Error('no gold'); }) }));
vi.mock('@/api/smartbi/gold', () => ({ getFinanceSummary: vi.fn() }));
vi.mock('@/composables/useCapability', async () => {
  const { ref } = await import('vue');
  return { useCapability: () => ({ fetchCapability: vi.fn(), hasAll: () => true, missingOf: () => [], available: ref(true), loading: ref(false), suggestions: ref([]) }) };
});

import FinanceAnalysis from '../FinanceAnalysis.vue';

const stubs = { teleport: true, TemplateGrid: true, UnlockMoreCTA: true, DynamicChartRenderer: true, ChartInsightProvider: true, ElAlert: { props: ['title'], template: '<div class="el-alert-stub">{{ title }}<slot /></div>' }, ChartTypeSelector: true, CapabilityGate: { template: '<div><slot /></div>' } };

const noShipment: ShippedGrossProfit = {
  periodStart: '2026-11-12',
  periodEnd: '2026-11-18',
  status: 'NO_SHIPMENT',
  shipmentLineCount: 0,
  revenue: null,
  cost: null,
  grossProfit: null,
  marginRate: null,
  unavailableReason: '缺少发货确认数据',
  revenueBasis: '发货收入按实发数量和不含税单价计算',
  basis: 'GROSS' as const,
  costBasis: '销售成本按实际出库批次计算',
};

async function mountWith(resp: unknown, shippedProfit = noShipment) {
  mocks.get.mockImplementation(async (url: string) => {
    if (url === '/LSMDEMO/finance/report/shipped-gross-profit') {
      return { success: true, data: shippedProfit };
    }
    if (url.includes('/smart-bi/analysis/finance')) {
      if (resp instanceof Error) throw resp;
      return resp;
    }
    return { success: true, data: [] };
  });
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  const w = mount(FinanceAnalysis, { global: { stubs } });
  await flushPromises();
  await flushPromises();
  return w;
}

beforeEach(() => { mocks.get.mockReset(); });

describe('FinanceAnalysis × LSMDEMO 真实响应', () => {
  it('毛利/净利算不出 (null) ⇒ 显示「—」并说明缺什么、按什么数据算, 不显示编出来的 0', async () => {
    const w = await mountWith(fixture);
    expect(w.get('[data-testid="shipped-gp-profit"]').text()).toBe('—');
    expect(w.get('[data-testid="shipped-gp-profit-note"]').text()).toContain('缺少发货确认数据');
    expect(w.get('[data-testid="fa-gross-profit"]').text()).toBe('—');
    const why = w.get('[data-testid="fa-gross-profit-why"]').text();
    expect(why).toContain('缺少收入或成本数据');
    expect(why).toContain('上传的财务/销售报表');
    expect(w.get('[data-testid="fa-net-profit"]').text()).toBe('—');
    expect(w.text()).not.toContain('请上传 Excel');
  });

  it('阴性对照: 后端给出数值时照常显示金额', async () => {
    const withValue = JSON.parse(JSON.stringify(fixture));
    for (const m of withValue.data.metrics) {
      if (m.metricCode === 'GROSS_PROFIT') m.value = 12345;
      if (m.metricCode === 'NET_PROFIT') m.value = 678;
    }
    const w = await mountWith(withValue, {
      ...noShipment,
      status: 'OK',
      shipmentLineCount: 1,
      revenue: 1000,
      cost: 800,
      grossProfit: 200,
      marginRate: 20,
      unavailableReason: null,
    });
    const systemCard = w.get('[data-testid="shipped-gp-card"]');
    expect(systemCard.get('[data-testid="shipped-gp-profit"]').text()).toContain('200.00');
    expect(systemCard.get('[data-testid="shipped-gp-profit-note"]').text()).toBe('毛利率 20.00%');
    expect(w.findAll('[data-testid="shipped-gp-card"], [data-testid="fa-gross-profit"]')[0].element)
      .toBe(systemCard.element);
    expect(w.get('[data-testid="fa-gross-profit"]').text()).toContain('1.2万元');
    expect(w.find('[data-testid="fa-gross-profit-why"]').exists()).toBe(false);
    expect(w.get('[data-testid="fa-net-profit"]').text()).toContain('678');
  });

  it('请求失败: 如实说加载失败及原因, 不笼统地叫人「上传 Excel」', async () => {
    const w = await mountWith(new Error('Request failed with status code 503'));
    expect(w.text()).toContain('财务数据加载失败');
    expect(w.text()).toContain('503');
    expect(w.text()).not.toContain('请上传 Excel');
  });
});
