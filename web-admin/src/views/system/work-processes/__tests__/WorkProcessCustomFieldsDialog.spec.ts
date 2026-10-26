import { defineComponent, h, nextTick } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import WorkProcessCustomFieldsDialog from '../WorkProcessCustomFieldsDialog.vue';
import type { ProcessSheetCustomFieldDef, WorkProcessItem } from '@/api/processProduction';

const api = vi.hoisted(() => ({ updateWorkProcess: vi.fn() }));
vi.mock('@/api/processProduction', () => ({ updateWorkProcess: api.updateWorkProcess }));

vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), success: vi.fn() },
}));

const InputStub = defineComponent({
  props: { modelValue: { type: String, default: '' }, disabled: Boolean },
  emits: ['update:modelValue'],
  setup(props, { emit, attrs }) {
    return () => h('input', {
      ...attrs,
      value: props.modelValue,
      disabled: props.disabled,
      onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value),
    });
  },
});

const SelectStub = defineComponent({
  props: { modelValue: { type: String, default: '' }, disabled: Boolean },
  emits: ['update:modelValue'],
  setup(props, { emit, attrs, slots }) {
    return () => h('select', {
      ...attrs,
      value: props.modelValue,
      disabled: props.disabled,
      onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value),
    }, slots.default?.());
  },
});

const SwitchStub = defineComponent({
  props: { modelValue: Boolean, disabled: Boolean },
  emits: ['update:modelValue'],
  setup(props, { emit, attrs }) {
    return () => h('input', {
      ...attrs,
      type: 'checkbox',
      checked: props.modelValue,
      disabled: props.disabled,
      onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).checked),
    });
  },
});

const DialogStub = defineComponent({
  props: { modelValue: Boolean },
  emits: ['update:modelValue'],
  setup(props, { slots }) {
    return () => props.modelValue ? h('section', { 'data-dialog': 'open' }, [
      slots.default?.(), slots.footer?.(),
    ]) : null;
  },
});

const ButtonStub = defineComponent({
  props: { disabled: Boolean },
  setup(props, { slots, attrs }) {
    return () => h('button', { ...attrs, disabled: props.disabled }, slots.default?.());
  },
});

const stubs = {
  'el-dialog': DialogStub,
  'el-input': InputStub,
  'el-select': SelectStub,
  'el-option': defineComponent({
    props: { value: { type: String, default: '' }, label: { type: String, default: '' } },
    setup(props) { return () => h('option', { value: props.value }, props.label); },
  }),
  'el-switch': SwitchStub,
  'el-button': ButtonStub,
  'el-alert': defineComponent({
    props: { title: { type: String, default: '' } },
    setup(props, { slots }) { return () => h('div', props.title || slots.default?.()); },
  }),
};

const processRow: WorkProcessItem = {
  id: 'WP-42', processName: '灌装', processCategory: '包装', estimatedMinutes: 18,
  standardYieldMin: 0.8, standardYieldMax: 1.1, needsInput: true,
  defaultOutputMaterialKind: 'FINISHED_GOOD', semiFinishedOutputCode: 'FG-42',
  standardHourlyRate: 35, isActive: true, createdAt: '2026-01-01', updatedAt: '2026-01-02',
  customFieldSchema: [{ key: 'old_note', label: '已有备注', type: 'text', enabled: true }],
};

function mountDialog(overrides: Partial<InstanceType<typeof WorkProcessCustomFieldsDialog>['$props']> = {}) {
  return mount(WorkProcessCustomFieldsDialog, {
    props: { modelValue: true, factoryId: 'F006', workProcess: processRow, canWrite: true, ...overrides },
    global: { stubs },
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  api.updateWorkProcess.mockResolvedValue({ success: true, data: processRow });
});

describe('WorkProcessCustomFieldsDialog', () => {
  it('saves real text and number input with exact identity and preserved master data', async () => {
    const wrapper = mountDialog();
    await wrapper.find('[data-testid="custom-field-key-0"]').setValue('smartbi_oee_equipment_id');
    await wrapper.find('[data-testid="custom-field-label-0"]').setValue('OEE设备ID');
    await wrapper.find('[data-testid="custom-field-type-0"]').setValue('text');
    await wrapper.find('[data-testid="custom-field-add"]').trigger('click');
    await wrapper.find('[data-testid="custom-field-key-1"]').setValue('planned_minutes');
    await wrapper.find('[data-testid="custom-field-label-1"]').setValue('计划分钟');
    await wrapper.find('[data-testid="custom-field-type-1"]').setValue('number');
    await wrapper.find('[data-testid="custom-field-save"]').trigger('click');
    await flushPromises();

    expect(api.updateWorkProcess).toHaveBeenCalledWith('F006', 'WP-42', {
      processName: '灌装', processCategory: '包装', estimatedMinutes: 18,
      standardYieldMin: 0.8, standardYieldMax: 1.1, needsInput: true,
      defaultOutputMaterialKind: 'FINISHED_GOOD', semiFinishedOutputCode: 'FG-42',
      standardHourlyRate: 35,
      customFieldSchema: [
        { key: 'smartbi_oee_equipment_id', label: 'OEE设备ID', type: 'text', enabled: true },
        { key: 'planned_minutes', label: '计划分钟', type: 'number', enabled: true },
      ],
    });
    expect(wrapper.emitted('saved')?.[0]?.[0]).toEqual([
      { key: 'smartbi_oee_equipment_id', label: 'OEE设备ID', type: 'text', enabled: true },
      { key: 'planned_minutes', label: '计划分钟', type: 'number', enabled: true },
    ]);
    expect(wrapper.emitted('update:modelValue')).toContainEqual([false]);
  });

  it('keeps the form and blocks empty, duplicate, and unknown types', async () => {
    const wrapper = mountDialog({ workProcess: { ...processRow, customFieldSchema: [{ key: '', label: '', type: 'number', enabled: true }] } });
    await wrapper.find('[data-testid="custom-field-save"]').trigger('click');
    expect(api.updateWorkProcess).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="custom-field-key-0"]').element).toHaveProperty('value', '');

    await wrapper.find('[data-testid="custom-field-key-0"]').setValue('same');
    await wrapper.find('[data-testid="custom-field-label-0"]').setValue('一');
    await wrapper.find('[data-testid="custom-field-add"]').trigger('click');
    await wrapper.find('[data-testid="custom-field-key-1"]').setValue('same');
    await wrapper.find('[data-testid="custom-field-label-1"]').setValue('二');
    await wrapper.find('[data-testid="custom-field-save"]').trigger('click');
    expect(api.updateWorkProcess).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="custom-fields-error"]').text()).toContain('不能重复');

    const unknown = mountDialog({ workProcess: { ...processRow, customFieldSchema: [{ key: 'legacy', label: '旧值', type: 'currency' as never, enabled: true }] } });
    await unknown.find('[data-testid="custom-field-save"]').trigger('click');
    expect(api.updateWorkProcess).not.toHaveBeenCalled();
    expect(unknown.find('[data-testid="custom-fields-error"]').text()).toContain('不支持');
  });

  it('keeps the form on API failure and ignores a response after scope switch', async () => {
    api.updateWorkProcess.mockRejectedValueOnce(new Error('failure'));
    const wrapper = mountDialog();
    await wrapper.find('[data-testid="custom-field-key-0"]').setValue('equipment');
    await wrapper.find('[data-testid="custom-field-save"]').trigger('click');
    await flushPromises();
    expect(wrapper.emitted('update:modelValue')).toBeUndefined();
    expect(wrapper.find('[data-testid="custom-field-key-0"]').element).toHaveProperty('value', 'equipment');

    let resolveRequest!: (value: unknown) => void;
    api.updateWorkProcess.mockReturnValueOnce(new Promise(resolve => { resolveRequest = resolve; }));
    await wrapper.find('[data-testid="custom-field-save"]').trigger('click');
    await wrapper.setProps({ factoryId: 'F007', workProcess: { ...processRow, id: 'WP-77' } });
    resolveRequest({ success: true, data: processRow });
    await nextTick();
    await flushPromises();
    expect(wrapper.emitted('saved')).toBeUndefined();
    expect(wrapper.emitted('update:modelValue')).toBeUndefined();
  });

  it('hydrates fresh state when the same object is cancelled and opened again', async () => {
    const wrapper = mountDialog();
    await wrapper.find('[data-testid="custom-field-key-0"]').setValue('unsaved_key');
    const cancel = wrapper.findAll('button').find(button => button.text() === '取消');
    expect(cancel).toBeDefined();
    await cancel!.trigger('click');
    await wrapper.setProps({ modelValue: false });
    await wrapper.setProps({ modelValue: true });
    expect(wrapper.find('[data-testid="custom-field-key-0"]').element).toHaveProperty('value', 'old_note');
  });

  it('does not let a delayed save close or update a newly reopened same-object session', async () => {
    let resolveRequest!: (value: unknown) => void;
    api.updateWorkProcess.mockReturnValueOnce(new Promise(resolve => { resolveRequest = resolve; }));
    const wrapper = mountDialog();
    await wrapper.find('[data-testid="custom-field-key-0"]').setValue('pending_key');
    await wrapper.find('[data-testid="custom-field-save"]').trigger('click');
    await wrapper.setProps({ modelValue: false });
    await wrapper.setProps({ modelValue: true });
    expect(wrapper.find('[data-testid="custom-field-key-0"]').element).toHaveProperty('value', 'old_note');

    resolveRequest({ success: true, data: processRow });
    await nextTick();
    await flushPromises();
    expect(wrapper.props('modelValue')).toBe(true);
    expect(wrapper.emitted('saved')).toBeUndefined();
  });

  it('disables the editor and save action without write permission', () => {
    const wrapper = mountDialog({ canWrite: false });
    expect(wrapper.find('[data-testid="custom-field-key-0"]').attributes('disabled')).toBeDefined();
    expect(wrapper.find('[data-testid="custom-field-save"]').attributes('disabled')).toBeDefined();
  });
});
