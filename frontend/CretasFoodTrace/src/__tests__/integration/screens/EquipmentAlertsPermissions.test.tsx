import React from 'react';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';

const mockNavigate = jest.fn();

jest.mock('@react-navigation/native', () => ({
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({ navigate: mockNavigate, goBack: jest.fn(), setOptions: jest.fn(), addListener: jest.fn() }),
  useRoute: () => ({ params: {} }),
  useFocusEffect: (effect: () => void | (() => void)) => React.useEffect(effect, [effect]),
}));

jest.mock('../../../components/ui/AppDialog', () => ({
  appAlert: jest.fn(),
}));

import EquipmentAlertsScreen from '../../../screens/processing/EquipmentAlertsScreen';
import { appAlert } from '../../../components/ui/AppDialog';
import type { AppDialogButton } from '../../../components/ui/AppDialog';
import { alertApiClient, AlertDTO } from '../../../services/api/alertApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { User } from '../../../types/auth';

describe('EquipmentAlertsScreen permissions and actor boundaries', () => {
  beforeEach(() => mockNavigate.mockClear());
  afterEach(() => {
    cleanup();
    jest.restoreAllMocks();
    useAuthStore.setState({ user: null, isAuthenticated: false });
  });

  it('shows alerts to equipment read users but hides acknowledge and resolve writes', async () => {
    const getAlerts = jest.spyOn(alertApiClient, 'getEquipmentAlerts').mockResolvedValue(alertPage());
    const acknowledge = jest.spyOn(alertApiClient, 'acknowledgeAlert');
    const resolve = jest.spyOn(alertApiClient, 'resolveAlert');
    useAuthStore.setState({ user: equipmentUser('r'), isAuthenticated: true });
    const screen = render(<EquipmentAlertsScreen />);

    await waitFor(() => expect(getAlerts).toHaveBeenCalledWith(expect.objectContaining({ factoryId: 'F006' })));
    expect(screen.getByText('冷却机组压力异常')).toBeTruthy();
    expect(screen.UNSAFE_queryAllByProps({ icon: 'check' })).toHaveLength(0);
    expect(screen.UNSAFE_queryAllByProps({ icon: 'check-all' })).toHaveLength(0);
    expect(acknowledge).not.toHaveBeenCalled();
    expect(resolve).not.toHaveBeenCalled();
  });

  it('acknowledges and resolves with current writable actor and factory', async () => {
    const getAlerts = jest.spyOn(alertApiClient, 'getEquipmentAlerts').mockResolvedValue(alertPage());
    const acknowledge = jest.spyOn(alertApiClient, 'acknowledgeAlert').mockResolvedValue(successResponse());
    const resolve = jest.spyOn(alertApiClient, 'resolveAlert').mockResolvedValue(successResponse());
    useAuthStore.setState({ user: equipmentUser('rw'), isAuthenticated: true });
    const screen = render(<EquipmentAlertsScreen />);

    await waitFor(() => expect(getAlerts).toHaveBeenCalled());
    await act(async () => {
      fireEvent.press(screen.UNSAFE_getByProps({ icon: 'check' }));
      await Promise.resolve();
    });
    expect(acknowledge).toHaveBeenCalledWith({ factoryId: 'F006', alertId: 'ALERT-1', acknowledgedBy: 11 });

    await act(async () => {
      fireEvent.press(screen.UNSAFE_getByProps({ icon: 'check-all' }));
    });
    const confirm = latestConfirmButton();
    expect(confirm).toBeDefined();
    await act(async () => {
      confirm?.onPress?.();
      await Promise.resolve();
    });
    expect(resolve).toHaveBeenCalledWith({ factoryId: 'F006', alertId: 'ALERT-1', resolvedBy: 11 });
  });

  it('drops a confirmation captured before write permission is revoked', async () => {
    const getAlerts = jest.spyOn(alertApiClient, 'getEquipmentAlerts').mockResolvedValue(alertPage());
    const resolve = jest.spyOn(alertApiClient, 'resolveAlert').mockResolvedValue(successResponse());
    useAuthStore.setState({ user: equipmentUser('rw'), isAuthenticated: true });
    const screen = render(<EquipmentAlertsScreen />);
    await waitFor(() => expect(getAlerts).toHaveBeenCalled());

    await act(async () => {
      fireEvent.press(screen.UNSAFE_getByProps({ icon: 'check-all' }));
    });
    const confirm = latestConfirmButton();
    await act(async () => {
      useAuthStore.setState({ user: equipmentUser('r'), isAuthenticated: true });
      confirm?.onPress?.();
      await Promise.resolve();
    });

    expect(resolve).not.toHaveBeenCalled();
  });

  it('rejects a retained row press immediately after read permission is revoked', async () => {
    jest.spyOn(alertApiClient, 'getEquipmentAlerts').mockResolvedValue(alertPage('F006', [{ ...alertDto('F006'), equipmentId: '42' }]));
    useAuthStore.setState({ user: equipmentUser('r'), isAuthenticated: true });
    const screen = render(<EquipmentAlertsScreen />);
    await waitFor(() => expect(screen.getByText('冷却机组压力异常')).toBeTruthy());
    const text = screen.getByText('冷却机组压力异常');
    let row = text.parent;
    while (row && !row.props.onPress) row = row.parent;
    expect(row?.props.onPress).toEqual(expect.any(Function));
    const retainedPress = row!.props.onPress;
    act(() => retainedPress());
    expect(mockNavigate).toHaveBeenCalledWith('EquipmentDetail', { equipmentId: 42 });
    mockNavigate.mockClear();
    act(() => {
      useAuthStore.setState({ user: equipmentUser('-'), isAuthenticated: true });
      retainedPress();
    });
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('clears old rows and rejects a pending GET when read permission is revoked', async () => {
    const pendingRows = deferred<ReturnType<typeof alertPage>>();
    let requestCount = 0;
    const getAlerts = jest.spyOn(alertApiClient, 'getEquipmentAlerts').mockImplementation(() => {
      requestCount += 1;
      return requestCount === 1 ? Promise.resolve(alertPage()) : pendingRows.promise;
    });
    useAuthStore.setState({ user: equipmentUser('rw'), isAuthenticated: true });
    const screen = render(<EquipmentAlertsScreen />);
    await waitFor(() => expect(getAlerts).toHaveBeenCalledTimes(1));
    expect(screen.getByText('冷却机组压力异常')).toBeTruthy();

    await act(async () => {
      fireEvent.press(screen.getByText('equipmentAlerts.filters.acknowledged'));
      await Promise.resolve();
    });
    await waitFor(() => expect(getAlerts).toHaveBeenCalledTimes(2));
    await act(async () => {
      useAuthStore.setState({ user: equipmentUser('-'), isAuthenticated: true });
    });
    expect(screen.queryByText('冷却机组压力异常')).toBeNull();

    await act(async () => {
      pendingRows.resolve(alertPage('F006'));
      await pendingRows.promise;
    });
    expect(screen.queryByText('冷却机组压力异常')).toBeNull();
  });

  it('filters mixed-factory responses before displaying or writing rows', async () => {
    const getAlerts = jest.spyOn(alertApiClient, 'getEquipmentAlerts').mockResolvedValue(
      alertPage('F007', [
        alertDto('F006', 'ALERT-F006', '跨工厂异常'),
        alertDto('F007', 'ALERT-F007', 'F007合法告警'),
      ]),
    );
    const acknowledge = jest.spyOn(alertApiClient, 'acknowledgeAlert').mockResolvedValue(successResponse());
    useAuthStore.setState({ user: equipmentUser('rw', 'F007'), isAuthenticated: true });
    const screen = render(<EquipmentAlertsScreen />);

    await waitFor(() => expect(getAlerts).toHaveBeenCalledWith(expect.objectContaining({ factoryId: 'F007' })));
    expect(screen.queryByText('跨工厂异常')).toBeNull();
    expect(screen.getByText('F007合法告警')).toBeTruthy();
    await act(async () => {
      fireEvent.press(screen.UNSAFE_getByProps({ icon: 'check' }));
      await Promise.resolve();
    });
    expect(acknowledge).toHaveBeenCalledWith({ factoryId: 'F007', alertId: 'ALERT-F007', acknowledgedBy: 11 });
  });

  it('drops F006 late rows and old confirmation after switching to F007', async () => {
    const firstRows = deferred<ReturnType<typeof alertPage>>();
    const secondRows = deferred<ReturnType<typeof alertPage>>();
    let requestCount = 0;
    const getAlerts = jest.spyOn(alertApiClient, 'getEquipmentAlerts').mockImplementation(() => {
      requestCount += 1;
      return requestCount === 1 ? firstRows.promise : secondRows.promise;
    });
    const resolve = jest.spyOn(alertApiClient, 'resolveAlert').mockResolvedValue(successResponse());
    useAuthStore.setState({ user: equipmentUser('rw', 'F006'), isAuthenticated: true });
    const screen = render(<EquipmentAlertsScreen />);
    await waitFor(() => expect(getAlerts).toHaveBeenCalledTimes(1));
    await act(async () => {
      firstRows.resolve(alertPage('F006'));
      await firstRows.promise;
    });
    fireEvent.press(screen.UNSAFE_getByProps({ icon: 'check-all' }));
    const oldConfirm = latestConfirmButton();

    await act(async () => {
      useAuthStore.setState({ user: equipmentUser('rw', 'F007'), isAuthenticated: true });
    });
    await waitFor(() => expect(getAlerts).toHaveBeenCalledTimes(2));
    await act(async () => {
      secondRows.resolve(alertPage('F007', []));
      await secondRows.promise;
      oldConfirm?.onPress?.();
      await Promise.resolve();
    });

    expect(screen.queryByText('冷却机组压力异常')).toBeNull();
    expect(resolve).not.toHaveBeenCalled();
  });
});

function latestConfirmButton(): AppDialogButton | undefined {
  const calls = jest.mocked(appAlert).mock.calls;
  return calls
    .slice()
    .reverse()
    .flatMap((call) => call[2] ?? [])
    .find((button) => button.text === '确定' || button.text === 'common.confirm');
}

function equipmentUser(equipment: 'r' | 'rw' | '-', factoryId = 'F006'): User {
  return {
    id: 11,
    username: 'equipment-admin',
    email: 'equipment@example.test',
    createdAt: '',
    updatedAt: '',
    isActive: true,
    userType: 'factory',
    factoryId,
    factoryUser: {
      role: 'equipment_admin',
      factoryId,
      permissions: [],
      modulePermissions: { equipment },
    },
  } as User;
}

function alertPage(factoryId = 'F006', content: AlertDTO[] = [alertDto(factoryId)]): {
  success: boolean;
  code: number;
  data: { content: AlertDTO[]; totalElements: number; totalPages: number; currentPage: number; pageSize: number };
} {
  return {
    success: true,
    code: 200,
    data: { content, totalElements: content.length, totalPages: content.length ? 1 : 0, currentPage: 1, pageSize: 100 },
  };
}

function alertDto(factoryId: string, id = 'ALERT-1', message = '冷却机组压力异常'): AlertDTO {
  return {
    id, factoryId, equipmentId: 'EQ-1', equipmentName: '冷却机组',
    alertType: 'PRESSURE', level: 'CRITICAL', status: 'ACTIVE',
    message, details: '压力超过安全阈值', triggeredAt: '2026-10-04T00:00:00Z',
  };
}

function successResponse() {
  return { success: true, code: 200, data: { id: 'ALERT-1', status: 'ACKNOWLEDGED', resolvedAt: '', resolvedBy: 11, acknowledgedAt: '', acknowledgedBy: 11 } };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((nextResolve) => { resolve = nextResolve; });
  return { promise, resolve };
}
