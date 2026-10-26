import React from 'react';
import { Text } from 'react-native';
import { act, fireEvent, render, waitFor, within } from '@testing-library/react-native';
import FactoryChartsScreen from '../../../screens/smartbi/FactoryChartsScreen';
import { useAuthStore } from '../../../store/authStore';
import { exportFactoryCharts, shareFactoryChartFile, getFactoryChartSelection, getFactoryChartUploads, getFactoryRecommendations, saveFactoryChartSelection } from '../../../services/api/smartbiFactoryCharts';

const mockCapturePng = jest.fn<Promise<string>, [string]>();
const firstPng = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lK0AAAAASUVORK5CYII=';
const secondPng = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=';

jest.mock('../../../services/api/smartbiFactoryCharts', () => ({
  getFactoryRecommendations: jest.fn(),
  getFactoryChartSelection: jest.fn(),
  saveFactoryChartSelection: jest.fn(),
  getFactoryChartUploads: jest.fn(),
  exportFactoryCharts: jest.fn(),
  shareFactoryChartFile: jest.fn(),
}));

jest.mock('../../../components/smartbi/FactoryChartCanvas', () => ({
  __esModule: true,
  default: React.forwardRef((props: { testID?: string; option: Record<string, unknown>; onReady?: () => void }, ref: React.ForwardedRef<{ capturePng: () => Promise<string> }>) => {
    React.useImperativeHandle(ref, () => ({ capturePng: () => mockCapturePng(String(props.option.testCandidate)) }), [props.option]);
    React.useEffect(() => { props.onReady?.(); }, [props.onReady]);
    return <Text testID={props.testID}>mounted chart</Text>;
  }),
}));

const recommendation = {
  id: 'sales', title: '销售渠道', metric: '收入', domain: '销售', priority: 'required', reason: 'reason', source: 'business', unit: '元',
  findings: { good: ['稳定'], attention: [], suggestions: [] }, chart: { chartType: 'bar_horizontal', echartsOption: { testCandidate: 'sales', series: [] } }, kpis: [{ label: '收入', value: 30, unit: '元' }], tableData: null,
  detailContext: { id: 'ctx', expiresAt: '2099-01-01T00:00:00Z' },
} as const;
const optionalRecommendation = { ...recommendation, id: 'optional', title: '可选分析', priority: 'optional' as const, chart: { chartType: 'bar_horizontal', echartsOption: { testCandidate: 'optional', series: [] } } };

function successData() {
  return { factoryId: 'F006', source: 'business', analysisSubject: null, sourceLabel: '业务数据', period: { label: '全部历史' }, defaultSelection: ['sales'], ranking: { mode: 'rule', reason: '' }, recommendations: [recommendation, optionalRecommendation], unavailable: [], sourceErrors: [] };
}

describe('FactoryChartsScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (getFactoryRecommendations as jest.Mock).mockResolvedValue({ success: true, data: successData() });
    (getFactoryChartSelection as jest.Mock).mockResolvedValue({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: null, updatedAt: null } });
    (getFactoryChartUploads as jest.Mock).mockResolvedValue([]);
    mockCapturePng.mockReset().mockImplementation(async id => id === 'sales' ? firstPng : secondPng);
    (exportFactoryCharts as jest.Mock).mockResolvedValue({ uri: 'file:///cache/result.pdf', name: 'result.pdf', mimeType: 'application/pdf', size: 1000, factoryId: 'F006', userId: '1309', role: 'factory_super_admin' });
    (shareFactoryChartFile as jest.Mock).mockResolvedValue(undefined);
  });

  it('shows retry after recommendation failure and recovers asynchronously', async () => {
    (getFactoryRecommendations as jest.Mock).mockRejectedValueOnce(new Error('network down')).mockResolvedValueOnce({ success: true, data: successData() });
    const screen = render(<FactoryChartsScreen />);
    await waitFor(() => expect(getFactoryRecommendations).toHaveBeenCalledWith(expect.objectContaining({ guided: true, analysis_subject: null })));
    await waitFor(() => expect(screen.getByText('network down')).toBeTruthy());
    fireEvent.press(screen.getByText('重试'));
    await waitFor(() => expect(screen.getAllByText('销售渠道').length).toBeGreaterThan(0));
    expect(screen.queryByTestId('factory-chart-card-optional')).toBeNull();
    fireEvent.press(screen.getByTestId('factory-chart-selection-toggle'));
    fireEvent.press(within(screen.getByTestId('factory-family-optional')).getByText('加入分析'));
    await waitFor(() => expect(screen.getByTestId('factory-chart-card-optional')).toBeTruthy());
    expect(getFactoryRecommendations).toHaveBeenCalledTimes(2);
  });

  it('keeps the guided category and subject flow local while bounding product choices', async () => {
    const guidedData = {
      ...successData(),
      subjects: Array.from({ length: 100 }, (_, index) => ({ id: `product-${index}`, label: `产品${index}` })),
      recommendations: [
        { ...recommendation, analysis: { familyId: 'sales', familyLabel: '销售表现' } },
        { ...optionalRecommendation, id: 'sales@box', title: '销售表现-箱', analysis: { familyId: 'sales', familyLabel: '销售表现' } },
      ],
    };
    (getFactoryRecommendations as jest.Mock).mockImplementation(async request => ({ success: true, data: { ...guidedData, analysisSubject: request.analysis_subject } }));
    const screen = render(<FactoryChartsScreen />);
    await waitFor(() => expect(screen.getByText('销售')).toBeTruthy());
    fireEvent.press(screen.getByText('销售'));
    expect(screen.getByText('销售表现')).toBeTruthy();
    fireEvent.press(screen.getByText('销售表现'));
    expect(screen.getByLabelText('搜索产品')).toBeTruthy();
    expect(screen.queryByText('产品99')).toBeNull();
    fireEvent.changeText(screen.getByLabelText('搜索产品'), '产品99');
    fireEvent.press(screen.getByText('产品99'));
    await waitFor(() => expect(getFactoryRecommendations).toHaveBeenLastCalledWith(expect.objectContaining({ guided: true, analysis_subject: 'product-99' })));
  });

  it('uses the business answer and selected topic to choose local charts without fetching or saving', async () => {
    const items = Array.from({ length: 7 }, (_, index) => ({ ...recommendation, id: `cost-${index}`, domain: '成本', title: `成本图${index}`, analysis: { familyId: `cost-${index}`, familyLabel: `成本主题${index}` } }));
    (getFactoryRecommendations as jest.Mock).mockResolvedValue({ success: true, data: { ...successData(), recommendations: [recommendation, ...items] } });
    const screen = render(<FactoryChartsScreen />);
    await waitFor(() => expect(screen.getByTestId('factory-chart-card-sales')).toBeTruthy());
    fireEvent.press(screen.getByText('成本'));
    fireEvent.press(screen.getByText('推荐组合（最多 4 张）'));
    fireEvent.press(screen.getByTestId('factory-chart-load'));
    await waitFor(() => expect(screen.getByTestId('factory-chart-card-cost-0')).toBeTruthy());
    expect(screen.queryByTestId('factory-chart-card-sales')).toBeNull();
    expect(screen.queryByTestId('factory-chart-card-cost-4')).toBeNull();
    expect(getFactoryRecommendations).toHaveBeenCalledTimes(1);
    expect(saveFactoryChartSelection).not.toHaveBeenCalled();
    fireEvent.press(screen.getByText('成本主题5'));
    fireEvent.press(screen.getByTestId('factory-chart-load'));
    await waitFor(() => expect(screen.getByTestId('factory-chart-card-cost-5')).toBeTruthy());
    expect(screen.queryByTestId('factory-chart-card-cost-0')).toBeNull();
  });

  it('rejects unscoped product results and keeps controls to return to factory data', async () => {
    (getFactoryRecommendations as jest.Mock).mockResolvedValue({ success: true, data: { ...successData(), subjects: [{ id: 'p1', label: '黄油鸡' }] } });
    const screen = render(<FactoryChartsScreen />);
    await waitFor(() => expect(screen.getByTestId('factory-chart-card-sales')).toBeTruthy());
    fireEvent.press(screen.getByText('先看整体情况'));
    fireEvent.press(screen.getByText('推荐组合（最多 4 张）'));
    fireEvent.press(screen.getByText('黄油鸡'));
    await waitFor(() => expect(screen.getByText('返回的分析产品与当前选择不一致')).toBeTruthy());
    expect(screen.queryByTestId('factory-chart-card-sales')).toBeNull();
    fireEvent.press(screen.getByTestId('factory-guide-all-products'));
    await waitFor(() => expect(screen.getByTestId('factory-chart-card-sales')).toBeTruthy());
    expect(getFactoryRecommendations).toHaveBeenLastCalledWith(expect.objectContaining({ analysis_subject: null, guided: true }));
    expect(saveFactoryChartSelection).not.toHaveBeenCalled();
  });

  it('keeps explicit empty selection empty and does not save implicitly', async () => {
    (getFactoryChartSelection as jest.Mock).mockResolvedValue({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: [], updatedAt: null } });
    const screen = render(<FactoryChartsScreen />);
    await waitFor(() => expect(screen.getByText('当前条件没有可展示的推荐图表。')).toBeTruthy());
    expect(screen.queryByTestId('factory-chart-card-sales')).toBeNull();
    fireEvent.press(screen.getByText('PDF'));
    await waitFor(() => expect(screen.getByText(/请至少选择一个图表/)).toBeTruthy());
    expect(exportFactoryCharts).not.toHaveBeenCalled();
  });

  it('captures each selected chart in a visible serial preview before exporting and sharing', async () => {
    (getFactoryChartSelection as jest.Mock).mockResolvedValue({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: ['sales', 'optional'] } });
    const screen = render(<FactoryChartsScreen />);
    await waitFor(() => expect(screen.getByTestId('factory-chart-card-sales')).toBeTruthy());
    fireEvent.press(screen.getByText('PDF'));
    await waitFor(() => expect(shareFactoryChartFile).toHaveBeenCalledTimes(1));
    expect(mockCapturePng.mock.calls).toEqual([['sales'], ['optional']]);
    expect(exportFactoryCharts).toHaveBeenCalledWith('pdf', expect.objectContaining({
      factory_id: 'F006', period_label: '全部历史',
      chart_images: { sales: firstPng, optional: secondPng },
      charts: [expect.objectContaining({ candidate_id: 'sales' }), expect.objectContaining({ candidate_id: 'optional' })],
    }));
  });

  it('does not export a partial report when the second selected PNG fails', async () => {
    (getFactoryChartSelection as jest.Mock).mockResolvedValue({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: ['sales', 'optional'] } });
    mockCapturePng.mockImplementation(async id => { if (id === 'optional') throw new Error('second PNG failed'); return firstPng; });
    const screen = render(<FactoryChartsScreen />);
    await waitFor(() => expect(screen.getByTestId('factory-chart-card-sales')).toBeTruthy());
    fireEvent.press(screen.getByText('PPT'));
    await waitFor(() => expect(screen.getByText(/second PNG failed/)).toBeTruthy());
    expect(exportFactoryCharts).not.toHaveBeenCalled();
    expect(shareFactoryChartFile).not.toHaveBeenCalled();
  });

  it.each(['source', 'unmount'])('cancels an in-flight capture after %s changes', async mode => {
    let finishCapture!: (value: string) => void;
    mockCapturePng.mockImplementation(() => new Promise(resolve => { finishCapture = resolve; }));
    const screen = render(<FactoryChartsScreen />);
    await waitFor(() => expect(screen.getByTestId('factory-chart-card-sales')).toBeTruthy());
    fireEvent.press(screen.getByText('PDF'));
    await waitFor(() => expect(mockCapturePng).toHaveBeenCalledTimes(1));
    if (mode === 'source') fireEvent.press(screen.getByText('上传数据'));
    else screen.unmount();
    await act(async () => { finishCapture(firstPng); await Promise.resolve(); });
    expect(exportFactoryCharts).not.toHaveBeenCalled();
    expect(shareFactoryChartFile).not.toHaveBeenCalled();
  });

  it('normalizes annual month bounds after switching from a custom range', async () => {
    const screen = render(<FactoryChartsScreen />);
    await waitFor(() => expect(screen.getByTestId('factory-chart-card-sales')).toBeTruthy());
    fireEvent.press(screen.getByText('月范围'));
    fireEvent.changeText(screen.getByLabelText('开始月'), '2');
    fireEvent.changeText(screen.getByLabelText('结束月'), '4');
    fireEvent.press(screen.getByText('指定年度'));
    await waitFor(() => expect(getFactoryRecommendations).toHaveBeenLastCalledWith(expect.objectContaining({ period_type: 'year', start_month: 1, end_month: 12 })));
  });

  it('clears the selected upload and file names after a factory or role switch', async () => {
    const original = useAuthStore.getState();
    await act(async () => { useAuthStore.setState({ getFactoryId: () => 'F006', getUserRole: () => 'factory_super_admin' }); });
    (getFactoryChartUploads as jest.Mock).mockResolvedValueOnce([{ id: 101, fileName: 'F006-old.xlsx' }]).mockResolvedValueOnce([{ id: 202, fileName: 'F007-new.xlsx' }]);
    (getFactoryRecommendations as jest.Mock).mockImplementation(async request => ({ success: true, data: { ...successData(), source: request.source } }));
    (getFactoryChartSelection as jest.Mock).mockImplementation(async source => ({ success: true, data: { factoryId: 'F006', source, selectedIds: [] } }));
    const screen = render(<FactoryChartsScreen />);
    try {
      await waitFor(() => expect(screen.getByText('上传数据')).toBeTruthy());
      fireEvent.press(screen.getByText('上传数据'));
      await waitFor(() => expect(screen.getByText('F006-old.xlsx')).toBeTruthy());
      fireEvent.press(screen.getByText('F006-old.xlsx'));
      await waitFor(() => expect(getFactoryRecommendations).toHaveBeenCalledWith(expect.objectContaining({ upload_id: 101 })));
      const callsBeforeSwitch = (getFactoryRecommendations as jest.Mock).mock.calls.length;
      await act(async () => { useAuthStore.setState({ getFactoryId: () => 'F007', getUserRole: () => 'finance_manager' }); });
      await waitFor(() => expect(screen.getByText('F007-new.xlsx')).toBeTruthy());
      expect(screen.queryByText('F006-old.xlsx')).toBeNull();
      expect((getFactoryRecommendations as jest.Mock).mock.calls.slice(callsBeforeSwitch)).toEqual([]);
    } finally {
      screen.unmount();
      await act(async () => { useAuthStore.setState(original, true); });
    }
  });

  it('keeps selected charts reachable before hundreds of optional gap explanations without hiding source errors', async () => {
    const unavailable = Array.from({ length: 200 }, (_, index) => ({ id: `gap-${index}`, title: `未满足条件${index}`, missing: ['真实条件不足'] }));
    (getFactoryRecommendations as jest.Mock).mockResolvedValue({ success: true, data: { ...successData(), unavailable, sourceErrors: [{ source: 'accounting', message: 'read failed' }] } });
    const screen = render(<FactoryChartsScreen />);
    await waitFor(() => expect(screen.getByTestId('factory-chart-card-sales')).toBeTruthy());
    expect(screen.queryByText(/未提供：未满足条件/)).toBeNull();
    expect(screen.getByText('来源错误：accounting · read failed')).toBeTruthy();
    expect(screen.getByText('查看未满足条件说明（200）')).toBeTruthy();
    fireEvent.press(screen.getByTestId('factory-chart-unavailable-toggle'));
    expect(screen.getByText(/未提供：未满足条件199/)).toBeTruthy();
    fireEvent.press(screen.getByTestId('factory-chart-unavailable-toggle'));
    expect(screen.queryByText(/未提供：未满足条件/)).toBeNull();
  });
});
