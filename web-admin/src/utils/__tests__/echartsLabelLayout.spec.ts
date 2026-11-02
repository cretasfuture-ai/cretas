import { describe, expect, it, vi } from 'vitest';

// The real wordcloud plugin probes canvas during module evaluation. This spec
// exercises the real ECharts core/SVG renderer and only replaces that unrelated
// plugin, matching the existing jsdom test convention.
vi.mock('echarts-wordcloud', () => ({}));

import echarts from '../echarts';

describe('按需 ECharts LabelLayout registration', () => {
  it('runs real hideOverlap on dense heatmap labels without changing source data', () => {
    const sourceData = Array.from({ length: 17 }, (_, index) => [index, Math.floor(index / 6), index]);
    const option = {
      animation: false,
      grid: { left: 12, right: 12, top: 12, bottom: 12 },
      xAxis: { type: 'category', data: Array.from({ length: 17 }, (_, index) => `D${index}`), show: false },
      yAxis: { type: 'category', data: Array.from({ length: 6 }, (_, index) => `P${index}`), show: false },
      visualMap: { min: 0, max: 100, show: false },
      series: [{
        type: 'heatmap',
        data: sourceData,
        label: { show: true, formatter: (params: { value: unknown[] }) => `H${String(params.value[2])}` },
        labelLayout: { hideOverlap: true },
      }],
    } as const;
    const coordinates = sourceData.map(([x, y]) => `${x}:${y}`);
    expect(new Set(coordinates).size).toBe(sourceData.length);
    const before = structuredClone(sourceData);
    const originalGetContext = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = (() => ({
      measureText: (text: string) => ({ width: text.length * 8 }),
    })) as typeof HTMLCanvasElement.prototype.getContext;

    try {
      const renderValueTextCount = (withLabelLayout: boolean) => {
        const chart = echarts.init(null as unknown as HTMLElement, undefined, { renderer: 'svg', ssr: true, width: 180, height: 180 });
        try {
          chart.setOption(withLabelLayout ? option : {
            ...option,
            series: [{ ...option.series[0], labelLayout: undefined }],
          });
          const svg = chart.renderToSVGString();
          const parsed = new DOMParser().parseFromString(svg, 'image/svg+xml');
          const valueTexts = [...parsed.querySelectorAll('text')].filter(text => /^H\d+$/u.test(text.textContent || ''));
          return valueTexts.filter(text => text.getAttribute('style')?.includes('display: none') !== true).length;
        } finally {
          chart.dispose();
        }
      };

      const baselineCount = renderValueTextCount(false);
      const laidOutCount = renderValueTextCount(true);

      expect(option.series[0].data).toEqual(before);
      expect(baselineCount).toBe(sourceData.length);
      expect(laidOutCount).toBeGreaterThan(0);
      expect(laidOutCount).toBeLessThan(baselineCount);
    } finally {
      HTMLCanvasElement.prototype.getContext = originalGetContext;
    }
  });
});
