type ChartRecord = Record<string, unknown>;

function asRecord(value: unknown): ChartRecord | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as ChartRecord
    : undefined;
}

function isDateLabel(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/u.test(value);
}

/**
 * Apply display-only safeguards for the narrow factory cards.
 * The returned option is shallow-cloned at every changed branch; series data
 * and axis data retain their original references and values.
 */
export function applyFactoryChartResponsive(option: ChartRecord, width: number): ChartRecord {
  const next: ChartRecord = { ...option };
  const narrow = Number.isFinite(width) && width > 0 && width <= 520;
  const series = Array.isArray(option.series) ? option.series : undefined;

  if (series) {
    next.series = series.map((value) => {
      const current = asRecord(value);
      if (!current || current.type !== 'heatmap' || !narrow) return current ?? value;
      const label = asRecord(current.label);
      if (label?.show === false) return current;
      const labelLayout = asRecord(current.labelLayout);
      return {
        ...current,
        label: {
          ...(label ?? {}),
          padding: label?.padding ?? [1, 3],
        },
        labelLayout: typeof current.labelLayout === 'function'
          ? current.labelLayout
          : { ...(labelLayout ?? {}), hideOverlap: true },
      };
    });
  }

  const xAxis = asRecord(option.xAxis);
  const xAxisData = xAxis?.data;
  const hasDateAxis = Array.isArray(xAxisData)
    && xAxisData.length > 1
    && xAxisData.every(isDateLabel);
  const hasLineOrArea = series?.some((value) => {
    const current = asRecord(value);
    return current?.type === 'line' || current?.type === 'area';
  }) ?? false;

  if (xAxis && hasDateAxis && hasLineOrArea) {
    const axisLabel = asRecord(xAxis.axisLabel);
    next.xAxis = {
      ...xAxis,
      axisLabel: {
        ...(axisLabel ?? {}),
        alignMinLabel: axisLabel?.alignMinLabel ?? 'left',
        alignMaxLabel: axisLabel?.alignMaxLabel ?? 'right',
        showMinLabel: axisLabel?.showMinLabel ?? true,
        showMaxLabel: axisLabel?.showMaxLabel ?? true,
      },
    };
  }

  return next;
}
