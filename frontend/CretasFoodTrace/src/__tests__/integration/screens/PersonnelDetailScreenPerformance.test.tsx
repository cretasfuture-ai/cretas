import React from 'react';
import { act, render, waitFor } from '@testing-library/react-native';
import { ScrollView, TouchableOpacity } from 'react-native';
import PersonnelDetailScreen from '../../../screens/dispatcher/personnel/PersonnelDetailScreen';
import { hrApiClient } from '../../../services/api/hrApiClient';
import { hrShiftApiClient } from '../../../services/api/hrShiftApiClient';
import { schedulingApiClient } from '../../../services/api/schedulingApiClient';

const mockActor = {
  id: 9,
  username: 'hr',
  email: 'hr@example.com',
  createdAt: '2026-01-01',
  updatedAt: '2026-01-01',
  isActive: true,
  factoryId: 'F006',
  userType: 'factory' as const,
  factoryUser: { role: 'hr_admin' as const, factoryId: 'F006', permissions: [], modulePermissions: { hr: 'rw' as 'rw' | '-' } },
};

jest.mock('../../../services/api/hrApiClient', () => ({
  hrApiClient: { getEmployeePerformance: jest.fn() },
}));
jest.mock('../../../services/api/schedulingApiClient', () => ({
  schedulingApiClient: { getEmployeeDetail: jest.fn() },
}));
jest.mock('../../../services/api/hrShiftApiClient', () => ({
  hrShiftApiClient: { getSelfModuleAccess: jest.fn() },
}));
jest.mock('expo-linear-gradient', () => ({
  LinearGradient: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));
jest.mock('../../../store/authStore', () => ({
  useAuthStore: Object.assign(() => ({ user: mockActor }), { getState: () => ({ user: mockActor }) }),
}));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: jest.fn(), dispatch: jest.fn() }),
  useRoute: () => ({ params: { personnelId: '42' } }),
  CommonActions: { navigate: jest.fn() },
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

describe('PersonnelDetailScreenPerformance', () => {
  beforeEach(() => {
    mockActor.id = 9;
    mockActor.factoryId = 'F006';
    mockActor.factoryUser.factoryId = 'F006';
    mockActor.factoryUser.modulePermissions = { hr: 'rw' as const };
    jest.clearAllMocks();
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mockResolvedValue([
      { moduleCode: 'hr_employee', permissionModule: 'hr', roleDefaultAllowed: true, effectiveAllowed: true },
    ]);
  });

  it('requests the real userId and keeps unavailable metrics unknown', async () => {
    (schedulingApiClient.getEmployeeDetail as jest.Mock).mockResolvedValue({
      success: true,
      data: { id: 42, fullName: '真实员工', status: 'IDLE', efficiency: null, skillLevels: {} },
    });
    (hrApiClient.getEmployeePerformance as jest.Mock).mockResolvedValue({
      userId: 42,
      userName: '真实员工',
      score: null,
      grade: null,
      efficiency: null,
      attendance: null,
    });
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mockResolvedValue([
      { moduleCode: 'hr_employee', permissionModule: 'hr', roleDefaultAllowed: true, effectiveAllowed: true },
    ]);

    const view = render(<PersonnelDetailScreen />);
    await waitFor(() => expect(hrApiClient.getEmployeePerformance).toHaveBeenCalledWith(42, { factoryId: 'F006' }));

    expect(view.getByText('真实员工')).toBeTruthy();
    expect(view.getAllByText('未知').length).toBeGreaterThan(0);
    expect(view.queryByText('95%')).toBeNull();
    expect(view.queryByText('90%')).toBeNull();
    expect(view.queryByText('85%')).toBeNull();
  });

  it('keeps the real profile when the separate performance read fails', async () => {
    (schedulingApiClient.getEmployeeDetail as jest.Mock).mockResolvedValue({
      success: true,
      data: { id: 42, fullName: '真实员工', status: 'IDLE', skillLevels: {} },
    });
    (hrApiClient.getEmployeePerformance as jest.Mock).mockRejectedValue(new Error('performance unavailable'));

    const view = render(<PersonnelDetailScreen />);
    await waitFor(() => expect(view.getByText('真实员工')).toBeTruthy());
    expect(view.getByText('performance unavailable')).toBeTruthy();
    expect(view.getAllByText('未知').length).toBeGreaterThan(0);
  });

  it('does not issue an HR performance GET without canonical HR read and keeps the detail profile', async () => {
    mockActor.factoryUser.modulePermissions = { hr: '-' as const };
    (schedulingApiClient.getEmployeeDetail as jest.Mock).mockResolvedValue({
      success: true,
      data: { id: 42, fullName: '真实员工', status: 'IDLE', skillLevels: {} },
    });

    const view = render(<PersonnelDetailScreen />);
    await waitFor(() => expect(view.getByText('真实员工')).toBeTruthy());
    expect(hrApiClient.getEmployeePerformance).not.toHaveBeenCalled();
    expect(view.getByText('无权读取员工绩效')).toBeTruthy();
    expect(view.getAllByText('未知').length).toBeGreaterThan(0);
  });

  it('does not fabricate a profile when the personnel detail read fails', async () => {
    (schedulingApiClient.getEmployeeDetail as jest.Mock).mockResolvedValue({ success: false, message: '404 denied' });
    const view = render(<PersonnelDetailScreen />);
    await waitFor(() => expect(view.getByText('未找到该员工信息')).toBeTruthy());
    expect(view.queryByText('王五行')).toBeNull();
    expect(hrApiClient.getEmployeePerformance).not.toHaveBeenCalled();
  });

  it('ignores a late old-owner detail response after the actor and factory change', async () => {
    let resolveOld: ((value: unknown) => void) | undefined;
    const oldDetail = new Promise((resolve) => { resolveOld = resolve; });
    (schedulingApiClient.getEmployeeDetail as jest.Mock)
      .mockImplementationOnce(() => oldDetail)
      .mockResolvedValueOnce({ success: true, data: { id: 42, fullName: '新工厂员工', status: 'IDLE', skillLevels: {} } });
    const view = render(<PersonnelDetailScreen />);
    await waitFor(() => expect(schedulingApiClient.getEmployeeDetail).toHaveBeenCalledTimes(1));
    await act(async () => {
      mockActor.id = 10;
      mockActor.factoryId = 'F007';
      mockActor.factoryUser.factoryId = 'F007';
      view.rerender(<PersonnelDetailScreen />);
    });
    await waitFor(() => expect(schedulingApiClient.getEmployeeDetail).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(view.getByText('新工厂员工')).toBeTruthy());
    await act(async () => {
      resolveOld?.({ success: true, data: { id: 42, fullName: '旧工厂员工', status: 'IDLE', skillLevels: {} } });
      await Promise.resolve();
    });
    expect(view.queryByText('旧工厂员工')).toBeNull();
    expect(view.getByText('新工厂员工')).toBeTruthy();
  });

  it('ignores a late detail response when only the same-factory actor id changes in place', async () => {
    let resolveOld: ((value: unknown) => void) | undefined;
    const oldDetail = new Promise((resolve) => { resolveOld = resolve; });
    (schedulingApiClient.getEmployeeDetail as jest.Mock)
      .mockImplementationOnce(() => oldDetail)
      .mockResolvedValueOnce({ success: true, data: { id: 42, fullName: '同厂新操作者', status: 'IDLE', skillLevels: {} } });
    const view = render(<PersonnelDetailScreen />);
    await waitFor(() => expect(schedulingApiClient.getEmployeeDetail).toHaveBeenCalledTimes(1));
    await act(async () => {
      mockActor.id = 10;
      view.rerender(<PersonnelDetailScreen />);
    });
    await waitFor(() => expect(schedulingApiClient.getEmployeeDetail).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(view.getByText('同厂新操作者')).toBeTruthy());
    await act(async () => {
      resolveOld?.({ success: true, data: { id: 42, fullName: '旧操作者资料', status: 'IDLE', skillLevels: {} } });
      await Promise.resolve();
    });
    expect(view.queryByText('旧操作者资料')).toBeNull();
    expect(view.getByText('同厂新操作者')).toBeTruthy();
  });

  it('ignores a late performance response after a same-factory actor id change', async () => {
    let resolveOld: ((value: unknown) => void) | undefined;
    const oldPerformance = new Promise((resolve) => { resolveOld = resolve; });
    (schedulingApiClient.getEmployeeDetail as jest.Mock).mockResolvedValue({
      success: true, data: { id: 42, fullName: '当前员工', status: 'IDLE', skillLevels: {} },
    });
    (hrApiClient.getEmployeePerformance as jest.Mock)
      .mockImplementationOnce(() => oldPerformance)
      .mockResolvedValueOnce({ userId: 42, userName: '当前员工', score: 40, grade: 'E', efficiency: 40, attendance: 40 });
    const view = render(<PersonnelDetailScreen />);
    await waitFor(() => expect(hrApiClient.getEmployeePerformance).toHaveBeenCalledTimes(1));
    await act(async () => {
      mockActor.id = 10;
      view.rerender(<PersonnelDetailScreen />);
    });
    await waitFor(() => expect(hrApiClient.getEmployeePerformance).toHaveBeenCalledTimes(2));
    await act(async () => {
      resolveOld?.({ userId: 42, userName: '旧绩效', score: 99, grade: 'A', efficiency: 99, attendance: 99 });
      await Promise.resolve();
    });
    expect(view.queryByText('旧绩效')).toBeNull();
    expect(view.getByText('当前员工')).toBeTruthy();
  });

  it('ignores a retained old-owner refresh while the new personnel owner is loading', async () => {
    (schedulingApiClient.getEmployeeDetail as jest.Mock).mockResolvedValueOnce({
      success: true, data: { id: 42, fullName: '旧人员', status: 'IDLE', skillLevels: {} },
    });
    let resolveNew: ((value: unknown) => void) | undefined;
    (schedulingApiClient.getEmployeeDetail as jest.Mock).mockImplementationOnce(() => new Promise((resolve) => { resolveNew = resolve; }));
    const view = render(<PersonnelDetailScreen />);
    await waitFor(() => expect(view.getByText('旧人员')).toBeTruthy());
    const oldRefresh = view.UNSAFE_getByType(ScrollView).props.refreshControl.props.onRefresh;
    mockActor.id = 10;
    await act(async () => { view.rerender(<PersonnelDetailScreen />); });
    await waitFor(() => expect(schedulingApiClient.getEmployeeDetail).toHaveBeenCalledTimes(2));
    oldRefresh();
    expect(schedulingApiClient.getEmployeeDetail).toHaveBeenCalledTimes(2);
    expect(view.queryByText('旧人员')).toBeNull();
    await act(async () => {
      resolveNew?.({ success: true, data: { id: 42, fullName: '新人员', status: 'IDLE', skillLevels: {} } });
      await Promise.resolve();
    });
    await waitFor(() => expect(view.UNSAFE_getByType(ScrollView).props.refreshControl.props.refreshing).toBe(false));
    await act(async () => { oldRefresh(); });
    expect(view.UNSAFE_getByType(ScrollView).props.refreshControl.props.refreshing).toBe(false);
    expect(schedulingApiClient.getEmployeeDetail).toHaveBeenCalledTimes(2);
  });

  it.each(['resolve', 'reject'])('keeps the newer profile when an older same-owner performance request settles: %s', async (settlement) => {
    let resolveOld: ((value: unknown) => void) | undefined;
    let rejectOld: ((reason: Error) => void) | undefined;
    (schedulingApiClient.getEmployeeDetail as jest.Mock)
      .mockResolvedValueOnce({ success: true, data: { id: 42, fullName: 'old profile', status: 'IDLE', skillLevels: {} } })
      .mockResolvedValueOnce({ success: true, data: { id: 42, fullName: 'new profile', status: 'IDLE', skillLevels: {} } });
    (hrApiClient.getEmployeePerformance as jest.Mock)
      .mockImplementationOnce(() => new Promise((resolve, reject) => { resolveOld = resolve; rejectOld = reject; }))
      .mockResolvedValueOnce({ userId: 42, efficiency: 40, attendance: 40, score: 40, grade: 'E' });
    const view = render(<PersonnelDetailScreen />);
    await waitFor(() => expect(hrApiClient.getEmployeePerformance).toHaveBeenCalledTimes(1));
    await act(async () => { view.UNSAFE_getByType(ScrollView).props.refreshControl.props.onRefresh(); });
    await waitFor(() => expect(hrApiClient.getEmployeePerformance).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(view.getByText('new profile')).toBeTruthy());
    await act(async () => {
      if (settlement === 'resolve') resolveOld?.({ userId: 42, efficiency: 99, attendance: 99, score: 99, grade: 'A' });
      else rejectOld?.(new Error('old performance failed'));
      await Promise.resolve();
    });
    expect(view.getByText('new profile')).toBeTruthy();
    expect(view.queryByText('old profile')).toBeNull();
    expect(view.queryByText('old performance failed')).toBeNull();
    expect(view.UNSAFE_getByType(ScrollView).props.refreshControl.props.refreshing).toBe(false);
  });

  it('keeps the new actor performance error when a retained old retry runs', async () => {
    (schedulingApiClient.getEmployeeDetail as jest.Mock).mockResolvedValue({
      success: true, data: { id: 42, fullName: '当前员工', status: 'IDLE', skillLevels: {} },
    });
    (hrApiClient.getEmployeePerformance as jest.Mock)
      .mockRejectedValueOnce(new Error('old performance failed'))
      .mockRejectedValueOnce(new Error('new performance failed'));
    const view = render(<PersonnelDetailScreen />);
    await waitFor(() => expect(view.getByText('old performance failed')).toBeTruthy());
    const oldRetry = view.getByText('common.retry').parent?.parent?.props.onPress;
    expect(typeof oldRetry).toBe('function');
    mockActor.id = 10;
    await act(async () => { view.rerender(<PersonnelDetailScreen />); });
    await waitFor(() => expect(view.getByText('new performance failed')).toBeTruthy());
    await act(async () => { oldRetry(); });
    expect(view.getByText('new performance failed')).toBeTruthy();
    expect(schedulingApiClient.getEmployeeDetail).toHaveBeenCalledTimes(2);
    expect(hrApiClient.getEmployeePerformance).toHaveBeenCalledTimes(2);
  });
});
