import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { FlatList, Text, TextInput, TouchableOpacity, View } from 'react-native';
import AttendanceManageScreen from '../../../screens/hr/attendance/AttendanceManageScreen';
import { timeclockApiClient } from '../../../services/api/timeclockApiClient';
import { hrShiftApiClient } from '../../../services/api/hrShiftApiClient';
import { useAuthStore } from '../../../store/authStore';

jest.mock('../../../services/api/timeclockApiClient', () => ({ timeclockApiClient: { getTodayRecords: jest.fn(), getHistoryRecords: jest.fn() } }));
jest.mock('../../../services/api/hrShiftApiClient', () => ({ hrShiftApiClient: { getSelfModuleAccess: jest.fn() } }));

jest.mock('../../../store/authStore', () => {
  const state = { user: {
    id: 9, username: 'hr', email: 'hr@example.com', createdAt: '2026-01-01', updatedAt: '2026-01-01', isActive: true,
    factoryId: 'F006', userType: 'factory' as const,
    factoryUser: { role: 'hr_admin' as const, factoryId: 'F006', permissions: [], modulePermissions: { hr: 'rw' as const, analytics: 'r' as const } },
  } };
  const useAuthStore = Object.assign((selector?: (value: typeof state) => unknown) => selector ? selector(state) : state, { getState: () => state });
  return { useAuthStore };
});

jest.mock('../../../utils/permissionHelper', () => ({
  hasModulePermission: (user: { factoryUser?: { modulePermissions?: Record<string, string> } } | null, module: string, action: string) => {
    const level = user?.factoryUser?.modulePermissions?.[module];
    return action === 'read' ? level === 'r' || level === 'rw' : level === 'w' || level === 'rw';
  },
  canUseAIQuery: (user: { factoryUser?: { modulePermissions?: Record<string, string> } } | null) => {
    const level = user?.factoryUser?.modulePermissions?.analytics;
    return level === 'r' || level === 'rw';
  },
}));

const mockNavigate = jest.fn();
const mockFocusCallbacks: Array<() => void | (() => void)> = [];
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate }),
  useFocusEffect: (callback: () => void | (() => void)) => {
    const previousRef = React.useRef<(() => void | (() => void)) | undefined>(undefined);
    React.useEffect(() => {
      if (previousRef.current === callback) return undefined;
      previousRef.current = callback;
      mockFocusCallbacks.push(callback);
      return callback();
    }, [callback]);
  },
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => ({
    'attendance.manage.status.normal': '正常',
    'attendance.manage.status.late': '迟到',
    'attendance.manage.status.earlyLeave': '早退',
    'attendance.manage.status.lateAndEarlyLeave': '迟到且早退',
    'attendance.manage.status.absent': '缺勤',
    'attendance.manage.status.working': '工作中',
    'attendance.manage.status.ended': '已结束',
  }[key] ?? key) }),
}));
jest.mock('react-native-safe-area-context', () => ({ SafeAreaView: ({ children }: { children?: React.ReactNode }) => <View>{children}</View> }));
jest.mock('react-native-paper', () => {
  const Native = require('react-native') as typeof import('react-native');
  const Box = ({ children, ...props }: { children?: React.ReactNode; [key: string]: unknown }) => <Native.View {...props}>{children}</Native.View>;
  return {
    Text: Native.Text, Card: Object.assign(Box, { Content: Box }), Chip: Box, Avatar: { Text: Box }, ActivityIndicator: Box,
    Searchbar: ({ value, onChangeText, ...props }: { value?: string; onChangeText?: (value: string) => void; [key: string]: unknown }) => <Native.TextInput {...props} value={value} onChangeText={onChangeText} />,
    SegmentedButtons: ({ onValueChange, buttons }: { value: string; onValueChange: (value: string) => void; buttons: Array<{ value: string; label: string }> }) => <Native.View>{buttons.map((button) => <Native.TouchableOpacity key={button.value} onPress={() => onValueChange(button.value)}><Native.Text>{button.label}</Native.Text></Native.TouchableOpacity>)}</Native.View>,
  };
});

interface FooterProps {
  pagination?: { currentPage: number; totalPages: number; onPageChange: (page: number) => void };
  onAIAnalyze?: () => void;
  stats: Array<{ label: string; value: number }>;
}
let footerProps: FooterProps | undefined;
jest.mock('../../../components/list', () => ({
  StickyFooterSummary: (props: FooterProps) => {
    footerProps = props;
    return <View>
      {props.pagination && <TouchableOpacity testID="footer-next" onPress={() => props.pagination?.onPageChange(2)}><TextInput value="next" editable={false} /></TouchableOpacity>}
      {props.onAIAnalyze && <TouchableOpacity testID="footer-ai" onPress={props.onAIAnalyze}><TextInput value="ai" editable={false} /></TouchableOpacity>}
    </View>;
  },
}));

const allowedModule = [{ moduleCode: 'hr_employee', permissionModule: 'hr', roleDefaultAllowed: true, effectiveAllowed: true }];
const deniedModule = [{ moduleCode: 'hr_employee', permissionModule: 'hr', roleDefaultAllowed: true, effectiveAllowed: false }];
const todayResponse = (records: unknown[]) => ({ success: true, data: { records } });
const historyResponse = (content: unknown[], totalElements = content.length, page = 1, metadata: Record<string, unknown> = {}) => ({
  success: true,
  data: { content, totalElements, totalPages: Math.ceil(totalElements / 50), page, size: 50, ...metadata },
});
const record = (userId: number, date = '2026-10-04', extra: Record<string, unknown> = {}) => ({ id: userId, userId, clockInTime: `${date}T08:00:00`, ...extra });

function setActor(id: number, factoryId = 'F006', permissions: { hr?: string; analytics?: string } = { hr: 'rw', analytics: 'r' }) {
  const actor = useAuthStore.getState().user;
  if (!actor || actor.userType !== 'factory') throw new Error('test actor missing');
  useAuthStore.getState().user = {
    ...actor, id, factoryId,
    factoryUser: { ...actor.factoryUser, factoryId, modulePermissions: { hr: permissions.hr ?? '-', analytics: permissions.analytics ?? '-' } },
  } as typeof actor;
}

describe('AttendanceManageScreenReadback', () => {
  beforeEach(() => {
    jest.resetAllMocks();
    mockFocusCallbacks.length = 0;
    footerProps = undefined;
    setActor(9, 'F006', { hr: 'rw', analytics: 'r' });
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mockResolvedValue(allowedModule);
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValue(todayResponse([]));
    (timeclockApiClient.getHistoryRecords as jest.Mock).mockResolvedValue(historyResponse([]));
  });

  it('uses bounded current-month filters, real page two, local search, and one-based production page metadata', async () => {
    (timeclockApiClient.getHistoryRecords as jest.Mock).mockResolvedValueOnce(historyResponse([record(7), record(8)], 51, 1));
    const view = render(<AttendanceManageScreen />);
    await waitFor(() => expect(timeclockApiClient.getTodayRecords).toHaveBeenCalledWith({ factoryId: 'F006' }));
    fireEvent.press(view.getByText('attendance.history.title'));
    await waitFor(() => expect(timeclockApiClient.getHistoryRecords).toHaveBeenCalledWith(expect.objectContaining({
      factoryId: 'F006', startDate: expect.stringMatching(/^\d{4}-\d{2}-01$/), endDate: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), page: 1, size: 50,
    })));
    expect((timeclockApiClient.getHistoryRecords as jest.Mock).mock.calls[0][0]).not.toHaveProperty('number');
    fireEvent.changeText(view.getByPlaceholderText('staff.search.placeholder'), '用户7');
    expect(view.getByText('用户7')).toBeTruthy();
    expect(view.queryByText('用户8')).toBeNull();
    fireEvent.press(view.getByTestId('footer-next'));
    await waitFor(() => expect(timeclockApiClient.getHistoryRecords).toHaveBeenLastCalledWith(expect.objectContaining({ factoryId: 'F006', page: 2, size: 50 })));
  });

  it('refreshes the history month on focus and ignores a late old-range success', async () => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date(2026, 9, 4, 12, 0, 0));
    try {
      (timeclockApiClient.getHistoryRecords as jest.Mock).mockResolvedValueOnce(historyResponse([record(7, '2026-10-04')], 51, 1));
      const view = render(<AttendanceManageScreen />);
      fireEvent.press(view.getByText('attendance.history.title'));
      await waitFor(() => expect(view.getByText('用户7')).toBeTruthy());
      expect(footerProps?.stats.some((stat) => stat.value > 0)).toBe(true);
      expect(view.getByTestId('footer-ai')).toBeTruthy();
      const oldFocus = mockFocusCallbacks[mockFocusCallbacks.length - 1];
      const oldRefresh = view.UNSAFE_getByType(FlatList).props.refreshControl.props.onRefresh as () => void;
      let resolveOld: ((value: unknown) => void) | undefined;
      const oldResponse = new Promise((resolve) => { resolveOld = resolve; });
      (timeclockApiClient.getHistoryRecords as jest.Mock).mockImplementationOnce(() => oldResponse);
      act(() => { oldRefresh(); });
      let resolveNew: ((value: unknown) => void) | undefined;
      const newResponse = new Promise((resolve) => { resolveNew = resolve; });
      (timeclockApiClient.getHistoryRecords as jest.Mock).mockImplementationOnce(() => newResponse);
      jest.setSystemTime(new Date(2026, 10, 3, 12, 0, 0));
      act(() => { oldFocus?.(); });
      expect(mockFocusCallbacks.length).toBeGreaterThan(2);
      await waitFor(() => expect(String(view.getByTestId('attendance-date-range').props.children)).toContain('2026-11-01'));
      act(() => { mockFocusCallbacks[mockFocusCallbacks.length - 1]?.(); });
      expect(view.queryByText('用户7')).toBeNull();
      expect(footerProps?.stats.every((stat) => stat.value === 0)).toBe(true);
      expect(view.queryByTestId('footer-ai')).toBeNull();
      expect(view.queryByTestId('attendance-error')).toBeNull();
      await waitFor(() => expect(timeclockApiClient.getHistoryRecords).toHaveBeenCalledTimes(3));
      expect(timeclockApiClient.getHistoryRecords).toHaveBeenLastCalledWith({
        factoryId: 'F006', startDate: '2026-11-01', endDate: '2026-11-03', page: 1, size: 50,
      });
      resolveNew?.(historyResponse([record(10, '2026-11-02')], 1, 1));
      await waitFor(() => expect(view.getByText('用户10')).toBeTruthy());
      expect(String(view.getByTestId('attendance-date-range').props.children)).toContain('2026-11-01');
      await act(async () => { resolveOld?.(historyResponse([record(7, '2026-10-04')], 1, 1)); await Promise.resolve(); });
      expect(view.queryByText('用户7')).toBeNull();
      expect(view.getByText('用户10')).toBeTruthy();
    } finally {
      jest.useRealTimers();
    }
  });

  it('keeps the new month visible when a late old-range error arrives', async () => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date(2026, 9, 4, 12, 0, 0));
    try {
      (timeclockApiClient.getHistoryRecords as jest.Mock).mockResolvedValueOnce(historyResponse([record(7, '2026-10-04')], 51, 1));
      const view = render(<AttendanceManageScreen />);
      fireEvent.press(view.getByText('attendance.history.title'));
      await waitFor(() => expect(view.getByText('用户7')).toBeTruthy());
      expect(footerProps?.stats.some((stat) => stat.value > 0)).toBe(true);
      expect(view.getByTestId('footer-ai')).toBeTruthy();
      const oldFocus = mockFocusCallbacks[mockFocusCallbacks.length - 1];
      const oldRefresh = view.UNSAFE_getByType(FlatList).props.refreshControl.props.onRefresh as () => void;
      let rejectOld: ((reason?: unknown) => void) | undefined;
      const oldResponse = new Promise((_, reject) => { rejectOld = reject; });
      (timeclockApiClient.getHistoryRecords as jest.Mock).mockImplementationOnce(() => oldResponse);
      act(() => { oldRefresh(); });
      let resolveNew: ((value: unknown) => void) | undefined;
      const newResponse = new Promise((resolve) => { resolveNew = resolve; });
      (timeclockApiClient.getHistoryRecords as jest.Mock).mockImplementationOnce(() => newResponse);
      jest.setSystemTime(new Date(2026, 10, 3, 12, 0, 0));
      act(() => { oldFocus?.(); });
      expect(mockFocusCallbacks.length).toBeGreaterThan(2);
      await waitFor(() => expect(String(view.getByTestId('attendance-date-range').props.children)).toContain('2026-11-01'));
      act(() => { mockFocusCallbacks[mockFocusCallbacks.length - 1]?.(); });
      expect(view.queryByText('用户7')).toBeNull();
      expect(footerProps?.stats.every((stat) => stat.value === 0)).toBe(true);
      expect(view.queryByTestId('attendance-error')).toBeNull();
      await waitFor(() => expect(timeclockApiClient.getHistoryRecords).toHaveBeenCalledTimes(3));
      expect(timeclockApiClient.getHistoryRecords).toHaveBeenLastCalledWith({
        factoryId: 'F006', startDate: '2026-11-01', endDate: '2026-11-03', page: 1, size: 50,
      });
      resolveNew?.(historyResponse([record(10, '2026-11-02')], 1, 1));
      await waitFor(() => expect(view.getByText('用户10')).toBeTruthy());
      await act(async () => { rejectOld?.(new Error('old month failed')); await Promise.resolve(); });
      expect(view.getByText('用户10')).toBeTruthy();
      expect(view.queryByTestId('attendance-error')).toBeNull();
    } finally {
      jest.useRealTimers();
    }
  });

  it('updates only the history end date on a same-month next-day focus', async () => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date(2026, 9, 4, 12, 0, 0));
    try {
      (timeclockApiClient.getHistoryRecords as jest.Mock).mockResolvedValueOnce(historyResponse([record(7, '2026-10-04')], 1, 1));
      const view = render(<AttendanceManageScreen />);
      fireEvent.press(view.getByText('attendance.history.title'));
      await waitFor(() => expect(view.getByText('用户7')).toBeTruthy());
      const oldFocus = mockFocusCallbacks[mockFocusCallbacks.length - 1];
      (timeclockApiClient.getHistoryRecords as jest.Mock).mockResolvedValueOnce(historyResponse([record(8, '2026-10-05')], 1, 1));
      jest.setSystemTime(new Date(2026, 9, 5, 12, 0, 0));
      act(() => { oldFocus?.(); });
      expect(view.queryByText('用户7')).toBeNull();
      await waitFor(() => expect(timeclockApiClient.getHistoryRecords).toHaveBeenCalledTimes(2));
      expect(timeclockApiClient.getHistoryRecords).toHaveBeenLastCalledWith({
        factoryId: 'F006', startDate: '2026-10-01', endDate: '2026-10-05', page: 1, size: 50,
      });
      await waitFor(() => expect(view.getByText('用户8')).toBeTruthy());
      expect(String(view.getByTestId('attendance-date-range').props.children)).toContain('2026-10-01');
      expect(String(view.getByTestId('attendance-date-range').props.children)).toContain('2026-10-05');
    } finally {
      jest.useRealTimers();
    }
  });

  it('clears the visible old month synchronously when focus crosses into a new month', async () => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date(2026, 9, 4, 12, 0, 0));
    try {
      (timeclockApiClient.getHistoryRecords as jest.Mock).mockResolvedValueOnce(historyResponse([record(7, '2026-10-04')], 51, 1));
      const view = render(<AttendanceManageScreen />);
      fireEvent.press(view.getByText('attendance.history.title'));
      await waitFor(() => expect(view.getByText('用户7')).toBeTruthy());
      expect(view.getByText('用户7')).toBeTruthy();
      expect(footerProps?.stats.some((stat) => stat.value > 0)).toBe(true);
      expect(view.getByTestId('footer-ai')).toBeTruthy();

      const oldFocus = mockFocusCallbacks[mockFocusCallbacks.length - 1];
      let resolveNew: ((value: unknown) => void) | undefined;
      const newResponse = new Promise((resolve) => { resolveNew = resolve; });
      (timeclockApiClient.getHistoryRecords as jest.Mock).mockImplementationOnce(() => newResponse);
      jest.setSystemTime(new Date(2026, 10, 3, 12, 0, 0));

      act(() => { oldFocus?.(); });
      await waitFor(() => expect(timeclockApiClient.getHistoryRecords).toHaveBeenCalledTimes(2));
      expect(view.queryByText('用户7')).toBeNull();
      expect(footerProps?.stats.every((stat) => stat.value === 0)).toBe(true);
      expect(view.queryByTestId('footer-ai')).toBeNull();
      expect(view.queryByTestId('attendance-error')).toBeNull();
      expect(timeclockApiClient.getHistoryRecords).toHaveBeenLastCalledWith({
        factoryId: 'F006', startDate: '2026-11-01', endDate: '2026-11-03', page: 1, size: 50,
      });

      resolveNew?.(historyResponse([record(10, '2026-11-02')], 1, 1));
      await waitFor(() => expect(view.getByText('用户10')).toBeTruthy());
    } finally {
      jest.useRealTimers();
    }
  });

  it('keeps failed, malformed, empty, invalid record, and negative duration states explicit', async () => {
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValueOnce({ success: false, message: '403 denied' });
    const failed = render(<AttendanceManageScreen />);
    await waitFor(() => expect(failed.getByTestId('attendance-error')).toBeTruthy());
    failed.unmount();
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValueOnce(todayResponse([record(0)]));
    const malformedRecord = render(<AttendanceManageScreen />);
    await waitFor(() => expect(malformedRecord.getByTestId('attendance-malformed')).toBeTruthy());
    malformedRecord.unmount();
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValueOnce(todayResponse([record(7, '2026-10-04', { workDuration: -1 })]));
    const negative = render(<AttendanceManageScreen />);
    await waitFor(() => expect(negative.getByTestId('attendance-malformed')).toBeTruthy());
    negative.unmount();
    (timeclockApiClient.getHistoryRecords as jest.Mock).mockResolvedValueOnce(historyResponse([record(7)], 0, 1));
    const inconsistentHistory = render(<AttendanceManageScreen />);
    fireEvent.press(inconsistentHistory.getByText('attendance.history.title'));
    await waitFor(() => expect(inconsistentHistory.getByTestId('attendance-malformed')).toBeTruthy());
    inconsistentHistory.unmount();
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValueOnce(todayResponse([]));
    const empty = render(<AttendanceManageScreen />);
    await waitFor(() => expect(empty.getByTestId('attendance-empty')).toBeTruthy());
  });

  it('renders measured zero, ended status, and clock-in date rather than createdAt', async () => {
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValueOnce(todayResponse([
      record(7, '2026-10-03', { clockOutTime: '2026-10-03T08:00:00', workDuration: 0, createdAt: '2026-10-04T12:00:00' }),
    ]));
    const view = render(<AttendanceManageScreen />);
    await waitFor(() => expect(view.getByText('0h 0m')).toBeTruthy());
    expect(view.getByTestId('attendance-status-7')).toBeTruthy();
    expect(view.getByText('2026-10-03')).toBeTruthy();
    expect(view.queryByText('2026-10-04')).toBeNull();
  });

  it('renders production uppercase clock status and every attendance exception', async () => {
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValueOnce(todayResponse([
      record(7, '2026-10-04', { status: 'OFF_WORK' }),
      record(8, '2026-10-04', { status: 'WORKING' }),
      record(9, '2026-10-04', { status: 'ON_BREAK' }),
      record(10, '2026-10-04', { status: 'WORKING', attendanceStatus: 'NORMAL' }),
      record(11, '2026-10-04', { status: 'WORKING', attendanceStatus: 'LATE' }),
      record(12, '2026-10-04', { status: 'OFF_WORK', clockOutTime: '2026-10-04T17:00:00', attendanceStatus: 'EARLY_LEAVE' }),
      record(13, '2026-10-04', { status: 'OFF_WORK', clockOutTime: '2026-10-04T17:00:00', attendanceStatus: 'LATE_AND_EARLY_LEAVE' }),
      record(14, '2026-10-04', { status: 'OFF_WORK', attendanceStatus: 'ABSENT' }),
    ]));
    const view = render(<AttendanceManageScreen />);
    await waitFor(() => expect(view.getByText('用户14')).toBeTruthy());
    expect(view.getByTestId('attendance-status-7').children).toContain('已结束');
    expect(view.getByTestId('attendance-status-8').children).toContain('工作中');
    expect(view.getByTestId('attendance-status-9').children).toContain('工作中');
    expect(view.getByTestId('attendance-status-10').children).toContain('正常');
    expect(view.getByTestId('attendance-status-11').children).toContain('迟到');
    expect(view.getByTestId('attendance-status-12').children).toContain('早退');
    expect(view.getByTestId('attendance-status-13').children).toContain('迟到且早退');
    expect(view.getByTestId('attendance-status-14').children).toContain('缺勤');
  });

  it('does zero self/access/business requests when canonical HR read is denied or self module is denied', async () => {
    setActor(9, 'F006', { hr: '-', analytics: 'r' });
    const denied = render(<AttendanceManageScreen />);
    expect(denied.getByTestId('attendance-permission-hint')).toBeTruthy();
    expect(hrShiftApiClient.getSelfModuleAccess).not.toHaveBeenCalled();
    expect(timeclockApiClient.getTodayRecords).not.toHaveBeenCalled();
    denied.unmount();
    setActor(9, 'F006', { hr: 'rw', analytics: 'r' });
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mockResolvedValueOnce(deniedModule);
    const moduleDenied = render(<AttendanceManageScreen />);
    await waitFor(() => expect(moduleDenied.getByTestId('attendance-denied')).toBeTruthy());
    expect(timeclockApiClient.getTodayRecords).not.toHaveBeenCalled();
  });

  it('rejects a non-positive actor before any self-module or attendance request', () => {
    setActor(0, 'F006', { hr: 'rw', analytics: 'r' });
    const view = render(<AttendanceManageScreen />);
    expect(view.getByTestId('attendance-permission-hint')).toBeTruthy();
    expect(hrShiftApiClient.getSelfModuleAccess).not.toHaveBeenCalled();
    expect(timeclockApiClient.getTodayRecords).not.toHaveBeenCalled();
    expect(timeclockApiClient.getHistoryRecords).not.toHaveBeenCalled();
  });

  it('hides AI for canonical analytics denial and navigates a permitted actor with bounded counts', async () => {
    setActor(9, 'F006', { hr: 'rw', analytics: '-' });
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValueOnce(todayResponse([record(7)]));
    const denied = render(<AttendanceManageScreen />);
    await waitFor(() => expect(denied.getByText('用户7')).toBeTruthy());
    expect(denied.queryByTestId('attendance-ai-header')).toBeNull();
    expect(denied.queryByTestId('footer-ai')).toBeNull();
    expect(mockNavigate).not.toHaveBeenCalled();
    denied.unmount();
    setActor(9, 'F006', { hr: 'rw', analytics: 'r' });
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValueOnce(todayResponse([record(7)]));
    const permitted = render(<AttendanceManageScreen />);
    await waitFor(() => expect(permitted.getByTestId('attendance-ai-header')).toBeTruthy());
    fireEvent.press(permitted.getByTestId('attendance-ai-header'));
    expect(mockNavigate).toHaveBeenCalledWith('AIChat', expect.objectContaining({ entityType: 'ATTENDANCE', initialMessage: expect.stringContaining('当前筛选页面 1 条') }));
    expect(mockNavigate.mock.calls[0][1].initialMessage).toContain('明确范围');
  });

  it('does not let retained refresh, focus, page, or AI callbacks mutate a new actor first frame', async () => {
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValueOnce(todayResponse([record(7)]));
    const view = render(<AttendanceManageScreen />);
    await waitFor(() => expect(view.getByText('用户7')).toBeTruthy());
    const oldRefresh = view.UNSAFE_getByType(FlatList).props.refreshControl.props.onRefresh as () => void;
    const oldFocus = mockFocusCallbacks[0];
    const oldAI = footerProps?.onAIAnalyze;
    const oldTimeCalls = (timeclockApiClient.getTodayRecords as jest.Mock).mock.calls.length;
    let resolveModule: ((value: unknown[]) => void) | undefined;
    const pendingModule = new Promise<unknown[]>((resolve) => { resolveModule = resolve; });
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mockImplementationOnce(() => pendingModule);
    setActor(10, 'F007', { hr: 'rw', analytics: 'r' });
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValueOnce(todayResponse([record(10)]));
    act(() => { view.rerender(<AttendanceManageScreen />); });
    expect(view.queryByText('用户7')).toBeNull();
    resolveModule?.(allowedModule);
    await waitFor(() => expect(view.getByText('用户10')).toBeTruthy());
    const newSelfCalls = (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mock.calls.length;
    const newTodayCalls = (timeclockApiClient.getTodayRecords as jest.Mock).mock.calls.length;
    act(() => { oldRefresh(); oldFocus?.(); oldAI?.(); });
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect((hrShiftApiClient.getSelfModuleAccess as jest.Mock).mock.calls.length).toBe(newSelfCalls);
    expect((timeclockApiClient.getTodayRecords as jest.Mock).mock.calls.length).toBe(newTodayCalls);
    expect((timeclockApiClient.getTodayRecords as jest.Mock).mock.calls.length).toBeGreaterThan(oldTimeCalls);
    expect(view.getByText('用户10')).toBeTruthy();
    expect(view.queryByTestId('attendance-error')).toBeNull();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('ignores a late old success after the new actor has loaded', async () => {
    let resolveOld: ((value: unknown) => void) | undefined;
    const oldResponse = new Promise((resolve) => { resolveOld = resolve; });
    (timeclockApiClient.getTodayRecords as jest.Mock).mockImplementationOnce(() => oldResponse);
    const view = render(<AttendanceManageScreen />);
    await waitFor(() => expect(timeclockApiClient.getTodayRecords).toHaveBeenCalledTimes(1));
    setActor(10, 'F007', { hr: 'rw', analytics: 'r' });
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValueOnce(todayResponse([record(10, '2026-10-04')]));
    act(() => { view.rerender(<AttendanceManageScreen />); });
    await waitFor(() => expect(view.getByText('用户10')).toBeTruthy());
    await act(async () => { resolveOld?.(todayResponse([record(7)])); await Promise.resolve(); });
    expect(view.queryByText('用户7')).toBeNull();
    expect(view.getByText('用户10')).toBeTruthy();
  });

  it('ignores a late old error after the new actor has loaded', async () => {
    let rejectOld: ((reason?: unknown) => void) | undefined;
    const oldResponse = new Promise((_, reject) => { rejectOld = reject; });
    (timeclockApiClient.getTodayRecords as jest.Mock).mockImplementationOnce(() => oldResponse);
    const view = render(<AttendanceManageScreen />);
    await waitFor(() => expect(timeclockApiClient.getTodayRecords).toHaveBeenCalledTimes(1));
    setActor(10, 'F007', { hr: 'rw', analytics: 'r' });
    (timeclockApiClient.getTodayRecords as jest.Mock).mockResolvedValueOnce(todayResponse([record(10, '2026-10-04')]));
    act(() => { view.rerender(<AttendanceManageScreen />); });
    await waitFor(() => expect(view.getByText('用户10')).toBeTruthy());
    await act(async () => { rejectOld?.(new Error('old failure')); await Promise.resolve(); });
    expect(view.getByText('用户10')).toBeTruthy();
    expect(view.queryByTestId('attendance-error')).toBeNull();
  });

  it('drops retained page callbacks together with refresh, focus, and AI callbacks', async () => {
    (timeclockApiClient.getHistoryRecords as jest.Mock).mockResolvedValueOnce(historyResponse([record(7)], 51, 1));
    const view = render(<AttendanceManageScreen />);
    fireEvent.press(view.getByText('attendance.history.title'));
    await waitFor(() => expect(view.getByText('用户7')).toBeTruthy());
    const oldRefresh = view.UNSAFE_getByType(FlatList).props.refreshControl.props.onRefresh as () => void;
    const oldFocus = mockFocusCallbacks[mockFocusCallbacks.length - 1];
    const oldPage = footerProps?.pagination?.onPageChange;
    const oldAI = footerProps?.onAIAnalyze;
    const oldHistoryCalls = (timeclockApiClient.getHistoryRecords as jest.Mock).mock.calls.length;
    const oldTodayCalls = (timeclockApiClient.getTodayRecords as jest.Mock).mock.calls.length;
    let resolveModule: ((value: unknown[]) => void) | undefined;
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mockImplementationOnce(() => new Promise<unknown[]>((resolve) => { resolveModule = resolve; }));
    setActor(10, 'F007', { hr: 'rw', analytics: 'r' });
    (timeclockApiClient.getHistoryRecords as jest.Mock).mockResolvedValueOnce(historyResponse([record(10)], 1, 1));
    act(() => { view.rerender(<AttendanceManageScreen />); });
    expect(view.queryByText('用户7')).toBeNull();
    resolveModule?.(allowedModule);
    await waitFor(() => expect(view.getByText('用户10')).toBeTruthy());
    const newSelfCalls = (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mock.calls.length;
    const newHistoryCalls = (timeclockApiClient.getHistoryRecords as jest.Mock).mock.calls.length;
    act(() => { oldRefresh(); oldFocus?.(); oldPage?.(2); oldAI?.(); });
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect((hrShiftApiClient.getSelfModuleAccess as jest.Mock).mock.calls.length).toBe(newSelfCalls);
    expect((timeclockApiClient.getHistoryRecords as jest.Mock).mock.calls.length).toBe(newHistoryCalls);
    expect((timeclockApiClient.getHistoryRecords as jest.Mock).mock.calls.length).toBeGreaterThan(oldHistoryCalls);
    expect((timeclockApiClient.getTodayRecords as jest.Mock).mock.calls.length).toBe(oldTodayCalls);
    expect(view.getByText('用户10')).toBeTruthy();
    expect(view.queryByTestId('attendance-error')).toBeNull();
    expect(mockNavigate).not.toHaveBeenCalled();
    resolveModule?.(allowedModule);
  });
});
