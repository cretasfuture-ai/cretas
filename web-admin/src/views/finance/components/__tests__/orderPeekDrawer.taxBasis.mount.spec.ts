/**
 * 缺陷 30 续 (2026-09-01, LIUSHANMEN 生产, PO-20260901-0004):
 *
 *   审批人点开「只读速览抽屉」, 未税总额 ¥750.00 显示得好好的, 而
 *   含税总额是「—」、每行小计是「—」、没有单价列 —— 后端**其实**下发了
 *   totalAmount(未税)/taxAmount(税额), 数据在, 是显示没接上
 *   (`order.totalAmountWithTax` / `row.totalAmount ?? row.subtotal` 这两个属性
 *   在 PurchaseOrder(Item) 上都不存在)。
 *
 * ⚠️ 与隔壁 orderPeekDrawer.readonly.spec.ts 分工不同, 两条都要:
 *   源码闸(readonly.spec.ts)   钉「只读 / 不能注册路由」这类**结构**约束
 *   本文件                     钉「点开真的看到含税总额/单价/小计的**实际数字**」——
 *                              走的是渲染出来的文案, 只桩掉 HTTP + 两个 store。
 *
 * ⚠️ el-table/el-table-column 必须是**会按行渲染**的桩(与 pending.vue 那份
 *    mount.spec.ts 同一个理由): shallowMount 或不桩直接 mount 时, 未注册的
 *    Element Plus 组件不会真的按 :data 遍历, 明细行会渲染成空, 「小计缺失」和
 *    「压根没渲染」会长成同一个读数。
 */
import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const harness = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock('@/api/request', () => ({ get: harness.get }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'LIUSHANMEN' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canViewPrice: true }),
}));

import OrderPeekDrawer from '../OrderPeekDrawer.vue';

const ROWS_KEY = 'stub-table-rows';

/** 把 :data 交给下面的列去渲染 —— 与 oaApprovalRefreshesOtherTabs.mount.spec.ts 同一份手法。 */
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
    return () => h(
      'div',
      { class: 'stub-column', 'data-col-label': props.label },
      rows.value.map((row) => h('div', { class: 'stub-cell' }, slots.default?.({ row }))),
    );
  },
});

const ElDescriptionsItemStub = defineComponent({
  name: 'ElDescriptionsItem',
  props: { label: { type: String, default: '' } },
  setup(props, { slots }) {
    return () => h(
      'div',
      { class: 'desc-item', 'data-label': props.label },
      [`${props.label}：`, ...(slots.default?.() ?? [])],
    );
  },
});

const PassThrough = (name: string) => defineComponent({
  name,
  setup(_props, { slots }) {
    return () => h('div', { class: `stub-${name}` }, slots.default?.());
  },
});

/**
 * ⚠️ OrderPeekDrawer 的取数 watcher 是 `watch(() => [visible.value, orderId], ...)`,
 * **没有** `immediate: true` —— 生产上这是对的(抽屉初始是关的, 用户点开才是一次
 * "变化"), 但测试如果直接拿 `visible: true` 当初始 prop 挂载, watcher 从「一直是 true」
 * 到「还是 true」不算变化, `load()` 永远不会被调用。⇒ 必须先挂载成"关闭", 再
 * setProps 触发一次真正的 open, 与生产上「用户点开」是同一种触发方式。
 */
async function mountDrawer(orderId = 'po-1') {
  const wrapper = mount(OrderPeekDrawer, {
    props: { visible: false, orderId: null, kind: 'purchase' },
    global: {
      directives: { loading: {} },
      stubs: {
        ElDrawer: PassThrough('ElDrawer'),
        ElAlert: PassThrough('ElAlert'),
        ElTag: PassThrough('ElTag'),
        ElEmpty: PassThrough('ElEmpty'),
        ElDescriptions: PassThrough('ElDescriptions'),
        ElDescriptionsItem: ElDescriptionsItemStub,
        ElTable: ElTableStub,
        ElTableColumn: ElTableColumnStub,
      },
    },
  });
  await wrapper.setProps({ visible: true, orderId });
  await flushPromises();
  return wrapper;
}

/** PO-20260901-0004 的形状: 未税 750 / 税额 97.5 / 税率 13% —— 三行明细各占 1/3。 */
function orderWithTax() {
  return {
    orderNumber: 'PO-20260901-0004',
    supplierName: '测试供应商',
    status: 'PENDING_APPROVAL',
    orderDate: '2026-09-01',
    totalAmount: 750,
    taxAmount: 97.5,
    invoicedAmount: 0,
    paidAmount: 0,
    items: [
      {
        materialName: '3251厂牛前', quantity: 0.5, unit: '箱', priceUnit: '箱',
        unitPrice: 500, taxRate: 13, lineAmount: 250, lineAmountWithTax: 282.5,
      },
      {
        materialName: '941厂牛后', quantity: 5, unit: 'kg', priceUnit: 'kg',
        unitPrice: 50, taxRate: 13, lineAmount: 250, lineAmountWithTax: 282.5,
      },
      {
        materialName: '脂肪(3MM)', quantity: 5, unit: 'kg', priceUnit: 'kg',
        unitPrice: 50, taxRate: 13, lineAmount: 250, lineAmountWithTax: 282.5,
      },
    ],
  };
}

describe('OrderPeekDrawer 含税/未税实际数字', () => {
  beforeEach(() => {
    harness.get.mockReset();
  });

  it('含税总额领先、税率说清楚、未税殿后, 且至少两行明细各自的单价/小计是真数字', async () => {
    harness.get.mockResolvedValue({ success: true, data: orderWithTax() });

    const wrapper = await mountDrawer();
    await flushPromises();

    // 阳性对照: 组件真的渲染出了订单内容(否则下面全部断言在验证一片空白)
    expect(wrapper.text(), '订单号都没渲染出来 —— 组件没挂载成功, 后面的断言无意义')
      .toContain('PO-20260901-0004');

    const inclusive = wrapper.find('[data-label="含税总额"]');
    const rate = wrapper.find('[data-label="税率"]');
    const untaxed = wrapper.find('[data-label="未税总额"]');
    expect(inclusive.exists() && rate.exists() && untaxed.exists(),
      '三个 descriptions-item 都应该渲染出来').toBe(true);
    expect(inclusive.text()).toBe('含税总额：¥847.50');
    expect(rate.text()).toBe('税率：13%');
    expect(untaxed.text()).toBe('未税总额：¥750.00');

    // 顺序: 含税领先, 未税殿后 —— owner 裁定的是**顺序**, 不只是"都要有"。
    const descOrder = wrapper.findAll('.desc-item').map((n) => n.attributes('data-label'));
    const inclusiveIdx = descOrder.indexOf('含税总额');
    const untaxedIdx = descOrder.indexOf('未税总额');
    expect(inclusiveIdx, '含税总额一定要渲染出来').toBeGreaterThanOrEqual(0);
    expect(inclusiveIdx, '含税应该排在未税前面(owner 裁定「先算含税」)').toBeLessThan(untaxedIdx);

    // 明细: 单价列 + 小计列都要有, 且至少两行是不同的真实数字(⛔ 不是只验总计)。
    const priceCol = wrapper.find('[data-col-label="单价（含税）"]');
    const subtotalCol = wrapper.find('[data-col-label="小计（含税）"]');
    expect(priceCol.exists() && subtotalCol.exists(), '单价列/小计列都应该渲染出来').toBe(true);

    const priceCells = priceCol.findAll('.stub-cell').map((c) => c.text());
    const subtotalCells = subtotalCol.findAll('.stub-cell').map((c) => c.text());
    expect(priceCells.length, '阳性对照: 三行都应该渲染出来').toBe(3);
    // 第一行 500 元/箱 * 1.13 = 565.00/箱; 第二行 50 元/kg * 1.13 = 56.50/kg —— 单位不同, 数字不同。
    expect(priceCells[0]).toBe('¥565.00 / 箱');
    expect(priceCells[1]).toBe('¥56.50 / kg');
    expect(subtotalCells[0]).toBe('¥282.50');
    expect(subtotalCells[1]).toBe('¥282.50');
  });

  it('硬约束9: 「未税总额有值」是设计上必然出现的对照 —— 与「含税不是 0/不等未税」配对读', async () => {
    // 税额 null(区别于显式 0): 真实场景是税率字段没配过的历史单据。
    harness.get.mockResolvedValue({
      success: true,
      data: { ...orderWithTax(), taxAmount: null },
    });

    const wrapper = await mountDrawer();
    await flushPromises();

    // 必发生的对照: 未税总额一定要正常渲染(证明组件没有整体崩溃/没挂载)。
    const untaxed = wrapper.find('[data-label="未税总额"]');
    expect(untaxed.text(), '未税总额没渲染 —— 组件可能整体没挂载, 下面「含税不对」的读数无意义')
      .toBe('未税总额：¥750.00');

    // 待验证的对照: 含税总额必须显式「不知道」——
    const inclusive = wrapper.find('[data-label="含税总额"]');
    expect(inclusive.text(), '税额未知时含税总额应显示「—」').toBe('含税总额：—');
    // ⛔ 阴性对照双保险: 既不许是「¥0.00」也不许悄悄等于未税的「¥750.00」
    expect(inclusive.text()).not.toContain('¥0.00');
    expect(inclusive.text()).not.toContain('¥750.00');

    const rate = wrapper.find('[data-label="税率"]');
    expect(rate.text(), '税率未知时不许编一个百分比出来').toBe('税率：—');
  });
});
