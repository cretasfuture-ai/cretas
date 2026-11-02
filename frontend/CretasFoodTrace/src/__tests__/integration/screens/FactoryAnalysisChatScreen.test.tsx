import React from 'react';
import { Text, View } from 'react-native';
import { fireEvent, render, waitFor, within } from '@testing-library/react-native';
import FactoryAnalysisChatScreen from '../../../screens/smartbi/FactoryAnalysisChatScreen';
import { askFactoryAnalysis, FactoryAnalysisApiError } from '../../../services/api/smartbiFactoryAnalysis';
import { exportFactoryCharts, getFactoryRecommendations, saveFactoryChartFile, shareFactoryChartFile } from '../../../services/api/smartbiFactoryCharts';

const navigate = jest.fn();
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ navigate }) }));
jest.mock('../../../components/ui/ScreenWrapper', () => ({ ScreenWrapper: ({ children, testID }: { children: React.ReactNode; testID?: string }) => <View testID={testID}>{children}</View> }));
jest.mock('../../../services/api/smartbiFactoryAnalysis', () => ({
  askFactoryAnalysis: jest.fn(),
  FactoryAnalysisApiError: class FactoryAnalysisApiError extends Error { status?: number; },
}));
jest.mock('../../../services/api/smartbiFactoryCharts', () => ({
  exportFactoryCharts: jest.fn(),
  getFactoryRecommendations: jest.fn(),
  saveFactoryChartFile: jest.fn(),
  shareFactoryChartFile: jest.fn(),
}));
jest.mock('../../../store/authStore', () => {
  const state = { user: { id: 1309 }, getFactoryId: () => 'F006', getUserRole: () => 'factory_super_admin', getUserId: () => 1309 };
  const store = ((selector?: (value: typeof state) => unknown) => selector ? selector(state) : state) as unknown as ((selector?: (value: typeof state) => unknown) => unknown) & { getState: () => typeof state };
  store.getState = () => state;
  return { useAuthStore: store };
});
const mockCapturePng = jest.fn<Promise<string>, []>();
jest.mock('../../../components/smartbi/FactoryChartCanvas', () => ({
  __esModule: true,
  default: React.forwardRef(({ testID, onReady }: { testID?: string; onReady?: () => void }, ref: React.ForwardedRef<{ capturePng: () => Promise<string> }>) => {
    React.useImperativeHandle(ref, () => ({ capturePng: () => mockCapturePng() }), []);
    React.useEffect(() => { onReady?.(); }, [onReady]);
    return <Text testID={testID}>real chart</Text>;
  }),
}));

const chart = {
  id: 'production.oee', title: '生产效率', metric: 'OEE', domain: '生产', priority: 'required' as const,
  reason: '真实来源', source: '业务系统', unit: '%', findings: { good: [], attention: [], suggestions: [] },
  chart: { chartType: 'line', echartsOption: { series: [] } }, kpis: [{ label: 'OEE', value: 83, unit: '%' }], tableData: null,
  detailContext: { id: 'detail-1', expiresAt: '2099-01-01T00:00:00Z' }, analysis: { familyId: 'production.oee' },
};
const chartTwo = { ...chart, id: 'quality.pass', title: '质量合格率', metric: '合格率', domain: '质量', analysis: { familyId: 'quality.pass' } };
const chartThree = { ...chart, id: 'cost.unit', title: '单位成本', metric: '单位成本', domain: '成本', analysis: { familyId: 'cost.unit' } };
const chartVariant = { ...chart, id: 'production.oee.variant', title: '生产效率变体', analysis: { familyId: 'production.oee' } };

function answered(overrides: Record<string, unknown> = {}) {
  return {
    factoryId: 'F006', source: 'business', sourceLabel: '本厂业务系统', period: { label: '2025 全年', year: 2025, periodType: 'all', startMonth: 1, endMonth: 12 }, analysisScopeLabel: '当前工厂授权范围', question: '看产量',
    status: 'answered', answer: '可信事实：OEE=83%', selectedIds: [chart.id], recommendations: [chart],
    catalog: Array.from({ length: 10 }, (_, index) => ({ id: `candidate-${index}`, familyId: `family-${index}`, familyLabel: `分析主题${index}`, chartType: 'bar', domain: '生产', metric: 'finished-goods-inventory.finished_goods.inventory.current', unit: '%', priority: 'optional', sourceKey: 'business' })),
    facts: [{ id: 'fact-1', candidateId: chart.id, label: 'OEE', value: 83, unit: '%', source: '业务系统' }], limitations: ['未覆盖联合分析'], sourceErrors: [{ source: '质量', message: '来源超时' }],
    followUpQuestions: ['再看质量指标'], contextId: 'ctx-latest', subjects: [], ...overrides,
  };
}

function renderScreen(params: Record<string, unknown> = {}) {
  return render(<FactoryAnalysisChatScreen route={{ key: 'FactoryAnalysisChat', name: 'FactoryAnalysisChat', params } as never} navigation={{} as never} />);
}

describe('FactoryAnalysisChatScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockCapturePng.mockResolvedValue('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lK0AAAAASUVORK5CYII=');
    (askFactoryAnalysis as jest.Mock).mockResolvedValue(answered());
    (exportFactoryCharts as jest.Mock).mockResolvedValue({ uri: 'file:///cache/result.pdf', name: 'result.pdf', mimeType: 'application/pdf', size: 1000, factoryId: 'F006', userId: '1309', role: 'factory_super_admin' });
    (shareFactoryChartFile as jest.Mock).mockResolvedValue(undefined);
    (saveFactoryChartFile as jest.Mock).mockResolvedValue({ uri: 'content://saved/result.pdf' });
  });

  it('refreshes expired joint details in the original guided product scope without AI ranking', async () => {
    const expired = { ...chart, id: 'production.inspection_fused@sku-1@kg', detailSnapshotKey: 'snapshot-original', detailContext: { id: 'expired', expiresAt: '2000-01-01T00:00:00Z' } };
    const next = { ...expired, detailContext: { id: 'renewed', expiresAt: '2099-01-01T00:00:00Z' } };
    const data = answered({ source: 'upload', uploadId: 42, jointUploads: { inspectionUploadId: 43, costUploadId: 44 }, analysisSubject: 'sku-1', recommendations: [expired] });
    (askFactoryAnalysis as jest.Mock).mockResolvedValueOnce(data);
    (getFactoryRecommendations as jest.Mock).mockResolvedValueOnce({ success: true, data: { ...data, recommendations: [next] } });
    const screen = renderScreen({ initialQuestion: '关联生产检验成本', source: 'upload', uploadId: 42, inspectionUploadId: 43, costUploadId: 44 });
    await waitFor(() => expect(screen.getByText('查看原始明细')).toBeTruthy());
    fireEvent.press(screen.getByText('查看原始明细'));
    await waitFor(() => expect(getFactoryRecommendations).toHaveBeenCalledWith(expect.objectContaining({
      source: 'upload', upload_id: 42, inspection_upload_id: 43, cost_upload_id: 44,
      guided: true, use_ai: false, analysis_subject: 'sku-1', detail_candidate_ids: [expired.id],
    })));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('FactoryChartDetails', expect.objectContaining({ recommendation: expect.objectContaining({ detailContext: next.detailContext }) })));
  });

  it('sends all three upload IDs on the mounted first question and context only on follow-up', async () => {
    (askFactoryAnalysis as jest.Mock)
      .mockResolvedValueOnce(answered({ source: 'upload', jointUploads: { inspectionUploadId: 43, costUploadId: 44 } }))
      .mockResolvedValueOnce(answered({ source: 'upload', jointUploads: { inspectionUploadId: 43, costUploadId: 44 } }));
    const screen = renderScreen({ initialQuestion: '联合看生产质量成本', source: 'upload', uploadId: 42, inspectionUploadId: 43, costUploadId: 44 });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-followups')).toBeTruthy());
    expect(askFactoryAnalysis).toHaveBeenNthCalledWith(1, expect.objectContaining({ question: '联合看生产质量成本', source: 'upload', uploadId: 42, inspectionUploadId: 43, costUploadId: 44 }));
    fireEvent.press(screen.getByText('再看质量指标'));
    await waitFor(() => expect(askFactoryAnalysis).toHaveBeenCalledTimes(2));
    expect(askFactoryAnalysis).toHaveBeenNthCalledWith(2, { question: '再看质量指标', contextId: 'ctx-latest' });
  });

  it('drops a mounted first response when a related upload ID changes', async () => {
    let resolveRequest!: (value: ReturnType<typeof answered>) => void;
    (askFactoryAnalysis as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { resolveRequest = resolve; }));
    const screen = renderScreen({ initialQuestion: '联合分析', source: 'upload', uploadId: 42, inspectionUploadId: 43, costUploadId: 44 });
    await waitFor(() => expect(askFactoryAnalysis).toHaveBeenCalledTimes(1));
    screen.rerender(<FactoryAnalysisChatScreen route={{ key: 'FactoryAnalysisChat', name: 'FactoryAnalysisChat', params: { source: 'upload', uploadId: 42, inspectionUploadId: 43, costUploadId: 45 } } as never} navigation={{} as never} />);
    resolveRequest(answered({ source: 'upload', jointUploads: { inspectionUploadId: 43, costUploadId: 44 } }));
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(screen.queryByText('可信事实：OEE=83%')).toBeNull();
  });

  it('renders trusted facts, source errors, limitations and an expandable full catalogue', async () => {
    const screen = renderScreen({ initialQuestion: '看产量' });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-facts')).toBeTruthy());
    expect(screen.getByTestId('factory-analysis-answer-metadata-factory-analysis-1')).toHaveTextContent('工厂F006 · 本厂业务系统 · 2025 全年 · 当前工厂授权范围');
    expect(askFactoryAnalysis).toHaveBeenNthCalledWith(1, {
      question: '看产量', source: 'business', year: new Date().getFullYear(), periodType: 'all', startMonth: 1, endMonth: 12,
    });
    expect(screen.getAllByText('83 %').length).toBeGreaterThan(0);
    expect(screen.getByTestId('factory-analysis-source-errors')).toBeTruthy();
    expect(screen.getByTestId('factory-analysis-limitations')).toBeTruthy();
    expect(screen.queryByText('指标9')).toBeNull();
    fireEvent.press(screen.getByText('展开目录'));
    expect(screen.getByText('分析主题9')).toBeTruthy();
    expect(screen.getByText('分析主题0')).toBeTruthy();
    expect(screen.queryByText(/family-0/)).toBeNull();
    expect(screen.queryByText(/finished-goods-inventory\.finished_goods\.inventory\.current/)).toBeNull();
  });

  it('folds long answer prose by default and exposes accessible expand/collapse control', async () => {
    const longAnswer = Array.from({ length: 12 }, (_, index) => `分析说明第${index + 1}段：本段保留完整的经营分析结论。`).join('\n');
    (askFactoryAnalysis as jest.Mock).mockResolvedValueOnce(answered({ answer: longAnswer }));
    const screen = renderScreen({ initialQuestion: '看完整分析' });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-answer-prose-factory-analysis-1')).toBeTruthy());

    const prose = screen.getByTestId('factory-analysis-answer-prose-factory-analysis-1');
    const toggle = screen.getByTestId('factory-analysis-answer-toggle-factory-analysis-1');
    expect(prose.props.children).toBe(longAnswer);
    expect(prose.props.numberOfLines).toBe(5);
    expect(toggle).toHaveTextContent('展开分析说明');
    expect(toggle.props.accessibilityState).toEqual({ expanded: false });

    fireEvent.press(toggle);
    expect(prose.props.numberOfLines).toBeUndefined();
    expect(toggle).toHaveTextContent('收起分析说明');
    expect(toggle.props.accessibilityLabel).toBe('收起分析说明');
    expect(toggle.props.accessibilityState).toEqual({ expanded: true });

    fireEvent.press(toggle);
    expect(prose.props.numberOfLines).toBe(5);
    expect(toggle.props.accessibilityState).toEqual({ expanded: false });
  });

  it('keeps a clarification pending and limits searchable product matches to eight', async () => {
    (askFactoryAnalysis as jest.Mock).mockResolvedValueOnce(answered({
      status: 'clarification', answer: '请指定产品', recommendations: [], facts: [], limitations: ['产品不明确'],
      subjects: Array.from({ length: 20 }, (_, index) => ({ id: `sku-${index}`, label: `产品${index}` })),
    }));
    const screen = renderScreen({ initialQuestion: '看产品' });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-subject-picker')).toBeTruthy());
    expect(screen.queryByText('产品9')).toBeNull();
    fireEvent.changeText(screen.getByLabelText('搜索产品 / SKU'), '产品19');
    fireEvent.press(screen.getByText('产品19'));
    fireEvent.press(screen.getByTestId('factory-analysis-clarification-retry'));
    await waitFor(() => expect(askFactoryAnalysis).toHaveBeenCalledTimes(2));
    expect(askFactoryAnalysis).toHaveBeenLastCalledWith({ question: '看产品', contextId: 'ctx-latest', analysisSubject: 'sku-19' });
  });

  it('keeps period clarification on the normal input path without a product selector', async () => {
    (askFactoryAnalysis as jest.Mock).mockResolvedValueOnce(answered({
      status: 'clarification', answer: '请补充有效的分析期间，例如 2026 年 1 月至 3 月', recommendations: [], facts: [],
      limitations: ['分析期间不完整'], subjects: [],
    }));
    const screen = renderScreen({ initialQuestion: '看本月产量' });
    await waitFor(() => expect(screen.getByText(/请补充有效的分析期间/)).toBeTruthy());
    expect(screen.queryByTestId('factory-analysis-subject-picker')).toBeNull();
    expect(screen.queryByTestId('factory-analysis-clarification-retry')).toBeNull();
    fireEvent.changeText(screen.getByTestId('factory-analysis-input'), '2026年1月至3月');
    fireEvent.press(screen.getByTestId('factory-analysis-send'));
    await waitFor(() => expect(askFactoryAnalysis).toHaveBeenCalledTimes(2));
    expect(askFactoryAnalysis).toHaveBeenLastCalledWith({ question: '2026年1月至3月', contextId: 'ctx-latest' });
  });

  it('sends only the latest context for an ordinary follow-up', async () => {
    const screen = renderScreen({ initialQuestion: '看产量' });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-followups')).toBeTruthy());
    fireEvent.press(screen.getByText('再看质量指标'));
    await waitFor(() => expect(askFactoryAnalysis).toHaveBeenCalledTimes(2));
    expect(askFactoryAnalysis).toHaveBeenLastCalledWith({ question: '再看质量指标', contextId: 'ctx-latest' });
  });

  it('carries the current route subject into manual first questions after a scope change', async () => {
    const screen = renderScreen({ source: 'business', analysisSubject: 'sku-a' });
    fireEvent.changeText(screen.getByTestId('factory-analysis-input'), '看产品产量');
    fireEvent.press(screen.getByTestId('factory-analysis-send'));
    await waitFor(() => expect(askFactoryAnalysis).toHaveBeenCalledTimes(1));
    expect(askFactoryAnalysis).toHaveBeenNthCalledWith(1, {
      question: '看产品产量', source: 'business', analysisSubject: 'sku-a',
      year: new Date().getFullYear(), periodType: 'all', startMonth: 1, endMonth: 12,
    });

    screen.rerender(<FactoryAnalysisChatScreen route={{ key: 'FactoryAnalysisChat', name: 'FactoryAnalysisChat', params: { source: 'business', analysisSubject: 'sku-b' } } as never} navigation={{} as never} />);
    fireEvent.changeText(screen.getByTestId('factory-analysis-input'), '再看产品成本');
    fireEvent.press(screen.getByTestId('factory-analysis-send'));
    await waitFor(() => expect(askFactoryAnalysis).toHaveBeenCalledTimes(2));
    expect(askFactoryAnalysis).toHaveBeenNthCalledWith(2, {
      question: '再看产品成本', source: 'business', analysisSubject: 'sku-b',
      year: new Date().getFullYear(), periodType: 'all', startMonth: 1, endMonth: 12,
    });
  });

  it('renders every active answer chart and exports real canvas snapshots with answer metadata', async () => {
    (askFactoryAnalysis as jest.Mock).mockResolvedValueOnce(answered({
      selectedIds: [chart.id, chartTwo.id, chartThree.id],
      recommendations: [chart, chartTwo, chartThree],
    }));
    const screen = renderScreen({ initialQuestion: '看生产质量成本', year: 2026, periodType: 'all', startMonth: 1, endMonth: 12 });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-canvas-cost.unit')).toBeTruthy());
    expect(screen.getByTestId('factory-analysis-canvas-production.oee')).toBeTruthy();
    expect(screen.getByTestId('factory-analysis-canvas-quality.pass')).toBeTruthy();
    fireEvent.press(screen.getByText('PDF'));
    await waitFor(() => expect(exportFactoryCharts).toHaveBeenCalledWith('pdf', expect.objectContaining({
      factory_id: 'F006',
      period_label: '2025 全年',
      year: 2025,
      period_type: 'all',
      start_month: 1,
      end_month: 12,
      chart_images: expect.objectContaining({
        'production.oee': expect.stringMatching(/^data:image\/png;base64,/),
        'quality.pass': expect.stringMatching(/^data:image\/png;base64,/),
        'cost.unit': expect.stringMatching(/^data:image\/png;base64,/),
      }),
    })));
    await waitFor(() => expect(screen.getByTestId('factory-analysis-export-share')).toBeTruthy());
    fireEvent.press(screen.getByTestId('factory-analysis-export-share'));
    expect(shareFactoryChartFile).toHaveBeenCalledTimes(1);
    fireEvent.press(screen.getAllByText('查看原始明细')[0]);
    expect(navigate).toHaveBeenCalledWith('FactoryChartDetails', expect.objectContaining({
      period: { year: 2025, periodType: 'all', startMonth: 1, endMonth: 12, label: '2025 全年' },
      periodLabel: '2025 全年',
    }));
  });

  it('rejects detail and export when an answer has no legal structured period', async () => {
    (askFactoryAnalysis as jest.Mock).mockResolvedValueOnce(answered({ period: undefined }));
    const screen = renderScreen({ initialQuestion: '看产量' });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-facts')).toBeTruthy());
    fireEvent.press(screen.getByText('EXCEL'));
    await waitFor(() => expect(screen.getByTestId('factory-analysis-export-error')).toBeTruthy());
    expect(screen.getByText(/缺少合法的结构化期间/)).toBeTruthy();
    expect(exportFactoryCharts).not.toHaveBeenCalled();
    fireEvent.press(screen.getByText('查看原始明细'));
    expect(screen.getByTestId('factory-analysis-detail-error')).toBeTruthy();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('shows unavailable answer limitations instead of hiding the missing-relation reason', async () => {
    (askFactoryAnalysis as jest.Mock).mockResolvedValueOnce(answered({
      status: 'unavailable', answer: '暂时无法完成联合分析', recommendations: [], facts: [],
      limitations: ['缺少产量与质量的可关联批次关系'], followUpQuestions: [],
    }));
    const screen = renderScreen({ initialQuestion: '联合看产量和质量' });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-limitations')).toBeTruthy());
    expect(screen.getByText(/缺少产量与质量的可关联批次关系/)).toBeTruthy();
    expect(screen.queryByTestId('factory-analysis-chart-list')).toBeNull();
  });

  it('retries an expired context with the latest unavailable answer period and subject', async () => {
    const expired = new FactoryAnalysisApiError('上下文已过期');
    Object.defineProperty(expired, 'status', { value: 410 });
    (askFactoryAnalysis as jest.Mock)
      .mockResolvedValueOnce(answered({ status: 'unavailable', analysisSubject: 'sku-a', period: { label: '2025 全年', year: 2025, periodType: 'all', startMonth: 1, endMonth: 12 } }))
      .mockRejectedValueOnce(expired)
      .mockResolvedValueOnce(answered({ analysisSubject: 'sku-a', period: { label: '2025 全年', year: 2025, periodType: 'all', startMonth: 1, endMonth: 12 } }));
    const screen = renderScreen({ initialQuestion: '去年产量', source: 'business', analysisSubject: 'sku-a', year: 2026, periodType: 'all', startMonth: 1, endMonth: 12 });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-followups')).toBeTruthy());
    fireEvent.press(screen.getByText('再看质量指标'));
    await waitFor(() => expect(screen.getByTestId('factory-analysis-clear-context-retry')).toBeTruthy());
    fireEvent.press(screen.getByTestId('factory-analysis-clear-context-retry'));
    await waitFor(() => expect(askFactoryAnalysis).toHaveBeenCalledTimes(3));
    expect(askFactoryAnalysis).toHaveBeenNthCalledWith(3, {
      question: '再看质量指标', source: 'business', analysisSubject: 'sku-a',
      year: 2025, periodType: 'all', startMonth: 1, endMonth: 12,
    });
  });

  it('keeps each older turn detail and export on its answer period snapshot', async () => {
    (askFactoryAnalysis as jest.Mock)
      .mockResolvedValueOnce(answered({ answer: '首轮事实', period: { label: '2025 全年', year: 2025, periodType: 'all', startMonth: 1, endMonth: 12 }, recommendations: [chart, chartTwo, chartThree] }))
      .mockResolvedValueOnce(answered({ answer: '次轮事实', period: { label: '2024 全年', year: 2024, periodType: 'all', startMonth: 1, endMonth: 12 }, question: '再看成本', recommendations: [chartVariant, chartTwo, chartThree] }));
    const screen = renderScreen({ initialQuestion: '去年产量', year: 2026, periodType: 'all', startMonth: 1, endMonth: 12 });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-followups')).toBeTruthy());
    fireEvent.press(screen.getByText('再看质量指标'));
    await waitFor(() => expect(screen.getByTestId('factory-analysis-answer-factory-analysis-2')).toBeTruthy());
    expect(screen.getAllByTestId(/factory-analysis-canvas-/)).toHaveLength(3);
    expect(screen.getByTestId('factory-analysis-canvas-production.oee.variant')).toBeTruthy();
    expect(screen.queryByTestId('factory-analysis-canvas-production.oee')).toBeNull();
    expect(screen.getByText('首轮事实')).toBeTruthy();
    const oldAnswer = screen.getByTestId('factory-analysis-answer-factory-analysis-1');
    fireEvent.press(within(oldAnswer).getByTestId('factory-analysis-show-charts-factory-analysis-1'));
    expect(screen.getAllByTestId(/factory-analysis-canvas-/)).toHaveLength(3);
    expect(screen.getByTestId('factory-analysis-canvas-production.oee')).toBeTruthy();
    expect(screen.queryByTestId('factory-analysis-canvas-production.oee.variant')).toBeNull();
    fireEvent.press(within(oldAnswer).getAllByText('查看原始明细')[0]);
    expect(navigate).toHaveBeenCalledWith('FactoryChartDetails', expect.objectContaining({ periodLabel: '2025 全年', period: { year: 2025, periodType: 'all', startMonth: 1, endMonth: 12, label: '2025 全年' } }));
    fireEvent.press(within(screen.getByTestId('factory-analysis-export-factory-analysis-1')).getByText('PDF'));
    await waitFor(() => expect(exportFactoryCharts).toHaveBeenCalledWith('pdf', expect.objectContaining({ year: 2025, period_label: '2025 全年' })));
  });

  it('shows export failure and never shares when the binary export fails', async () => {
    (exportFactoryCharts as jest.Mock).mockRejectedValueOnce(new Error('invalid binary'));
    const screen = renderScreen({ initialQuestion: '看产量' });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-facts')).toBeTruthy());
    fireEvent.press(screen.getByText('EXCEL'));
    await waitFor(() => expect(screen.getByTestId('factory-analysis-export-error')).toBeTruthy());
    expect(screen.getByText(/invalid binary/)).toBeTruthy();
    expect(shareFactoryChartFile).not.toHaveBeenCalled();
  });

  it('drops an in-flight answer when the route scope changes', async () => {
    let resolveRequest: ((value: ReturnType<typeof answered>) => void) | undefined;
    (askFactoryAnalysis as jest.Mock).mockImplementationOnce(() => new Promise(resolve => { resolveRequest = resolve; }));
    const screen = renderScreen();
    fireEvent.changeText(screen.getByTestId('factory-analysis-input'), '看产量');
    fireEvent.press(screen.getByTestId('factory-analysis-send'));
    screen.rerender(<FactoryAnalysisChatScreen route={{ key: 'FactoryAnalysisChat', name: 'FactoryAnalysisChat', params: { source: 'upload', uploadId: 42 } } as never} navigation={{} as never} />);
    resolveRequest?.(answered());
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(screen.queryByText('可信事实：OEE=83%')).toBeNull();
  });

  it('cancels export delivery when the route scope changes during capture', async () => {
    let resolveCapture: ((value: string) => void) | undefined;
    mockCapturePng.mockImplementationOnce(() => new Promise(resolve => { resolveCapture = resolve; }));
    const screen = renderScreen({ initialQuestion: '看产量' });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-facts')).toBeTruthy());
    fireEvent.press(screen.getByText('PDF'));
    await waitFor(() => expect(screen.getByTestId('factory-analysis-export-preview')).toBeTruthy());
    screen.rerender(<FactoryAnalysisChatScreen route={{ key: 'FactoryAnalysisChat', name: 'FactoryAnalysisChat', params: { source: 'business', year: 2025 } } as never} navigation={{} as never} />);
    resolveCapture?.('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lK0AAAAASUVORK5CYII=');
    await new Promise(resolve => setTimeout(resolve, 20));
    expect(exportFactoryCharts).not.toHaveBeenCalled();
    expect(shareFactoryChartFile).not.toHaveBeenCalled();
  });
});
