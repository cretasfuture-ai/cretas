import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { ScrollView } from 'react-native';
import { Chip } from 'react-native-paper';
import DepartmentAttendanceScreen from '../../../screens/attendance/DepartmentAttendanceScreen';
import TimeClockScreen from '../../../screens/attendance/TimeClockScreen';
import { useAuthStore } from '../../../store/authStore';

const mockGetDepartmentAttendance = jest.fn();
const mockGetTodayRecord = jest.fn();
const mockFocusCallbacks: Array<() => void | (() => void)> = [];
const mockNavigation = { navigate: jest.fn(), goBack: jest.fn() };
type TestRendererHandle = { toJSON: () => unknown; update: (element: React.ReactElement) => void; unmount: () => void; root: { findAllByType: (type: unknown) => Array<{ props: Record<string, unknown> }> } };
type TestRendererApi = { create: (element: React.ReactElement) => TestRendererHandle; act: (callback: () => void | Promise<void>) => void | Promise<void> };
const testRenderer = require('react-test-renderer') as TestRendererApi;
const rendererAct = testRenderer.act;
let activeRenderer: TestRendererHandle | null = null;
let commitProbeRecords: Array<{ marker: string; treeJson: string; treeText: string; getCalls: number; passiveMarkersAtLayout: string[] }> = [];
let passiveProbeMarkers: string[] = [];

function treeText(value: unknown, seen = new WeakSet<object>()): string {
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map((item) => treeText(item, seen)).join(' ');
  if (!value || typeof value !== 'object') return '';
  if (seen.has(value)) return '';
  seen.add(value);
  const children = (value as { children?: unknown }).children;
  return Array.isArray(children) ? treeText(children, seen) : '';
}

function treeJson(value: unknown): string {
  return JSON.stringify(value, (key, nestedValue) => key === '_owner' ? undefined : nestedValue);
}

jest.mock('../../../services/api/timeclockApiClient', () => ({ timeclockApiClient: {
  getDepartmentAttendance: (...args: unknown[]) => mockGetDepartmentAttendance(...args),
  getTodayRecord: (...args: unknown[]) => mockGetTodayRecord(...args),
} }));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => mockNavigation,
  useFocusEffect: (callback: () => void | (() => void)) => {
    mockFocusCallbacks.push(callback);
    return React.useEffect(callback, [callback]);
  },
}));
jest.mock('@react-native-community/datetimepicker', () => () => null);
jest.mock('../../../components/ui', () => {
  const ReactNative = require('react-native');
  const passthrough = ({ children }: { children: React.ReactNode }) => <>{children}</>;
  const button = ({ children, onPress, disabled }: { children: React.ReactNode; onPress?: () => void; disabled?: boolean }) => <ReactNative.Pressable onPress={onPress} disabled={disabled}><ReactNative.Text>{children}</ReactNative.Text></ReactNative.Pressable>;
  return { ScreenWrapper: passthrough, NeoCard: passthrough, NeoButton: button, StatusBadge: passthrough };
});
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

function user(id: number, factoryId: string, hr: 'r' | 'rw' | '-' = 'rw') {
  return {
    id,
    username: `hr-${id}`,
    email: '',
    createdAt: '',
    updatedAt: '',
    isActive: true,
    userType: 'factory' as const,
    factoryId,
    factoryUser: { role: 'quality_manager' as const, factoryId, permissions: [], modulePermissions: { hr } },
  };
}

const successResponse = (name: string, count = 1) => ({
  success: true,
  code: 200,
  message: 'ok',
  data: {
    department: 'PROCESSING',
    date: '2026-10-04',
    totalEmployees: 1,
    clockedIn: 1,
    onBreak: 0,
    clockedOut: 0,
    absent: 0,
    records: Array.from({ length: count }, (_, index) => ({ userId: index + 1, userName: index === 0 ? name : `${name}-${index}`, status: 'present', clockInTime: '2026-10-04T08:00:00Z' })),
  },
});

function CommitProbe({ marker, children }: { marker: string; children: React.ReactNode }) {
  React.useLayoutEffect(() => {
    if (activeRenderer) {
      commitProbeRecords.push({
        marker,
        treeJson: treeJson(activeRenderer.toJSON()),
        treeText: treeText(activeRenderer.toJSON()),
        getCalls: mockGetDepartmentAttendance.mock.calls.length,
        passiveMarkersAtLayout: [...passiveProbeMarkers],
      });
    }
  }, [marker]);
  React.useEffect(() => {
    passiveProbeMarkers.push(marker);
  }, [marker]);
  return <>{children}</>;
}

describe('DepartmentAttendance read boundary', () => {
  beforeEach(() => {
    mockGetDepartmentAttendance.mockReset();
    mockGetTodayRecord.mockReset().mockResolvedValue({ success: true, data: null });
    mockNavigation.navigate.mockReset();
    mockNavigation.goBack.mockReset();
    mockFocusCallbacks.length = 0;
    useAuthStore.setState({ user: user(11, 'F006'), isAuthenticated: true });
  });

  afterEach(() => {
    useAuthStore.setState({ user: null, isAuthenticated: false });
  });

  it.each(['-', 'r'] as const)('keeps personal history available and follows login HR=%s for the department entry', async (hr) => {
    useAuthStore.setState({ user: user(11, 'F006', hr) });
    const view = render(<TimeClockScreen />);
    await waitFor(() => expect(view.getByText('attendance.timeClock.todayRecords')).toBeTruthy());
    if (hr === '-') {
      expect(view.queryByText('attendance.timeClock.departmentAttendance')).toBeNull();
    } else {
      fireEvent.press(view.getByText('attendance.timeClock.departmentAttendance'));
      expect(mockNavigation.navigate).toHaveBeenCalledWith('DepartmentAttendance');
    }
    expect(mockGetDepartmentAttendance).not.toHaveBeenCalled();
    fireEvent.press(view.getByText('attendance.timeClock.historyRecords'));
    expect(mockNavigation.navigate).toHaveBeenCalledWith('AttendanceHistory');
    view.unmount();
  });

  it('shows a truthful permission boundary and sends zero GETs when HR read is denied', async () => {
    useAuthStore.setState({ user: user(11, 'F006', '-') });
    const view = render(<DepartmentAttendanceScreen />);
    await waitFor(() => expect(view.getByText('暂无部门考勤权限')).toBeTruthy());
    expect(mockGetDepartmentAttendance).not.toHaveBeenCalled();
  });

  it('renders a valid successful read with the selected factory and department', async () => {
    mockGetDepartmentAttendance.mockResolvedValue(successResponse('F006员工'));
    const view = render(<DepartmentAttendanceScreen />);
    await waitFor(() => expect(view.getByText('F006员工')).toBeTruthy());
    expect(mockGetDepartmentAttendance).toHaveBeenCalledWith('PROCESSING', expect.any(String), 'F006');
    expect(view.queryByText('attendance.department.errors.loadFailed')).toBeNull();
  });

  it.each([
    ['malformed envelope', { success: true, data: { records: null } }],
    ['unsuccessful envelope', { success: false, data: successResponse('错误').data }],
  ])('shows load failure instead of a zero attendance result for %s', async (_label, response) => {
    mockGetDepartmentAttendance.mockResolvedValue(response);
    const view = render(<DepartmentAttendanceScreen />);
    await waitFor(() => expect(view.getByText('attendance.department.errors.loadFailed')).toBeTruthy());
    expect(view.queryByText('attendance.department.shouldAttend')).toBeNull();
    expect(view.queryByText('attendance.department.noRecords')).toBeNull();
  });

  it('blocks retained old refresh and focus callbacks after an actor switch', async () => {
    mockGetDepartmentAttendance.mockResolvedValueOnce(successResponse('F006旧员工')).mockResolvedValueOnce(successResponse('F007新员工'));
    const view = render(<DepartmentAttendanceScreen />);
    await waitFor(() => expect(view.getByText('F006旧员工')).toBeTruthy());
    const retainedRefresh = view.UNSAFE_getByType(ScrollView).props.refreshControl.props.onRefresh as () => void;
    const retainedFocus = mockFocusCallbacks[0]!;

    useAuthStore.setState({ user: user(22, 'F007') });
    view.rerender(<DepartmentAttendanceScreen />);
    const callsAfterSwitch = mockGetDepartmentAttendance.mock.calls.length;
    await act(async () => {
      retainedRefresh();
      retainedFocus();
    });
    expect(mockGetDepartmentAttendance.mock.calls.length).toBe(callsAfterSwitch);
    await waitFor(() => expect(view.getByText('F007新员工')).toBeTruthy());
    expect(view.queryByText('F006旧员工')).toBeNull();
  });

  it('ignores a late old factory response after the new actor readback', async () => {
    let releaseOld!: (value: unknown) => void;
    const oldResponse = new Promise((resolve) => { releaseOld = resolve; });
    mockGetDepartmentAttendance.mockReturnValueOnce(oldResponse).mockResolvedValueOnce(successResponse('F007员工'));
    const view = render(<DepartmentAttendanceScreen />);
    await waitFor(() => expect(mockGetDepartmentAttendance).toHaveBeenCalledWith('PROCESSING', expect.any(String), 'F006'));
    await act(async () => { useAuthStore.setState({ user: user(22, 'F007') }); view.rerender(<DepartmentAttendanceScreen />); });
    await waitFor(() => expect(view.getByText('F007员工')).toBeTruthy());
    releaseOld(successResponse('F006旧员工'));
    await waitFor(() => expect(view.getByText('F007员工')).toBeTruthy());
    expect(view.queryByText('F006旧员工')).toBeNull();
  });

  it('blocks a retained old department filter callback after the current query changes', async () => {
    mockGetDepartmentAttendance.mockResolvedValueOnce(successResponse('PROCESSING员工')).mockResolvedValueOnce(successResponse('QUALITY员工'));
    const view = render(<DepartmentAttendanceScreen />);
    await waitFor(() => expect(view.getByText('PROCESSING员工')).toBeTruthy());
    const oldQualityChip = view.UNSAFE_getAllByType(Chip).find((chip) => chip.props.children === 'attendance.department.departments.quality');
    expect(oldQualityChip).toBeTruthy();
    const retainedOldFilter = oldQualityChip!.props.onPress as () => void;
    const currentQualityChip = view.UNSAFE_getAllByType(Chip).find((chip) => chip.props.children === 'attendance.department.departments.quality');
    await act(async () => { currentQualityChip!.props.onPress(); });
    await waitFor(() => expect(view.getByText('QUALITY员工')).toBeTruthy());
    const callsAfterCurrentQuery = mockGetDepartmentAttendance.mock.calls.length;
    retainedOldFilter();
    expect(mockGetDepartmentAttendance.mock.calls.length).toBe(callsAfterCurrentQuery);
    expect(view.getByText('QUALITY员工')).toBeTruthy();
  });

  it('keeps the latest same-query refresh when the older response arrives late', async () => {
    let releaseInitial!: (value: unknown) => void;
    let releaseOldRefresh!: (value: unknown) => void;
    let releaseCurrentRefresh!: (value: unknown) => void;
    const initialResponse = new Promise((resolve) => { releaseInitial = resolve; });
    const oldRefreshResponse = new Promise((resolve) => { releaseOldRefresh = resolve; });
    const currentRefreshResponse = new Promise((resolve) => { releaseCurrentRefresh = resolve; });
    mockGetDepartmentAttendance.mockReturnValueOnce(initialResponse).mockReturnValueOnce(oldRefreshResponse).mockReturnValueOnce(currentRefreshResponse);
    const view = render(<DepartmentAttendanceScreen />);
    await waitFor(() => expect(mockGetDepartmentAttendance).toHaveBeenCalledTimes(1));
    const retainedRefresh = view.UNSAFE_getByType(ScrollView).props.refreshControl.props.onRefresh as () => void;
    await act(async () => { retainedRefresh(); retainedRefresh(); });
    await waitFor(() => expect(mockGetDepartmentAttendance).toHaveBeenCalledTimes(3));
    releaseInitial(successResponse('初始员工'));
    releaseOldRefresh(successResponse('旧刷新员工'));
    await waitFor(() => expect(view.UNSAFE_getByType(ScrollView).props.refreshControl.props.refreshing).toBe(true));
    expect(view.queryByText('旧刷新员工')).toBeNull();
    releaseCurrentRefresh(successResponse('当前员工'));
    await waitFor(() => expect(view.getByText('当前员工')).toBeTruthy());
    expect(view.UNSAFE_getByType(ScrollView).props.refreshControl.props.refreshing).toBe(false);
  });

  it('captures actor and query commits before their passive reads and then shows current data', async () => {
    mockGetDepartmentAttendance
      .mockResolvedValueOnce(successResponse('F006部门员工37', 37))
      .mockResolvedValueOnce(successResponse('F007部门员工37', 37))
      .mockResolvedValueOnce(successResponse('F007质量员工37', 37));
    commitProbeRecords = [];
    passiveProbeMarkers = [];
    activeRenderer = null;

    let renderer!: TestRendererHandle;
    await rendererAct(async () => {
      renderer = testRenderer.create(<CommitProbe marker="F006:PROCESSING"><DepartmentAttendanceScreen /></CommitProbe>);
      activeRenderer = renderer;
      await Promise.resolve();
      await Promise.resolve();
    });
    await waitFor(() => expect(treeText(renderer.toJSON())).toContain('F006部门员工37'));
    const initialTreeJson = treeJson(renderer.toJSON());
    expect(initialTreeJson).toContain('"F006部门员工37"');
    expect(initialTreeJson).toContain('"37"');
    const callsBeforeActor = mockGetDepartmentAttendance.mock.calls.length;

    rendererAct(() => {
      useAuthStore.setState({ user: user(22, 'F007') });
      renderer.update(<CommitProbe marker="F007:PROCESSING"><DepartmentAttendanceScreen /></CommitProbe>);
    });
    const actorCommit = commitProbeRecords.find((record) => record.marker === 'F007:PROCESSING');
    expect(actorCommit).toBeDefined();
    const actorTree = actorCommit!.treeText;
    const actorTreeJson = actorCommit!.treeJson;
    expect(actorTreeJson).not.toContain('F006部门员工37');
    expect(actorTree).not.toContain('attendance.department.errors.loadFailed');
    expect(actorTreeJson).not.toContain('attendance.department.errors.loadFailed');
    expect(actorTreeJson).not.toContain('"37"');
    expect(actorCommit!.getCalls).toBe(callsBeforeActor);
    expect(actorCommit!.passiveMarkersAtLayout).not.toContain('F007:PROCESSING');
    await waitFor(() => expect(treeText(renderer.toJSON())).toContain('F007部门员工37'));
    const callsBeforeQuery = mockGetDepartmentAttendance.mock.calls.length;

    const qualityChip = renderer.root.findAllByType(Chip).find((chip) => chip.props.children === 'attendance.department.departments.quality');
    expect(qualityChip).toBeDefined();
    rendererAct(() => {
      (qualityChip!.props.onPress as () => void)();
      renderer.update(<CommitProbe marker="F007:QUALITY"><DepartmentAttendanceScreen /></CommitProbe>);
    });
    const queryCommit = commitProbeRecords.find((record) => record.marker === 'F007:QUALITY');
    expect(queryCommit).toBeDefined();
    const queryTree = queryCommit!.treeText;
    const queryTreeJson = queryCommit!.treeJson;
    expect(queryTree).not.toContain('F007部门员工37');
    expect(queryTreeJson).not.toContain('F007部门员工37');
    expect(queryTreeJson).not.toContain('"37"');
    expect(queryTreeJson).not.toContain('attendance.department.errors.loadFailed');
    expect(queryCommit!.getCalls).toBe(callsBeforeQuery);
    expect(queryCommit!.passiveMarkersAtLayout).not.toContain('F007:QUALITY');
    await waitFor(() => expect(mockGetDepartmentAttendance).toHaveBeenCalledWith('QUALITY', expect.any(String), 'F007'));
    await waitFor(() => expect(treeText(renderer.toJSON())).toContain('F007质量员工37'));
    rendererAct(() => { renderer.unmount(); });
    activeRenderer = null;
  });

  it('removes an actor error at the layout commit before the new actor GET starts', async () => {
    mockGetDepartmentAttendance
      .mockResolvedValueOnce({ success: false, code: 403, message: 'denied', data: null })
      .mockResolvedValueOnce(successResponse('F007恢复员工37', 37));
    commitProbeRecords = [];
    passiveProbeMarkers = [];
    activeRenderer = null;

    let renderer!: TestRendererHandle;
    await rendererAct(async () => {
      renderer = testRenderer.create(<CommitProbe marker="F006:PROCESSING:error"><DepartmentAttendanceScreen /></CommitProbe>);
      activeRenderer = renderer;
      await Promise.resolve();
      await Promise.resolve();
    });
    await waitFor(() => expect(treeText(renderer.toJSON())).toContain('attendance.department.errors.loadFailed'));
    const callsBeforeActor = mockGetDepartmentAttendance.mock.calls.length;
    rendererAct(() => {
      useAuthStore.setState({ user: user(22, 'F007') });
      renderer.update(<CommitProbe marker="F007:PROCESSING:recovery"><DepartmentAttendanceScreen /></CommitProbe>);
    });
    const actorCommit = commitProbeRecords.find((record) => record.marker === 'F007:PROCESSING:recovery');
    expect(actorCommit).toBeDefined();
    expect(actorCommit!.treeJson).not.toContain('attendance.department.errors.loadFailed');
    expect(actorCommit!.getCalls).toBe(callsBeforeActor);
    expect(actorCommit!.passiveMarkersAtLayout).not.toContain('F007:PROCESSING:recovery');
    await waitFor(() => expect(treeText(renderer.toJSON())).toContain('F007恢复员工37'));
    rendererAct(() => { renderer.unmount(); });
    activeRenderer = null;
  });
});
