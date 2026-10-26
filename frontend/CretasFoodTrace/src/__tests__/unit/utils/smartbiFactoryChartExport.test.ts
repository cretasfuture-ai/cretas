import { buildFactoryChartExportPayload } from '../../../utils/smartbiFactoryChartExport';
import { exportFactoryCharts, shareFactoryChartFile } from '../../../services/api/smartbiFactoryCharts';
import { apiClient } from '../../../services/api/apiClient';
import * as FileSystem from 'expo-file-system';

jest.mock('../../../services/api/apiClient', () => ({ apiClient: { post: jest.fn(), get: jest.fn(), put: jest.fn() } }));
jest.mock('../../../utils/factoryIdHelper', () => ({ requireFactoryId: jest.fn(() => 'F006') }));
jest.mock('../../../store/authStore', () => ({ useAuthStore: { getState: () => ({ getUserId: () => 42, getUserRole: () => 'factory_admin' }) } }));
jest.mock('expo-sharing', () => ({ isAvailableAsync: jest.fn(async () => true), shareAsync: jest.fn(async () => undefined) }));

const client = apiClient as jest.Mocked<typeof apiClient>;
const item = {
  id: 'channel', title: '渠道收入', metric: '营业收入', domain: 'finance', priority: 'required' as const,
  reason: '来自订单', source: 'business', unit: '元',
  findings: { good: ['稳定'], attention: [], suggestions: [] },
  chart: { chartType: 'bar', echartsOption: { series: [{ data: [10] }] } },
  kpis: [{ label: '收入', value: 10, unit: '元' }], tableData: [{ 渠道: '直营', 收入: 10 }],
};

describe('smartbiFactoryChartExport', () => {
  beforeEach(() => jest.clearAllMocks());

  it('exports only selected candidates, preserving source/unit/facts and image keys', () => {
    const payload = buildFactoryChartExportPayload([item, { ...item, id: 'other', title: '=unsafe' }], { channel: 'data:image/png;base64,abc' }, {
      factoryId: 'F006', source: 'business', sourceLabel: '业务数据', periodLabel: '2026年', selectedIds: ['channel'],
    });
    expect(payload.factory_id).toBe('F006');
    expect(payload.charts).toHaveLength(1);
    expect(payload.charts[0]).toEqual(expect.objectContaining({ id: 'channel', source: 'business', unit: '元', kpis: item.kpis }));
    expect(payload.chart_images).toEqual({ channel: 'data:image/png;base64,abc' });
    expect(payload.analysis_results.channel).toContain('稳定');
    expect(payload.charts.some(chart => chart.id === 'other')).toBe(false);
  });

  it('protects formula-like source text without recalculating KPI values', () => {
    const payload = buildFactoryChartExportPayload([{ ...item, title: '=SUM(A1:A2)', tableData: [{ value: '-1' }] }], {}, {
      factoryId: 'F006', source: 'business', sourceLabel: '业务数据', periodLabel: '全部期间', selectedIds: ['channel'],
    });
    expect(payload.charts[0]!.title).toBe('=SUM(A1:A2)');
    expect((payload.charts[0]!.tableData as { rows: Array<Array<string>> }).rows[0]![0]).toBe("'-1");
    expect(payload.charts[0]!.kpis).toEqual(item.kpis);
  });

  it('verifies PDF signature/size before caching and returns a cache file', async () => {
    const bytes = new Uint8Array(120); bytes.set([0x25, 0x50, 0x44, 0x46]);
    client.post.mockResolvedValueOnce(bytes as never);
    const file = await exportFactoryCharts('pdf', { factory_id: 'F006', charts: [], chart_images: {}, analysis_results: {} });
    expect(client.post).toHaveBeenCalledWith(expect.stringContaining('/export-pdf'), expect.objectContaining({ factory_id: 'F006' }), { responseType: 'arraybuffer' });
    expect(file.mimeType).toBe('application/pdf');
    expect(file.name).toMatch(/\.pdf$/);
    expect(file.size).toBe(120);
    expect(file.role).toBe('factory_admin');
    expect(FileSystem.writeAsStringAsync).toHaveBeenCalled();
  });

  it('rejects a payload from another factory before sending it', async () => {
    await expect(exportFactoryCharts('pdf', { factory_id: 'F007', charts: [], chart_images: {}, analysis_results: {} })).rejects.toThrow(/factory scope/i);
    expect(client.post).not.toHaveBeenCalled();
  });

  it('shares only a file owned by the current user and factory', async () => {
    await expect(shareFactoryChartFile({ uri: 'file:///ok.pdf', name: 'ok.pdf', mimeType: 'application/pdf', size: 120, factoryId: 'F006', userId: '42', role: 'factory_admin' })).resolves.toBeUndefined();
    await expect(shareFactoryChartFile({ uri: 'file:///old.pdf', name: 'old.pdf', mimeType: 'application/pdf', size: 120, factoryId: 'F006', userId: '43', role: 'factory_admin' })).rejects.toThrow(/owner|factory/i);
  });

  it('rejects an HTML/error body instead of caching it as an export', async () => {
    client.post.mockResolvedValueOnce(`<html>${'x'.repeat(200)}</html>` as never);
    await expect(exportFactoryCharts('excel', { factory_id: 'F006', charts: [], chart_images: {}, analysis_results: {} })).rejects.toThrow(/invalid binary/i);
  });
});
