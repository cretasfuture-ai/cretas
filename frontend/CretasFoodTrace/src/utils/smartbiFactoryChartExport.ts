import { FactoryChartExportContext, FactoryChartExportPayload, FactoryRecommendation } from '../types/factoryCharts';

const FORMULA_START = /^\s*[=+\-@]/;
export function safeSpreadsheetValue(value: unknown): unknown {
  return typeof value === 'string' && FORMULA_START.test(value) ? `'${value}` : value;
}
function sanitizeCell(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sanitizeCell);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, sanitizeCell(entry)]));
  return safeSpreadsheetValue(value);
}
function safeTable(table: unknown): Record<string, unknown> | null {
  if (!table) return null;
  if (Array.isArray(table)) {
    if (table.some(row => row === null || typeof row !== 'object' || Array.isArray(row))) return { headers: ['value'], rows: table.map(value => [sanitizeCell(value)]) };
    const values = table as Array<Record<string, unknown>>;
    const headers = [...new Set(values.flatMap(row => Object.keys(row)))];
    return { headers: headers.map(safeSpreadsheetValue), rows: values.map(row => headers.map(header => sanitizeCell(row[header]))) };
  }
  if (typeof table !== 'object') return null;
  const record = table as Record<string, unknown>;
  if (Array.isArray(record.headers) && Array.isArray(record.rows)) return { ...record, headers: record.headers.map(value => safeSpreadsheetValue(String(value))), rows: record.rows.map(sanitizeCell) };
  return sanitizeCell(record) as Record<string, unknown>;
}

export function buildFactoryChartExportPayload(items: readonly FactoryRecommendation[], images: Readonly<Record<string, string>>, context: FactoryChartExportContext): FactoryChartExportPayload {
  const selected = items.filter(item => context.selectedIds.includes(item.id));
  const charts = selected.map(item => ({ id: item.id, candidate_id: item.id, chartType: `${item.chart.chartType}__${item.id}`, source_chart_type: item.chart.chartType, title: item.title, metric: item.metric, domain: item.domain, source: item.source || context.sourceLabel, period: context.periodLabel, unit: item.unit, kpis: item.kpis, echartsOption: item.chart.echartsOption, tableData: safeTable(item.tableData), findings: item.findings, reason: item.reason, aiReason: item.aiReason || '' }));
  const analysisResults = Object.fromEntries(selected.map(item => [item.id, [`标题：${item.title}`, `来源：${item.source || context.sourceLabel}`, `期间：${context.periodLabel}`, `单位：${item.unit}`, `规则依据：${item.reason}`, item.aiReason ? `AI 补充：${item.aiReason}` : '', ...item.kpis.map(kpi => `KPI｜${kpi.label}：${kpi.value ?? '—'} ${kpi.unit}`), ...Object.entries(item.findings || {}).flatMap(([label, facts]) => (facts as string[]).map(fact => `${label === 'good' ? '做得好的' : label === 'attention' ? '需要关注' : '建议'}：${fact}`))].filter(Boolean).join('\n')]));
  const chartImages: Record<string, string> = {};
  for (const item of selected) { const image = images[item.id]; if (typeof image === 'string') chartImages[item.id] = image; }
  return { factory_id: context.factoryId, charts, chart_images: chartImages, analysis_results: analysisResults, chart_titles: Object.fromEntries(selected.map(item => [item.id, item.title])), period_label: context.periodLabel, report_title: '工厂经营分析报告', company_name: context.factoryId, year: context.year, period_type: context.periodType, start_month: context.startMonth, end_month: context.endMonth };
}
