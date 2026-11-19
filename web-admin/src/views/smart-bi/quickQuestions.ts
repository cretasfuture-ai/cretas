/**
 * 问数据(AI) 默认快捷问题: 餐饮租户保留餐饮向, 其余 (食品工厂) 给工厂向。
 * 工厂向的每一句都是识别链已能正确路由的原话 (2026-11-18 LSMDEMO 实测: 餐饮 chip 在食品厂账号下答非所问)。
 */
export const FACTORY_QUICK_QUESTIONS: readonly string[] = [
  '本月销售额是多少',
  '这个月毛利最低的产品是哪些',
  '哪个客户欠款最多',
  '哪些原料价格涨得最多',
  '最近有哪些批次出成率异常',
  '本月人工成本占比',
];

export function isRestaurantFactoryType(factoryType?: string | null): boolean {
  return factoryType === 'RESTAURANT';
}

/** 仅在没有按数据集推断出专属 domain (即走 default) 时才按租户类型换题; 上传数据集的 domain chip 不动。 */
export function pickDefaultQuickQuestions(
  domainQuestions: string[],
  isDefaultDomain: boolean,
  factoryType?: string | null,
): string[] {
  if (isDefaultDomain && !isRestaurantFactoryType(factoryType)) return [...FACTORY_QUICK_QUESTIONS];
  return domainQuestions;
}
