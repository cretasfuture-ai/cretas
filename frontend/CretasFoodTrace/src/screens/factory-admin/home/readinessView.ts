import type { ReadinessDomain } from '../../../services/api/factoryReadinessApiClient';

export const DOMAIN_STATE_LABEL: Record<ReadinessDomain['state'], string> = {
  READY: '可以开展',
  MISSING: '还差资料',
  UNKNOWN: '无法判断',
  NOT_APPLICABLE: '本次不适用',
};

export const CHECK_STATE_LABEL = { MET: '已满足', MISSING: '缺少', UNKNOWN: '无法判断' } as const;

/** 每个业务对应的现有首页栈页面；就绪页只跳转，不在这里写任何单据。 */
export const FIRST_ACTION_SCREEN = {
  PURCHASE: 'PurchaseOrderList',
  PRODUCTION: 'ProductionPlanManagement',
  SHIPPING: 'SalesOrderList',
} as const;

export type FirstActionScreen = (typeof FIRST_ACTION_SCREEN)[ReadinessDomain['domain']];

/** 只有「适用且全部满足」的业务才有首笔入口；缺项时返回 null，不给去下单的路。 */
export function firstActionTarget(domain: ReadinessDomain): { screen: FirstActionScreen; label: string } | null {
  if (domain.applicability !== 'APPLICABLE' || domain.state !== 'READY' || !domain.firstAction) return null;
  return { screen: FIRST_ACTION_SCREEN[domain.domain], label: domain.firstAction.label };
}

export function missingChecks(domain: ReadinessDomain) {
  return domain.checks.filter((check) => check.state !== 'MET');
}
