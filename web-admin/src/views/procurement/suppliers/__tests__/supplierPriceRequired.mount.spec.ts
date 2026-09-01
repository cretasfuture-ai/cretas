import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 缺陷 17 —— 价格必填，但只在价格被动过时拦。
 *
 * <h3>为什么必须是行为测试，⛔ 不能是源码闸</h3>
 * 本文件三个 `<el-form>` 里，采购关系(:188)/内联规格(:233) **没有** `:model`/`:rules`/`ref`，
 * 独立规格(:290)只有 `:model`，而全文件**没有任何地方调用 `formRef.validate()`**。
 * ⇒ `el-form-item` 上的 `required` **只画一个红星，不拦任何东西**。
 * 「红星出现了」不是证据；唯一的证据是**留空点保存，接口没被调用**。
 *
 * <h3>阴性对照守的是 owner 那条裁定</h3>
 * LIUSHANMEN 有 4 条价格为空的存量关系。若把「一律必填」做进去，想把其中一条从启用改成停用的人
 * 就得先编一个价格出来 —— 而编出来的价格比空着更糟（空的还能被发现，编的会一直安静地错下去）。
 * 所以 {@link 编辑既有关系时不动价格照样存得下去} 与正向断言同等重要。
 */
const api = vi.hoisted(() => ({
  getSupplier: vi.fn(),
  listSupplierMaterials: vi.fn(),
  createSupplierMaterial: vi.fn(),
  updateSupplierMaterial: vi.fn(),
  deleteSupplierMaterial: vi.fn(),
  listSupplierPurchaseSpecs: vi.fn(),
  createSupplierPurchaseSpec: vi.fn(),
  updateSupplierPurchaseSpec: vi.fn(),
  deleteSupplierPurchaseSpec: vi.fn(),
  updateSupplier: vi.fn(),
  updateSupplierStatus: vi.fn(),
  warning: vi.fn(),
  get: vi.fn(),
  listSystemUnits: vi.fn(),
}));

vi.mock('@/api/supplierManagement', () => ({
  getSupplier: api.getSupplier,
  listSupplierMaterials: api.listSupplierMaterials,
  createSupplierMaterial: api.createSupplierMaterial,
  updateSupplierMaterial: api.updateSupplierMaterial,
  deleteSupplierMaterial: api.deleteSupplierMaterial,
  listSupplierPurchaseSpecs: api.listSupplierPurchaseSpecs,
  createSupplierPurchaseSpec: api.createSupplierPurchaseSpec,
  updateSupplierPurchaseSpec: api.updateSupplierPurchaseSpec,
  deleteSupplierPurchaseSpec: api.deleteSupplierPurchaseSpec,
  updateSupplier: api.updateSupplier,
  updateSupplierStatus: api.updateSupplierStatus,
}));
vi.mock('@/api/request', () => ({ get: api.get }));
// ⚠️ 只覆盖取数那一个 —— 整份替换会把 findDuplicateUnit 之类的同模块导出弄丢,
//    那种失败长得像"组件坏了", 其实是桩不完整。
vi.mock('@/api/systemUnits', async (actual) => ({
  ...(await actual<Record<string, unknown>>()),
  listSystemUnits: api.listSystemUnits,
}));
/**
 * 🔴 采购单位契约必须桩掉, 否则 saveRelation 会先被 relationUnitReason 挡住 ——
 *    而"被单位闸挡住"和"被价格闸挡住"在 `createSupplierMaterial 没被调用` 这个读数上
 *    长得一模一样。实测: 第一版没桩它, 三条正向断言全绿, 但绿的原因是单位闸,
 *    价格闸一次都没执行到。阳性对照(合法价格能存下去)才把这件事暴露出来。
 */
vi.mock('@/utils/purchaseUnitContract', () => ({
  purchaseUnitSelectionReason: () => '',
  resolvePurchaseUnits: () => ({ defaultUnit: 'kg', compatibleUnits: [{ code: 'kg', name: '千克' }] }),
}));
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: api.warning, error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm'), prompt: vi.fn(), alert: vi.fn() },
}));

import SupplierDetailDrawer from '../SupplierDetailDrawer.vue';

const RELATION = {
  id: 'rel-1',
  materialTypeId: 'MAT-1',
  materialName: 'WL026',
  supplierMaterialCode: 'S-1',
  // 🔴 LIUSHANMEN 存量的形状: 价格是空的
  defaultPurchasePrice: null,
  currency: 'CNY',
  purchaseUnit: 'kg',
  baseUnit: 'kg',
  minOrderQuantity: null,
  leadTimeDays: null,
  preferred: false,
  active: true,
  version: 3,
};

/** 只做一件事: 把 slot 渲染出来。⛔ 不模仿 Element Plus 的任何别的行为。 */
const PassThrough = (name: string) => defineComponent({
  name,
  setup(_p, { slots }) {
    return () => h('div', slots.default?.());
  },
});

const ElButtonStub = defineComponent({
  name: 'ElButton',
  emits: ['click'],
  setup(_p, { slots, emit }) {
    return () => h('button', { onClick: () => emit('click') }, slots.default?.());
  },
});

/** 受控输入桩 —— 必须真的 emit，否则"改了价格"这个动作根本没发生。 */
const ModelStub = (name: string) => defineComponent({
  name,
  props: { modelValue: { type: null, default: null } },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    return () => h('input', {
      'data-model': String(props.modelValue),
      onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value),
    });
  },
});

/**
 * ⚠️ 必须"先关后开": 组件是靠 `watch(() => props.modelValue)` 去 loadDetail 的,
 * 而那个 watch **没有 immediate** —— 直接以 modelValue:true 挂载, 它一次都不会触发,
 * `detail` 停在 null ⇒ `isActive` 恒 false ⇒ openRelationEdit 第一行就 return。
 * 实测这会让编辑态的两条用例以"接口没被调用"的形式失败, 而那与价格闸毫无关系。
 */
async function openDrawer() {
  const wrapper = mountDrawer();
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

function mountDrawer() {
  return mount(SupplierDetailDrawer, {
    props: {
      modelValue: false,
      factoryId: 'F006',
      supplier: { id: 'SUP-1', name: '上海鹃城', status: 'ACTIVE', active: true } as never,
      canWrite: true,
    },
    global: {
      directives: { loading: {} },
      stubs: {
        ElDrawer: PassThrough('ElDrawer'),
        ElDialog: PassThrough('ElDialog'),
        ElTabs: PassThrough('ElTabs'),
        ElTabPane: PassThrough('ElTabPane'),
        ElForm: PassThrough('ElForm'),
        ElFormItem: PassThrough('ElFormItem'),
        ElDescriptions: PassThrough('ElDescriptions'),
        ElDescriptionsItem: PassThrough('ElDescriptionsItem'),
        ElTable: PassThrough('ElTable'),
        // ⛔ 不渲染列的 default slot: 那些 slot 都 destructure `{ row }`,
        //    不喂行就会 TypeError。本组测试驱动的是保存函数, 不需要表格行。
        ElTableColumn: defineComponent({ name: 'ElTableColumn', setup: () => () => h('div') }),
        ElCard: PassThrough('ElCard'),
        ElTag: PassThrough('ElTag'),
        ElTooltip: PassThrough('ElTooltip'),
        ElDivider: PassThrough('ElDivider'),
        ElAlert: PassThrough('ElAlert'),
        ElEmpty: PassThrough('ElEmpty'),
        ElButton: ElButtonStub,
        ElInput: ModelStub('ElInput'),
        ElInputNumber: ModelStub('ElInputNumber'),
        ElSwitch: ModelStub('ElSwitch'),
        ElSelect: ModelStub('ElSelect'),
        ElOption: PassThrough('ElOption'),
        UnitSelect: ModelStub('UnitSelect'),
        SupplierProfileCollections: PassThrough('SupplierProfileCollections'),
      },
    },
  });
}

type Vm = {
  relationDialogVisible: boolean;
  relationEditingId: string;
  relationForm: Record<string, unknown>;
  inlineSpecEnabled: boolean;
  inlineSpecForm: Record<string, unknown>;
  saveRelation: () => Promise<void>;
  openRelationCreate: () => Promise<void>;
  openRelationEdit: (row: unknown) => Promise<void>;
  materialOptions: Array<unknown>;
  openSpecManager: (row: unknown) => Promise<void>;
  editPurchaseSpec: (row: unknown) => void;
  savePurchaseSpec: () => Promise<void>;
  showNewSpecForm: boolean;
  specEditingId: string;
  specForm: Record<string, unknown>;
};

const SPEC = {
  id: 'spec-1',
  name: '10kg/箱',
  purchasePackageUnit: 'box',
  inventoryBaseUnit: 'kg',
  factor: 10,
  quotedPrice: null,          // 存量空报价
  currency: 'CNY',
  minOrderQuantity: null,
  leadTimeDays: null,
  defaultSpec: false,
  active: true,
  version: 2,
};

describe('缺陷 17: 供应原料关系 / 采购规格的价格', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.getSupplier.mockResolvedValue({
      id: 'SUP-1', name: '上海鹃城', status: 'ACTIVE', active: true,
    });
    api.listSupplierMaterials.mockResolvedValue([RELATION]);
    api.listSupplierPurchaseSpecs.mockResolvedValue([]);
    api.listSystemUnits.mockResolvedValue([]);
    // ⚠️ data 必须是数组: 组件对它做 .map / .find。给个对象会变成 unhandled rejection ——
    //    断言照样绿, 但 vitest 退出码是 1(在 CI 上就是红的)。桩不完整不等于产品有问题。
    api.get.mockResolvedValue({ success: true, data: [] });
    api.createSupplierMaterial.mockResolvedValue({ ...RELATION, id: 'rel-new' });
    api.updateSupplierMaterial.mockResolvedValue({ ...RELATION });
    api.createSupplierPurchaseSpec.mockResolvedValue({});
    api.updateSupplierPurchaseSpec.mockResolvedValue({});
  });

  it('新增关系时价格留空 -> 保存被拦, ⛔ 接口一次都没被调用', async () => {
    const wrapper = await openDrawer();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openRelationCreate();
    Object.assign(vm.relationForm, { materialTypeId: 'MAT-1', purchaseUnit: 'kg' });
    vm.relationForm.defaultPurchasePrice = null;   // 用户没填价格

    await vm.saveRelation();
    await flushPromises();

    expect(api.createSupplierMaterial,
      '价格留空却把关系建出去了 —— 红星不拦东西，这正是本条要修的').not.toHaveBeenCalled();
    expect(api.warning).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('🔴 阴性对照: 编辑价格为空的存量关系, 不动价格照样存得下去', async () => {
    // owner 裁定的那一条 —— 只想把它从启用改成停用的人, 不该被逼着先编一个价格出来。
    const wrapper = await openDrawer();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openRelationEdit(RELATION);
    vm.relationForm.active = false;                // 只改了启用状态
    expect(vm.relationForm.defaultPurchasePrice).toBeNull();   // 价格仍是空的

    await vm.saveRelation();
    await flushPromises();

    expect(api.updateSupplierMaterial,
      '把存量空价格的关系锁死了 —— 用户只能先编一个假价格才能改别的字段').toHaveBeenCalled();
    wrapper.unmount();
  });

  it('编辑时把价格动成 0 -> 被拦 (后端判据也是 > 0, 前端放行只会被 400 打回)', async () => {
    const wrapper = await openDrawer();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openRelationEdit(RELATION);
    vm.relationForm.defaultPurchasePrice = 0;      // 动过了, 而且是非法值

    await vm.saveRelation();
    await flushPromises();

    expect(api.updateSupplierMaterial).not.toHaveBeenCalled();
    expect(api.warning).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('编辑时把价格填成合法值 -> 正常保存 (阳性对照: 这条路本身是通的)', async () => {
    const wrapper = await openDrawer();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openRelationEdit(RELATION);
    vm.relationForm.defaultPurchasePrice = 12.5;

    await vm.saveRelation();
    await flushPromises();

    expect(api.updateSupplierMaterial).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('🔴 新增路径的内联规格报价留空 -> 被拦 (空价格最主要的来源)', async () => {
    const wrapper = await openDrawer();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openRelationCreate();
    Object.assign(vm.relationForm, {
      materialTypeId: 'MAT-1', purchaseUnit: 'kg', defaultPurchasePrice: 10,
    });
    vm.inlineSpecEnabled = true;
    Object.assign(vm.inlineSpecForm, {
      name: '10kg/箱', purchasePackageUnit: 'box', factor: 10, quotedPrice: null,
    });

    await vm.saveRelation();
    await flushPromises();

    expect(api.createSupplierMaterial,
      '内联规格报价为空却把关系+规格一起建出去了').not.toHaveBeenCalled();
    expect(api.createSupplierPurchaseSpec).not.toHaveBeenCalled();
    expect(api.warning).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('独立规格(编辑路径): 新增规格报价留空 -> 被拦', async () => {
    const wrapper = await openDrawer();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openSpecManager(RELATION);
    vm.showNewSpecForm = true;
    Object.assign(vm.specForm, {
      name: '20kg/袋', purchasePackageUnit: 'bag', factor: 20, quotedPrice: null,
    });

    await vm.savePurchaseSpec();
    await flushPromises();

    expect(api.createSupplierPurchaseSpec).not.toHaveBeenCalled();
    expect(api.warning).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('🔴 阴性对照: 编辑报价为空的存量规格, 不动报价照样存得下去', async () => {
    const wrapper = await openDrawer();
    const vm = wrapper.vm as unknown as Vm;

    await vm.openSpecManager(RELATION);
    vm.editPurchaseSpec(SPEC);
    vm.specForm.active = false;                 // 只改启用状态
    expect(vm.specForm.quotedPrice).toBeNull();  // 报价仍是空的

    await vm.savePurchaseSpec();
    await flushPromises();

    expect(api.updateSupplierPurchaseSpec,
      '把存量空报价的规格锁死了').toHaveBeenCalled();
    wrapper.unmount();
  });
});
