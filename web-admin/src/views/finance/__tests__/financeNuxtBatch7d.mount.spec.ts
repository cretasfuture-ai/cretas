/**
 * 第7批 (财务 d) 迁移后的真实挂载断言: SKU 毛利率页 + 实际出货毛利子组件
 * (两页一起真挂载, 不桩 ShipmentSkuMargin, 只桩 Nuxt UI 组件和 echarts):
 *  - 不再引用 element-plus / el-*;
 *  - 无价格权限: 整块财务数据不渲染, DOM 里没有金额也没有 0;
 *  - 算不出 = 「—」+ 原因, 真实 0 = 0.0% / ¥0.00, null 排序永远在最后;
 *  - 单位显示中文 (kg → 公斤), 不外露英文原码;
 *  - 订单与目录参考默认收起, 点开才出现; 日期两端都填才查询;
 *  - 出货来源弹窗按需打开、批次追溯链接正确编码。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';
import indexSource from '../sku-margin/index.vue?raw';
import shipmentSource from '../sku-margin/ShipmentSkuMargin.vue?raw';

const mocks = vi.hoisted(() => ({
  post: vi.fn(),
  getShipment: vi.fn(),
  perm: { canViewPrice: true },
  route: { query: {} as Record<string, unknown> },
}));
vi.mock('@/api/request', () => ({ post: mocks.post }));
vi.mock('@/api/financeReport', () => ({ getShipmentSkuMargin: mocks.getShipment }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => mocks.perm }));
vi.mock('vue-router', () => ({ useRoute: () => mocks.route }));
vi.mock('@/utils/echarts', () => ({
  default: { init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), isDisposed: () => false }) },
}));

import SkuMarginPage from '../sku-margin/index.vue';

const stubs = {
  ...nuxtUiStubs,
  UIcon: { template: '<i />' },
  UModal: { props: ['open', 'title'], template: '<section v-if="open" role="dialog"><h2>{{ title }}</h2><slot name="body" /></section>' },
  UAlert: { props: ['title'], template: '<div class="u-alert">{{ title }}</div>' },
};

const side = (over: Record<string, unknown> = {}) => ({
  status: 'OK', reason: null, note: null, missingPriceLineCount: 0,
  totalRevenue: 190, revenue: 190, cost: 120, grossProfit: 70, marginRate: 36.84, costCoverage: 100, ...over,
});
const itemA = {
  productTypeId: 'A', productName: '牛肉酱', quantity: 10, marginRate: 36.84, marginRateBasis: 'realized',
  realized: side(), catalog: { status: 'OK', reason: null, price: 100, unitCost: 60, marginRate: 40, costBatchNumber: 'B-A' },
};
const itemNull = {
  productTypeId: 'B', productName: '只有目录价', quantity: null, marginRate: null, marginRateBasis: 'realized',
  realized: side({ status: 'NO_SALES', reason: '所选期间没有销售记录', revenue: null, cost: null, grossProfit: null, marginRate: null, totalRevenue: null }),
  catalog: { status: 'NO_BATCH_COST', reason: '没有已算出单位成本的批次', price: 80, unitCost: null, marginRate: null, costBatchNumber: null },
};
const itemZero = {
  productTypeId: 'C', productName: '保本产品', quantity: 5, marginRate: 0, marginRateBasis: 'realized',
  realized: side({ revenue: 100, cost: 100, grossProfit: 0, marginRate: 0 }),
  catalog: { status: 'NO_PRICE', reason: '没有目录价', price: null, unitCost: 50, marginRate: null, costBatchNumber: 'B-C' },
};
const okResponse = (items: unknown[]) => ({
  success: true, message: 'ok',
  data: {
    status: 'SUCCESS', message: 'm',
    resultData: { message: 'm', data: { items, totalProducts: items.length, truncated: false, realizedPeriod: { startDate: '2026-09-07', endDate: '2026-10-06', defaultPeriod: true }, rateUnit: 'percent' } },
  },
});
const shipment = {
  periodStart: '2026-10-01', periodEnd: '2026-10-31',
  rows: [{
    productTypeId: 'A', productName: '散装牛肉', unit: 'kg', quantity: 9, revenue: 270, cost: 137.62, grossProfit: 132.38, marginRate: 49.03, shipmentLineCount: 1, batchCount: 1,
    sources: [{ shipmentLineId: 42, deliveryNumber: 'SHIP-001', deliveryDate: '2026-10-16', quantity: 9, unit: 'kg', revenue: 270, rawCost: 137.62, cost: 137.62,
      batches: [{ batchNumber: '批次&A', quantity: 9, unit: 'kg', unitCost: 15.29, rawCost: 137.62, cost: 137.62 }] }],
  }],
  totalRevenue: 270, totalCost: 137.62, grossProfit: 132.38, marginRate: 49.03, roundingAdjustment: 0,
  reconciliationStatus: 'MATCH', reconciliationMessage: '实际出货与同期间已过账利润表一致',
  financialStatement: { totalRevenue: 270, totalCost: 137.62, grossProfit: 132.38, netProfit: 132.38 },
};

async function mountPage(items: unknown[] = [itemA, itemNull, itemZero]) {
  mocks.post.mockResolvedValue(okResponse(items));
  const w = mount(SkuMarginPage, { global: { stubs } });
  await flushPromises();
  return w;
}
const openReference = async (w: ReturnType<typeof mount>) => {
  await w.get('[data-testid="margin-reference-toggle"]').trigger('click');
  await flushPromises();
};

beforeEach(() => {
  mocks.post.mockReset();
  mocks.getShipment.mockReset();
  mocks.getShipment.mockResolvedValue({ success: true, data: shipment });
  mocks.perm = { canViewPrice: true };
  mocks.route.query = {};
});

describe('迁移完整性', () => {
  it('两个文件都不再引用 element-plus 或 el-* 组件', () => {
    for (const src of [indexSource, shipmentSource]) {
      expect(src).not.toMatch(/<el-/);
      expect(src).not.toMatch(/element-plus|@element-plus/);
      expect(src).not.toMatch(/\bEl[A-Z]/);
      expect(src).not.toMatch(/v-loading/);
      expect(src).not.toMatch(/UTooltip/);
    }
  });
});

describe('SKU 毛利率页 (真挂载, 含真实 ShipmentSkuMargin)', () => {
  it('订单与目录参考默认收起, 点开后才出现明细表', async () => {
    const w = await mountPage();
    expect(w.find('[data-testid="margin-table"]').exists()).toBe(false);
    expect(w.find('[data-testid="shipment-margin"]').exists()).toBe(true);
    await openReference(w);
    expect(w.find('[data-testid="margin-table"]').exists()).toBe(true);
    expect(w.text()).toContain('共 3 个SKU');
  });

  it('算不出显示「—」和原因, 真实 0 显示 0.0%, KPI 平均不被 null 拉低', async () => {
    const w = await mountPage();
    await openReference(w);
    expect(w.get('.kpi-avg').text()).toBe('18.4%');
    expect(w.text()).toContain('2 / 3 个SKU有订单毛利率');
    expect(w.text()).toContain('所选期间没有销售记录');
    expect(w.text()).toContain('0.0%');
    expect(w.text()).toContain('没有已算出单位成本的批次');
    // 只有目录价那一行的订单数量/收入是破折号, 不是 0
    const tds = w.findAll('td').map((t) => t.text());
    expect(tds.filter((t) => t === '—').length).toBeGreaterThan(0);
  });

  it('毛利率升序时 null 仍排最后 (不当 0)', async () => {
    const w = await mountPage();
    await openReference(w);
    const names = () => w.get('[data-testid="margin-table"]').findAll('tbody tr').map((tr) => tr.findAll('td')[0].text());
    expect(names()).toEqual(['牛肉酱', '保本产品', '只有目录价']);
    (w.vm as unknown as { sorting: { id: string; desc: boolean }[] }).sorting = [{ id: 'realizedMargin', desc: false }];
    await flushPromises();
    expect(names()).toEqual(['保本产品', '牛肉酱', '只有目录价']);
  });

  it('无价格权限: 不请求出货毛利, 整块不渲染, 没有任何金额或 0', async () => {
    mocks.perm = { canViewPrice: false };
    const w = await mountPage();
    expect(w.get('[data-testid="margin-no-permission"]').text()).toContain('没有查看价格');
    expect(w.find('[data-testid="shipment-margin"]').exists()).toBe(false);
    expect(w.find('[data-testid="margin-reference-toggle"]').exists()).toBe(false);
    expect(mocks.getShipment).not.toHaveBeenCalled();
    expect(w.text()).not.toMatch(/¥|0\.0%|0\.00/);
  });

  it('请求被拒: 参考区如实显示错误原因, 没有表格', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    mocks.post.mockRejectedValue(new Error('您当前的角色没有权限执行「SKU毛利率查询」操作'));
    const w = mount(SkuMarginPage, { global: { stubs } });
    await flushPromises();
    await openReference(w);
    expect(w.get('.empty-notice').attributes('data-type')).toBe('error');
    expect(w.get('.empty-notice').text()).toContain('没有权限');
    expect(w.find('[data-testid="margin-table"]').exists()).toBe(false);
  });

  it('日期两端都填才查询, 请求带 context; 只填一端不查询', async () => {
    const w = await mountPage();
    expect(mocks.post).toHaveBeenCalledTimes(1);
    const start = w.get('[data-testid="margin-start"]');
    const end = w.get('[data-testid="margin-end"]');
    await start.setValue('2026-08-01');
    await flushPromises();
    expect(mocks.post).toHaveBeenCalledTimes(1);
    await end.setValue('2026-08-31');
    await flushPromises();
    expect(mocks.post).toHaveBeenCalledTimes(2);
    expect(mocks.post.mock.calls[1][1].context).toEqual({ startDate: '2026-08-01', endDate: '2026-08-31' });
    expect(mocks.getShipment).toHaveBeenLastCalledWith('F006', '2026-08-01', '2026-08-31');
  });
});

describe('实际出货毛利 (真挂载)', () => {
  it('单位显示中文, 不外露 kg; 金额有权限时显示', async () => {
    const w = await mountPage();
    expect(w.text()).toContain('9 公斤');
    expect(w.text()).not.toContain('kg');
    expect(w.text()).toContain('¥270.00');
    expect(w.text()).toContain('实际出货与同期间已过账利润表一致');
  });

  it('来源弹窗默认不开, 点「查看出货来源」后出现, 追溯链接编码批次号', async () => {
    const w = await mountPage();
    expect(w.find('[role="dialog"]').exists()).toBe(false);
    const btn = w.findAll('button').find((b) => b.text() === '查看出货来源');
    expect(btn).toBeDefined();
    await btn!.trigger('click');
    const dialog = w.get('[role="dialog"]');
    expect(dialog.text()).toContain('散装牛肉 · 实际出货来源');
    expect(dialog.text()).toContain('SHIP-001');
    expect(dialog.text()).toContain('15.29 元/公斤');
    expect(dialog.findAll('a').find((a) => a.text() === '查看批次追溯')?.attributes('href'))
      .toBe(`/production/traceability?batchNumber=${encodeURIComponent('批次&A')}`);
  });

  it('接口失败: 显示错误提示, 不显示 0 金额', async () => {
    mocks.getShipment.mockResolvedValue({ success: false, message: '读取失败原因' });
    const w = await mountPage();
    expect(w.get('[data-testid="shipment-margin"]').text()).toContain('读取失败原因');
    expect(w.get('[data-testid="shipment-margin"]').text()).not.toContain('¥0.00');
  });
});
