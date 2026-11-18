/**
 * PROTO-DEMO-PO-TAX (2026-11-18, F006 PO-20261118-0001): 新建采购单录「含税单价」与税率,
 * 保存的 unit_price 必须是【未税】单价, 改税率要重算, 表单合计三个数对得上。
 *
 * 挂载**真实的** list.vue, 走模板上的真实接线 (el-input-number / el-select 的 update:modelValue
 * → onTaxIncludedPriceEdited / onTaxRateEdited → handleCreate 载荷), ⛔ 不只测纯函数。
 *
 * 样本按 prod 原样: 物料「演示·250g食品盒」单位「个」, 供应关系默认价 0.65, 原料档案**没配税率**
 * (raw_material_types.tax_rate IS NULL) —— 所以用户先录含税价、后选税率, 正是本单的录入顺序。
 */
import { flushPromises, shallowMount } from '@vue/test-utils';
import { defineComponent, h, reactive } from 'vue';
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
  quantity: number | null;
  unitPrice: number | null;
  taxIncludedUnitPrice: number | null;
  priceSource?: string | null;
  taxRate?: number | null;
}
interface Vm {
  openCreateDialog: () => void;
  onSupplierChange: () => Promise<void>;
  handleCreate: () => Promise<void>;
  form: { supplierId: string; items: FormItem[] };
}

const PassThrough = (name: string) => defineComponent({
  name,
  setup(_props, { slots }) {
    return () => h('div', slots.default?.());
  },
});
// el-table-column 的 `#default="{ row }"` 在自动 stub 下会以 undefined 调用并崩溃 —— 列表表格本测不关心, 不渲染列体。
const ElTableColumnStub = defineComponent({ name: 'ElTableColumn', setup: () => () => h('div') });
// 表单控件: 只保留 placeholder / modelValue, 测试通过 $emit('update:modelValue') 走模板上真实的
// v-model + @update:model-value 接线 (与 Element Plus 用户输入时发出的事件同名)。
const InputStub = (name: string) => defineComponent({
  name,
  props: { placeholder: { type: String, default: '' }, modelValue: { type: null, default: null } },
  emits: ['update:modelValue', 'change'],
  setup(_props, { slots }) {
    return () => h('div', slots.default?.());
  },
});
const DialogStub = defineComponent({
  name: 'ElDialog',
  props: { modelValue: { type: Boolean, default: false } },
  setup(props, { slots }) {
    return () => (props.modelValue ? h('div', { 'data-stub': 'dialog' }, slots.default?.()) : null);
  },
});

const SUPPLIER = { id: 'sup-box', name: '演示·包材供应商', shortName: '', isActive: true };
const BOX_RELATION = {
  id: 'rel-box', materialTypeId: 'mat-box', materialName: '演示·250g食品盒',
  purchaseUnit: '个', baseUnit: '个', defaultPurchasePrice: 0.65, active: true,
};

function routeGet() {
  harness.get.mockImplementation(async (url: string) => {
    if (url.endsWith('/raw-material-types/active')) {
      return { success: true, data: [{ id: 'mat-box', name: '演示·250g食品盒', unit: '个', taxRate: null }] };
    }
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
        ElTableColumn: ElTableColumnStub,
        ElSelect: InputStub('ElSelect'),
        ElInputNumber: InputStub('ElInputNumber'),
        ElForm: PassThrough('ElForm'),
        ElFormItem: PassThrough('ElFormItem'),
        ElCard: PassThrough('ElCard'),
        CanvasAwareWrapper: PassThrough('CanvasAwareWrapper'),
      },
    },
  });
}

type Wrapper = ReturnType<typeof mountList>;

function byPlaceholder(wrapper: Wrapper, name: 'ElSelect' | 'ElInputNumber', placeholder: string) {
  const found = wrapper.findAllComponents({ name }).filter((c) => c.props('placeholder') === placeholder);
  // 阳性对照: 找不到控件时下面所有断言都在守空气。
  expect(found, `找不到 placeholder=「${placeholder}」的 ${name}`).toHaveLength(1);
  return found[0];
}

async function openWithBoxLine(wrapper: Wrapper): Promise<Vm> {
  const vm = wrapper.vm as unknown as Vm;
  vm.openCreateDialog();
  await flushPromises();
  vm.form.supplierId = SUPPLIER.id;
  await vm.onSupplierChange();
  await flushPromises();
  const relationSelect = byPlaceholder(wrapper, 'ElSelect', '选择该供应商的原料 (支持拼音)');
  relationSelect.vm.$emit('update:modelValue', 'rel-box');
  relationSelect.vm.$emit('change', 'rel-box');
  await flushPromises();
  vm.form.items[0].quantity = 10;
  await flushPromises();
  return vm;
}

async function typeTaxIncludedPrice(wrapper: Wrapper, value: number) {
  byPlaceholder(wrapper, 'ElInputNumber', '未配置含税采购价').vm.$emit('update:modelValue', value);
  await flushPromises();
}

async function pickTaxRate(wrapper: Wrapper, value: number) {
  byPlaceholder(wrapper, 'ElSelect', '请选择税率').vm.$emit('update:modelValue', value);
  await flushPromises();
}

function formTotals(wrapper: Wrapper) {
  return {
    untaxed: wrapper.find('[data-testid="po-form-untaxed"]').text(),
    tax: wrapper.find('[data-testid="po-form-tax"]').text(),
    taxIncluded: wrapper.find('[data-testid="po-form-tax-included"]').text(),
  };
}

describe('PROTO-DEMO-PO-TAX 采购单录入含税单价 → 提交载荷 (挂载真实 list.vue)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    route.query = {};
    routeGet();
    harness.listSupplierMaterials.mockResolvedValue([BOX_RELATION]);
    harness.listSupplierPurchaseSpecs.mockResolvedValue([]);
    harness.post.mockResolvedValue({ success: true, data: { id: 'po-new' } });
  });

  it('含税 2、税率 9%、数量 10: 提交的 unitPrice 是未税 1.8349, 表单合计 18.35 + 1.65 = 20.00', async () => {
    const wrapper = mountList();
    await flushPromises();
    const vm = await openWithBoxLine(wrapper);
    // 前提: 选完原料, 关系价 0.65 落在未税单价上; 档案没税率 ⇒ 含税价算不出, 不猜。
    expect(vm.form.items[0].unitPrice).toBe(0.65);
    expect(vm.form.items[0].taxIncludedUnitPrice).toBeNull();

    await typeTaxIncludedPrice(wrapper, 2);
    // 税率还没选: 未税价算不出, 不把含税 2 当未税存。
    expect(vm.form.items[0].unitPrice).toBeNull();
    expect(vm.form.items[0].priceSource).toBe('MISSING');

    await pickTaxRate(wrapper, 9);
    expect(vm.form.items[0].taxIncludedUnitPrice).toBe(2);
    expect(vm.form.items[0].unitPrice).toBe(1.8349);
    expect(vm.form.items[0].priceSource).toBe('MANUAL');
    expect(formTotals(wrapper)).toEqual({ untaxed: '18.35', tax: '1.65', taxIncluded: '20.00' });

    await vm.handleCreate();
    await flushPromises();
    const creates = harness.post.mock.calls.filter(([path]) => path === '/F006/purchase/orders');
    expect(creates).toHaveLength(1);
    const payload = creates[0][1] as { items: Array<Record<string, unknown>> };
    expect(payload.items).toHaveLength(1);
    expect(payload.items[0]).toMatchObject({ unitPrice: 1.8349, taxRate: 9, quantity: 10, priceUnit: '个' });
    // 含税单价只是界面录入字段, 不进载荷 (后端 unit_price 恒为未税)。
    expect(payload.items[0]).not.toHaveProperty('taxIncludedUnitPrice');
    wrapper.unmount();
  });

  it('改税率 9% → 13%: 含税单价不动, 未税单价/税额/含税合计全部重算, 提交的是新税率下的未税价', async () => {
    const wrapper = mountList();
    await flushPromises();
    const vm = await openWithBoxLine(wrapper);
    await pickTaxRate(wrapper, 9);
    await typeTaxIncludedPrice(wrapper, 2);
    expect(vm.form.items[0].unitPrice).toBe(1.8349);
    expect(formTotals(wrapper)).toEqual({ untaxed: '18.35', tax: '1.65', taxIncluded: '20.00' });

    await pickTaxRate(wrapper, 13);
    expect(vm.form.items[0].taxIncludedUnitPrice).toBe(2);
    expect(vm.form.items[0].unitPrice).toBe(1.7699);
    expect(formTotals(wrapper)).toEqual({ untaxed: '17.70', tax: '2.30', taxIncluded: '20.00' });

    await vm.handleCreate();
    await flushPromises();
    const creates = harness.post.mock.calls.filter(([path]) => path === '/F006/purchase/orders');
    expect(creates).toHaveLength(1);
    expect((creates[0][1] as { items: Array<Record<string, unknown>> }).items[0])
      .toMatchObject({ unitPrice: 1.7699, taxRate: 13 });
    wrapper.unmount();
  });
});
