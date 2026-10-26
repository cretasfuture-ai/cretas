import {
  exportFactoryCharts,
  type FactoryChartExportFormat,
  type FactoryRecommendation,
} from '@/api/smartbi/factory-charts';

export interface FactoryChartExportContext {
  sourceLabel: string;
  source: string;
  periodLabel: string;
  year?: number;
  periodType?: string;
  startMonth?: number;
  endMonth?: number;
  factoryId: string;
  selectedIds: readonly string[];
  scopeLabel?: string;
}

export interface FactoryChartExportPayload {
  charts: Array<Record<string, unknown>>;
  chart_images: Record<string, string>;
  analysis_results: Record<string, string>;
  chart_titles?: Record<string, string>;
  period_label?: string;
  report_title?: string;
  headline?: { text: string; tone: 'good' | 'warn' | 'bad' | 'neutral'; facts: string[] } | null;
  findings?: { good: string[]; attention: string[]; suggestions: string[] };
  company_name?: string;
  year?: number;
  period_type?: string;
  start_month?: number;
  end_month?: number;
}

const FORMULA_START = /^\s*[=+\-@]/;

/** Prefix spreadsheet formula-looking values while keeping their visible meaning. */
export function safeSpreadsheetValue(value: unknown): unknown {
  if (typeof value !== 'string') return value;
  return FORMULA_START.test(value) ? `'${value}` : value;
}

function sanitizeCell(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sanitizeCell);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([key, entry]) => [key, sanitizeCell(entry)]));
  return safeSpreadsheetValue(value);
}

function safeTable(table: unknown): Record<string, unknown> | null {
  if (!table) return null;
  if (Array.isArray(table)) {
    if (table.some(row => row === null || typeof row !== 'object' || Array.isArray(row))) {
      return { headers: ['value'], rows: table.map(value => [sanitizeCell(value)]) };
    }
    const values = table as Array<Record<string, unknown>>;
    const headers = [...new Set(values.flatMap(row => Object.keys(row)))];
    return {
      headers: headers.map(safeSpreadsheetValue),
      rows: values.map(row => headers.map(header => sanitizeCell(row[header])))
    };
  }
  if (typeof table !== 'object') return null;
  const record = table as Record<string, unknown>;
  if (Array.isArray(record.headers) && Array.isArray(record.rows)) {
    const headers = record.headers.map(value => String(value));
    return {
      ...record,
      headers: headers.map(safeSpreadsheetValue),
      rows: record.rows.map(row => sanitizeCell(row)),
    };
  }
  return sanitizeCell(record) as Record<string, unknown>;
}

export function buildFactoryChartExportPayload(
  items: readonly FactoryRecommendation[],
  images: Record<string, string>,
  context: FactoryChartExportContext,
): FactoryChartExportPayload {
  const charts = items.map(item => ({
    id: item.id,
    candidate_id: item.id,
    // Legacy exporters use chartType as a worksheet/image key. Make it
    // candidate-unique so two candidates sharing a graph type never overwrite.
    chartType: `${item.chart.chartType}__${item.id}`,
    source_chart_type: item.chart.chartType,
    title: item.title,
    metric: item.metric,
    domain: item.domain,
    source: [item.source || context.sourceLabel, context.scopeLabel].filter(Boolean).join(' · '),
    period: context.periodLabel,
    unit: item.unit,
    kpis: item.kpis,
    echartsOption: item.chart.echartsOption,
    tableData: safeTable(item.tableData),
    findings: item.findings,
    reason: item.reason,
    aiReason: item.aiReason || '',
  }));
  const analysisResults = Object.fromEntries(items.map(item => [item.id, [
    `标题：${item.title}`,
    `来源：${[item.source || context.sourceLabel, context.scopeLabel].filter(Boolean).join(' · ')}`,
    `期间：${context.periodLabel}`,
    `单位：${item.unit}`,
    `规则依据：${item.reason}`,
    item.aiReason ? `AI 补充：${item.aiReason}` : '',
    ...item.kpis.map(kpi => `KPI｜${kpi.label}：${kpi.value ?? '—'} ${kpi.unit}`),
    ...Object.entries(item.findings || {}).flatMap(([label, facts]) => {
      const heading = label === 'good' ? '做得好的' : label === 'attention' ? '需要关注' : '建议';
      return (facts as string[]).map(fact => `${heading}：${fact}`);
    }),
  ].filter(Boolean).join('\n')]));
  return {
    charts,
    chart_images: Object.fromEntries(items.map(item => [item.id, images[item.id]])),
    analysis_results: analysisResults,
    chart_titles: Object.fromEntries(items.map(item => [item.id, item.title])),
    period_label: context.periodLabel,
    report_title: '工厂经营分析报告',
    company_name: context.factoryId,
    year: context.year,
    period_type: context.periodType,
    start_month: context.startMonth,
    end_month: context.endMonth,
  };
}

function chartDomFor(id: string): HTMLElement | null {
  if (typeof document === 'undefined') return null;
  const card = [...document.querySelectorAll<HTMLElement>('[data-testid^="factory-chart-card-"]')]
    .find(element => element.dataset.testid === `factory-chart-card-${id}`);
  return card?.querySelector<HTMLElement>('.factory-chart-canvas') || null;
}

/** Capture from mounted ECharts instances, including cards below the viewport. */
export async function captureFactoryChartImages(items: readonly FactoryRecommendation[]): Promise<Record<string, string>> {
  const { default: echarts } = await import('@/utils/echarts');
  const images: Record<string, string> = {};
  for (const item of items) {
    const dom = chartDomFor(item.id);
    const instance = dom ? echarts.getInstanceByDom(dom) : null;
    if (!instance) throw new Error(`无法导出图表：${item.title}（图表尚未完成绘制）`);
    const image = instance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' });
    if (!image || !image.startsWith('data:image/')) throw new Error(`无法导出图表：${item.title}`);
    images[item.id] = image;
  }
  return images;
}

async function isExpectedBinary(blob: Blob, format: FactoryChartExportFormat): Promise<boolean> {
  const type = (blob.type || '').toLowerCase();
  if (type.includes('json') || type.includes('text/html')) return false;
  const bytes = new Uint8Array(await blob.slice(0, 4).arrayBuffer());
  const signature = String.fromCharCode(...bytes);
  if (format === 'pdf') return type.includes('pdf') && signature === '%PDF';
  if (format === 'excel') return (type.includes('spreadsheet') || type.includes('excel') || type === 'application/zip') && signature === 'PK\u0003\u0004';
  return (type.includes('presentation') || type.includes('powerpoint') || type === 'application/zip') && signature === 'PK\u0003\u0004';
}

export async function downloadFactoryChartExport(
  format: FactoryChartExportFormat,
  payload: FactoryChartExportPayload,
  filename: string,
  save: (blob: Blob, filename: string) => void = triggerFactoryDownload,
): Promise<void> {
  const blob = await exportFactoryCharts(format, payload);
  if (!blob || !(await isExpectedBinary(blob, format))) throw new Error(`${format.toUpperCase()} 导出失败：服务器未返回有效文件`);
  save(blob, filename);
}

export function triggerFactoryDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
