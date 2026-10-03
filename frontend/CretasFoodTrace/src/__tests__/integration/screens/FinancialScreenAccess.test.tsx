import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import ArApOverviewScreen from '../../../screens/factory-admin/inventory/ArApOverviewScreen';
import PriceListScreen from '../../../screens/factory-admin/inventory/PriceListScreen';
import { financeApiClient, type FinanceOverview, type ArApTransaction } from '../../../services/api/financeApiClient';
import { purchaseApiClient, type PriceList } from '../../../services/api/purchaseApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { FactoryUser, ModulePermissionLevel } from '../../../types/auth';

jest.mock('../../../services/api/financeApiClient', () => ({ financeApiClient: {
  getOverview: jest.fn(), getTransactions: jest.fn(), getAging: jest.fn(),
  recordArPayment: jest.fn(), recordApPayment: jest.fn(),
} }));
jest.mock('../../../services/api/purchaseApiClient', () => ({ purchaseApiClient: {
  getPriceLists: jest.fn(), getEffectivePriceLists: jest.fn(),
} }));
const finance = jest.mocked(financeApiClient);
const purchase = jest.mocked(purchaseApiClient);
const actor = (role: FactoryUser['factoryUser']['role'], level: ModulePermissionLevel, factoryId = 'F006'): FactoryUser => ({
  id: 77, username: 'audit', email: 'audit@example.test', fullName: '审计角色',
  isActive: true, createdAt: '2026-10-03', updatedAt: '2026-10-03', userType: 'factory',
  factoryId, factoryUser: { factoryId, role, permissions: [], modulePermissions: { finance: level } },
});
const overview = (amount: number): FinanceOverview => ({
  totalReceivable: amount, totalPayable: 0, netPosition: amount, receivableCount: 0, payableCount: 0,
  overdueReceivable: 0, overduePayable: 0, overdueARCount: 0, overdueAPCount: 0,
});
const prices: PriceList = {
  id: 'price-f006', factoryId: 'F006', name: '旧工厂机密价格表', priceType: 'SELLING_PRICE',
  effectiveFrom: '2026-01-01', effectiveTo: null, isActive: true, createdBy: 77,
  remark: null, items: [], createdAt: '2026-01-01', updatedAt: null,
};
const page = <T,>(content: T[]) => ({ content, totalElements: content.length, totalPages: 1, size: 20, number: 0 });

beforeEach(() => {
  jest.clearAllMocks();
  finance.getOverview.mockResolvedValue({ success: true, data: overview(0) });
  finance.getTransactions.mockResolvedValue({ success: true, data: page([]) });
  finance.getAging.mockResolvedValue({ success: true, data: [] });
  purchase.getPriceLists.mockResolvedValue({ success: true, data: page([prices]) });
  purchase.getEffectivePriceLists.mockResolvedValue({ success: true, data: [] });
  useAuthStore.setState({ user: actor('viewer', 'r'), isAuthenticated: true });
});

it('denies finance reads before any of the four business GETs', () => {
  useAuthStore.setState({ user: actor('sales_manager', '-') });
  const view = render(<ArApOverviewScreen />);
  expect(view.getByText('当前账号无权查看应收应付')).toBeTruthy();
  expect(finance.getOverview).not.toHaveBeenCalled();
  expect(finance.getTransactions).not.toHaveBeenCalled();
  expect(finance.getAging).not.toHaveBeenCalled();
});

it('viewer retains real finance reads but has no payment form even with erroneous rw grant', async () => {
  useAuthStore.setState({ user: actor('viewer', 'rw') });
  const view = render(<ArApOverviewScreen />);
  await waitFor(() => expect(finance.getOverview).toHaveBeenCalledWith('F006'));
  expect(finance.getTransactions).toHaveBeenCalledTimes(2);
  expect(finance.getAging).toHaveBeenCalledWith('CUSTOMER', 'F006');
  expect(view.queryByTestId('finance-payment-create')).toBeNull();
  expect(view.queryByPlaceholderText('客户ID')).toBeNull();
});

it('shows masked transaction amounts as unavailable and keeps real zero amounts', async () => {
  const transaction: ArApTransaction = {
    id: 'tx-masked', factoryId: 'F006', transactionNumber: 'AR-MASKED',
    counterpartyType: 'CUSTOMER', counterpartyId: 'customer', transactionType: 'AR_PAYMENT',
    amount: null, balanceAfter: null, transactionDate: '2026-10-03', createdAt: '2026-10-03',
  };
  finance.getTransactions.mockImplementation(async params => ({ success: true, data: page(
    params?.counterpartyType === 'CUSTOMER'
      ? [transaction, { ...transaction, id: 'tx-zero', transactionNumber: 'AR-ZERO', amount: 0 }]
      : [],
  ) }));
  const view = render(<ArApOverviewScreen />);
  await waitFor(() => expect(view.getByText('AR-MASKED')).toBeTruthy());
  expect(view.getByText('金额未提供')).toBeTruthy();
  expect(view.getAllByText('¥0').length).toBeGreaterThan(0);
  fireEvent.press(view.getByText('应收'));
  expect(view.getByText('金额未提供')).toBeTruthy();
});

it('distinguishes unknown aging balances from genuine zero', async () => {
  finance.getAging.mockResolvedValue({ success: true, data: [{
    counterpartyId: 'customer', counterpartyName: '测试账龄',
    total: null, current: 0, days1_30: null, days31_60: 0,
    days61_90: 0, days91_120: 0, days120plus: null,
  }] });
  const view = render(<ArApOverviewScreen />);
  await waitFor(() => expect(finance.getAging).toHaveBeenCalled());
  fireEvent.press(view.getByText('账龄'));
  expect(view.getByText('测试账龄')).toBeTruthy();
  expect(view.getAllByText('金额未提供')).toHaveLength(3);
  expect(view.getAllByText('¥0')).toHaveLength(4);
});

it('uses the service wire keys for overdue totals and exact aging bucket boundaries', async () => {
  finance.getOverview.mockResolvedValue({ success: true, data: {
    ...overview(100), netPosition: 75, overdueReceivable: 12, overduePayable: 13,
    overdueARCount: 2, overdueAPCount: 3, receivableCount: 4, payableCount: 6,
  } });
  finance.getAging.mockResolvedValue({ success: true, data: [{
    counterpartyId: 'customer', counterpartyName: '真实字段口径', total: 216,
    current: 1, days1_30: 2, days31_60: 3, days61_90: 4, days91_120: 5, days120plus: 201,
  }] });
  const view = render(<ArApOverviewScreen />);
  await waitFor(() => expect(view.getByText('¥25')).toBeTruthy());
  expect(view.getByText('5 笔逾期')).toBeTruthy();
  expect(view.getByText('4 家未结')).toBeTruthy();
  expect(view.getByText('¥75')).toBeTruthy();
  fireEvent.press(view.getByText('账龄'));
  expect(view.getByText('¥216')).toBeTruthy();
  expect(view.getByText('91-120天')).toBeTruthy();
  expect(view.getByText('>120天')).toBeTruthy();
  expect(view.getByText('¥201')).toBeTruthy();
  expect(view.queryByText('金额未提供')).toBeNull();
});

it.each(['role', 'factory', 'revoke'] as const)('closes the payment form and refuses its old callback after %s changes', async change => {
  useAuthStore.setState({ user: actor('finance_manager', 'rw') });
  const view = render(<ArApOverviewScreen />);
  await waitFor(() => expect(finance.getOverview).toHaveBeenCalled());
  fireEvent.press(view.getByTestId('finance-payment-create'));
  fireEvent.changeText(view.getByPlaceholderText('客户ID'), 'audit-customer');
  fireEvent.changeText(view.getByPlaceholderText('金额'), '12.3');
  const oldSubmit: () => Promise<void> = view.getByTestId('finance-payment-submit').props.onPress;
  await act(async () => {
    useAuthStore.setState({ user: change === 'role' ? actor('viewer', 'r')
      : change === 'factory' ? actor('finance_manager', 'rw', 'F007') : actor('finance_manager', 'r') });
  });
  expect(view.queryByPlaceholderText('客户ID')).toBeNull();
  await act(async () => { await oldSubmit(); });
  expect(finance.recordArPayment).not.toHaveBeenCalled();
  expect(finance.recordApPayment).not.toHaveBeenCalled();
});

it('binds a legitimate payment to the factory in which the form was opened', async () => {
  useAuthStore.setState({ user: actor('finance_manager', 'rw') });
  finance.recordArPayment.mockRejectedValueOnce(new Error('controlled mock response'));
  const view = render(<ArApOverviewScreen />);
  await waitFor(() => expect(finance.getOverview).toHaveBeenCalled());
  fireEvent.press(view.getByTestId('finance-payment-create'));
  fireEvent.changeText(view.getByPlaceholderText('客户ID'), 'audit-customer');
  fireEvent.changeText(view.getByPlaceholderText('金额'), '12.3');
  await act(async () => { fireEvent.press(view.getByTestId('finance-payment-submit')); });
  expect(finance.recordArPayment).toHaveBeenCalledWith(expect.objectContaining({ counterpartyId: 'audit-customer', amount: 12.3 }), 'F006');
});

it('discards the old factory overview if its response arrives after an actor change', async () => {
  let complete!: (result: { success: boolean; data: FinanceOverview }) => void;
  finance.getOverview.mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }));
  finance.getOverview.mockResolvedValue({ success: true, data: overview(222) });
  useAuthStore.setState({ user: actor('finance_manager', 'rw') });
  const view = render(<ArApOverviewScreen />);
  await act(async () => { useAuthStore.setState({ user: actor('finance_manager', 'rw', 'F007') }); });
  await waitFor(() => expect(view.queryAllByText('¥222').length).toBeGreaterThan(0));
  await act(async () => { complete({ success: true, data: overview(111) }); });
  expect(view.queryAllByText('¥111')).toHaveLength(0);
  expect(finance.getOverview).toHaveBeenCalledWith('F007');
});

it.each(['viewer', 'cashier'] as const)('blocks price-list reads for %s', role => {
  useAuthStore.setState({ user: actor(role, 'rw') });
  const view = render(<PriceListScreen />);
  expect(view.getByText('当前账号无权查看价格表')).toBeTruthy();
  expect(purchase.getPriceLists).not.toHaveBeenCalled();
  expect(purchase.getEffectivePriceLists).not.toHaveBeenCalled();
});

it('immediately removes loaded prices after the role loses the special price scope', async () => {
  useAuthStore.setState({ user: actor('sales_manager', '-') });
  const view = render(<PriceListScreen />);
  await waitFor(() => expect(view.getByText('旧工厂机密价格表')).toBeTruthy());
  await act(async () => { useAuthStore.setState({ user: actor('viewer', 'r') }); });
  expect(view.queryByText('旧工厂机密价格表')).toBeNull();
  expect(view.getByText('当前账号无权查看价格表')).toBeTruthy();
  expect(purchase.getPriceLists).toHaveBeenCalledWith({ page: 1, size: 50 }, 'F006');
});

it('binds a legitimate AP payment to the current form factory', async () => {
  useAuthStore.setState({ user: actor('finance_manager', 'rw') });
  finance.recordApPayment.mockRejectedValueOnce(new Error('controlled mock response'));
  const view = render(<ArApOverviewScreen />);
  await waitFor(() => expect(finance.getOverview).toHaveBeenCalled());
  fireEvent.press(view.getByText('应付'));
  fireEvent.press(view.getByTestId('finance-payment-create'));
  fireEvent.changeText(view.getByPlaceholderText('供应商ID'), 'audit-supplier');
  fireEvent.changeText(view.getByPlaceholderText('金额'), '12.3');
  await act(async () => { fireEvent.press(view.getByTestId('finance-payment-submit')); });
  expect(finance.recordApPayment).toHaveBeenCalledWith(expect.objectContaining({ counterpartyId: 'audit-supplier', amount: 12.3 }), 'F006');
  expect(finance.recordArPayment).not.toHaveBeenCalled();
});

it('discards an old price-list reply after switching factories', async () => {
  let complete!: (result: { success: boolean; data: ReturnType<typeof page<PriceList>> }) => void;
  purchase.getPriceLists.mockImplementationOnce(() => new Promise(resolve => { complete = resolve; }));
  purchase.getPriceLists.mockResolvedValue({ success: true, data: page([{ ...prices, factoryId: 'F007', name: '新工厂价格表' }]) });
  useAuthStore.setState({ user: actor('sales_manager', '-') });
  const view = render(<PriceListScreen />);
  await act(async () => { useAuthStore.setState({ user: actor('sales_manager', '-', 'F007') }); });
  await waitFor(() => expect(view.getByText('新工厂价格表')).toBeTruthy());
  await act(async () => { complete({ success: true, data: page([prices]) }); });
  expect(view.queryByText('旧工厂机密价格表')).toBeNull();
  expect(purchase.getEffectivePriceLists).toHaveBeenCalledWith('F007');
});
