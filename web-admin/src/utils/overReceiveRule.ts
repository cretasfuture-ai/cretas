/**
 * 仓库入库多收规则 (纪律排查 A11, 本厂配置) 的前端显示与防呆。
 * 判定以服务端为准 (ProductionPlanServiceImpl.assertOverReceiptAllowed); 这里只负责「预先显示边界」。
 */
import type { OverReceiveRule } from '@/api/productionPlan';

/** 读不到规则时的显示口径: 与服务端默认一致 (硬拦), ⛔ 不按宽处理。 */
export const DEFAULT_OVER_RECEIVE_RULE: OverReceiveRule = { mode: 'BLOCK', rate: 0 };

/** 本厂允许的最大实收; undefined = 不设上限 (一律填原因放行)。 */
export function overReceiveMax(reported: number, rule: OverReceiveRule): number | undefined {
  if (rule.mode === 'ALWAYS_WITH_REASON') return undefined;
  if (rule.mode === 'WITHIN_RATE') return Math.round(reported * (1 + rule.rate) * 100) / 100;
  return reported;
}

export function overReceiveExceeds(reported: number, received: number, rule: OverReceiveRule): boolean {
  const max = overReceiveMax(reported, rule);
  return max !== undefined && received > max;
}

export function overReceiveHelp(reported: number, unit: string, rule: OverReceiveRule): string {
  const reportedText = `${reported} ${unit}`;
  if (rule.mode === 'ALWAYS_WITH_REASON') {
    return `生产报产 ${reportedText}。本厂允许多收，多出部分超过称重容差时须选差异原因和责任侧。`;
  }
  if (rule.mode === 'WITHIN_RATE') {
    return `本厂允许多收 ${Math.round(rule.rate * 10000) / 100}%（最多 ${overReceiveMax(reported, rule)} ${unit}），`
      + '多出部分超过称重容差时须选差异原因和责任侧；再多需先退回生产修正结单。';
  }
  return `上限为生产报产 ${reportedText}，超过上限需先退回生产修正结单。`;
}

export function overReceiveBlockedMessage(reported: number, unit: string, rule: OverReceiveRule): string {
  return rule.mode === 'WITHIN_RATE'
    ? `仓库实收超过本厂允许的多收上限 ${overReceiveMax(reported, rule)} ${unit}，请先让生产修正结单`
    : '仓库实收不能超过生产报产，请先让生产修正结单';
}
