import { defineComponent, h } from 'vue';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const io = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }));
vi.mock('@/api/request', () => ({ get: io.get, post: io.post, put: io.put, del: vi.fn(), default: { get: io.get, post: io.post } }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'WHOLESALE_TEST', user: { id: 1709 } }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true, canRead: () => true, canAccess: () => true, hasPermission: () => true }) }));
vi.mock('@/composables/useBusinessMode', () => ({ useBusinessMode: () => ({ label: (value: string) => value }) }));
vi.mock('vue-router', () => ({ useRoute: () => ({ path: '/sales/orders', query: {} }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock('element-plus', () => ({ ElMessage: Object.assign(vi.fn(), { warning: vi.fn(), success: vi.fn(), error: vi.fn() }), ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm') } }));

import SalesOrderList from '../list.vue';

const pass = defineComponent({ setup(_props, { slots }) { return () => h('div', slots.default?.()); } });
const dialog = defineComponent({ props: ['modelValue', 'title'], setup(props, { slots }) { return () => props.modelValue ? h('section', { 'data-editor': '', 'data-title': props.title }, [slots.default?.(), slots.footer?.()]) : null; } });
const button = defineComponent({ emits: ['click'], setup(_props, { slots, emit }) { return () => h('button', { onClick: () => emit('click') }, slots.default?.()); } });
const datePicker = defineComponent({ setup(_props, { expose }) { expose({ validate: () => true }); return () => h('span'); } });

interface Vm {
  handleEdit(row: { id: string }): Promise<void>;
  handleAiFill(params: Record<string, unknown>): void;
  editingOrderId: string | null;
  dialogVisible: boolean;
  form: { shippingFee: number; extraFees: unknown[]; suppliedMaterials: unknown[]; customFields: Record<string, unknown>; processingMode: string };
}

describe('sales AI entry starts a new order after editing', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    io.get.mockImplementation(async (path: string) => {
      if (path.endsWith('/sales/orders/old-order')) return { success: true, data: {
        id: 'old-order', customerId: 'customer', processingMode: 'COMMISSIONED_PROCESSING', materialSupplyMode: 'CUSTOMER_SUPPLIED',
        shippingFee: 99, extraFees: [{ name: '旧费用', amount: 88 }], customFields: { old: true }, suppliedMaterials: [], items: [], version: 3,
      } };
      if (path.endsWith('/customers')) return { success: true, data: { content: [{ id: 'customer', name: '验收客户' }] } };
      if (path.endsWith('/product-types/sellable')) return { success: true, data: [{ id: 'product', name: '验收商品', unit: 'kg', taxRate: 13 }] };
      return { success: true, data: { content: [], totalElements: 0 } };
    });
    io.post.mockResolvedValue({ success: true, data: { id: 'new-order' } });
  });

  it('clears editing identity and old fees, then the real create button POSTs instead of PUTting the old order', async () => {
    const wrapper = shallowMount(SalesOrderList, { global: { stubs: {
      CanvasAwareWrapper: pass, ElDialog: dialog, ElForm: pass, ElFormItem: pass, ElButton: button, OrderDeliveryDatePicker: datePicker,
      ElTable: defineComponent({ template: '<div />' }), ElTableColumn: defineComponent({ template: '<div />' }),
    } } });
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;
    await vm.handleEdit({ id: 'old-order' });
    expect(vm.editingOrderId).toBe('old-order');
    expect(vm.form.shippingFee).toBe(99);
    vm.dialogVisible = false;
    await flushPromises();
    vm.handleAiFill({ customerName: '验收客户', requiredDeliveryDate: '2026-11-16', items: [{ productName: '验收商品', quantity: 1, unit: 'kg', unitPrice: 10, taxRate: 13 }] });
    await flushPromises();
    expect(vm.editingOrderId).toBeNull();
    expect(vm.form.shippingFee).toBe(0);
    expect(vm.form.extraFees).toEqual([]);
    expect(vm.form.suppliedMaterials).toEqual([]);
    expect(vm.form.customFields).toEqual({});
    expect(vm.form.processingMode).toBe('STANDARD_SALE');
    expect(wrapper.get('[data-editor]').attributes('data-title')).toBe('新建salesOrder');
    await wrapper.findAll('button').find((item) => item.text() === '创建')!.trigger('click');
    await flushPromises();
    expect(io.post).toHaveBeenCalledWith('/WHOLESALE_TEST/sales/orders', expect.objectContaining({ customerId: 'customer' }));
    expect(io.put).not.toHaveBeenCalled();
    wrapper.unmount();
  });
});
