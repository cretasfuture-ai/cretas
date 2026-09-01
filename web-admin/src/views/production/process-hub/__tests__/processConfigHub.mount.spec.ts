import { mount, flushPromises } from '@vue/test-utils';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * 工序 hub —— 行为验收 (硬约束 9)。见 inventoryQueryHub.mount.spec.ts 头部说明。
 */

const mockRoute: { query: Record<string, unknown> } = { query: { tab: 'work-processes' } };
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
const apiDel = vi.fn();
vi.mock('@/api/request', () => ({
  default: {
    get: (...a: unknown[]) => apiGet(...a),
    post: (...a: unknown[]) => apiPost(...a),
    put: (...a: unknown[]) => apiPut(...a),
    delete: (...a: unknown[]) => apiDel(...a),
  },
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
  put: (...args: unknown[]) => apiPut(...args),
  del: (...args: unknown[]) => apiDel(...args),
}));

import ProcessConfigHub from '../index.vue';
import WorkProcesses from '@/views/system/work-processes/index.vue';
import ProductProcesses from '@/views/system/product-processes/index.vue';

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
  apiPut.mockReset();
  apiDel.mockReset();
  routerReplace.mockReset();
  apiGet.mockResolvedValue({ success: true, data: [] });
  apiPost.mockResolvedValue({ success: true, data: [] });
  apiPut.mockResolvedValue({ success: true, data: [] });
  apiDel.mockResolvedValue({ success: true, data: [] });
});

function mountHub() {
  return mount(ProcessConfigHub, { global: { plugins: [ElementPlus] } });
}

describe('工序 hub —— 2 个视角逐一挂载真实子页面 (LIUSHANMEN IA 收拢)', () => {
  it('默认 tab (工序管理): 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'work-processes' };
    const wrapper = mountHub();
    await flushPromises();
    // work-processes/index.vue onMounted(loadData) → getWorkProcesses → get(`/${factoryId}/work-processes`, ...)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/work-processes'), expect.anything());
    expect(wrapper.findComponent(WorkProcesses).exists()).toBe(true);
    expect(wrapper.findComponent(ProductProcesses).exists()).toBe(false);
    wrapper.unmount();
  });

  it('产品-工序配置 tab: 只有这一个子组件被真实挂载, 并调用它自己原来的接口', async () => {
    mockRoute.query = { tab: 'product-processes' };
    const wrapper = mountHub();
    await flushPromises();
    // product-processes/index.vue onMounted → loadProducts() → get(`/${factoryId}/product-types/options`)
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/product-types/options'));
    expect(wrapper.findComponent(ProductProcesses).exists()).toBe(true);
    expect(wrapper.findComponent(WorkProcesses).exists()).toBe(false);
    wrapper.unmount();
  });

  it('2 个顶层 tab 导航标签都在 (工序管理/产品-工序配置) —— 不多不少', async () => {
    mockRoute.query = { tab: 'work-processes' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const labels = outerHeader.findAll('.el-tabs__item').map((n) => n.text());
    expect(labels).toEqual(['工序管理', '产品-工序配置']);
    wrapper.unmount();
  });

  it('切换到未曾激活的 tab 后, router.replace 被调用且带上新的 tab query', async () => {
    mockRoute.query = { tab: 'work-processes' };
    const wrapper = mountHub();
    await flushPromises();
    const outerHeader = wrapper.find('.el-tabs__header');
    const productProcessesTabItem = outerHeader.findAll('.el-tabs__item')[1];
    await productProcessesTabItem.trigger('click');
    await flushPromises();
    expect(routerReplace).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ tab: 'product-processes' }) }),
    );
    expect(wrapper.findComponent(ProductProcesses).exists()).toBe(true);
    wrapper.unmount();
    // 15s: 同 productionKanbanHub.mount.spec.ts 那条一样的判据 —— product-processes
    // 挂了 ProductProcessWorkflowEditor(重组件, 见 stderr 里的 catalog loading 日志),
    // 冷转译+挂载在全量套件并发跑(多 worker 抢 CPU)时能超过默认 5000ms
    // (2026-09-02 实测: 单独跑该文件通过, 混进全量套件同一条超时)。
  }, 15000);
});
