import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 缺陷 35 —— 列头不说口径，值却有口径。
 *
 * <h3>生产实测</h3>
 * owner 在表单里填**含税 2.00**，回列表看到 **`CNY 1.77/个`**；冰水填 1.00 → 列表 0.89。
 * ⇒ 长得像「我填的数被系统改了」。系统其实算对了 —— 列表渲染的是**未税**那个字段，
 * 而列头「默认采购价」**一个口径都没说**。
 *
 * <h3>这是「改共享结构只改了一部分」的实例</h3>
 * #465 把三个**录入口**都标了含税，但这个字段有 **5 个面向用户的出口**（3 录入 + 2 列表）。
 * ⛔ 判据必须覆盖显示侧，不只是录入侧。
 *
 * <h3>三态</h3>
 * ① 含税有值 → 显示含税；② 只有未税(存量) → 显示并**标注基准未知**；③ 都没有 → 未设置。
 * 🔴 ② 尤其重要：把一个基准不明的数摆在「含税」列头下面，是把同一个谎反过来说一遍。
 */
const api = vi.hoisted(() => ({
  getSupplier: vi.fn(), listSupplierMaterials: vi.fn(), createSupplierMaterial: vi.fn(),
  updateSupplierMaterial: vi.fn(), deleteSupplierMaterial: vi.fn(),
  listSupplierPurchaseSpecs: vi.fn(), createSupplierPurchaseSpec: vi.fn(),
  updateSupplierPurchaseSpec: vi.fn(), deleteSupplierPurchaseSpec: vi.fn(),
  updateSupplier: vi.fn(), updateSupplierStatus: vi.fn(),
  get: vi.fn(), listSystemUnits: vi.fn(),
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
  resolvePurchaseUnits: () => ({ defaultUnit: 'kg', compatibleUnits: [] }),
}));
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm'), prompt: vi.fn(), alert: vi.fn() },
}));

import SupplierDetailDrawer from '../SupplierDetailDrawer.vue';

const ROWS_KEY = 'stub-rows';

/** ⚠️ 必须真的把行渲染出来 —— 否则「没看到 1.77」和「压根没渲染」是同一个读数。 */
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
  props: { label: { type: String, default: '' } },
  setup(props, { slots }) {
    const rows = inject<ComputedRef<unknown[]>>(ROWS_KEY, computed(() => []));
    return () => h('div', { 'data-col': props.label },
      rows.value.map((row) => h('div', { class: 'cell' }, slots.default?.({ row }))));
  },
});
const PassThrough = (name: string) => defineComponent({
  name, setup: (_p, { slots }) => () => h('div', slots.default?.()),
});
/** el-tooltip 要把默认插槽渲染出来, 否则「基准未知」那段看不见。 */
const ElTooltipStub = defineComponent({
  name: 'ElTooltip',
  props: { content: { type: String, default: '' } },
  setup: (props, { slots }) => () => h('span', { 'data-tip': props.content }, slots.default?.()),
});

function mountDrawer() {
  return mount(SupplierDetailDrawer, {
    props: {
      modelValue: false, factoryId: 'F006',
      supplier: { id: 'SUP-1', name: 'S', status: 'ACTIVE', active: true } as never,
      canWrite: true,
    },
    global: {
      directives: { loading: {} },
      stubs: {
        ElDrawer: PassThrough('ElDrawer'), ElDialog: PassThrough('ElDialog'),
        ElTabs: PassThrough('ElTabs'), ElTabPane: PassThrough('ElTabPane'),
        ElForm: PassThrough('ElForm'), ElFormItem: PassThrough('ElFormItem'),
        ElDescriptions: PassThrough('ElDescriptions'), ElDescriptionsItem: PassThrough('ElDescriptionsItem'),
        ElTable: ElTableStub, ElTableColumn: ElTableColumnStub,
        ElTooltip: ElTooltipStub,
        ElCard: PassThrough('ElCard'), ElTag: PassThrough('ElTag'),
        ElDivider: PassThrough('ElDivider'), ElAlert: PassThrough('ElAlert'), ElEmpty: PassThrough('ElEmpty'),
        ElSkeleton: PassThrough('ElSkeleton'),
        ElButton: defineComponent({ name: 'ElButton', setup: (_p, { slots }) => () => h('button', slots.default?.()) }),
        ElInput: PassThrough('ElInput'), ElInputNumber: PassThrough('ElInputNumber'),
        ElSwitch: PassThrough('ElSwitch'), ElSelect: PassThrough('ElSelect'), ElOption: PassThrough('ElOption'),
        UnitSelect: PassThrough('UnitSelect'), SupplierProfileCollections: PassThrough('SupplierProfileCollections'),
      },
    },
  });
}

function relation(over: Record<string, unknown>) {
  return {
    id: 'rel-1', materialTypeId: 'MAT-1', materialName: '牛外脊', purchaseUnit: 'kg',
    currency: 'CNY', active: true, version: 0,
    defaultPurchasePrice: null, taxIncludedDefaultPurchasePrice: null, ...over,
  };
}

async function openWith(rows: unknown[]) {
  api.listSupplierMaterials.mockResolvedValue(rows);
  const wrapper = mountDrawer();
  await wrapper.setProps({ modelValue: true });
  await flushPromises();
  return wrapper;
}

function priceColumnText(wrapper: ReturnType<typeof mountDrawer>): string {
  const col = wrapper.findAll('[data-col]').find((n) => n.attributes('data-col')?.includes('默认采购价'));
  return col ? col.text() : '';
}

describe('缺陷 35: 供应原料列表的价格列必须说清口径', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.getSupplier.mockResolvedValue({ id: 'SUP-1', name: 'S', status: 'ACTIVE', active: true });
    api.listSupplierPurchaseSpecs.mockResolvedValue([]);
    api.listSystemUnits.mockResolvedValue([]);
    api.get.mockResolvedValue({ success: true, data: [] });
  });

  it('🔴 ① 含税有值 ⇒ 列表显示的就是【填进去的那个数】, ⛔ 不是换算后的未税', async () => {
    // 生产上 owner 填 2.00 看到 1.77 —— 这条就是钉那件事不再发生。
    const wrapper = await openWith([relation({
      taxIncludedDefaultPurchasePrice: 2, defaultPurchasePrice: 1.7699,
    })]);

    const text = priceColumnText(wrapper);
    expect(text, '价格列没渲染出来 —— 后面的断言无从谈起').not.toBe('');
    expect(text).toContain('2.00');
    expect(text, '列表又把未税值摆出来了 —— 用户会以为自己填的数被改了').not.toContain('1.77');
    wrapper.unmount();
  });

  it('列头必须写明口径 (含税)', async () => {
    const wrapper = await openWith([relation({ taxIncludedDefaultPurchasePrice: 2 })]);
    const cols = wrapper.findAll('[data-col]').map((n) => n.attributes('data-col') ?? '');
    expect(cols.some((c) => c.includes('含税默认采购价')),
      '列头仍然不说口径 —— 值有口径而标题没有, 正是缺陷 35 的形状').toBe(true);
    wrapper.unmount();
  });

  it('🔴 ② 存量只有未税 ⇒ 显示出来但标注「基准未知」, ⛔ 不能冒充含税', async () => {
    const wrapper = await openWith([relation({ defaultPurchasePrice: 50 })]);

    const text = priceColumnText(wrapper);
    expect(text).toContain('50.00');          // 阳性: 值仍然看得见, 不是藏起来
    expect(text, '把一个基准不明的数摆在「含税」列头下面 = 把同一个谎反过来说一遍')
      .toContain('基准未知');
    wrapper.unmount();
  });

  it('③ 两个都没有 ⇒ 未设置 (⛔ 不是 0)', async () => {
    const wrapper = await openWith([relation({})]);
    const text = priceColumnText(wrapper);
    expect(text).toContain('未设置');
    expect(text, '空价格渲染成 0 会被读成「这家就是 0 元」').not.toContain('0.00');
    wrapper.unmount();
  });
});
