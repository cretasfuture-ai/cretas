import React from 'react';
import { ScrollView } from 'react-native';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';
import LaborCostScreen from '../../../screens/hr/analytics/LaborCostScreen';
import { useAuthStore } from '../../../store/authStore';
import { timeStatsApiClient } from '../../../services/api/timeStatsApiClient';
import wageApiClient from '../../../services/api/wageApiClient';
import { hrShiftApiClient, type SelfModuleAccess } from '../../../services/api/hrShiftApiClient';
import { periodDates } from '../../../utils/laborCostData';
import type { FactoryUser, ModulePermissions, User } from '../../../types/auth';

let mockFocusEffect: (() => void | (() => void)) | undefined;
let mockBlurCleanup: (() => void) | undefined;
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: jest.fn(), dispatch: jest.fn() }),
  useFocusEffect: (effect: () => void | (() => void)) => React.useEffect(() => {
    mockFocusEffect = effect;
    const dispose = effect();
    mockBlurCleanup = typeof dispose === 'function' ? dispose : undefined;
    return () => { if (typeof dispose === 'function') dispose(); };
  }, [effect]),
  CommonActions: { navigate: jest.fn() },
}));
jest.mock('react-native-safe-area-context', () => ({ SafeAreaView: ({ children }: { children: React.ReactNode }) => <>{children}</> }));
jest.mock('../../../services/api/timeStatsApiClient', () => ({ timeStatsApiClient: {
  getLaborCostSummary: jest.fn(), getCostByDepartment: jest.fn(), getWorkerHoursRank: jest.fn(),
} }));
jest.mock('../../../services/api/wageApiClient', () => ({ __esModule: true, default: { analyzeLaborCost: jest.fn() } }));
jest.mock('../../../services/api/hrShiftApiClient', () => {
  const client = { getSelfModuleAccess: jest.fn() };
  return { __esModule: true, hrShiftApiClient: client, default: client };
});
const actor = (factoryId: string, modulePermissions: ModulePermissions = { hr: 'r' }): FactoryUser => ({
  id: 11, username: 'test-hr', email: '', createdAt: '', updatedAt: '', isActive: true,
  userType: 'factory', factoryId,
  factoryUser: { factoryId, role: 'hr_admin', position: 'hr_admin', permissions: [], modulePermissions },
});
const summary = (minutes: number | null) => ({ totalCost: null, totalWorkMinutes: minutes,
  avgHourlyRate: null, participatingBatches: null, participatingEmployees: 0 });
const wage = (totalWage: number | null) => ({ totalWage, totalPieceRateWage: null,
  totalPieceCount: 0, workerCount: 0, costPerPiece: null, averageEfficiency: null,
  avgPiecesPerWorker: null, avgWagePerWorker: null, byEfficiencyRating: {}, byProcessStage: {} });
const allowed: SelfModuleAccess[] = [{ moduleCode: 'hr_employee', permissionModule: 'hr', roleDefaultAllowed: true, effectiveAllowed: true }];
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const reads = () => [hrShiftApiClient.getSelfModuleAccess, timeStatsApiClient.getLaborCostSummary,
  timeStatsApiClient.getCostByDepartment, timeStatsApiClient.getWorkerHoursRank, wageApiClient.analyzeLaborCost];
const counts = () => reads().map((read) => (read as jest.Mock).mock.calls.length);
const refresh = (screen: ReturnType<typeof render>): (() => void) => screen.UNSAFE_getByType(ScrollView).props.refreshControl.props.onRefresh;

beforeEach(() => {
  jest.resetAllMocks();
  mockFocusEffect = undefined; mockBlurCleanup = undefined;
  jest.mocked(hrShiftApiClient.getSelfModuleAccess).mockResolvedValue(allowed);
  jest.mocked(timeStatsApiClient.getLaborCostSummary).mockResolvedValue(summary(0));
  jest.mocked(timeStatsApiClient.getCostByDepartment).mockResolvedValue([]);
  jest.mocked(timeStatsApiClient.getWorkerHoursRank).mockResolvedValue([]);
  jest.mocked(wageApiClient.analyzeLaborCost).mockResolvedValue(wage(null));
  useAuthStore.setState({ user: actor('F006'), isAuthenticated: true });
});
afterEach(() => { cleanup(); useAuthStore.setState({ user: null, isAuthenticated: false }); });

describe('LaborCostScreen real request ownership', () => {
  it('renders measured zero hours and precisely unknown masked total wage', async () => {
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(screen.getByText('0h 0m')).toBeTruthy());
    expect(hrShiftApiClient.getSelfModuleAccess).toHaveBeenCalledWith(11, 'F006');
    expect(screen.getByTestId('labor-cost-total-wage').props.children).toBe('—');
    expect(screen.queryByTestId('labor-cost-loading')).toBeNull();
  });
  it('renders authorized measured wage zero in the total wage field', async () => {
    jest.mocked(wageApiClient.analyzeLaborCost).mockResolvedValue(wage(0));
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(screen.getByTestId('labor-cost-total-wage').props.children).toBe('¥0'));
  });
  it('self DENY blocks all four business reads', async () => {
    jest.mocked(hrShiftApiClient.getSelfModuleAccess).mockResolvedValue([{ ...allowed[0]!, effectiveAllowed: false }]);
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(screen.getByText('当前账号没有员工数据读取权限')).toBeTruthy());
    expect(counts()).toEqual([1, 0, 0, 0, 0]);
    expect(screen.queryByText('暂无数据')).toBeNull();
  });
  it('canonical DENY cannot be widened by a self GRANT', async () => {
    useAuthStore.setState({ user: actor('F006', { hr: '-' }) });
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(screen.getByText('当前账号没有人事读取权限')).toBeTruthy());
    expect(counts()).toEqual([0, 0, 0, 0, 0]);
  });
  it.each(['logout', 'missing factory'])('ends loading safely for %s without reads', async (kind) => {
    const missingFactory = actor('F006'); delete missingFactory.factoryId;
    useAuthStore.setState({ user: kind === 'logout' ? null : missingFactory });
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
    expect(screen.queryByTestId('labor-cost-loading')).toBeNull();
    expect(counts()).toEqual([0, 0, 0, 0, 0]);
  });
  it('old refresh changes neither calls nor UI after the new owner finishes', async () => {
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(screen.getByText('0h 0m')).toBeTruthy());
    const oldRefresh = refresh(screen);
    jest.mocked(timeStatsApiClient.getLaborCostSummary).mockResolvedValue(summary(60));
    await act(async () => { useAuthStore.setState({ user: actor('F007') }); });
    await waitFor(() => expect(screen.getByText('1h 0m')).toBeTruthy());
    const before = counts();
    await act(async () => { oldRefresh(); });
    expect(counts()).toEqual(before);
    expect(screen.getByText('1h 0m')).toBeTruthy();
    expect(screen.queryByTestId('labor-cost-loading')).toBeNull();
  });
  it('drops self success after in-place canonical permission revocation in the same factory', async () => {
    const oldSelf = deferred<SelfModuleAccess[]>();
    jest.mocked(hrShiftApiClient.getSelfModuleAccess).mockReturnValueOnce(oldSelf.promise);
    const current = actor('F006'); useAuthStore.setState({ user: current });
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(hrShiftApiClient.getSelfModuleAccess).toHaveBeenCalledTimes(1));
    await act(async () => { current.factoryUser.modulePermissions = { hr: '-' }; useAuthStore.setState({ user: current }); });
    await waitFor(() => expect(screen.getByText('当前账号没有人事读取权限')).toBeTruthy());
    await act(async () => { oldSelf.resolve(allowed); });
    expect(counts()).toEqual([1, 0, 0, 0, 0]);
  });
  it('rebuilds ownership on userType change even when actor ID and factory remain the same', async () => {
    const old = deferred<ReturnType<typeof summary>>();
    jest.mocked(timeStatsApiClient.getLaborCostSummary).mockReturnValueOnce(old.promise).mockResolvedValue(summary(60));
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(timeStatsApiClient.getLaborCostSummary).toHaveBeenCalledTimes(1));
    const platform: User = { id: 11, username: 'test-platform', email: '', createdAt: '', updatedAt: '', isActive: true,
      userType: 'platform', factoryId: 'F006', platformUser: { role: 'platform_admin', permissions: [] } };
    await act(async () => { useAuthStore.setState({ user: platform }); });
    await waitFor(() => expect(screen.getByText('1h 0m')).toBeTruthy());
    await act(async () => { old.resolve(summary(999)); });
    expect(screen.queryByText('16h 39m')).toBeNull();
    expect(screen.getByText('1h 0m')).toBeTruthy();
  });
  it.each(['resolve', 'reject'])('keeps the newer success when older same-owner self reads %s late', async (kind) => {
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(screen.getByText('0h 0m')).toBeTruthy());
    const old = deferred<SelfModuleAccess[]>();
    jest.mocked(hrShiftApiClient.getSelfModuleAccess).mockReturnValueOnce(old.promise).mockResolvedValueOnce(allowed);
    jest.mocked(timeStatsApiClient.getLaborCostSummary).mockResolvedValue(summary(180));
    const onRefresh = refresh(screen);
    await act(async () => { onRefresh(); onRefresh(); });
    await waitFor(() => expect(screen.getByText('3h 0m')).toBeTruthy());
    const before = counts();
    await act(async () => { if (kind === 'resolve') old.resolve(allowed); else old.reject(new Error('old-self-failed')); });
    expect(counts()).toEqual(before);
    expect(screen.getByText('3h 0m')).toBeTruthy();
    expect(screen.queryByText('old-self-failed')).toBeNull();
  });
  it.each(['resolve', 'reject'])('does not revive pre-blur self reads after refocus: %s', async (kind) => {
    const old = deferred<SelfModuleAccess[]>();
    jest.mocked(hrShiftApiClient.getSelfModuleAccess).mockReturnValueOnce(old.promise).mockResolvedValueOnce(allowed);
    jest.mocked(timeStatsApiClient.getLaborCostSummary).mockResolvedValue(summary(180));
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(hrShiftApiClient.getSelfModuleAccess).toHaveBeenCalledTimes(1));
    await act(async () => { mockBlurCleanup?.(); const dispose = mockFocusEffect?.(); mockBlurCleanup = typeof dispose === 'function' ? dispose : undefined; });
    await waitFor(() => expect(screen.getByText('3h 0m')).toBeTruthy());
    const before = counts();
    await act(async () => { if (kind === 'resolve') old.resolve(allowed); else old.reject(new Error('old-focus-failed')); });
    expect(counts()).toEqual(before);
    expect(screen.getByText('3h 0m')).toBeTruthy();
    expect(screen.queryByTestId('labor-cost-loading')).toBeNull();
  });
  it('clears the previous error on the first frame of a new factory', async () => {
    jest.mocked(timeStatsApiClient.getLaborCostSummary).mockRejectedValueOnce(new Error('old-factory-error'));
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(screen.getByText('old-factory-error')).toBeTruthy());
    const pending = deferred<SelfModuleAccess[]>();
    jest.mocked(hrShiftApiClient.getSelfModuleAccess).mockReturnValueOnce(pending.promise);
    await act(async () => { useAuthStore.setState({ user: actor('F007') }); });
    expect(screen.queryByText('old-factory-error')).toBeNull();
    expect(screen.getByTestId('labor-cost-loading')).toBeTruthy();
    await act(async () => { pending.resolve(allowed); });
  });
  it('retry keeps exclusive loading while business reads are pending instead of rendering fake empty data', async () => {
    jest.mocked(timeStatsApiClient.getLaborCostSummary).mockRejectedValueOnce(new Error('503 unavailable'));
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(screen.getByText('503 unavailable')).toBeTruthy());
    expect(screen.queryByText('暂无数据')).toBeNull();
    const pending = deferred<ReturnType<typeof summary>>();
    jest.mocked(timeStatsApiClient.getLaborCostSummary).mockReturnValueOnce(pending.promise);
    await act(async () => { fireEvent.press(screen.getByText('重试')); });
    expect(screen.getByTestId('labor-cost-loading')).toBeTruthy();
    expect(screen.queryByText('暂无数据')).toBeNull();
    expect(screen.queryByText('0h 0m')).toBeNull();
    await act(async () => { pending.resolve(summary(60)); });
    expect(screen.getByText('1h 0m')).toBeTruthy();
  });
  it('uses the clicked calendar week identically for all four business requests', async () => {
    const screen = render(<LaborCostScreen />);
    await waitFor(() => expect(screen.getByText('0h 0m')).toBeTruthy());
    await act(async () => { fireEvent.press(screen.getByText('本周')); });
    await waitFor(() => expect(timeStatsApiClient.getLaborCostSummary).toHaveBeenCalledTimes(2));
    const window = periodDates('week');
    for (const read of [timeStatsApiClient.getLaborCostSummary, timeStatsApiClient.getCostByDepartment, timeStatsApiClient.getWorkerHoursRank]) {
      expect(read).toHaveBeenLastCalledWith(expect.objectContaining({ period: 'week', factoryId: 'F006', ...window }));
    }
    expect(wageApiClient.analyzeLaborCost).toHaveBeenLastCalledWith(window.startDate, window.endDate, 'F006');
  });
});