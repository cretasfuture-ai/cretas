import type { FactoryRecommendation } from '../types/factoryCharts';

export interface ChartFamily {
  id: string;
  label: string;
  variants: FactoryRecommendation[];
}

export interface GuideSubject { id: string; label: string; }

/** The server owns labels. This fallback is only for old candidates without analysis metadata. */
export function legacyFamilyId(item: FactoryRecommendation): string {
  const semantic = item.id.split(/[@/:]/, 1)[0];
  return semantic || item.id || item.chart.chartType;
}

export function familyFor(item: FactoryRecommendation): { id: string; label: string } {
  const id = item.analysis?.familyId?.trim() || legacyFamilyId(item);
  const label = item.analysis?.familyLabel?.trim() || item.title || item.metric || item.chart.chartType;
  return { id, label };
}

export function groupChartFamilies(items: readonly FactoryRecommendation[]): ChartFamily[] {
  const groups = new Map<string, ChartFamily>();
  items.forEach(item => {
    const family = familyFor(item);
    const existing = groups.get(family.id);
    if (existing) existing.variants.push(item);
    else groups.set(family.id, { id: family.id, label: family.label, variants: [item] });
  });
  return [...groups.values()];
}

export function selectedFamilyIds(items: readonly FactoryRecommendation[], selectedIds: readonly string[] | null): Set<string> {
  if (selectedIds == null) return new Set(groupChartFamilies(items).map(group => group.id));
  const selected = new Set(selectedIds);
  return new Set(items.filter(item => selected.has(item.id)).map(item => familyFor(item).id));
}

export function selectionForFamilies(items: readonly FactoryRecommendation[], familyIds: ReadonlySet<string>, variants: ReadonlyMap<string, string>, selectedIds: readonly string[] = []): string[] {
  const output: string[] = [];
  groupChartFamilies(items).forEach(group => {
    if (!familyIds.has(group.id)) return;
    const preferred = variants.get(group.id) || group.variants.find(item => selectedIds.includes(item.id))?.id;
    const item = group.variants.find(candidate => candidate.id === preferred) || group.variants[0];
    if (item) output.push(item.id);
  });
  return output;
}

const kinds: Record<string, string> = { line: '折线', area: '面积', bar: '柱形', bar_horizontal: '横条', pie: '饼图', donut: '环形', nested_donut: '双环', scatter: '散点', boxplot: '箱线', dual_axis: '双轴', heatmap: '热力', matrix_heatmap: '矩阵热力', budget_comparison: '计划对比', combination: '组合', bullet: '子弹图', gauge: '仪表', pareto: '帕累托', sankey: '流向', waterfall: '瀑布', slope: '两期比较' };
export function chartVariantLabel(item: FactoryRecommendation): string {
  return `${kinds[item.chart.chartType] || '财务图表'} · ${item.unit || '单位未标注'}`;
}

export function guideSubjects(subjects: readonly GuideSubject[] | undefined): GuideSubject[] {
  // Product/subject ids are opaque server-owned values. Never substitute a
  // family id here: the backend must receive a real subject id when chosen.
  return (subjects || []).filter(subject => subject.id && subject.label);
}

export function filterSubjects(subjects: readonly GuideSubject[], query: string): GuideSubject[] {
  const needle = query.trim().toLowerCase();
  return subjects.filter(subject => !needle || subject.label.toLowerCase().includes(needle)).slice(0, 30);
}
