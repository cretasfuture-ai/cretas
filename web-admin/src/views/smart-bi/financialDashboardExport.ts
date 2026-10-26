interface ExportableChart {
  chartType: string;
  success?: boolean;
  echartsOption?: Record<string, unknown>;
}

interface ChartAnnotation { text: string; x: number; y: number; color?: string }

/** Shared by visible and exported charts so user notes survive report export. */
export function withChartAnnotations(
  option: Record<string, unknown>, annotations: readonly ChartAnnotation[],
): Record<string, unknown> {
  if (!annotations.length) return option;
  const existing = option.graphic || [];
  return { ...option, graphic: [
    ...(Array.isArray(existing) ? existing : [existing]),
    ...annotations.flatMap(ann => [
      { type: 'circle', shape: { r: 4, cx: 0, cy: 0 }, left: `${ann.x}%`, top: `${ann.y}%`,
        style: { fill: ann.color || '#e6a23c', stroke: '#fff', lineWidth: 1.5 }, silent: true, z: 100 },
      { type: 'text', left: `${ann.x + 1}%`, top: `${ann.y - 1}%`,
        style: { text: ann.text, fontSize: 11, fill: ann.color || '#e6a23c', fontWeight: 'bold',
          backgroundColor: 'rgba(255,255,255,0.92)', padding: [3, 8], borderRadius: 4,
          borderColor: ann.color || '#e6a23c', borderWidth: 1,
          shadowColor: 'rgba(0,0,0,0.1)', shadowBlur: 4 }, silent: true, z: 100 },
    ]),
  ] };
}

/** Export all valid dashboard charts, regardless of which tab is mounted. */
export function collectExportChartImages<T extends ExportableChart>(
  charts: readonly T[], render: (chart: T) => string,
): Record<string, string> {
  const images: Record<string, string> = {};
  for (const chart of charts) {
    if (chart.success === false || !chart.echartsOption || !Object.keys(chart.echartsOption).length) continue;
    const image = render(chart);
    if (!image) throw new Error(`无法导出图表：${chart.chartType}`);
    images[chart.chartType] = image;
  }
  return images;
}
