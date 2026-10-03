import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';

import ProductionReportScreen from '../../../screens/reports/ProductionReportScreen';
import { useAuthStore } from '../../../store/authStore';
import { dashboardAPI } from '../../../services/api/dashboardApiClient';
import { processingApiClient } from '../../../services/api/processingApiClient';
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
    TextInput: component('TextInput'),
    ActivityIndicator: component('ActivityIndicator'),
    RefreshControl: component('RefreshControl'),
    StyleSheet: { create: (styles: unknown) => styles, flatten: (style: unknown) => style },
    Alert: { alert: jest.fn() },
  };
});

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => ({
      'production.today': '今日',
      'production.thisWeek': '本周',
      'production.thisMonth': '本月',
      'production.timeRange': '时间范围',
      'production.productionStats': '生产统计',
      'production.productionBatches': '生产批次',
      'production.outputByUnit': '按单位实际产量',
      'production.output': '产量',
      'production.completionRate': '完成率',
      'production.unknownRate': '未知',
      'production.missingActualQuantity': '缺少实际量',
      'production.missingUnit': '缺少单位',
      'production.unknownUnit': '未知单位',
      'production.recentBatches': '近期批次',
      'production.recentTen': '最近10条',
      'production.rollingSevenDays': '近7天',
      'production.capacityUtilization': '产能利用率分析',
      'production.averageUtilization': '平均利用率',
      'production.dailyCapacity': '日产能',
      'production.totalEquipment': '设备总数',
      'production.capacityBasis': '产能分母',
      'production.utilizationTarget': '利用率目标',
      'production.dailyUtilization': '每日利用率',
      'production.dailyOutput': '日产出',
      'production.equipmentUtilization': '设备利用率',
      'production.noDailyUtilization': '暂无每日利用率数据',
      'production.noEquipmentUtilization': '暂无设备利用率数据',
      'production.noReportedQuantity': '未填实际量',
      'production.unitMissing': '单位缺失',
      'production.statisticsRange': '统计范围',
      'production.noRealtimeData': '暂无实时监控数据',
      'production.noCapacityData': '暂无产能利用数据',
      'production.loadRecentBatchesFailed': '近期批次加载失败',
      'production.loadRealtimeFailed': '实时监控加载失败',
      'production.loadCapacityFailed': '产能利用加载失败',
      'production.permissionDenied': '当前账号没有生产报表读取权限',
      'production.retry': '重新加载',
      'production.status': '状态',
      'production.noStatsData': '暂无统计数据',
      'production.noBatchRecords': '暂无批次记录',
      'production.loadFailed': '加载失败',
      'production.loadProductionDataFailed': '加载生产数据失败，请稍后重试',
      'production.error': '错误',
      'production.cannotGetFactoryInfo': '无法获取工厂信息，请重新登录',
      'production.statusPlanned': '计划中',
      'production.statusPlanning': '计划中',
      'production.statusInProgress': '进行中',
      'production.statusProducing': '生产中',
      'production.statusPaused': '已暂停',
      'production.statusCancelled': '已取消',
      'production.statusCompleted': '已完成',
      'status.unknown': '未知',
      'production.batchNumber': '批次号',
      'production.product': '产品',
    }[key] ?? key),
  }),
}));

jest.mock('@react-native-community/datetimepicker', () => 'DateTimePicker');

jest.mock('react-native-safe-area-context', () => {
  const ReactRuntime = require('react');
  return {
    SafeAreaView: ({ children, ...props }: Record<string, unknown> & { children?: unknown }) =>
      ReactRuntime.createElement('View', props, children),
  };
});

const resolvedSafeAreaModule = require('react-native-safe-area-context') as {
  SafeAreaView?: React.ComponentType<Record<string, unknown>>;
};
if (!resolvedSafeAreaModule.SafeAreaView) {
  resolvedSafeAreaModule.SafeAreaView = ({ children, ...props }: Record<string, unknown> & { children?: unknown }) =>
    React.createElement('View', props, children as React.ReactNode);
}

jest.mock('../../../store/authStore', () => ({
  useAuthStore: jest.fn(),
}));

type TestUser = {
  id: number;
  username: string;
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

const mockUseAuthStore = useAuthStore as unknown as jest.Mock & {
  getState: () => { user: TestUser | null };
};
const mockGetUserModuleAccess = canvasApiClient.getUserModuleAccess as jest.Mock;

jest.mock('../../../services/api/dashboardApiClient', () => ({
  dashboardAPI: {
    getProductionStatistics: jest.fn(),
  },
}));

jest.mock('../../../services/api/processingApiClient', () => ({
  processingApiClient: {
    getBatches: jest.fn(),
  },
}));

jest.mock('../../../services/api/canvasApiClient', () => ({
  canvasApiClient: { getUserModuleAccess: jest.fn() },
}));

jest.mock('../../../services/api/reportApiClient', () => ({
  reportApiClient: {
    getRealtimeData: jest.fn(),
    getCapacityUtilizationReport: jest.fn(),
  },
}));

const mockGoBack = jest.fn();
const mockFocusEffects: Array<() => void | (() => void)> = [];
jest.mock('@react-navigation/native', () => {
  const ReactRuntime = require('react');
  return {
    useNavigation: () => ({ goBack: mockGoBack }),
    useFocusEffect: (effect: () => void | (() => void)) => {
      mockFocusEffects.push(effect);
      return ReactRuntime.useEffect(effect, [effect]);
    },
  };
});

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

function actor(factoryId: string, id: number, role = 'production_manager', canRead = true): TestUser {
  return {
    id,
    username: `${factoryId}-operator`,
    userType: 'factory',
    factoryId,
    role,
    factoryUser: {
      factoryId,
      role,
      permissions: [],
      modulePermissions: {
        dashboard: canRead ? 'r' : '-',
        production: canRead ? 'r' : '-',
        warehouse: '-',
        inventory: '-',
        quality: '-',
        finance: '-',
        production_plan: canRead ? 'r' : '-',
      },
    },
  };
}

function summary(period: string, startDate = '2026-10-04', endDate = '2026-10-04'): {
  period: string;
  startDate: string;
  endDate: string;
  totalBatches: number;
  completedBatches: number;
  completionRate: number | null;
  statusCounts: Record<string, number>;
  missingUnitCount: number;
  missingActualQuantityCount: number;
  unknownUnitCount: number;
  unitBreakdown: Array<Record<string, string | number>>;
} {
  return {
    period,
    startDate,
    endDate,
    totalBatches: 3,
    completedBatches: 1,
    completionRate: 33.33,
    statusCounts: { PLANNED: 1, IN_PROGRESS: 1, COMPLETED: 1 },
    missingUnitCount: 1,
    missingActualQuantityCount: 1,
    unknownUnitCount: 1,
    unitBreakdown: [
      { unit: 'kg', totalBatches: 2, reportedBatches: 1, missingActualQuantityBatches: 1, reportedQuantity: 0, totalQuantity: 0 },
      { unit: 'crate', totalBatches: 1, reportedBatches: 1, missingActualQuantityBatches: 0, reportedQuantity: 2, totalQuantity: 2 },
    ],
  };
}

const batchesSuccess = {
  success: true,
  data: {
    content: [
      { id: 1, batchNumber: 'B-1', productType: '牛肉', status: 'PLANNED', targetQuantity: 9, actualQuantity: 0, unit: 'kg', createdAt: '2026-10-04' },
      { id: 2, batchNumber: 'B-2', productType: '牛肉', status: 'IN_PROGRESS', targetQuantity: 3, actualQuantity: undefined, unit: undefined, createdAt: '2026-10-04' },
      { id: 3, batchNumber: 'B-3', productType: '牛肉', status: 'PAUSED', targetQuantity: 1, actualQuantity: 1, unit: undefined, createdAt: '2026-10-04' },
      { id: 4, batchNumber: 'B-4', productType: '牛肉', status: 'PLANNING', targetQuantity: 1, actualQuantity: 1, unit: 'kg', createdAt: '2026-10-04' },
      { id: 5, batchNumber: 'B-5', productType: '牛肉', status: 'PRODUCING', targetQuantity: 1, actualQuantity: 1, unit: 'kg', createdAt: '2026-10-04' },
      { id: 6, batchNumber: 'B-6', productType: '牛肉', status: 'CANCELLED', targetQuantity: 1, actualQuantity: 1, unit: 'kg', createdAt: '2026-10-04' },
      { id: 7, batchNumber: 'B-7', productType: '牛肉', status: 'COMPLETED', targetQuantity: 1, actualQuantity: 1, unit: 'kg', createdAt: '2026-10-04' },
    ],
  },
  message: '',
};

describe('ProductionReportScreen real statistics behavior', () => {
  let currentUser: TestUser;
  let statisticsRequests: Array<{ period: string; factoryId: string; deferred: Deferred<unknown> }>;

  beforeEach(() => {
    currentUser = actor('F006', 1);
    statisticsRequests = [];
    mockFocusEffects.length = 0;
    mockUseAuthStore.mockImplementation(() => ({ user: currentUser }));
    mockUseAuthStore.getState = () => ({ user: currentUser });
    mockGetUserModuleAccess.mockImplementation(async () => [{
      moduleCode: 'production_plan',
      effectiveAllowed: currentUser.factoryUser.modulePermissions.production_plan === 'r',
    }]);
    (processingApiClient.getBatches as jest.Mock).mockResolvedValue(batchesSuccess);
    (dashboardAPI.getProductionStatistics as jest.Mock).mockImplementation(
      ({ period }: { period: string }, factoryId: string) => {
        const response = deferred<unknown>();
        statisticsRequests.push({ period, factoryId, deferred: response });
        return response.promise;
      },
    );
  });

  afterEach(() => {
    jest.clearAllMocks();
  });

  async function resolveStatistics(index: number, value = summary(statisticsRequests[index]!.period)) {
    await act(async () => {
      statisticsRequests[index]!.deferred.resolve({ success: true, data: { productionSummary: value }, message: '' });
    });
  }

  it('renders real period clicks, the exact window, zero, NULL, raw units, and recent ten semantics', async () => {
    const view = render(<ProductionReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    expect(statisticsRequests[0]).toMatchObject({ period: 'week', factoryId: 'F006' });
    await resolveStatistics(0, summary('week', '2026-09-28', '2026-10-04'));

    expect(view.getByText('统计范围: 2026-09-28 - 2026-10-04')).toBeTruthy();
    expect(view.getByText('0.00 kg')).toBeTruthy();
    expect(view.getByText(/2\.00 crate/)).toBeTruthy();
    expect(view.getAllByText('未填实际量').length).toBeGreaterThan(0);
    expect(view.getByText(/单位缺失/)).toBeTruthy();
    expect(view.getByText('近期批次 · 最近10条')).toBeTruthy();
    expect(view.getAllByText('计划中').length).toBeGreaterThanOrEqual(2);
    expect(view.getByText('进行中')).toBeTruthy();
    expect(view.getByText('生产中')).toBeTruthy();
    expect(view.getByText('已暂停')).toBeTruthy();
    expect(view.getByText('已取消')).toBeTruthy();
    expect(view.getByText('已完成')).toBeTruthy();

    await act(async () => fireEvent.press(view.getByText('今日')));
    await waitFor(() => expect(statisticsRequests).toHaveLength(2));
    expect(statisticsRequests[1]).toMatchObject({ period: 'today', factoryId: 'F006' });
    await resolveStatistics(1, summary('today'));

    await act(async () => fireEvent.press(view.getByText('本月')));
    await waitFor(() => expect(statisticsRequests).toHaveLength(3));
    expect(statisticsRequests[2]).toMatchObject({ period: 'month', factoryId: 'F006' });
    await resolveStatistics(2, {
      ...summary('month'),
      totalBatches: 0,
      completedBatches: 0,
      completionRate: null,
      unitBreakdown: [],
    });
    expect(view.getByText('未知')).toBeTruthy();
  });

  it('clears the first frame on actor change and rejects old late data and old focus callbacks', async () => {
    const first = deferred<unknown>();
    (dashboardAPI.getProductionStatistics as jest.Mock).mockImplementationOnce(() => first.promise);
    const view = render(<ProductionReportScreen />);
    await waitFor(() => expect((dashboardAPI.getProductionStatistics as jest.Mock).mock.calls).toHaveLength(1));
    const callsBeforeActorChange = (dashboardAPI.getProductionStatistics as jest.Mock).mock.calls.length;

    currentUser = actor('F007', 2, 'warehouse_manager');
    await act(async () => view.rerender(<ProductionReportScreen />));
    expect(view.queryByText('统计范围: 2026-10-04 - 2026-10-04')).toBeNull();
    const callsAfterActorChange = (dashboardAPI.getProductionStatistics as jest.Mock).mock.calls.slice(callsBeforeActorChange);
    expect(callsAfterActorChange.some((call) => call[1] === 'F006')).toBe(false);
    const callsBeforeOldFocus = (dashboardAPI.getProductionStatistics as jest.Mock).mock.calls.length;
    await act(async () => {
      mockFocusEffects[0]?.();
    });
    expect((dashboardAPI.getProductionStatistics as jest.Mock).mock.calls.length).toBe(callsBeforeOldFocus);

    await act(async () => {
      first.resolve({ success: true, data: { productionSummary: summary('week') }, message: '' });
    });
    expect(view.queryByText('0.00 kg')).toBeNull();
    expect((dashboardAPI.getProductionStatistics as jest.Mock).mock.calls.slice(callsBeforeActorChange)
      .some((call) => call[1] === 'F006')).toBe(false);
  });

  it('does not unlock samples when a previous actor module-access response arrives late', async () => {
    const staleAccess = deferred<unknown>();
    const staleStatistics = deferred<unknown>();
    mockGetUserModuleAccess.mockImplementationOnce(() => staleAccess.promise);
    (dashboardAPI.getProductionStatistics as jest.Mock).mockImplementationOnce(() => staleStatistics.promise);

    const view = render(<ProductionReportScreen />);
    await waitFor(() => expect(mockGetUserModuleAccess).toHaveBeenCalledTimes(1));

    currentUser = actor('F007', 2, 'warehouse_manager');
    await act(async () => view.rerender(<ProductionReportScreen />));
    await act(async () => {
      staleAccess.resolve([{ moduleCode: 'production_plan', effectiveAllowed: true }]);
      staleStatistics.resolve({ success: true, data: { productionSummary: summary('week') }, message: '' });
    });

    expect(processingApiClient.getBatches).not.toHaveBeenCalled();
    expect(view.queryByText('0.00 kg')).toBeNull();
  });

  it('does not call after access revocation and separates statistics error from sample error', async () => {
    const view = render(<ProductionReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    await resolveStatistics(0);

    currentUser = actor('F006', 1, 'production_manager', false);
    await act(async () => view.rerender(<ProductionReportScreen />));
    expect(statisticsRequests).toHaveLength(1);
    expect(view.queryByText('0.00 kg')).toBeNull();

    currentUser = actor('F006', 1, 'production_manager', true);
    (processingApiClient.getBatches as jest.Mock).mockResolvedValueOnce({ success: false, data: null, message: '样本403' });
    await act(async () => view.rerender(<ProductionReportScreen />));
    await waitFor(() => expect(statisticsRequests).toHaveLength(2));
    await resolveStatistics(1);
    expect(view.getByText('样本403')).toBeTruthy();
    expect(view.getByText('统计范围: 2026-10-04 - 2026-10-04')).toBeTruthy();

    (dashboardAPI.getProductionStatistics as jest.Mock).mockResolvedValueOnce({ success: false, data: null, message: '统计失败' });
    await act(async () => fireEvent.press(view.getByText('今日')));
    await waitFor(() => expect(view.getByText('统计失败')).toBeTruthy());
    expect(view.queryByText('样本403')).toBeNull();
  });

  it('settles statistics and sample rejection independently and fail-closes sample access', async () => {
    (processingApiClient.getBatches as jest.Mock).mockRejectedValueOnce(new Error('sample network'));
    const view = render(<ProductionReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    await resolveStatistics(0);
    await waitFor(() => expect(view.getByText('sample network')).toBeTruthy());
    expect(view.getByText('统计范围: 2026-10-04 - 2026-10-04')).toBeTruthy();

    (dashboardAPI.getProductionStatistics as jest.Mock).mockRejectedValueOnce(new Error('stats network'));
    currentUser = actor('F006', 1, 'warehouse_manager', true);
    await act(async () => view.rerender(<ProductionReportScreen />));
    await waitFor(() => expect(dashboardAPI.getProductionStatistics).toHaveBeenCalledTimes(2));
    expect((dashboardAPI.getProductionStatistics as jest.Mock).mock.calls[1]).toMatchObject([{ period: 'week' }, 'F006']);
    await waitFor(() => expect(view.getByText('stats network')).toBeTruthy());
    expect(view.queryByText('sample network')).toBeNull();

    currentUser = actor('F006', 1, 'production_manager', true);
    currentUser.factoryUser.modulePermissions.production_plan = '-';
    await act(async () => view.rerender(<ProductionReportScreen />));
    await waitFor(() => expect(dashboardAPI.getProductionStatistics).toHaveBeenCalledTimes(3));
    expect(processingApiClient.getBatches).toHaveBeenCalledTimes(2);
  });

  it('renders nullable wire capacity fields and requests an inclusive seven-day window', async () => {
    const capacityResponse = {
      factoryId: 'F006',
      startDate: '2026-10-04',
      endDate: '2026-10-04',
      totalEquipment: 1,
      averageUtilization: null,
      utilizationTarget: null,
      dailyCapacity: null,
      capacityBasis: null,
      equipmentUtilization: [{ equipmentId: 1, equipmentName: '设备A', utilization: null, status: 'idle' }],
      dailyUtilization: [{ date: '2026-10-04', utilization: null, output: 0, dayOfWeek: 0, weekOfYear: 40 }],
    };
    currentUser.factoryUser.modulePermissions.finance = 'r';
    (reportApiClient.getCapacityUtilizationReport as jest.Mock).mockResolvedValue(capacityResponse);
    const view = render(<ProductionReportScreen />);
    await waitFor(() => expect(statisticsRequests).toHaveLength(1));
    await resolveStatistics(0);
    await act(async () => fireEvent.press(view.getByText('产能利用')));
    await waitFor(() => expect(reportApiClient.getCapacityUtilizationReport).toHaveBeenCalledTimes(1));
    const request = (reportApiClient.getCapacityUtilizationReport as jest.Mock).mock.calls[0]![0];
    const start = new Date(`${request.startDate}T00:00:00`);
    const end = new Date(`${request.endDate}T00:00:00`);
    expect(Math.round((end.getTime() - start.getTime()) / 86400000)).toBe(6);
    expect(view.getByText('平均利用率')).toBeTruthy();
    expect(view.getAllByText('未知').length).toBeGreaterThan(0);
    expect(view.queryByText('计划产能')).toBeNull();
    expect(view.queryByText('生产线产能详情')).toBeNull();
  });
});
