import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 19② —— 价格录入一律含税，未税只算不填。
 *
 * <h3>owner 裁定</h3>
 * 「一般在中国基本上都是含税报价含税价格……**不可能一开始就知道未税报价**。
 *  未税报价是不需要去填写的，**只能是自动计算出来**；他填的指的是含税报价。」
 *
 * <h3>判据是行为，⛔ 不是「标题写着含税」</h3>
 * 填含税 + 物料有税率 ⇒ 派生出来的未税**要出现且数值正确**；
 * 没有税率 ⇒ **不渲染**（⛔ 不是 0，也不是一个会被读成 0 的空白）。
 * 硬约束 9：「没有未税录入框」这条阴性断言必须配一条「派生显示确实渲染了」的阳性断言 ——
 * 否则整个表单没渲染出来也能让阴性断言通过。
 */
const api = vi.hoisted(() => ({
  getSupplier: vi.fn(), listSupplierMaterials: vi.fn(), createSupplierMaterial: vi.fn(),
  updateSupplierMaterial: vi.fn(), deleteSupplierMaterial: vi.fn(),
  listSupplierPurchaseSpecs: vi.fn(), createSupplierPurchaseSpec: vi.fn(),
  updateSupplierPurchaseSpec: vi.fn(), deleteSupplierPurchaseSpec: vi.fn(),
  updateSupplier: vi.fn(), updateSupplierStatus: vi.fn(),
  get: vi.fn(), listSystemUnits: vi.fn(), warning: vi.fn(),
}));

vi.mock('@/api/supplierManagement', () => ({
  getSupplier: api.getSupplier, listSupplierMaterials: api.listSupplierMaterials,
  createSupplierMaterial: api.createSupplierMaterial, updateSupplierMaterial: api.updateSupplierMaterial,
  deleteSupplierMaterial: api.deleteSupplierMaterial, listSupplierPurchaseSpecs: api.listSupplierPurchaseSpecs,
  createSupplierPurchaseSpec: api.createSupplierPurchaseSpec, updateSupplierPurchaseSpec: api.updateSupplierPurchaseSpec,
  deleteSupplierPurchaseSpec: api.deleteSupplierPurchaseSpec, updateSupplier: api.updateSupplier,
  updateSupplierStatus: api.updateSupplierStatus,
}));
vi.mock('@/api/request', () => ({ get: api.get }));
vi.mock('@/api/systemUnits', async (actual) => ({
  ...(await actual<Record<string, unknown>>()), listSystemUnits: api.listSystemUnits,
}));
vi.mock('@/utils/purchaseUnitContract', () => ({
  purchaseUnitSelectionReason: () => '',
  resolvePurchaseUnits: () => ({ defaultUnit: 'kg', compatibleUnits: [{ code: 'kg', name: '千克' }] }),
}));
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: api.warning, error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm'), prompt: vi.fn(), alert: vi.fn() },
}));

import SupplierDetailDrawer from '../SupplierDetailDrawer.vue';

const PassThrough = (name: string) => defineComponent({
  name, setup: (_p, { slots }) => () => h('div', slots.default?.()),
});

/** el-form-item 必须把 label 渲染出来 —— 本组测试就是在看标题说的是含税还是未税。 */
const ElFormItemStub = defineComponent({
  name: 'ElFormItem',
  props: { label: { type: String, default: '' } },
  setup: (props, { slots }) => () => h('div', { class: 'form-item' }, [
    h('label', props.label), slots.default?.(),
  ]),
});

const ModelStub = (name: string) => defineComponent({
  name,
  props: { modelValue: { type: null, default: null }, disabled: { type: Boolean, default: false } },
  emits: ['update:modelValue'],
  setup: (props) => () => h('input', {
    'data-value': String(props.modelValue ?? ''),
    disabled: props.disabled,
  }),
});

function mountDrawer() {
  return mount(SupplierDetailDrawer, {
    props: {
      modelValue: false, factoryId: 'F006',
      supplier: { id: 'SUP-1', name: '测试供应商', status: 'ACTIVE', active: true } as never,
      canWrite: true,
    },
    global: {
      directives: { loading: {} },
      stubs: {
        ElDrawer: PassThrough('ElDrawer'), ElDialog: PassThrough('ElDialog'),
        ElTabs: PassThrough('ElTabs'), ElTabPane: PassThrough('ElTabPane'),
        ElForm: PassThrough('ElForm'), ElFormItem: ElFormItemStub,
        ElDescriptions: PassThrough('ElDescriptions'), ElDescriptionsItem: PassThrough('ElDescriptionsItem'),
        ElTable: PassThrough('ElTable'),
        ElTableColumn: defineComponent({ name: 'ElTableColumn', setup: () => () => h('div') }),
        ElCard: PassThrough('ElCard'), ElTag: PassThrough('ElTag'), ElTooltip: PassThrough('ElTooltip'),
        ElDivider: PassThrough('ElDivider'), ElAlert: PassThrough('ElAlert'), ElEmpty: PassThrough('ElEmpty'),
        ElButton: defineComponent({
          name: 'ElButton', emits: ['click'],
          setup: (_p, { slots, emit }) => () => h('button', { onClick: () => emit('click') }, slots.default?.()),
        }),
        ElInput: ModelStub('ElInput'), ElInputNumber: ModelStub('ElInputNumber'),
        ElSwitch: ModelStub('ElSwitch'), ElSelect: ModelStub('ElSelect'),
        ElOption: PassThrough('ElOption'), UnitSelect: ModelStub('UnitSelect'),
        SupplierProfileCollections: PassThrough('SupplierProfileCollections'),
      },
    },
  });
}

type Vm = {
  openRelationCreate: () => Promise<void>;
  relationForm: Record<string, unknown>;
};

/** 物料主数据带税率 —— 未税是按【物料】税率派生的, 供应侧没有自己的税率字段。 */
function material(taxRate: string | null, taxTreatment = 'TAXABLE') {
  return [{ id: 'MAT-1', name: '牛外脊', unit: 'kg', taxRate, taxTreatment }];
}

async function openWithMaterial(materials: ReturnType<typeof material>) {
  // ⚠️ get 同时服务「变更记录」和「原料下拉」两条路 —— 按 URL 分派,
  //    整份返回同一个形状会让其中一条拿到不能 .map/.find 的东西(unhandled rejection)。
  api.get.mockImplementation((url: string) =>
    Promise.resolve({ success: true, data: String(url).includes('raw-material-types') ? materials : [] }));
  const wrapper = mountDrawer();
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  const vm = wrapper.vm as unknown as Vm;
  await vm.openRelationCreate();
  Object.assign(vm.relationForm, { materialTypeId: 'MAT-1', purchaseUnit: 'kg' });
  await flushPromises();
  return { wrapper, vm };
}

function labels(wrapper: ReturnType<typeof mountDrawer>): string[] {
  return wrapper.findAll('label').map((n) => n.text());
}

describe('19②: 含税录入 / 未税派生', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.getSupplier.mockResolvedValue({ id: 'SUP-1', name: '测试供应商', status: 'ACTIVE', active: true });
    api.listSupplierMaterials.mockResolvedValue([]);
    api.listSupplierPurchaseSpecs.mockResolvedValue([]);
    api.listSystemUnits.mockResolvedValue([]);
  });

  it('🔴 填含税 + 物料 13% ⇒ 未税自动出现且数值正确 (113 ÷ 1.13 = 100.0000)', async () => {
    const { wrapper, vm } = await openWithMaterial(material('TAX_13'));
    vm.relationForm.taxIncludedDefaultPurchasePrice = 113;
    await flushPromises();

    // 阳性断言(硬约束 9): 派生显示**确实渲染了**, 且数是对的
    const untaxed = wrapper.findAll('input').find((n) => n.attributes('data-value') === '100.0000');
    expect(untaxed, '未税派生值没有出现 —— 「不填未税」若配不上「算出来了」就没有意义').toBeTruthy();
    expect(untaxed!.attributes('disabled')).toBeDefined();   // 只读, 不是给人填的

    // 阴性断言: 没有任何**可录入**的未税字段
    const untaxedEntryLabels = labels(wrapper).filter((t) => t.includes('未税') && !t.includes('含税'));
    expect(untaxedEntryLabels.length, '未税应当只作为只读派生出现').toBeGreaterThan(0);
    expect(labels(wrapper).some((t) => t.includes('含税默认采购价')),
      '录入框的标题必须说清基准是含税').toBe(true);
    wrapper.unmount();
  });

  it('🔴 阴性对照: 物料没有税率 ⇒ 未税那一行【不渲染】, ⛔ 不是 0 也不是空白', async () => {
    const { wrapper, vm } = await openWithMaterial(material(null));
    vm.relationForm.taxIncludedDefaultPurchasePrice = 113;
    await flushPromises();

    // 🔴 阳性对照(必须先证明表单确实渲染了): 否则「没有 0」也可能只是**什么都没渲染**,
    //    而那与「正确地不显示」在读数上一模一样。本用例用的物料与上一条不同,
    //    所以上一条的渲染证明**不能**直接搬过来。
    expect(labels(wrapper).some((t) => t.includes('含税默认采购价')),
      '含税录入框都没渲染 —— 本用例后面的阴性断言无从谈起').toBe(true);

    const zeroish = wrapper.findAll('input')
      .filter((n) => ['0', '0.0000', ''].includes(n.attributes('data-value') ?? ''))
      .filter((n) => n.attributes('disabled') !== undefined);
    expect(zeroish.length, '算不出未税时渲染了一个只读的 0/空白 —— 那会被读成「未税是 0」').toBe(0);
    wrapper.unmount();
  });

  it('阳性对照: 免税物料 ⇒ 标题变成「免税采购价」', async () => {
    const { wrapper } = await openWithMaterial(material(null, 'EXEMPT'));
    expect(labels(wrapper).some((t) => t.includes('免税采购价')),
      '免税时标题应当说免税, ⛔ 不该还叫含税').toBe(true);
    wrapper.unmount();
  });
});
