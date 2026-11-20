import { mount, flushPromises } from '@vue/test-utils';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ElementPlus from 'element-plus';
import type { ExternalProductionData } from '@/api/smartbi/factoryGold';

// 成品出厂核算 tab (PAYieldCost = M67YieldCost.vue) 静态 import `@/utils/echarts`，
// 该模块静态 `import 'echarts-wordcloud'` —— 那个包在没有 canvas 2d context 的 jsdom
// 环境下会同步 throw，把整个模块炸掉。⛔ 不改生产行为(保留静态 import, 见
// utils/echarts.ts 头部说明)，测试侧规避即可：只替身词云插件本身，echarts.ts 里其余
// 17 种真实图表类型的注册逻辑照常执行。
vi.mock('echarts-wordcloud', () => ({}));

const mockRoute: { query: Record<string, unknown> } = { query: { tab: 'bom-achievement' } };
const routerReplace = vi.fn();
const routerPush = vi.fn();

vi.mock('vue-router', () => ({
  useRoute: () => mockRoute,
  useRouter: () => ({ replace: routerReplace, push: routerPush }),
}));

let mockFactoryType: string = 'LOGISTICS';
let mockFactoryId = 'LIUSHANMEN';
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: mockFactoryId, factoryType: mockFactoryType, hasRole: () => false }),
}));

vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    canWrite: () => true,
    canAccess: () => true,
    canViewPrice: true,
  }),
}));

const apiGet = vi.fn();
const apiPost = vi.fn();
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
}));
const pythonFetch = vi.fn();
vi.mock('@/api/smartbi/common', async importOriginal => ({
  ...await importOriginal<typeof import('@/api/smartbi/common')>(),
  pythonFetch: (...args: unknown[]) => pythonFetch(...args),
}));

import ProductionAnalysisHub from '../index.vue';
import ProductionReport from '@/views/analytics/production-report/index.vue';
import PAProduction from '@/views/production-analytics/ProductionAnalysis.vue';
import BomAchievement from '@/views/production/BomAchievementView.vue';
import ProcessIOComparison from '@/views/production/ProcessIOComparison.vue';
import LaborEfficiency from '@/views/production/labor-efficiency/index.vue';
import PAYieldCost from '@/views/production-analytics/M67YieldCost.vue';
import BlacklakeProductionAnalysis from '@/views/production-analytics/BlacklakeProductionAnalysis.vue';

const noExternalSource = { success: true, data: null, message: '' };
const blacklakeSnapshot: ExternalProductionData = {
  sourceSystem: 'blacklake',
  sourceLabel: '黑湖',
  snapshotCollectedAt: '2026-11-18T15:09:00Z',
  stale: false,
  yieldRate: null,
  cost: null,
  unavailableMessage: '黑湖数据不含投入量，无法计算',
  rows: [],
};
const externalSource = { success: true, data: blacklakeSnapshot, message: '' };

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
  routerReplace.mockReset();
  routerPush.mockReset();
  apiGet.mockResolvedValue({ success: true, data: [] });
  apiPost.mockResolvedValue({ success: true, data: [] });
  pythonFetch.mockReset();
  pythonFetch.mockResolvedValue(noExternalSource);
  mockFactoryType = 'LOGISTICS';
  mockFactoryId = 'LIUSHANMEN';
  // productionAnalytics.ts 的 getFactoryId() 直接读 localStorage (不经 authStore mock) ——
  // PAProduction (生产数据分析) tab 需要它, 否则 getProductionDashboard() 抛
  // 'Factory ID unavailable'。setup.ts 的全局 beforeEach 会先 clear(), 这里在其后设置。
  localStorage.setItem('cretas_user', JSON.stringify({ factoryId: 'LIUSHANMEN' }));
});

function mountHub() {
  return mount(ProductionAnalysisHub, { global: { plugins: [ElementPlus] } });
}

describe('生产分析 hub —— 6 个视角逐一挂载真实子页面 (LIUSHANMEN IA 收拢)', () => {
  it('默认 tab (BOM用量对比): 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'bom-achievement' };
    const wrapper = mountHub();
    await flushPromises();
    // BomAchievementView.vue onMounted(loadData) → get(`/${factoryId}/processing/batches`, ...)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/processing/batches'), expect.anything());
    expect(wrapper.findComponent(BomAchievement).exists()).toBe(true);
    expect(wrapper.findComponent(ProcessIOComparison).exists()).toBe(false);
    wrapper.unmount();
  });

  it('工序投入产出对比 tab: 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'process-io' };
    const wrapper = mountHub();
    await flushPromises();
    // ProcessIOComparison.vue onMounted(loadData) → get(`/${factoryId}/production/yield/by-process`, ...)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/production/yield/by-process'), expect.anything());
    expect(wrapper.findComponent(ProcessIOComparison).exists()).toBe(true);
    expect(wrapper.findComponent(BomAchievement).exists()).toBe(false);
    wrapper.unmount();
  });

  it('人效双口径对比 tab: 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'labor-efficiency' };
    const wrapper = mountHub();
    await flushPromises();
    // labor-efficiency/index.vue onMounted → loadData() → getLaborEfficiencyCompare → get(`/${factoryId}/labor-efficiency/compare`, ...)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/labor-efficiency/compare'), expect.anything());
    expect(wrapper.findComponent(LaborEfficiency).exists()).toBe(true);
    wrapper.unmount();
  });

  it('LOGISTICS 业态: 车间实时生产报表 tab 可见, 只有这一个子组件被真实挂载', async () => {
    mockFactoryType = 'LOGISTICS';
    mockRoute.query = { tab: 'workshop-report' };
    const wrapper = mountHub();
    await flushPromises();
    // production-report/index.vue onMounted → get(`/${factoryId}/reports/production-by-product`, ...)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/reports/production-by-product'), expect.anything());
    expect(wrapper.findComponent(ProductionReport).exists()).toBe(true);
    wrapper.unmount();
  });

  it('LOGISTICS 业态: 生产数据分析 tab 可见, 只有这一个子组件被真实挂载', async () => {
    mockFactoryType = 'LOGISTICS';
    mockRoute.query = { tab: 'data-analysis' };
    const wrapper = mountHub();
    await flushPromises();
    // productionAnalytics.ts getProductionDashboard() → get(`${basePath()}/dashboard`, ...) = /LIUSHANMEN/production-analytics/dashboard
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/production-analytics/dashboard'), expect.anything());
    expect(wrapper.findComponent(PAProduction).exists()).toBe(true);
    wrapper.unmount();
  });

  it('LOGISTICS 业态: 6 个顶层 tab 导航标签全部都在 —— 不多不少', async () => {
    mockFactoryType = 'LOGISTICS';
    mockRoute.query = { tab: 'bom-achievement' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
    expect(labels).toEqual([
      '车间实时生产报表', '生产数据分析', 'BOM用量对比',
      '工序投入产出对比', '人效双口径对比', '成品出厂核算',
    ]);
    wrapper.unmount();
  });

  it('FACTORY 有外部生产来源时，深链在核实后打开生产数据分析并显示黑湖区块', async () => {
    mockFactoryType = 'FACTORY';
    mockRoute.query = { tab: 'data-analysis' };
    let resolveSummary!: (result: typeof externalSource) => void;
    pythonFetch.mockImplementation((path: string) => {
      if (!path.includes('summaryOnly=true')) return Promise.resolve(externalSource);
      return new Promise<typeof externalSource>(resolve => { resolveSummary = resolve; });
    });
    const wrapper = mountHub();
    expect(wrapper.find('#tab-data-analysis').exists()).toBe(false);
    expect(pythonFetch).toHaveBeenCalledWith(
      '/api/smartbi/factory/external-production?factory_id=LIUSHANMEN&summaryOnly=true',
    );

    resolveSummary(externalSource);
    await flushPromises();
    expect(wrapper.get('#tab-data-analysis').attributes('aria-selected')).toBe('true');
    expect(wrapper.getComponent(BlacklakeProductionAnalysis).text()).toContain('黑湖');
    expect(pythonFetch).toHaveBeenCalledWith(expect.stringMatching(
      /^\/api\/smartbi\/factory\/external-production\?factory_id=LIUSHANMEN&startDate=.+&endDate=.+$/,
    ));
    expect(wrapper.find('#tab-workshop-report').exists()).toBe(false);
    expect(wrapper.find('#tab-yield-cost').exists()).toBe(false);
    wrapper.unmount();
  });

  it.each(['F006', 'FOOD_0000_001'])('FACTORY %s 无来源时保留三个标签，数据分析深链回落到 BOM', async factoryId => {
    mockFactoryType = 'FACTORY';
    mockFactoryId = factoryId;
    mockRoute.query = { tab: 'data-analysis' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
    expect(labels).toEqual(['BOM用量对比', '工序投入产出对比', '人效双口径对比']);
    expect(wrapper.get('#tab-bom-achievement').attributes('aria-selected')).toBe('true');
    expect(wrapper.findComponent(PAProduction).exists()).toBe(false);
    expect(wrapper.find('.external-source-warning').exists()).toBe(false);
    wrapper.unmount();
  });

  it.each([
    ['接口返回失败', () => Promise.resolve({ success: false, data: null, message: '读取失败' })],
    ['HTTP 403', () => Promise.reject({ status: 403 })],
  ] as const)('FACTORY %s时隐藏生产数据分析并显示提示', async (_, fail) => {
    mockFactoryType = 'FACTORY';
    mockRoute.query = { tab: 'data-analysis' };
    pythonFetch.mockImplementation(fail);
    const wrapper = mountHub();
    await flushPromises();
    expect(wrapper.find('#tab-data-analysis').exists()).toBe(false);
    expect(wrapper.get('#tab-bom-achievement').attributes('aria-selected')).toBe('true');
    expect(wrapper.get('.external-source-warning').text()).toContain('暂时无法核实外部生产数据');
    wrapper.unmount();
  });

  it('🔴 RESTAURANT 业态: 同 FACTORY, 3 个 analytics 系 tab 的 pane 不出现', async () => {
    mockFactoryType = 'RESTAURANT';
    mockRoute.query = { tab: 'bom-achievement' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
    expect(labels).toEqual(['BOM用量对比', '工序投入产出对比', '人效双口径对比']);
    wrapper.unmount();
  });

  it('阳性对照: LOGISTICS 业态下同一个 hub 确实能渲染出那 3 个 analytics 系 tab (证明上面两条不是「组件本身画不出来」)', async () => {
    mockFactoryType = 'LOGISTICS';
    mockRoute.query = { tab: 'bom-achievement' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
    expect(labels).toContain('车间实时生产报表');
    expect(labels).toContain('生产数据分析');
    expect(labels).toContain('成品出厂核算');
    wrapper.unmount();
  });

  it('切换到未曾激活的 tab 后, router.replace 被调用且带上新的 tab query', async () => {
    mockFactoryType = 'LOGISTICS';
    mockRoute.query = { tab: 'bom-achievement' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const processIoTabItem = outerHeader.findAll('.el-tabs__item')
      .find((n) => n.text() === '工序投入产出对比')!;
    await processIoTabItem.trigger('click');
    await flushPromises();
    expect(routerReplace).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ tab: 'process-io' }) }),
    );
    expect(wrapper.findComponent(ProcessIOComparison).exists()).toBe(true);
    wrapper.unmount();
  });

  it('成品出厂核算 tab (yield-cost, LOGISTICS 业态): 组件真的挂载了', async () => {
    mockFactoryType = 'LOGISTICS';
    mockRoute.query = { tab: 'yield-cost' };
    const wrapper = mountHub();
    await flushPromises();
    expect(wrapper.findComponent(PAYieldCost).exists()).toBe(true);
    wrapper.unmount();
  });
});
