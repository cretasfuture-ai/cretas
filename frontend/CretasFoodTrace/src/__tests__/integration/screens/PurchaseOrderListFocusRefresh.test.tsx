import React from 'react';
import { act, fireEvent, render, renderHook, waitFor } from '@testing-library/react-native';
import PurchaseOrderListScreen from '../../../screens/factory-admin/inventory/PurchaseOrderListScreen';
import { purchaseApiClient, type PurchaseOrder } from '../../../services/api/purchaseApiClient';
import { listSummaryApiClient } from '../../../services/api/listSummaryApiClient';
import { makeUser } from '../../gates/lib/roleReachability';
import type { RowContext } from '../../../hooks/useRowActions';
import { useListSummary } from '../../../hooks/useListSummary';

let mockFocus: (() => void | (() => void)) | undefined;
let mockBlur: (() => void) | undefined;
let mockUser = makeUser('procurement_manager');
let mockHandlers: Record<string, (context: RowContext) => unknown> = {};
const mockNavigation = { navigate: jest.fn(), dispatch: jest.fn() };
let mockRenderedTexts: Array<{ actor: typeof mockUser; text: string }> = [];
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => mockNavigation,
  CommonActions: { navigate: jest.fn() },
  useFocusEffect: (callback: () => void | (() => void)) => {
    mockFocus = callback;
    require('react').useEffect(() => { mockBlur = callback() || undefined; return () => mockBlur?.(); }, [callback]);
  },
}));
jest.mock('../../../store/authStore', () => ({ useAuthStore: Object.assign(
  () => ({ user: mockUser }), { getState: () => ({ user: mockUser }) },
) }));
jest.mock('../../../services/api/purchaseApiClient', () => ({ purchaseApiClient: {
  getOrders: jest.fn(), getOrdersByStatus: jest.fn(), cancelOrder: jest.fn(),
} }));
jest.mock('../../../services/api/listSummaryApiClient', () => ({
  listSummaryApiClient: { getSummary: jest.fn() },
}));
jest.mock('../../../hooks/useRowActions', () => ({ useRowActions: (
  _entity: string, _context: RowContext, options: { handlers: typeof mockHandlers },
) => { mockHandlers = options.handlers; return []; } }));
jest.mock('../../../components/list', () => ({ RowActionBottomSheet: () => null,
  StickyFooterSummary: ({ stats }: { stats: Array<{ label: string; value: number | string }> }) => {
    const text = stats.map(stat => `${stat.label}:${stat.value}`).join(',');
    mockRenderedTexts.push({ actor: mockUser, text });
    return require('react').createElement(require('react-native').Text, null, text);
  },
}));
jest.mock('../../../services/api/printApiClient', () => ({ safePrint: jest.fn() }));
jest.mock('react-native-paper', () => {
  const R = require('react');
  const RN = require('react-native');
  type Props = { children?: React.ReactNode; title?: string; label?: string; visible?: boolean;
    onPress?: () => void; testID?: string; icon?: string; onValueChange?: (value: string) => void;
    buttons?: Array<{ value: string; label: string }> };
  const Box = ({ children }: Props) => R.createElement(RN.View, null, children);
  const Button = ({ children, label, onPress, testID, icon }: Props) => R.createElement(RN.Pressable,
    { onPress, testID: testID || icon }, R.createElement(RN.Text, null, label || children));
  return {
    Text: ({ children }: Props) => {
      if (typeof children === 'string') mockRenderedTexts.push({ actor: mockUser, text: children });
      return R.createElement(RN.Text, null, children);
    }, Appbar: { Header: Box, BackAction: Button, Action: Button,
      Content: ({ title }: Props) => R.createElement(RN.Text, null, title) },
    Card: Object.assign(Button, { Content: Box }), Chip: Button, FAB: Button, Button,
    Portal: Box, Modal: ({ visible, children }: Props) => visible ? R.createElement(Box, null, children) : null,
    TextInput: RN.TextInput, ActivityIndicator: RN.ActivityIndicator, Icon: () => null,
    SegmentedButtons: ({ buttons, onValueChange }: Props) => R.createElement(RN.View, null,
      buttons?.map(({ value, label }) => R.createElement(Button, { key: value, label,
        onPress: () => onValueChange?.(value) }))),
  };
});

const api = jest.mocked(purchaseApiClient);
function order(orderNumber: string): PurchaseOrder {
  return { id: orderNumber, factoryId: 'F006', supplierId: 'audit-supplier',
    supplierName: 'F006 测试供应商', orderNumber, status: 'APPROVED', totalAmount: 1,
    orderDate: '2026-10-03', createdAt: '2026-10-03T12:00:00' };
}
function page(...numbers: string[]): Awaited<ReturnType<typeof purchaseApiClient.getOrders>> {
  return { success: true, data: { content: numbers.map(order),
    totalElements: numbers.length, totalPages: 1, size: 50, number: 0 } };
}

const summaryApi = jest.mocked(listSummaryApiClient);
function summary(value: number): Awaited<ReturnType<typeof listSummaryApiClient.getSummary>> {
  return { success: true, data: { entityType: 'purchaseOrder', stats: [{ label: '合计', value }] } };
}
function cancelHandler() {
  const handler = mockHandlers.cancel;
  if (!handler) throw new Error('Missing cancel menu handler');
  return handler;
}
beforeEach(() => {
  jest.clearAllMocks(); mockFocus = undefined; mockBlur = undefined;
  mockRenderedTexts = [];
  mockUser = makeUser('procurement_manager');
  if (mockUser.userType === 'factory') mockUser.factoryUser.factoryId = 'F006';
  summaryApi.getSummary.mockResolvedValue(summary(1));
  api.getOrders.mockResolvedValue(page('PO-existing'));
  api.getOrdersByStatus.mockResolvedValue(page('PO-filtered'));
  api.cancelOrder.mockResolvedValue({ success: true, data: order('PO-filtered') });
});

it('reopening after draft creation refreshes retained list and summary without manual pull', async () => {
  api.getOrders.mockResolvedValueOnce(page('PO-existing')).mockResolvedValueOnce(page('PO-new', 'PO-existing'));
  const screen = render(<PurchaseOrderListScreen />);
  await screen.findByText('PO-existing');
  expect(screen.queryByText('PO-new')).toBeNull();
  // An ordinary render is the negative control: it must not impersonate a focus event.
  screen.rerender(<PurchaseOrderListScreen />);
  expect(api.getOrders).toHaveBeenCalledTimes(1);
  expect(mockFocus).toBeDefined();
  await act(async () => { mockBlur?.(); mockBlur = mockFocus?.() || undefined; });
  await screen.findByText('PO-new');
  expect(api.getOrders).toHaveBeenCalledTimes(2);
  expect(summaryApi.getSummary).toHaveBeenCalledTimes(2);
});

it('focused filter changes load the selected status and refresh its summary', async () => {
  api.getOrders.mockResolvedValue(page('PO-existing'));
  api.getOrdersByStatus.mockResolvedValue(page('PO-filtered'));
  const screen = render(<PurchaseOrderListScreen />);
  await screen.findByText('PO-existing');
  fireEvent.press(screen.getByText('草稿'));
  await screen.findByText('PO-filtered');
  await waitFor(() => expect(api.getOrdersByStatus).toHaveBeenCalledWith('DRAFT', { page: 1, size: 50 }));
  expect(summaryApi.getSummary).toHaveBeenCalledTimes(2);
  expect(summaryApi.getSummary).toHaveBeenLastCalledWith('purchaseOrder',
    { filterConditions: { status: 'DRAFT' } }, 'F006');
});

it('manual refresh refreshes both requests once', async () => {
  const screen = render(<PurchaseOrderListScreen />);
  await screen.findByText('PO-existing');
  fireEvent.press(screen.getByTestId('refresh'));
  await waitFor(() => expect(api.getOrders).toHaveBeenCalledTimes(2));
  expect(api.getOrders).toHaveBeenCalledTimes(2);
  expect(summaryApi.getSummary).toHaveBeenCalledTimes(2);
});

it('a legal menu action after changing filter reloads the selected status', async () => {
  const screen = render(<PurchaseOrderListScreen />);
  await screen.findByText('PO-existing');
  fireEvent.press(screen.getByText('草稿'));
  await screen.findByText('PO-filtered');
  await act(async () => { await cancelHandler()({ id: 'PO-filtered', status: 'DRAFT', canEdit: true }); });
  expect(api.cancelOrder).toHaveBeenCalledWith('PO-filtered');
  expect(api.getOrders).toHaveBeenCalledTimes(1);
  expect(api.getOrdersByStatus).toHaveBeenCalledTimes(2);
  expect(summaryApi.getSummary).toHaveBeenCalledTimes(3);
  expect(summaryApi.getSummary).toHaveBeenLastCalledWith('purchaseOrder',
    { filterConditions: { status: 'DRAFT' } }, 'F006');
});

it('late responses from an old filter cannot replace current rows or totals', async () => {
  let resolveOrders!: (value: ReturnType<typeof page>) => void;
  let resolveSummary!: (value: ReturnType<typeof summary>) => void;
  api.getOrders.mockReturnValueOnce(new Promise(resolve => { resolveOrders = resolve; }));
  summaryApi.getSummary.mockReturnValueOnce(new Promise(resolve => { resolveSummary = resolve; }));
  summaryApi.getSummary.mockResolvedValueOnce(summary(7));
  const screen = render(<PurchaseOrderListScreen />);
  fireEvent.press(screen.getByText('草稿'));
  await screen.findByText('PO-filtered');
  await screen.findByText('合计:7');
  await act(async () => { resolveOrders(page('PO-stale')); resolveSummary(summary(99)); });
  expect(screen.queryByText('PO-stale')).toBeNull();
  expect(screen.getByText('PO-filtered')).toBeTruthy();
  expect(screen.getByText('合计:7')).toBeTruthy();
});

it('switching factory clears old data and rejects a retained old actor action', async () => {
  let resolveOrders!: (value: ReturnType<typeof page>) => void;
  let resolveSummary!: (value: ReturnType<typeof summary>) => void;
  api.getOrders.mockReturnValueOnce(new Promise(resolve => { resolveOrders = resolve; }));
  summaryApi.getSummary.mockReturnValueOnce(new Promise(resolve => { resolveSummary = resolve; }));
  summaryApi.getSummary.mockResolvedValueOnce(summary(8));
  const screen = render(<PurchaseOrderListScreen />);
  const oldCancel = cancelHandler();
  mockUser = makeUser('procurement_manager');
  if (mockUser.userType === 'factory') mockUser.factoryUser.factoryId = 'F007';
  const currentPage = page('PO-current-factory');
  currentPage.data.content = currentPage.data.content.map(item => ({ ...item, factoryId: 'F007' }));
  api.getOrders.mockResolvedValueOnce(currentPage);
  screen.rerender(<PurchaseOrderListScreen />);
  await screen.findByText('PO-current-factory');
  await screen.findByText('合计:8');
  await act(async () => {
    resolveOrders(page('PO-old-factory')); resolveSummary(summary(99));
    await oldCancel({ id: 'old', status: 'DRAFT', canEdit: true });
  });
  expect(screen.queryByText('PO-old-factory')).toBeNull();
  expect(api.cancelOrder).not.toHaveBeenCalled();
  expect(screen.getByText('合计:8')).toBeTruthy();
  expect(summaryApi.getSummary).toHaveBeenLastCalledWith('purchaseOrder', { filterConditions: {} }, 'F007');
});

it('a retained summary refresh cannot request the old factory after a context switch', async () => {
  const request = { filterConditions: {} };
  const hook = renderHook<ReturnType<typeof useListSummary>, { factoryId: string }>(
    ({ factoryId }) => useListSummary('purchaseOrder', request, factoryId),
    { initialProps: { factoryId: 'F006' } });
  await waitFor(() => expect(hook.result.current.summary).not.toBeNull());
  const oldRefresh = hook.result.current.refresh;
  summaryApi.getSummary.mockResolvedValueOnce(summary(8));
  hook.rerender({ factoryId: 'F007' });
  await waitFor(() => expect(hook.result.current.summary?.stats[0]?.value).toBe(8));
  await act(async () => { await oldRefresh(); });
  expect(summaryApi.getSummary).toHaveBeenCalledTimes(2);
  expect(summaryApi.getSummary).toHaveBeenLastCalledWith('purchaseOrder', request, 'F007');
  expect(hook.result.current.summary?.stats[0]?.value).toBe(8);
  const currentRefresh = hook.result.current.refresh;
  hook.unmount();
  await act(async () => { await currentRefresh(); });
  expect(summaryApi.getSummary).toHaveBeenCalledTimes(2);
});

it('the first render for a new actor contains neither old rows nor old totals', async () => {
  const screen = render(<PurchaseOrderListScreen />);
  await screen.findByText('PO-existing');
  await screen.findByText('合计:1');
  mockRenderedTexts = [];
  mockUser = makeUser('procurement_manager');
  if (mockUser.userType === 'factory') mockUser.factoryUser.factoryId = 'F007';
  api.getOrders.mockReturnValueOnce(new Promise(() => {}));
  summaryApi.getSummary.mockReturnValueOnce(new Promise(() => {}));
  screen.rerender(<PurchaseOrderListScreen />);
  expect(mockRenderedTexts.filter(entry => entry.actor === mockUser)
    .some(entry => entry.text === 'PO-existing' || entry.text === '合计:1')).toBe(false);
  expect(screen.queryByText('PO-existing')).toBeNull();
  expect(screen.queryByText('合计:1')).toBeNull();
});
