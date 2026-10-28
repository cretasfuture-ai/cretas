import { describe, expect, it, vi } from 'vitest';
import { collectExportChartImages, withChartAnnotations } from '../financialDashboardExport';

it('可见图与导出图保留原图形和完整用户标注', () => {
  const original = { graphic: { type: 'text', style: { text: '原图说明' } } };
  const note = { text: '复核完成', x: 20, y: 30, color: '#123456' };
  const option = withChartAnnotations(original, [note]);
  expect(option.graphic).toEqual([
    original.graphic,
    expect.objectContaining({ type: 'circle', left: '20%', top: '30%', style: expect.objectContaining({ fill: '#123456' }) }),
    expect.objectContaining({ type: 'text', left: '21%', top: '29%', style: expect.objectContaining({ text: '复核完成', fill: '#123456' }) }),
  ]);
  expect(withChartAnnotations(original, [])).toBe(original);
});

describe('导出完整看板，包含当前未挂载的图', () => {
  const charts = [
    { chartType: 'budget_achievement', success: true, echartsOption: { series: [] } },
    { chartType: 'bullet_chart', success: true, echartsOption: { series: [] } },
    { chartType: 'channel_analysis', success: false, echartsOption: {} },
  ];
  it('即使用户在结论页，也收集每张可用图，缺数据图不导出', () => {
    const render = vi.fn(c => `data:image/png;base64,${c.chartType}`);
    expect(collectExportChartImages(charts, render)).toEqual({
      budget_achievement: 'data:image/png;base64,budget_achievement',
      bullet_chart: 'data:image/png;base64,bullet_chart',
    });
    expect(render).toHaveBeenCalledTimes(2);
  });
  it('截图失败不能被当作图表齐全的成功报告', () => {
    expect(() => collectExportChartImages(charts, () => '')).toThrow('无法导出图表');
    expect(() => collectExportChartImages(charts, () => { throw new Error('canvas failed'); })).toThrow('canvas failed');
  });
});
