/**
 * 商机漏斗 → 一图一分析输入（问题 40，FLOW 族）。
 * 与图上同一组阶段（不含丢单）、同一个数量；阶段没有记录就是 0 个（接口只返回有记录的阶段）。
 * 分析句子由 smart-bi chartInsight 的 FLOW 族现算：相邻阶段转化、转化最低的一步。
 */
import type { ChartWithMeta } from '@/views/smart-bi/components/chartInsight';
import { STAGE_META, type FunnelStatEntry } from '@/api/salesOpportunity';

export function funnelStages(stats: FunnelStatEntry[]): Array<{ label: string; value: number }> {
  return STAGE_META.filter((m) => m.value !== 'CLOSED_LOST').map((m) => ({
    label: m.label,
    value: stats.find((s) => s.stage === m.value)?.count || 0,
  }));
}

export function funnelInsightChart(stats: FunnelStatEntry[], loaded: boolean): ChartWithMeta | null {
  if (!loaded) return null;
  return {
    chartType: 'funnel',
    title: '商机漏斗（按数量）',
    meta: { xDim: 'category', yMetric: 'count', aggregation: 'count', domain: 'factory', unit: '个' },
    flow: {
      kind: 'funnel',
      stages: funnelStages(stats).map((s) => ({ ...s, unit: '个' })),
    },
  };
}
