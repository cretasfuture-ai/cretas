import React from 'react';
import { ScrollView, TouchableOpacity, View } from 'react-native';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { hrShiftApiClient } from '../../../services/api/hrShiftApiClient';
import { userApiClient } from '../../../services/api/userApiClient';
import { useAuthStore } from '../../../store/authStore';
import * as AppDialog from '../../../components/ui/AppDialog';

let WorkScheduleScreen: React.ComponentType;
let mockHrPermission: 'r' | 'rw' = 'rw';

jest.mock('../../../services/api/hrShiftApiClient', () => ({
  hrShiftApiClient: {
    listAssignments: jest.fn(),
    listActiveShifts: jest.fn(),
    getSelfModuleAccess: jest.fn(),
    assign: jest.fn(),
    remove: jest.fn(),
  },
}));
jest.mock('../../../services/api/userApiClient', () => ({ userApiClient: { getUsers: jest.fn() } }));
jest.mock('../../../store/authStore', () => {
  const state = { user: {
    id: 9,
    username: 'hr',
    email: 'hr@example.com',
    createdAt: '2026-01-01',
    updatedAt: '2026-01-01',
    isActive: true,
    factoryId: 'F006',
    userType: 'factory' as const,
    factoryUser: { role: 'hr_admin' as const, factoryId: 'F006', permissions: [], modulePermissions: { hr: 'rw' as const } },
  } };
  const useAuthStore = Object.assign(
    (selector?: (value: typeof state) => unknown) => selector ? selector(state) : state,
    { getState: () => state },
  );
  return { useAuthStore };
});
jest.mock('../../../utils/permissionHelper', () => ({
  hasModulePermission: (_user: unknown, _module: string, action: 'read' | 'write') => action === 'read' || mockHrPermission === 'rw',
}));
jest.mock('../../../components/ui/AppDialog', () => ({ appAlert: jest.fn() }));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: jest.fn() }),
  useFocusEffect: (callback: () => void) => React.useEffect(callback, [callback]),
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));
jest.mock('react-native-safe-area-context', () => {
  const { View: NativeView } = require('react-native');
  return {
    SafeAreaView: ({ children, ...props }: { children?: React.ReactNode; [key: string]: unknown }) => (
      <NativeView {...props}>{children}</NativeView>
    ),
  };
});
jest.mock('@react-native-picker/picker', () => ({
  Picker: Object.assign(({ children, onValueChange }: { children: React.ReactNode; onValueChange?: (value: string) => void }) => {
    const options = React.Children.toArray(children) as Array<React.ReactElement<{ value?: string }>>;
    const option = options.find((child) => child.props?.value);
    return (
      <View>
        <TouchableOpacity
          testID={option?.props.value === '7' ? 'picker-employee' : 'picker-shift'}
          onPress={() => option?.props.value && onValueChange?.(option.props.value)}
        />
      </View>
    );
  }, {
    Item: () => null,
  }),
}));

describe('WorkScheduleScreenReadback', () => {
  const initialActor = useAuthStore.getState().user;

  beforeEach(() => {
    mockHrPermission = 'rw';
    useAuthStore.getState().user = initialActor;
    (require('react-native') as { SafeAreaView?: React.ComponentType }).SafeAreaView = View;
    const safeArea = require('react-native-safe-area-context') as { SafeAreaView?: React.ComponentType };
    safeArea.SafeAreaView = View;
    WorkScheduleScreen = require('../../../screens/hr/scheduling/WorkScheduleScreen').default;
    jest.clearAllMocks();
    (userApiClient.getUsers as jest.Mock).mockResolvedValue({ content: [{ id: 7, username: 'worker', fullName: '员工甲', factoryId: 'F006', isActive: true }] });
    (hrShiftApiClient.listAssignments as jest.Mock).mockResolvedValue([]);
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mockResolvedValue([
      { moduleCode: 'hr_employee', permissionModule: 'hr', roleDefaultAllowed: true, effectiveAllowed: true },
    ]);
    (hrShiftApiClient.listActiveShifts as jest.Mock).mockResolvedValue([
      { id: 's1', factoryId: 'F006', shiftCode: 'DAY', shiftName: '白班', startTime: '08:00', endTime: '17:00', isActive: true },
    ]);
  });

  it('does not let a retained old refresh start a spinner for the new actor', async () => {
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('scheduling.workSchedule.empty')).toBeTruthy());
    const oldRefresh = view.UNSAFE_getByType(ScrollView).props.refreshControl.props.onRefresh;
    await act(async () => {
      useAuthStore.getState().user = { ...initialActor, id: 10 } as typeof initialActor;
      view.rerender(<WorkScheduleScreen />);
    });
    await waitFor(() => expect(hrShiftApiClient.listAssignments).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(view.UNSAFE_getByType(ScrollView).props.refreshControl.props.refreshing).toBe(false));
    await act(async () => { oldRefresh(); });
    expect(view.UNSAFE_getByType(ScrollView).props.refreshControl.props.refreshing).toBe(false);
    expect(hrShiftApiClient.listAssignments).toHaveBeenCalledTimes(2);
    expect(hrShiftApiClient.assign).not.toHaveBeenCalled();
    expect(hrShiftApiClient.remove).not.toHaveBeenCalled();
  });

  it('reads the real HR endpoints and shows configuration state without writing', async () => {
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(hrShiftApiClient.listAssignments).toHaveBeenCalled());

    expect((hrShiftApiClient.listAssignments as jest.Mock).mock.calls[0][0]).toMatchObject({
      startDate: expect.any(String), endDate: expect.any(String), factoryId: 'F006',
    });
    expect(hrShiftApiClient.listActiveShifts).toHaveBeenCalledWith('F006');
    expect(hrShiftApiClient.getSelfModuleAccess).toHaveBeenCalledWith(9, 'F006');
    expect(hrShiftApiClient.assign).not.toHaveBeenCalled();
    expect(view.getByText('scheduling.workSchedule.empty')).toBeTruthy();
  });

  it('requires confirmation, sends one employee/date payload, and reads back after save', async () => {
    const alertSpy = jest.spyOn(AppDialog, 'appAlert').mockImplementation((...args) => {
      const buttons = args[2] as Array<{ text: string; onPress?: () => void }> | undefined;
      if (buttons?.[1]?.onPress) buttons[1].onPress();
    });
    (hrShiftApiClient.assign as jest.Mock).mockResolvedValue({ success: true, data: { id: 'a2' } });

    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('scheduling.workSchedule.empty')).toBeTruthy());
    fireEvent.press(view.getByLabelText('scheduling.workSchedule.addTitle'));
    fireEvent.press(view.getByTestId('picker-employee'));
    fireEvent.press(view.getByTestId('picker-shift'));
    fireEvent.press(view.getByText('common.save'));

    await waitFor(() => expect(hrShiftApiClient.assign).toHaveBeenCalledWith(
      expect.objectContaining({ userId: 7, workDate: expect.any(String), shiftId: 's1' }),
      'F006',
    ));
    await waitFor(() => expect((hrShiftApiClient.listAssignments as jest.Mock).mock.calls.length).toBeGreaterThan(1));
    expect(alertSpy).toHaveBeenCalled();
    alertSpy.mockRestore();
  });

  it('renders an explicit no-shift configuration state and does not invent times', async () => {
    (hrShiftApiClient.listActiveShifts as jest.Mock).mockResolvedValue([]);
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('scheduling.workSchedule.noShiftConfigured')).toBeTruthy());
    expect(view.queryByText('08:00')).toBeNull();
    expect(hrShiftApiClient.assign).not.toHaveBeenCalled();
  });

  it('keeps a false-success read as an explicit error and does not offer a write', async () => {
    (hrShiftApiClient.listAssignments as jest.Mock).mockRejectedValue(new Error('403 denied'));
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('403 denied')).toBeTruthy());
    expect(hrShiftApiClient.assign).not.toHaveBeenCalled();
  });

  it('ignores an old factory GET that resolves after the owner changes', async () => {
    const today = new Date();
    const currentDate = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
    let resolveOldAssignments: ((value: unknown[]) => void) | undefined;
    const oldAssignments = new Promise<unknown[]>((resolve) => { resolveOldAssignments = resolve; });
    (hrShiftApiClient.listAssignments as jest.Mock)
      .mockImplementationOnce(() => oldAssignments)
      .mockResolvedValueOnce([{ id: 'new-assignment', factoryId: 'F007', userId: 7, workDate: currentDate, shiftId: 's7', status: 'new-owner' }]);
    (hrShiftApiClient.listActiveShifts as jest.Mock)
      .mockImplementationOnce(async () => [{ id: 's1', factoryId: 'F006', shiftCode: 'DAY', shiftName: '旧班次', startTime: '08:00', endTime: '17:00', isActive: true }])
      .mockResolvedValueOnce([{ id: 's7', factoryId: 'F007', shiftCode: 'DAY', shiftName: '新班次', startTime: '09:00', endTime: '18:00', isActive: true }]);

    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(hrShiftApiClient.listAssignments).toHaveBeenCalledTimes(1));
    await act(async () => {
      useAuthStore.getState().user = { ...initialActor, id: 10, factoryId: 'F007' } as typeof initialActor;
      view.rerender(<WorkScheduleScreen />);
    });
    await waitFor(() => expect(hrShiftApiClient.listAssignments).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(view.getByText('new-owner')).toBeTruthy());

    await act(async () => {
      resolveOldAssignments?.([{ id: 'old-assignment', factoryId: 'F006', userId: 7, workDate: currentDate, shiftId: 's1', status: 'old-owner' }]);
      await new Promise((resolve) => setTimeout(resolve, 20));
    });
    expect(view.queryByText('old-owner')).toBeNull();
    expect(view.getByText('new-owner')).toBeTruthy();
  });

  it('requires the effective hr_employee self module grant in addition to hr write', async () => {
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mockResolvedValue([
      { moduleCode: 'hr_employee', permissionModule: 'hr', roleDefaultAllowed: true, override: 'DENY', effectiveAllowed: false },
    ]);
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('scheduling.workSchedule.permissionRequired')).toBeTruthy());
    expect(hrShiftApiClient.listAssignments).not.toHaveBeenCalled();
    expect(hrShiftApiClient.listActiveShifts).not.toHaveBeenCalled();
    expect(userApiClient.getUsers).not.toHaveBeenCalled();
    expect(view.queryByLabelText('scheduling.workSchedule.addTitle')).toBeNull();
  });

  it('loads with canonical HR read while denying write when the role is read-only', async () => {
    mockHrPermission = 'r';
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('scheduling.workSchedule.empty')).toBeTruthy());
    expect(hrShiftApiClient.listAssignments).toHaveBeenCalled();
    expect(hrShiftApiClient.listActiveShifts).toHaveBeenCalled();
    expect(view.queryByLabelText('scheduling.workSchedule.addTitle')).toBeNull();
  });

  it('filters foreign, inactive, and invalid employees and shifts before rendering write choices', async () => {
    (userApiClient.getUsers as jest.Mock).mockResolvedValue({ content: [
      { id: null, username: 'null-id', fullName: '无效员工', isActive: true, factoryId: 'F006' },
      { id: 8, username: 'foreign', fullName: '外厂员工', isActive: true, factoryId: 'F007' },
      { id: 10, username: 'missing-factory', fullName: '缺少工厂员工', isActive: true },
      { id: 9, username: 'inactive', fullName: '离职员工', isActive: false, factoryId: 'F006' },
    ] });
    (hrShiftApiClient.listActiveShifts as jest.Mock).mockResolvedValue([
      { id: 'foreign-shift', factoryId: 'F007', shiftCode: 'X', shiftName: '外厂班次', startTime: '08:00', endTime: '17:00', isActive: true },
      { id: 'disabled-shift', factoryId: 'F006', shiftCode: 'D', shiftName: '停用班次', startTime: '08:00', endTime: '17:00', isActive: false },
    ]);
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('scheduling.workSchedule.noShiftConfigured')).toBeTruthy());
    expect(view.queryByText('外厂员工')).toBeNull();
    expect(view.queryByText('缺少工厂员工')).toBeNull();
    expect(view.queryByText('离职员工')).toBeNull();
    expect((view.getByLabelText('scheduling.workSchedule.addTitle') as any).props.disabled).toBe(true);
  });

  it('uses one mutation for a double confirmation and rejects an old actor confirmation', async () => {
    let confirm: (() => void) | undefined;
    const alertSpy = jest.spyOn(AppDialog, 'appAlert').mockImplementation((...args) => {
      const buttons = args[2] as Array<{ onPress?: () => void }> | undefined;
      if (buttons?.[1]?.onPress) confirm = buttons[1].onPress;
    });
    (hrShiftApiClient.assign as jest.Mock).mockResolvedValue({ success: true, data: { id: 'a2' } });
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('scheduling.workSchedule.empty')).toBeTruthy());
    fireEvent.press(view.getByLabelText('scheduling.workSchedule.addTitle'));
    fireEvent.press(view.getByTestId('picker-employee'));
    fireEvent.press(view.getByTestId('picker-shift'));
    fireEvent.press(view.getByText('common.save'));
    fireEvent.press(view.getByText('common.save'));
    expect(alertSpy).toHaveBeenCalledTimes(1);
    confirm?.();
    await waitFor(() => expect(hrShiftApiClient.assign).toHaveBeenCalledTimes(1));

    (hrShiftApiClient.assign as jest.Mock).mockClear();
    const secondAlert = jest.spyOn(AppDialog, 'appAlert').mockImplementation((...args) => {
      const buttons = args[2] as Array<{ onPress?: () => void }> | undefined;
      if (buttons?.[1]?.onPress) confirm = buttons[1].onPress;
    });
    fireEvent.press(view.getByLabelText('scheduling.workSchedule.addTitle'));
    fireEvent.press(view.getByTestId('picker-employee'));
    fireEvent.press(view.getByTestId('picker-shift'));
    fireEvent.press(view.getByText('common.save'));
    const oldConfirm = confirm;
    useAuthStore.getState().user = { ...initialActor, id: 10, factoryId: 'F006' } as typeof initialActor;
    oldConfirm?.();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(hrShiftApiClient.assign).not.toHaveBeenCalled();
    secondAlert.mockRestore();
    alertSpy.mockRestore();
  });

  it('keeps the form open after a failed save', async () => {
    const alertMessages: string[] = [];
    const alertSpy = jest.spyOn(AppDialog, 'appAlert').mockImplementation((title, message, buttons) => {
      alertMessages.push(`${title}:${message}`);
      const confirm = buttons?.[1] as { onPress?: () => void } | undefined;
      if (confirm?.onPress && title === 'scheduling.workSchedule.confirmTitle') confirm.onPress();
    });
    (hrShiftApiClient.assign as jest.Mock).mockRejectedValue(new Error('write failed'));
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('scheduling.workSchedule.empty')).toBeTruthy());
    fireEvent.press(view.getByLabelText('scheduling.workSchedule.addTitle'));
    fireEvent.press(view.getByTestId('picker-employee'));
    fireEvent.press(view.getByTestId('picker-shift'));
    fireEvent.press(view.getByText('common.save'));

    await waitFor(() => expect(alertMessages.some((message) => message.includes('write failed'))).toBe(true));
    expect(view.getByText('common.save')).toBeTruthy();
    alertSpy.mockRestore();
  });

  it('does not close the form or show success when an old owner mutation settles', async () => {
    let resolveAssign: ((value: unknown) => void) | undefined;
    const assignPromise = new Promise<unknown>((resolve) => { resolveAssign = resolve; });
    const alertMessages: string[] = [];
    const alertSpy = jest.spyOn(AppDialog, 'appAlert').mockImplementation((title, message, buttons) => {
      alertMessages.push(`${title}:${message}`);
      const confirm = buttons?.[1] as { onPress?: () => void } | undefined;
      if (confirm?.onPress && title === 'scheduling.workSchedule.confirmTitle') confirm.onPress();
    });
    (hrShiftApiClient.assign as jest.Mock).mockReturnValue(assignPromise);
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('scheduling.workSchedule.empty')).toBeTruthy());
    fireEvent.press(view.getByLabelText('scheduling.workSchedule.addTitle'));
    fireEvent.press(view.getByTestId('picker-employee'));
    fireEvent.press(view.getByTestId('picker-shift'));
    fireEvent.press(view.getByText('common.save'));
    await waitFor(() => expect(hrShiftApiClient.assign).toHaveBeenCalledTimes(1));

    await act(async () => {
      useAuthStore.getState().user = { ...initialActor, id: 10, factoryId: 'F006' } as typeof initialActor;
      view.rerender(<WorkScheduleScreen />);
    });
    await waitFor(() => expect(view.getByText('scheduling.workSchedule.empty')).toBeTruthy());
    fireEvent.press(view.getByLabelText('scheduling.workSchedule.addTitle'));
    fireEvent.press(view.getByTestId('picker-employee'));
    fireEvent.press(view.getByTestId('picker-shift'));
    await act(async () => {
      resolveAssign?.({ success: true, data: { id: 'old-owner-assignment' } });
      await new Promise((resolve) => setTimeout(resolve, 20));
    });
    expect(view.getByText('common.save')).toBeTruthy();
    expect(alertMessages.some((message) => message.includes('common.success'))).toBe(false);
    alertSpy.mockRestore();
  });

  it('deletes a current assignment only after confirmation and reloads the same date', async () => {
    const now = new Date();
    const workDate = String(now.getFullYear()) + '-' + String(now.getMonth() + 1).padStart(2, '0') + '-' + String(now.getDate()).padStart(2, '0');
    (hrShiftApiClient.listAssignments as jest.Mock).mockResolvedValue([
      { id: 'a1', factoryId: 'F006', userId: 7, workDate, shiftId: 's1', status: '已排班' },
    ]);
    (hrShiftApiClient.remove as jest.Mock).mockResolvedValue({ success: true });
    const alertSpy = jest.spyOn(AppDialog, 'appAlert').mockImplementation((_title, _message, buttons) => {
      const deleteButton = buttons?.find((button) => button.text === 'common.delete');
      deleteButton?.onPress?.();
    });
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('已排班')).toBeTruthy());
    fireEvent.press(view.getByText('common.delete'));
    await waitFor(() => expect(hrShiftApiClient.remove).toHaveBeenCalledWith('a1', 'F006'));
    await waitFor(() => expect(hrShiftApiClient.listAssignments).toHaveBeenCalledTimes(2));
    alertSpy.mockRestore();
  });

  it('rejects a confirmed delete when the actor changes before the request', async () => {
    const now = new Date();
    const workDate = String(now.getFullYear()) + '-' + String(now.getMonth() + 1).padStart(2, '0') + '-' + String(now.getDate()).padStart(2, '0');
    (hrShiftApiClient.listAssignments as jest.Mock).mockResolvedValue([
      { id: 'a1', factoryId: 'F006', userId: 7, workDate, shiftId: 's1', status: '已排班' },
    ]);
    let confirmDelete: (() => void) | undefined;
    const alertSpy = jest.spyOn(AppDialog, 'appAlert').mockImplementation((_title, _message, buttons) => {
      confirmDelete = buttons?.find((button) => button.text === 'common.delete')?.onPress;
    });
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('已排班')).toBeTruthy());
    fireEvent.press(view.getByText('common.delete'));
    useAuthStore.getState().user = { ...initialActor, id: 10 } as typeof initialActor;
    view.rerender(<WorkScheduleScreen />);
    confirmDelete?.();
    await act(async () => { await Promise.resolve(); });
    expect(hrShiftApiClient.remove).not.toHaveBeenCalled();
    alertSpy.mockRestore();
  });

  it('rejects a confirmed delete when the assignment disappears on refresh', async () => {
    const now = new Date();
    const workDate = String(now.getFullYear()) + '-' + String(now.getMonth() + 1).padStart(2, '0') + '-' + String(now.getDate()).padStart(2, '0');
    const assignment = { id: 'a1', factoryId: 'F006', userId: 7, workDate, shiftId: 's1', status: '已排班' };
    (hrShiftApiClient.listAssignments as jest.Mock).mockResolvedValueOnce([assignment]).mockResolvedValueOnce([]);
    let confirmDelete: (() => void) | undefined;
    const alertSpy = jest.spyOn(AppDialog, 'appAlert').mockImplementation((_title, _message, buttons) => {
      confirmDelete = buttons?.find((button) => button.text === 'common.delete')?.onPress;
    });
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('已排班')).toBeTruthy());
    fireEvent.press(view.getByText('common.delete'));
    const scrollView = view.UNSAFE_getByType(ScrollView);
    await act(async () => {
      scrollView.props.refreshControl.props.onRefresh();
      await Promise.resolve();
    });
    await waitFor(() => expect(hrShiftApiClient.listAssignments).toHaveBeenCalledTimes(2));
    confirmDelete?.();
    await act(async () => { await Promise.resolve(); });
    expect(hrShiftApiClient.remove).not.toHaveBeenCalled();
    alertSpy.mockRestore();
  });

  it('rejects a confirmed delete when write permission is revoked during the final self check', async () => {
    const now = new Date();
    const workDate = String(now.getFullYear()) + '-' + String(now.getMonth() + 1).padStart(2, '0') + '-' + String(now.getDate()).padStart(2, '0');
    (hrShiftApiClient.listAssignments as jest.Mock).mockResolvedValue([
      { id: 'a1', factoryId: 'F006', userId: 7, workDate, shiftId: 's1', status: '已排班' },
    ]);
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock).mockReset();
    let resolveAccess: ((value: unknown[]) => void) | undefined;
    const accessPromise = new Promise<unknown[]>((resolve) => { resolveAccess = resolve; });
    (hrShiftApiClient.getSelfModuleAccess as jest.Mock)
      .mockResolvedValueOnce([{ moduleCode: 'hr_employee', permissionModule: 'hr', effectiveAllowed: true }])
      .mockReturnValueOnce(accessPromise);
    let confirmDelete: (() => void) | undefined;
    const alertSpy = jest.spyOn(AppDialog, 'appAlert').mockImplementation((_title, _message, buttons) => {
      confirmDelete = buttons?.find((button) => button.text === 'common.delete')?.onPress;
    });
    const view = render(<WorkScheduleScreen />);
    await waitFor(() => expect(view.getByText('已排班')).toBeTruthy());
    fireEvent.press(view.getByText('common.delete'));
    mockHrPermission = 'r';
    confirmDelete?.();
    await act(async () => {
      resolveAccess?.([{ moduleCode: 'hr_employee', permissionModule: 'hr', effectiveAllowed: true }]);
      await Promise.resolve();
    });
    expect(hrShiftApiClient.remove).not.toHaveBeenCalled();
    alertSpy.mockRestore();
  });
});
