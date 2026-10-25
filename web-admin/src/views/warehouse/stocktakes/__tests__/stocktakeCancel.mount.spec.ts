import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006', user: { id: 7001 } }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => harness.get(...args),
  post: (...args: unknown[]) => harness.post(...args),
  put: vi.fn(),
  default: { get: (...args: unknown[]) => harness.get(...args) },
}));
vi.mock('element-plus', () => {
  const ElMessage = Object.assign(
    (...args: unknown[]) => harness.error(...args),
    {
      success: (...args: unknown[]) => harness.success(...args),
      error: (...args: unknown[]) => harness.error(...args),
      warning: vi.fn(),
      info: vi.fn(),
    },
  );
  return {
    ElMessage,
    ElMessageBox: {
      prompt: vi.fn(),
      confirm: vi.fn().mockResolvedValue('confirm'),
    },
  };
});

import StocktakePage from '../index.vue';

const ROWS_KEY = 'stocktake-cancel-test-rows';

const ElTableStub = defineComponent({
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide(ROWS_KEY, computed(() => props.data));
    return () => h('div', { class: 'stocktake-table' }, slots.default?.());
  },
});

const ElTableColumnStub = defineComponent({
  setup(_props, { slots }) {
    const rows = inject(ROWS_KEY, computed<unknown[]>(() => []));
    return () => h('div', rows.value.map((row) => slots.default?.({ row })));
  },
});

const ElButtonStub = defineComponent({
  props: { loading: Boolean, disabled: Boolean },
  emits: ['click'],
  setup(props, { slots, emit }) {
    return () => h('button', {
      disabled: props.disabled || props.loading,
      onClick: () => emit('click'),
    }, slots.default?.());
  },
});

const ElDialogStub = defineComponent({
  props: { modelValue: Boolean },
  setup(props, { slots }) {
    return () => props.modelValue ? h('section', [slots.default?.(), slots.footer?.()]) : null;
  },
});

const ElSelectStub = defineComponent({
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  setup(props, { slots, emit }) {
    return () => h('select', {
      value: props.modelValue,
      onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value),
    }, slots.default?.());
  },
});

const ElOptionStub = defineComponent({
  props: { label: String, value: { type: String, default: '' } },
  setup(props) {
    return () => h('option', { value: props.value }, props.label);
  },
});

const ElInputStub = defineComponent({
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('textarea', {
      value: props.modelValue,
      onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLTextAreaElement).value),
    });
  },
});

const ElAlertStub = defineComponent({
  props: { title: String, description: String },
  setup(props, { slots }) {
    return () => h('div', [props.title, props.description, slots.default?.()]);
  },
});

const ElDescriptionsItemStub = defineComponent({
  props: { label: String },
  setup(props, { slots }) {
    return () => h('div', [props.label, slots.default?.()]);
  },
});

const PassThrough = (name: string) => defineComponent({
  name,
  setup(_props, { slots }) {
    return () => h('div', slots.default?.());
  },
});

const stubs = {
  ElTable: ElTableStub,
  ElTableColumn: ElTableColumnStub,
  ElButton: ElButtonStub,
  ElDialog: ElDialogStub,
  ElSelect: ElSelectStub,
  ElOption: ElOptionStub,
  ElInput: ElInputStub,
  ElCard: PassThrough('ElCard'),
  ElTag: PassThrough('ElTag'),
  ElPagination: PassThrough('ElPagination'),
  ElForm: PassThrough('ElForm'),
  ElFormItem: PassThrough('ElFormItem'),
  ElDescriptions: PassThrough('ElDescriptions'),
  ElDescriptionsItem: ElDescriptionsItemStub,
  ElInputNumber: PassThrough('ElInputNumber'),
  ElAlert: ElAlertStub,
  ElLink: ElButtonStub,
  ElText: PassThrough('ElText'),
  ElCollapse: PassThrough('ElCollapse'),
  ElCollapseItem: PassThrough('ElCollapseItem'),
  ElRadioGroup: PassThrough('ElRadioGroup'),
  ElRadio: PassThrough('ElRadio'),
  ElDatePicker: PassThrough('ElDatePicker'),
  ElUpload: PassThrough('ElUpload'),
  ElTooltip: PassThrough('ElTooltip'),
};

const ownerRow = {
  id: 'ST-COUNTING',
  stocktakeNo: 'ST-202610-CANCEL',
  status: 'COUNTING',
  version: 4,
  initiatedBy: 7001,
  warehouseId: 'WH-1',
  periodMonth: '2026-10',
  initiatedAt: '2026-10-23T10:00:00',
};
const otherOwnerRow = { ...ownerRow, id: 'ST-OTHER', initiatedBy: 8002 };
const missingInitiatorRow = { ...ownerRow, id: 'ST-MISSING-INITIATOR', initiatedBy: undefined };
const cancelledRow = {
  ...ownerRow,
  id: 'ST-CANCELLED',
  status: 'CANCELLED',
  cancelledBy: 7001,
  cancelledAt: '2026-10-24T09:30:00',
  cancelReason: '盘点月份有误',
};

let listRows = [ownerRow, otherOwnerRow, missingInitiatorRow];

function mountPage() {
  return mount(StocktakePage, { global: { stubs } });
}

describe('盘点任务取消真实挂载链', () => {
  beforeEach(() => {
    Object.values(harness).forEach((fn) => fn.mockReset());
    listRows = [ownerRow, otherOwnerRow, missingInitiatorRow];
    harness.get.mockImplementation((url: string) => {
      if (url.endsWith('/stocktakes')) {
        return Promise.resolve({ success: true, data: { content: listRows, totalElements: listRows.length } });
      }
      if (url.endsWith('/pending-defect-counts') || url.endsWith('/factory/warehouses')
          || url.endsWith('/initiate-constraint')) {
        return Promise.resolve({ success: true, data: [] });
      }
      if (url.endsWith('/ST-CANCELLED')) {
        return Promise.resolve({ success: true, data: cancelledRow });
      }
      return Promise.resolve({ success: true, data: {} });
    });
  });

  it('only lets the maker cancel, sends reason and expected version once, then refreshes CANCELLED', async () => {
    let resolvePost: ((value: unknown) => void) | undefined;
    harness.post.mockImplementation(() => new Promise((resolve) => { resolvePost = resolve; }));
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.findAll('button').filter((button) => button.text() === '取消盘点')).toHaveLength(1);
    await wrapper.findAll('button').find((button) => button.text() === '取消盘点')!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('库存不会发生变化');

    await wrapper.findAll('select').at(-1)!.setValue('WRONG_PERIOD');
    const confirm = wrapper.findAll('button').find((button) => button.text() === '确认取消');
    expect(confirm).toBeTruthy();
    await confirm!.trigger('click');
    await confirm!.trigger('click');
    expect(harness.post).toHaveBeenCalledTimes(1);
    expect(harness.post).toHaveBeenCalledWith(
      '/F006/stocktakes/ST-COUNTING/cancel',
      { reason: '盘点月份有误', expectedVersion: 4 },
    );

    listRows = [cancelledRow, otherOwnerRow];
    resolvePost?.({ success: true, data: cancelledRow });
    await flushPromises();
    expect(harness.success).toHaveBeenCalledWith('盘点任务已取消，库存未发生变化');
    expect(wrapper.findAll('button').filter((button) => button.text() === '取消盘点')).toHaveLength(0);
    wrapper.unmount();
  });

  it('keeps the reason visible and shows the actual server error after failure', async () => {
    harness.post.mockRejectedValue(new Error('后端拒绝取消：已有提交记录'));
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.findAll('button').find((button) => button.text() === '取消盘点')!.trigger('click');
    await flushPromises();
    await wrapper.findAll('select').at(-1)!.setValue('OTHER');
    await wrapper.find('textarea').setValue('需要保留的现场原因');
    await wrapper.findAll('button').find((button) => button.text() === '确认取消')!.trigger('click');
    await flushPromises();

    expect(wrapper.find('textarea').element.value).toBe('需要保留的现场原因');
    expect(wrapper.text()).toContain('后端拒绝取消：已有提交记录');
    expect(harness.error).toHaveBeenCalledWith(expect.objectContaining({
      message: '后端拒绝取消：已有提交记录',
    }));
    expect(harness.post).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('rejects a truthful non-CANCELLED response even when the server message says success', async () => {
    harness.post.mockResolvedValue({
      success: true,
      message: '操作成功',
      data: { ...ownerRow, status: 'COUNTING' },
    });
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.findAll('button').find((button) => button.text() === '取消盘点')!.trigger('click');
    await flushPromises();
    await wrapper.findAll('select').at(-1)!.setValue('WRONG_PERIOD');
    await wrapper.findAll('button').find((button) => button.text() === '确认取消')!.trigger('click');
    await flushPromises();

    expect(harness.success).not.toHaveBeenCalled();
    expect(harness.error).toHaveBeenCalledWith(expect.objectContaining({
      message: '取消未完成：服务器没有返回已取消状态，请保持窗口打开并重试。',
    }));
    expect(wrapper.text()).toContain('取消未完成：服务器没有返回已取消状态');
    expect(wrapper.findAll('button').some((button) => button.text() === '确认取消')).toBe(true);
    expect(wrapper.findAll('select').at(-1)!.element.value).toBe('WRONG_PERIOD');
    expect(harness.post).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('renders CANCELLED as audit-only and hides count, submit, apply and cancel actions', async () => {
    listRows = [cancelledRow];
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.text()).toContain('已取消');
    expect(wrapper.findAll('button').some((button) => button.text() === '录入')).toBe(false);
    expect(wrapper.findAll('button').some((button) => button.text() === '提交审批')).toBe(false);
    expect(wrapper.findAll('button').some((button) => button.text() === '应用差异')).toBe(false);
    await wrapper.findAll('button').find((button) => button.text() === '查看')!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('盘点月份有误');
    expect(wrapper.text()).toContain('取消人');
    expect(wrapper.text()).toContain('取消时间');
    expect(wrapper.findAll('button').some((button) => button.text() === '取消盘点')).toBe(false);
    wrapper.unmount();
  });
});
