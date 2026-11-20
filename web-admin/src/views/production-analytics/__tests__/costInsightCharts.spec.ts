/**
 * 成本页一图一分析（问题 40）：句子必须按当前数据现算，不许回到模板话。
 * 取代 #1831 的「成本页禁止 ChartInsight」——禁止的是模板话，不是分析本身。
 */
import { describe, expect, it } from 'vitest';
import { buildChartInsight, type UserPermissions } from '@/views/smart-bi/components/chartInsight';
import type { CostStructureData, ProductCostItem, YieldTrendData } from '@/api/smartbi/factoryGold';
import {
  costPieInsightChart,
  costTrendInsightChart,
  previousRange,
  productInsightChart,
  yieldTrendInsightChart,
} from '../costInsightCharts';

const finance: UserPermissions = { canViewFinance: true };
const noFinance: UserPermissions = { canViewFinance: false };

/** 11-17 评估里被扣分的模板话 + 中文旁边多余的空格 */
const TEMPLATE_RE = /超过一半|分布相对均衡|头部集中|单调(上升|下降)|累计变幅|整体(上升|下降)|是末位|前二占|[一-龥] | [一-龥]/;
const MONEY_RE = /¥|元/;
const PCT_RE = /\d+(\.\d+)?%/;

function row(statDate: string, material: number | null, labor: number | null) {
  return {
    factoryId: 'F006', statDate, productTypeId: null, batchCount: 1,
    totalPlannedQty: null, totalActualQty: null, totalGoodQty: null, avgYieldRate: null,
    totalMaterialCost: material, totalLaborCost: labor, totalEquipmentCost: null, totalOtherCost: null,
    totalCost: material == null && labor == null ? null : (material ?? 0) + (labor ?? 0),
    version: 1, updatedAt: null,
  };
}

function costData(material: number, labor: number, equipment: number, other: number): CostStructureData {
  return {
    rows: [row('2026-11-10', material * 0.2, labor * 0.2), row('2026-11-12', material * 0.7, labor * 0.7), row('2026-11-13', material * 0.1, labor * 0.1)],
    summary: {
      totalMaterialCost: material, totalLaborCost: labor, totalEquipmentCost: equipment, totalOtherCost: other,
      totalCost: material + labor + equipment + other, batchCount: 3, totalActualQty: null,
    },
  };
}

const text = (r: { finding: string; suggestion?: string; implication?: string } | null) =>
  r ? [r.finding, r.implication ?? '', r.suggestion ?? ''].join('\n') : '';

describe('TEMPLATE_RE 阴性对照：旧模板话确实会被它拦下', () => {
  it.each([
    '头部集中，材料成本 占比超过一半。',
    '整体下降 99.7%（期间有波动）',
    'A 排名最高，是末位 B 的 7646.0倍；占合计 99.9%',
    '材料成本 占 93.9%，前二占 98.0%',
  ])('%s', (old) => {
    expect(old).toMatch(TEMPLATE_RE);
  });
});

describe('成本结构占比', () => {
  const current = costData(74_463, 3_250, 1_100, 500);

  it('写出最大一项的实际占比和金额，建议按占比现算', () => {
    const r = buildChartInsight(costPieInsightChart(current, null)!, finance)!;
    expect(r.finding).toBe('材料成本占93.9%（¥7.45万），是最大一项；其次人工成本4.1%、设备成本1.4%。');
    expect(r.suggestion).toContain('材料成本占合计的93.9%，先从这一项核对费用明细的用量和单价');
    expect(text(r)).not.toMatch(TEMPLATE_RE);
  });

  it('没有上期 → 不写环比；有上期 → 写占比变化（个百分点）', () => {
    const none = buildChartInsight(costPieInsightChart(current, null)!, finance)!;
    expect(text(none)).not.toContain('上期');
    const prev = costData(60_000, 6_000, 1_000, 500); // 材料 60000/67500 = 88.9%
    const withPrev = buildChartInsight(costPieInsightChart(current, prev)!, finance)!;
    expect(withPrev.finding).toContain('占比比上期高5.0个百分点');
  });

  it('无价格权限：不出金额，也不出占比', () => {
    const r = buildChartInsight(costPieInsightChart(current, null)!, noFinance)!;
    expect(text(r)).not.toMatch(MONEY_RE);
    expect(text(r)).not.toMatch(PCT_RE);
    expect(r.finding).toContain('材料成本是最大一项');
  });

  it('没有成本拆分 → 没有图也没有分析（不编）', () => {
    expect(costPieInsightChart(costData(0, 0, 0, 0), null)).toBeNull();
    expect(costPieInsightChart(null, null)).toBeNull();
  });
});

describe('每日成本趋势（堆叠）', () => {
  const current = costData(10_000, 1_000, 0, 0);

  it('按日合计（堆叠各类相加）找峰值，并说明没生产的日子没计入', () => {
    const r = buildChartInsight(costTrendInsightChart(current, null)!, finance)!;
    // 11-12：材料 7000 + 人工 700 = 7700；三天合计 11000，平均 3666.67 → 2.1 倍
    expect(r.finding).toContain('区间合计¥1.1万');
    expect(r.finding).toContain('11-12最高，¥7,700，是平均的2.1倍');
    expect(r.finding).toContain('其中材料占90.9%');
    expect(r.finding).toContain('1个日期无数据，未计入');
    expect(r.suggestion).toContain('先查11-12');
    expect(text(r)).not.toMatch(TEMPLATE_RE);
  });

  it('上期合计给了才写环比', () => {
    const prev = costData(5_000, 500, 0, 0);
    const r = buildChartInsight(costTrendInsightChart(current, prev)!, finance)!;
    expect(r.finding).toContain('合计比上期高100.0%');
  });

  it('无价格权限：不出金额和占比（只留峰值日和相对变化）', () => {
    const r = buildChartInsight(costTrendInsightChart(current, null)!, noFinance)!;
    expect(text(r)).not.toMatch(MONEY_RE);
    expect(text(r)).not.toMatch(/占\d/);
    expect(text(r)).not.toContain('合计');
    expect(r.finding).toContain('11-12最高');
  });
});

describe('出成率趋势（日度）', () => {
  const trend = (rates: Array<[string, number | null]>): YieldTrendData => ({
    productTypeId: null,
    trend: rates.map(([statDate, avgYieldRate]) => ({
      statDate, avgYieldRate, batchCount: 1, totalActualQty: 100, totalGoodQty: avgYieldRate, totalCost: null,
    })),
  });

  it('点出最低的一天、比平均低几个百分点，前后半段走势', () => {
    const r = buildChartInsight(yieldTrendInsightChart(trend([
      ['2026-11-10', 96], ['2026-11-11', 95], ['2026-11-12', 88], ['2026-11-13', 97],
    ]), null)!, noFinance)!; // 出成率不是成本指标：无价格权限也照常显示
    expect(r.finding).toBe('各期平均94.0%；11-12最低，88.0%，比平均低6.0个百分点；后半段比前半段低3.0个百分点。');
    expect(r.suggestion).toBe('先查11-12的批次明细，找出拉低的原因。');
  });

  it('各天都一样（如全是 100%）→ 不点名「最低的一天」，如实说没有波动', () => {
    const r = buildChartInsight(yieldTrendInsightChart(trend([
      ['2026-11-10', 100], ['2026-11-11', 100], ['2026-11-13', 100],
    ]), null)!, finance)!;
    expect(r.finding).toBe('3个日期都在100.0%左右，最高最低相差不到0.5个百分点（1个日期无数据，未计入）。');
    expect(r.finding).not.toContain('最低，');
    expect(r.suggestion).not.toContain('拉低');
  });

  it('有上期 → 平均与上期比（个百分点）', () => {
    const r = buildChartInsight(yieldTrendInsightChart(
      trend([['2026-11-10', 96], ['2026-11-11', 95], ['2026-11-12', 88]]),
      trend([['2026-10-10', 90], ['2026-10-11', 92]]),
    )!, finance)!;
    expect(r.finding).toContain('平均比上期高2.0个百分点');
  });
});

describe('各产品单位成本 Top10', () => {
  const p = (id: string, unit: string, costPerUnit: number | null): ProductCostItem => ({
    productTypeId: id, unit, productName: id, batchCount: 2, totalActualQty: 100, totalGoodQty: 95,
    avgYieldRate: 95, totalMaterialCost: null, totalLaborCost: null, totalEquipmentCost: null,
    totalOtherCost: null, totalCost: null, costPerUnit,
  });
  const products = [p('卤牛肉', '盒', 30), p('卤鸡爪', '盒', 12), p('卤豆干', '盒', 6), p('散装牛肉', 'kg', 80)];
  const labels = products.map(x => x.productTypeId);

  it('跨单位不比大小：只在同单位里说最贵的、比平均高多少', () => {
    const r = buildChartInsight(productInsightChart(products, labels, true, null, x => x.productTypeId)!, finance)!;
    expect(r.finding).toBe('按盒计的3项里，卤牛肉最高，¥30/盒，是平均的1.9倍；最低是卤豆干，¥6/盒。');
    expect(r.suggestion).toBe('先查卤牛肉：比平均高87.5%，看用料和工时。');
    expect(r.finding).not.toContain('散装牛肉'); // kg 的 80 元不拿来和盒比
    expect(text(r)).not.toMatch(TEMPLATE_RE);
  });

  it('上期同产品能对上才写环比', () => {
    const prev = [p('卤牛肉', '盒', 25)];
    const r = buildChartInsight(productInsightChart(products, labels, true, prev, x => x.productTypeId)!, finance)!;
    expect(r.finding).toContain('比上期高20.0%');
  });

  it('无价格权限：比出成率，不出金额', () => {
    const r = buildChartInsight(productInsightChart(products, labels, false, null, x => x.productTypeId)!, noFinance)!;
    expect(text(r)).not.toMatch(MONEY_RE);
    expect(r.finding).toContain('%');
  });
});

describe('previousRange', () => {
  it('取紧挨着的前一段等长区间', () => {
    expect(previousRange('2026-10-20', '2026-11-18')).toEqual({ startDate: '2026-09-20', endDate: '2026-10-19' });
    expect(previousRange('2026-11-18', '2026-11-18')).toEqual({ startDate: '2026-11-17', endDate: '2026-11-17' });
  });
});
