import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

import QualityReportScreen from '../../../screens/reports/QualityReportScreen';
import { useAuthStore } from '../../../store/authStore';
import { qualityInspectionApiClient } from '../../../services/api/qualityInspectionApiClient';
import { canvasApiClient } from '../../../services/api/canvasApiClient';
import { reportApiClient } from '../../../services/api/reportApiClient';

jest.mock('react-native', () => {
  const ReactRuntime = require('react');
  const component = (name: string) => ({ children, ...props }: Record<string, unknown>) =>
    ReactRuntime.createElement(name, props, children);
  return {
    View: component('View'),
    Text: component('Text'),
    TouchableOpacity: component('TouchableOpacity'),
    ScrollView: component('ScrollView'),
    SafeAreaView: component('SafeAreaView'),
    ActivityIndicator: component('ActivityIndicator'),
    RefreshControl: component('RefreshControl'),
    StyleSheet: { create: (styles: unknown) => styles, flatten: (style: unknown) => style },
    Alert: { alert: jest.fn() },
  };
});

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => ({
      'quality.managementTitle': '质量管理',
      'quality.overviewTab': '质量概览',
      'quality.anomalyTab': '异常检测',
      'quality.timeRange': '时间范围',
      'quality.today': '今日',
      'quality.thisWeek': '近7天',
      'quality.thisMonth': '本月',
      'quality.qualityStats': '质检统计',
      'quality.totalInspections': '总质检数',
      'quality.passed': '合格',
      'quality.failed': '不合格',
      'quality.conditional': '条件通过',
      'quality.pending': '待检',
      'quality.unknown': '未知',
      'quality.emptyResult': '未判定',
      'quality.averagePassRate': '检测平均合格率',
      'quality.decisionPassRate': '判定通过率',
      'quality.statisticsRange': '统计范围',
      'quality.totalSampleSize': '总样本量',
      'quality.missingSampleSize': '缺少样本量',
      'quality.recentInspectionRecords': '最近质检记录',
      'quality.recentTwenty': '最近20条',
      'quality.batchNumber': '批次号',
      'quality.inspector': '质检员',
      'quality.result': '结果',
      'quality.noStatsData': '暂无统计数据',
      'quality.noInspectionRecords': '暂无质检记录',
      'quality.permissionDenied': '当前账号没有质量数据读取权限',
      'quality.retry': '重新加载',
      'quality.loadSamplesFailed': '近期质检样本加载失败',
      'quality.loadAnomalyFailed': '异常数据加载失败',
      'quality.cannotGetFactoryInfo': '无法获取工厂信息，请重新登录',
      'quality.loadFailed': '加载失败',
      'quality.loadQualityDataFailed': '加载质检数据失败，请稍后重试',
      'common.loading': '加载中...',
      'common.error': '错误',
    }[key] ?? key),
  }),
}));

jest.mock('react-native-safe-area-context', () => {
  const ReactRuntime = require('react');
  return {
    SafeAreaView: ({ children, ...props }: Record<string, unknown> & { children?: unknown }) =>
      ReactRuntime.createElement('View', props, children),
  };
});

jest.mock('../../../store/authStore', () => ({ useAuthStore: jest.fn() }));
jest.mock('../../../services/api/qualityInspectionApiClient', () => ({
  qualityInspectionApiClient: {
    getStatistics: jest.fn(),
    getInspections: jest.fn(),
  },
}));
jest.mock('../../../services/api/canvasApiClient', () => ({
  canvasApiClient: { getUserModuleAccess: jest.fn() },
}));
jest.mock('../../../services/api/reportApiClient', () => ({
  reportApiClient: { getAnomalyReport: jest.fn() },
}));

const mockUseAuthStore = useAuthStore as unknown as jest.Mock & {
  getState: () => { user: TestUser | null };
};
const mockGetStatistics = qualityInspectionApiClient.getStatistics as jest.Mock;
const mockGetInspections = qualityInspectionApiClient.getInspections as jest.Mock;
const mockGetUserModuleAccess = canvasApiClient.getUserModuleAccess as jest.Mock;
const mockGetAnomalyReport = reportApiClient.getAnomalyReport as jest.Mock;

const mockGoBack = jest.fn();
jest.mock('@react-navigation/native', () => {
  const ReactRuntime = require('react');
  return {
    useNavigation: () => ({ goBack: mockGoBack, dispatch: jest.fn() }),
    useFocusEffect: (effect: () => void | (() => void)) => ReactRuntime.useEffect(effect, [effect]),
    CommonActions: { navigate: jest.fn() },
  };
});

type TestUser = {
  id: number;
  userType: 'factory';
  factoryId: string;
  role: string;
  factoryUser: {
    factoryId: string;
    role: string;
    permissions: string[];
    modulePermissions: Record<string, 'rw' | 'r' | 'w' | '-'>;
  };
};

type Deferred<T> = {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: Error) => void;
};

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

function actor(factoryId: string, id: number, finance: 'r' | '-' = 'r', productionPlan: 'r' | '-' = 'r', priceOnly = false): TestUser {
  return {
    id,
    userType: 'factory',
    factoryId,
    role: 'quality_manager',
    factoryUser: {
      factoryId,
      role: 'quality_manager',
      permissions: priceOnly ? ['procurement:price:view'] : [],
      modulePermissions: {
        production_plan: productionPlan,
        finance,
        quality: 'r',
      },
    },
  };
}

function summary(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    startDate: '2026-09-28',
    endDate: '2026-10-04',
    totalInspections: 6,
    statusCounts: { PASS: 1, FAIL: 1, CONDITIONAL: 1, PENDING: 1, UNKNOWN: 1, EMPTY: 1 },
    passedInspections: 1,
    failedInspections: 1,
    conditionalInspections: 1,
    pendingInspections: 1,
    unknownInspections: 1,
    emptyResultInspections: 1,
    averagePassRate: 0,
    reportedPassRateCount: 1,
    missingPassRateCount: 5,
    decisionPassRate: 0,
    decidedInspectionCount: 3,
    totalSampleSize: 0,
    totalPassCount: 0,
    totalFailCount: 0,
    missingSampleSizeCount: 1,
    missingPassCount: 1,
    missingFailCount: 1,
    ...overrides,
  };
}

const sampleResponse = {
  success: true,
  data: {
    content: [
      { id: '1', productionBatchId: 1, inspectorId: 10, result: 'PENDING' },
      { id: '2', productionBatchId: 2, inspectorId: 11, result: 'REWORK' },
      { id: '3', productionBatchId: 3, inspectorId: 12, result: '  ' },
    ],
  },
  message: '',
};

describe('QualityReportScreen real quality summary behavior', () => {
  let currentUser: TestUser;
  let statisticsRequests: Array<{ params: { startDate: string; endDate: string }; deferred: Deferred<unknown> }>;

  beforeEach(() => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date(2026, 9, 4, 12, 0, 0));
    currentUser = actor('F006', 1);
    statisticsRequests = [];
    mockUseAuthStore.mockImplementation(() => ({ user: currentUser }));
    mockUseAuthStore.getState = () => ({ user: currentUser });
    mockGetUserModuleAccess.mockResolvedValue([{ moduleCode: 'production_plan', effectiveAllowed: true }]);
    mockGetInspections.mockResolvedValue(sampleResponse);
    mockGetAnomalyReport.mockResolvedValue({ anomalies: [], totalAnomalies: 0 });
    mockGetStatistics.mockImplementation((params: { startDate: string; endDate: string }) => {
      const response = deferred<unknown>();
      statisticsRequests.push({ params, deferred: response });
      return response.promise;
    });
  });

  afterEach(() => {
    jest.clearAllMocks();
    jest.useRealTimers();
  });

  async function resolveStatistics(index: number, data = summary()) {
    await act(async () => {
      statisticsRequests[index]!.deferred.resolve({ success: true, data: { qualitySummary: data }, message: '' });
    });
  }

  it('uses full-period statistics, keeps zero rates, and renders status/missing semantics', async () => {
    const view = render(<QualityReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    expect(statisticsRequests[0]!.params.startDate).toBe('2026-09-28');
    expect(statisticsRequests[0]!.params.endDate).toBe('2026-10-04');
    await resolveStatistics(0);

    expect(view.getByText('统计范围: 2026-09-28 - 2026-10-04')).toBeTruthy();
    expect(view.getByText('检测平均合格率')).toBeTruthy();
    expect(view.getAllByText('0.0%').length).toBeGreaterThanOrEqual(2);
    expect(view.getAllByText('待检').length).toBeGreaterThanOrEqual(1);
    expect(view.getAllByText('未判定').length).toBeGreaterThanOrEqual(2);
    expect(view.getByText('最近质检记录 · 最近20条')).toBeTruthy();
    expect(view.getAllByText('待检').length).toBeGreaterThanOrEqual(1);
    expect(view.getAllByText('未知').length).toBeGreaterThanOrEqual(2);
    expect(view.getAllByText('未判定').length).toBeGreaterThanOrEqual(2);

    await act(async () => fireEvent.press(view.getByText('今日')));
    await waitFor(() => expect(statisticsRequests).toHaveLength(2));
    expect(statisticsRequests[1]!.params.startDate).toBe(statisticsRequests[1]!.params.endDate);
  });

  it('settles summary and sample failures independently and keeps empty summary visible', async () => {
    mockGetInspections.mockRejectedValueOnce(new Error('sample failed'));
    const view = render(<QualityReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    await resolveStatistics(0, summary({
      totalInspections: 0,
      statusCounts: { PASS: 0, FAIL: 0, CONDITIONAL: 0, PENDING: 0, UNKNOWN: 0, EMPTY: 0 },
      passedInspections: 0,
      failedInspections: 0,
      conditionalInspections: 0,
      pendingInspections: 0,
      unknownInspections: 0,
      emptyResultInspections: 0,
      averagePassRate: null,
      decisionPassRate: null,
      decidedInspectionCount: 0,
    }));
    await waitFor(() => expect(view.getByText('sample failed')).toBeTruthy());
    expect(view.getByText('统计范围: 2026-09-28 - 2026-10-04')).toBeTruthy();
    expect(view.getAllByText('未知').length).toBeGreaterThan(0);

    mockGetStatistics.mockRejectedValueOnce(new Error('statistics failed'));
    currentUser = actor('F006', 2);
    await act(async () => view.rerender(<QualityReportScreen />));
    await waitFor(() => expect(view.getByText('statistics failed')).toBeTruthy());
    expect(view.queryByText('sample failed')).toBeNull();
  });

  it('does not request samples for effective DENY and allows an effective GRANT without role permission', async () => {
    currentUser = actor('F006', 1, 'r', '-');
    mockGetUserModuleAccess.mockResolvedValueOnce([{ moduleCode: 'production_plan', effectiveAllowed: false }]);
    const deniedView = render(<QualityReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    await resolveStatistics(0);
    await waitFor(() => expect(deniedView.getByText('当前账号没有质量数据读取权限')).toBeTruthy());
    expect(mockGetInspections).not.toHaveBeenCalled();

    currentUser = actor('F006', 2, 'r', '-');
    mockGetUserModuleAccess.mockResolvedValue([{ moduleCode: 'production_plan', effectiveAllowed: true }]);
    const grantedView = render(<QualityReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(2));
    await resolveStatistics(1);
    await waitFor(() => expect(mockGetInspections).toHaveBeenCalledTimes(1));
    expect(mockGetInspections.mock.calls[0]![1]).toBe('F006');
    expect(grantedView.getByText('最近质检记录 · 最近20条')).toBeTruthy();
  });

  it('allows price-only anomaly access and sends the selected factory/date range', async () => {
    currentUser = actor('F006', 3, '-', 'r', true);
    const view = render(<QualityReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    await resolveStatistics(0);
    await act(async () => fireEvent.press(view.getByText('异常检测')));
    await waitFor(() => expect(mockGetAnomalyReport).toHaveBeenCalledTimes(1));
    expect(mockGetAnomalyReport.mock.calls[0]![0]).toMatchObject({
      factoryId: 'F006',
      startDate: '2026-09-28',
      endDate: '2026-10-04',
    });
  });

  it('drops a late sample response after the actor changes and scopes each request', async () => {
    const oldStats = deferred<unknown>();
    const oldAccess = deferred<unknown>();
    const oldSample = deferred<unknown>();
    const newSample = deferred<unknown>();
    mockGetStatistics.mockImplementationOnce(() => oldStats.promise);
    mockGetUserModuleAccess.mockImplementationOnce(() => oldAccess.promise);
    mockGetInspections
      .mockImplementationOnce(() => oldSample.promise)
      .mockImplementationOnce(() => newSample.promise);
    const view = render(<QualityReportScreen />);
    await waitFor(() => expect(mockGetUserModuleAccess).toHaveBeenCalledTimes(1));

    await act(async () => {
      oldAccess.resolve([{ moduleCode: 'production_plan', effectiveAllowed: true }]);
      oldStats.resolve({ success: true, data: { qualitySummary: summary() }, message: '' });
    });
    await waitFor(() => expect(mockGetInspections).toHaveBeenCalledTimes(1));
    expect(mockGetInspections.mock.calls[0]![1]).toBe('F006');

    currentUser = actor('F007', 2);
    await act(async () => view.rerender(<QualityReportScreen />));
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    await resolveStatistics(0);
    await waitFor(() => expect(mockGetInspections).toHaveBeenCalledTimes(2));
    expect(mockGetInspections.mock.calls[1]![1]).toBe('F007');
    expect(view.queryByText('统计范围: 2026-09-28 - 2026-10-04')).toBeNull();

    await act(async () => {
      oldSample.resolve({
        success: true,
        data: { content: [{ id: 'old', productionBatchId: 6, inspectorId: 1, result: 'FAIL' }] },
        message: '',
      });
    });
    expect(view.queryByText('批次#6')).toBeNull();

    await act(async () => {
      newSample.resolve({
        success: true,
        data: { content: [{ id: 'new', productionBatchId: 7, inspectorId: 2, result: 'PASS' }] },
        message: '',
      });
    });
    await waitFor(() => expect(view.getByText('批次#7')).toBeTruthy());
    expect(view.queryByText('批次#6')).toBeNull();
    expect(view.getByText('统计范围: 2026-09-28 - 2026-10-04')).toBeTruthy();
  });

  it('drops a late anomaly response when the actor changes and scopes both requests', async () => {
    const oldAnomaly = deferred<unknown>();
    const newAnomaly = deferred<unknown>();
    mockGetAnomalyReport
      .mockImplementationOnce(() => oldAnomaly.promise)
      .mockImplementationOnce(() => newAnomaly.promise);
    const view = render(<QualityReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    await act(async () => fireEvent.press(view.getByText('异常检测')));
    await waitFor(() => expect(mockGetAnomalyReport).toHaveBeenCalledTimes(1));
    expect(mockGetAnomalyReport.mock.calls[0]![0]).toMatchObject({ factoryId: 'F006' });

    currentUser = actor('F007', 2);
    await act(async () => view.rerender(<QualityReportScreen />));
    await waitFor(() => expect(mockGetAnomalyReport).toHaveBeenCalledTimes(2));
    expect(mockGetAnomalyReport.mock.calls[1]![0]).toMatchObject({ factoryId: 'F007' });

    await act(async () => {
      oldAnomaly.resolve({
        anomalies: [{ type: 'OLD', level: 'CRITICAL', title: 'old', description: 'F006 old', count: 1, detectedAt: '2026-10-04T01:00:00Z' }],
        totalAnomalies: 1,
      });
    });
    expect(view.queryByText('F006 old')).toBeNull();

    await act(async () => {
      newAnomaly.resolve({
        anomalies: [{ type: 'NEW', level: 'INFO', title: 'new', description: 'F007 new', count: 1, detectedAt: '2026-10-04T02:00:00Z' }],
        totalAnomalies: 1,
      });
    });
    await waitFor(() => expect(view.getByText('F007 new')).toBeTruthy());
    expect(view.queryByText('F006 old')).toBeNull();
  });

  it('ignores an old overview refresh after switching to anomaly and keeps the new response', async () => {
    const view = render(<QualityReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    const oldOverviewRefresh = view.getByTestId('quality-report-scroll-view').props.refreshControl.props.onRefresh;
    const anomalyResponse = deferred<unknown>();
    mockGetAnomalyReport.mockImplementationOnce(() => anomalyResponse.promise);

    await act(async () => fireEvent.press(view.getByText('异常检测')));
    await waitFor(() => expect(mockGetAnomalyReport).toHaveBeenCalledTimes(1));
    expect(view.getByTestId('quality-report-scroll-view').props.refreshControl.props.refreshing).toBe(false);

    await act(async () => {
      void oldOverviewRefresh();
    });
    expect(statisticsRequests).toHaveLength(1);

    await act(async () => {
      anomalyResponse.resolve({
        anomalies: [{ type: 'CURRENT', level: 'INFO', title: 'current', description: 'new anomaly', count: 1, detectedAt: '2026-10-04T02:00:00Z' }],
        totalAnomalies: 1,
      });
    });
    await waitFor(() => expect(view.getByText('new anomaly')).toBeTruthy());
    expect(view.queryByText('加载中...')).toBeNull();
    expect(view.getByTestId('quality-report-scroll-view').props.refreshControl.props.refreshing).toBe(false);
  });

  it('ignores an old week refresh after switching to today and settles the new range', async () => {
    const view = render(<QualityReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    const oldWeekRefresh = view.getByTestId('quality-report-scroll-view').props.refreshControl.props.onRefresh;

    await act(async () => fireEvent.press(view.getByText('今日')));
    await waitFor(() => expect(statisticsRequests).toHaveLength(2));
    await act(async () => {
      void oldWeekRefresh();
    });
    expect(statisticsRequests).toHaveLength(2);

    await resolveStatistics(1, summary({ startDate: '2026-10-04', endDate: '2026-10-04' }));
    await waitFor(() => expect(view.getByText('统计范围: 2026-10-04 - 2026-10-04')).toBeTruthy());
    expect(view.getByTestId('quality-report-scroll-view').props.refreshControl.props.refreshing).toBe(false);
  });

  it('clears a hanging refresh when the owner changes and ignores the old refresh completion', async () => {
    const view = render(<QualityReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    await resolveStatistics(0);

    const refreshStats = deferred<unknown>();
    mockGetStatistics.mockImplementationOnce((params: { startDate: string; endDate: string }) => {
      statisticsRequests.push({ params, deferred: refreshStats });
      return refreshStats.promise;
    });
    const scrollView = view.getByTestId('quality-report-scroll-view');
    const refreshControl = scrollView.props.refreshControl;
    await act(async () => {
      void refreshControl.props.onRefresh();
    });
    await waitFor(() => expect(statisticsRequests).toHaveLength(2));
    expect(scrollView.props.refreshControl.props.refreshing).toBe(true);

    currentUser = actor('F007', 2);
    await act(async () => view.rerender(<QualityReportScreen />));
    expect(view.getByTestId('quality-report-scroll-view').props.refreshControl.props.refreshing).toBe(false);

    await act(async () => {
      refreshStats.resolve({ success: true, data: { qualitySummary: summary() }, message: '' });
    });
    expect(view.getByTestId('quality-report-scroll-view').props.refreshControl.props.refreshing).toBe(false);
  });

  it('does not let an old refresh callback cancel a new actor refresh', async () => {
    const view = render(<QualityReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    await resolveStatistics(0);
    const oldRefresh = view.getByTestId('quality-report-scroll-view').props.refreshControl.props.onRefresh;

    currentUser = actor('F007', 2);
    await act(async () => view.rerender(<QualityReportScreen />));
    await waitFor(() => expect(statisticsRequests).toHaveLength(2));
    await resolveStatistics(1);

    const newRefreshStats = deferred<unknown>();
    mockGetStatistics.mockImplementationOnce((params: { startDate: string; endDate: string }) => {
      statisticsRequests.push({ params, deferred: newRefreshStats });
      return newRefreshStats.promise;
    });
    const newRefresh = view.getByTestId('quality-report-scroll-view').props.refreshControl.props.onRefresh;
    await act(async () => {
      void newRefresh();
    });
    await waitFor(() => expect(statisticsRequests).toHaveLength(3));
    expect(view.getByTestId('quality-report-scroll-view').props.refreshControl.props.refreshing).toBe(true);

    await act(async () => {
      void oldRefresh();
    });
    expect(view.getByTestId('quality-report-scroll-view').props.refreshControl.props.refreshing).toBe(true);

    await act(async () => {
      newRefreshStats.resolve({ success: true, data: { qualitySummary: summary() }, message: '' });
    });
    await waitFor(() => expect(view.getByTestId('quality-report-scroll-view').props.refreshControl.props.refreshing).toBe(false));
  });

  it('enforces finance/price permission and keeps anomaly failure explicit', async () => {
    currentUser = actor('F006', 1, '-');
    const view = render(<QualityReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    await resolveStatistics(0);
    await act(async () => fireEvent.press(view.getByText('异常检测')));
    expect(mockGetAnomalyReport).not.toHaveBeenCalled();
    expect(view.getByText('当前账号没有质量数据读取权限')).toBeTruthy();

    currentUser = actor('F006', 1, 'r');
    mockGetAnomalyReport.mockRejectedValueOnce(new Error('anomaly failed'));
    await act(async () => view.rerender(<QualityReportScreen />));
    await act(async () => fireEvent.press(view.getByText('异常检测')));
    await waitFor(() => expect(view.getByText('anomaly failed')).toBeTruthy());
  });
});
