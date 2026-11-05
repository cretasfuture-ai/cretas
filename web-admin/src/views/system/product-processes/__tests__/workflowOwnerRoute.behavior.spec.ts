import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils';
import { defineComponent, nextTick } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ProductProcesses from '../index.vue';

const mocks = vi.hoisted(() => ({
  route: { query: {} as Record<string, unknown> },
  get: vi.fn(),
  getLinked: vi.fn(),
  findProducing: vi.fn(),
  products: [] as Array<Record<string, string>>,
}));
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue');
  mocks.route = reactive(mocks.route);
  return { useRoute: () => mocks.route };
});
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));
vi.mock('@/api/request', () => ({ get: mocks.get }));
vi.mock('@/api/processProduction', () => ({
  getActiveWorkProcesses: vi.fn(async () => ({ success: true, data: [] })),
  getProductWorkProcesses: mocks.getLinked,
  createProductWorkProcess: vi.fn(), deleteProductWorkProcess: vi.fn(),
  batchSortProductWorkProcesses: vi.fn(), updateProductWorkProcess: vi.fn(),
  getProductWorkProcessRecommendation: vi.fn(),
}));
vi.mock('@/api/factory', () => ({ getOperatorUsers: vi.fn(async () => ({ success: true, data: [] })) }));
vi.mock('@/api/productionPlan', () => ({ findWorkflowsProducing: mocks.findProducing }));

const editor = defineComponent({
  name: 'ProductProcessWorkflowEditor',
  props: ['productTypeId', 'canWrite'],
  template: '<div data-testid="test-editor" :data-owner="productTypeId" />',
});
let wrapper: VueWrapper | undefined;
async function mountPage(query: Record<string, unknown>) {
  mocks.route.query = query;
  wrapper = shallowMount(ProductProcesses, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        ProductProcessWorkflowEditor: editor,
        ElSelect: defineComponent({
          name: 'ElSelect', props: ['modelValue'], emits: ['update:modelValue', 'change'],
          template: '<div><slot /></div>',
        }),
        ElAlert: defineComponent({ props: ['title'], template: '<div role="alert">{{ title }}</div>' }),
      },
    },
  });
  await flushPromises();
  await nextTick();
  return wrapper;
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.products = [
    { id: 'PT-A', name: '成品A', productCategory: 'FINISHED_PRODUCT' },
    { id: 'PT-B', name: '成品B', productCategory: 'FINISHED_PRODUCT' },
    { id: 'SEMI', name: '半成品', productCategory: 'SEMI_FINISHED' },
  ];
  mocks.get.mockImplementation(async (url: string) => ({ success: true,
    data: url.includes('/product-types/options') ? { content: mocks.products } : [],
  }));
  mocks.getLinked.mockResolvedValue({ success: true, data: [] });
  mocks.findProducing.mockResolvedValue({ success: true, data: [] });
});
afterEach(() => { wrapper?.unmount(); wrapper = undefined; });

describe('explicit Workflow owner identity', () => {
  it('keeps the ordinary default when the URL has no explicit owner', async () => {
    const page = await mountPage({});
    expect(page.get('[data-testid="test-editor"]').attributes('data-owner')).toBe('PT-A');
  });
  it('loads an eligible explicit owner rather than the first option', async () => {
    const page = await mountPage({ productTypeId: 'PT-B' });
    expect(page.get('[data-testid="test-editor"]').attributes('data-owner')).toBe('PT-B');
  });
  it.each(['missing', 'SEMI', '', null, ['PT-A', 'PT-B']])(
    'fails closed for unavailable or malformed explicit target %j', async (target) => {
      const page = await mountPage({ productTypeId: target });
      expect(page.find('[data-testid="test-editor"]').exists()).toBe(false);
      expect(page.get('[data-testid="workflow-owner-unavailable"]').text()).toContain('归属对象不可用');
      expect(mocks.getLinked).not.toHaveBeenCalled();
    },
  );
  it('clears the previous owner on an invalid same-page route change, then recovers on a valid link', async () => {
    const page = await mountPage({ productTypeId: 'PT-B' });
    expect(page.get('[data-testid="test-editor"]').attributes('data-owner')).toBe('PT-B');
    mocks.route.query = { productTypeId: 'missing' };
    await flushPromises();
    expect(page.find('[data-testid="test-editor"]').exists()).toBe(false);
    mocks.route.query = { productTypeId: 'PT-A' };
    await flushPromises();
    expect(page.get('[data-testid="test-editor"]').attributes('data-owner')).toBe('PT-A');
    expect(page.find('[data-testid="workflow-owner-unavailable"]').exists()).toBe(false);
  });
  it('shows the invalid target error even when there are no available options', async () => {
    mocks.products = [];
    const page = await mountPage({ productTypeId: 'missing' });
    expect(page.find('[data-testid="test-editor"]').exists()).toBe(false);
    expect(page.get('[data-testid="workflow-owner-unavailable"]').text()).toContain('归属对象不可用');
  });
  it('allows an explicit manual selection after rejecting the unavailable link', async () => {
    const page = await mountPage({ productTypeId: 'missing' });
    const selector = page.findComponent({ name: 'ElSelect' });
    selector.vm.$emit('update:modelValue', 'PT-B');
    selector.vm.$emit('change', 'PT-B');
    await flushPromises();
    expect(page.get('[data-testid="test-editor"]').attributes('data-owner')).toBe('PT-B');
    expect(page.find('[data-testid="workflow-owner-unavailable"]').exists()).toBe(false);
  });
});
