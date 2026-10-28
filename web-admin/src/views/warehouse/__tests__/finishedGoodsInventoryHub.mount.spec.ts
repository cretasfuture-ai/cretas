import { mount, flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';

const mockRoute: { query: Record<string, unknown> } = { query: { tab: 'by-warehouse' } };
const routerReplace = vi.fn();
const apiGet = vi.fn();
let factoryType = 'FACTORY';
let canAccessSales = true;

vi.mock('vue-router', () => ({
  useRoute: () => mockRoute,
  useRouter: () => ({ replace: routerReplace, push: vi.fn() }),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006', factoryType, hasRole: () => false }),
}));

vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    canWrite: () => true,
    canAccess: (module: string) => module !== 'sales' || canAccessSales,
    canViewPrice: true,
  }),
}));

vi.mock('@/composables/useBusinessMode', () => ({
  useBusinessMode: () => ({ label: (key: string) => key === 'finishedGoods' ? '成品库存' : key }),
}));

vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

vi.mock('echarts-wordcloud', () => ({}));

// Existing tabs remain covered by their own hub regression. This test keeps the
// finished-goods child real while isolating unrelated legacy children.
vi.mock('@/views/inventory/by-warehouse/index.vue', () => ({ default: { template: '<div>分仓库存查询</div>' } }));
vi.mock('@/views/warehouse/inventory-total/index.vue', () => ({ default: { template: '<div>总库存查询</div>' } }));
vi.mock('@/views/warehouse/inventory/index.vue', () => ({ default: { template: '<div>库存批次查询</div>' } }));
vi.mock('@/views/warehouse/semi-finished/list.vue', () => ({ default: { template: '<div>半成品库存</div>' } }));

import InventoryQueryHub from '../inventory-query/index.vue';
import FinishedGoodsInventory from '@/views/sales/finished-goods/list.vue';

beforeEach(() => {
  factoryType = 'FACTORY';
  canAccessSales = true;
  mockRoute.query = { tab: 'by-warehouse' };
  routerReplace.mockReset();
  apiGet.mockReset();
  apiGet.mockResolvedValue({ success: true, data: { content: [], totalElements: 0 } });
});

describe('仓储库存查询 hub — 成品库存真实子页面', () => {
  it('无销售模块权限时不能借仓储 query 读取成品库存', async () => {
    canAccessSales = false;
    mockRoute.query = { tab: 'finished-goods' };
    const wrapper = mount(InventoryQueryHub, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(wrapper.findAll('.el-tabs__item').some((node) => node.text() === '成品库存')).toBe(false);
    expect(wrapper.findComponent(FinishedGoodsInventory).exists()).toBe(false);
    expect(apiGet).not.toHaveBeenCalled();
    wrapper.unmount();
  });
  it('餐饮工厂不能通过 hub query 绕过成品页面的原有隐藏边界', async () => {
    factoryType = 'RESTAURANT';
    // An explicit module GRANT must not bypass the restaurant exclusion.
    canAccessSales = true;
    mockRoute.query = { tab: 'finished-goods' };
    const wrapper = mount(InventoryQueryHub, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(wrapper.findAll('.el-tabs__item').some((node) => node.text() === '成品库存')).toBe(false);
    expect(wrapper.findComponent(FinishedGoodsInventory).exists()).toBe(false);
    expect(apiGet).not.toHaveBeenCalled();
    wrapper.unmount();
  });
  it('切换成品库存后挂载真实成品组件、读取查询接口并同步 tab query', async () => {
    const wrapper = mount(InventoryQueryHub, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    const finishedTab = wrapper.findAll('.el-tabs__item').find((node) => node.text() === '成品库存');
    expect(finishedTab).toBeDefined();
    await finishedTab!.trigger('click');
    await flushPromises();

    expect(routerReplace).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ tab: 'finished-goods' }) }),
    );
    expect(wrapper.findComponent(FinishedGoodsInventory).exists()).toBe(true);
    expect(wrapper.text()).toContain('入库渠道说明');
    expect(apiGet).toHaveBeenCalledWith(
      '/F006/sales/finished-goods',
      expect.objectContaining({ params: { page: 1, size: 10 } }),
    );
    wrapper.unmount();
  });
});
