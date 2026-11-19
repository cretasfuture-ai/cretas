/**
 * FLOW 族（漏斗 / 桑基）一图一分析（问题 40，LSM-COSTBI-D2 PR2）。
 * 数据形状取自 2026-11-18 LSMDEMO prod 只读录制（商机漏斗 / 进销存总览 / 成品出厂核算）。
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { buildChartInsight, buildFlowInsight, type ChartWithMeta, type UserPermissions } from '../chartInsight';
import { funnelInsightChart } from '@/views/crm/opportunity/funnelInsight';
import { supplyChainInsightChart, type SupplyChainInsightInput } from '@/views/analytics/supplyChainInsight';
import { mixSankeyInsightChart, yieldSankeyInsightChart } from '@/views/production-analytics/yieldFlowInsight';
import { summarizeQuantity } from '@/views/analytics/quantitySummary';
import type { FunnelStatEntry } from '@/api/salesOpportunity';

const finance: UserPermissions = { canViewFinance: true };
const noFinance: UserPermissions = { canViewFinance: false };
const TEMPLATE_RE = /超过一半|分布相对均衡|头部集中|单调(上升|下降)|累计变幅|整体(上升|下降)|是末位|前二占|数据缺失|[一-龥] | [一-龥]/;
const text = (r: { finding: string; suggestion?: string } | null) => (r ? `${r.finding}\n${r.suggestion ?? ''}` : '');

function entry(stage: FunnelStatEntry['stage'], count: number): FunnelStatEntry {
  return { stage, stageDisplay: '', count, totalValue: count * 10000, expectedValue: 0 };
}

describe('漏斗：相邻阶段转化', () => {
  const stats = [entry('LEAD', 40), entry('QUALIFIED', 20), entry('DEMO', 15), entry('PROPOSAL', 3), entry('NEGOTIATE', 2), entry('VERBAL', 2), entry('CLOSED_WON', 1), entry('CLOSED_LOST', 9)];

  it('点出转化最低的一步，数和阶段一致；整体转化 = 赢单 / 线索', () => {
    const r = buildChartInsight(funnelInsightChart(stats, true)!, finance)!;
    expect(r.finding).toContain('转化最低的是产品演示→方案报价，15个到3个，20.0%');
    expect(r.finding).toContain('从线索（40个）到赢单（1个）整体2.5%');
    expect(r.finding).not.toContain('丢单');
    expect(text(r)).not.toMatch(TEMPLATE_RE);
  });

  it('无价格权限：同一句话（只用数量），不出 ¥', () => {
    const r = buildChartInsight(funnelInsightChart(stats, true)!, noFinance)!;
    expect(text(r)).not.toMatch(/¥|元/);
    expect(r.finding).toContain('20.0%');
  });

  it('LSMDEMO 实况：接口返回空数组 → 说清是还没有商机，不写成转化 0%', () => {
    const r = buildChartInsight(funnelInsightChart([], true)!, finance)!;
    expect(r.finding).toBe('7个阶段都是0个，还没有数据，算不出转化。');
    expect(text(r)).not.toMatch(/\d%/);
  });

  it('中间阶段为 0：不拿 0 当分母', () => {
    const r = buildChartInsight(funnelInsightChart([entry('LEAD', 5), entry('DEMO', 2)], true)!, finance)!;
    expect(r.finding).toContain('转化最低的是线索→已资格化，5个到0个，0.0%');
    expect(r.finding).toContain('已资格化、方案报价、商务谈判、口头承诺目前是0，下一步算不出转化');
    expect(r.finding).not.toMatch(/Infinity|NaN/);
  });

  it('没加载成功 → 不出分析', () => {
    expect(funnelInsightChart(stats, false)).toBeNull();
  });
});

// 成品出厂核算：LSMDEMO 订单 1f578ab3 的 6 道工序（录制原值）
const STEPS = [
  { processName: '解冻修整', totalInput: 215, inputUnit: 'kg', totalOutput: 204.25, outputUnit: 'kg' },
  { processName: '称量腌制', totalInput: 204.25, inputUnit: 'kg', totalOutput: 219.3, outputUnit: 'kg' },
  { processName: '熟制杀菌', totalInput: 219.3, inputUnit: 'kg', totalOutput: 176, outputUnit: 'kg' },
  { processName: '快速冷却', totalInput: 176, inputUnit: 'kg', totalOutput: 172, outputUnit: 'kg' },
  { processName: '定量包装', totalInput: 172, inputUnit: 'kg', totalOutput: 860, outputUnit: '袋' },
  { processName: '金检贴标', totalInput: 860, inputUnit: '袋', totalOutput: 860, outputUnit: '袋' },
];

describe('批次溯源桑基：每个环节只和前面最近的同单位环节比', () => {
  it('降得最多的是熟制杀菌→快速冷却（219.3kg→176kg，80.3%），kg 段首尾 80.0%', () => {
    const r = buildChartInsight(yieldSankeyInsightChart(STEPS)!, finance)!;
    expect(r.finding).toContain('解冻修整215kg，到定量包装172kg，是开头的80.0%');
    expect(r.finding).toContain('降得最多的是熟制杀菌→快速冷却，从219.3kg到176kg，剩80.3%');
    expect(r.finding).toContain('称量腌制→熟制杀菌多了7.4%');
    expect(r.suggestion).toContain('先查熟制杀菌这一步');
    expect(text(r)).not.toMatch(TEMPLATE_RE);
  });

  it('袋不和 kg 换算：860袋 不出现在任何「剩 N%」里', () => {
    const r = buildChartInsight(yieldSankeyInsightChart(STEPS)!, finance)!;
    expect(r.finding).not.toMatch(/860袋[^；。]*剩/);
    expect(r.finding).not.toMatch(/172kg到860袋|172kg，到.*860/);
  });

  it('某道没报投入：照实说缺，不当 0', () => {
    const steps = STEPS.map((s, i) => (i === 3 ? { ...s, totalInput: Number.NaN } : s));
    const r = buildChartInsight(yieldSankeyInsightChart(steps)!, finance)!;
    expect(r.finding).toContain('快速冷却：没报投入量');
    expect(r.finding).not.toContain('到0kg');
  });
});

describe('工序名去掉共同的「产品·」前缀', () => {
  it('LSMDEMO 原名', async () => {
    const { shortStepNames } = await import('@/views/production-analytics/yieldFlowInsight');
    expect(shortStepNames(['演示·藤椒掌中宝·原料解冻修整', '演示·藤椒掌中宝·熟制杀菌'])).toEqual(['原料解冻修整', '熟制杀菌']);
    expect(shortStepNames(['解冻', '熟制'])).toEqual(['解冻', '熟制']);
  });
});

describe('多批混锅：投料合计 → 成品，成品没报不顶替', () => {
  const sources = [{ name: 'MB-1', quantity: 120, unit: 'kg' }, { name: 'MB-2', quantity: 40, unit: 'kg' }];
  it('成品产出缺：照实说，没有出成比例', () => {
    const r = buildChartInsight(mixSankeyInsightChart(sources, null, 'kg')!, noFinance)!;
    expect(r.finding).toContain('成品产出：还没报末道产出');
    expect(r.finding).toContain('投料最多的是MB-1，120kg，是第二多的MB-2的3.0倍');
    expect(text(r)).not.toMatch(/¥|占/);
  });
  it('同单位：成品是投料的百分之几', () => {
    const r = buildChartInsight(mixSankeyInsightChart(sources, 144, 'kg')!, finance)!;
    expect(r.finding).toContain('2个上游批次投料160kg，到成品产出144kg，是开头的90.0%');
  });
  it('单一来源不是混锅 → 不出', () => {
    expect(mixSankeyInsightChart([sources[0]], 100, 'kg')).toBeNull();
  });
});

// 进销存总览：LSMDEMO 录制（列表各取 10 条；采购单共 99、销售单 28、生产批次 40、入库批次 189）
const batches = [586, 860, 688, 840, 800, 640, 546, 656, 560, 704].map((q, i) => ({ q, unit: [2, 5, 7, 9].includes(i) ? '盒' : '袋' }));
function scInput(over: Partial<SupplyChainInsightInput> = {}): SupplyChainInsightInput {
  return {
    purchaseTotal: 21477.73,
    receivedQuantity: 189,
    consumedQuantity: summarizeQuantity(batches, (b) => b.q),
    productionBatches: 40,
    finishedGoods: summarizeQuantity(batches, (b) => b.q),
    salesTotal: 92705.6,
    sources: {
      purchase: { ok: true, listed: 10, total: 99 },
      material: { ok: true, listed: 10, total: 189 },
      batches: { ok: true, listed: 10, total: 40 },
      sales: { ok: true, listed: 10, total: 28 },
    },
    canViewPurchasePrice: true,
    canViewPrice: true,
    ...over,
  };
}

describe('供应链流向桑基：元 / 批 / 数量 混合，⛔ 不相邻硬换算', () => {
  it('有权限：各环节读数与卡片一致；不同单位不出「剩 N%」；袋盒混合说清；只算了 10 张单照实说', () => {
    const r = buildChartInsight(supplyChainInsightChart(scInput())!, finance)!;
    expect(r.finding).toContain('采购¥2.15万、入库189批、生产40批、出库¥9.27万，单位或口径各不相同，不互相换算');
    expect(r.finding).toContain('领用：批次单位不一致，没法相加');
    expect(r.finding).toContain('采购额只算了列表里的10张采购单（共99张）');
    expect(r.finding).toContain('出库额只算了列表里的10张销售单（共28张）');
    expect(r.finding).not.toMatch(/剩\d|是开头的/);
    expect(text(r)).not.toMatch(TEMPLATE_RE);
  });

  it('入库批次和生产批次同是「批」也不互比', () => {
    const r = buildFlowInsight(supplyChainInsightChart(scInput())!, finance)!;
    expect(r.finding).not.toMatch(/入库→生产|生产→/);
  });

  it('无价格权限（也无采购价权限）：不出 ¥，不出占比，说明不显示金额', () => {
    const r = buildChartInsight(supplyChainInsightChart(scInput({ canViewPurchasePrice: false, canViewPrice: false }))!, noFinance)!;
    expect(text(r)).not.toMatch(/¥|元|占/);
    expect(r.finding).toContain('无采购价权限，不显示采购额');
    expect(r.finding).toContain('无价格权限，不显示金额环节');
    expect(r.finding).toContain('入库189批、生产40批');
    expect(r.finding).not.toContain('出库额');
  });

  it('单一单位时成品与领用同口径才比：成品是领用的百分之几', () => {
    const one = [{ q: 100, unit: 'kg' }, { q: 50, unit: 'kg' }];
    const r = buildChartInsight(supplyChainInsightChart(scInput({
      consumedQuantity: summarizeQuantity(one, (b) => b.q),
      finishedGoods: summarizeQuantity(one, (b) => b.q * 0.9),
    }))!, finance)!;
    expect(r.finding).toContain('领用→成品，从150kg到135kg，剩90.0%');
  });

  it('接口没取到：说清是哪一环节没取到，不写 0', () => {
    const r = buildChartInsight(supplyChainInsightChart(scInput({
      sources: { purchase: { ok: true, listed: 10, total: 10 }, material: { ok: false, listed: 0, total: 0 }, batches: { ok: true, listed: 10, total: 10 }, sales: { ok: true, listed: 10, total: 10 } },
    }))!, finance)!;
    expect(r.finding).toContain('入库：入库记录没取到');
    expect(r.finding).not.toContain('入库0批');
  });
});

describe('FLOW 路由', () => {
  it('chartType funnel / sankey 或带 flow → FLOW 族；无 flow 数据 → null', () => {
    const bare: ChartWithMeta = { chartType: 'sankey', meta: { xDim: 'category', yMetric: 'other', aggregation: 'sum', domain: 'factory' } };
    expect(buildChartInsight(bare, finance)).toBeNull();
  });
});

describe('接线：三页都把分析挂在图下，且不调 Tier2', () => {
  it.each([
    ['src/views/crm/opportunity/SalesOpportunityFunnel.vue', 'funnelInsight'],
    ['src/views/analytics/SupplyChainOverview.vue', 'sankeyInsight'],
    ['src/views/production-analytics/M67YieldCost.vue', 'sankeyInsight'],
    ['src/views/production-analytics/M67YieldCost.vue', 'mixInsight'],
  ])('%s → %s', (file, prop) => {
    const src = readFileSync(resolve(__dirname, '../../../../..', file), 'utf-8');
    const block = new RegExp(`<ChartInsightProvider\\s+:chart="${prop}"[^>]*:auto-tier2="false"`, 's');
    expect(src).toMatch(block);
  });
});
