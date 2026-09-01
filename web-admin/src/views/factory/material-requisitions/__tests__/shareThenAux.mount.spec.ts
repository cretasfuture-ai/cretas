/**
 * 确认领料 —— 主料份额推算与辅料推算是同一屏的兄弟机制, 顺序固定「RAW 份额 → 辅料」。
 *
 * organizer 2026-09-02 要的那条链: 用户只填 2082=300 ⇒ 1399/和牛50 按 30/30/40 自动跳 ⇒
 * RAW 合计 1000 kg ⇒ 洋葱泥按 dosagePerKgG 立刻算出。一次填数全出。
 *
 * 阴性对照: 无份额的 RAW 不动; 自备料 RAW 不参与(既不被推也不当基准, 缺陷 47);
 * 手改过的 RAW(manuallyTouched, 复用 #483 的标记)不被再次推算覆盖。
 * ⛔ #483 的判据(1+1+1 ⇒ 0.2514)在 auxAutoRecalc.mount.spec.ts 里, 本文件不复制、不改。
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
  materialCategory?: string | null;
  pickedInput: number | null;
  inputShareRatio?: number | null;
  selfSupplied?: boolean | null;
  autoLocked?: boolean;
  manuallyTouched?: boolean;
}
interface Vm {
  requiredQtyLabel: (item: { requiredQty?: number | string | null; inputShareRatio?: number | null }) => string;
  confirmRows: ConfirmRow[];
  openConfirmDialog: (row: { id: string; requisitionNo?: string }) => Promise<void>;
  handlePickedInputChange: (row: ConfirmRow) => void;
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

/** 美式汉堡饼形状: 三味带份额的主料 30/30/40 + 一味无份额主料 + 一味自备料 + 一味辅料(洋葱泥 83.8 g/kg)。 */
const DETAIL = {
  id: 'MR-S',
  requisitionNo: 'MR-20260902-S',
  productionPlanId: 'PP-S',
  productName: '美式谷饲牛肉饼',
  sourceWarehouseId: null,
  targetWarehouseId: null,
  items: [
    { id: 'R-2082', materialName: '2082上脑', materialTypeId: 'RMT_2082', materialCategory: 'RAW', unit: 'kg', requiredQty: null, pickedQty: null, inputShareRatio: 30 },
    { id: 'R-1399', materialName: '1399上脑', materialTypeId: 'RMT_1399', materialCategory: 'RAW', unit: 'kg', requiredQty: null, pickedQty: null, inputShareRatio: 30 },
    { id: 'R-WAGYU', materialName: '和牛50', materialTypeId: 'RMT_WAGYU', materialCategory: 'RAW', unit: 'kg', requiredQty: null, pickedQty: null, inputShareRatio: 40 },
    { id: 'R-50CL', materialName: '50CL', materialTypeId: 'RMT_50CL', materialCategory: 'RAW', unit: 'kg', requiredQty: null, pickedQty: null, inputShareRatio: null },
    { id: 'R-ZERO', materialName: '份额0料', materialTypeId: 'RMT_ZERO', materialCategory: 'RAW', unit: 'kg', requiredQty: null, pickedQty: null, inputShareRatio: 0 },
    { id: 'R-SELF', materialName: '冰水(自备)', materialTypeId: 'RMT_ICE', materialCategory: 'RAW', unit: 'kg', requiredQty: null, pickedQty: null, inputShareRatio: 10, selfSupplied: true },
    { id: 'AUX-ONION', materialName: '洋葱泥', materialTypeId: 'RMT_ONION', materialCategory: 'AUXILIARY', unit: 'kg', requiredQty: null, pickedQty: null },
  ],
};

function installGetRouter() {
  harness.get.mockImplementation(async (url: string) => {
    if (url === `/F006/material-requisitions/${DETAIL.id}`) return { success: true, data: DETAIL };
    if (url === '/F006/production-plans/PP-S') return { success: true, data: { productTypeId: 'PT-AMERICAN' } };
    if (url === '/F006/bom/recipes/by-product/PT-AMERICAN/seasoning') {
      return {
        success: true,
        data: {
          bomRecipeId: 'BOM-A', productTypeId: 'PT-AMERICAN', productName: '美式谷饲牛肉饼', status: 'ACTIVE',
          injectionConfigs: [],
          seasoningItems: [{
            workProcessId: 'WP-MIX', materialTypeId: 'RMT_ONION', unit: 'kg', section: 'INJECTION', seq: 1,
            name: '洋葱泥', dosagePerKgG: 83.8, priceSource1: null, priceSource2: null, countInSeasoning: true,
          }],
        },
      };
    }
    return { success: true, data: { content: [], totalElements: 0 } };
  });
}

function row(vm: Vm, id: string): ConfirmRow {
  const found = vm.confirmRows.find((r) => r.id === id);
  if (!found) throw new Error(`fixture 错误: 找不到行 ${id}`);
  return found;
}

async function openDialog() {
  installGetRouter();
  const wrapper = mountList();
  await flushPromises();
  const vm = wrapper.vm as unknown as Vm;
  await vm.openConfirmDialog({ id: DETAIL.id });
  await flushPromises();
  await flushPromises();
  return { wrapper, vm };
}

describe('确认领料 — 主料份额 → 辅料, 一次填数全出 (owner/organizer 2026-09-02)', () => {
  beforeEach(() => {
    Object.values(harness).forEach((fn) => fn.mockReset());
    harness.put.mockResolvedValue({ success: true });
  });

  it('🔴 阳性: 填 2082=300 ⇒ 1399=300、和牛50=400 ⇒ RAW 合计 1000kg ⇒ 洋葱泥 83.8kg', async () => {
    const { wrapper, vm } = await openDialog();
    const r2082 = row(vm, 'R-2082');
    r2082.pickedInput = 300;
    vm.handlePickedInputChange(r2082);

    expect(row(vm, 'R-1399').pickedInput).toBe(300);
    expect(row(vm, 'R-WAGYU').pickedInput).toBe(400);
    // 83.8 g/kg × 1000 kg ÷ 1000 = 83.8 kg —— 精确值, 不是约等于
    expect(row(vm, 'AUX-ONION').pickedInput).toBe(83.8);
    expect(row(vm, 'AUX-ONION').autoLocked).toBe(true);
    wrapper.unmount();
  });

  it('阴性对照(终审跟进①): 持久化为 0 的份额 —— 不被推, 标签也不能写「按配比 0%」', async () => {
    const { wrapper, vm } = await openDialog();
    const r2082 = row(vm, 'R-2082');
    r2082.pickedInput = 300;
    vm.handlePickedInputChange(r2082);
    const zero = row(vm, 'R-ZERO');
    expect(zero.pickedInput).toBeNull();
    expect(vm.requiredQtyLabel(zero)).toBe('按实际领用');
    expect(vm.requiredQtyLabel(r2082)).toBe('按配比 30%');
    wrapper.unmount();
  });

  it('阴性对照: 无份额的 RAW 与自备料 RAW 都不被推 (自备料即使带份额也不参与)', async () => {
    const { wrapper, vm } = await openDialog();
    const r2082 = row(vm, 'R-2082');
    r2082.pickedInput = 300;
    vm.handlePickedInputChange(r2082);

    expect(row(vm, 'R-50CL').pickedInput).toBeNull();
    expect(row(vm, 'R-SELF').pickedInput).toBeNull();
    wrapper.unmount();
  });

  it('阴性对照: 手改过的 1399 (manuallyTouched) 不被第二次推算覆盖, 辅料按新合计重算', async () => {
    const { wrapper, vm } = await openDialog();
    const r2082 = row(vm, 'R-2082');
    r2082.pickedInput = 300;
    vm.handlePickedInputChange(r2082);

    const r1399 = row(vm, 'R-1399');
    r1399.pickedInput = 250;
    vm.handlePickedInputChange(r1399);          // 现场实际只有 250, 这是真实的, 不能被吃掉
    expect(r1399.manuallyTouched).toBe(true);

    r2082.pickedInput = 600;
    vm.handlePickedInputChange(r2082);
    expect(r1399.pickedInput).toBe(250);        // ⛔ 不覆盖
    expect(row(vm, 'R-WAGYU').pickedInput).toBe(800);
    // 600 + 250 + 800 = 1650 kg ⇒ 83.8 × 1.65 = 138.27
    expect(row(vm, 'AUX-ONION').pickedInput).toBe(138.27);
    wrapper.unmount();
  });
});
