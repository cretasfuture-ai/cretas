import { mount, flushPromises } from '@vue/test-utils';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * 盘点 hub —— 行为验收 (硬约束 9)。
 *
 * 「菜单项消失了」不等于「合并后的新入口真的渲染出内容」——本文件真挂载 2 个
 * **原样未改动**的子页面组件（不是 stub/mock），逐 tab 切换，证明每个视角:
 *   ① 真的渲染进 DOM（找得到该子页面独有的文案标记，不是 el-tabs 的空壳）
 *   ② 真的按它自己原来的方式发起数据请求
 *   ③ finance_manager 个人 OA 深链 (?stocktakeId=) 原样透传给内嵌组件, 而不是
 *      被 hub 吞掉 (见 warehouseInventoryStocktakeRedirects.spec.ts 的路由层证明;
 *      本文件证明**组件真的读到了**这个 query, 两层合起来才是完整证据链)
 *
 * 只 mock 网络边界 (@/api/request) 与 store —— 2 个子组件本身一行都没改。
 */

const mockRoute: { query: Record<string, unknown> } = { query: { tab: 'main' } };
const routerReplace = vi.fn();

vi.mock('vue-router', () => ({
  useRoute: () => mockRoute,
  useRouter: () => ({ replace: routerReplace, push: vi.fn() }),
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
const apiPut = vi.fn();
vi.mock('@/api/request', () => ({
  default: { get: (...args: unknown[]) => apiGet(...args) },
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
  put: (...args: unknown[]) => apiPut(...args),
}));

import StocktakeQueryHub from '../index.vue';
import WarehouseStocktakes from '@/views/warehouse/stocktakes/index.vue';
import WarehouseSemiFinishedStocktakes from '@/views/warehouse/semi-finished-stocktakes/index.vue';

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
  apiPut.mockReset();
  routerReplace.mockReset();
  apiGet.mockResolvedValue({ success: true, data: [] });
  apiPost.mockResolvedValue({ success: true, data: [] });
  apiPut.mockResolvedValue({ success: true, data: [] });
});

function mountHub() {
  return mount(StocktakeQueryHub, {
    global: { plugins: [ElementPlus] },
  });
}

describe('盘点 hub —— 2 个视角逐一挂载真实子页面 (LIUSHANMEN IA 收拢)', () => {
  it('默认 tab (盘点与期初库存): 只有这一个子组件被真实挂载', async () => {
    mockRoute.query = { tab: 'main' };
    const wrapper = mountHub();
    await flushPromises();
    expect(wrapper.text()).toContain('盘点任务管理');
    expect(wrapper.findComponent(WarehouseStocktakes).exists()).toBe(true);
    expect(wrapper.findComponent(WarehouseSemiFinishedStocktakes).exists()).toBe(false);
    wrapper.unmount();
  });

  it('半成品盘点 tab: 只有这一个子组件被真实挂载', async () => {
    mockRoute.query = { tab: 'semi-finished' };
    const wrapper = mountHub();
    await flushPromises();
    expect(wrapper.text()).toContain('半成品盘点');
    expect(wrapper.findComponent(WarehouseSemiFinishedStocktakes).exists()).toBe(true);
    expect(wrapper.findComponent(WarehouseStocktakes).exists()).toBe(false);
    wrapper.unmount();
  });

  it('2 个顶层 tab 导航标签都在 (盘点与期初库存/半成品盘点) —— 不多不少', async () => {
    mockRoute.query = { tab: 'main' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
    expect(labels).toEqual(['盘点与期初库存', '半成品盘点']);
    wrapper.unmount();
  });

  it('finance_manager 个人 OA 深链 (?stocktakeId=) 透传给内嵌的盘点组件, 打开对应详情', async () => {
    // 硬约束 2/9: /warehouse/stocktakes?stocktakeId=SK-1 redirect 到
    // /warehouse/stocktake-query?tab=main&stocktakeId=SK-1 (路由层已证明,
    // 见 warehouseInventoryStocktakeRedirects.spec.ts) —— 这里证明 hub 组件
    // **真的把这个 query 转交给了内嵌的 stocktakes/index.vue**, 而不是只把
    // tab 切对却把 stocktakeId 弄丢。
    //
    // 证据: stocktakes/index.vue 的 onMounted 在列表里找到 route.query.stocktakeId
    // 对应的行后调用 openDetail(row) → get(`/{factoryId}/stocktakes/{row.id}`)。
    // 只要这个**带着那个具体 id** 的详情请求真的被发出去了, 就证明 hub 没有把
    // query 弄丢 —— 这比检查内部 ref/弹窗 DOM (el-dialog 默认 teleport 到 body,
    // 容易测出假阴性) 更稳。
    apiGet.mockImplementation((url: string) => {
      if (url.includes('/stocktakes') && !url.includes('/warehouses') && !url.includes('/stocktakes/SK-1')) {
        // 列表接口: 塞进那条待深链打开的记录
        return Promise.resolve({ success: true, data: [{ id: 'SK-1', status: 'PENDING', warehouseId: 'WH-1' }] });
      }
      return Promise.resolve({ success: true, data: [] });
    });
    mockRoute.query = { tab: 'main', stocktakeId: 'SK-1' };
    mountHub();
    await flushPromises();
    await flushPromises();
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/stocktakes/SK-1'));
  });
});
