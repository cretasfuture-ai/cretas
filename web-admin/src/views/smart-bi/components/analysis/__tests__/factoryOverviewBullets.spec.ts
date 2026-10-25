import { describe, it, expect } from 'vitest';
import {
  factoryFinanceOverview,
  factorySalesOverview,
  factoryTrendOverview,
  factoryKpiOverview,
  fmtWan,
} from '../factoryOverviewBullets';

// 线上 F006 的真实读数 (2026-10-23, cretas_prod_db / 生产接口)
const SALES = {
  orderCount: 59, effectiveOrderCount: 42, cancelledCount: 10, draftCount: 6, rejectedCount: 1,
  inFlightCount: 22, orderAmount: 62664.67, ordersWithoutAmount: 0,
  firstOrderDate: '2026-08-09', lastOrderDate: '2026-09-18',
  topCustomers: [{ name: 'SOP-20260730-01-测试客户', orders: 12, amount: 33621.17 }],
  topProducts: [{ name: 'SOP-20260730-01-黄油鸡-成品800g', unit: '盒', quantity: 616, amount: 30506 }],
};

describe('fmtWan (金额显示)', () => {
  it('1 万以上用万, 以下用元; null 不显示成 0', () => {
    expect(fmtWan(62664.67)).toBe('6.27万元');
    expect(fmtWan(2959123)).toBe('295.91万元');
    expect(fmtWan(353.98)).toBe('353.98元');
    expect(fmtWan(null)).toBe('—');
  });
});

describe('factorySalesOverview (销售订单)', () => {
  it('写出有效订单、金额、期间、在途、最大客户和产品, 并说明哪些不计入', () => {
    const o = factorySalesOverview(SALES);
    expect(o.empty).toBeNull();
    expect(o.bullets[0]).toBe('有效订单 42 张，订单金额 6.27万元（2026-08-09 至 2026-09-18）');
    expect(o.bullets).toContain('在途 22 张（已确认 / 待财务审核 / 财务已批准 / 处理中）');
    expect(o.bullets).toContain('金额最大的客户：SOP-20260730-01-测试客户，3.36万元（12 张）');
    expect(o.bullets).toContain('金额最高的产品：SOP-20260730-01-黄油鸡-成品800g，616 盒，3.05万元');
    expect(o.bullets).toContain('另有已取消 10 张、草稿 6 张、财务驳回 1 张，不计入金额');
    expect(o.summary).toContain('6.27万元');
  });

  it('没有订单: 写明没有, 不出现 0 元', () => {
    const o = factorySalesOverview({ ...SALES, orderCount: 0, effectiveOrderCount: 0, orderAmount: null, cancelledCount: 0, draftCount: 0, rejectedCount: 0, inFlightCount: 0, firstOrderDate: null, lastOrderDate: null, topCustomers: [], topProducts: [] });
    expect(o.bullets).toEqual([]);
    expect(o.empty).toBe('业务系统里还没有销售订单');
    expect(o.summary).not.toMatch(/0(\.00)?元/);
  });

  it('有订单但都没填金额: 不说金额是 0', () => {
    const o = factorySalesOverview({ ...SALES, orderAmount: null, ordersWithoutAmount: 42 });
    expect(o.bullets[0]).toBe('有效订单 42 张，都还没有填金额（2026-08-09 至 2026-09-18）');
  });
});

describe('factoryFinanceOverview (往来余额)', () => {
  it('只写有记录的余额; 收入/成本/利润不从这里出', () => {
    const o = factoryFinanceOverview({ accountsReceivable: 212577.96, accountsPayable: 2959123, customerPrepayments: 16507.02, totalRevenue: 0, totalCost: null, totalProfit: null });
    expect(o.bullets).toEqual(['应收账款余额 21.26万元', '应付账款余额 295.91万元', '客户预收款 1.65万元']);
    expect(o.bullets.join()).not.toContain('收入');
    expect(o.unavailableMetrics).toEqual(expect.arrayContaining(['营业收入', '毛利', '净利润']));
  });

  it('全都没有记录时写明没有', () => {
    const o = factoryFinanceOverview({ accountsReceivable: null, accountsPayable: null, customerPrepayments: null });
    expect(o.bullets).toEqual([]);
    expect(o.empty).toBe('业务系统里还没有应收、应付或预收记录');
  });
});

describe('factoryTrendOverview (近一年生产)', () => {
  const trend = {
    productionTrend: [{ date: '2026-08-15', value: 120 }, { date: '2026-08-16', value: 0 }, { date: '2026-09-30', value: 3 }],
    costTrend: [{ date: '2026-08-15', value: 400 }, { date: '2026-08-17', value: 120.5 }, { date: '2026-09-01', value: null }],
  };
  const products = [{ productName: 'SOP-20260817-01-黄油鸡-成品800g', totalQuantity: 240, unit: '盒' }];

  it('写有生产的天数、最近一次、成本合计和产量最多的产品', () => {
    const o = factoryTrendOverview(trend, products);
    expect(o.bullets).toEqual([
      '近一年有 2 天有产出，最近一次在 2026-09-30',
      '近一年生产成本合计 520.50元（2 天有成本记录）',
      '产量最多的产品：SOP-20260817-01-黄油鸡-成品800g，240 盒',
    ]);
  });

  it('没有成本读数 (无价格权限或没记) 时不写成本, 不写 0', () => {
    const o = factoryTrendOverview({ ...trend, costTrend: [{ date: '2026-08-15', value: null }] }, products);
    expect(o.bullets.join()).not.toContain('成本');
  });

  it('近一年没有产出时写明', () => {
    const o = factoryTrendOverview({ productionTrend: [{ date: '2026-08-15', value: 0 }], costTrend: [] }, []);
    expect(o.bullets).toEqual([]);
    expect(o.empty).toBe('近一年没有生产记录');
  });
});

describe('factoryKpiOverview (KPI)', () => {
  it('只写有值的几项, 带单位和目标', () => {
    const o = factoryKpiOverview({ unitCost: 12.58, bomVarianceRate: 5.26, avgCycleTime: 7.07, outputCompletionRate: 100, oee: null, fpy: 0 });
    expect(o.bullets).toEqual([
      '单位成本 12.58 元',
      'BOM 用料偏差 5.26%（目标 ≤5%，超出）',
      '平均生产周期 7.07 小时',
      '产量完成率 100.0%',
    ]);
  });

  it('全为空时写明', () => {
    const o = factoryKpiOverview({ unitCost: null, bomVarianceRate: null, avgCycleTime: null, outputCompletionRate: null });
    expect(o.empty).toBe('KPI 还没有可用的读数');
  });
});
