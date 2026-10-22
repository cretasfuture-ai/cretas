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
  async stubView(name: string) {
    const { defineComponent, h: hFn } = await import('vue');
    return { default: defineComponent({ name, render: () => hFn('div', { class: `stub-${name}` }) }) };
  },
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { tab: 'finance' } }),
  useRouter: () => ({ replace: vi.fn() }),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/api/smartbi/gold', () => ({
  getFinanceSummary: vi.fn().mockResolvedValue({ totalRevenue: 0, billCount: 0, avgBillValue: null, storeCount: 0, topStores: [], startDate: '2026-01-01', endDate: '2026-12-31' }),
  getTopProducts: vi.fn().mockResolvedValue({ topProducts: [], startMonth: '2026-01', endMonth: '2026-12' }),
  getTrendBundle: vi.fn().mockResolvedValue({ dailyTrend: [], monthlyTrend: [], weekdayWeekend: { weekdayAvg: null, weekendAvg: null, weekdayDays: 0, weekendDays: 0 } }),
  getKpiSummary: vi.fn().mockResolvedValue({ revenue: 0, billCount: 0, itemCount: 0, customerCount: 0, storeCount: 0, avgBillValue: null, itemsPerBill: null, avgPerCapita: null, startDate: '2026-01-01', endDate: '2026-12-31' }),
}));
vi.mock('@/api/smartbi/dataRange', () => ({ getGoldDataRange: vi.fn().mockResolvedValue(null) }));

vi.mock('../FinancialDashboardPBI.vue', () => h.stubView('FinancialDashboardPBI'));
vi.mock('../SalesAnalysis.vue', () => h.stubView('SalesAnalysis'));
vi.mock('@/views/analytics/trends/index.vue', () => h.stubView('TrendsView'));
vi.mock('@/views/analytics/kpi/index.vue', () => h.stubView('KpiView'));
vi.mock('@/views/indicator-center/IndicatorCenterDashboard.vue', () => h.stubView('IndicatorCenterDashboard'));
vi.mock('../components/analysis/ChartInsights.vue', async () => {
  const { defineComponent, h: hFn } = await import('vue');
  return { default: defineComponent({
    name: 'ChartInsights',
    props: ['chartKey'],
    emits: ['focus'],
    render() { return hFn('div', { class: 'stub-insights' }); },
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

beforeEach(() => h.setFocus.mockReset());
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
