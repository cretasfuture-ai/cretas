/**
 * D-S411R-01 (2026-11-10, F006 实测): 缺料自动生成的采购草稿 PO-AUTO-* 没有供应商,
 * 提交 → 422 PURCHASE_SUPPLIER_REQUIRED「请编辑采购草稿并选择供应商后再提交」;
 * 而「编辑草稿」弹窗里供应商下拉是 `:disabled="isEditing"` —— 对所有已存在单据一刀切锁住,
 * 于是这张草稿既提交不了、也补不了供应商, 只能取消重建。
 *
 * 本测试挂载**真实的** list.vue, 走真实入口 openEditDialog(读单据 → 预填 → 开弹窗),
 * 读供应商下拉的 disabled —— ⛔ 不只测纯函数: 纯函数对了而模板没接上, 是本仓反复出现的形状。
 *
 * 阴性对照:
 * - 已有供应商的草稿: 下拉可用, 换成另一家后明细改绑到新供应关系 (2026-11-11 起; 此前一直锁死)。
 * - 已提交的单: 编辑弹窗根本不打开 (后端也只放行 DRAFT), 下拉不可达。
 */
import { flushPromises, shallowMount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide, reactive } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  listSupplierMaterials: vi.fn(),
  listSupplierPurchaseSpecs: vi.fn(),
  message: vi.fn(),
}));

const route = reactive({ path: '/procurement/orders', fullPath: '/procurement/orders', query: {} as Record<string, unknown> });

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn().mockResolvedValue(undefined),
    resolve: () => ({ href: '/procurement/suppliers' }),
  }),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canRead: () => true, hasPermission: () => true }),
}));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => harness.get(...args),
  post: (...args: unknown[]) => harness.post(...args),
  put: (...args: unknown[]) => harness.put(...args),
  default: {
    get: (...args: unknown[]) => harness.get(...args),
    post: (...args: unknown[]) => harness.post(...args),
  },
}));
vi.mock('@/api/supplierManagement', () => ({
  listSupplierMaterials: (...args: unknown[]) => harness.listSupplierMaterials(...args),
  listSupplierPurchaseSpecs: (...args: unknown[]) => harness.listSupplierPurchaseSpecs(...args),
}));
vi.mock('element-plus', () => ({
  ElMessage: Object.assign((...a: unknown[]) => harness.message(...a), {
    success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn(),
  }),
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm') },
}));

import PurchaseOrderList from '../list.vue';

interface FormItem {
  supplierMaterialId?: string;
  materialTypeId: string;
  materialName?: string;
  quantity: number;
  unit: string;
  unitPrice: number | null;
  priceSource?: string | null;
}
interface Vm {
  openEditDialog: (id: string) => Promise<void>;
  onSupplierChange: () => Promise<void>;
  dialogVisible: boolean;
  form: { supplierId: string; items: FormItem[] };
}

const PassThrough = (name: string) => defineComponent({
  name,
  setup(_props, { slots }) {
    return () => h('div', slots.default?.());
  },
});
// el-table-column 的 `#default="{ row }"` 在自动 stub 下会以 undefined 调用并崩溃 ——
// 同 material-requisitions/__tests__/*.mount.spec.ts 的最小实现。
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
// 弹窗关着时不渲染内容 —— 让「编辑弹窗根本没打开」成为可观测的读数。
const DialogStub = defineComponent({
  name: 'ElDialog',
  props: { modelValue: { type: Boolean, default: false } },
  setup(props, { slots }) {
    return () => (props.modelValue ? h('div', { 'data-stub': 'dialog' }, slots.default?.()) : null);
  },
});

const SUPPLIER = { id: 'sup-salt', name: 'QA-S4-1029-供应商', shortName: '', isActive: true };
const SALT_RELATION = {
  id: 'rel-salt', materialTypeId: 'mat-salt', materialName: 'QA-S4-1029-盐料',
  purchaseUnit: 'kg', baseUnit: 'kg', defaultPurchasePrice: 6, active: true,
};

function autoDraft(overrides: Record<string, unknown> = {}) {
  return {
    id: 'po-auto-7', orderNumber: 'PO-AUTO-20261110-7', status: 'DRAFT', supplierId: null,
    version: 3, orderDate: '2026-11-10', purchaseType: 'DIRECT',
    items: [{
      materialTypeId: 'mat-salt', materialName: 'QA-S4-1029-盐料',
      quantity: 56.74, unit: 'kg', priceUnit: 'kg', unitPrice: 0, taxRate: 0,
    }],
    ...overrides,
  };
}

function routeGet(order: Record<string, unknown>) {
  harness.get.mockImplementation(async (url: string) => {
    if (url === `/F006/purchase/orders/${order.id}`) return { success: true, data: order };
    if (/\/suppliers(\?|$)/.test(url) || url.endsWith('/suppliers/active')) {
      return { success: true, data: { content: [SUPPLIER], totalElements: 1 } };
    }
    return { success: true, data: { content: [], totalElements: 0 } };
  });
}

function mountList() {
  return shallowMount(PurchaseOrderList, {
    global: {
      stubs: {
        ElDialog: DialogStub,
        ElTable: ElTableStub,
        ElTableColumn: ElTableColumnStub,
        ElForm: PassThrough('ElForm'),
        ElFormItem: PassThrough('ElFormItem'),
        ElCard: PassThrough('ElCard'),
        CanvasAwareWrapper: PassThrough('CanvasAwareWrapper'),
      },
    },
  });
}

function supplierSelect(wrapper: ReturnType<typeof mountList>) {
  return wrapper.find('[data-testid="purchase-supplier-select"]');
}

describe('D-S411R-01 编辑采购草稿补录供应商 (挂载真实 list.vue)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    route.query = {};
    harness.listSupplierMaterials.mockResolvedValue([SALT_RELATION]);
    harness.listSupplierPurchaseSpecs.mockResolvedValue([]);
  });

  it('没有供应商的草稿: 编辑弹窗打开, 供应商下拉可用, 并提示可在此补录', async () => {
    routeGet(autoDraft());
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openEditDialog('po-auto-7');
    await flushPromises();

    expect(vm.dialogVisible).toBe(true);
    const select = supplierSelect(wrapper);
    // 阳性对照: 确实找到了那个下拉 (否则下面的 disabled 断言是在守空气)。
    expect(select.exists()).toBe(true);
    expect(select.attributes('disabled')).toBe('false');
    const hint = wrapper.find('[data-testid="purchase-draft-supplier-backfill-hint"]');
    expect(hint.exists()).toBe(true);
    expect(hint.attributes('title')).toContain('QA-S4-1029-盐料 56.74');
  });

  it('选了供应商后明细保留: 原料按供应关系对应、缺口数量不变、0 元占位价换成供应商价', async () => {
    routeGet(autoDraft());
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;
    await vm.openEditDialog('po-auto-7');
    await flushPromises();

    vm.form.supplierId = SUPPLIER.id;
    await vm.onSupplierChange();
    await flushPromises();

    expect(harness.listSupplierMaterials).toHaveBeenCalledWith('F006', SUPPLIER.id);
    expect(vm.form.items).toHaveLength(1);
    const [line] = vm.form.items;
    expect(line.materialTypeId).toBe('mat-salt');
    expect(line.supplierMaterialId).toBe('rel-salt');
    expect(line.quantity).toBe(56.74);
    expect(line.unitPrice).toBe(6);
    expect(line.priceSource).toBe('SUPPLIER_RELATION');
    // 选完第一个供应商后下拉不锁 —— 选错了还能改。
    expect(supplierSelect(wrapper).attributes('disabled')).toBe('false');
  });

  it('选的供应商不供应草稿里的原料: 行不被猜着绑上, 并列出该原料', async () => {
    harness.listSupplierMaterials.mockResolvedValue([
      { ...SALT_RELATION, id: 'rel-sugar', materialTypeId: 'mat-sugar', materialName: '白糖' },
    ]);
    routeGet(autoDraft());
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;
    await vm.openEditDialog('po-auto-7');
    await flushPromises();

    vm.form.supplierId = SUPPLIER.id;
    await vm.onSupplierChange();
    await flushPromises();

    expect(vm.form.items[0].supplierMaterialId).toBe('');
    expect(vm.form.items[0].quantity).toBe(56.74);
    const warning = wrapper.find('[data-testid="purchase-draft-supplier-unmatched"]');
    expect(warning.exists()).toBe(true);
    expect(warning.attributes('title')).toContain('QA-S4-1029-盐料');
  });

  it('已有供应商的草稿: 下拉可用, 换成另一家后明细改绑新供应关系与价、数量不变', async () => {
    const OTHER_RELATION = { ...SALT_RELATION, id: 'rel-salt-2', defaultPurchasePrice: 5.5 };
    harness.listSupplierMaterials.mockImplementation(async (_factoryId: string, supplierId: string) =>
      supplierId === 'sup-salt-2' ? [OTHER_RELATION] : [SALT_RELATION]);
    routeGet(autoDraft({
      supplierId: SUPPLIER.id,
      items: [{
        supplierMaterialId: 'rel-salt', materialTypeId: 'mat-salt', materialName: 'QA-S4-1029-盐料',
        quantity: 56.74, unit: 'kg', priceUnit: 'kg', unitPrice: 6, taxRate: 13,
      }],
    }));
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openEditDialog('po-auto-7');
    await flushPromises();

    expect(vm.dialogVisible).toBe(true);
    const select = supplierSelect(wrapper);
    expect(select.exists()).toBe(true);
    expect(select.attributes('disabled')).toBe('false');
    // 已有供应商就不是「补录」, 不出「还没有供应商」的提示
    expect(wrapper.find('[data-testid="purchase-draft-supplier-backfill-hint"]').exists()).toBe(false);

    vm.form.supplierId = 'sup-salt-2';
    await vm.onSupplierChange();
    await flushPromises();

    expect(harness.listSupplierMaterials).toHaveBeenLastCalledWith('F006', 'sup-salt-2');
    expect(vm.form.items).toHaveLength(1);
    const [line] = vm.form.items;
    expect(line.supplierMaterialId).toBe('rel-salt-2');
    expect(line.unitPrice).toBe(5.5);
    expect(line.quantity).toBe(56.74);
  });

  it('阴性对照: 已提交的单不进编辑弹窗, 供应商下拉不可达', async () => {
    routeGet(autoDraft({ status: 'SUBMITTED' }));
    const wrapper = mountList();
    await flushPromises();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openEditDialog('po-auto-7');
    await flushPromises();

    // 阳性对照: 读单据那一步确实发生了 (否则「没开弹窗」可能只是根本没跑)。
    expect(harness.get).toHaveBeenCalledWith('/F006/purchase/orders/po-auto-7', expect.anything());
    expect(vm.dialogVisible).toBe(false);
    expect(supplierSelect(wrapper).exists()).toBe(false);
    expect(harness.message).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('仅草稿采购单允许编辑'),
    }));
  });
});
