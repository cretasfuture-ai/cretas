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
 * ⚠️ UTable 用 nuxtUiStubs 里**会按行渲染**的桩: 不桩时明细行渲染成空,
 *    「小计缺失」和「压根没渲染」会长成同一个读数。
 */
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';

const harness = vi.hoisted(() => ({
  get: vi.fn(),
  // S5-12: 采购单抽屉按配置的采购读 / 财务读判 (审批速览), 不看旧名单 canViewPrice
  permission: { canViewPrice: true, canViewPurchasePrice: true, canAccess: (_m: string) => false },
}));

vi.mock('@/api/request', () => ({ get: harness.get }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'LIUSHANMEN' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => harness.permission,
}));

import OrderPeekDrawer from '../OrderPeekDrawer.vue';

/** 头部字段: 渲染成 `label：value` 文本, 与旧断言口径一致。 */
function item(wrapper: ReturnType<typeof mount>, label: string) {
  const n = wrapper.find(`[data-label="${label}"]`);
  return {
    exists: () => n.exists(),
    text: () => (n.exists() ? `${label}：${n.find('dd').text()}` : ''),
  };
}
/** 明细表某列(按表头)的全部单元格文本。 */
function colCells(wrapper: ReturnType<typeof mount>, header: string): string[] | null {
  const ths = wrapper.findAll('th').map((t) => t.text());
  const idx = ths.indexOf(header);
  if (idx < 0) return null;
  return wrapper.findAll('tbody tr').map((tr) => tr.findAll('td')[idx].text());
}

/**
 * ⚠️ OrderPeekDrawer 的取数 watcher 是 `watch(() => [visible.value, orderId], ...)`,
 * **没有** `immediate: true` —— 必须先挂载成"关闭", 再 setProps 触发一次真正的 open,
 * 与生产上「用户点开」是同一种触发方式。
 */
async function mountDrawer(orderId = 'po-1') {
  const wrapper = mount(OrderPeekDrawer, {
    props: { visible: false, orderId: null, kind: 'purchase' },
    global: { stubs: nuxtUiStubs },
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

    const inclusive = item(wrapper, '含税总额');
    const rate = item(wrapper, '税率');
    const untaxed = item(wrapper, '未税总额');
    expect(inclusive.exists() && rate.exists() && untaxed.exists(),
      '三个字段都应该渲染出来').toBe(true);
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
    const priceCells = colCells(wrapper, '单价（含税）');
    const subtotalCells = colCells(wrapper, '小计（含税）');
    expect(priceCells && subtotalCells, '单价列/小计列都应该渲染出来').toBeTruthy();
    expect(priceCells!.length, '阳性对照: 三行都应该渲染出来').toBe(3);
    // 第一行 500 元/箱 * 1.13 = 565.00/箱; 第二行 50 元/kg * 1.13 = 56.50/kg —— 单位不同, 数字不同。
    expect(priceCells![0]).toBe('¥565.00 / 箱');
    expect(priceCells![1]).toBe('¥56.50 / kg');
    expect(subtotalCells![0]).toBe('¥282.50');
    expect(subtotalCells![1]).toBe('¥282.50');
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
    const untaxed = item(wrapper, '未税总额');
    expect(untaxed.text(), '未税总额没渲染 —— 组件可能整体没挂载, 下面「含税不对」的读数无意义')
      .toBe('未税总额：¥750.00');

    // 待验证的对照: 含税总额必须显式「不知道」——
    const inclusive = item(wrapper, '含税总额');
    expect(inclusive.text(), '税额未知时含税总额应显示「—」').toBe('含税总额：—');
    // ⛔ 阴性对照双保险: 既不许是「¥0.00」也不许悄悄等于未税的「¥750.00」
    expect(inclusive.text()).not.toContain('¥0.00');
    expect(inclusive.text()).not.toContain('¥750.00');

    // 缺陷 30 再续 (2026-09-02): 税率现在读明细行的权威 taxRate 字段, 与订单级
    // taxAmount 是否为 null **无关** —— orderWithTax() 的三行明细都带着 taxRate:13,
    // 这份数据本来就知道税率, 只是不知道税额合计。⇒ 税率应该照常显示 13%,
    // 不该因为一个不相关的字段是 null 就跟着说「不知道」(那是旧的反算实现遗留的
    // 耦合, 已经被本次修复解开)。
    const rate = item(wrapper, '税率');
    expect(rate.text(), '明细行税率仍然可读, 不应该被 taxAmount=null 连带隐藏')
      .toBe('税率：13%');
  });

  it('缺陷 30 再续 (PO-20260902-0001): 反算会丢精度, 权威值不会 —— 阳性对照必须选一个反算真的算错的样本', async () => {
    // ⛔ 不能用「未税 750 / 税额 97.5」这类整除样本: 97.5÷750×100 = 13 与权威值
    // 完全相同, 修复前后读数一样, 证明不了任何东西(本文件顶部另一条用例就是这种
    // 样本, 留着测总额求和逻辑没问题, 但它不能当"税率修复"的阳性对照)。
    // 生产实测: 未税 8.85 / 税额 1.15 / 权威 taxRate 13.00 —— 反算 = 1.15÷8.85×100
    // = 12.9944…% 四舍五入显示 12.99%, 与权威值不同, 才是真正能证伪的样本。
    harness.get.mockResolvedValue({
      success: true,
      data: {
        orderNumber: 'PO-20260902-0001',
        supplierName: '测试供应商',
        status: 'PENDING_APPROVAL',
        orderDate: '2026-09-02',
        totalAmount: 8.85,
        taxAmount: 1.15,
        invoicedAmount: 0,
        paidAmount: 0,
        items: [
          {
            materialName: '测试物料', quantity: 1, unit: '件', priceUnit: '件',
            unitPrice: 8.85, taxRate: 13, lineAmount: 8.85, lineAmountWithTax: 10,
          },
        ],
      },
    });

    const wrapper = await mountDrawer();
    await flushPromises();

    expect(wrapper.text(), '订单没渲染出来 —— 后面的断言无意义').toContain('PO-20260902-0001');

    const rate = item(wrapper, '税率');
    // 阳性断言: 权威值必须真的渲染出来了(硬约束 9 —— 不能只验证"坏值不在", 还要
    // 验证"对的值在")。
    expect(rate.text(), '应该显示库里的权威税率 13%, 不是反算出的 12.99%')
      .toBe('税率：13%');
    // 阴性对照: 反算残留一个字符都不许出现。
    expect(rate.text()).not.toContain('12.99');
  });

  it('多税率单据: 不挑一个代表, 也不显示反算出的加权平均', async () => {
    harness.get.mockResolvedValue({
      success: true,
      data: {
        orderNumber: 'PO-20260902-0002',
        supplierName: '测试供应商',
        status: 'PENDING_APPROVAL',
        orderDate: '2026-09-02',
        totalAmount: 200,
        taxAmount: 19,
        invoicedAmount: 0,
        paidAmount: 0,
        items: [
          {
            materialName: '13%税率物料', quantity: 1, unit: '件', priceUnit: '件',
            unitPrice: 100, taxRate: 13, lineAmount: 100, lineAmountWithTax: 113,
          },
          {
            materialName: '6%税率物料', quantity: 1, unit: '件', priceUnit: '件',
            unitPrice: 100, taxRate: 6, lineAmount: 100, lineAmountWithTax: 106,
          },
        ],
      },
    });

    const wrapper = await mountDrawer();
    await flushPromises();

    expect(wrapper.text(), '订单没渲染出来 —— 后面的断言无意义').toContain('PO-20260902-0002');

    const rate = item(wrapper, '税率');
    expect(rate.text(), '逐行税率不一致时应显式说「多税率」').toBe('税率：多税率');
    // 阴性对照: 不许悄悄挑了 13 或 6, 也不许显示 (19÷200×100=9.5) 这个加权平均。
    expect(rate.text()).not.toContain('13%');
    expect(rate.text()).not.toContain('6%');
    expect(rate.text()).not.toContain('9.5%');
  });

  it('明细行没有可读税率时显示「—」, ⛔ 不编一个 0% 或猜测值', async () => {
    harness.get.mockResolvedValue({
      success: true,
      data: {
        orderNumber: 'PO-20260902-0003',
        supplierName: '测试供应商',
        status: 'PENDING_APPROVAL',
        orderDate: '2026-09-02',
        totalAmount: 100,
        taxAmount: null,
        invoicedAmount: 0,
        paidAmount: 0,
        items: [
          {
            // 历史单据: 明细行本身就没有 taxRate 字段(不是 0, 是压根没配过)。
            materialName: '老单据物料', quantity: 1, unit: '件', priceUnit: '件',
            unitPrice: 100, lineAmount: 100,
          },
        ],
      },
    });

    const wrapper = await mountDrawer();
    await flushPromises();

    expect(wrapper.text(), '订单没渲染出来 —— 后面的断言无意义').toContain('PO-20260902-0003');

    const rate = item(wrapper, '税率');
    expect(rate.text(), '明细行读不到税率, 不许显示 0% 或任何猜测值').toBe('税率：—');
    expect(rate.text()).not.toContain('0%');
  });
});

describe('OrderPeekDrawer S5-12 采购价可见性', () => {
  beforeEach(() => {
    harness.get.mockReset();
    harness.permission = { canViewPrice: true, canViewPurchasePrice: true, canAccess: (_m: string) => false };
  });

  it('采购单走 OA 审批读 (approval-view), 不走普通详情', async () => {
    harness.get.mockResolvedValue({ success: true, data: orderWithTax() });
    await mountDrawer('po-9');
    expect(harness.get).toHaveBeenCalledWith('/LIUSHANMEN/purchase/orders/po-9/approval-view', expect.anything());
  });

  it('后端剥成 null 的金额显示「—」, 不显示 ¥0.00', async () => {
    harness.get.mockResolvedValue({
      success: true,
      data: { ...orderWithTax(), totalAmount: null, taxAmount: null, invoicedAmount: null, paidAmount: null,
        items: [{ materialName: '牛前', quantity: 1, unit: 'kg', priceUnit: 'kg', unitPrice: null,
          lineAmount: null, lineAmountWithTax: null }] },
    });
    const wrapper = await mountDrawer();
    expect(wrapper.text(), '订单没渲染出来').toContain('PO-20260901-0004');
    expect(item(wrapper, '未税总额').text()).toBe('未税总额：—');
    expect(wrapper.text()).not.toContain('¥0.00');
  });

  it('财务 (配置无采购读、有财务读) 在审批抽屉里看得到采购金额 —— 裁定 a', async () => {
    harness.permission = { canViewPrice: true, canViewPurchasePrice: false, canAccess: (m: string) => m === 'finance' };
    harness.get.mockResolvedValue({ success: true, data: orderWithTax() });
    const wrapper = await mountDrawer();
    expect(item(wrapper, '未税总额').text()).toBe('未税总额：¥750.00');
  });

  it('旧名单里但既无采购读也无财务读 → 采购金额一律「—」', async () => {
    harness.permission = { canViewPrice: true, canViewPurchasePrice: false, canAccess: (_m: string) => false };
    harness.get.mockResolvedValue({ success: true, data: orderWithTax() });
    const wrapper = await mountDrawer();
    expect(item(wrapper, '未税总额').text()).toBe('未税总额：—');
  });
});
