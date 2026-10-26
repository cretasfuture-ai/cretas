import type { FactoryRecommendation } from '@/api/smartbi/factory-charts';

export interface AnalysisFamily {
  id: string;
  label: string;
  domain: string;
  items: FactoryRecommendation[];
}

export const chartTypeNames: Record<string, string> = {
  line: '折线', bar: '柱形', bar_horizontal: '横条', area: '面积', pie: '饼图', donut: '环形',
  nested_donut: '双环', scatter: '散点', waterfall: '瀑布', sankey: '流向', bullet: '子弹图',
  dual_axis: '双轴', budget_comparison: '计划对比', combination: '组合', funnel: '漏斗', gantt: '时间安排',
  gauge: '仪表', pareto: '帕累托', radar: '雷达', slope: '两期变化', sunburst: '旭日', treemap: '矩形树图',
  boxplot: '箱线', heatmap: '热力', matrix_heatmap: '矩阵热力', correlation_matrix: '相关矩阵', parallel: '平行坐标',
};
export const chartTypeLabel = (item: FactoryRecommendation) => chartTypeNames[item.chart.chartType] || '财务图表';

/** Server owns metric identity. The fallback only supports older server responses. */
export function analysisFamilies(items: readonly FactoryRecommendation[]): AnalysisFamily[] {
  const groups = new Map<string, AnalysisFamily>();
  for (const item of items) {
    const id = `${item.domain}:${item.analysis?.familyId || item.id.split(/[@:]/)[0]}`;
    let group = groups.get(id);
    if (!group) {
      group = { id, label: item.analysis?.familyLabel || item.metric, domain: item.domain, items: [] };
      groups.set(id, group);
    }
    group.items.push(item);
  }
  return [...groups.values()];
}

export function recommendedAnalysisIds(items: readonly FactoryRecommendation[], domain: string, familyId = 'all'): string[] {
  return analysisFamilies(items).filter(group => (domain === 'all' || group.domain === domain)
    && (familyId === 'all' || group.id === familyId)).slice(0, 4).map(group => group.items[0].id);
}

/** Switching a drawing or unit replaces only that family's view, without recomputing any values. */
export function replaceFamilySelection(selected: readonly string[], group: AnalysisFamily, id: string | null): string[] {
  const familyIds = new Set(group.items.map(item => item.id));
  return [...selected.filter(value => !familyIds.has(value)), ...(id && familyIds.has(id) ? [id] : [])];
}
