/**
 * 确认领料 — 辅料自动计算 (owner 2026-09-02 拍板, 逐字引用见 confirmPickingAuxDerive.ts)。
 *
 * 覆盖三条硬约束:
 *   ① 正数(验收数字): RAW 1+1+1=3kg ⇒ 洋葱泥 0.2514kg; RAW 合计改到 5kg ⇒ 0.419kg
 *      (精确值断言, 见同目录 confirmPickingAuxDerive.spec.ts 的纯函数单测)。
 *   ② 手改保护: 用户手改过的辅料行, 改 RAW 之后不许被静默覆盖 —— 断言那个值原样保留。
 *   ③ 阴性对照: RAW 合计为 0 时辅料必须是「按实际领用」(null), ⛔ 不是编出来的 0。
 *   ④ 阳性对照(正常路径不受影响): 纯 RAW 的领料单(没有辅料行)照常能确认。
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
// `@/api/bom` 的 bomSeasoningApi.getByProduct 内部调 `./request` 的 get() —— 走的是
// 下面同一个 mock, ⛔ 不额外桩 @/api/bom 本身: 这样才顺带验证了它拼出的 URL 是对的
// (由 harness.get 按路径分发, 见下方 mockImplementation)。
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => harness.get(...args),
  post: (...args: unknown[]) => harness.post(...args),
  put: (...args: unknown[]) => harness.put(...args),
  default: { get: (...args: unknown[]) => harness.requestGet(...args) },
}));
vi.mock('element-plus', () => ({
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

interface ConfirmRow {
  id: string;
  materialTypeId?: string | null;
  materialName?: string | null;
  materialCategory?: string | null;
  unit?: string | null;
  pickedInput: number | null;
  autoLocked?: boolean;
  manuallyTouched?: boolean;
}
interface Vm {
  confirmRows: ConfirmRow[];
  openConfirmDialog: (row: { id: string; requisitionNo?: string }) => Promise<void>;
  handlePickedInputChange: (row: ConfirmRow) => void;
  resetAuxToAuto: (row: ConfirmRow) => void;
  submitConfirm: () => Promise<void>;
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

/** 真实 LIUSHANMEN 熟肉饼 BOM 形状: 3 味 RAW(牛前/牛后/脂肪) + 1 味辅料(洋葱泥, WL2020)。 */
const REQUISITION_DETAIL = {
  id: 'MR-1',
  requisitionNo: 'MR-20260902-0001',
  productionPlanId: 'PP-1',
  productName: '熟肉饼',
  sourceWarehouseId: null,
  targetWarehouseId: null,
  items: [
    { id: 'RAW-1', materialName: '牛前腿肉', materialTypeId: 'RMT_BEEF_FRONT', materialCategory: 'RAW', unit: 'kg', requiredQty: null, pickedQty: null },
    { id: 'RAW-2', materialName: '牛后腿肉', materialTypeId: 'RMT_BEEF_BACK', materialCategory: 'RAW', unit: 'kg', requiredQty: null, pickedQty: null },
    { id: 'RAW-3', materialName: '肥膘', materialTypeId: 'RMT_FAT', materialCategory: 'RAW', unit: 'kg', requiredQty: null, pickedQty: null },
    { id: 'AUX-1', materialName: '洋葱泥', materialTypeId: 'RMT_29e73042', materialCategory: 'AUXILIARY', unit: 'kg', requiredQty: null, pickedQty: null },
  ],
};

const RAW_ONLY_DETAIL = {
  id: 'MR-2',
  requisitionNo: 'MR-20260902-0002',
  productionPlanId: 'PP-2',
  productName: '原料测试',
  sourceWarehouseId: null,
  targetWarehouseId: null,
  items: [
    { id: 'RAW-A', materialName: '原料A', materialTypeId: 'RMT_A', materialCategory: 'RAW', unit: 'kg', requiredQty: 2, pickedQty: null },
  ],
};

/** 按 URL 分发桩响应 —— 顺带验证 loadAuxRatios 真的拼出了预期的两跳请求。 */
function installGetRouter(detail: typeof REQUISITION_DETAIL | typeof RAW_ONLY_DETAIL) {
  harness.get.mockImplementation(async (url: string) => {
    if (url === `/F006/material-requisitions/${detail.id}`) {
      return { success: true, data: detail };
    }
    if (url === '/F006/production-plans/PP-1') {
      return { success: true, data: { productTypeId: 'PT-SHOUROUBING' } };
    }
    if (url === '/F006/bom/recipes/by-product/PT-SHOUROUBING/seasoning') {
      return {
        success: true,
        data: {
          bomRecipeId: 'BOM-1',
          productTypeId: 'PT-SHOUROUBING',
          productName: '熟肉饼',
          status: 'ACTIVE',
          injectionConfigs: [],
          seasoningItems: [
            {
              workProcessId: 'WP-1',
              materialTypeId: 'RMT_29e73042',
              unit: 'kg',
              section: 'INJECTION',
              seq: 1,
              name: '洋葱泥',
              dosagePerKgG: 83.8,
              priceSource1: null,
              priceSource2: null,
              countInSeasoning: true,
            },
          ],
        },
      };
    }
    if (url === '/F006/production-plans/PP-2') {
      return { success: true, data: { productTypeId: null } };
    }
    // 列表页 onMounted 会打 GET /F006/material-requisitions —— 兜个底, 不让它炸。
    return { success: true, data: { content: [], totalElements: 0 } };
  });
}

function rawRow(vm: Vm, id: string): ConfirmRow {
  const row = vm.confirmRows.find((r) => r.id === id);
  if (!row) throw new Error(`fixture 错误: 找不到行 ${id}`);
  return row;
}

describe('确认领料 — 辅料按 RAW 实际合计自动实时计算 (owner 2026-09-02)', () => {
  beforeEach(() => {
    Object.values(harness).forEach((fn) => fn.mockReset());
    harness.put.mockResolvedValue({ success: true });
  });

  it('①🔴 阳性(验收数字): RAW 1+1+1=3kg ⇒ 洋葱泥自动算成 0.2514kg, 且标记为「自动计算」', async () => {
    installGetRouter(REQUISITION_DETAIL);
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openConfirmDialog({ id: 'MR-1' });
    await flushPromises();
    await flushPromises(); // loadAuxRatios 里有两跳 await, 多 flush 一次确保都落地

    // 输入 RAW 三行为 1kg, 每次改动都模拟真实用户触发 @change
    for (const id of ['RAW-1', 'RAW-2', 'RAW-3']) {
      const row = rawRow(vm, id);
      row.pickedInput = 1;
      vm.handlePickedInputChange(row);
    }

    const aux = rawRow(vm, 'AUX-1');
    expect(aux.pickedInput, '未量到真实数字 —— 停在这里比继续断言更诚实').toBe(0.2514);
    expect(aux.autoLocked).toBe(true);
    expect(aux.manuallyTouched).toBeFalsy();

    wrapper.unmount();
  });

  it('①b 改 RAW 合计到 5kg ⇒ 辅料重新算成 0.419kg (「改了原料它就重新算」)', async () => {
    installGetRouter(REQUISITION_DETAIL);
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openConfirmDialog({ id: 'MR-1' });
    await flushPromises();
    await flushPromises();

    const [r1, r2, r3] = ['RAW-1', 'RAW-2', 'RAW-3'].map((id) => rawRow(vm, id));
    r1.pickedInput = 1; vm.handlePickedInputChange(r1);
    r2.pickedInput = 1; vm.handlePickedInputChange(r2);
    r3.pickedInput = 1; vm.handlePickedInputChange(r3);
    expect(rawRow(vm, 'AUX-1').pickedInput).toBe(0.2514);

    // 把第三行原料从 1kg 改到 3kg —— 合计 1+1+3=5kg
    r3.pickedInput = 3;
    vm.handlePickedInputChange(r3);

    expect(rawRow(vm, 'AUX-1').pickedInput).toBe(0.419);

    wrapper.unmount();
  });

  it('②🔴 手改保护(卡点②): 用户手改辅料行之后, 再改 RAW 不许覆盖那个手改值', async () => {
    installGetRouter(REQUISITION_DETAIL);
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openConfirmDialog({ id: 'MR-1' });
    await flushPromises();
    await flushPromises();

    for (const id of ['RAW-1', 'RAW-2', 'RAW-3']) {
      const row = rawRow(vm, id);
      row.pickedInput = 1;
      vm.handlePickedInputChange(row);
    }
    expect(rawRow(vm, 'AUX-1').pickedInput).toBe(0.2514);

    // 现场手改: 多放了盐(这里用洋葱泥代表), 手填 0.9
    const aux = rawRow(vm, 'AUX-1');
    aux.pickedInput = 0.9;
    vm.handlePickedInputChange(aux);
    expect(aux.manuallyTouched, '手改没有被登记 —— recalc 会在下一次 RAW 变化时把它冲掉').toBe(true);

    // 再改一次 RAW (合计变成 5kg, 按自动算法应该是 0.419) —— 手改值必须原样保留
    const r3 = rawRow(vm, 'RAW-3');
    r3.pickedInput = 3;
    vm.handlePickedInputChange(r3);

    expect(rawRow(vm, 'AUX-1').pickedInput, '手改值被静默覆盖了 —— 这正是 brief 明令禁止的').toBe(0.9);

    // 「恢复自动计算」之后才应该重新交给公式
    vm.resetAuxToAuto(rawRow(vm, 'AUX-1'));
    expect(rawRow(vm, 'AUX-1').manuallyTouched).toBe(false);
    expect(rawRow(vm, 'AUX-1').pickedInput).toBe(0.419);

    wrapper.unmount();
  });

  it('③🔴 阴性对照: RAW 合计为 0(还没填/全填0) ⇒ 辅料必须是「按实际领用」(null), ⛔ 不能是编出来的 0', async () => {
    installGetRouter(REQUISITION_DETAIL);
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openConfirmDialog({ id: 'MR-1' });
    await flushPromises();
    await flushPromises();

    // 刚打开对话框, RAW 还没填 —— 合计为 0
    const aux = rawRow(vm, 'AUX-1');
    expect(aux.autoLocked, '没有命中比例数据 —— 说明 loadAuxRatios 没接上, 后面的断言都无意义').toBe(true);
    expect(aux.pickedInput).toBeNull();

    // 显式把三味原料都填 0 (「这次不领」) —— 合计仍是 0, 辅料依旧是 null 不是 0
    for (const id of ['RAW-1', 'RAW-2', 'RAW-3']) {
      const row = rawRow(vm, id);
      row.pickedInput = 0;
      vm.handlePickedInputChange(row);
    }
    expect(rawRow(vm, 'AUX-1').pickedInput).toBeNull();

    wrapper.unmount();
  });

  it('④ 阳性对照: 纯 RAW 领料单(没有辅料行)照常打开, 不受这次改动影响', async () => {
    installGetRouter(RAW_ONLY_DETAIL);
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openConfirmDialog({ id: 'MR-2' });
    await flushPromises();
    await flushPromises();

    expect(vm.confirmRows).toHaveLength(1);
    expect(vm.confirmRows[0].materialCategory).toBe('RAW');
    // RAW 行照旧走老逻辑: 有参考需求量(2)且未曾拣货 ⇒ 预填需求量
    expect(vm.confirmRows[0].pickedInput).toBe(2);
    expect(vm.confirmRows[0].autoLocked).toBeFalsy();

    wrapper.unmount();
  });
});
