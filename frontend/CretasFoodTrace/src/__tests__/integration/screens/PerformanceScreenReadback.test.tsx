import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { FlatList } from 'react-native';
import PerformanceScreen from '../../../screens/hr/analytics/PerformanceScreen';
import { hrApiClient } from '../../../services/api/hrApiClient';
import { hrShiftApiClient } from '../../../services/api/hrShiftApiClient';
import { useAuthStore } from '../../../store/authStore';

jest.mock('../../../services/api/hrApiClient', () => ({
  hrApiClient: { getPerformanceStats: jest.fn() },
}));
jest.mock('../../../services/api/hrShiftApiClient', () => ({
  hrShiftApiClient: { getSelfModuleAccess: jest.fn() },
}));
jest.mock('../../../store/authStore', () => {
  const state = { user: {
    id: 9, username: 'hr', email: 'hr@example.com', createdAt: '2026-01-01', updatedAt: '2026-01-01',
    isActive: true, factoryId: 'F006', userType: 'factory' as const,
    factoryUser: { role: 'hr_admin' as const, factoryId: 'F006', permissions: [], modulePermissions: { hr: 'rw' as const } },
  } };
  const useAuthStore = Object.assign((selector?: (value: typeof state) => unknown) => selector ? selector(state) : state, {
    getState: () => state,
  });
  return { useAuthStore };
});
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ dispatch: jest.fn(), goBack: jest.fn() }),
  useFocusEffect: (callback: () => void) => React.useEffect(callback, [callback]),
  CommonActions: { navigate: jest.fn() },
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

describe('PerformanceScreenReadback', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    const actor = useAuthStore.getState().user;
    if (actor) {
      actor.id = 9;
      actor.factoryId = 'F006';
    }
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mockResolvedValue([
      { moduleCode: 'hr_employee', permissionModule: 'hr', roleDefaultAllowed: true, effectiveAllowed: true },
    ]);
  });

  it('shows unknown values for an empty workers sample instead of fabricated scores', async () => {
    (hrApiClient.getPerformanceStats as jest.Mock).mockResolvedValue({
      avgScore: null,
      sampleSize: 0,
      period: 'month',
      excellentCount: 0,
      needAttentionCount: 0,
      needImprovementCount: 0,
      gradeDistribution: [],
      employees: [],
    });

    const view = render(<PerformanceScreen />);
    await waitFor(() => expect(hrApiClient.getPerformanceStats).toHaveBeenCalledWith({ period: 'month', factoryId: 'F006' }));

    expect(view.getAllByText('-').length).toBeGreaterThan(0);
    expect(view.queryByText('100')).toBeNull();
    expect(view.queryByText('85')).toBeNull();
    expect(view.queryByText('90')).toBeNull();
  });

  it('renders the returned worker sample and period without a second default-window request', async () => {
    (hrApiClient.getPerformanceStats as jest.Mock).mockResolvedValue({
      avgScore: 88,
      sampleSize: 1,
      period: 'quarter',
      excellentCount: 0,
      needAttentionCount: 0,
      needImprovementCount: 0,
      gradeDistribution: [{ grade: 'B', count: 1, percentage: 100 }],
      employees: [{ userId: 7, userName: '真实员工', score: 88, grade: 'B', efficiency: 80, attendance: 100 }],
    });

    const view = render(<PerformanceScreen />);
    await waitFor(() => expect(view.getByText('真实员工')).toBeTruthy());
    expect(hrApiClient.getPerformanceStats).toHaveBeenCalledTimes(1);
  });

  it('renders a measured zero as zero rather than unknown', async () => {
    (hrApiClient.getPerformanceStats as jest.Mock).mockResolvedValue({
      avgScore: 0,
      sampleSize: 1,
      period: 'month',
      excellentCount: 0,
      needAttentionCount: 0,
      needImprovementCount: 1,
      gradeDistribution: [{ grade: 'E', count: 1, percentage: 100 }],
      employees: [{ userId: 7, userName: '零分员工', score: 0, grade: 'E', efficiency: 0, attendance: 0 }],
    });
    const view = render(<PerformanceScreen />);
    await waitFor(() => expect(view.getByText('零分员工')).toBeTruthy());
    expect(view.getAllByText('0').length).toBeGreaterThan(0);
  });

  it('requests the selected quarter instead of the stale default month', async () => {
    (hrApiClient.getPerformanceStats as jest.Mock)
      .mockResolvedValueOnce({
        avgScore: 88, sampleSize: 1, period: 'month', excellentCount: 0, needAttentionCount: 0,
        needImprovementCount: 0, gradeDistribution: [], employees: [],
      })
      .mockResolvedValueOnce({
        avgScore: null, sampleSize: 0, period: 'quarter', excellentCount: 0, needAttentionCount: 0,
        needImprovementCount: 0, gradeDistribution: [], employees: [],
      });
    const view = render(<PerformanceScreen />);
    await waitFor(() => expect(hrApiClient.getPerformanceStats).toHaveBeenCalledWith({ period: 'month', factoryId: 'F006' }));
    fireEvent.press(view.getByText('analytics.performance.period.quarter'));
    await waitFor(() => expect(hrApiClient.getPerformanceStats).toHaveBeenLastCalledWith({ period: 'quarter', factoryId: 'F006' }));
  });

  it('shows only an error state when the performance read fails', async () => {
    (hrApiClient.getPerformanceStats as jest.Mock)
      .mockRejectedValueOnce(new Error('403 denied'))
      .mockImplementationOnce(() => new Promise(() => {}));
    const view = render(<PerformanceScreen />);
    await waitFor(() => expect(view.getByText('403 denied')).toBeTruthy());
    expect(view.queryByText('analytics.performance.stats.avgScore')).toBeNull();
    expect(view.queryByText('analytics.performance.empty')).toBeNull();
    const actor = useAuthStore.getState().user;
    if (actor) actor.id = 10;
    await act(async () => { view.rerender(<PerformanceScreen />); });
    expect(view.queryByText('403 denied')).toBeNull();
    expect(view.queryByText('common.retry')).toBeNull();
  });

  it('does not let a late quarter response replace the newer month owner', async () => {
    let resolveQuarter: ((value: unknown) => void) | undefined;
    const quarterResult = new Promise((resolve) => { resolveQuarter = resolve; });
    (hrApiClient.getPerformanceStats as jest.Mock)
      .mockResolvedValueOnce({ avgScore: 80, sampleSize: 1, period: 'month', excellentCount: 0, needAttentionCount: 0, needImprovementCount: 0, gradeDistribution: [], employees: [{ userId: 1, userName: '月度样本', score: 80, grade: 'B' }] })
      .mockImplementationOnce(() => quarterResult)
      .mockResolvedValueOnce({ avgScore: 70, sampleSize: 1, period: 'month', excellentCount: 0, needAttentionCount: 0, needImprovementCount: 0, gradeDistribution: [], employees: [{ userId: 2, userName: '最新月度样本', score: 70, grade: 'C' }] });
    const view = render(<PerformanceScreen />);
    await waitFor(() => expect(view.getByText('月度样本')).toBeTruthy());
    fireEvent.press(view.getByText('analytics.performance.period.quarter'));
    await waitFor(() => expect(hrApiClient.getPerformanceStats).toHaveBeenLastCalledWith({ period: 'quarter', factoryId: 'F006' }));
    fireEvent.press(view.getByText('analytics.performance.period.month'));
    await waitFor(() => expect(view.getByText('最新月度样本')).toBeTruthy());
    await act(async () => {
      resolveQuarter?.({ avgScore: 99, sampleSize: 1, period: 'quarter', excellentCount: 1, needAttentionCount: 0, needImprovementCount: 0, gradeDistribution: [], employees: [{ userId: 3, userName: '迟到季度样本', score: 99, grade: 'A' }] });
      await Promise.resolve();
    });
    expect(view.queryByText('迟到季度样本')).toBeNull();
    expect(view.getByText('最新月度样本')).toBeTruthy();
  });

  it('fails closed after canonical HR self access is denied without a worker GET', async () => {
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mockResolvedValue([
      { moduleCode: 'hr_employee', permissionModule: 'hr', roleDefaultAllowed: true, override: 'DENY', effectiveAllowed: false },
    ]);
    const view = render(<PerformanceScreen />);
    await waitFor(() => expect(view.getByText('analytics.performance.readFailed')).toBeTruthy());
    expect(hrApiClient.getPerformanceStats).not.toHaveBeenCalled();
  });

  it('does not issue the worker GET when the actor changes while self access is pending', async () => {
    let resolveAccess: ((value: unknown[]) => void) | undefined;
    const access = new Promise<unknown[]>((resolve) => { resolveAccess = resolve; });
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock)
      .mockImplementationOnce(() => access)
      .mockImplementationOnce(() => new Promise<unknown[]>(() => {}));
    const view = render(<PerformanceScreen />);
    await waitFor(() => expect(hrShiftApiClient.getSelfModuleAccess).toHaveBeenCalledTimes(1));
    await act(async () => {
      const liveActor = useAuthStore.getState().user;
      if (liveActor) liveActor.id = 10;
      view.rerender(<PerformanceScreen />);
      resolveAccess?.([{ moduleCode: 'hr_employee', permissionModule: 'hr', effectiveAllowed: true }]);
      await Promise.resolve();
    });
    expect(hrApiClient.getPerformanceStats).not.toHaveBeenCalled();
  });

  it('ignores a retained old-owner refresh while the new owner is still loading', async () => {
    (hrApiClient.getPerformanceStats as jest.Mock).mockResolvedValueOnce({
      avgScore: 88, sampleSize: 1, period: 'month', excellentCount: 0, needAttentionCount: 0,
      needImprovementCount: 0, gradeDistribution: [], employees: [{ userId: 7, userName: '旧样本', score: 88, grade: 'B' }],
    });
    let resolveNew: ((value: unknown) => void) | undefined;
    (hrApiClient.getPerformanceStats as jest.Mock).mockImplementationOnce(() => new Promise((resolve) => { resolveNew = resolve; }));
    const view = render(<PerformanceScreen />);
    await waitFor(() => expect(view.getByText('旧样本')).toBeTruthy());
    const oldRefresh = view.UNSAFE_getByType(FlatList).props.refreshControl.props.onRefresh;
    const actor = useAuthStore.getState().user;
    if (actor) actor.id = 10;
    await act(async () => { view.rerender(<PerformanceScreen />); });
    await waitFor(() => expect(hrApiClient.getPerformanceStats).toHaveBeenCalledTimes(2));
    oldRefresh();
    expect(hrApiClient.getPerformanceStats).toHaveBeenCalledTimes(2);
    expect(view.queryByText('旧样本')).toBeNull();
    await act(async () => {
      resolveNew?.({ avgScore: 70, sampleSize: 1, period: 'month', excellentCount: 0, needAttentionCount: 0, needImprovementCount: 0, gradeDistribution: [], employees: [] });
      await Promise.resolve();
    });
    await waitFor(() => expect(view.UNSAFE_getByType(FlatList).props.refreshControl.props.refreshing).toBe(false));
    await act(async () => { oldRefresh(); });
    expect(view.UNSAFE_getByType(FlatList).props.refreshControl.props.refreshing).toBe(false);
    expect(hrApiClient.getPerformanceStats).toHaveBeenCalledTimes(2);
  });
});
