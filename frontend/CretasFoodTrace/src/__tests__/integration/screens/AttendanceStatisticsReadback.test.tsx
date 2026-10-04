import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import AttendanceStatisticsScreen from '../../../screens/attendance/AttendanceStatisticsScreen';
import { timeStatsApiClient } from '../../../services/api/timeStatsApiClient';

jest.mock('../../../services/api/timeStatsApiClient', () => ({ timeStatsApiClient: {
  getEmployeeTimeStats: jest.fn(),
  getDepartmentTimeStats: jest.fn(),
  getDailyStats: jest.fn(),
  getWeeklyStats: jest.fn(),
  getMonthlyStats: jest.fn(),
} }));

const mockActorState = { user: {
  id: 9,
  userType: 'factory' as const,
  factoryId: 'F006',
  factoryUser: { role: 'department_admin', department: 'processing', factoryId: 'F006', modulePermissions: { hr: 'rw' } },
} };
type LoggerGlobal = typeof globalThis & { __attendanceStatsLoggerCalls?: unknown[][] };
jest.mock('../../../store/authStore', () => ({
  useAuthStore: Object.assign((selector?: (state: typeof mockActorState) => unknown) => selector ? selector(mockActorState) : mockActorState, {
    getState: () => mockActorState,
  }),
}));
jest.mock('../../../utils/permissionHelper', () => ({
  hasModulePermission: (user: { factoryUser?: { modulePermissions?: Record<string, string> } } | null, module: string, action: string) => {
    const level = user?.factoryUser?.modulePermissions?.[module];
    return action === 'read' && (level === 'r' || level === 'rw');
  },
}));

jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ goBack: jest.fn() }) }));
jest.mock('../../../utils/logger', () => ({
  logger: {
    createContextLogger: () => ({
      error: (...args: unknown[]) => {
        const scope = globalThis as LoggerGlobal;
        (scope.__attendanceStatsLoggerCalls ??= []).push(args);
      },
      info: jest.fn(),
    }),
  },
}));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
jest.mock('react-native-paper', () => {
  const Native = require('react-native') as typeof import('react-native');
  const Box = ({ children, ...props }: { children?: React.ReactNode; [key: string]: unknown }) => <Native.View {...props}>{children}</Native.View>;
  const Button = ({ children, onPress, ...props }: { children?: React.ReactNode; onPress?: () => void; [key: string]: unknown }) => <Native.TouchableOpacity {...props} onPress={onPress}>{children}</Native.TouchableOpacity>;
  const SegmentedButtons = ({ buttons, onValueChange }: { buttons: Array<{ value: string; label: string; disabled?: boolean }>; onValueChange: (value: string) => void }) => (
    <Native.View>{buttons.map((button) => <Native.TouchableOpacity key={button.value} disabled={button.disabled} onPress={() => onValueChange(button.value)}><Native.Text>{button.label}</Native.Text></Native.TouchableOpacity>)}</Native.View>
  );
  return {
    Text: Native.Text,
    Appbar: { Header: Box, BackAction: Button, Content: Box, Action: Button },
    Card: Object.assign(Box, { Content: Box }),
    List: { Icon: Box },
    ActivityIndicator: Box,
    Button,
    SegmentedButtons,
    Divider: Box,
    ProgressBar: Box,
  };
});

const employeeStats = (totalHours: number) => ({
  success: true,
  data: {
    employeeId: 9,
    employeeName: '真实账号',
    totalHours,
    regularHours: totalHours,
    overtimeHours: 0,
    attendanceDays: totalHours === 0 ? 0 : 1,
  },
});

const departmentStats = (totalHours: number) => ({
  success: true,
  data: { departmentStats: { processing: { departmentName: 'processing', totalHours, overtimeHours: 2, averageHours: 6 } } },
});
const factoryStats = (totalHours: number) => ({
  success: true,
  data: { totalHours, regularHours: totalHours, overtimeHours: 0, averageHours: totalHours },
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

beforeEach(() => {
  jest.clearAllMocks();
  (globalThis as LoggerGlobal).__attendanceStatsLoggerCalls = [];
  mockActorState.user = {
    ...mockActorState.user,
    id: 9,
    factoryId: 'F006',
    factoryUser: { ...mockActorState.user.factoryUser, role: 'department_admin', factoryId: 'F006', department: 'processing', modulePermissions: { hr: 'rw' } },
  };
});

describe('AttendanceStatisticsScreen real readback', () => {
  it('keeps a successful measured zero and does not render demo values', async () => {
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockResolvedValue(employeeStats(0) as never);
    const view = render(<AttendanceStatisticsScreen />);

    await waitFor(() => expect(view.getAllByText('0.0').length).toBeGreaterThan(0));
    expect(view.queryByText('176.0')).toBeNull();
    expect(view.queryByTestId('attendance-statistics-error')).toBeNull();
  });

  it('shows an explicit error and retries without inventing zero data', async () => {
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats)
      .mockRejectedValueOnce(new Error('503 unavailable'))
      .mockResolvedValueOnce(employeeStats(0) as never);
    const view = render(<AttendanceStatisticsScreen />);

    await waitFor(() => expect(view.getByTestId('attendance-statistics-error')).toBeTruthy());
    expect(view.queryByText('176.0')).toBeNull();
    await act(async () => { fireEvent.press(view.getByTestId('attendance-statistics-retry')); });
    await waitFor(() => expect(view.queryByTestId('attendance-statistics-error')).toBeNull());
    expect(view.getAllByText('0.0').length).toBeGreaterThan(0);
  });

  it('reads the selected department from the backend departmentStats map', async () => {
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockResolvedValue(employeeStats(0) as never);
    jest.mocked(timeStatsApiClient.getDepartmentTimeStats).mockResolvedValue(departmentStats(12) as never);
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getAllByText('0.0').length).toBeGreaterThan(0));

    fireEvent.press(view.getByText('attendance.statistics.dimensions.department'));
    await waitFor(() => expect(timeStatsApiClient.getDepartmentTimeStats).toHaveBeenCalledWith(
      'processing', expect.objectContaining({ startDate: expect.any(String), endDate: expect.any(String) }), 'F006',
    ));
    expect(view.getByText('12.0')).toBeTruthy();
    expect(view.getByText('10.0')).toBeTruthy();
    expect(view.getByText('2.0')).toBeTruthy();
    expect(view.getByText('6.0')).toBeTruthy();
  });

  it.each(['reject', 'false-success', 'malformed'])('shows a department read failure for %s instead of zeroes', async (label) => {
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockResolvedValue(employeeStats(0) as never);
    if (label === 'reject') {
      jest.mocked(timeStatsApiClient.getDepartmentTimeStats).mockRejectedValueOnce(new Error('department unavailable'));
    } else if (label === 'false-success') {
      jest.mocked(timeStatsApiClient.getDepartmentTimeStats).mockResolvedValueOnce({ success: false, message: 'department denied' } as never);
    } else {
      jest.mocked(timeStatsApiClient.getDepartmentTimeStats).mockResolvedValueOnce({ success: true, data: { totalHours: 0 } } as never);
    }
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getAllByText('0.0').length).toBeGreaterThan(0));
    fireEvent.press(view.getByText('attendance.statistics.dimensions.department'));
    await waitFor(() => expect(view.getByTestId('attendance-statistics-error')).toBeTruthy());
    expect(view.queryByText('12.0')).toBeNull();
  });

  it('accepts a free-text selected department with no backend map entry as measured zero', async () => {
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockResolvedValue(employeeStats(0) as never);
    mockActorState.user = { ...mockActorState.user, factoryUser: { ...mockActorState.user.factoryUser, department: 'Packaging' } };
    jest.mocked(timeStatsApiClient.getDepartmentTimeStats).mockResolvedValue({
      success: true,
      data: { departmentStats: { quality: { departmentName: 'quality', totalHours: 3, overtimeHours: 0, averageHours: 3 } } },
    } as never);
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getAllByText('0.0').length).toBeGreaterThan(0));
    fireEvent.press(view.getByText('attendance.statistics.dimensions.department'));
    await waitFor(() => expect(view.queryByTestId('attendance-statistics-error')).toBeNull());
    expect(view.getAllByText('0.0').length).toBeGreaterThan(0);
  });

  it.each(['operator', 'quality_manager'])('allows a personal read for %s without HR module permission', async (role) => {
    mockActorState.user = {
      ...mockActorState.user,
      factoryUser: { ...mockActorState.user.factoryUser, role, modulePermissions: { hr: '-' } },
    };
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockResolvedValue(employeeStats(2) as never);
    const view = render(<AttendanceStatisticsScreen />);

    await waitFor(() => expect(view.getAllByText('2.0').length).toBeGreaterThan(0));
    expect(view.queryByTestId('attendance-statistics-error')).toBeNull();
  });

  it('reads factory daily and weekly responses through their real endpoints', async () => {
    mockActorState.user = { ...mockActorState.user, factoryUser: { ...mockActorState.user.factoryUser, role: 'factory_super_admin' } };
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockResolvedValue(employeeStats(0) as never);
    jest.mocked(timeStatsApiClient.getDailyStats).mockResolvedValue(factoryStats(5) as never);
    jest.mocked(timeStatsApiClient.getWeeklyStats).mockResolvedValue(factoryStats(40) as never);
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getAllByText('0.0').length).toBeGreaterThan(0));
    fireEvent.press(view.getByText('attendance.statistics.dimensions.factory'));
    await waitFor(() => expect(view.getAllByText('5.0').length).toBeGreaterThan(0));
    fireEvent.press(view.getByText('attendance.statistics.periods.week'));
    await waitFor(() => expect(timeStatsApiClient.getWeeklyStats).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), 'F006'));
    expect(view.getAllByText('40.0').length).toBeGreaterThan(0);
    expect(timeStatsApiClient.getDailyStats).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), 'F006');
  });

  it.each(['reject', 'false-success', 'malformed'])('shows a factory read failure for %s instead of zeroes', async (label) => {
    mockActorState.user = { ...mockActorState.user, factoryUser: { ...mockActorState.user.factoryUser, role: 'factory_super_admin' } };
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockResolvedValue(employeeStats(0) as never);
    if (label === 'reject') {
      jest.mocked(timeStatsApiClient.getDailyStats).mockRejectedValueOnce(new Error('factory unavailable'));
    } else if (label === 'false-success') {
      jest.mocked(timeStatsApiClient.getDailyStats).mockResolvedValueOnce({ success: false, message: 'factory denied' } as never);
    } else {
      jest.mocked(timeStatsApiClient.getDailyStats).mockResolvedValueOnce({ success: true, data: { totalHours: 0 } } as never);
    }
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getAllByText('0.0').length).toBeGreaterThan(0));
    fireEvent.press(view.getByText('attendance.statistics.dimensions.factory'));
    await waitFor(() => expect(view.getByTestId('attendance-statistics-error')).toBeTruthy());
    expect(view.queryByText('5.0')).toBeNull();
  });

  it('ignores a late response owned by the previous account and factory', async () => {
    const oldResponse = deferred<ReturnType<typeof employeeStats>>();
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockReturnValueOnce(oldResponse.promise as never).mockResolvedValueOnce(employeeStats(7) as never);
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(timeStatsApiClient.getEmployeeTimeStats).toHaveBeenCalledTimes(1));

    mockActorState.user = { ...mockActorState.user, id: 10, factoryId: 'F007', factoryUser: { ...mockActorState.user.factoryUser, factoryId: 'F007' } };
    view.rerender(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getAllByText('7.0').length).toBeGreaterThan(0));
    await act(async () => { oldResponse.resolve(employeeStats(99)); await Promise.resolve(); });

    expect(view.getAllByText('7.0').length).toBeGreaterThan(0);
    expect(view.queryByText('99.0')).toBeNull();
  });

  it('masks the old query before a dimension response and ignores its late result', async () => {
    const oldResponse = deferred<ReturnType<typeof employeeStats>>();
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockReturnValueOnce(oldResponse.promise as never);
    jest.mocked(timeStatsApiClient.getDepartmentTimeStats).mockResolvedValueOnce(departmentStats(12) as never);
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(timeStatsApiClient.getEmployeeTimeStats).toHaveBeenCalledTimes(1));

    fireEvent.press(view.getByText('attendance.statistics.dimensions.department'));
    expect(view.queryByText('99.0')).toBeNull();
    await waitFor(() => expect(view.getByText('12.0')).toBeTruthy());
    await act(async () => { oldResponse.resolve(employeeStats(99)); await Promise.resolve(); });
    expect(view.getByText('12.0')).toBeTruthy();
    expect(view.queryByText('99.0')).toBeNull();
  });

  it('masks populated old stats in the first render after a dimension switch', async () => {
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockResolvedValueOnce(employeeStats(9) as never);
    const nextDepartment = deferred<ReturnType<typeof departmentStats>>();
    jest.mocked(timeStatsApiClient.getDepartmentTimeStats).mockReturnValueOnce(nextDepartment.promise as never);
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getAllByText('9.0').length).toBeGreaterThan(0));

    fireEvent.press(view.getByText('attendance.statistics.dimensions.department'));
    expect(view.queryByText('9.0')).toBeNull();
    await act(async () => { nextDepartment.resolve(departmentStats(12)); await Promise.resolve(); });
    await waitFor(() => expect(view.getByText('12.0')).toBeTruthy());
  });

  it('masks a retained refresh callback after the actor and permission change', async () => {
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats)
      .mockResolvedValueOnce(employeeStats(4) as never)
      .mockResolvedValueOnce(employeeStats(6) as never);
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getAllByText('4.0').length).toBeGreaterThan(0));
    const retainedRefresh = view.getByTestId('attendance-statistics-refresh').props.onPress as () => void;
    const callsBeforeSwitch = jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mock.calls.length;

    mockActorState.user = {
      ...mockActorState.user,
      id: 10,
      factoryId: 'F007',
      factoryUser: { ...mockActorState.user.factoryUser, role: 'viewer', factoryId: 'F007', modulePermissions: { hr: '-' } },
    };
    view.rerender(<AttendanceStatisticsScreen />);
    expect(view.queryByText('4.0')).toBeNull();
    await waitFor(() => expect(view.getAllByText('6.0').length).toBeGreaterThan(0));
    await act(async () => { retainedRefresh(); });
    expect(jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mock.calls.length).toBe(callsBeforeSwitch + 1);
    expect(view.queryByText('4.0')).toBeNull();
  });

  it('masks aggregate data when the same actor loses HR permission', async () => {
    mockActorState.user = {
      ...mockActorState.user,
      factoryUser: { ...mockActorState.user.factoryUser, role: 'factory_super_admin', modulePermissions: { hr: 'rw' } },
    };
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockResolvedValueOnce(employeeStats(0) as never);
    jest.mocked(timeStatsApiClient.getDailyStats).mockResolvedValueOnce(factoryStats(5) as never);
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getAllByText('0.0').length).toBeGreaterThan(0));
    fireEvent.press(view.getByText('attendance.statistics.dimensions.factory'));
    await waitFor(() => expect(view.getAllByText('5.0').length).toBeGreaterThan(0));
    const retainedRefresh = view.getByTestId('attendance-statistics-refresh').props.onPress as () => void;
    const dailyCallsBeforeSwitch = jest.mocked(timeStatsApiClient.getDailyStats).mock.calls.length;

    mockActorState.user = {
      ...mockActorState.user,
      factoryUser: { ...mockActorState.user.factoryUser, modulePermissions: { hr: '-' } },
    };
    view.rerender(<AttendanceStatisticsScreen />);
    expect(view.queryByText('5.0')).toBeNull();
    await waitFor(() => expect(view.getByTestId('attendance-statistics-error')).toBeTruthy());
    await act(async () => { retainedRefresh(); });
    expect(jest.mocked(timeStatsApiClient.getDailyStats).mock.calls.length).toBe(dailyCallsBeforeSwitch);
    expect(view.queryByText('5.0')).toBeNull();
  });

  it('ignores a late aggregate rejection after the account and factory change', async () => {
    mockActorState.user = {
      ...mockActorState.user,
      factoryUser: { ...mockActorState.user.factoryUser, role: 'factory_super_admin', modulePermissions: { hr: 'rw' } },
    };
    const oldAggregate = deferred<ReturnType<typeof factoryStats>>();
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockResolvedValueOnce(employeeStats(0) as never);
    jest.mocked(timeStatsApiClient.getDailyStats)
      .mockReturnValueOnce(oldAggregate.promise as never)
      .mockResolvedValueOnce(factoryStats(6) as never);
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getAllByText('0.0').length).toBeGreaterThan(0));
    fireEvent.press(view.getByText('attendance.statistics.dimensions.factory'));
    await waitFor(() => expect(timeStatsApiClient.getDailyStats).toHaveBeenCalledTimes(1));
    const loggerCallsBefore = (globalThis as LoggerGlobal).__attendanceStatsLoggerCalls?.length || 0;

    mockActorState.user = {
      ...mockActorState.user,
      id: 10,
      factoryId: 'F007',
      factoryUser: { ...mockActorState.user.factoryUser, factoryId: 'F007' },
    };
    view.rerender(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getAllByText('6.0').length).toBeGreaterThan(0));
    await act(async () => { oldAggregate.reject(new Error('stale aggregate unavailable')); await Promise.resolve(); });

    expect(view.getAllByText('6.0').length).toBeGreaterThan(0);
    expect(view.queryByTestId('attendance-statistics-error')).toBeNull();
    expect((globalThis as LoggerGlobal).__attendanceStatsLoggerCalls?.length || 0).toBe(loggerCallsBefore);
  });

  it('blocks a retained department refresh after the role loses department access', async () => {
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockResolvedValueOnce(employeeStats(0) as never);
    jest.mocked(timeStatsApiClient.getDepartmentTimeStats).mockResolvedValueOnce(departmentStats(12) as never);
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getAllByText('0.0').length).toBeGreaterThan(0));
    fireEvent.press(view.getByText('attendance.statistics.dimensions.department'));
    await waitFor(() => expect(view.getByText('12.0')).toBeTruthy());
    const retainedRefresh = view.getByTestId('attendance-statistics-refresh').props.onPress as () => void;
    const departmentCallsBefore = jest.mocked(timeStatsApiClient.getDepartmentTimeStats).mock.calls.length;

    mockActorState.user = {
      ...mockActorState.user,
      factoryUser: { ...mockActorState.user.factoryUser, role: 'viewer', modulePermissions: { hr: 'rw' } },
    };
    view.rerender(<AttendanceStatisticsScreen />);
    expect(view.queryByText('12.0')).toBeNull();
    await waitFor(() => expect(view.getByTestId('attendance-statistics-error')).toBeTruthy());
    await act(async () => { retainedRefresh(); });
    expect(jest.mocked(timeStatsApiClient.getDepartmentTimeStats).mock.calls.length).toBe(departmentCallsBefore);
    expect(view.queryByText('12.0')).toBeNull();
  });

  it('masks a late response after the selected date range changes to a week', async () => {
    const oldResponse = deferred<ReturnType<typeof employeeStats>>();
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockReturnValueOnce(oldResponse.promise as never).mockResolvedValueOnce(employeeStats(8) as never);
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(timeStatsApiClient.getEmployeeTimeStats).toHaveBeenCalledTimes(1));
    fireEvent.press(view.getByText('attendance.statistics.periods.week'));
    expect(view.queryByText('99.0')).toBeNull();
    await waitFor(() => expect(view.getAllByText('8.0').length).toBeGreaterThan(0));
    await act(async () => { oldResponse.resolve(employeeStats(99)); await Promise.resolve(); });
    expect(view.queryByText('99.0')).toBeNull();
  });

  it('rejects a false-success response instead of rendering zeroes', async () => {
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockResolvedValue({ success: false, message: 'denied' } as never);
    const view = render(<AttendanceStatisticsScreen />);
    await waitFor(() => expect(view.getByTestId('attendance-statistics-error')).toBeTruthy());
    expect(view.queryByText('0.0')).toBeNull();
  });

  it('logs a stable failure event without the raw error or request owner', async () => {
    jest.mocked(timeStatsApiClient.getEmployeeTimeStats).mockRejectedValueOnce(new Error('sensitive backend detail'));
    const view = render(<AttendanceStatisticsScreen />);

    await waitFor(() => expect(view.getByTestId('attendance-statistics-error')).toBeTruthy());
    const loggerCalls = (globalThis as LoggerGlobal).__attendanceStatsLoggerCalls || [];
    expect(loggerCalls).toHaveLength(1);
    const [loggerMessage, loggerError] = loggerCalls[0] || [];
    expect(loggerMessage).toBe('加载统计数据失败');
    expect(loggerError).toEqual(expect.objectContaining({ message: 'attendance_stats_read_failed' }));
    expect(loggerError).not.toEqual(expect.objectContaining({ message: 'sensitive backend detail' }));
  });
});
