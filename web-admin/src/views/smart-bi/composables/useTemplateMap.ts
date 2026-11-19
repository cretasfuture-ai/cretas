/**
 * Week 6 — static mapping: which templates show on which page + their display labels.
 *
 * Why hardcoded instead of backend-driven:
 * - Page layout is a UI concern; backend shouldn't own UX decisions.
 * - Template codes evolve via PR; FE changes in lockstep.
 * - CI would catch a rename when all requested codes return as
 *   never_materialized_codes (100% miss rate).
 */

/** Pages → template_code[] (order matters, renders top-to-bottom left-to-right) */
export const PAGE_TEMPLATE_MAP: Record<string, readonly string[]> = {
  dashboard: [
    'monthly_trend',
    'top_n_by_dim',
    'category_distribution',
    'anomaly_detection',
  ],
  finance: [
    'profit_loss_statement',
    'revenue_management_report',
    'stored_value_card_consumption',
    'groupon_channel_breakdown',
  ],
  trend: [
    'monthly_trend',
    'period_comparison_trend',
    'weekday_weekend_pattern',
    'monthly_anomaly',
  ],
} as const;

export type TemplatePageKey = keyof typeof PAGE_TEMPLATE_MAP;

/** Stable 中文 display titles — do NOT use AI-generated titles, they drift.
 *
 * Covers every template registered in the Python TemplateRegistry
 * (backend/python/smartbi/services/materialized_analytics/templates/registry.py).
 * Titles here are the short, chip-friendly Chinese labels shown in the
 * "猜你想问" recommendation area; the backend registry holds the canonical
 * long title and is the ultimate source of truth (the list-factory-templates
 * endpoint returns the registry title, this map is the FE display override). */
export const TEMPLATE_TITLES: Record<string, string> = {
  // W1 generic
  monthly_trend: '时间趋势',
  top_n_by_dim: '维度 Top N',
  category_distribution: '分类占比',
  anomaly_detection: '异常值检测',
  pareto_analysis: '帕累托分析',
  // 财务
  profit_loss_statement: '利润表',
  revenue_management_report: '营收管理报表',
  stored_value_card_consumption: '储值卡消费分析',
  groupon_channel_breakdown: '团购渠道明细',
  business_overview_summary: '营业概况汇总',
  payment_method_mix: '付款方式分布',
  refund_analysis: '退菜/损耗分析',
  reverse_checkout_stats: '反结账统计',
  // 趋势
  period_comparison_trend: '营收趋势对比',
  weekday_weekend_pattern: '周末平日对比',
  monthly_anomaly: '月度异常',
  // 渠道 / 门店 / 员工
  channel_analysis: '堂食外卖渠道',
  store_performance: '门店业绩',
  staff_performance: '员工业绩',
  table_type_comparison: '堂食/包厢/外卖对比',
  store_customer_stratification: '门店客单分层',
  // 会员
  member_consumption: '会员卡消费',
  member_deep_analytics: '会员卡深度分析',
  // 营销
  promotion_impact: '优惠券效果',
  // 评价
  reviews_sentiment_summary: '评价情感',
  // 后厨
  kitchen_dispatch_heatmap: '传菜厨房统计',
  // 进销存
  purchase_inventory_inflow: '采购入库分析',
  // 制造
  defect_rate_top_n: '不良率 Top N',
};

/** What fields must be present in the source Excel for this template to match.
 * Used in empty-state copy only — no runtime enforcement. */
export const TEMPLATE_REQUIRED_FIELDS: Record<string, string> = {
  profit_loss_statement: '营业收入 / 成本 / 毛利',
  revenue_management_report: '营业额 / 渠道 / 门店',
  stored_value_card_consumption: '储值卡消费金额 / 会员ID',
  groupon_channel_breakdown: '渠道 / 代金券类型 / 消费金额',
  monthly_trend: '时间字段 + 数值指标',
  period_comparison_trend: '时间字段 + 数值指标 (≥2 月)',
  weekday_weekend_pattern: '每日营业数据',
  monthly_anomaly: '月度数值序列 (≥3 月)',
  top_n_by_dim: '类别维度 + 数值指标',
  category_distribution: '分类维度 + 数值指标',
  anomaly_detection: '数值序列',
};

export function getPageCodes(pageKey: string): readonly string[] {
  return PAGE_TEMPLATE_MAP[pageKey] || [];
}

/** 餐饮专属模板（储值卡/团购/门店营业额渠道/菜品等）：食品工厂、物流不展示。 */
export const RESTAURANT_ONLY_TEMPLATE_CODES: ReadonlySet<string> = new Set([
  'revenue_management_report',
  'stored_value_card_consumption',
  'groupon_channel_breakdown',
]);

/** 按业务域(auth store businessDomain: FACTORY/RESTAURANT/LOGISTICS)过滤模板。
 * 仅 FACTORY / LOGISTICS 隐藏餐饮专属；餐饮与未知(空)域保持原样。 */
export function getVisiblePageCodes(
  pageKey: string,
  businessDomain: string | undefined,
): readonly string[] {
  const codes = getPageCodes(pageKey);
  const domain = (businessDomain || '').toUpperCase();
  if (domain !== 'FACTORY' && domain !== 'LOGISTICS') return codes;
  return codes.filter((c) => !RESTAURANT_ONLY_TEMPLATE_CODES.has(c));
}

export function getTemplateTitle(code: string): string {
  return TEMPLATE_TITLES[code] || code;
}

export function getRequiredFields(code: string): string {
  return TEMPLATE_REQUIRED_FIELDS[code] || '对应业务数据';
}
