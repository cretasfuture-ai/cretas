/**
 * 2026-11-10: 仓管 (warehouse_worker / warehouse_manager, production 模块 = none) 打开「确认领料」,
 * 弹窗为了自动算辅料先 GET /production-plans/{id} 拿 productTypeId, 再查 BOM 调料 —— 两跳都 403,
 * 还弹出 sticky 错误「缺少模块 production_plan 的访问权限」, 辅料只能手填。
 *
 * 修法: 配比由仓管本来就读得到的领料单详情带出 (items[].dosagePerKgG + auxiliaryAutoCalc),
 * 弹窗不再请求生产计划和 BOM。本测试钉三件事:
 *   ① 打开确认领料的全过程, 没有任何 /production-plans/ 或 /bom/ 请求, 也没有错误提示;
 *   ② 配比来自详情: RAW 1+1+1=3kg ⇒ 洋葱泥 83.8 g/kg × 3kg = 0.2514kg (与旧路径同一个数, UX 不变);
 *   ③ 工艺保密且无权 (PROCESS_SECRET): 不自动算、给一句「按实际称重填写」的说明, ⛔ 不是错误 toast。
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
// 仓管: 只有 warehouse 写权限, 没有 production
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: (module: string) => module === 'warehouse' }),
}));
vi.mock('@/api/factoryWarehouse', () => ({
  listWarehouses: vi.fn().mockResolvedValue({ success: true, data: [] }),
}));
// ⛔ 不桩 @/api/bom: 万一弹窗还在调 bomSeasoningApi, 它会走到下面这个 get, 被「禁止的请求」断言抓住。
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
  autoLocked?: boolean;
  manuallyTouched?: boolean;
}
interface Vm {
  confirmRows: ConfirmRow[];
  auxAutoCalcNote: string;
  openConfirmDialog: (row: { id: string }) => Promise<void>;
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
// el-alert 的文案在 title 属性里, 透传组件看不到 —— 把 title 渲染成文本, 才断言得到「用户看得见」。
const ElAlertStub = defineComponent({
  name: 'ElAlert',
  props: { title: { type: String, default: '' } },
  setup(props, { slots, attrs }) {
    return () => h('div', { 'data-testid': attrs['data-testid'] }, [props.title, slots.default?.()]);
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
        ElAlert: ElAlertStub,
        ElSelect: PassThrough('ElSelect'),
        ElOption: PassThrough('ElOption'),
        ElPagination: PassThrough('ElPagination'),
        ElCard: PassThrough('ElCard'),
        ElTag: PassThrough('ElTag'),
      },
    },
  });
}

const baseItems = () => [
  { id: 'RAW-1', materialName: '牛前腿肉', materialTypeId: 'RMT_BEEF_FRONT', materialCategory: 'RAW', unit: 'kg', requiredQty: null, pickedQty: null },
  { id: 'RAW-2', materialName: '牛后腿肉', materialTypeId: 'RMT_BEEF_BACK', materialCategory: 'RAW', unit: 'kg', requiredQty: null, pickedQty: null },
  { id: 'RAW-3', materialName: '肥膘', materialTypeId: 'RMT_FAT', materialCategory: 'RAW', unit: 'kg', requiredQty: null, pickedQty: null },
];

/** 后端详情 (FactoryMaterialRequisitionController#detail) 在保密关 / 有权时的形状。 */
const DETAIL_WITH_DOSAGE = {
  id: 'MR-1',
  requisitionNo: 'MR-20261110-0001',
  productionPlanId: 'PP-1',
  productName: '熟肉饼',
  auxiliaryAutoCalc: 'AVAILABLE',
  items: [
    ...baseItems(),
    { id: 'AUX-1', materialName: '洋葱泥', materialTypeId: 'RMT_29e73042', materialCategory: 'AUXILIARY', unit: 'kg', requiredQty: null, pickedQty: null, dosagePerKgG: 83.8 },
  ],
};

/** 工艺保密且调用者无权: 后端不给 dosagePerKgG, 状态 PROCESS_SECRET。 */
const DETAIL_PROCESS_SECRET = {
  ...DETAIL_WITH_DOSAGE,
  id: 'MR-2',
  auxiliaryAutoCalc: 'PROCESS_SECRET',
  items: [
    ...baseItems(),
    { id: 'AUX-1', materialName: '洋葱泥', materialTypeId: 'RMT_29e73042', materialCategory: 'AUXILIARY', unit: 'kg', requiredQty: null, pickedQty: null },
  ],
};

/** 配方里没有这味辅料的配比 (NO_RATIO) —— 独立审查 #1558 L3。 */
const DETAIL_NO_RATIO = { ...DETAIL_PROCESS_SECRET, id: 'MR-3', auxiliaryAutoCalc: 'NO_RATIO' };
/** 后端读配比出错, 详情照常返回 (UNAVAILABLE) —— 独立审查 #1558 L1。 */
const DETAIL_UNAVAILABLE = { ...DETAIL_PROCESS_SECRET, id: 'MR-4', auxiliaryAutoCalc: 'UNAVAILABLE' };
/** 只有原料、没有辅料: 不需要任何辅料提示。 */
const DETAIL_RAW_ONLY = { ...DETAIL_WITH_DOSAGE, id: 'MR-5', auxiliaryAutoCalc: 'NO_RATIO', items: baseItems() };

const FORBIDDEN = /\/production-plans\/|\/bom\//;

function installGetRouter(detail: typeof DETAIL_WITH_DOSAGE) {
  harness.get.mockImplementation(async (url: string) => {
    if (url === `/F006/material-requisitions/${detail.id}`) return { success: true, data: detail };
    // 真实世界里仓管打这两类接口是 403 + sticky 错误 toast; 这里直接让它炸, 有调用就一定暴露
    if (FORBIDDEN.test(url)) throw new Error(`403 缺少模块 production_plan 的访问权限 (${url})`);
    return { success: true, data: { content: [], totalElements: 0 } };
  });
}

function forbiddenCalls(): string[] {
  return harness.get.mock.calls.map((c) => String(c[0])).filter((url) => FORBIDDEN.test(url));
}

function errorToasts(): unknown[] {
  const typedErrors = harness.message.mock.calls.filter((c) => (c[0] as { type?: string } | undefined)?.type === 'error');
  return [...harness.error.mock.calls, ...typedErrors];
}

async function openAndFillRaw(detail: typeof DETAIL_WITH_DOSAGE) {
  installGetRouter(detail);
  const wrapper = mountList();
  await flushPromises();
  const vm = wrapper.vm as unknown as Vm;
  await vm.openConfirmDialog({ id: detail.id });
  await flushPromises();
  await flushPromises();
  for (const id of ['RAW-1', 'RAW-2', 'RAW-3']) {
    const row = vm.confirmRows.find((r) => r.id === id);
    if (!row) throw new Error(`fixture 错误: 找不到行 ${id}`);
    row.pickedInput = 1;
    vm.handlePickedInputChange(row);
  }
  const aux = vm.confirmRows.find((r) => r.id === 'AUX-1');
  return { wrapper, vm, aux };
}

function requireAux(aux: ConfirmRow | undefined): ConfirmRow {
  if (!aux) throw new Error('fixture 错误: 找不到辅料行');
  return aux;
}

describe('确认领料 — 辅料配比来自领料单详情, 仓管不再碰生产计划 / BOM (2026-11-10)', () => {
  beforeEach(() => {
    Object.values(harness).forEach((fn) => fn.mockReset());
    harness.put.mockResolvedValue({ success: true });
  });

  it('①② 仓管打开确认领料: 零个 production-plans / bom 请求、零个错误提示, 辅料按详情里的配比自动算出 0.2514kg', async () => {
    const opened = await openAndFillRaw(DETAIL_WITH_DOSAGE);
    const { wrapper, vm } = opened;
    const aux = requireAux(opened.aux);

    // 阳性锚点: 详情请求确实发了 (否则「零个禁止请求」可能只是什么都没跑)
    expect(harness.get.mock.calls.map((c) => c[0])).toContain('/F006/material-requisitions/MR-1');
    expect(forbiddenCalls(), '弹窗还在查生产计划 / BOM —— 仓管会 403').toEqual([]);
    expect(errorToasts(), '打开确认领料不该弹任何错误').toEqual([]);

    expect(aux.autoLocked, '没有用上详情里的 dosagePerKgG').toBe(true);
    expect(aux.pickedInput).toBe(0.2514);
    expect(vm.auxAutoCalcNote).toBe('');
    // 有配比时总说明照旧 (阳性对照: 下一条测试的「不含无需手填」不是因为这句话根本没渲染)
    expect(wrapper.text()).toContain('无需手填');
    expect(wrapper.find('[data-testid="aux-auto-calc-note"]').exists()).toBe(false);

    wrapper.unmount();
  });

  it('③ 工艺保密且无权: 不自动算(留空待称重), 弹窗里给一句说明, 不弹错误、也不去查生产计划 / BOM', async () => {
    const opened = await openAndFillRaw(DETAIL_PROCESS_SECRET);
    const { wrapper, vm } = opened;
    const aux = requireAux(opened.aux);

    expect(forbiddenCalls()).toEqual([]);
    expect(errorToasts()).toEqual([]);
    expect(aux.autoLocked).toBeFalsy();
    expect(aux.pickedInput, '没有配比却编出了一个数').toBeNull();
    expect(vm.auxAutoCalcNote).toContain('保密');
    expect(wrapper.find('[data-testid="aux-auto-calc-note"]').text()).toContain('按实际称重');
    // 同一弹窗里不能再有「无需手填」这句与保密提示自相矛盾的话
    expect(wrapper.text()).not.toContain('无需手填');

    wrapper.unmount();
  });

  it.each([
    ['NO_RATIO', DETAIL_NO_RATIO, '配方里没有'],
    ['UNAVAILABLE', DETAIL_UNAVAILABLE, '没有读取到'],
  ])('④ L3: %s ⇒ 不写「无需手填」, 改为请人工称重填写的提示 (措辞与保密不同), 不弹错误', async (_status, detail, wording) => {
    const opened = await openAndFillRaw(detail);
    const { wrapper, vm } = opened;
    const aux = requireAux(opened.aux);

    expect(forbiddenCalls()).toEqual([]);
    expect(errorToasts()).toEqual([]);
    expect(aux.autoLocked).toBeFalsy();
    expect(aux.pickedInput).toBeNull();
    expect(wrapper.text(), '辅料一行都不会算, 总说明却还说无需手填').not.toContain('无需手填');
    const note = wrapper.find('[data-testid="aux-auto-calc-note"]');
    expect(note.exists(), '没有给人工填写的提示').toBe(true);
    expect(note.text()).toContain('请按实际称重填写');
    expect(note.text()).toContain(wording);
    expect(vm.auxAutoCalcNote).not.toContain('保密');

    wrapper.unmount();
  });

  it('⑤ 只有原料没有辅料: 不给辅料提示, 也不说「辅料无需手填」', async () => {
    const { wrapper, vm } = await openAndFillRaw(DETAIL_RAW_ONLY);

    expect(vm.confirmRows).toHaveLength(3);
    expect(vm.auxAutoCalcNote).toBe('');
    expect(wrapper.find('[data-testid="aux-auto-calc-note"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('无需手填');
    expect(wrapper.text()).toContain('请称重录入每种物料的实际拣货数量');

    wrapper.unmount();
  });
});
