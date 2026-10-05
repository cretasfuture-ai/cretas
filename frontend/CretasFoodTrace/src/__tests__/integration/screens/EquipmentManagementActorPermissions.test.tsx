import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { useFocusEffect } from '@react-navigation/native';
import { Menu } from 'react-native-paper';
import { appAlert } from '../../../components/ui/AppDialog';

import EquipmentManagementScreen from '../../../screens/processing/EquipmentManagementScreen';
import { equipmentApiClient } from '../../../services/api/equipmentApiClient';
import { userApiClient, type UserDTO } from '../../../services/api/userApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { User } from '../../../types/auth';

jest.mock('../../../components/ui/AppDialog', () => ({ appAlert: jest.fn() }));

const equipment = (factoryId: string, id = 7) => ({
  id,
  factoryId,
  name: `${factoryId}-设备`,
  code: `${factoryId}-EQ-${id}`,
  type: 'processing' as const,
  status: 'active' as const,
  location: '一号车间',
});

function actor(
  id: number,
  factoryId: string,
  equipmentPermission: 'r' | 'rw' | '-',
  hrPermission: 'r' | 'rw' | '-' = '-',
): User {
  return {
    id,
    username: `equipment-${id}`,
    email: `equipment-${id}@example.test`,
    createdAt: '',
    updatedAt: '',
    isActive: true,
    userType: 'factory',
    factoryId,
    factoryUser: {
      role: 'equipment_admin',
      factoryId,
      permissions: [],
      modulePermissions: { equipment: equipmentPermission, hr: hrPermission },
    },
  } as User;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve;
    reject = nextReject;
  });
  return { promise, resolve, reject };
}

describe('EquipmentManagement actor and permission isolation', () => {
  const focusEffect = useFocusEffect as jest.Mock;
  let getEquipments: jest.SpiedFunction<typeof equipmentApiClient.getEquipments>;
  let createEquipment: jest.SpiedFunction<typeof equipmentApiClient.createEquipment>;
  let updateEquipment: jest.SpiedFunction<typeof equipmentApiClient.updateEquipment>;
  let deleteEquipment: jest.SpiedFunction<typeof equipmentApiClient.deleteEquipment>;
  let updateEquipmentStatus: jest.SpiedFunction<typeof equipmentApiClient.updateEquipmentStatus>;
  let getUsers: jest.SpiedFunction<typeof userApiClient.getUsers>;

  beforeEach(() => {
    (appAlert as jest.Mock).mockClear();
    focusEffect.mockImplementation((callback: () => void) => {
      React.useEffect(() => {
        callback();
      }, [callback]);
    });
    getEquipments = jest.spyOn(equipmentApiClient, 'getEquipments').mockResolvedValue({
      content: [], totalElements: 0, totalPages: 0,
    });
    createEquipment = jest.spyOn(equipmentApiClient, 'createEquipment').mockResolvedValue(equipment('F006'));
    updateEquipment = jest.spyOn(equipmentApiClient, 'updateEquipment').mockResolvedValue(equipment('F006'));
    deleteEquipment = jest.spyOn(equipmentApiClient, 'deleteEquipment').mockResolvedValue(undefined);
    updateEquipmentStatus = jest.spyOn(equipmentApiClient, 'updateEquipmentStatus').mockResolvedValue(equipment('F006'));
    getUsers = jest.spyOn(userApiClient, 'getUsers').mockResolvedValue({
      content: [], totalElements: 0, totalPages: 0, size: 100, number: 0,
    });
    useAuthStore.setState({ user: actor(11, 'F006', 'rw'), isAuthenticated: true });
  });

  afterEach(() => {
    jest.restoreAllMocks();
    focusEffect.mockReset();
    useAuthStore.setState({ user: null, isAuthenticated: false });
  });

  it('keeps read-only rows visible while hiding every write entry point', async () => {
    getEquipments.mockResolvedValue({
      content: [equipment('F006'), equipment('F007', 8)], totalElements: 2, totalPages: 1,
    });
    useAuthStore.setState({ user: actor(11, 'F006', 'r'), isAuthenticated: true });

    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());
    expect(view.queryByText('F007-设备')).toBeNull();
    expect(view.queryByTestId('equipment-management-create-button')).toBeNull();
    expect(view.queryByTestId('equipment-management-fab')).toBeNull();
    expect(view.queryByTestId('equipment-management-edit-7')).toBeNull();
    expect(view.queryByTestId('equipment-management-delete-7')).toBeNull();
    expect(view.queryByTestId('equipment-management-status-7')).toBeNull();
    expect(createEquipment).not.toHaveBeenCalled();
  });

  it('does not request HR candidates without live HR read and keeps create unassigned', async () => {
    getEquipments.mockResolvedValue({
      content: [equipment('F006')], totalElements: 1, totalPages: 1,
    });
    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());

    fireEvent.press(view.getByTestId('equipment-management-fab'));
    await waitFor(() => expect(view.getByTestId('equipment-management-operator-hr-locked')).toBeTruthy());
    expect(view.queryByTestId('equipment-management-operator-picker')).toBeNull();
    expect(getUsers).not.toHaveBeenCalled();
    fireEvent.changeText(view.getByTestId('equipment-management-create-name'), '新设备');
    await act(async () => { fireEvent.press(view.getByTestId('equipment-management-submit-create')); });
    await waitFor(() => expect(createEquipment).toHaveBeenCalledWith(
      expect.objectContaining({ operatorId: undefined }),
      'F006',
    ));
  });

  it('preserves an existing operator binding when saving an edit without HR read', async () => {
    getEquipments.mockResolvedValue({
      content: [{ ...equipment('F006'), operatorId: 99 }], totalElements: 1, totalPages: 1,
    });
    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());

    fireEvent.press(view.getByTestId('equipment-management-edit-7'));
    await waitFor(() => expect(view.getByTestId('equipment-management-operator-hr-locked')).toBeTruthy());
    expect(view.queryByTestId('equipment-management-operator-picker')).toBeNull();
    expect(getUsers).not.toHaveBeenCalled();
    await act(async () => { fireEvent.press(view.getByTestId('equipment-management-submit-edit')); });
    await waitFor(() => expect(updateEquipment).toHaveBeenCalledWith(
      7,
      expect.objectContaining({ operatorId: 99 }),
      'F006',
    ));
    expect(getUsers).not.toHaveBeenCalled();
    expect(createEquipment).not.toHaveBeenCalled();
  });

  it('does not display a late GET from the previous actor and reloads the current factory', async () => {
    const first = deferred<any>();
    const second = deferred<any>();
    getEquipments.mockReset()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);

    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(getEquipments).toHaveBeenCalledWith(expect.objectContaining({ factoryId: 'F006' })));
    await act(async () => {
      useAuthStore.setState({ user: actor(22, 'F007', 'rw'), isAuthenticated: true });
    });
    await waitFor(() => expect(getEquipments).toHaveBeenCalledWith(expect.objectContaining({ factoryId: 'F007' })));
    expect(view.queryByText('F006-设备')).toBeNull();

    await act(async () => { first.resolve({ content: [equipment('F006')] }); });
    expect(view.queryByText('F006-设备')).toBeNull();
    await act(async () => { second.resolve({ content: [equipment('F007')] }); });
    await waitFor(() => expect(view.getByText('F007-设备')).toBeTruthy());
  });

  it('invalidates an old candidate callback when HR read is revoked', async () => {
    getEquipments.mockResolvedValue({
      content: [equipment('F006')], totalElements: 1, totalPages: 1,
    });
    getUsers.mockResolvedValue({
      content: [{ id: 99, realName: 'F006操作员', position: '操作员', factoryId: 'F006' } as UserDTO & { factoryId: string }],
      totalElements: 1, totalPages: 1, size: 100, number: 0,
    });
    useAuthStore.setState({ user: actor(11, 'F006', 'rw', 'r'), isAuthenticated: true });
    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());
    fireEvent.press(view.getByTestId('equipment-management-fab'));
    await waitFor(() => expect(view.getByTestId('equipment-management-operator-99')).toBeTruthy());
    const oldCandidateCallback = view.getByTestId('equipment-management-operator-99').props.onPress;

    await act(async () => {
      useAuthStore.setState({ user: actor(11, 'F006', 'rw'), isAuthenticated: true });
      oldCandidateCallback();
    });
    fireEvent.press(view.getByTestId('equipment-management-fab'));
    await waitFor(() => expect(view.getByTestId('equipment-management-operator-hr-locked')).toBeTruthy());
    expect(view.queryByTestId('equipment-management-operator-99')).toBeNull();
    fireEvent.changeText(view.getByTestId('equipment-management-create-name'), '新设备');
    await act(async () => { fireEvent.press(view.getByTestId('equipment-management-submit-create')); });
    await waitFor(() => expect(createEquipment).toHaveBeenCalledWith(
      expect.objectContaining({ operatorId: undefined }),
      'F006',
    ));
  });

  it('shows an explicit retry when HR candidate loading fails', async () => {
    getEquipments.mockResolvedValue({
      content: [equipment('F006')], totalElements: 1, totalPages: 1,
    });
    getUsers.mockRejectedValueOnce(new Error('HR forbidden')).mockResolvedValueOnce({
      content: [{ id: 99, realName: 'F006操作员', position: '操作员', factoryId: 'F006' } as UserDTO & { factoryId: string }],
      totalElements: 1, totalPages: 1, size: 100, number: 0,
    });
    useAuthStore.setState({ user: actor(11, 'F006', 'rw', 'r'), isAuthenticated: true });
    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());
    fireEvent.press(view.getByTestId('equipment-management-fab'));
    await waitFor(() => expect(view.getByTestId('equipment-management-operator-load-error')).toBeTruthy());
    expect(getUsers).toHaveBeenCalledTimes(1);
    fireEvent.press(view.getByTestId('equipment-management-operator-retry'));
    await waitFor(() => expect(view.getByTestId('equipment-management-operator-99')).toBeTruthy());
    expect(getUsers).toHaveBeenCalledTimes(2);
  });

  it('blocks old create, update, delete, and status callbacks after actor/factory change', async () => {
    getEquipments.mockResolvedValue({
      content: [equipment('F006')], totalElements: 1, totalPages: 1,
    });
    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());

    fireEvent.press(view.getByTestId('equipment-management-fab'));
    await waitFor(() => expect(view.getByTestId('equipment-management-submit-create')).toBeTruthy());
    const oldCreateCallback = view.getByTestId('equipment-management-submit-create').props.onPress;
    await act(async () => {
      useAuthStore.setState({ user: actor(22, 'F007', 'rw'), isAuthenticated: true });
    });
    await act(async () => { oldCreateCallback(); });
    expect(createEquipment).not.toHaveBeenCalled();

    await act(async () => {
      useAuthStore.setState({ user: actor(11, 'F006', 'rw'), isAuthenticated: true });
    });
    getEquipments.mockResolvedValue({
      content: [equipment('F006')], totalElements: 1, totalPages: 1,
    });
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());

    fireEvent.press(view.getByTestId('equipment-management-edit-7'));
    await waitFor(() => expect(view.getByTestId('equipment-management-submit-edit')).toBeTruthy());
    const oldUpdateCallback = view.getByTestId('equipment-management-submit-edit').props.onPress;
    await act(async () => {
      useAuthStore.setState({ user: actor(22, 'F007', 'rw'), isAuthenticated: true });
    });
    await act(async () => { oldUpdateCallback(); });

    await act(async () => {
      useAuthStore.setState({ user: actor(11, 'F006', 'rw'), isAuthenticated: true });
    });
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());
    fireEvent.press(view.getByTestId('equipment-management-delete-7'));
    await waitFor(() => expect(view.getByTestId('equipment-management-confirm-delete')).toBeTruthy());
    const oldDeleteCallback = view.getByTestId('equipment-management-confirm-delete').props.onPress;
    await act(async () => {
      useAuthStore.setState({ user: actor(22, 'F007', 'rw'), isAuthenticated: true });
    });
    await act(async () => { oldDeleteCallback(); });

    await act(async () => {
      useAuthStore.setState({ user: actor(11, 'F006', 'rw'), isAuthenticated: true });
    });
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());
    const statusMenu = view.UNSAFE_getAllByType(Menu)[0];
    fireEvent.press(statusMenu.props.anchor);
    await waitFor(() => expect(view.getByTestId('equipment-management-status-maintenance')).toBeTruthy());
    const oldStatusCallback = view.getByTestId('equipment-management-status-maintenance').props.onPress;
    await act(async () => {
      useAuthStore.setState({ user: actor(22, 'F007', 'rw'), isAuthenticated: true });
    });
    await act(async () => { oldStatusCallback(); });

    expect(updateEquipment).not.toHaveBeenCalled();
    expect(deleteEquipment).not.toHaveBeenCalled();
    expect(updateEquipmentStatus).not.toHaveBeenCalled();
    expect(createEquipment).not.toHaveBeenCalled();
    expect(appAlert).not.toHaveBeenCalled();
  });

  it('removes old dialog name, operator, and confirmation on the first actor-switch render', async () => {
    const f006Equipment = {
      ...equipment('F006'),
      operatorId: 99,
      operatorName: 'F006操作员',
    };
    getEquipments.mockResolvedValue({
      content: [f006Equipment], totalElements: 1, totalPages: 1,
    });
    getUsers.mockResolvedValue({
      content: [
        { id: 99, realName: 'F006操作员', position: '操作员', factoryId: 'F006' } as UserDTO & { factoryId: string },
        { id: 100, realName: 'F007操作员', position: '操作员', factoryId: 'F007' } as UserDTO & { factoryId: string },
      ],
      totalElements: 2, totalPages: 1, size: 100, number: 0,
    });
    useAuthStore.setState({ user: actor(11, 'F006', 'rw', 'r'), isAuthenticated: true });
    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());

    fireEvent.press(view.getByTestId('equipment-management-edit-7'));
    await waitFor(() => expect(view.getAllByDisplayValue('F006-设备').length).toBeGreaterThan(0));
    expect(view.getAllByText('F006操作员').length).toBeGreaterThan(0);
    expect(view.queryAllByText('F007操作员')).toHaveLength(0);
    const oldEditCallback = view.getByTestId('equipment-management-submit-edit').props.onPress;
    await act(async () => {
      useAuthStore.setState({ user: actor(22, 'F007', 'rw'), isAuthenticated: true });
    });
    expect(view.queryAllByDisplayValue('F006-设备')).toHaveLength(0);
    expect(view.queryAllByText('F006操作员')).toHaveLength(0);
    expect(view.queryByTestId('equipment-management-submit-edit')).toBeNull();
    expect(view.queryByTestId('equipment-management-confirm-delete')).toBeNull();
    await act(async () => { oldEditCallback(); });
    expect(updateEquipment).not.toHaveBeenCalled();

    await act(async () => {
      useAuthStore.setState({ user: actor(11, 'F006', 'rw', 'r'), isAuthenticated: true });
    });
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());
    fireEvent.press(view.getByTestId('equipment-management-delete-7'));
    await waitFor(() => expect(view.getByTestId('equipment-management-confirm-delete')).toBeTruthy());
    await act(async () => {
      useAuthStore.setState({ user: actor(22, 'F007', 'rw'), isAuthenticated: true });
    });
    expect(view.queryByTestId('equipment-management-confirm-delete')).toBeNull();
  });

  it('sends legal F006 create and update requests with the live factory', async () => {
    getEquipments.mockResolvedValue({
      content: [equipment('F006')], totalElements: 1, totalPages: 1,
    });
    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());

    fireEvent.press(view.getByTestId('equipment-management-fab'));
    await waitFor(() => expect(view.getByTestId('equipment-management-submit-create')).toBeTruthy());
    fireEvent.changeText(view.getByTestId('equipment-management-create-name'), '新设备');
    await act(async () => { fireEvent.press(view.getByTestId('equipment-management-submit-create')); });
    await waitFor(() => expect(createEquipment).toHaveBeenCalledWith(expect.any(Object), 'F006'));

    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());
    fireEvent.press(view.getByTestId('equipment-management-edit-7'));
    await waitFor(() => expect(view.getByTestId('equipment-management-submit-edit')).toBeTruthy());
    await act(async () => { fireEvent.press(view.getByTestId('equipment-management-submit-edit')); });
    await waitFor(() => expect(updateEquipment).toHaveBeenCalledWith(7, expect.any(Object), 'F006'));
  });

  it('rejects a blank or whitespace-only name without sending a create request', async () => {
    getEquipments.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 });
    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(view.getByTestId('equipment-management-fab')).toBeTruthy());

    fireEvent.press(view.getByTestId('equipment-management-fab'));
    await waitFor(() => expect(view.getByTestId('equipment-management-submit-create')).toBeTruthy());
    fireEvent.changeText(view.getByTestId('equipment-management-create-name'), '   ');
    await act(async () => { fireEvent.press(view.getByTestId('equipment-management-submit-create')); });

    await waitFor(() => expect(view.getByTestId('equipment-management-create-name-error')).toBeTruthy());
    expect(createEquipment).not.toHaveBeenCalled();
    expect(appAlert).not.toHaveBeenCalled();
  });

  it('keeps create inputs open and reports a real mutation failure', async () => {
    getEquipments.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 });
    createEquipment.mockRejectedValueOnce(new Error('设备名称已存在'));
    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(view.getByTestId('equipment-management-fab')).toBeTruthy());

    fireEvent.press(view.getByTestId('equipment-management-fab'));
    await waitFor(() => expect(view.getByTestId('equipment-management-submit-create')).toBeTruthy());
    fireEvent.changeText(view.getByTestId('equipment-management-create-name'), '保留输入的设备');
    await act(async () => { fireEvent.press(view.getByTestId('equipment-management-submit-create')); });

    await waitFor(() => expect(appAlert).toHaveBeenCalledWith(expect.any(String), '设备名称已存在'));
    expect(view.getByTestId('equipment-management-submit-create')).toBeTruthy();
    expect(view.getByTestId('equipment-management-create-name').props.value).toBe('保留输入的设备');
  });

  it('ignores a late mutation failure after the actor changes', async () => {
    getEquipments.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0 });
    const pending = deferred<ReturnType<typeof equipment>>();
    createEquipment.mockReturnValueOnce(pending.promise);
    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(view.getByTestId('equipment-management-fab')).toBeTruthy());

    fireEvent.press(view.getByTestId('equipment-management-fab'));
    await waitFor(() => expect(view.getByTestId('equipment-management-submit-create')).toBeTruthy());
    fireEvent.changeText(view.getByTestId('equipment-management-create-name'), '旧账号设备');
    await act(async () => { fireEvent.press(view.getByTestId('equipment-management-submit-create')); await Promise.resolve(); });

    act(() => { useAuthStore.setState({ user: actor(22, 'F007', 'rw'), isAuthenticated: true }); });
    await act(async () => { pending.reject(new Error('旧账号失败')); await pending.promise.catch(() => undefined); });

    expect(appAlert).not.toHaveBeenCalled();
    expect(view.queryByTestId('equipment-management-create-name')).toBeNull();
  });

  it('reports update, delete, and status failures while preserving their controls', async () => {
    getEquipments.mockResolvedValue({ content: [equipment('F006')], totalElements: 1, totalPages: 1 });
    const view = render(<EquipmentManagementScreen />);
    await waitFor(() => expect(view.getByText('F006-设备')).toBeTruthy());

    updateEquipment.mockRejectedValueOnce(new Error('更新失败'));
    fireEvent.press(view.getByTestId('equipment-management-edit-7'));
    await waitFor(() => expect(view.getByTestId('equipment-management-submit-edit')).toBeTruthy());
    await act(async () => { fireEvent.press(view.getByTestId('equipment-management-submit-edit')); });
    await waitFor(() => expect(appAlert).toHaveBeenCalledWith(expect.any(String), '更新失败'));
    expect(view.getByTestId('equipment-management-submit-edit')).toBeTruthy();

    deleteEquipment.mockRejectedValueOnce(new Error('删除失败'));
    fireEvent.press(view.getByTestId('equipment-management-delete-7'));
    await waitFor(() => expect(view.getByTestId('equipment-management-confirm-delete')).toBeTruthy());
    await act(async () => { fireEvent.press(view.getByTestId('equipment-management-confirm-delete')); });
    await waitFor(() => expect(appAlert).toHaveBeenCalledWith(expect.any(String), '删除失败'));
    expect(view.getByTestId('equipment-management-confirm-delete')).toBeTruthy();

    updateEquipmentStatus.mockRejectedValueOnce(new Error('状态更新失败'));
    const statusMenu = view.UNSAFE_getAllByType(Menu)[0];
    fireEvent.press(statusMenu.props.anchor);
    await waitFor(() => expect(view.getByTestId('equipment-management-status-maintenance')).toBeTruthy());
    await act(async () => { fireEvent.press(view.getByTestId('equipment-management-status-maintenance')); });
    await waitFor(() => expect(appAlert).toHaveBeenCalledWith(expect.any(String), '状态更新失败'));
    expect(view.getByTestId('equipment-management-status-maintenance')).toBeTruthy();
  });
});
