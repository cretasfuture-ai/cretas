import { askFactoryAnalysis, FactoryAnalysisApiError } from '../../../services/api/smartbiFactoryAnalysis';
import { apiClient } from '../../../services/api/apiClient';

jest.mock('../../../services/api/apiClient', () => ({ apiClient: { post: jest.fn() } }));
jest.mock('../../../utils/factoryIdHelper', () => ({ requireFactoryId: () => 'F006' }));
jest.mock('../../../store/authStore', () => ({
  useAuthStore: { getState: () => ({ getUserId: () => 1309, getUserRole: () => 'factory_super_admin', getFactoryId: () => 'F006' }) },
}));

const responseData = {
  factoryId: 'F006', source: 'business', question: '看产量', status: 'answered', answer: '已完成',
  selectedIds: [], recommendations: [], catalog: [], facts: [], limitations: [], sourceErrors: [],
  followUpQuestions: [], contextId: 'ctx-latest', subjects: [],
};

describe('smartbiFactoryAnalysis API', () => {
  beforeEach(() => jest.clearAllMocks());

  it('posts the complete current scope for a first question', async () => {
    (apiClient.post as jest.Mock).mockResolvedValue({ success: true, data: responseData });
    await askFactoryAnalysis({ question: '看产量', source: 'business', uploadId: 42, year: 2026, periodType: 'all', startMonth: 1, endMonth: 12, analysisSubject: 'sku-1' });
    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/mobile/F006/smart-bi/factory-analysis',
      { question: '看产量', source: 'business', uploadId: 42, year: 2026, periodType: 'all', startMonth: 1, endMonth: 12, analysisSubject: 'sku-1' },
    );
    expect((apiClient.post as jest.Mock).mock.calls[0][1]).not.toHaveProperty('factoryId');
    expect((apiClient.post as jest.Mock).mock.calls[0][1]).not.toHaveProperty('role');
  });

  it('posts only question and latest context for an ordinary follow-up', async () => {
    (apiClient.post as jest.Mock).mockResolvedValue({ success: true, data: responseData });
    await askFactoryAnalysis({ question: '继续看质量', contextId: 'ctx-latest', source: 'upload', uploadId: 99, year: 2025, periodType: 'month_range', startMonth: 2, endMonth: 2 });
    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/mobile/F006/smart-bi/factory-analysis',
      { question: '继续看质量', contextId: 'ctx-latest' },
    );
  });

  it('posts only question, latest context and selected subject for clarification', async () => {
    (apiClient.post as jest.Mock).mockResolvedValue({ success: true, data: responseData });
    await askFactoryAnalysis({ question: '看产品', contextId: 'ctx-latest', analysisSubject: 'sku-19', source: 'business', year: 2026, periodType: 'all' });
    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/mobile/F006/smart-bi/factory-analysis',
      { question: '看产品', contextId: 'ctx-latest', analysisSubject: 'sku-19' },
    );
  });

  it('preserves HTTP 410 for the screen explicit clear-context retry', async () => {
    (apiClient.post as jest.Mock).mockRejectedValue({ response: { status: 410 }, message: '对话上下文已过期' });
    await expect(askFactoryAnalysis({ question: '继续看质量', contextId: 'ctx-old' })).rejects.toMatchObject<Partial<FactoryAnalysisApiError>>({ status: 410 });
  });

  it('rejects a response from another factory', async () => {
    (apiClient.post as jest.Mock).mockResolvedValue({ success: true, data: { ...responseData, factoryId: 'F007' } });
    await expect(askFactoryAnalysis({ question: '看产量' })).rejects.toThrow(/工厂不一致/);
  });
});
