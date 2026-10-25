import { describe, expect, it, vi } from 'vitest';
import type { FactoryRecommendation } from '@/api/smartbi/factory-charts';
import { buildFactoryChartExportPayload, downloadFactoryChartExport, safeSpreadsheetValue } from '../factoryChartExport';

const chart = (id: string, chartType = 'line'): FactoryRecommendation => ({
  id, title: `图表 ${id}`, metric: '产量', domain: '生产', priority: 'required', reason: '有完整产量记录', aiReason: '按波动排序', source: '业务系统', unit: 'kg',
  findings: { good: ['稳定'], attention: ['=SUM(A1:A2)'], suggestions: ['复核'] },
  chart: { chartType, echartsOption: { series: [{ data: [1] }] } },
  kpis: [{ label: '总量', value: 2.64, unit: '元/kg' }],
  tableData: [{ label: '=恶意公式', value: 1 }, { label: '正常', value: 2 }],
});

describe('factory chart export contract', () => {
  it('uses candidate IDs for duplicate chart types and keeps structured facts', () => {
    const payload = buildFactoryChartExportPayload([chart('a'), chart('b')], { a: 'data:image/png;base64,a', b: 'data:image/png;base64,b' }, {
      factoryId: 'F006', source: 'business', sourceLabel: '业务系统', periodLabel: '2026年', year: 2026, periodType: 'year', startMonth: 1, endMonth: 12, selectedIds: ['a', 'b'],
    });
    expect(Object.keys(payload.chart_images)).toEqual(['a', 'b']);
    expect(payload.charts.map(item => item.id)).toEqual(['a', 'b']);
    expect(payload.charts[0].tableData).toMatchObject({ headers: ['label', 'value'] });
    expect((payload.charts[0].tableData as { rows: unknown[][] }).rows[0]).toEqual(["'=恶意公式", 1]);
    expect(payload.chart_titles).toEqual({ a: '图表 a', b: '图表 b' });
    expect(payload.report_title).toBe('工厂经营分析报告');
    expect(payload).toMatchObject({ year: 2026, period_type: 'year', start_month: 1, end_month: 12, period_label: '2026年' });
    expect(payload.analysis_results.a).toContain('规则依据：有完整产量记录');
    expect(payload.analysis_results.a).toContain('期间：2026年');
    expect(payload.analysis_results.a).toContain('KPI｜总量：2.64 元/kg');
    expect(payload.analysis_results.a).toContain('做得好的：稳定');
    expect(payload.analysis_results.a).toContain('需要关注：=SUM(A1:A2)');
    expect(payload.analysis_results.a).toContain('建议：复核');
  });

  it('prefixes formula-looking spreadsheet cells', () => {
    expect(safeSpreadsheetValue('=1+1')).toBe("'=1+1");
    expect(safeSpreadsheetValue('  +1')).toBe("'  +1");
    expect(safeSpreadsheetValue('@cmd')).toBe("'@cmd");
    expect(safeSpreadsheetValue('plain')).toBe('plain');
  });

  it('rejects JSON/error blobs before creating a download', async () => {
    const api = await import('@/api/smartbi/factory-charts');
    vi.spyOn(api, 'exportFactoryCharts').mockResolvedValue(new Blob(['{"error":"bad"}'], { type: 'application/json' }));
    const save = vi.fn();
    await expect(downloadFactoryChartExport('excel', {
      charts: [], chart_images: {}, analysis_results: [],
    } as never, 'bad.xlsx', save)).rejects.toThrow('未返回有效文件');
    expect(save).not.toHaveBeenCalled();
  });

  it('requires both the expected content type and a real file signature', async () => {
    const api = await import('@/api/smartbi/factory-charts');
    vi.spyOn(api, 'exportFactoryCharts').mockResolvedValue(new Blob(['not a pdf'], { type: 'application/pdf' }));
    await expect(downloadFactoryChartExport('pdf', { charts: [], chart_images: {}, analysis_results: {} }, 'bad.pdf', vi.fn())).rejects.toThrow('未返回有效文件');
  });
});
