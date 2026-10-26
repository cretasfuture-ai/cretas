import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  prompt: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => harness.get(...args),
  post: (...args: unknown[]) => harness.post(...args),
  put: (...args: unknown[]) => harness.put(...args),
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
      prompt: (...args: unknown[]) => harness.prompt(...args),
      confirm: vi.fn().mockResolvedValue('confirm'),
    },
  };
});

import StocktakePage from '../index.vue';

const ROWS_KEY = 'stocktake-test-rows';

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
  props: { loading: { type: Boolean, default: false }, disabled: Boolean },
  emits: ['click'],
  setup(props, { slots, emit }) {
    return () => h('button', {
      disabled: props.disabled,
      onClick: () => emit('click'),
    }, slots.default?.());
  },
});

const ElAlertStub = defineComponent({
  props: { title: String, description: String },
  setup(props, { slots }) {
    return () => h('div', [props.title, props.description, slots.default?.()]);
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
  ElCard: PassThrough('ElCard'),
  ElTag: PassThrough('ElTag'),
  ElSelect: PassThrough('ElSelect'),
  ElOption: PassThrough('ElOption'),
  ElPagination: PassThrough('ElPagination'),
  ElDialog: PassThrough('ElDialog'),
  ElForm: PassThrough('ElForm'),
  ElFormItem: PassThrough('ElFormItem'),
  ElDescriptions: PassThrough('ElDescriptions'),
  ElDescriptionsItem: PassThrough('ElDescriptionsItem'),
  ElInput: PassThrough('ElInput'),
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
};

const approvedRow = {
  id: 'ST-APPROVED',
  stocktakeNo: 'ST-202610-1',
  status: 'APPROVED',
  version: 7,
  warehouseId: 'WH-1',
  periodMonth: '2026-10',
  initiatedAt: '2026-10-23T10:00:00',
};
const approvedDetail = { ...approvedRow, approvedBy: 7000 };
const childDetail = {
  id: 'ST-RECOUNT',
  stocktakeNo: 'ST-202610-2',
  status: 'INITIATED',
  version: 0,
  warehouseId: 'WH-1',
  periodMonth: '2026-10',
};
const archivedRow = {
  ...approvedRow,
  id: 'ST-ARCHIVED',
  status: 'RECOUNT_REQUIRED',
  recountReason: '同仓调拨后重盘',
  recountStocktakeId: 'ST-RECOUNT',
};

function mountPage() {
  return mount(StocktakePage, { global: { stubs } });
}

describe('盘点漂移后重盘真实挂载链', () => {
  beforeEach(() => {
    Object.values(harness).forEach((fn) => fn.mockReset());
    harness.prompt.mockResolvedValue({ value: '同仓调拨后重盘' });
    harness.get.mockImplementation((url: string) => {
      if (url.endsWith('/stocktakes')) {
        return Promise.resolve({ success: true, data: { content: [approvedRow], totalElements: 1 } });
      }
      if (url.endsWith('/pending-defect-counts')) {
        return Promise.resolve({ success: true, data: [] });
      }
      if (url.endsWith('/factory/warehouses')) {
        return Promise.resolve({ success: true, data: [] });
      }
      if (url.endsWith('/initiate-constraint')) {
        return Promise.resolve({ success: true, data: { canInitiateToday: true } });
      }
      if (url.endsWith('/ST-RECOUNT')) {
        return Promise.resolve({ success: true, data: childDetail });
      }
      if (url.endsWith('/ST-APPROVED')) {
        return Promise.resolve({ success: true, data: approvedDetail });
      }
      return Promise.resolve({ success: true, data: {} });
    });
    harness.post.mockResolvedValue({ success: true, data: childDetail });
  });

  it('clicks approved row, submits versioned reason, and shows the new task', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const action = wrapper.findAll('button').find((button) => button.text() === '漂移后重盘');
    expect(action, '已审批行未渲染漂移后重盘操作').toBeTruthy();

    await action!.trigger('click');
    await flushPromises();

    expect(harness.prompt).toHaveBeenCalledTimes(1);
    expect(harness.post).toHaveBeenCalledWith(
      '/F006/stocktakes/ST-APPROVED/recount-after-drift',
      { expectedVersion: 7, reason: '同仓调拨后重盘' },
    );
    expect(harness.success).toHaveBeenCalledWith('已保留原审批证据并发起新的盘点任务');
    expect(wrapper.text()).toContain('ST-202610-2');
    expect(harness.post.mock.calls.some(([url]) => String(url).includes('/apply'))).toBe(false);
    wrapper.unmount();
  });

  it('stops before prompt or post when detail loading fails and shows the error', async () => {
    harness.get.mockImplementation((url: string) => {
      if (url.endsWith('/stocktakes')) {
        return Promise.resolve({ success: true, data: { content: [approvedRow], totalElements: 1 } });
      }
      if (url.endsWith('/pending-defect-counts') || url.endsWith('/factory/warehouses')
          || url.endsWith('/initiate-constraint')) {
        return Promise.resolve({ success: true, data: [] });
      }
      if (url.endsWith('/ST-APPROVED')) {
        return Promise.resolve({ success: false, message: '详情版本读取失败' });
      }
      return Promise.resolve({ success: true, data: {} });
    });
    const wrapper = mountPage();
    await flushPromises();
    const action = wrapper.findAll('button').find((button) => button.text() === '漂移后重盘');
    await action!.trigger('click');
    await flushPromises();

    expect(harness.error).toHaveBeenCalledWith(expect.objectContaining({ message: '详情版本读取失败' }));
    expect(harness.prompt).not.toHaveBeenCalled();
    expect(harness.post).not.toHaveBeenCalledWith(
      '/F006/stocktakes/ST-APPROVED/recount-after-drift', expect.anything(),
    );
    expect(harness.success).not.toHaveBeenCalled();
    expect(wrapper.text()).not.toContain('ST-202610-2');
    wrapper.unmount();
  });

  it('shows an archived approved snapshot as read-only and hides apply', async () => {
    harness.get.mockImplementation((url: string) => {
      if (url.endsWith('/stocktakes')) {
        return Promise.resolve({ success: true, data: { content: [archivedRow], totalElements: 1 } });
      }
      if (url.endsWith('/pending-defect-counts') || url.endsWith('/factory/warehouses')
          || url.endsWith('/initiate-constraint')) {
        return Promise.resolve({ success: true, data: [] });
      }
      if (url.endsWith('/ST-ARCHIVED')) {
        return Promise.resolve({ success: true, data: archivedRow });
      }
      if (url.endsWith('/diff-preview')) {
        return Promise.resolve({
          success: true,
          data: {
            diffLines: [{
              batchNumber: 'B-ARCHIVED',
              systemQty: 10,
              actualQty: 11,
              differenceQty: 1,
              quantityUnit: 'kg',
              differenceType: 'SURPLUS',
            }],
          },
        });
      }
      return Promise.resolve({ success: true, data: childDetail });
    });
    const wrapper = mountPage();
    await flushPromises();
    const view = wrapper.findAll('button').find((button) => button.text() === '查看');
    expect(view, '归档盘点未渲染查看入口').toBeTruthy();
    await view!.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('该盘点已归档为需重新盘点');
    expect(wrapper.text()).toContain('原盘点快照、实盘数量和审批证据仅供历史追溯');
    expect(wrapper.text()).toContain('B-ARCHIVED');
    expect(wrapper.text()).toContain('10kg');
    expect(wrapper.text()).toContain('11kg');
    expect(wrapper.text()).toContain('+1kg');
    expect(wrapper.findAll('button').some((button) => button.text() === '应用差异')).toBe(false);
    wrapper.unmount();
  });

  it('shows diff preview failure and does not claim zero difference or apply', async () => {
    harness.get.mockImplementation((url: string) => {
      if (url.endsWith('/stocktakes')) {
        return Promise.resolve({ success: true, data: { content: [archivedRow], totalElements: 1 } });
      }
      if (url.endsWith('/pending-defect-counts') || url.endsWith('/factory/warehouses')
          || url.endsWith('/initiate-constraint')) {
        return Promise.resolve({ success: true, data: [] });
      }
      if (url.endsWith('/ST-ARCHIVED')) {
        return Promise.resolve({ success: true, data: archivedRow });
      }
      if (url.endsWith('/diff-preview')) {
        return Promise.resolve({ success: false, message: '差异预览服务暂不可用' });
      }
      return Promise.resolve({ success: true, data: childDetail });
    });
    const wrapper = mountPage();
    await flushPromises();
    const view = wrapper.findAll('button').find((button) => button.text() === '查看');
    await view!.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('差异预览服务暂不可用');
    expect(wrapper.text()).not.toContain('无实际差异，无库存影响');
    expect(wrapper.findAll('button').some((button) => button.text() === '应用差异')).toBe(false);
    wrapper.unmount();
  });
});
