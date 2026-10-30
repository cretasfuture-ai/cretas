type ChartRecord = Record<string, unknown>;
type HeatmapFormatterParams = ChartRecord & { dataIndex?: unknown; value?: unknown };

function asRecord(value: unknown): ChartRecord | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as ChartRecord
    : undefined;
}

function isDateLabel(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/u.test(value);
}

function asFiniteNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function asHeatmapPoint(value: unknown): unknown[] | undefined {
  return Array.isArray(value) && value.length >= 3 ? value : undefined;
}

function heatmapDisplayIndices(
  data: unknown[],
  xAxis: ChartRecord | undefined,
  yAxis: ChartRecord | undefined,
  grid: ChartRecord | undefined,
  label: ChartRecord,
  width: number,
): Set<number> {
  const xAxisData = xAxis?.data;
  const xCount = Array.isArray(xAxisData)
    ? xAxisData.length
    : Math.max(1, ...data.flatMap((value) => {
      const point = asHeatmapPoint(value);
      const x = asFiniteNumber(point?.[0]);
      return x === undefined ? [] : [x + 1];
    }));
  const yAxisData = yAxis?.data;
  const fontSize = asFiniteNumber(label.fontSize)
    ?? asFiniteNumber(asRecord(label.textStyle)?.fontSize)
    ?? 12;
  const yLabelWidth = Array.isArray(yAxisData)
    ? Math.max(0, ...yAxisData.map((value) => String(value).length * fontSize * 0.56 + 8))
    : 0;
  const gridLeft = asFiniteNumber(grid?.left) ?? 0;
  const gridRight = asFiniteNumber(grid?.right) ?? 0;
  // containLabel moves the plot inward by the longest category label. Keep a
  // conservative fraction of the resulting cell width because the actual
  // font metrics are only available after ECharts has mounted the card.
  const plotWidth = Math.max(1, width - Math.max(gridLeft, yLabelWidth) - gridRight);
  const cellWidth = (plotWidth / Math.max(1, xCount)) * 0.75;
  const valueWidth = Math.max(1, ...data.map((value) => {
    const point = asHeatmapPoint(value);
    return String(point?.[2] ?? '').length * fontSize * 0.56;
  }));
  const requiredWidth = valueWidth + 6;
  const stride = Math.max(1, Math.ceil(requiredWidth / cellWidth));
  const rows = new Map<number, Array<{ index: number; x: number }>>();
  data.forEach((value, index) => {
    const point = asHeatmapPoint(value);
    const x = asFiniteNumber(point?.[0]);
    const y = asFiniteNumber(point?.[1]);
    if (x === undefined || y === undefined) return;
    const row = rows.get(y) ?? [];
    row.push({ index, x });
    rows.set(y, row);
  });
  const visible = new Set<number>();
  for (const row of rows.values()) {
    row.sort((left, right) => left.x - right.x);
    let lastX: number | undefined;
    for (const item of row) {
      if (lastX === undefined || item.x - lastX >= stride) {
        visible.add(item.index);
        lastX = item.x;
      }
    }
  }
  return visible;
}

function defaultHeatmapLabel(params: HeatmapFormatterParams): string {
  const value = Array.isArray(params.value) ? params.value[2] : params.value;
  return value === null || value === undefined ? '' : String(value);
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
      const data = Array.isArray(current.data) ? current.data : [];
      const sourceFormatter = label?.formatter;
      const supportsSparseFormatter = data.length > 0
        && data.every((point) => {
          const value = asHeatmapPoint(point);
          return asFiniteNumber(value?.[0]) !== undefined && asFiniteNumber(value?.[1]) !== undefined;
        });
      const sparseIndices = sourceFormatter === undefined
        && typeof current.labelLayout !== 'function'
        && supportsSparseFormatter
        ? heatmapDisplayIndices(
          data,
          asRecord(option.xAxis),
          asRecord(option.yAxis),
          asRecord(option.grid),
          label ?? {},
          width,
        )
        : undefined;
      return {
        ...current,
        label: {
          ...(label ?? {}),
          padding: label?.padding ?? [1, 3],
          ...(sparseIndices
            ? {
              formatter: (params: HeatmapFormatterParams) => {
                const index = asFiniteNumber(params.dataIndex);
                return index !== undefined && sparseIndices.has(index)
                  ? defaultHeatmapLabel(params)
                  : '';
              },
            }
            : {}),
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
