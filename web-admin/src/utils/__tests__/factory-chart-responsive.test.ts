import { afterEach, describe, expect, it, vi } from 'vitest';

// The real wordcloud plugin probes canvas during module evaluation. The chart
// renderer itself remains real ECharts SVG SSR in these assertions.
vi.mock('echarts-wordcloud', () => ({}));

import echarts from '../echarts';
import { applyFactoryChartResponsive } from '../factory-chart-responsive';

const originalGetContext = HTMLCanvasElement.prototype.getContext;

afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
});

function enableTextMetrics() {
  HTMLCanvasElement.prototype.getContext = (() => ({
    measureText: (text: string) => ({ width: text.length * 8 }),
  })) as typeof HTMLCanvasElement.prototype.getContext;
}

function renderSvg(option: Record<string, unknown>, width: number, height = 240): XMLDocument {
  const chart = echarts.init(null as unknown as HTMLElement, undefined, {
    renderer: 'svg',
    ssr: true,
    width,
    height,
  });
  try {
    chart.setOption(option);
    return new DOMParser().parseFromString(chart.renderToSVGString(), 'image/svg+xml');
  } finally {
    chart.dispose();
  }
}

function visibleNumericLabels(document: XMLDocument): Element[] {
  return [...document.querySelectorAll('text')].filter((text) => {
    const value = text.textContent?.trim() ?? '';
    return /^\d+(?:\.\d+)?$/u.test(value)
      && !text.getAttribute('style')?.includes('display: none');
  });
}

function numericLabelGeometry(document: XMLDocument): Array<{ x: number; y: number; width: number }> {
  return visibleNumericLabels(document).flatMap((text) => {
    const transform = text.getAttribute('transform') ?? '';
    const match = /translate\((-?[\d.]+)\s+(-?[\d.]+)\)/u.exec(transform);
    if (!match) return [];
    const fontSize = Number(/font-size:([\d.]+)px/u.exec(text.getAttribute('style') ?? '')?.[1] ?? 12);
    return [{ x: Number(match[1]), y: Number(match[2]), width: (text.textContent?.trim().length ?? 0) * fontSize * 0.56 }];
  });
}

function minimumSameRowGap(labels: Array<{ x: number; y: number; width: number }>): number {
  const rows = new Map<number, Array<{ x: number; width: number }>>();
  for (const label of labels) {
    const row = Math.round(label.y * 100) / 100;
    const items = rows.get(row) ?? [];
    items.push({ x: label.x, width: label.width });
    rows.set(row, items);
  }
  const gaps = [...rows.values()].flatMap((items) => {
    const sorted = items.sort((left, right) => left.x - right.x);
    return sorted.slice(1).map((item, index) => {
      const previous = sorted[index];
      return item.x - item.width / 2 - (previous.x + previous.width / 2);
    });
  });
  return gaps.length ? Math.min(...gaps) : Number.POSITIVE_INFINITY;
}

function visibleDateLabelGeometry(document: XMLDocument): Array<{ left: number; right: number; text: string }> {
  return [...document.querySelectorAll('text')].flatMap((text) => {
    const value = text.textContent?.trim() ?? '';
    if (!/^2026-10-\d{2}$/u.test(value)) return [];
    const transform = text.getAttribute('transform') ?? '';
    const match = /translate\((-?[\d.]+)\s+(-?[\d.]+)\)/u.exec(transform);
    if (!match) return [];
    const fontSize = Number(/font-size:([\d.]+)px/u.exec(text.getAttribute('style') ?? '')?.[1] ?? 12);
    const width = value.length * fontSize * 0.56;
    const x = Number(match[1]);
    const anchor = text.getAttribute('text-anchor');
    return [{
      left: anchor === 'start' ? x : anchor === 'end' ? x - width : x - width / 2,
      right: anchor === 'start' ? x + width : anchor === 'end' ? x : x + width / 2,
      text: value,
    }];
  });
}

describe('factory chart responsive display safeguards', () => {
  it('uses real label-layout geometry for dense narrow heatmaps without changing data', () => {
    enableTextMetrics();
    const xAxisData = Array.from({ length: 17 }, (_, index) => `2026-10-${String(index + 1).padStart(2, '0')}`);
    const yAxisData = Array.from({ length: 6 }, (_, index) => `Product ${index}`);
    const sourceData = [
      [0, 0, 18], [1, 1, 10], [2, 2, 80], [3, 2, 115], [4, 2, 4], [5, 2, 21],
      [6, 2, 10], [7, 2, 8], [8, 2, 1], [9, 2, 1], [10, 3, 8], [11, 3, 1],
      [12, 3, 8], [13, 4, 248], [14, 5, 16], [15, 5, 100], [16, 5, 1],
    ];
    const option = {
      animation: false,
      xAxis: { type: 'category', data: xAxisData },
      yAxis: { type: 'category', data: yAxisData },
      visualMap: { min: 1, max: 17, show: false },
      grid: { left: 160, right: 40, top: 40, bottom: 64, containLabel: true },
      series: [{ type: 'heatmap', data: sourceData, label: { show: true } }],
    } as Record<string, unknown>;
    const narrow = applyFactoryChartResponsive(option, 360);
    const wide = applyFactoryChartResponsive(option, 800);

    const narrowSeries = (narrow.series as Array<Record<string, unknown>>)[0];
    const wideSeries = (wide.series as Array<Record<string, unknown>>)[0];
    expect(narrowSeries.labelLayout).toMatchObject({ hideOverlap: true });
    expect(narrowSeries.label).toMatchObject({ padding: [1, 3] });
    expect(wideSeries.labelLayout).toBeUndefined();
    expect(narrowSeries.data).toBe(sourceData);
    expect(narrow.xAxis).toMatchObject({ data: xAxisData });
    expect((narrow.xAxis as Record<string, unknown>).data).toBe(xAxisData);

    const baselineLabels = visibleNumericLabels(renderSvg(option, 440, 320));
    const laidOutLabels = visibleNumericLabels(renderSvg(narrow, 440, 320));
    expect(baselineLabels.length).toBe(sourceData.length);
    expect(laidOutLabels.length).toBeGreaterThan(0);
    expect(laidOutLabels.length).toBeLessThanOrEqual(baselineLabels.length);
    const baselineGap = minimumSameRowGap(numericLabelGeometry(renderSvg(option, 440, 320)));
    const narrowGap = minimumSameRowGap(numericLabelGeometry(renderSvg(narrow, 440, 320)));
    expect(Number.isFinite(narrowGap)).toBe(true);
    expect(narrowGap).toBeGreaterThanOrEqual(baselineGap);
    expect(narrowGap).toBeGreaterThan(1);
    const wideGap = minimumSameRowGap(numericLabelGeometry(renderSvg(wide, 800, 320)));
    expect(Number.isFinite(wideGap)).toBe(true);
    expect(wideGap).toBeGreaterThan(1);
  });

  it('preserves an explicit label-layout callback without executing or replacing it', () => {
    const layout = vi.fn(() => ({ x: 42, rotate: 15 }));
    const data = [[0, 0, 80]];
    const option = { series: [{ type: 'heatmap', data, labelLayout: layout }] };
    const result = applyFactoryChartResponsive(option, 440);
    const series = (result.series as Array<Record<string, unknown>>)[0];
    expect(series.labelLayout).toBe(layout);
    expect(series.data).toBe(data);
    expect(layout).not.toHaveBeenCalled();
  });

  it('aligns the final date label while preserving the full axis and series', () => {
    enableTextMetrics();
    const dates = ['2026-10-26', '2026-10-27', '2026-10-28', '2026-10-29'];
    const values = [30, 2902.66, 20, 22000];
    const option = {
      animation: false,
      xAxis: { type: 'category', data: dates, boundaryGap: false },
      yAxis: { type: 'value' },
      series: [{ type: 'line', data: values, areaStyle: {} }],
    } as Record<string, unknown>;
    const narrow = applyFactoryChartResponsive(option, 360);
    const axis = narrow.xAxis as Record<string, unknown>;
    const axisLabel = axis.axisLabel as Record<string, unknown>;

    expect(axisLabel).toMatchObject({
      alignMinLabel: 'left',
      alignMaxLabel: 'right',
      showMinLabel: true,
      showMaxLabel: true,
    });
    expect(axis.data).toBe(dates);
    expect((narrow.series as Array<Record<string, unknown>>)[0].data).toBe(values);
    for (const width of [440, 800]) {
      const svg = renderSvg(applyFactoryChartResponsive(option, width), width);
      const visibleDates = visibleDateLabelGeometry(svg);
      expect(visibleDates.map((label) => label.text)).toContain('2026-10-26');
      expect(visibleDates.map((label) => label.text)).toContain('2026-10-29');
      expect(visibleDates.every((label) => label.left >= 0 && label.right <= width)).toBe(true);
    }
  });
});
