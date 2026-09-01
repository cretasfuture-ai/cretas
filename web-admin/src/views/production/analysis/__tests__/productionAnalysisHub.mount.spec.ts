import { mount, flushPromises } from '@vue/test-utils';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * 生产分析 hub —— 行为验收 (硬约束 9)。见 inventoryQueryHub.mount.spec.ts 头部说明。
 *
 * 额外覆盖本 hub 特有的一点: 3 个「analytics 系」tab (车间实时生产报表/生产数据分析/
 * 成品出厂核算) 合并前各自 hideForFactoryTypes:['RESTAURANT','FACTORY']，合并后
 * 业态门控挪到组件内部 (showAnalyticsTabs) —— 这里用真实 mount 证明 FACTORY 业态下
 * 那 3 个 tab-pane 连 DOM 都不出现，与合并前「路由守卫 403」的最终用户可见效果一致。
 */

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
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'LIUSHANMEN', factoryType: mockFactoryType, hasRole: () => false }),
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

import ProductionAnalysisHub from '../index.vue';
import ProductionReport from '@/views/analytics/production-report/index.vue';
import PAProduction from '@/views/production-analytics/ProductionAnalysis.vue';
import BomAchievement from '@/views/production/BomAchievementView.vue';
import ProcessIOComparison from '@/views/production/ProcessIOComparison.vue';
import LaborEfficiency from '@/views/production/labor-efficiency/index.vue';
import PAYieldCost from '@/views/production-analytics/M67YieldCost.vue';

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
  routerReplace.mockReset();
  routerPush.mockReset();
  apiGet.mockResolvedValue({ success: true, data: [] });
  apiPost.mockResolvedValue({ success: true, data: [] });
  mockFactoryType = 'LOGISTICS';
  // productionAnalytics.ts 的 getFactoryId() 直接读 localStorage (不经 authStore mock) ——
  // PAProduction (生产数据分析) tab 需要它, 否则 getProductionDashboard() 抛
  // 'Factory ID unavailable'。setup.ts 的全局 beforeEach 会先 clear(), 这里在其后设置。
  localStorage.setItem('cretas_user', JSON.stringify({ factoryId: 'LIUSHANMEN' }));
});

function mountHub() {
  return mount(ProductionAnalysisHub, { global: { plugins: [ElementPlus] } });
}

describe('生产分析 hub —— 6 个视角逐一挂载真实子页面 (LIUSHANMEN IA 收拢)', () => {
  it('默认 tab (BOM达成率分析): 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
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
      '车间实时生产报表', '生产数据分析', 'BOM达成率分析',
      '工序投入产出对比', '人效双口径对比', '成品出厂核算',
    ]);
    wrapper.unmount();
  });

  it('🔴 FACTORY 业态: 3 个 analytics 系 tab 的 pane 连 DOM 都不出现 (与合并前 hideForFactoryTypes 逐字节等价)', async () => {
    mockFactoryType = 'FACTORY';
    mockRoute.query = { tab: 'bom-achievement' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
    expect(labels).toEqual(['BOM达成率分析', '工序投入产出对比', '人效双口径对比']);
    expect(labels).not.toContain('车间实时生产报表');
    expect(labels).not.toContain('生产数据分析');
    expect(labels).not.toContain('成品出厂核算');
    wrapper.unmount();
  });

  it('🔴 RESTAURANT 业态: 同 FACTORY, 3 个 analytics 系 tab 的 pane 不出现', async () => {
    mockFactoryType = 'RESTAURANT';
    mockRoute.query = { tab: 'bom-achievement' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
    expect(labels).toEqual(['BOM达成率分析', '工序投入产出对比', '人效双口径对比']);
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
