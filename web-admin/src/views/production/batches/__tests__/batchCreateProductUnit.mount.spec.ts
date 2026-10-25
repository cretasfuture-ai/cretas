import { describe, it, expect, beforeEach, vi } from 'vitest';
import { defineComponent } from 'vue';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

const apiGet = vi.fn();
const apiPost = vi.fn();

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canViewPrice: true }),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => apiGet(...args),
  post: (...args: unknown[]) => apiPost(...args),
}));
vi.mock('@/api/printApi', () => ({ safePrint: vi.fn() }));

import BatchesList from '../list.vue';

const SelectStub = defineComponent({
  props: { modelValue: { type: [String, Number], default: '' } },
  emits: ['update:modelValue', 'change'],
  template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\', $event.target.value)"><slot /></select>',
});

const OptionStub = defineComponent({
  props: { label: { type: String, default: '' }, value: { type: [String, Number], default: '' } },
  template: '<option :value="value">{{ label }}</option>',
});

const InputNumberStub = defineComponent({
  props: { modelValue: { type: [Number, String], default: null } },
  emits: ['update:modelValue'],
  template: '<input type="number" :value="modelValue == null ? \'\' : modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />',
});

const DialogStub = defineComponent({
  props: { modelValue: { type: Boolean, default: false } },
  template: '<div v-if="modelValue" role="dialog"><slot /><slot name="footer" /></div>',
});

function mountList() {
  return mount(BatchesList, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        teleport: true,
        transition: false,
        ConceptDisambiguationAlert: true,
        AiEntryDrawer: true,
        RowActionMenu: true,
        'el-select': SelectStub,
        'el-option': OptionStub,
        'el-input-number': InputNumberStub,
        'el-dialog': DialogStub,
      },
    },
  });
}

describe('生产批次创建 — 产品声明单位进入可选项并随选择默认', () => {
  beforeEach(() => {
    apiGet.mockReset();
    apiPost.mockReset();
    apiGet.mockImplementation(async (path: string) => {
      if (path.endsWith('/product-types/active')) {
        return {
          success: true,
          data: [
            { id: 'PRODUCT-BOX', name: 'QA 盒装产品', unit: '盒' },
            { id: 'PRODUCT-KG', name: 'QA 重量产品', unit: 'kg' },
          ],
        };
      }
      return { success: true, data: { content: [], totalElements: 0 } };
    });
    apiPost.mockResolvedValue({ success: true, data: { id: 'BATCH-NEW' } });
  });

  it('真实选择产品与单位后提交对应单位和数量，并支持切换产品', async () => {
    const wrapper = mountList();
    await flushPromises();

    const createButton = wrapper.findAll('button').find((button) => button.text().trim() === '创建批次');
    expect(createButton).toBeDefined();
    await createButton!.trigger('click');
    await flushPromises();

    const selects = wrapper.findAll('select');
    const productSelect = selects.find((select) => select.text().includes('QA 盒装产品'));
    expect(productSelect).toBeDefined();
    await productSelect!.setValue('PRODUCT-BOX');
    await flushPromises();
    const unitSelect = wrapper.findAll('select').find((select) => select.find('option[value="盒"]').exists());
    expect(unitSelect).toBeDefined();
    expect(unitSelect!.text()).toContain('kg');
    expect((unitSelect!.element as HTMLSelectElement).value).toBe('盒');

    const quantityInput = wrapper.find('[role="dialog"] input[type="number"]');
    await quantityInput.setValue('2');

    await productSelect!.setValue('PRODUCT-KG');
    await flushPromises();
    expect((unitSelect!.element as HTMLSelectElement).value).toBe('kg');

    await productSelect!.setValue('PRODUCT-BOX');
    await flushPromises();
    expect((unitSelect!.element as HTMLSelectElement).value).toBe('盒');
    await quantityInput.setValue('3');

    const submitButton = wrapper.findAll('button').find((button) => button.text().trim() === '创建');
    expect(submitButton).toBeDefined();
    await submitButton!.trigger('click');
    await flushPromises();

    expect(apiPost).toHaveBeenCalledWith('/F006/processing/batches', expect.objectContaining({
      productTypeId: 'PRODUCT-BOX',
      plannedQuantity: 3,
      quantity: 3,
      unit: '盒',
    }));
    wrapper.unmount();
  });
});
