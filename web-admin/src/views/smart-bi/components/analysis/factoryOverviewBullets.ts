/**
 * 经营分析页顶部四张概览卡 (工厂): 把业务系统里的真实读数写成几句事实。
 *
 * 原来这四张卡读餐饮 POS 汇总 (账单/门店), 工厂账号下全空, 趋势卡还写着「工作日日均营收 ¥0」。
 * 现在每张卡读工厂自己的数据: 销售订单 / 往来余额 / 近一年生产 / KPI。
 *
 * 规则: 没有读数就不写那一句 (⛔ 不把 null 写成 0); 一句都写不出时 empty 给出原因, 卡片显示它。
 * 同一组句子也是右侧 AI 面板的上下文 (summary / dataScope / unavailableMetrics)。
 */

export interface OverviewBlock {
  bullets: string[];
  /** 没有任何读数时给用户看的原因; 有读数时为 null */
  empty: string | null;
  /** 给 AI 面板的数据摘要 */
  summary: string;
  /** 给 AI 面板: 这些数字来自哪里、截至何时 */
  dataScope: string;
  /** 给 AI 面板: 这张卡没有、不能据此推断的指标 */
  unavailableMetrics: string[];
}

type Num = number | null | undefined;

const has = (v: Num): v is number => typeof v === 'number' && Number.isFinite(v);

/** 金额: 1 万以上用「万元」, 以下用「元」, 两位小数; 没有读数显示「—」。 */
export function fmtWan(v: Num): string {
  if (!has(v)) return '—';
  if (Math.abs(v) >= 1e4) return `${(v / 1e4).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}万元`;
  return `${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}元`;
}

const fmtQty = (v: number): string => v.toLocaleString('zh-CN', { maximumFractionDigits: 2 });

function block(bullets: string[], empty: string, dataScope: string, unavailableMetrics: string[]): OverviewBlock {
  return {
    bullets,
    empty: bullets.length ? null : empty,
    summary: bullets.length ? bullets.join('；') : empty,
    dataScope,
    unavailableMetrics,
  };
}

// ---------------------------------------------------------------- 销售订单

export interface FactorySalesSummaryLike {
  orderCount: number;
  effectiveOrderCount: number;
  cancelledCount: number;
  draftCount: number;
  rejectedCount: number;
  inFlightCount: number;
  orderAmount: number | null;
  ordersWithoutAmount: number;
  firstOrderDate: string | null;
  lastOrderDate: string | null;
  topCustomers: { name: string; orders: number; amount: number | null }[];
  topProducts: { name: string; unit: string | null; quantity: number | null; amount: number | null }[];
}

export function factorySalesOverview(s: FactorySalesSummaryLike | null | undefined): OverviewBlock {
  const scope = '业务系统销售订单，全部历史；金额取订单金额，不计已取消、草稿、财务驳回';
  const unavailable = ['回款', '毛利', '退货'];
  if (!s || s.orderCount <= 0) return block([], '业务系统里还没有销售订单', scope, unavailable);

  const bullets: string[] = [];
  const span = s.firstOrderDate && s.lastOrderDate ? `（${s.firstOrderDate} 至 ${s.lastOrderDate}）` : '';
  if (s.effectiveOrderCount > 0) {
    bullets.push(has(s.orderAmount)
      ? `有效订单 ${s.effectiveOrderCount} 张，订单金额 ${fmtWan(s.orderAmount)}${span}`
      : `有效订单 ${s.effectiveOrderCount} 张，都还没有填金额${span}`);
    if (has(s.orderAmount) && s.ordersWithoutAmount > 0) bullets.push(`其中 ${s.ordersWithoutAmount} 张没有填金额，未计入`);
  }
  if (s.inFlightCount > 0) bullets.push(`在途 ${s.inFlightCount} 张（已确认 / 待财务审核 / 财务已批准 / 处理中）`);

  const customer = s.topCustomers.find(c => has(c.amount));
  if (customer) bullets.push(`金额最大的客户：${customer.name}，${fmtWan(customer.amount)}（${customer.orders} 张）`);
  const product = s.topProducts.find(p => has(p.amount));
  if (product) {
    const qty = has(product.quantity) ? `${fmtQty(product.quantity)} ${product.unit ?? ''}`.trim() + '，' : '';
    bullets.push(`金额最高的产品：${product.name}，${qty}${fmtWan(product.amount)}`);
  }

  const excluded = [
    s.cancelledCount > 0 ? `已取消 ${s.cancelledCount} 张` : '',
    s.draftCount > 0 ? `草稿 ${s.draftCount} 张` : '',
    s.rejectedCount > 0 ? `财务驳回 ${s.rejectedCount} 张` : '',
  ].filter(Boolean);
  if (excluded.length) bullets.push(`另有${excluded.join('、')}，不计入金额`);

  return block(bullets, '业务系统里还没有有效的销售订单', scope, unavailable);
}

// ---------------------------------------------------------------- 往来余额

export interface FactoryFinanceLike {
  accountsReceivable?: Num;
  accountsPayable?: Num;
  customerPrepayments?: Num;
  // 下面几项不用: 收入取自空的发货表 (查不到时后端填 0), 成本/利润在这里也常为空;
  // 损益以「财务」看板上传的报表为准
  totalRevenue?: Num;
  totalCost?: Num;
  totalProfit?: Num;
}

export function factoryFinanceOverview(f: FactoryFinanceLike | null | undefined): OverviewBlock {
  const bullets: string[] = [];
  if (f && has(f.accountsReceivable)) bullets.push(`应收账款余额 ${fmtWan(f.accountsReceivable)}`);
  if (f && has(f.accountsPayable)) bullets.push(`应付账款余额 ${fmtWan(f.accountsPayable)}`);
  if (f && has(f.customerPrepayments)) bullets.push(`客户预收款 ${fmtWan(f.customerPrepayments)}`);
  return block(
    bullets,
    '业务系统里还没有应收、应付或预收记录',
    '业务系统往来余额，截至今天；收入、成本、利润以下方「财务」看板上传的报表为准',
    ['营业收入', '营业成本', '毛利', '毛利率', '净利润'],
  );
}

// ---------------------------------------------------------------- 近一年生产

export interface FactoryTrendLike {
  productionTrend?: { date: string; value?: Num }[] | null;
  costTrend?: { date: string; value?: Num }[] | null;
}

export interface ProductionByProductLike {
  productName: string;
  totalQuantity?: Num;
  unit?: string | null;
}

export function factoryTrendOverview(
  t: FactoryTrendLike | null | undefined,
  products: ProductionByProductLike[] | null | undefined,
): OverviewBlock {
  const bullets: string[] = [];
  const producing = (t?.productionTrend ?? []).filter(p => has(p.value) && (p.value as number) > 0);
  if (producing.length) {
    const last = producing.map(p => p.date).sort().at(-1);
    bullets.push(`近一年有 ${producing.length} 天有产出，最近一次在 ${last}`);
  }
  const costDays = (t?.costTrend ?? []).filter(c => has(c.value) && (c.value as number) > 0);
  if (costDays.length) {
    const total = costDays.reduce((sum, c) => sum + (c.value as number), 0);
    bullets.push(`近一年生产成本合计 ${fmtWan(total)}（${costDays.length} 天有成本记录）`);
  }
  const top = (products ?? []).filter(p => has(p.totalQuantity) && (p.totalQuantity as number) > 0)
    .sort((a, b) => (b.totalQuantity as number) - (a.totalQuantity as number))[0];
  if (top) bullets.push(`产量最多的产品：${top.productName}，${fmtQty(top.totalQuantity as number)} ${top.unit ?? ''}`.trim());

  return block(
    bullets,
    '近一年没有生产记录',
    '业务系统生产记录，近 365 天；产量各产品单位不同，不做合计',
    ['产量合计（单位不统一）', '良品率趋势'],
  );
}

// ---------------------------------------------------------------- KPI

export interface FactoryKpiLike {
  unitCost?: Num;
  bomVarianceRate?: Num;
  avgCycleTime?: Num;
  outputCompletionRate?: Num;
  [k: string]: unknown;
}

/** 概览只放读数可靠的四项; OEE、一次合格率、准时交付等在下方「KPI·指标」看板看 */
export function factoryKpiOverview(k: FactoryKpiLike | null | undefined): OverviewBlock {
  const bullets: string[] = [];
  if (k && has(k.unitCost)) bullets.push(`单位成本 ${k.unitCost.toFixed(2)} 元`);
  if (k && has(k.bomVarianceRate)) {
    bullets.push(`BOM 用料偏差 ${k.bomVarianceRate.toFixed(2)}%（目标 ≤5%${k.bomVarianceRate > 5 ? '，超出' : ''}）`);
  }
  if (k && has(k.avgCycleTime)) bullets.push(`平均生产周期 ${k.avgCycleTime.toFixed(2)} 小时`);
  if (k && has(k.outputCompletionRate)) bullets.push(`产量完成率 ${k.outputCompletionRate.toFixed(1)}%`);
  return block(
    bullets,
    'KPI 还没有可用的读数',
    '业务系统 KPI，截至今天',
    ['OEE', '一次合格率', '准时交付率'],
  );
}
