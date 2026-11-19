/**
 * 进销存闭环总览「供应链流向」桑基图 → 一图一分析输入（问题 40，FLOW 族）。
 *
 * 六个环节单位各不相同：采购 / 出库是金额，入库 / 生产是批次数（两种批次，不是一回事），
 * 领用 / 成品是数量（批次单位不一致时合计不成立）。⛔ 不在相邻环节之间硬换算：
 * chartInsight 的 FLOW 族只让「单位与口径都相同」的环节互相比。
 * 金额和领用/成品只合计了接口返回的那一页单据，口径写进 scope，不同口径不互比，并在结论里照实说。
 */
import type { ChartWithMeta, FlowStage } from '@/views/smart-bi/components/chartInsight';
import type { QuantitySummary } from './quantitySummary';
import { formatNumber } from '@/utils/format-number';

/** 读数照上方统计卡片的写法（formatNumber），分析里的数与卡片逐字一致。 */
const money = (v: number | null) => (v === null ? undefined : `¥${formatNumber(v)}`);
const count = (v: number | null) => (v === null ? undefined : `${formatNumber(v, 0)}批`);

export interface ListSource {
  /** 接口是否取到了 */
  ok: boolean;
  /** 列表里实际拿到的条数 */
  listed: number;
  /** 接口报的总条数 */
  total: number;
}

export interface SupplyChainInsightInput {
  purchaseTotal: number | null;
  receivedQuantity: number;
  consumedQuantity: QuantitySummary;
  productionBatches: number;
  finishedGoods: QuantitySummary;
  salesTotal: number;
  sources: { purchase: ListSource | null; material: ListSource; batches: ListSource; sales: ListSource };
  canViewPurchasePrice: boolean;
  /** 出库/销售额的价格权限（与上方卡片同一个开关） */
  canViewPrice: boolean;
}

function quantityMissing(q: QuantitySummary): string {
  if (q.reason === 'mixed-units') return '批次单位不一致，没法相加';
  if (q.reason === 'missing-unit') return '部分批次没填单位，没法可靠相加';
  return '批次数量里有无效值，没法相加';
}

function partial(src: ListSource, measure: string, thing: string): string | undefined {
  return src.total > src.listed ? `只算了列表里的${src.listed}${measure}${thing}（共${src.total}${measure}）` : undefined;
}

export function supplyChainInsightChart(input: SupplyChainInsightInput): ChartWithMeta | null {
  const { sources } = input;
  const notes: string[] = [];
  const stages: FlowStage[] = [];

  if (input.canViewPurchasePrice && sources.purchase) {
    const p = sources.purchase;
    const scope = p.ok ? partial(p, '张', '采购单') : undefined;
    if (scope) notes.push(`采购额${scope}`);
    stages.push({
      label: '采购', unit: '元', money: true, scope: scope ? 'purchase-page' : undefined,
      value: p.ok ? input.purchaseTotal : null,
      display: p.ok ? money(input.purchaseTotal) : undefined,
      missing: !p.ok ? '采购单没取到' : '有采购单金额读不到，合计不成立',
    });
  } else if (!input.canViewPurchasePrice) {
    notes.push('无采购价权限，不显示采购额');
  }

  stages.push({
    label: '入库', unit: '批', compareKey: '入库批次',
    value: sources.material.ok ? input.receivedQuantity : null, missing: '入库记录没取到',
    display: sources.material.ok ? count(input.receivedQuantity) : undefined,
  });

  const batchScope = sources.batches.ok ? partial(sources.batches, '个', '生产批次') : undefined;
  if (batchScope) notes.push(`领用、成品${batchScope}`);
  const qtyStage = (label: string, q: QuantitySummary): FlowStage => ({
    label,
    unit: q.unit ?? undefined,
    scope: batchScope ? 'batch-page' : undefined,
    value: sources.batches.ok && q.value !== null ? q.value : null,
    missing: !sources.batches.ok ? '生产批次没取到' : quantityMissing(q),
    display: sources.batches.ok && q.value !== null ? `${formatNumber(q.value, 2)}${q.unit ?? ''}` : undefined,
  });
  stages.push(qtyStage('领用', input.consumedQuantity));
  stages.push({
    label: '生产', unit: '批', compareKey: '生产批次',
    value: sources.batches.ok ? input.productionBatches : null, missing: '生产批次没取到',
    display: sources.batches.ok ? count(input.productionBatches) : undefined,
  });
  stages.push(qtyStage('成品', input.finishedGoods));

  const salesScope = sources.sales.ok ? partial(sources.sales, '张', '销售单') : undefined;
  // 看不到出库额时，它的口径说明也不写（不提金额环节的存在以外的细节）
  if (salesScope && input.canViewPrice) notes.push(`出库额${salesScope}`);
  stages.push({
    label: '出库', unit: '元', money: true, scope: salesScope ? 'sales-page' : undefined,
    value: sources.sales.ok ? input.salesTotal : null, missing: '销售单没取到',
    display: sources.sales.ok ? money(input.salesTotal) : undefined,
  });

  return {
    chartType: 'sankey',
    title: '供应链流向',
    meta: { xDim: 'category', yMetric: 'other', aggregation: 'sum', domain: 'factory' },
    flow: { kind: 'chain', stages, note: notes.length ? notes.join('；') : undefined },
  };
}
