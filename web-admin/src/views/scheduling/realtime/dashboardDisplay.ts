import type { LineSchedule, SchedulingDashboard } from '@/api/scheduling';

/**
 * 调度实时监控的显示规则: 后端量不到的值是 null (工作流批次没有完成率 / 完成概率 / 派工人数),
 * ⛔ 不画成 0% / 0 / 0 人 —— 0 和「真的是 0」在图上长得一模一样。与调度首页 (scheduling/index.vue) 同一口径。
 */

export interface ChartPoint {
  name: string;
  value: number;
  status: LineSchedule['status'];
  probability?: number;
}

/** 进度图: 只画有完成率的行; skipped = 没有完成率 (如工作流批次) 而没画的条数。 */
export function progressChartPoints(schedules: LineSchedule[]): { points: ChartPoint[]; skipped: number } {
  const points = schedules
    .filter(s => s.completionRate !== null && s.completionRate !== undefined)
    .map(s => ({ name: s.batchNumber, value: s.completionRate as number, status: s.status }));
  return { points, skipped: schedules.length - points.length };
}

/** 完成概率图: 只画有预测概率的行 (百分数); skipped = 没有概率而没画的条数。 */
export function probabilityChartPoints(schedules: LineSchedule[]): { points: ChartPoint[]; skipped: number } {
  const points = schedules
    .filter(s => s.predictedCompletionProb !== null && s.predictedCompletionProb !== undefined)
    .map(s => ({
      name: s.batchNumber,
      value: (s.predictedCompletionProb as number) * 100,
      status: s.status,
      probability: s.predictedCompletionProb as number,
    }));
  return { points, skipped: schedules.length - points.length };
}

/** 派工人数: 没有派工记录 → '-'。 */
export function formatWorkerCount(count: number | null | undefined): string {
  return count === null || count === undefined ? '-' : String(count);
}

/** 0~1 的比率: null → '-'。 */
export function formatRate(rate: number | null | undefined): string {
  return rate === null || rate === undefined ? '-' : `${(rate * 100).toFixed(0)}%`;
}

export function lineUtilizationDisplay(
  dashboard: Pick<SchedulingDashboard, 'lineStats' | 'unavailableReasons'> | null,
) {
  const reasons = dashboard?.unavailableReasons;
  const count = dashboard?.lineStats?.unattributedBatches ?? 0;
  const hint = count > 0 ? `${count} 个批次未指定产线，未计入利用率` : null;
  const rateReason = reasons?.['lineStats.utilizationRate'] || '今日有任务的启用产线 ÷ 启用产线';
  const attributionReason = hint && (reasons?.['lineStats.unattributedBatches'] || hint);

  return {
    value: formatRate(dashboard?.lineStats?.utilizationRate),
    hint,
    title: attributionReason ? `${rateReason}\n${attributionReason}` : rateReason,
  };
}
