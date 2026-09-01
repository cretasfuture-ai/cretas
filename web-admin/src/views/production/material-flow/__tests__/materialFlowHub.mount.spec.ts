import { mount, flushPromises } from '@vue/test-utils';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * 领料 hub —— 行为验收 (硬约束 9)。见 inventoryQueryHub.mount.spec.ts 头部说明
 * (同一套判据: 真挂载原样未改动的子页面组件, 逐 tab 切换, 证明渲染 + 发起原接口)。
 */

const mockRoute: { query: Record<string, unknown> } = { query: { tab: 'requisitions' } };
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
  default: { get: (...a: unknown[]) => apiGet(...a), post: (...a: unknown[]) => apiPost(...a), put: (...a: unknown[]) => apiPut(...a) },
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
  put: (...args: unknown[]) => apiPut(...args),
}));

import MaterialFlowHub from '../index.vue';
import FactoryMaterialRequisitions from '@/views/factory/material-requisitions/list.vue';
import ProductionMaterialReturns from '@/views/factory/material-returns/list.vue';

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
  return mount(MaterialFlowHub, { global: { plugins: [ElementPlus] } });
}

describe('领料 hub —— 2 个视角逐一挂载真实子页面 (LIUSHANMEN IA 收拢)', () => {
  it('默认 tab (领料单): 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'requisitions' };
    const wrapper = mountHub();
    await flushPromises();
    // material-requisitions/list.vue onMounted → loadData() → get(`/${factoryId}/material-requisitions`)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/material-requisitions'), expect.anything());
    expect(wrapper.findComponent(FactoryMaterialRequisitions).exists()).toBe(true);
    expect(wrapper.findComponent(ProductionMaterialReturns).exists()).toBe(false);
    wrapper.unmount();
  });

  it('退料记录 tab: 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'returns' };
    const wrapper = mountHub();
    await flushPromises();
    // material-returns/list.vue onMounted(loadData) → get(`/${factoryId}/production-material-returns`)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/production-material-returns'), expect.anything());
    expect(wrapper.findComponent(ProductionMaterialReturns).exists()).toBe(true);
    expect(wrapper.findComponent(FactoryMaterialRequisitions).exists()).toBe(false);
    wrapper.unmount();
  });

  it('2 个顶层 tab 导航标签都在 (领料单/退料记录) —— 不多不少', async () => {
    mockRoute.query = { tab: 'requisitions' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
    expect(labels).toEqual(['领料单', '退料记录']);
    wrapper.unmount();
  });

  it('切换到未曾激活的 tab 后, router.replace 被调用且带上新的 tab query', async () => {
    mockRoute.query = { tab: 'requisitions' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const returnsTabItem = outerHeader.findAll('.el-tabs__item')[1];
    await returnsTabItem.trigger('click');
    await flushPromises();
    expect(routerReplace).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ tab: 'returns' }) }),
    );
    expect(wrapper.findComponent(ProductionMaterialReturns).exists()).toBe(true);
    wrapper.unmount();
  });
});
