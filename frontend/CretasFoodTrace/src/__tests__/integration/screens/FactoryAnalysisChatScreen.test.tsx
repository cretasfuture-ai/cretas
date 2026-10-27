import React from 'react';
import { Text, View } from 'react-native';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import FactoryAnalysisChatScreen from '../../../screens/smartbi/FactoryAnalysisChatScreen';
import { askFactoryAnalysis } from '../../../services/api/smartbiFactoryAnalysis';
import { exportFactoryCharts, shareFactoryChartFile } from '../../../services/api/smartbiFactoryCharts';

const navigate = jest.fn();
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ navigate }) }));
jest.mock('../../../components/ui/ScreenWrapper', () => ({ ScreenWrapper: ({ children, testID }: { children: React.ReactNode; testID?: string }) => <View testID={testID}>{children}</View> }));
jest.mock('../../../services/api/smartbiFactoryAnalysis', () => ({
  askFactoryAnalysis: jest.fn(),
  FactoryAnalysisApiError: class FactoryAnalysisApiError extends Error { status?: number; },
}));
jest.mock('../../../services/api/smartbiFactoryCharts', () => ({
  exportFactoryCharts: jest.fn(),
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

function answered(overrides: Record<string, unknown> = {}) {
  return {
    factoryId: 'F006', source: 'business', sourceLabel: '本厂业务系统', period: { label: '全部历史' }, question: '看产量',
    status: 'answered', answer: '可信事实：OEE=83%', selectedIds: [chart.id], recommendations: [chart],
    catalog: Array.from({ length: 10 }, (_, index) => ({ id: `candidate-${index}`, familyId: `family-${index}`, chartType: 'bar', domain: '生产', metric: `指标${index}`, unit: '%', priority: 'optional', sourceKey: 'business' })),
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
  });

  it('renders trusted facts, source errors, limitations and an expandable full catalogue', async () => {
    const screen = renderScreen({ initialQuestion: '看产量' });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-facts')).toBeTruthy());
    expect(askFactoryAnalysis).toHaveBeenNthCalledWith(1, {
      question: '看产量', source: 'business', year: new Date().getFullYear(), periodType: 'all', startMonth: 1, endMonth: 12,
    });
    expect(screen.getAllByText('83 %').length).toBeGreaterThan(0);
    expect(screen.getByTestId('factory-analysis-source-errors')).toBeTruthy();
    expect(screen.getByTestId('factory-analysis-limitations')).toBeTruthy();
    expect(screen.queryByText('指标9')).toBeNull();
    fireEvent.press(screen.getByText('展开目录'));
    expect(screen.getByText(/指标9/)).toBeTruthy();
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

  it('renders every active answer chart and exports real canvas snapshots with answer metadata', async () => {
    (askFactoryAnalysis as jest.Mock).mockResolvedValueOnce(answered({
      selectedIds: [chart.id, chartTwo.id, chartThree.id],
      recommendations: [chart, chartTwo, chartThree],
    }));
    const screen = renderScreen({ initialQuestion: '看生产质量成本' });
    await waitFor(() => expect(screen.getByTestId('factory-analysis-canvas-cost.unit')).toBeTruthy());
    expect(screen.getByTestId('factory-analysis-canvas-production.oee')).toBeTruthy();
    expect(screen.getByTestId('factory-analysis-canvas-quality.pass')).toBeTruthy();
    fireEvent.press(screen.getByText('PDF'));
    await waitFor(() => expect(exportFactoryCharts).toHaveBeenCalledWith('pdf', expect.objectContaining({
      factory_id: 'F006',
      period_label: '全部历史',
      chart_images: expect.objectContaining({
        'production.oee': expect.stringMatching(/^data:image\/png;base64,/),
        'quality.pass': expect.stringMatching(/^data:image\/png;base64,/),
        'cost.unit': expect.stringMatching(/^data:image\/png;base64,/),
      }),
    })));
    expect(shareFactoryChartFile).toHaveBeenCalledTimes(1);
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
