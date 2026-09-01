import { mount, flushPromises } from '@vue/test-utils';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * 生产看板 hub —— 行为验收 (硬约束 9)。见 inventoryQueryHub.mount.spec.ts 头部说明。
 */

const mockRoute: { query: Record<string, unknown> } = { query: { tab: 'pending-queue' } };
const routerReplace = vi.fn();
const routerPush = vi.fn();

vi.mock('vue-router', () => ({
  useRoute: () => mockRoute,
  useRouter: () => ({ replace: routerReplace, push: routerPush }),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'LIUSHANMEN', hasRole: () => false }),
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

import ProductionKanbanHub from '../index.vue';
import ProductionPendingQueue from '@/views/production/plans/PendingQueue.vue';
import RestockBoard from '@/views/production/restock-board/index.vue';
import ProductionDeliveryWarnings from '@/views/production/delivery-warnings/list.vue';

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
  routerReplace.mockReset();
  routerPush.mockReset();
  apiGet.mockResolvedValue({ success: true, data: [] });
  apiPost.mockResolvedValue({ success: true, data: [] });
});

function mountHub() {
  return mount(ProductionKanbanHub, { global: { plugins: [ElementPlus] } });
}

describe('生产看板 hub —— 3 个视角逐一挂载真实子页面 (LIUSHANMEN IA 收拢)', () => {
  it('默认 tab (未完成计划队列): 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'pending-queue' };
    const wrapper = mountHub();
    await flushPromises();
    // PendingQueue.vue onMounted → loadData() → get(`/${factoryId}/production-plans`, ...) ×2 (allSettled)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/production-plans'), expect.anything());
    expect(wrapper.findComponent(ProductionPendingQueue).exists()).toBe(true);
    expect(wrapper.findComponent(RestockBoard).exists()).toBe(false);
    expect(wrapper.findComponent(ProductionDeliveryWarnings).exists()).toBe(false);
    wrapper.unmount();
  });

  it('备货看板 tab: 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'restock-board' };
    // restock-board/index.vue 把 res.data 解构成 {dates, rows, summary} (不是数组) ——
    // 通用的 `data: []` 兜底会让 res.data.rows 读到 undefined, 组件内部
    // hasCoveredButNotShippable computed 的 rows.value.some(...) 随即在下一次
    // reactive flush 时炸成 unhandled rejection (2026-09-02 实测踩中一次)。
    apiGet.mockResolvedValue({
      success: true,
      data: { dates: [], rows: [], summary: { totalProducts: 0, shortfallProducts: 0, fullyCoveredProducts: 0, days: 0 } },
    });
    const wrapper = mountHub();
    await flushPromises();
    // restock-board/index.vue onMounted(load) → getRestockHorizon → get(`/${factoryId}/restock-board/horizon`, ...)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/restock-board/horizon'), expect.anything());
    expect(wrapper.findComponent(RestockBoard).exists()).toBe(true);
    expect(wrapper.findComponent(ProductionPendingQueue).exists()).toBe(false);
    expect(wrapper.findComponent(ProductionDeliveryWarnings).exists()).toBe(false);
    wrapper.unmount();
    // 15s: 与 warehouseInventoryStocktakeRedirects.spec.ts 同一条判据 —— 冷转译在
    // 全量套件并发跑(多 worker 抢 CPU)时能超过默认 5000ms 超时(2026-09-02 实测:
    // 单独跑该文件 3663ms 通过, 混进同目录全部 hub spec 一起跑时同一条超过 5000ms)。
  }, 15000);

  it('交货预警 tab: 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'delivery-warnings' };
    const wrapper = mountHub();
    await flushPromises();
    // delivery-warnings/list.vue onMounted → get(`/${factoryId}/production-plans/delivery-warnings`, ...)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/production-plans/delivery-warnings'), expect.anything());
    expect(wrapper.findComponent(ProductionDeliveryWarnings).exists()).toBe(true);
    expect(wrapper.findComponent(ProductionPendingQueue).exists()).toBe(false);
    expect(wrapper.findComponent(RestockBoard).exists()).toBe(false);
    wrapper.unmount();
  });

  it('3 个顶层 tab 导航标签都在 (未完成计划队列/备货看板/交货预警) —— 不多不少', async () => {
    mockRoute.query = { tab: 'pending-queue' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
    expect(labels).toEqual(['未完成计划队列', '备货看板', '交货预警']);
    wrapper.unmount();
  });

  it('切换到未曾激活的 tab 后, router.replace 被调用且带上新的 tab query', async () => {
    mockRoute.query = { tab: 'pending-queue' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const deliveryTabItem = outerHeader.findAll('.el-tabs__item')[2];
    expect(deliveryTabItem.text()).toBe('交货预警');
    await deliveryTabItem.trigger('click');
    await flushPromises();
    expect(routerReplace).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ tab: 'delivery-warnings' }) }),
    );
    expect(wrapper.findComponent(ProductionDeliveryWarnings).exists()).toBe(true);
    wrapper.unmount();
  });
});
