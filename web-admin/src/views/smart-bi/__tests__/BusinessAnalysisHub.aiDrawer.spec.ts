/**
 * 经营分析: AI 分析面板从固定右栏改成右侧抽屉 (默认收起, 看板占满宽度)。
 * 真实挂载 BusinessAnalysisHub + 真实 Element Plus 抽屉; 5 个子视图、概览接口、面板本身打桩
 * (面板的对话行为有自己的 spec, 这里只管「放在哪 / 怎么打开 / 聚焦是否接上」)。
 */
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';

const h = vi.hoisted(() => ({
  setFocus: vi.fn(),
  auth: { factoryId: 'F006' as string },
  api: {
    getFactorySalesSummary: vi.fn(),
    getFactoryFinance: vi.fn(),
    getFactoryTrend: vi.fn(),
    getFactoryProductionByProduct: vi.fn(),
    getFactoryKpi: vi.fn(),
  },
  async stubView(name: string) {
    const { defineComponent, h: hFn } = await import('vue');
    return { default: defineComponent({ name, render: () => hFn('div', { class: `stub-${name}` }) }) };
  },
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { tab: 'finance' } }),
  useRouter: () => ({ replace: vi.fn() }),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => h.auth }));
vi.mock('@/api/smartbi/factoryOverview', () => h.api);

vi.mock('../FinancialDashboardPBI.vue', () => h.stubView('FinancialDashboardPBI'));
vi.mock('../SalesAnalysis.vue', () => h.stubView('SalesAnalysis'));
vi.mock('@/views/analytics/trends/index.vue', () => h.stubView('TrendsView'));
vi.mock('@/views/analytics/kpi/index.vue', () => h.stubView('KpiView'));
vi.mock('@/views/indicator-center/IndicatorCenterDashboard.vue', () => h.stubView('IndicatorCenterDashboard'));
vi.mock('../components/analysis/ChartInsights.vue', async () => {
  const { defineComponent, h: hFn } = await import('vue');
  return { default: defineComponent({
    name: 'ChartInsights',
    props: ['chartKey', 'bullets'],
    emits: ['focus'],
    render() { return hFn('ul', { class: 'stub-insights' }, (this.bullets as string[]).map(b => hFn('li', b))); },
  }) };
});
vi.mock('../components/analysis/AnalysisChatPanel.vue', async () => {
  const { defineComponent, h: hFn } = await import('vue');
  return { default: defineComponent({
    name: 'AnalysisChatPanel',
    setup(_, { expose }) {
      expose({ setFocus: h.setFocus });
      return () => hFn('div', { class: 'acp-panel', 'data-testid': 'stub-panel' });
    },
  }) };
});

import BusinessAnalysisHub from '../BusinessAnalysisHub.vue';

let w: VueWrapper | null = null;

async function mountHub(): Promise<VueWrapper> {
  w = mount(BusinessAnalysisHub, { global: { plugins: [ElementPlus] }, attachTo: document.body });
  await flushPromises();
  return w;
}

const panelInDom = () => document.body.querySelector('[data-testid="stub-panel"]');
/** 面板在 DOM 里, 且从它往上没有任何一层被 v-show 藏起来 (display:none)。 */
const drawerVisible = () => {
  let el: HTMLElement | null = panelInDom() as HTMLElement | null;
  if (!el) return false;
  while (el && el !== document.body) {
    if (el.style.display === 'none') return false;
    el = el.parentElement;
  }
  return true;
};

// 线上 F006 的真实读数 (2026-10-23)
const SALES = {
  orderCount: 59, effectiveOrderCount: 42, cancelledCount: 10, draftCount: 6, rejectedCount: 1,
  inFlightCount: 22, orderAmount: 62664.67, ordersWithoutAmount: 0,
  firstOrderDate: '2026-08-09', lastOrderDate: '2026-09-18',
  topCustomers: [{ name: 'SOP-20260730-01-测试客户', orders: 12, amount: 33621.17 }],
  topProducts: [],
};

beforeEach(() => {
  h.setFocus.mockReset();
  h.auth.factoryId = 'F006';
  h.api.getFactorySalesSummary.mockReset().mockResolvedValue(SALES);
  h.api.getFactoryFinance.mockReset().mockResolvedValue({ accountsReceivable: 212577.96, accountsPayable: 2959123, customerPrepayments: 16507.02, totalRevenue: 0 });
  h.api.getFactoryTrend.mockReset().mockResolvedValue({ productionTrend: [{ date: '2026-09-30', value: 3 }], costTrend: [] });
  h.api.getFactoryProductionByProduct.mockReset().mockResolvedValue([]);
  h.api.getFactoryKpi.mockReset().mockResolvedValue({ unitCost: null, bomVarianceRate: null, avgCycleTime: null, outputCompletionRate: null });
});
afterEach(() => { w?.unmount(); w = null; document.body.innerHTML = ''; });

describe('经营分析 AI 分析抽屉', () => {
  it('默认收起: 不再有固定右栏, 面板还没渲染, 看板在', async () => {
    const hub = await mountHub();
    expect(hub.find('.bah-side').exists()).toBe(false);
    expect(hub.find('.stub-FinancialDashboardPBI').exists()).toBe(true);
    expect(panelInDom()).toBeNull();
    expect(hub.get('[data-testid="bah-ai-toggle"]').attributes('aria-expanded')).toBe('false');
  });

  it('点「AI 分析」打开抽屉, 点 × 关上 (对话面板保留, 不重建)', async () => {
    const hub = await mountHub();
    await hub.get('[data-testid="bah-ai-toggle"]').trigger('click');
    await flushPromises();
    expect(panelInDom()).not.toBeNull();
    expect(drawerVisible()).toBe(true);
    expect(hub.get('[data-testid="bah-ai-toggle"]').attributes('aria-expanded')).toBe('true');

    const panel = panelInDom();
    document.body.querySelector<HTMLElement>('[data-testid="bah-ai-close"]')!.click();
    await flushPromises();
    expect(hub.get('[data-testid="bah-ai-toggle"]').attributes('aria-expanded')).toBe('false');
    expect(panelInDom()).toBe(panel);
  });

  it('概览卡「聚焦到 AI 分析面板」: 打开抽屉并把面板聚焦到那张卡', async () => {
    const hub = await mountHub();
    const sales = hub.findAllComponents({ name: 'ChartInsights' }).find((c) => c.props('chartKey') === 'sales');
    expect(sales).toBeTruthy();
    sales!.vm.$emit('focus', 'sales');
    await flushPromises();
    expect(drawerVisible()).toBe(true);
    expect(h.setFocus).toHaveBeenCalledWith('sales');
  });
});

describe('经营分析顶部概览卡: 工厂数据 (2026-10-23, 原来是餐饮汇总)', () => {
  const card = (hub: VueWrapper, key: string) => hub.get(`[data-testid="bah-card-${key}"]`);

  it('四张卡读工厂接口, 写出真实读数; 不再出现餐饮的门店/账单, 也没有 ¥0', async () => {
    const hub = await mountHub();
    expect(h.api.getFactoryFinance).toHaveBeenCalledWith('F006');
    expect(h.api.getFactoryKpi).toHaveBeenCalledWith('F006');
    expect(card(hub, 'sales').text()).toContain('有效订单 42 张，订单金额 6.27万元');
    expect(card(hub, 'finance').text()).toContain('应收账款余额 21.26万元');
    expect(card(hub, 'trend').text()).toContain('最近一次在 2026-09-30');
    expect(hub.text()).not.toMatch(/门店|账单|¥0/);
  });

  it('没有读数的卡写明原因 (KPI 全空), 其余照常', async () => {
    const hub = await mountHub();
    expect(card(hub, 'kpi').text()).toContain('KPI 还没有可用的读数');
    expect(card(hub, 'kpi').find('.stub-insights').exists()).toBe(false);
    expect(card(hub, 'sales').find('.stub-insights').exists()).toBe(true);
  });

  it('一张卡接口失败只影响它自己, 显示后端原文', async () => {
    h.api.getFactorySalesSummary.mockRejectedValue(Object.assign(new Error('Python service error: 503'), { serverMessage: '业务数据库未配置，暂时无法汇总销售订单' }));
    const hub = await mountHub();
    expect(card(hub, 'sales').text()).toContain('业务数据库未配置，暂时无法汇总销售订单');
    expect(card(hub, 'finance').text()).toContain('应收账款余额');
  });

  it('没有所属工厂的账号: 不发请求, 写明原因', async () => {
    h.auth.factoryId = '';
    const hub = await mountHub();
    expect(h.api.getFactoryFinance).not.toHaveBeenCalled();
    expect(card(hub, 'finance').text()).toContain('当前账号没有所属工厂');
  });
});
