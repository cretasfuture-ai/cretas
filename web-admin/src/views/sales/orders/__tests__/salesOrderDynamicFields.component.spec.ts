import { defineComponent } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';
import NewSalesOrder from '../new.vue';

const h = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), push: vi.fn() }));
vi.mock('@/api/request', () => ({ get: h.get, post: h.post }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));
vi.mock('@/composables/useBusinessMode', () => ({ useBusinessMode: () => ({ label: (value: string) => value }) }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: h.push }) }));

const DynamicFieldsStub = defineComponent({
  props: { modelValue: { type: Object, default: () => ({}) } },
  emits: ['update:modelValue'],
  template: '<button type="button" data-testid="dynamic-field-edit" @click="$emit(\'update:modelValue\', { channel: \'线上\', priority: 2 })">填写动态字段</button>',
});

describe('sales order new dynamic fields', () => {
  beforeEach(() => {
    h.get.mockImplementation((path: string) => {
      if (path.endsWith('/customers')) return Promise.resolve({ success: true, data: [{ id: 'C1', name: '客户一' }] });
      if (path.endsWith('/product-types/sellable')) return Promise.resolve({ success: true, data: [] });
      if (path.endsWith('/factory/warehouses')) return Promise.resolve({ success: true, data: [{ id: 'W1', code: 'FG', name: '成品仓', type: 'FINISHED', isActive: true }] });
      if (path.endsWith('/sales/product-inventory-summary')) return Promise.resolve({ success: true, data: [] });
      return Promise.resolve({ success: true, data: [] });
    });
    h.post.mockResolvedValue({ success: true, data: { id: 'SO-1' } });
  });

  it('mounts the new-order form, forwards dynamic values, and submits them with existing line fields', async () => {
    const wrapper = mount(NewSalesOrder, { global: { plugins: [ElementPlus], stubs: { CanvasDynamicFields: DynamicFieldsStub } } });
    await flushPromises();
    const vm = wrapper.vm as any;
    vm.form.customerId = 'C1';
    vm.form.items = [{ product: { id: 'P1', name: '成品', unit: 'kg' }, quantity: 3, unit: 'kg', taxIncludedUnitPrice: 12.5, taxRate: 13, unitPrice: 11.06, sourceWarehouseCode: 'FG' }];
    await wrapper.get('[data-testid="dynamic-field-edit"]').trigger('click');
    expect(vm.form.customFields).toEqual({ channel: '线上', priority: 2 });
    await vm.submit();
    expect(h.post).toHaveBeenCalledWith('/F006/sales/orders', expect.objectContaining({
      customFields: { channel: '线上', priority: 2 },
      customerId: 'C1',
      items: [expect.objectContaining({ productTypeId: 'P1', quantity: 3, unit: 'kg', sourceWarehouseCode: 'FG' })],
    }));
  });

  it('starts with an empty customFields map and does not invent values when definitions are absent', async () => {
    const wrapper = mount(NewSalesOrder, { global: { plugins: [ElementPlus], stubs: { CanvasDynamicFields: { template: '<span data-testid="no-fields" />' } } } });
    await flushPromises();
    expect((wrapper.vm as any).form.customFields).toEqual({});
    expect(wrapper.find('[data-testid="no-fields"]').exists()).toBe(true);
  });
});
