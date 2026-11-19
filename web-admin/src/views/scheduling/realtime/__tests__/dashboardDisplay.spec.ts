import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { LineSchedule } from '@/api/scheduling';
import { formatRate, formatWorkerCount, probabilityChartPoints, progressChartPoints } from '../dashboardDisplay';

function row(partial: Partial<LineSchedule>): LineSchedule {
  return {
    id: 'x', planId: 'p', productionLineId: 'l', productionLineName: '一号线', batchId: 1, batchNumber: 'B',
    sequenceOrder: 0, plannedStartTime: '', plannedEndTime: '', status: 'in_progress', workerAssignments: [],
    ...partial,
  } as LineSchedule;
}

const scheduled = row({ id: 's1', batchNumber: 'B-1', completionRate: 40, predictedCompletionProb: 0.8, assignedWorkerCount: 3 });
const workflow = row({ id: 'batch-7', batchNumber: 'B-7', source: 'WORKFLOW_BATCH',
  completionRate: undefined, predictedCompletionProb: undefined, assignedWorkerCount: undefined });

describe('调度实时监控: 量不到的值不画成 0', () => {
  it('进度图只画有完成率的行, 工作流批次算进 skipped 而不是 0%', () => {
    const { points, skipped } = progressChartPoints([scheduled, workflow]);
    expect(points).toEqual([{ name: 'B-1', value: 40, status: 'in_progress' }]);
    expect(skipped).toBe(1);
  });

  it('真实的 0% 完成率照样画 (只排除 null/undefined)', () => {
    const { points } = progressChartPoints([row({ batchNumber: 'B-0', completionRate: 0 })]);
    expect(points.map(p => p.value)).toEqual([0]);
  });

  it('概率图只画有预测概率的行', () => {
    const { points, skipped } = probabilityChartPoints([scheduled, workflow]);
    expect(points).toHaveLength(1);
    expect(points[0].value).toBeCloseTo(80);
    expect(skipped).toBe(1);
  });

  it('派工人数为空显示 -, 利用率为空显示 暂无数据', () => {
    expect(formatWorkerCount(undefined)).toBe('-');
    expect(formatWorkerCount(null)).toBe('-');
    expect(formatWorkerCount(0)).toBe('0');
    expect(formatRate(null)).toBe('暂无数据');
    expect(formatRate(0.5)).toBe('50%');
    expect(formatRate(0)).toBe('0%');
  });

  it('实时页确实用了这些规则 (没有回到 || 0 兜底)', () => {
    const vue = readFileSync(resolve(__dirname, '../index.vue'), 'utf8');
    expect(vue).toContain('progressChartPoints(dashboard.value.todaySchedules)');
    expect(vue).toContain('probabilityChartPoints(dashboard.value.todaySchedules)');
    expect(vue).toContain('formatWorkerCount(schedule.assignedWorkerCount)');
    expect(vue).toContain('formatRate(dashboard.lineStats?.utilizationRate)');
    expect(vue).not.toMatch(/completionRate \|\| 0,/);
    expect(vue).not.toMatch(/predictedCompletionProb \|\| 0/);
    expect(vue).not.toMatch(/assignedWorkerCount \|\| 0/);
  });
});
