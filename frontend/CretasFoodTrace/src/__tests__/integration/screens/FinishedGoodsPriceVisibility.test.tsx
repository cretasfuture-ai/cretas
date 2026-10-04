import React from 'react';
import { act, cleanup, render, waitFor } from '@testing-library/react-native';

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ dispatch: mockDispatch, goBack: jest.fn() }),
  CommonActions: { navigate: jest.fn() },
}));
jest.mock('../../../components/list', () => ({ RowActionBottomSheet: jest.fn(() => null) }));
jest.mock('../../../hooks/useRowActions', () => ({ useRowActions: jest.fn(() => []) }));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));
jest.mock('../../../services/api/salesApiClient', () => ({
  salesApiClient: { getFinishedGoods: jest.fn() },
}));
jest.mock('react-native-paper', () => {
  const Native = require('react-native') as typeof import('react-native');
  const Box = ({ children, ...props }: { children?: React.ReactNode; [key: string]: unknown }) => <Native.View {...props}>{children}</Native.View>;
  return {
    Text: Native.Text,
    Appbar: { Header: Box, BackAction: Box, Content: Box, Action: Box },
    Card: Object.assign(Box, { Content: Box }),
    Chip: Box, ActivityIndicator: Box, ProgressBar: Box,
  };
});

import FinishedGoodsListScreen from '../../../screens/factory-admin/inventory/FinishedGoodsListScreen';
import { salesApiClient, type FinishedGoodsBatch } from '../../../services/api/salesApiClient';
import { useAuthStore } from '../../../store/authStore';
import { Alert } from 'react-native';
import { Card } from 'react-native-paper';
import { RowActionBottomSheet } from '../../../components/list';
import { useRowActions, type RowContext } from '../../../hooks/useRowActions';
import type { FactoryRole, FactoryUser } from '../../../types/auth';

const getFinishedGoods = jest.mocked(salesApiClient.getFinishedGoods);
const mockDispatch = jest.fn();
function actor(role: FactoryRole, factoryId = 'F006', id = 77): FactoryUser {
  return {
    id, username: role, email: 'price-test@example.test', fullName: role,
    createdAt: '', updatedAt: '', isActive: true, userType: 'factory', factoryId: 'stale-top-level-factory',
    factoryUser: { factoryId, role, permissions: [], modulePermissions: { inventory: 'r' } },
  };
}
function result(unitCost: number | undefined = 12.67, factoryId = 'F006', productName = '测试成品') {
  const batch: FinishedGoodsBatch = {
    id: 'FG-1', factoryId, batchNumber: 'FG-TEST-001', productTypeId: 'product-1',
    productName, producedQuantity: 8, availableQuantity: 8, reservedQuantity: 0,
    unit: '盒', productionDate: '2026-10-01', createdAt: '2026-10-01', unitCost, storageLocation: 'DK01',
  };
  return { success: true, data: { content: [batch], totalElements: 1, totalPages: 1, number: 1, size: 100 } };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
beforeEach(() => {
  jest.clearAllMocks();
  getFinishedGoods.mockResolvedValue(result());
  useAuthStore.setState({ user: actor('viewer'), isAuthenticated: true });
});
afterEach(() => {
  cleanup();
  useAuthStore.setState({ user: null, isAuthenticated: false });
});

describe('FinishedGoodsListScreen canonical price visibility', () => {
  it.each(['viewer', 'warehouse_worker'] as FactoryRole[])('hides costs from a price-bearing response for %s while retaining stock and location', async role => {
    useAuthStore.setState({ user: actor(role) });
    const view = render(<FinishedGoodsListScreen />);
    await waitFor(() => expect(view.getByText('测试成品')).toBeTruthy());
    expect(view.queryByText(/finishedGoods.unitCost/)).toBeNull();
    expect(view.queryByText(/¥12\.67/)).toBeNull();
    expect(view.getAllByText('8 盒')).toHaveLength(2);
    await waitFor(() => expect(view.getByText('finishedGoods.storageLocation: DK01')).toBeTruthy());
  });

  it.each([['finance_manager', 12.67, '12.67'], ['factory_super_admin', 0, '0']] as const)('preserves real price including zero for %s', async (role, cost, displayed) => {
    useAuthStore.setState({ user: actor(role) });
    getFinishedGoods.mockResolvedValue(result(cost));
    const view = render(<FinishedGoodsListScreen />);
    await waitFor(() => expect(view.getByText('测试成品')).toBeTruthy());
    expect(view.getByText(`finishedGoods.unitCost: ¥${displayed}`)).toBeTruthy();
    expect(view.getByText('finishedGoods.storageLocation: DK01')).toBeTruthy();
  });

  it('masks rendered prices immediately when the canonical role is revoked', async () => {
    useAuthStore.setState({ user: actor('finance_manager') });
    const view = render(<FinishedGoodsListScreen />);
    await waitFor(() => expect(view.getByText(/¥12\.67/)).toBeTruthy());
    act(() => { useAuthStore.setState({ user: actor('viewer') }); });
    expect(view.queryByText(/finishedGoods.unitCost/)).toBeNull();
    expect(view.queryByText('测试成品')).toBeNull();
    await waitFor(() => expect(view.getByText('finishedGoods.storageLocation: DK01')).toBeTruthy());
  });

  it('does not expose a late price-bearing response after role revocation', async () => {
    const pending = deferred<ReturnType<typeof result>>();
    getFinishedGoods.mockReturnValue(pending.promise);
    useAuthStore.setState({ user: actor('finance_manager') });
    const view = render(<FinishedGoodsListScreen />);
    await waitFor(() => expect(getFinishedGoods).toHaveBeenCalledTimes(1));
    act(() => { useAuthStore.setState({ user: actor('viewer') }); });
    await act(async () => { pending.resolve(result()); });
    await waitFor(() => expect(view.getByText('测试成品')).toBeTruthy());
    expect(view.queryByText(/finishedGoods.unitCost/)).toBeNull();
  });

  it('hides cached prices when the current actor is absent', async () => {
    useAuthStore.setState({ user: actor('finance_manager') });
    const view = render(<FinishedGoodsListScreen />);
    await waitFor(() => expect(view.getByText(/¥12\.67/)).toBeTruthy());
    act(() => { useAuthStore.setState({ user: null, isAuthenticated: false }); });
    expect(view.queryByText(/finishedGoods.unitCost/)).toBeNull();
  });

  it('masks cached inventory immediately on an allowed-to-allowed factory switch', async () => {
    useAuthStore.setState({ user: actor('finance_manager') });
    const view = render(<FinishedGoodsListScreen />);
    await waitFor(() => expect(view.getByText(/¥12\.67/)).toBeTruthy());
    const newFactory = deferred<ReturnType<typeof result>>();
    getFinishedGoods.mockReturnValue(newFactory.promise);
    act(() => { useAuthStore.setState({ user: actor('finance_manager', 'F007') }); });
    expect(view.queryByText('测试成品')).toBeNull();
    expect(view.queryByText(/¥12\.67/)).toBeNull();
    await act(async () => { newFactory.resolve(result(99, 'F007', '新厂成品')); });
    await waitFor(() => expect(view.getByText('新厂成品')).toBeTruthy());
    expect(view.getByText(/¥99/)).toBeTruthy();
    expect(getFinishedGoods).toHaveBeenLastCalledWith({ page: 1, size: 100 }, 'F007');
  });

  it.each(['factory', 'actor'] as const)('drops a previous %s response even when both actors can see prices', async change => {
    const oldRequest = deferred<ReturnType<typeof result>>();
    const currentRequest = deferred<ReturnType<typeof result>>();
    getFinishedGoods.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(currentRequest.promise);
    useAuthStore.setState({ user: actor('finance_manager') });
    const view = render(<FinishedGoodsListScreen />);
    await waitFor(() => expect(getFinishedGoods).toHaveBeenCalledTimes(1));
    const currentFactory = change === 'factory' ? 'F007' : 'F006';
    act(() => { useAuthStore.setState({ user: actor('finance_manager', currentFactory, 88) }); });
    await waitFor(() => expect(getFinishedGoods).toHaveBeenCalledTimes(2));
    await act(async () => { oldRequest.resolve(result()); });
    expect(view.queryByText('测试成品')).toBeNull();
    expect(view.queryByText(/¥12\.67/)).toBeNull();
    await act(async () => { currentRequest.resolve(result(99, currentFactory, '当前成品')); });
    await waitFor(() => expect(view.getByText('当前成品')).toBeTruthy());
    expect(view.getByText(/¥99/)).toBeTruthy();
  });

  it('rejects a mismatched factory payload and reports a load error', async () => {
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    try {
      useAuthStore.setState({ user: actor('finance_manager') });
      getFinishedGoods.mockResolvedValue(result(12.67, 'F007'));
      const view = render(<FinishedGoodsListScreen />);
      await waitFor(() => expect(alert).toHaveBeenCalled());
      expect(view.queryByText('测试成品')).toBeNull();
      expect(view.queryByText(/¥12\.67/)).toBeNull();
    } finally { alert.mockRestore(); }
  });

  it('ignores a previous actor failure without alerting over the current inventory', async () => {
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    try {
      const old = deferred<ReturnType<typeof result>>();
      getFinishedGoods.mockReturnValueOnce(old.promise).mockResolvedValueOnce(result(99, 'F006', '当前成品'));
      useAuthStore.setState({ user: actor('finance_manager') });
      const view = render(<FinishedGoodsListScreen />);
      await waitFor(() => expect(getFinishedGoods).toHaveBeenCalledTimes(1));
      act(() => { useAuthStore.setState({ user: actor('finance_manager', 'F006', 88) }); });
      await waitFor(() => expect(view.getByText('当前成品')).toBeTruthy());
      await act(async () => { old.reject(new Error('old request failure')); });
      expect(alert).not.toHaveBeenCalled();
      expect(view.getByText(/¥99/)).toBeTruthy();
    } finally { alert.mockRestore(); }
  });

  it('rejects retained row and AI callbacks after switching the actor', async () => {
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    try {
      useAuthStore.setState({ user: actor('finance_manager') });
      const view = render(<FinishedGoodsListScreen />);
      await waitFor(() => expect(view.getByText('测试成品')).toBeTruthy());
      const card = view.UNSAFE_getAllByType(Card).find(node => typeof node.props.onLongPress === 'function');
      expect(card).toBeTruthy();
      act(() => { card!.props.onLongPress(); });
      const calls = jest.mocked(RowActionBottomSheet).mock.calls;
      const oldSheet = calls[calls.length - 1]![0];
      expect(oldSheet.visible).toBe(true);
      const actionCalls = jest.mocked(useRowActions).mock.calls;
      const oldHandlers = actionCalls[actionCalls.length - 1]![2]!.handlers!;
      const newRequest = deferred<ReturnType<typeof result>>();
      getFinishedGoods.mockReturnValue(newRequest.promise);
      act(() => { useAuthStore.setState({ user: actor('finance_manager', 'F007', 88) }); });
      act(() => {
        oldSheet.onAITrigger?.();
        oldHandlers['view-detail']?.({ id: 'FG-1', status: 'IN_STOCK' } as RowContext);
      });
      expect(mockDispatch).not.toHaveBeenCalled();
      expect(alert).not.toHaveBeenCalled();
    } finally { alert.mockRestore(); }
  });
});
