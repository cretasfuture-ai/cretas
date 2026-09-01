import { mount, flushPromises } from '@vue/test-utils';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * 库存查询 hub —— 行为验收 (硬约束 9)。
 *
 * 「菜单项消失了」不等于「合并后的新入口真的渲染出内容」——本文件真挂载 4 个
 * **原样未改动**的子页面组件（不是 stub/mock），逐 tab 切换，证明每个视角:
 *   ① 真的渲染进 DOM（找得到该子页面独有的文案标记，不是 el-tabs 的空壳）
 *   ② 真的按它自己原来的方式发起数据请求（endpoint 不变——合并前后行为逐字节
 *      相同，因为组件源码没有被改动过，见 inventory-query/index.vue 头部说明）
 *
 * 只 mock 网络边界 (@/api/request) 与 store —— 4 个子组件本身一行都没改，用的
 * 是真实 Element Plus 组件 (el-tabs 的 lazy 语义也要真的生效)。
 */

const mockRoute: { query: Record<string, unknown> } = { query: { tab: 'by-warehouse' } };
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
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
  put: (...args: unknown[]) => apiPut(...args),
}));

import InventoryQueryHub from '../index.vue';
import InventoryByWarehouse from '@/views/inventory/by-warehouse/index.vue';
import WarehouseInventoryTotal from '@/views/warehouse/inventory-total/index.vue';
import WarehouseInventoryBatch from '@/views/warehouse/inventory/index.vue';
import WarehouseSemiFinished from '@/views/warehouse/semi-finished/list.vue';

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
  return mount(InventoryQueryHub, {
    global: { plugins: [ElementPlus] },
  });
}

describe('库存查询 hub —— 4 个视角逐一挂载真实子页面 (LIUSHANMEN IA 收拢)', () => {
  it('默认 tab (分仓库存查询): 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'by-warehouse' };
    const wrapper = mountHub();
    await flushPromises();
    // 正面: 子页面自己的 hint 文案 (不是 tab 导航标签 —— 4 个标签任何 tab 下都会
    // 出现, 用标签当标记测不出「谁被真的渲染了」)。
    expect(wrapper.text()).toContain('按工厂 × 仓库查看当前快照');
    // by-warehouse/index.vue onMounted → loadFactoryNetwork() → get(`/${factoryId}/factories/network`)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/factories/network'));
    // 硬约束 9: 用组件身份 (不是文案子串) 证明「只有这一个视角真的挂载了」——
    // el-tab-pane 的 lazy 语义: 没激活过的 pane 连子组件实例都不存在。
    expect(wrapper.findComponent(InventoryByWarehouse).exists()).toBe(true);
    expect(wrapper.findComponent(WarehouseInventoryTotal).exists()).toBe(false);
    expect(wrapper.findComponent(WarehouseInventoryBatch).exists()).toBe(false);
    expect(wrapper.findComponent(WarehouseSemiFinished).exists()).toBe(false);
    wrapper.unmount();
  });

  it('总量 tab: 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'total' };
    const wrapper = mountHub();
    await flushPromises();
    expect(wrapper.text()).toContain('工厂级原料总库存');
    // inventory-total/index.vue onMounted → loadData() → get(`/{factoryId}/material-batches/stock-summary`)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/material-batches/stock-summary'));
    expect(wrapper.findComponent(WarehouseInventoryTotal).exists()).toBe(true);
    expect(wrapper.findComponent(InventoryByWarehouse).exists()).toBe(false);
    expect(wrapper.findComponent(WarehouseInventoryBatch).exists()).toBe(false);
    expect(wrapper.findComponent(WarehouseSemiFinished).exists()).toBe(false);
    wrapper.unmount();
  });

  it('批次 tab: 只有这一个子组件被真实挂载 (最重的一个视角, 含工作流/AI摘要依赖链)', async () => {
    mockRoute.query = { tab: 'batch' };
    const wrapper = mountHub();
    await flushPromises();
    // 库存批次查询自己的按钮/统计文案, 不是 tab 标签。
    expect(wrapper.text()).toContain('AI 盘点');
    expect(wrapper.findComponent(WarehouseInventoryBatch).exists()).toBe(true);
    expect(wrapper.findComponent(InventoryByWarehouse).exists()).toBe(false);
    expect(wrapper.findComponent(WarehouseInventoryTotal).exists()).toBe(false);
    expect(wrapper.findComponent(WarehouseSemiFinished).exists()).toBe(false);
    wrapper.unmount();
  });

  it('半成品 tab: 只有这一个子组件被真实挂载, 标题已收敛为「半成品库存」(硬约束 4)', async () => {
    mockRoute.query = { tab: 'semi-finished' };
    const wrapper = mountHub();
    await flushPromises();
    expect(wrapper.text()).toContain('半成品库存');
    // 阴性对照: 旧名字「半成品重量库存」不该再出现在合并后的页面里 (形态 D 修复)。
    expect(wrapper.text()).not.toContain('半成品重量库存');
    // semi-finished/list.vue onMounted → loadData() → get(`/{factoryId}/semi-finished/inventory`)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/semi-finished/inventory'));
    expect(wrapper.findComponent(WarehouseSemiFinished).exists()).toBe(true);
    expect(wrapper.findComponent(InventoryByWarehouse).exists()).toBe(false);
    expect(wrapper.findComponent(WarehouseInventoryTotal).exists()).toBe(false);
    expect(wrapper.findComponent(WarehouseInventoryBatch).exists()).toBe(false);
    wrapper.unmount();
  });

  it('4 个顶层 tab 导航标签都在 (分仓/总量/批次/半成品) —— 不多不少', async () => {
    mockRoute.query = { tab: 'by-warehouse' };
    const wrapper = mountHub();
    await flushPromises();
    // 只看**最外层** .el-tabs__header (第一个匹配), by-warehouse 页面自己内部
    // 也有一层 el-tabs (原料库存/成品库存/总览), 那是嵌套的, 不算在本 hub 的
    // 顶层导航里 —— 用 findAll({name}) 会把嵌套的也数进来, 所以按 DOM 结构取
    // 第一个 header 再在它下面找 item。
    const outerHeader = wrapper.find('.el-tabs__header');
    expect(outerHeader.exists()).toBe(true);
    const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
    expect(labels).toEqual(['分仓库存查询', '总库存查询', '库存批次查询', '半成品库存']);
    wrapper.unmount();
  });

  it('切换到未曾激活的 tab 后, router.replace 被调用且带上新的 tab query', async () => {
    mockRoute.query = { tab: 'by-warehouse' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const totalTabItem = outerHeader.findAll('.el-tabs__item')[1];
    expect(totalTabItem.text()).toBe('总库存查询');
    await totalTabItem.trigger('click');
    await flushPromises();
    expect(routerReplace).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ tab: 'total' }) }),
    );
    // 切过去之后, 总量视角真的挂载了。
    expect(wrapper.findComponent(WarehouseInventoryTotal).exists()).toBe(true);
    wrapper.unmount();
  });
});
