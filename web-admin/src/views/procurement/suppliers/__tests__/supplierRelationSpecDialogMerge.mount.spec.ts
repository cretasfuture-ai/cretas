import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 缺陷 16 —— 供应原料关系的「新增」把采购规格内联在同一表单，「编辑」却拆成
 * 两个弹窗 + 两个按钮。Owner 说了两次「放一起好了」。
 *
 * <h3>判据（硬约束 9：每条"两个按钮消失了"都要配一条"合并后的动作确实渲染并打开了统一弹窗"）</h3>
 * ① 操作列只剩一个按钮（不再是「采购规格」+「编辑」两个）；
 * ② 点它之后，同一个 `<el-dialog>` 里既有关系表单又有规格管理区（一个弹窗，不是两个）；
 * ③ 点击那一刻就把该关系的采购规格加载出来（旧「采购规格」按钮的能力，合并后不能丢）；
 * ④ 供应商暂停/无写权限时，按钮变成「查看」但仍然可点、仍然能看到规格（旧行为：
 *    「采购规格」按钮从不受 canWrite/isActive 限制）——只挡「能不能保存」，不挡「能不能看」；
 * ⑤ 停用规格这个能力原样保留；
 * ⑥ 起订量/交期只在供应关系层录入一次 —— 采购规格表单里不再有这两个字段（owner 裁定 3）。
 */
const api = vi.hoisted(() => ({
  getSupplier: vi.fn(), listSupplierMaterials: vi.fn(), createSupplierMaterial: vi.fn(),
  updateSupplierMaterial: vi.fn(), deleteSupplierMaterial: vi.fn(),
  listSupplierPurchaseSpecs: vi.fn(), createSupplierPurchaseSpec: vi.fn(),
  updateSupplierPurchaseSpec: vi.fn(), deleteSupplierPurchaseSpec: vi.fn(),
  updateSupplier: vi.fn(), updateSupplierStatus: vi.fn(),
  get: vi.fn(), listSystemUnits: vi.fn(), confirm: vi.fn(),
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
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: api.confirm, prompt: vi.fn(), alert: vi.fn() },
}));

import SupplierDetailDrawer from '../SupplierDetailDrawer.vue';

const ROWS_KEY = 'stub-rows';

/** 与缺陷 35 测试同一套手法: 真的把行渲染出来, 否则「没看到按钮」和「压根没渲染」是同一读数。 */
const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide(ROWS_KEY, computed(() => props.data));
    return () => h('div', { class: 'stub-table' }, slots.default?.());
  },
});
const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  props: { label: { type: String, default: '' } },
  setup(props, { slots }) {
    const rows = inject<ComputedRef<unknown[]>>(ROWS_KEY, computed(() => []));
    return () => h('div', { 'data-col': props.label },
      rows.value.map((row) => h('div', { class: 'cell' }, slots.default?.({ row }))));
  },
});
/** ⚠️ 只渲染 default 插槽的 PassThrough 会把 `<template #footer>` 吃掉 —— 本组要看 footer, 必须单独渲染。 */
const ElDialogStub = defineComponent({
  name: 'ElDialog',
  props: { title: { type: String, default: '' } },
  setup(props, { slots }) {
    return () => h('div', { class: 'stub-dialog', 'data-title': props.title },
      [slots.default?.(), slots.footer?.()]);
  },
});
const ElButtonStub = defineComponent({
  name: 'ElButton',
  emits: ['click'],
  setup(_p, { slots, emit }) {
    return () => h('button', { onClick: () => emit('click') }, slots.default?.());
  },
});
const ModelStub = (name: string) => defineComponent({
  name,
  props: { modelValue: { type: null, default: null }, disabled: { type: Boolean, default: false } },
  emits: ['update:modelValue'],
  setup(props) {
    return () => h('input', { 'data-value': String(props.modelValue ?? ''), disabled: props.disabled });
  },
});
const PassThrough = (name: string) => defineComponent({
  name, setup: (_p, { slots }) => () => h('div', slots.default?.()),
});

function mountDrawer(canWrite: boolean) {
  return mount(SupplierDetailDrawer, {
    props: {
      modelValue: false, factoryId: 'F006',
      supplier: { id: 'SUP-1', name: '六膳门', status: 'ACTIVE', active: true } as never,
      canWrite,
    },
    global: {
      directives: { loading: {} },
      stubs: {
        ElDrawer: PassThrough('ElDrawer'), ElDialog: ElDialogStub,
        ElTabs: PassThrough('ElTabs'), ElTabPane: PassThrough('ElTabPane'),
        ElForm: PassThrough('ElForm'), ElFormItem: PassThrough('ElFormItem'),
        ElDescriptions: PassThrough('ElDescriptions'), ElDescriptionsItem: PassThrough('ElDescriptionsItem'),
        ElTable: ElTableStub, ElTableColumn: ElTableColumnStub,
        ElCard: PassThrough('ElCard'), ElTag: PassThrough('ElTag'), ElTooltip: PassThrough('ElTooltip'),
        ElDivider: PassThrough('ElDivider'), ElAlert: PassThrough('ElAlert'), ElEmpty: PassThrough('ElEmpty'),
        ElSkeleton: PassThrough('ElSkeleton'),
        ElButton: ElButtonStub,
        ElInput: ModelStub('ElInput'), ElInputNumber: ModelStub('ElInputNumber'),
        ElSwitch: ModelStub('ElSwitch'), ElSelect: ModelStub('ElSelect'), ElOption: PassThrough('ElOption'),
        UnitSelect: ModelStub('UnitSelect'), SupplierProfileCollections: PassThrough('SupplierProfileCollections'),
      },
    },
  });
}

const RELATION = {
  id: 'rel-1', materialTypeId: 'MAT-1', materialName: 'WL026', supplierMaterialCode: 'S-1',
  defaultPurchasePrice: null, taxIncludedDefaultPurchasePrice: 12.5,
  currency: 'CNY', purchaseUnit: 'kg', baseUnit: 'kg',
  minOrderQuantity: 5, leadTimeDays: 3, preferred: false, active: true, version: 3,
};

const SPEC_ROW = {
  id: 'spec-1', name: '10kg/箱', purchasePackageUnit: 'box', inventoryBaseUnit: 'kg',
  factor: 10, quotedPrice: null, taxIncludedQuotedPrice: 100, currency: 'CNY',
  defaultSpec: false, active: true, version: 2,
};

async function openWith(canWrite: boolean, supplierActive = true) {
  api.getSupplier.mockResolvedValue({
    id: 'SUP-1', name: '六膳门', status: supplierActive ? 'ACTIVE' : 'INACTIVE', active: supplierActive,
  });
  api.listSupplierMaterials.mockResolvedValue([RELATION]);
  api.listSupplierPurchaseSpecs.mockResolvedValue([SPEC_ROW]);
  api.listSystemUnits.mockResolvedValue([]);
  api.get.mockResolvedValue({ success: true, data: [] });
  api.confirm.mockResolvedValue('confirm');
  const wrapper = mountDrawer(canWrite);
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

function operationCellButtons(wrapper: ReturnType<typeof mountDrawer>) {
  const col = wrapper.findAll('[data-col="操作"]')[0];
  return col ? col.findAll('button').map((b) => b.text()) : [];
}

describe('缺陷 16: 供应原料关系「新增」「编辑」合并成同一个弹窗形状', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
  });

  it('① 操作列只剩一个按钮 —— ⛔「采购规格」不再单独出现', async () => {
    const wrapper = await openWith(true);

    const labels = operationCellButtons(wrapper);
    expect(labels, '操作列没渲染出来 —— 后面的断言无从谈起').not.toEqual([]);
    expect(labels).not.toContain('采购规格');
    expect(labels.filter((t) => t.trim() === '编辑').length,
      '应当只有一个"编辑"按钮, 不是"采购规格"+"编辑"两个').toBe(1);
    // 停用是另一件事(缺陷 16 没有要求合并它), 原样保留。
    expect(labels).toContain('停用');
    wrapper.unmount();
  });

  it('② 阳性对照: 点"编辑"打开的是【同一个】弹窗, 而且它同时加载了这条关系的采购规格', async () => {
    const wrapper = await openWith(true);

    // 合并之前是 2 个 <el-dialog>(关系 + 采购规格); 合并之后必须只剩 1 个。
    expect(wrapper.findAllComponents({ name: 'ElDialog' }).length,
      '弹窗数量不是 1 —— 说明两个弹窗没有真的合并成一个').toBe(1);

    const editButton = wrapper.findAll('[data-col="操作"] button')
      .find((b) => b.text().trim() === '编辑');
    expect(editButton, '找不到"编辑"按钮').toBeTruthy();

    await editButton!.trigger('click');
    await flushPromises();

    // 旧"采购规格"按钮的能力: 点开就把规格列表加载出来。
    expect(api.listSupplierPurchaseSpecs,
      '点"编辑"没有一并加载采购规格 —— 两个弹窗没有真的合成一个').toHaveBeenCalledWith(
      'F006', 'SUP-1', 'rel-1',
    );

    const vm = wrapper.vm as unknown as { relationDialogVisible: boolean; relationEditingId: string };
    expect(vm.relationDialogVisible).toBe(true);
    expect(vm.relationEditingId).toBe('rel-1');

    // 同一个弹窗里, 关系内容和规格内容都在(而不是分成两个弹窗各管一半)。
    const dialogText = wrapper.find('.stub-dialog').text();
    expect(dialogText).toContain('采购规格');
    wrapper.unmount();
  });

  it('③ 供应商暂停合作(或无写权限)时: 按钮变"查看", 仍然可点、仍然能看到规格', async () => {
    // 旧的"采购规格"按钮从不受 canWrite/isActive 限制 —— 合并后这个能力不能丢。
    const wrapper = await openWith(true, /* supplierActive */ false);

    const labels = operationCellButtons(wrapper);
    expect(labels).not.toContain('编辑');
    expect(labels.filter((t) => t.trim() === '查看').length).toBe(1);
    // 停用按钮此时不该出现(它仍然要求 canWrite && isActive)。
    expect(labels).not.toContain('停用');

    const viewButton = wrapper.findAll('[data-col="操作"] button')
      .find((b) => b.text().trim() === '查看');
    await viewButton!.trigger('click');
    await flushPromises();

    expect(api.listSupplierPurchaseSpecs,
      '暂停合作时"查看"按钮应当仍能看到规格 —— 这是合并前"采购规格"按钮就有的能力')
      .toHaveBeenCalledWith('F006', 'SUP-1', 'rel-1');

    // 只读态: 表单输入被禁用, 且 footer 没有"保存"按钮。
    const disabledInputs = wrapper.findAll('input[disabled]');
    expect(disabledInputs.length, '查看态下关系表单应当整体只读').toBeGreaterThan(0);
    const footerLabels = wrapper.findAll('.stub-dialog button').map((b) => b.text().trim());
    expect(footerLabels).not.toContain('保存');
    expect(footerLabels).toContain('关闭');
    wrapper.unmount();
  });

  it('④ 停用规格的能力原样保留(合并没有削弱它)', async () => {
    const wrapper = await openWith(true);
    const vm = wrapper.vm as unknown as {
      openRelationEdit: (row: unknown) => Promise<void>;
      removePurchaseSpec: (row: unknown) => Promise<void>;
    };

    await vm.openRelationEdit(RELATION);
    await flushPromises();
    await vm.removePurchaseSpec(SPEC_ROW);
    await flushPromises();

    expect(api.confirm).toHaveBeenCalled();
    expect(api.deleteSupplierPurchaseSpec).toHaveBeenCalledWith(
      'F006', 'SUP-1', 'rel-1', 'spec-1', 2,
    );
    wrapper.unmount();
  });

  it('⑤ 起订量/交期只在供应关系层录入一次 —— 采购规格表单里不再有这两个字段', async () => {
    const wrapper = await openWith(true);
    const vm = wrapper.vm as unknown as {
      relationForm: Record<string, unknown>;
      specForm: Record<string, unknown>;
      inlineSpecForm: Record<string, unknown>;
    };

    // 阳性: 关系层仍然有(旧行为不能丢)。
    expect('minOrderQuantity' in vm.relationForm).toBe(true);
    expect('leadTimeDays' in vm.relationForm).toBe(true);

    // 阴性: 规格层(独立规格表单 + 内联新建规格表单)都不再有 —— owner 裁定 3。
    expect('minOrderQuantity' in vm.specForm,
      '规格表单不该再有起订量 —— 与关系层重复且零读者').toBe(false);
    expect('leadTimeDays' in vm.specForm).toBe(false);
    expect('minOrderQuantity' in vm.inlineSpecForm).toBe(false);
    expect('leadTimeDays' in vm.inlineSpecForm).toBe(false);
    wrapper.unmount();
  });

  it('⑥ 新增路径没有退化: 规格子表单仍然和关系表单在同一个弹窗里', async () => {
    const wrapper = await openWith(true);
    const vm = wrapper.vm as unknown as { openRelationCreate: () => Promise<void> };

    await vm.openRelationCreate();
    await flushPromises();

    expect(wrapper.findAllComponents({ name: 'ElDialog' }).length).toBe(1);
    expect(wrapper.find('.stub-dialog').text()).toContain('采购规格');
    wrapper.unmount();
  });
});
