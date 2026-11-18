/**
 * AI 问答的问候语与快捷问题 (LSM-COSTBI 第 9 项)。
 *
 * - 问候语随当前数据源变化 (切换 selectedUploadId 后要重写, 不能只在挂载时写一次)。
 * - 工厂租户 (businessDomain !== 'RESTAURANT') 的快捷问题用工厂词, 不出现门店 / 菜品 / 客单价。
 */

export type BusinessDomain = 'FACTORY' | 'RESTAURANT' | 'LOGISTICS' | string | null | undefined;

/** 餐饮租户: 按文件名推断的数据类型 → 快捷问题。 */
export const RESTAURANT_QUICK_QUESTIONS: Record<string, string[]> = {
  review: [
    '客户评价情况', '差评最多的门店', '哪些菜品差评多',
    'VIP 评价情况', '投诉最集中的问题', '哪个城市评价最低',
    '服务分排名', '环境分对比',
  ],
  member: [
    '会员卡数据有什么发现', 'VIP 占比', '充值最多的会员',
    '会员等级分布', '余额最高的客户', '会员消费频次',
    '储值卡使用率', '会员流失率',
  ],
  finance: [
    '总营业额', '哪家店利润最高', '成本结构',
    '毛利率排名', '门店营收对比', '同比增长',
    '费用占比', '收入趋势',
  ],
  inventory: [
    '库存周转情况', '滞销库存', '采购金额排名',
    '损耗率', '进货 Top 10', '库存预警',
  ],
  default: [
    '畅销品 Top 5', '哪家店业绩最好', '慢销菜品',
    '周末周中对比', '总营业额', '门店销售对比',
    '商品分类占比', '客单价分析',
  ],
};

/** 工厂租户: 用生产 / 成本 / 采购词, 均能命中已物化模板 (Top 排名 / 月度趋势 / 占比 / 异常 / 帕累托)。 */
export const FACTORY_QUICK_QUESTIONS: Record<string, string[]> = {
  finance: [
    '成本构成占比', '收入趋势', '产品成本排名',
    '费用占比', '月度趋势', '成本异常检测',
  ],
  inventory: [
    '库存周转情况', '采购金额排名', '供应商排名',
    '损耗率', '进货 Top 10', '库存预警',
  ],
  default: [
    '产品产量排名 Top 5', '月度趋势', '每月产量变化',
    '成本构成占比', '不良品排名', '供应商采购金额排名',
    '异常检测', '帕累托分析',
  ],
};

export function isRestaurantDomain(domain: BusinessDomain): boolean {
  return String(domain ?? '').toUpperCase() === 'RESTAURANT';
}

export function quickQuestionsFor(businessDomain: BusinessDomain, fileDomain: string): string[] {
  if (isRestaurantDomain(businessDomain)) {
    return RESTAURANT_QUICK_QUESTIONS[fileDomain] || RESTAURANT_QUICK_QUESTIONS.default;
  }
  return FACTORY_QUICK_QUESTIONS[fileDomain] || FACTORY_QUICK_QUESTIONS.default;
}

export function buildWelcomeText(businessDomain: BusinessDomain, dataSourceLabel: string): string {
  const sourceHint = dataSourceLabel
    ? `\n\n当前${dataSourceLabel}`
    : '\n\n提示：暂无上传数据，建议先在"数据分析"页面上传 Excel 文件。';
  const scope = isRestaurantDomain(businessDomain) ? '销售、财务、库存' : '生产、成本、采购、库存';
  return `您好！我是 SmartBI 智能助手，可以帮您分析${scope}等数据。您可以选择下方的分析模板快速开始，或直接输入问题。${sourceHint}`;
}
