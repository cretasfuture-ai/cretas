/**
 * 演示前卡点 (owner, 2026-09-02): 「用量待定」行(BOM 只登记配方资格、没有参考用量)
 * 的确认领料闸把「显式填 0」和「留空/不知道」当成了同一件事一起拦下来。
 *
 * LIUSHANMEN 现场 7 味辅料 + L1 纸箱库存全是 0, 仓管如实填 `0.000`(这次一个都不领)
 * 却被 `!(Number(row.pickedInput) > 0)` 当成"没填"拒绝提交 —— 确认领料 → 调拨 → 报工
 * 整段流程卡死。
 *
 * <h3>这道闸本身是有正当理由的, ⛔ 不能简单删掉</h3>
 * 它防的是「留空提交 issuedQty=null, 调拨时那一味料被整个跳过(料没搬却推到
 * TRANSFERRED)」—— 这个理由对 `null` 成立, 对显式 `0` 不成立(0 就是"这次不领",
 * 调拨时按 0 处理, 不是被静默跳过)。
 *
 * <h3>判据 (brief 原话): 阴性对照不是附加项</h3>
 * 只断言「填 0 能提交」的测试和「把闸整个删掉」长得一模一样(两者都会通过) ——
 * 必须同时证明「留空仍然被拦」, 否则这份测试守不住 null 场景, 等于放行了这道闸
 * 本来要防的那个坏结果(料没搬却推到 TRANSFERRED)。
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
  message: vi.fn(),
  warning: vi.fn(),
  success: vi.fn(),
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
  // 生产代码里 missingPending 走的是 `ElMessage({...})` 可调用形式 (不是 .warning),
  // 必须桩成 Object.assign 的可调用对象, 否则 `ElMessage({...})` 会报 "not a function"。
  ElMessage: Object.assign(
    (...a: unknown[]) => harness.message(...a),
    {
      success: (...a: unknown[]) => harness.success(...a),
      warning: (...a: unknown[]) => harness.warning(...a),
      error: (...a: unknown[]) => harness.error(...a),
      info: vi.fn(),
    },
  ),
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm') },
}));

import RequisitionList from '../list.vue';

interface Vm {
  confirmTarget: { id: string; requisitionNo: string } | null;
  confirmRows: Array<{ id: string; materialName: string; requiredQty: number | null; pickedInput: number | null }>;
  submitConfirm: () => Promise<void>;
  submitting: boolean;
}

// 🔴 `el-table-column` 的 `#default="{ row }"` scoped slot 在没有真实组件实现时
// 会直接 `Cannot destructure property 'row' of 'undefined'` 崩溃 (shallowMount 的
// 自动 stub 对无法解析的自定义标签不生效, 见 workflow/__tests__/staleDefinitionRecovery
// .mount.spec.ts 的同款 stub)。本测试不关心表格渲染, 但列表页始终会挂载主表格,
// 必须给个最小可用实现才能让组件正常 mount。
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
  const wrapper = shallowMount(RequisitionList, {
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
  return wrapper;
}

/** BOM 「用量待定」行: requiredQty=null (isPendingQty 判据)。 */
function pendingRow(pickedInput: number | null | '') {
  return {
    id: 'ITEM-1',
    materialName: '生抽',
    requiredQty: null,
    pickedInput: pickedInput as number | null,
  };
}

describe('确认领料: 「用量待定」行 —— 0 与 null 语义相反, 判据必须分得开', () => {
  beforeEach(() => {
    Object.values(harness).forEach((fn) => fn.mockReset());
    harness.get.mockResolvedValue({ success: true, data: { content: [], totalElements: 0 } });
    harness.put.mockResolvedValue({ success: true });
  });

  it('①🔴阳性: 显式填 0 —— 必须放行, PUT 真的发出且 payload 携带 pickedQty:0', async () => {
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    vm.confirmTarget = { id: 'MR-1', requisitionNo: 'MR-20260901-0001' };
    vm.confirmRows = [pendingRow(0)];

    await vm.submitConfirm();
    await flushPromises();

    // 没有被闸拦下的警告 —— 但这一条本身不够(闸被删掉也会绿), 必须配一条阳性:
    // PUT 请求真的发出去了, 而且 payload 里这一味料是明确的 0, 不是被吞掉或变成别的数。
    expect(harness.message, '显式填 0 却仍被当成"没填"拦下 —— 这正是本轮卡点复现').not.toHaveBeenCalled();
    expect(harness.put, '闸放行了, 但 PUT 请求根本没发出去 —— "没报错"可能只是没跑到那一步').toHaveBeenCalledTimes(1);
    expect(harness.put).toHaveBeenCalledWith(
      '/F006/material-requisitions/MR-1/confirm-picking',
      { items: [{ itemId: 'ITEM-1', pickedQty: 0 }] },
    );

    wrapper.unmount();
  });

  it('②🔴阴性对照(核心判据, 不是附加项): 留空(null) 仍必须被拦, ⛔ 不许发 PUT', async () => {
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    vm.confirmTarget = { id: 'MR-1', requisitionNo: 'MR-20260901-0001' };
    vm.confirmRows = [pendingRow(null)];

    await vm.submitConfirm();
    await flushPromises();

    expect(harness.message, '留空却没有任何提示 —— 闸被连带放宽了, 调拨时这味料会被静默跳过').toHaveBeenCalledTimes(1);
    expect(String(harness.message.mock.calls[0][0].message)).toContain('生抽');
    expect(harness.put, '留空却仍然发出了 PUT —— 料没搬却会被推到 TRANSFERRED').not.toHaveBeenCalled();

    wrapper.unmount();
  });

  it('③ 空字符串(输入框被清空的中间态) 同样按"没填"处理', async () => {
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    vm.confirmTarget = { id: 'MR-1', requisitionNo: 'MR-20260901-0001' };
    vm.confirmRows = [pendingRow('')];

    await vm.submitConfirm();
    await flushPromises();

    expect(harness.message).toHaveBeenCalledTimes(1);
    expect(harness.put).not.toHaveBeenCalled();

    wrapper.unmount();
  });

  it('④ 混合行: 一味填了 0、一味仍留空 —— 只点名留空那一味, 已填 0 的不许被误伤', async () => {
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    vm.confirmTarget = { id: 'MR-1', requisitionNo: 'MR-20260901-0001' };
    vm.confirmRows = [
      { id: 'ITEM-1', materialName: '生抽', requiredQty: null, pickedInput: 0 },
      { id: 'ITEM-2', materialName: 'L1纸箱', requiredQty: null, pickedInput: null },
    ];

    await vm.submitConfirm();
    await flushPromises();

    expect(harness.message).toHaveBeenCalledTimes(1);
    const msg = String(harness.message.mock.calls[0][0].message);
    expect(msg, '已经填 0 的「生抽」不该被点名 —— 那味没问题').not.toContain('生抽');
    expect(msg).toContain('L1纸箱');
    expect(harness.put).not.toHaveBeenCalled();

    wrapper.unmount();
  });

  it('⑤ 阳性对照: 有参考用量的普通行(非待定)本来就不受这道闸约束, 0 照常放行', async () => {
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    vm.confirmTarget = { id: 'MR-1', requisitionNo: 'MR-20260901-0001' };
    // requiredQty 有值 (非待定) —— isPendingQty 为 false, 这道闸压根不看它
    vm.confirmRows = [{ id: 'ITEM-3', materialName: '盐', requiredQty: 5, pickedInput: 0 }];

    await vm.submitConfirm();
    await flushPromises();

    expect(harness.message).not.toHaveBeenCalled();
    expect(harness.put).toHaveBeenCalledTimes(1);

    wrapper.unmount();
  });
});
