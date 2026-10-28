import {
  getFactoryRecommendations,
  getFactoryChartDetails,
  getFactoryChartSelection,
  saveFactoryChartSelection,
  getFactoryChartUploads,
  resolveSmartbiApiBaseUrl,
} from '../../../services/api/smartbiFactoryCharts';
import { apiClient } from '../../../services/api/apiClient';

jest.mock('../../../services/api/apiClient', () => ({
  apiClient: { get: jest.fn(), post: jest.fn(), put: jest.fn() },
}));
jest.mock('../../../utils/factoryIdHelper', () => ({
  requireFactoryId: jest.fn(() => 'F006'),
}));
let mockUserId = 42;
let mockRole = 'factory_admin';
jest.mock('../../../store/authStore', () => ({
  useAuthStore: { getState: () => ({ getUserId: () => mockUserId, getUserRole: () => mockRole }) },
}));

const client = apiClient as jest.Mocked<typeof apiClient>;
const recommendation = {
  success: true,
  data: {
    factoryId: 'F006', source: 'business', sourceLabel: '业务数据', period: { label: '2026年' },
    ranking: { mode: 'rule', reason: 'facts' }, recommendations: [], unavailable: [], sourceErrors: [],
  },
};

describe('smartbiFactoryCharts native API', () => {
  beforeEach(() => { jest.clearAllMocks(); mockUserId = 42; mockRole = 'factory_admin'; });

  it('maps only known company production origins and fails closed for unknown hosts', () => {
    expect(resolveSmartbiApiBaseUrl('https://www.cretaceousfuture.com/api')).toBe('https://admin.cretaceousfuture.com/smartbi-api');
    expect(() => resolveSmartbiApiBaseUrl('https://untrusted.example/smartbi')).toThrow(/not configured/i);
    expect(() => resolveSmartbiApiBaseUrl('https://localhost.evil.example/api')).toThrow(/not configured/i);
  });

  it('forces current factory in recommendation body and uses the factory endpoint', async () => {
    client.post.mockResolvedValueOnce(recommendation as never);
    await getFactoryRecommendations({
      source: 'business', factory_id: 'OTHER', year: 2026, period_type: 'all',
      start_month: 1, end_month: 12, include_optional: true, use_ai: false,
    });
    expect(client.post).toHaveBeenCalledWith(
      expect.stringContaining('/api/smartbi/financial-dashboard/factory-recommendations'),
      expect.objectContaining({ factory_id: 'F006' }),
    );
    expect((client.post.mock.calls[0]![1] as { factory_id: string }).factory_id).not.toBe('OTHER');
  });

  it('rejects a response belonging to another factory', async () => {
    client.post.mockResolvedValueOnce({ ...recommendation, data: { ...recommendation.data, factoryId: 'F007' } } as never);
    await expect(getFactoryRecommendations({
      source: 'business', year: 2026, period_type: 'all', start_month: 1, end_month: 12,
      include_optional: true, use_ai: false,
    })).rejects.toThrow(/factory scope/i);
  });

  it('rejects when the authenticated user changes while the request is pending', async () => {
    let release!: (value: typeof recommendation) => void;
    client.post.mockReturnValueOnce(new Promise(resolve => { release = resolve; }) as never);
    const pending = getFactoryRecommendations({ source: 'business', year: 2026, period_type: 'all', start_month: 1, end_month: 12, include_optional: true, use_ai: false });
    mockUserId = 43;
    release(recommendation);
    await expect(pending).rejects.toThrow(/authentication|factory/i);
  });

  it('rejects when the authenticated role changes while pending', async () => {
    let release!: (value: typeof recommendation) => void;
    client.post.mockReturnValueOnce(new Promise(resolve => { release = resolve; }) as never);
    const pending = getFactoryRecommendations({ source: 'business', year: 2026, period_type: 'all', start_month: 1, end_month: 12, include_optional: true, use_ai: false });
    mockRole = 'factory_viewer';
    release(recommendation);
    await expect(pending).rejects.toThrow(/role|authentication/i);
  });

  it('preserves detail point/filters and scopes selection read/write', async () => {
    client.post.mockResolvedValueOnce({ success: true, data: {
      factoryId: 'F006', candidateId: 'c1', title: '收入', source: 'business', period: '全部',
      columns: [], rows: [], total: 0, page: 1, pageSize: 50, hasMore: false,
      appliedFilters: [], availableFilters: [], snapshotAt: 1,
    } } as never);
    await getFactoryChartDetails({ context_id: 'ctx', filters: [{ field: '渠道', value: '直营' }], point: { name: '直营' } });
    expect(client.post).toHaveBeenCalledWith(expect.stringContaining('/factory-details'), expect.objectContaining({ factory_id: 'F006' }));

    client.get.mockResolvedValueOnce({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: null, updatedAt: null } } as never);
    await getFactoryChartSelection('business');
    expect(client.get).toHaveBeenCalledWith(expect.stringContaining('/factory-selection'), { params: { source: 'business', factory_id: 'F006' } });

    client.put.mockResolvedValueOnce({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: [], updatedAt: 'now' } } as never);
    await saveFactoryChartSelection('business', ['c1']);
    expect(client.put).toHaveBeenCalledWith(expect.stringContaining('/factory-selection'), { source: 'business', factory_id: 'F006', selected_ids: ['c1'] });
  });

  it('normalizes upload names while rejecting a foreign upload scope', async () => {
    client.get.mockResolvedValueOnce({ success: true, data: { content: [{ id: 9, fileName: 'a.xlsx', sheetName: '生产', status: 'COMPLETED' }, { id: 10, fileName: 'bad.xlsx', status: 'PENDING' }] } } as never);
    await expect(getFactoryChartUploads()).resolves.toEqual([{ id: 9, fileName: 'a.xlsx', sheetName: '生产' }]);
    expect(client.get).toHaveBeenCalledWith('/api/mobile/F006/smart-bi/uploads', { params: { page: 0, size: 200 } });
    client.get.mockResolvedValueOnce({ success: true, data: { content: [{ id: 10, fileName: 'bad.xlsx', status: 'PENDING' }] } } as never);
    await expect(getFactoryChartUploads()).resolves.toEqual([]);
  });

  it('preserves same-file workbook sheet names so role selectors can distinguish them', async () => {
    client.get.mockResolvedValueOnce({ success: true, data: { content: [
      { id: 11, fileName: '同一上传.xlsx', sheetName: '生产', status: 'COMPLETED' },
      { id: 12, fileName: '同一上传.xlsx', sheetName: '质检', status: 'COMPLETED' },
      { id: 13, fileName: '同一上传.xlsx', sheetName: '成本', status: 'COMPLETED' },
    ] } } as never);
    await expect(getFactoryChartUploads()).resolves.toEqual([
      { id: 11, fileName: '同一上传.xlsx', sheetName: '生产' },
      { id: 12, fileName: '同一上传.xlsx', sheetName: '质检' },
      { id: 13, fileName: '同一上传.xlsx', sheetName: '成本' },
    ]);
  });
});
