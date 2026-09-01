/**
 * 缺陷43 (owner 2026-09-02 演示走查, 同一屏「确认领料」): 调拨确认框 11 行里 8 行「× 0kg」
 * 占了 73% 篇幅, 真正要看的 3 行反而要滚动才看得到。
 *
 * 修法: 非零行照常逐行列出、排最前; 零值行折进一条可展开摘要。⛔ 不删除零值行 ——
 * 0 是明确的事实(#469: 0=这次不领, 审计要留痕), 只是不该占主视野。
 *
 * 判据走真实入口 handleTransfer() → ElMessageBox.confirm 收到的 HTML, 不是直接摸私有函数
 * (那样测不出「产出端有没有接到消费端」, 见 measurement-and-wiring 形态 B)。
 */
import { flushPromises, shallowMount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  requestGet: vi.fn(),
  confirm: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
}));

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));
vi.mock('@/api/factoryWarehouse', () => ({
  listWarehouses: vi.fn().mockResolvedValue({ success: true, data: [] }),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => harness.get(...args),
  post: (...args: unknown[]) => harness.post(...args),
  put: (...args: unknown[]) => harness.put(...args),
  default: { get: (...args: unknown[]) => harness.requestGet(...args) },
}));
vi.mock('element-plus', () => ({
  ElMessage: {
    success: (...a: unknown[]) => harness.success(...a),
    warning: (...a: unknown[]) => harness.warning(...a),
    error: (...a: unknown[]) => harness.error(...a),
    info: vi.fn(),
  },
  ElMessageBox: { confirm: (...a: unknown[]) => harness.confirm(...a) },
}));

import RequisitionList from '../list.vue';

interface Vm {
  handleTransfer: (row: { id: string }) => Promise<void>;
}

const ROWS_KEY = 'stub-table-rows';
const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide(ROWS_KEY, computed(() => props.data));
    return () => h('div', slots.default?.());
  },
});
const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  setup(_props, { slots }) {
    const rows = inject<ComputedRef<unknown[]>>(ROWS_KEY, computed(() => []));
    return () => h('div', rows.value.map((row) => h('div', slots.default?.({ row }))));
  },
});
const PassThrough = (name: string) => defineComponent({
  name,
  setup(_props, { slots }) {
    return () => h('div', slots.default?.());
  },
});

function mountList() {
  return shallowMount(RequisitionList, {
    global: {
      stubs: {
        ElTable: ElTableStub,
        ElTableColumn: ElTableColumnStub,
        ElButton: PassThrough('ElButton'),
        ElDialog: PassThrough('ElDialog'),
        ElForm: PassThrough('ElForm'),
        ElFormItem: PassThrough('ElFormItem'),
        ElDescriptions: PassThrough('ElDescriptions'),
        ElDescriptionsItem: PassThrough('ElDescriptionsItem'),
        ElInput: PassThrough('ElInput'),
        ElInputNumber: PassThrough('ElInputNumber'),
        ElAlert: PassThrough('ElAlert'),
        ElSelect: PassThrough('ElSelect'),
        ElOption: PassThrough('ElOption'),
        ElPagination: PassThrough('ElPagination'),
        ElCard: PassThrough('ElCard'),
        ElTag: PassThrough('ElTag'),
      },
    },
  });
}

// 11 行: 3 味有实际拣货量, 8 味 × 0kg (本次不领) —— 与 brief 描述的现场配比一致。
const ITEMS = [
  ...Array.from({ length: 3 }, (_, i) => ({
    id: `NZ-${i}`, materialName: `原料${i}`, materialTypeId: `MT-NZ-${i}`, pickedQty: i + 1, unit: 'kg',
  })),
  ...Array.from({ length: 8 }, (_, i) => ({
    id: `Z-${i}`, materialName: `辅料${i}`, materialTypeId: `MT-Z-${i}`, pickedQty: 0, unit: 'kg',
  })),
];

const DETAIL = {
  id: 'MR-1',
  requisitionNo: 'MR-20260902-0043',
  sourceWarehouseId: null,
  targetWarehouseId: null,
  items: ITEMS,
};

describe('缺陷43: 调拨确认框把零值行折叠, 非零行照常可见', () => {
  beforeEach(() => {
    Object.values(harness).forEach((fn) => fn.mockReset());
    harness.confirm.mockResolvedValue('confirm');
    harness.put.mockResolvedValue({ success: true });
    harness.get.mockImplementation(async (url: string) => {
      if (url === '/F006/material-requisitions/MR-1') return { success: true, data: DETAIL };
      return { success: true, data: { content: [], totalElements: 0 } };
    });
  });

  it('①🔴 3 个非零行原样列出且排在折叠摘要之前, 8 个零值行折进一条摘要(数字对), 不删除', async () => {
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    await vm.handleTransfer({ id: 'MR-1' });
    await flushPromises();

    expect(harness.confirm, '真实入口没有调到 ElMessageBox.confirm —— 后面的断言都摸不到产出').toHaveBeenCalledTimes(1);
    const html = String(harness.confirm.mock.calls[0][0]);

    for (let i = 0; i < 3; i++) {
      expect(html, `非零行「原料${i}」不见了`).toContain(`原料${i}`);
    }
    expect(html, '折叠摘要的计数不对').toContain('另有 8 项本次不领');
    expect(html, '折叠交互必须是原生 <details> (这段 HTML 没有 Vue 事件绑定)').toContain('<details');

    // 3 个非零行必须排在 <details> 之前 —— 折叠收起时不需要滚动就能看到
    const detailsAt = html.indexOf('<details');
    for (let i = 0; i < 3; i++) {
      expect(html.indexOf(`原料${i}`), `「原料${i}」排在折叠区之后 —— 收起状态下看不到`).toBeLessThan(detailsAt);
    }

    // ⛔ 阴性对照: 零值行不是被删掉了, 只是被折叠 —— 8 个名字必须仍然在 HTML 里(在 details 内)
    for (let i = 0; i < 8; i++) {
      expect(html, `零值行「辅料${i}」被删除了, 不该发生(0 是审计事实要留痕)`).toContain(`辅料${i}`);
    }

    wrapper.unmount();
  });

  it('② 阳性对照: 全部非零(没有零值行)时不出现折叠摘要, 老行为不受影响', async () => {
    const allNonZero = {
      ...DETAIL,
      id: 'MR-2',
      items: ITEMS.slice(0, 3),
    };
    harness.get.mockImplementation(async (url: string) => {
      if (url === '/F006/material-requisitions/MR-2') return { success: true, data: allNonZero };
      return { success: true, data: { content: [], totalElements: 0 } };
    });

    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    await vm.handleTransfer({ id: 'MR-2' });
    await flushPromises();

    const html = String(harness.confirm.mock.calls[0][0]);
    expect(html).not.toContain('<details');
    expect(html).not.toContain('另有');

    wrapper.unmount();
  });
});
