import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CreatePlan from '../create.vue';

const boundary = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), push: vi.fn(), success: vi.fn(), warning: vi.fn() }));
vi.mock('@/api/request', () => ({ get: boundary.get, post: boundary.post, put: vi.fn(), del: vi.fn() }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F-P05' }) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: boundary.push, back: vi.fn() }) }));
vi.mock('element-plus', () => ({ ElMessage: { success: boundary.success, warning: boundary.warning }, ElMessageBox: {} }));

const box = defineComponent({ setup(_, { slots }) { return () => h('div', [slots.header?.(), slots.default?.()]); } });
const button = defineComponent({ props: { loading: Boolean, disabled: Boolean }, setup(props, { slots }) { return () => h('button', { disabled: props.disabled || props.loading }, slots.default?.()); } });
const dateInput = defineComponent({ props: { modelValue: String }, emits: ['update:modelValue'], setup(props, { emit }) { return () => h('input', { type: 'date', value: props.modelValue, onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value) }); } });
const notesInput = defineComponent({ props: { modelValue: String }, emits: ['update:modelValue'], setup(props, { emit }) { return () => h('textarea', { value: props.modelValue, onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLTextAreaElement).value) }); } });

const capabilities = {
  contractVersion: 'scheduling-capabilities/v1',
  aiGeneration: { status: 'UNAVAILABLE', mode: 'UNAVAILABLE', reason: 'AI自动排程暂不可用；请使用手动创建。' },
  workerOptimization: { status: 'UNAVAILABLE', mode: 'UNAVAILABLE', reason: '自动人员优化暂不可用，请手动指派。' },
  reschedule: { status: 'AVAILABLE', mode: 'RECORD_REASON_ONLY', reason: '当前仅记录调整原因，不修改时间或资源。' },
  manualCreation: { status: 'AVAILABLE', mode: 'EXPLICIT_SCHEDULES', reason: '只保存用户明确提供的信息。' },
};

describe('scheduling create capability and manual payload contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    boundary.get.mockImplementation((url: string) => Promise.resolve({ success: true, data: url.includes('/processing/batches') ? { content: [{ id: 901, status: 'PLANNED', batchNumber: 'P05-TEST', plannedQuantity: 10, unit: 'kg' }] } : url.includes('/production-lines') ? [{ id: 'LINE-1', name: '一号线' }] : capabilities }));
    boundary.post.mockResolvedValue({ success: true, data: { id: 'PLAN-P05' } });
  });

  async function page(selectBatch = true) {
    const wrapper = mount(CreatePlan, { global: { directives: { loading: () => undefined }, stubs: { ElCard: box, ElForm: box, ElFormItem: box, ElIcon: box, ElButton: button, ElDatePicker: dateInput, ElInput: notesInput, ElCheckbox: true, ElEmpty: true } } });
    await flushPromises();
    await wrapper.get('input[type="date"]').setValue('2026-10-03');
    if (selectBatch) await wrapper.get('.batch-item').trigger('click');
    return wrapper;
  }

  it('renders server-owned AI unavailability and prevents stale AI submit', async () => {
    const wrapper = await page(false);
    expect(wrapper.find('#ai-generation-unavailable-reason').text()).toContain('AI自动排程暂不可用');
    const state = wrapper.vm as unknown as { useAI: boolean; handleSubmit: () => Promise<void> };
    state.useAI = true;
    await state.handleSubmit();
    expect(boundary.warning).toHaveBeenCalledWith('AI自动排程暂不可用；请使用手动创建。');
    expect(boundary.post).not.toHaveBeenCalled();
  });

  it('keeps automation unavailable and explains the recovery path when capabilities fail', async () => {
    boundary.get.mockImplementation((url: string) => {
      if (url.includes('/scheduling/capabilities')) return Promise.reject(new Error('capability unavailable'));
      return Promise.resolve({ success: true, data: url.includes('/processing/batches') ? { content: [] } : [] });
    });
    const output = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    try {
      const wrapper = await page(false);
      expect(wrapper.find('#ai-generation-unavailable-reason').text()).toContain('能力状态加载失败');
      expect(wrapper.find('.capability-notice').text()).toContain('使用人工确认的排程草稿');
    } finally {
      output.mockRestore();
    }
  });

  it('sends explicit schedules and never sends ignored batchIds or autoAssignWorkers', async () => {
    const wrapper = await page();
    expect(wrapper.find('.qty-label').text()).toContain('kg');
    expect(wrapper.find('.manual-schedule-context').text()).toContain('kg');
    await wrapper.get('select').setValue('LINE-1');
    await wrapper.find('input[type="number"]').setValue('10');
    await wrapper.findAll('input[type="datetime-local"]')[0].setValue('2026-10-03T08:00');
    await wrapper.findAll('input[type="datetime-local"]')[1].setValue('2026-10-03T09:00');
    await wrapper.findAll('.submit-area button')[1].trigger('click');
    await flushPromises();
    expect(boundary.post).toHaveBeenCalledExactlyOnceWith('/F-P05/scheduling/plans', { planDate: '2026-10-03', schedules: [{ productionLineId: 'LINE-1', batchId: 901, sequenceOrder: 1, plannedStartTime: '2026-10-03T08:00:00', plannedEndTime: '2026-10-03T09:00:00', plannedQuantity: 10 }] });
    expect(boundary.post.mock.calls[0][1]).not.toHaveProperty('batchIds');
    expect(boundary.post.mock.calls[0][1]).not.toHaveProperty('autoAssignWorkers');
    expect(boundary.success).toHaveBeenCalledWith('人工确认的排程草稿已创建');
  });

  it.each([
    ['no line', async (wrapper: any) => { await wrapper.findAll('input[type="datetime-local"]')[0].setValue('2026-10-03T08:00'); await wrapper.findAll('input[type="datetime-local"]')[1].setValue('2026-10-03T09:00'); }],
    ['fractional quantity', async (wrapper: any) => { await wrapper.get('select').setValue('LINE-1'); await wrapper.find('input[type="number"]').setValue('1.5'); }],
    ['end before start', async (wrapper: any) => { await wrapper.get('select').setValue('LINE-1'); await wrapper.findAll('input[type="datetime-local"]')[0].setValue('2026-10-03T09:00'); await wrapper.findAll('input[type="datetime-local"]')[1].setValue('2026-10-03T08:00'); }],
  ])('blocks manual submission for %s', async (_label, fill) => {
    const wrapper = await page();
    await fill(wrapper);
    await wrapper.findAll('.submit-area button')[1].trigger('click');
    await flushPromises();
    expect(boundary.post).not.toHaveBeenCalled();
    expect(boundary.warning).toHaveBeenCalledTimes(1);
  });
});
