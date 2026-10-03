import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CreatePlan from '../create.vue';

const UNAVAILABLE = 'AI 排产暂不可用。当前优化选项无法可靠执行，请使用手动创建。';
const boundary = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(), push: vi.fn(), success: vi.fn(), warning: vi.fn(),
}));

vi.mock('@/api/request', () => ({
  get: boundary.get, post: boundary.post, put: vi.fn(), del: vi.fn(),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F-P05' }) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: boundary.push, back: vi.fn() }) }));
vi.mock('element-plus', () => ({
  ElMessage: { success: boundary.success, warning: boundary.warning }, ElMessageBox: {},
}));

const box = defineComponent({
  setup(_, { slots }) { return () => h('div', [slots.header?.(), slots.default?.()]); },
});
const button = defineComponent({
  props: { loading: Boolean, disabled: Boolean },
  setup(props, { slots }) {
    return () => h('button', { disabled: props.disabled || props.loading }, slots.default?.());
  },
});
const booleanInput = defineComponent({
  props: { modelValue: Boolean }, emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('input', {
      type: 'checkbox', checked: props.modelValue,
      onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).checked),
    });
  },
});
const dateInput = defineComponent({
  props: { modelValue: String }, emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('input', {
      type: 'date', value: props.modelValue,
      onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value),
    });
  },
});
const notesInput = defineComponent({
  props: { modelValue: String }, emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('textarea', {
      value: props.modelValue,
      onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLTextAreaElement).value),
    });
  },
});

interface PageState {
  useAI: boolean;
  loading: boolean;
  handleSubmit: () => Promise<void>;
}

/** Actual Vue page and scheduling.ts adapter execute. Only HTTP/auth/router/message
 * boundaries and Element Plus presentation controls are isolated. Stale/programmatic
 * AI state calls the actual submit function, independently of the disabled card.
 */
describe('AI generation unavailable contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    boundary.get.mockImplementation((url: string) => Promise.resolve({
      success: true,
      data: url.includes('/processing/batches')
        ? { content: [{ id: 901, status: 'PLANNED', batchNumber: 'P05-TEST', plannedQuantity: 10 }] }
        : [],
    }));
    boundary.post.mockResolvedValue({ success: true, data: { id: 'PLAN-P05' } });
  });

  async function page(selectBatch = true) {
    const wrapper = mount(CreatePlan, {
      global: {
        directives: { loading: () => undefined },
        stubs: {
          ElCard: box, ElForm: box, ElFormItem: box, ElIcon: box,
          ElButton: button, ElSwitch: booleanInput, ElDatePicker: dateInput, ElInput: notesInput,
          ElCheckbox: true, ElEmpty: true, ElRadioGroup: true, ElRadioButton: true, ElSlider: true,
        },
      },
    });
    await flushPromises();
    await wrapper.get('input[type="date"]').setValue('2026-10-03');
    if (selectBatch) await wrapper.get('.batch-item').trigger('click');
    return wrapper;
  }

  it('explains unavailability and prevents selecting AI, while manual submit still executes', async () => {
    const wrapper = await page();
    const option = wrapper.findAll('.mode-option')[1];
    expect(option.text()).toContain('AI 排产暂不可用');
    expect(option.text()).toContain('当前优化选项无法可靠执行');
    expect(option.attributes('disabled')).toBeDefined();
    expect(option.attributes('aria-disabled')).toBe('true');
    await option.trigger('click');
    expect((wrapper.vm as unknown as PageState).useAI).toBe(false);
    await wrapper.findAll('.submit-area button')[1].trigger('click');
    await flushPromises();
    expect(boundary.post).toHaveBeenCalledWith('/F-P05/scheduling/plans', expect.objectContaining({ batchIds: [901] }));
    expect(boundary.post).toHaveBeenCalledTimes(1);
    expect(boundary.success).toHaveBeenCalledWith('调度计划创建成功');
  });

  it.each([true, false])('blocks the real submit function for stale AI state, selected batch=%s', async (selected) => {
    const wrapper = await page(selected);
    const state = wrapper.vm as unknown as PageState;
    state.useAI = true;
    await state.handleSubmit();
    await flushPromises();
    // The warning is the positive observation that the submit guard actually ran.
    expect(boundary.warning).toHaveBeenCalledExactlyOnceWith(UNAVAILABLE);
    expect(boundary.post).not.toHaveBeenCalled();
    expect(boundary.success).not.toHaveBeenCalled();
    expect(boundary.push).not.toHaveBeenCalled();
    expect(state.loading).toBe(false);
  });

  it.each([true, false])('preserves the actual manual request and success navigation, autoAssignWorkers=%s', async (autoAssign) => {
    const wrapper = await page();
    await wrapper.get('.manual-options input[type="checkbox"]').setValue(autoAssign);
    await wrapper.get('textarea').setValue('P05 manual contract');
    await wrapper.findAll('.submit-area button')[1].trigger('click');
    await flushPromises();
    expect(boundary.post).toHaveBeenCalledExactlyOnceWith('/F-P05/scheduling/plans', {
      planDate: '2026-10-03', batchIds: [901], autoAssignWorkers: autoAssign, notes: 'P05 manual contract',
    });
    expect(boundary.success).toHaveBeenCalledExactlyOnceWith('调度计划创建成功');
    expect(boundary.push).toHaveBeenCalledExactlyOnceWith('/scheduling/plans/PLAN-P05');
  });

  it('preserves manual empty-batch validation', async () => {
    const wrapper = await page(false);
    await wrapper.findAll('.submit-area button')[1].trigger('click');
    await flushPromises();
    expect(boundary.warning).toHaveBeenCalledExactlyOnceWith('请选择至少一个生产批次');
    expect(boundary.post).not.toHaveBeenCalled();
    expect(boundary.success).not.toHaveBeenCalled();
  });

  it('preserves manual failure without success toast or navigation', async () => {
    const error = new Error('manual contract rejection');
    boundary.post.mockRejectedValue(error);
    const output = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    try {
      const wrapper = await page();
      await wrapper.findAll('.submit-area button')[1].trigger('click');
      await flushPromises();
      expect(boundary.post).toHaveBeenCalledTimes(1);
      expect(output).toHaveBeenCalledWith('创建失败:', error);
      expect(boundary.success).not.toHaveBeenCalled();
      expect(boundary.push).not.toHaveBeenCalled();
      expect((wrapper.vm as unknown as PageState).loading).toBe(false);
    } finally {
      output.mockRestore();
    }
  });
});
