import { flushPromises, shallowMount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide, type ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), success: vi.fn(), error: vi.fn() }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => harness.get(...args), post: (...args: unknown[]) => harness.post(...args), put: (...args: unknown[]) => harness.put(...args), del: (...args: unknown[]) => harness.del(...args) }));
vi.mock('element-plus', () => ({ ElMessage: { success: harness.success, error: harness.error, warning: vi.fn() }, ElMessageBox: { confirm: vi.fn() } }));

import StandardsList from '../list.vue';

const ROWS = Symbol('rows');
const ElTableStub = defineComponent({ props: { data: { type: Array, default: () => [] } }, setup(props, { slots }) { provide(ROWS, computed(() => props.data)); return () => h('div', slots.default?.()); } });
const ElTableColumnStub = defineComponent({ setup(_props, { slots }) { const rows = inject<ComputedRef<unknown[]>>(ROWS, computed(() => [])); return () => h('div', rows.value.map((row: unknown) => slots.default?.({ row }))); } });
const PassThrough = defineComponent({ setup(_props, { slots }) { return () => h('div', slots.default?.()); } });
const ElButtonStub = defineComponent({ setup(_props, { slots, attrs }) { return () => h('button', attrs, slots.default?.()); } });
const ElSelectStub = defineComponent({ props: { modelValue: { type: [String, Boolean, Number], default: '' } }, emits: ['update:modelValue'], setup(props, { emit, attrs }) { return () => h('select', { 'data-testid': attrs.filterable ? 'binding-product-select' : undefined, value: props.modelValue, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value) }, [h('option', { value: 'QA-S301-A' }, 'QA产品A')]); } });
const ElDialogStub = defineComponent({ setup(_props, { slots }) { return () => h('div', [slots.default?.(), slots.footer?.()]); } });

describe('quality standard product binding mounted contract', () => {
  beforeEach(() => {
    harness.get.mockImplementation((url: string) => {
      if (url.includes('/quality-check-items/statistics')) return Promise.resolve({ success: true, data: { totalCount: 1, enabledCount: 1, requiredCount: 1, criticalCount: 0 } });
      if (url.includes('/quality-check-items')) return Promise.resolve({ success: true, data: { content: [{ id: 'item-a', itemName: 'QA温度', category: 'PHYSICAL', enabled: true, isRequired: true, bindingCount: 0 }] } });
      if (url.includes('/product-types')) return Promise.resolve({ success: true, data: { content: [{ id: 'QA-S301-A', name: 'QA产品A' }] } });
      return Promise.resolve({ success: true, data: {} });
    });
    harness.post.mockResolvedValue({ success: true, data: {} });
    harness.success.mockReset();
    harness.error.mockReset();
  });

  it('clicks the real binding action, selects a product, and asserts the scoped save payload', async () => {
    const wrapper = shallowMount(StandardsList, {
      global: { stubs: { ElCard: PassThrough, ElTable: ElTableStub, ElTableColumn: ElTableColumnStub, ElButton: ElButtonStub, ElInput: PassThrough, ElSelect: ElSelectStub, ElOption: PassThrough, ElPagination: PassThrough, ElTag: PassThrough, ElIcon: PassThrough, ElDialog: ElDialogStub, ElForm: PassThrough, ElFormItem: PassThrough, ElInputNumber: PassThrough, ElCheckbox: PassThrough } },
    });
    await flushPromises();
    expect(wrapper.text()).toContain('绑定产品');
    const openButton = wrapper.findAll('button').find((button) => button.text().includes('绑定产品'));
    expect(openButton).toBeDefined();
    await openButton!.trigger('click');
    const productSelect = wrapper.findAllComponents(ElSelectStub)
      .find((component) => component.attributes('placeholder') === '选择产品');
    expect(productSelect).toBeDefined();
    await productSelect!.find('select').setValue('QA-S301-A');
    const bindingButton = wrapper.findAll('button').find((button) => button.text() === '绑定');
    expect(bindingButton).toBeDefined();
    await bindingButton!.trigger('click');
    await flushPromises();
    expect(harness.post).toHaveBeenCalledWith('/F006/quality-check-items/bindings', expect.objectContaining({
      qualityCheckItemId: 'item-a', productTypeId: 'QA-S301-A', enabled: true,
    }));
    expect(harness.success).toHaveBeenCalledWith('产品标准绑定成功');
    expect(harness.error).not.toHaveBeenCalled();
  });

  it('keeps the mounted dialog open and reports failure when binding save rejects', async () => {
    harness.post.mockRejectedValueOnce(new Error('外厂产品不可绑定'));
    const wrapper = shallowMount(StandardsList, {
      global: { stubs: { ElCard: PassThrough, ElTable: ElTableStub, ElTableColumn: ElTableColumnStub, ElButton: ElButtonStub, ElInput: PassThrough, ElSelect: ElSelectStub, ElOption: PassThrough, ElPagination: PassThrough, ElTag: PassThrough, ElIcon: PassThrough, ElDialog: ElDialogStub, ElForm: PassThrough, ElFormItem: PassThrough, ElInputNumber: PassThrough, ElCheckbox: PassThrough } },
    });
    await flushPromises();
    const openButton = wrapper.findAll('button').find((button) => button.text().includes('绑定产品'));
    expect(openButton).toBeDefined();
    await openButton!.trigger('click');
    const productSelect = wrapper.findAllComponents(ElSelectStub)
      .find((component) => component.attributes('placeholder') === '选择产品');
    expect(productSelect).toBeDefined();
    await productSelect!.find('select').setValue('QA-S301-A');
    const bindingButton = wrapper.findAll('button').find((button) => button.text() === '绑定');
    expect(bindingButton).toBeDefined();
    await bindingButton!.trigger('click');
    await flushPromises();
    expect(harness.error).toHaveBeenCalledWith('外厂产品不可绑定');
    expect(harness.success).not.toHaveBeenCalled();
    expect(wrapper.findAll('button').some((button) => button.text() === '绑定')).toBe(true);
  });
});
