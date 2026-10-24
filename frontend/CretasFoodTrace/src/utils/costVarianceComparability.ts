import type { CostVarianceReportDTO } from '../services/api/reportApiClient';

/**
 * 成本差异「无法比较」时的统一文案。
 *
 * 后端在没有独立于实际成本的标准成本时下发 comparable=false, 标准 / 差异 / 差异率为 null。
 * 原来后端拿「实际成本 × 0.95」当标准, 屏上恒显示约 +5.26% —— 那是编出来的数。
 */
export const COST_VARIANCE_NOT_COMPARABLE_TEXT = '无法比较：缺少独立标准成本';

type ComparabilityFields = Pick<CostVarianceReportDTO, 'comparable' | 'totalVarianceRate'>;

/**
 * 只有后端没说「不可比较」且确实给出了差异率时才可比较。
 * ⛔ 不把 null 差异率当成 0% 显示。
 */
export function isCostVarianceComparable(data: ComparabilityFields | null | undefined): boolean {
  if (!data) return false;
  if (data.comparable === false) return false;
  return typeof data.totalVarianceRate === 'number' && Number.isFinite(data.totalVarianceRate);
}

/** 标准成本: 新字段 totalBomCost 优先, 旧字段 totalPlannedCost 兼容; 都没有为 null。 */
export function standardCostOf(
  data: Pick<CostVarianceReportDTO, 'totalBomCost' | 'totalPlannedCost'>
): number | null {
  if (typeof data.totalBomCost === 'number') return data.totalBomCost;
  if (typeof data.totalPlannedCost === 'number') return data.totalPlannedCost;
  return null;
}
