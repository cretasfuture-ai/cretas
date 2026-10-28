import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';

const state = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), push: vi.fn(), canWrite: true }));
const route = reactive<{ query: Record<string, string> }>({ query: { batchId: '11003' } });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: state.push }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => state.canWrite }) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => state.get(...args), post: (...args: unknown[]) => state.post(...args) }));
vi.mock('@/composables/useListSummary', () => ({ useListSummary: () => ({ summary: null, loading: false }) }));
vi.mock('@/composables/useCreateAndReturn', () => ({ useCreateAndReturn: () => ({ goCreate: vi.fn() }) }));
import Page from '../list.vue';

const batch = { id: 11003, batchNumber: '11003', productName: 'QA原料成品', unit: '盒', quantity: 10 };
const dynamicForm = {
  batchId: '11003', productTypeId: 'SKU-QA', productName: 'QA原料成品', standardRevision: 'rev-7',
  items: [{ bindingId: 'binding-temp', itemId: 'item-temp', itemVersion: 3, label: '温度', valueType: 'RANGE', unit: '°C', effectiveMinValue: 0, effectiveMaxValue: 4, effectiveIsRequired: true }],
};
const ok = (data: unknown) => ({ success: true, data });

function mountPage() {
  return mount(Page, { global: { plugins: [ElementPlus], stubs: {
    CanvasAwareWrapper: { template: '<div><slot /></div>' }, CanvasDynamicFields: true,
    TableFooter: true, UpstreamMissingHint: true,
  } } });
}

beforeEach(() => {
  vi.clearAllMocks();
  route.query = { batchId: '11003' };
  state.get.mockImplementation((url: string) => {
    if (url.endsWith('/batches/11003')) return Promise.resolve(ok(batch));
    if (url.endsWith('/quality-form')) return Promise.resolve(ok(dynamicForm));
    return Promise.resolve(ok({ content: [], totalElements: 0 }));
  });
});

describe('质检列表动态标准入口', () => {
  it('读取当前批次标准并挂载动态表单', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const create = wrapper.findAll('button').find((button) => button.text() === '新建质检');
    expect(create).toBeTruthy();
    await create!.trigger('click');
    await flushPromises();
    expect(state.get).toHaveBeenCalledWith('/F006/processing/batches/11003/quality-form');
    expect(wrapper.text()).toContain('动态质检标准 · rev-7');
    expect(wrapper.text()).toContain('温度');
  });

  it('标准读取失败时 fail-closed，不显示旧表单或调用旧 POST', async () => {
    state.get.mockImplementation((url: string) => {
      if (url.endsWith('/batches/11003')) return Promise.resolve(ok(batch));
      if (url.endsWith('/quality-form')) return Promise.reject(new Error('标准服务暂不可用'));
      return Promise.resolve(ok({ content: [], totalElements: 0 }));
    });
    const wrapper = mountPage();
    await flushPromises();
    const create = wrapper.findAll('button').find((button) => button.text() === '新建质检');
    await create!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('标准服务暂不可用');
    expect(wrapper.text()).not.toContain('抽样数量');
    expect(state.post).not.toHaveBeenCalled();
  });

  it('显式空标准继续既有抽样表单', async () => {
    state.get.mockImplementation((url: string) => {
      if (url.endsWith('/batches/11003')) return Promise.resolve(ok(batch));
      if (url.endsWith('/quality-form')) return Promise.resolve(ok({ ...dynamicForm, items: [] }));
      return Promise.resolve(ok({ content: [], totalElements: 0 }));
    });
    const wrapper = mountPage();
    await flushPromises();
    const create = wrapper.findAll('button').find((button) => button.text() === '新建质检');
    await create!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('抽样数量');
    expect(wrapper.text()).not.toContain('动态质检标准 · rev-7');
  });
});
