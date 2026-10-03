import React from 'react';
import { Alert } from 'react-native';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import SalesOrderDetailScreen from '../../../screens/factory-admin/inventory/SalesOrderDetailScreen';
import PurchaseOrderDetailScreen from '../../../screens/factory-admin/inventory/PurchaseOrderDetailScreen';
import { salesApiClient, SalesOrder } from '../../../services/api/salesApiClient';
import { purchaseApiClient, PurchaseOrder } from '../../../services/api/purchaseApiClient';
import { useAuthStore } from '../../../store/authStore';
import { appAlert, appPrompt } from '../../../components/ui';
import type { FactoryUser } from '../../../types/auth';

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: jest.fn() }),
  useRoute: () => ({ params: { orderId: 'f006-order' } }),
}));
jest.mock('../../../services/api/salesApiClient', () => ({ salesApiClient: {
  getOrder: jest.fn(), confirmOrder: jest.fn(), cancelOrder: jest.fn(), closeShortShipment: jest.fn(),
} }));
jest.mock('../../../services/api/purchaseApiClient', () => ({ purchaseApiClient: {
  getOrder: jest.fn(), submitOrder: jest.fn(), approveOrder: jest.fn(), rejectOrder: jest.fn(),
  cancelOrder: jest.fn(), updateOrder: jest.fn(),
} }));
jest.mock('../../../components/attachment', () => ({
  AttachmentList: () => null, AttachmentUploadButton: () => null,
}));
jest.mock('../../../components/ui', () => ({
  appAlert: jest.fn(), appPrompt: jest.fn(), AppDialogHost: () => null,
}));
// The shared paper Cell mock is a View; actual paper renders string cells as Text.
jest.mock('react-native-paper', () => {
  const actual = jest.requireActual('react-native-paper');
  const R = require('react');
  const { Text } = require('react-native');
  return { ...actual, DataTable: Object.assign(actual.DataTable, {
    Cell: ({ children, ...props }: { children?: React.ReactNode }) => R.createElement(Text, props, children),
  }) };
});

const user = (role: FactoryUser['factoryUser']['role']): FactoryUser => ({
  id: 77, username: `f006_${role}`, email: 'audit@example.test', fullName: '测试角色',
  isActive: true, createdAt: '2026-10-03', updatedAt: '2026-10-03', userType: 'factory',
  factoryId: 'F006', factoryUser: { role, factoryId: 'F006', permissions: [] },
});
const sales: SalesOrder = {
  id: 'f006-order', factoryId: 'F006', orderNumber: 'SO-AUDIT', customerId: 'customer',
  status: 'DRAFT', totalAmount: null, orderDate: '2026-10-03', createdAt: '2026-10-03',
  requiredDeliveryDate: '2026-10-04', expectedDeliveryDate: '2026-10-05',
  items: [{ id: 'line', productTypeId: 'product', productName: '产品', quantity: 10,
    deliveredQuantity: 8, unit: 'kg', unitPrice: null, totalPrice: null }],
};
const purchase: PurchaseOrder = {
  id: 'f006-order', factoryId: 'F006', orderNumber: 'PO-AUDIT', supplierId: 'supplier',
  status: 'DRAFT', totalAmount: null, orderDate: '2026-10-03', createdAt: '2026-10-03',
  items: [{ id: 'line', materialTypeId: 'material', materialName: '原料', quantity: 10,
    receivedQuantity: 0, unit: 'kg', unitPrice: null, totalPrice: null }],
};
const salesApi = jest.mocked(salesApiClient);
const purchaseApi = jest.mocked(purchaseApiClient);

beforeEach(() => {
  jest.clearAllMocks();
  salesApi.getOrder.mockResolvedValue({ success: true, data: sales });
  purchaseApi.getOrder.mockResolvedValue({ success: true, data: purchase });
  useAuthStore.setState({ user: user('viewer'), isAuthenticated: true });
});

it('shows the persisted required delivery date separately from a legacy expected date', async () => {
  const view = render(<SalesOrderDetailScreen />);
  await waitFor(() => expect(view.getByText('SO-AUDIT')).toBeTruthy());
  expect(view.getByText('要求交付')).toBeTruthy();
  expect(view.getByText('2026-10-04')).toBeTruthy();
  expect(view.getByText('预计交付')).toBeTruthy();
  expect(view.getByText('2026-10-05')).toBeTruthy();
});

it.each([
  ['销售', SalesOrderDetailScreen, 'SO-AUDIT', '确认订单'],
  ['采购', PurchaseOrderDetailScreen, 'PO-AUDIT', '提交审批'],
] as const)('%s查看员不显示写按钮，空金额和单价不显示假零', async (_, Screen, number, action) => {
  const view = render(<Screen />);
  await waitFor(() => expect(view.getByText(number)).toBeTruthy());
  expect(view.getAllByText('金额未提供')).toHaveLength(2);
  expect(view.queryByText('¥0')).toBeNull();
  expect(view.queryByText(action)).toBeNull();
  expect(view.queryByText('编辑')).toBeNull();
});

it('销售确认窗口打开后切换到查看员，旧确认回调不发出请求', async () => {
  useAuthStore.setState({ user: user('sales_manager') });
  const alert = jest.spyOn(Alert, 'alert');
  const view = render(<SalesOrderDetailScreen />);
  await waitFor(() => expect(view.getByText('确认订单')).toBeTruthy());
  fireEvent.press(view.getByText('确认订单'));
  const confirm = alert.mock.calls.at(-1)?.[2]?.find(button => button.text === '确定')?.onPress;
  expect(confirm).toBeDefined();
  act(() => { useAuthStore.setState({ user: user('viewer') }); });
  await act(async () => { await confirm?.(); });
  expect(salesApi.confirmOrder).not.toHaveBeenCalled();
  alert.mockRestore();
});

it('短交窗口切换查看员后关闭，已保存的提交回调也不发出请求', async () => {
  salesApi.getOrder.mockResolvedValue({ success: true, data: { ...sales, status: 'PARTIAL_DELIVERED' } });
  useAuthStore.setState({ user: user('sales_manager') });
  const view = render(<SalesOrderDetailScreen />);
  await waitFor(() => expect(view.getByTestId('sales-order-short-close-button')).toBeTruthy());
  fireEvent.press(view.getByTestId('sales-order-short-close-button'));
  // Group owns the selected value; use the actual Group callback rather than a radio mock's state.
  const group = view.UNSAFE_getByType(require('react-native-paper').RadioButton.Group);
  fireEvent(group, 'onValueChange', 'CUSTOMER_WAIVED_REMAINDER');
  fireEvent.press(view.getByTestId('sales-order-short-close-ack'));
  const submit = view.getByTestId('sales-order-short-close-confirm').props.onPress as () => Promise<void>;
  act(() => { useAuthStore.setState({ user: user('viewer') }); });
  expect(view.queryByTestId('sales-order-short-close-confirm')).toBeNull();
  await act(async () => { await submit(); });
  expect(salesApi.closeShortShipment).not.toHaveBeenCalled();
});

it('采购确认窗口打开后切换查看员，旧确认回调不发出请求', async () => {
  useAuthStore.setState({ user: user('procurement_manager') });
  const view = render(<PurchaseOrderDetailScreen />);
  await waitFor(() => expect(view.getByText('提交审批')).toBeTruthy());
  fireEvent.press(view.getByText('提交审批'));
  const confirm = jest.mocked(appAlert).mock.calls.at(-1)?.[2]?.find(button => button.text === '确定')?.onPress;
  expect(confirm).toBeDefined();
  act(() => { useAuthStore.setState({ user: user('viewer') }); });
  await act(async () => { await confirm?.(); });
  expect(purchaseApi.submitOrder).not.toHaveBeenCalled();
});

it.each(['viewer', 'masked-price'] as const)('采购编辑备注在%s情况下不发送整单更新', async mode => {
  useAuthStore.setState({ user: user('procurement_manager') });
  const view = render(<PurchaseOrderDetailScreen />);
  await waitFor(() => expect(view.getByText('编辑')).toBeTruthy());
  fireEvent.press(view.getByText('编辑'));
  const save = jest.mocked(appPrompt).mock.calls.at(-1)?.[2];
  expect(save).toBeDefined();
  if (mode === 'viewer') act(() => { useAuthStore.setState({ user: user('viewer') }); });
  await act(async () => { await save?.('更新备注'); });
  expect(purchaseApi.updateOrder).not.toHaveBeenCalled();
  if (mode === 'masked-price') expect(appAlert).toHaveBeenCalledWith('无法保存', expect.stringContaining('单价未提供'));
});

it('真实零采购单价仍按0保存，不把隐藏价格填成0', async () => {
  purchaseApi.getOrder.mockResolvedValue({ success: true, data: {
    ...purchase, items: purchase.items!.map(item => ({ ...item, unitPrice: 0 })),
  } });
  purchaseApi.updateOrder.mockResolvedValue({ success: true, data: purchase });
  useAuthStore.setState({ user: user('procurement_manager') });
  const view = render(<PurchaseOrderDetailScreen />);
  await waitFor(() => expect(view.getByText('编辑')).toBeTruthy());
  fireEvent.press(view.getByText('编辑'));
  await act(async () => { await jest.mocked(appPrompt).mock.calls.at(-1)?.[2]?.('更新备注'); });
  expect(purchaseApi.updateOrder).toHaveBeenCalledWith('f006-order', expect.objectContaining({
    orderDate: '2026-10-03', items: [expect.objectContaining({ unitPrice: 0 })], remark: '更新备注',
  }));
});
