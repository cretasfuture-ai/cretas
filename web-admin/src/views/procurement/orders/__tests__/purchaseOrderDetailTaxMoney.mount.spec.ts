/**
 * PROTO-DEMO-PO-TAX (2026-11-18, F006 PO-20261118-0001): 采购单详情的金额要和销售订单同一种写法,
 * 明确写出【未税】【税额】【含税】, 三个数对得上。
 *
 * 原来: 单头只有「总金额 ¥20.18 / 税额 ¥1.82」, 行上只有「含税单价 2.2 / 小计 ¥20.18」——
 * 20.18 其实是未税 (totalAmount = Σ 行未税), 但页面没说; 用户拿 10 × 2.2 = 22 去对,
 * 对不上, 以为金额算错了。
 *
 * 挂载**真实的** detail.vue, 数据按 prod 那一单的后端原样回包 (unit_price 2.0183 未税,
 * tax_rate 9, lineAmount 20.18, lineAmountWithTax 22.00, totalAmount 20.18, taxAmount 1.82)。
 */
import { flushPromises, shallowMount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide, reactive } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));

const route = reactive({ path: '/procurement/orders/po-1', fullPath: '/procurement/orders/po-1', params: { id: 'po-1' }, query: {} });

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn(), resolve: () => ({ href: '#' }) }),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006', currentRole: 'factory_super_admin' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canRead: () => true, canAccess: () => true, hasPermission: () => true }),
}));
vi.mock('@/router/guards', () => ({ isPathAllowedForRole: () => true }));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => harness.get(...args),
  post: (...args: unknown[]) => harness.post(...args),
  default: { get: (...args: unknown[]) => harness.get(...args), post: (...args: unknown[]) => harness.post(...args) },
}));
vi.mock('@/api/documentTrace', () => ({ getPurchaseOrderDocumentTrace: vi.fn() }));
vi.mock('@/demo/f006NativeBridge', () => ({ useNativeDemoAdapters: vi.fn() }));
vi.mock('element-plus', () => ({
  ElMessage: Object.assign(vi.fn(), { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }),
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm') },
}));

import PurchaseOrderDetail from '../detail.vue';

const PassThrough = (name: string) => defineComponent({
  name,
  setup(_props, { slots }) {
    return () => h('div', slots.default?.());
  },
});
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
  props: { label: { type: String, default: '' } },
  setup(props, { slots }) {
    const rows = inject<ComputedRef<unknown[]>>(ROWS_KEY, computed(() => []));
    return () => h('div', { 'data-col': props.label }, rows.value.map((row) => h('div', slots.default?.({ row }))));
  },
});
const DescriptionsItemStub = defineComponent({
  name: 'ElDescriptionsItem',
  props: { label: { type: String, default: '' } },
  setup(props, { slots }) {
    return () => h('div', { 'data-desc': props.label }, slots.default?.());
  },
});

function orderPayload(line: Record<string, unknown>, head: Record<string, unknown>) {
  return {
    id: 'po-1', orderNumber: 'PO-20261118-0001', status: 'DRAFT', supplierId: 'sup-box', supplierName: '演示·包材供应商',
    orderDate: '2026-11-18',
    ...head,
    items: [{
      id: 'line-1', materialTypeId: 'mat-box', materialName: '演示·250g食品盒',
      quantity: 10, unit: '个', quantityUnit: '个', priceUnit: '个', quantityToPriceFactor: 1,
      ...line,
    }],
  };
}

function routeGet(order: Record<string, unknown>) {
  harness.get.mockImplementation(async (url: string) => {
    if (url === '/F006/purchase/orders/po-1') return { success: true, data: order };
    return { success: true, data: [] };
  });
}

function mountDetail() {
  return shallowMount(PurchaseOrderDetail, {
    global: {
      stubs: {
        ElTable: ElTableStub,
        ElTableColumn: ElTableColumnStub,
        ElDescriptions: PassThrough('ElDescriptions'),
        ElDescriptionsItem: DescriptionsItemStub,
        ElCard: PassThrough('ElCard'),
        ElCollapse: PassThrough('ElCollapse'),
        ElCollapseItem: PassThrough('ElCollapseItem'),
      },
    },
  });
}

type Wrapper = ReturnType<typeof mountDetail>;

function text(wrapper: Wrapper, testId: string): string {
  const found = wrapper.findAll(`[data-testid="${testId}"]`);
  // 阳性对照: 读数节点必须真的渲染了一个 —— 否则断言在守空气。
  expect(found, `找不到 data-testid=${testId}`).toHaveLength(1);
  return found[0].text().replace(/\s+/g, ' ').trim();
}

function money(value: string): number {
  return Number(value.replace(/[¥,]/g, ''));
}

describe('PROTO-DEMO-PO-TAX 采购单详情: 未税 + 税额 = 含税 (挂载真实 detail.vue)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('prod 原单 PO-20261118-0001: 单头写清未税总额 20.18 / 税额合计 1.82 / 含税总额 22.00, 行上同口径', async () => {
    routeGet(orderPayload(
      { unitPrice: 2.0183, taxRate: 9, lineAmount: 20.18, lineAmountWithTax: 22.0 },
      { totalAmount: 20.18, taxAmount: 1.82 },
    ));
    const wrapper = mountDetail();
    await flushPromises();

    // 标签照实写: 不再有含糊的「总金额」「小计」。
    const descLabels = wrapper.findAll('[data-desc]').map((n) => n.attributes('data-desc'));
    expect(descLabels).toEqual(expect.arrayContaining(['未税总额', '税额合计', '含税总额']));
    expect(descLabels).not.toContain('总金额');
    const colLabels = wrapper.findAll('[data-col]').map((n) => n.attributes('data-col'));
    expect(colLabels).toEqual(expect.arrayContaining(['未税单价', '含税单价', '税率', '未税小计', '税额', '含税小计']));
    expect(colLabels).not.toContain('小计');

    expect(text(wrapper, 'po-money-untaxed')).toBe('¥20.18');
    expect(text(wrapper, 'po-money-tax')).toBe('¥1.82');
    expect(text(wrapper, 'po-money-tax-included')).toBe('¥22.00');

    expect(text(wrapper, 'po-line-untaxed-price')).toBe('2.0183 元/个');
    expect(text(wrapper, 'po-line-tax-included-price')).toBe('2.2 元/个');
    expect(text(wrapper, 'po-line-untaxed')).toBe('¥20.18');
    expect(text(wrapper, 'po-line-tax')).toBe('¥1.82');
    expect(text(wrapper, 'po-line-tax-included')).toBe('¥22.00');

    // 性质: 三个数对得上 (单头与行各自), 且行含税小计 = 数量 × 含税单价 (10 × 2.20)。
    expect(money(text(wrapper, 'po-money-untaxed')) + money(text(wrapper, 'po-money-tax')))
      .toBeCloseTo(money(text(wrapper, 'po-money-tax-included')), 2);
    expect(money(text(wrapper, 'po-line-untaxed')) + money(text(wrapper, 'po-line-tax')))
      .toBeCloseTo(money(text(wrapper, 'po-line-tax-included')), 2);
    expect(money(text(wrapper, 'po-line-tax-included'))).toBeCloseTo(10 * 2.2, 2);
    wrapper.unmount();
  });

  it('含税 2、税率 9%、数量 10 建的单: 未税 18.35 + 税额 1.65 = 含税 20.00, 含税单价回显 2', async () => {
    routeGet(orderPayload(
      { unitPrice: 1.8349, taxRate: 9, lineAmount: 18.35, lineAmountWithTax: 20.0 },
      { totalAmount: 18.35, taxAmount: 1.65 },
    ));
    const wrapper = mountDetail();
    await flushPromises();
    expect(text(wrapper, 'po-money-untaxed')).toBe('¥18.35');
    expect(text(wrapper, 'po-money-tax')).toBe('¥1.65');
    expect(text(wrapper, 'po-money-tax-included')).toBe('¥20.00');
    expect(text(wrapper, 'po-line-tax-included-price')).toBe('2 元/个');
    expect(text(wrapper, 'po-line-tax')).toBe('¥1.65');
    expect(text(wrapper, 'po-line-tax-included')).toBe('¥20.00');
    wrapper.unmount();
  });

  it('后端没给 lineAmountWithTax 时按后端同一口径算 (行未税 × (1+税率) 取两位), 不当 0 税', async () => {
    routeGet(orderPayload(
      { unitPrice: 1.8349, taxRate: 9, lineAmount: 18.35 },
      { totalAmount: 18.35, taxAmount: 1.65 },
    ));
    const wrapper = mountDetail();
    await flushPromises();
    expect(text(wrapper, 'po-line-tax')).toBe('¥1.65');
    expect(text(wrapper, 'po-line-tax-included')).toBe('¥20.00');
    wrapper.unmount();
  });

  it('阴性对照: 金额被剥 (无看价权限的回包为 null) 时三格都显示「-」, 不编出 0', async () => {
    routeGet(orderPayload(
      { unitPrice: null, taxRate: null, lineAmount: null, lineAmountWithTax: null },
      { totalAmount: null, taxAmount: null },
    ));
    const wrapper = mountDetail();
    await flushPromises();
    expect(text(wrapper, 'po-money-untaxed')).toBe('-');
    expect(text(wrapper, 'po-money-tax')).toBe('-');
    expect(text(wrapper, 'po-money-tax-included')).toBe('-');
    expect(text(wrapper, 'po-line-tax')).toBe('-');
    expect(text(wrapper, 'po-line-tax-included')).toBe('-');
    wrapper.unmount();
  });
});
