// @ts-nocheck
import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { createWarehouseDeliveryRequestKey, getOrCreateWarehouseDeliveryRequestKey, validateWarehouseReceipt, warehouseDeliveryMatchesPending, type WarehouseDeliveryPending, type WarehouseTransferDelivery } from '../../../utils/warehouseTransferDelivery';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
const screen = readFileSync(resolve(__dirname, '../../../screens/factory-admin/inventory/TransferDetailScreen.tsx'), 'utf8');
let mockCanWriteWarehouse = true;
const mockGetTransfer = jest.fn(); const mockGetDelivery = jest.fn(); const mockReceive = jest.fn();
jest.mock('../../../services/api/transferApiClient', () => ({ transferApiClient: { getTransfer: (...args: unknown[]) => mockGetTransfer(...args), getWarehouseDelivery: (...args: unknown[]) => mockGetDelivery(...args), dispatchWarehouseTransfer: jest.fn(), receiveWarehouseTransfer: (...args: unknown[]) => mockReceive(...args) } }));
jest.mock('../../../store/authStore', () => ({ useAuthStore: () => ({ user: { id: 7, factoryId: 'F1', factoryUser: { factoryId: 'F1' } } }) }));
jest.mock('../../../utils/permissionHelper', () => ({ hasModulePermission: () => mockCanWriteWarehouse }));
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ goBack: jest.fn() }), useRoute: () => ({ params: { transferId: 'T1' } }) }));
const TransferDetailScreen = require('../../../screens/factory-admin/inventory/TransferDetailScreen').default;

describe('warehouse delivery contract', () => {
  beforeEach(async () => { await AsyncStorage.clear(); jest.clearAllMocks(); mockCanWriteWarehouse = true; });
  it('uses incremental quantities and prevents over-receipt', () => {
    expect(validateWarehouseReceipt([{ itemId: 1, unit: 'kg', pendingQuantity: 0.1, quantity: 0.04 }])).toMatchObject({ valid: true, quantities: { 1: 0.04 } });
    expect(validateWarehouseReceipt([{ itemId: 1, unit: 'kg', pendingQuantity: 0.06, quantity: 0.07 }]).valid).toBe(false);
    expect(validateWarehouseReceipt([{ itemId: 1, unit: 'kg', pendingQuantity: 0.06, quantity: 0 }]).valid).toBe(false);
  });
  it('keeps one stable key for recovery', () => {
    const first = createWarehouseDeliveryRequestKey('F1', 'U1', 'T1');
    expect(createWarehouseDeliveryRequestKey('F1', 'U1', 'T1')).toBe(first);
    expect(createWarehouseDeliveryRequestKey('F2', 'U1', 'T1')).not.toBe(first);
  });
  it('requires UUID v4-shaped keys and supports persistent async recovery', async () => {
    const key = createWarehouseDeliveryRequestKey('F3', 'U3', 'T3');
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
    expect(await getOrCreateWarehouseDeliveryRequestKey('F4', 'U4', 'T4')).toBe(await getOrCreateWarehouseDeliveryRequestKey('F4', 'U4', 'T4'));
  });
  it('keeps the old same-factory confirm and cross-factory wiring', () => {
    expect(screen).toContain("handleAction('confirm')");
    expect(screen).toContain('sourceFactoryId !== transfer.targetFactoryId');
    expect(screen).toContain('persistResolvedWarehouseDelivery');
    expect(screen).toContain('targetBatchId');
    expect(screen).toContain("hasModulePermission(user, 'warehouse', 'write')");
    expect(screen).toContain('res.message ||');
    expect(screen).toContain('onPress={refreshAll}');
  });
  it('rejects empty, omitted, malformed, and extra receipts for a pending quantity', () => {
    const pending: WarehouseDeliveryPending = { factoryId: 'F1', userId: '7', transferId: 'T1', requestKey: 'key-1', itemQuantities: { 1: 0.04 }, state: 'unknown' };
    const item = (overrides = {}) => ({ id: 1, itemName: '原料', unit: 'kg', sourceBatchId: 'SRC', shippedQuantity: 0.1, receivedQuantity: 0.04, pendingQuantity: 0.06, receipts: [{ requestKey: 'key-1', operationId: 'op-1', targetBatchId: 'B1', quantity: 0.04, unit: 'kg', actorId: 7, createdAt: '' }], ...overrides });
    const delivery = (items: unknown[]): WarehouseTransferDelivery => ({ supported: true, staged: true, stage: 'PARTIAL', transferId: 'T1', transferNumber: 'TRF-1', inTransitByUnit: {}, items: items as WarehouseTransferDelivery['items'] });
    expect(warehouseDeliveryMatchesPending(delivery([]), pending)).toBe(false);
    expect(warehouseDeliveryMatchesPending(delivery([item({ id: 2 })]), pending)).toBe(false);
    expect(warehouseDeliveryMatchesPending(delivery([item({ receipts: [{ ...item().receipts[0], unit: 'box' }] })]), pending)).toBe(false);
    expect(warehouseDeliveryMatchesPending(delivery([item({ receipts: [{ ...item().receipts[0], operationId: '' }] })]), pending)).toBe(false);
    expect(warehouseDeliveryMatchesPending(delivery([item({ receipts: [{ ...item().receipts[0], targetBatchId: '' }] })]), pending)).toBe(false);
    expect(warehouseDeliveryMatchesPending(delivery([item({ receipts: [{ ...item().receipts[0], quantity: 0.06 }] })]), pending)).toBe(false);
    expect(warehouseDeliveryMatchesPending(delivery([item(), { ...item({ id: 2 }), receipts: [{ ...item().receipts[0] }] }]), pending)).toBe(false);
    expect(warehouseDeliveryMatchesPending(delivery([item()]), pending)).toBe(true);
  });
  it('mounts RN screen and refreshes delivery after the first delivery GET fails', async () => {
    mockGetTransfer.mockResolvedValue({ success: true, data: { id: 'T1', transferNumber: 'TRF-1', transferType: 'WAREHOUSE_TO_WAREHOUSE', sourceFactoryId: 'F1', targetFactoryId: 'F1', status: 'APPROVED', totalAmount: 0, transferDate: '', items: [] } });
    mockGetDelivery.mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ success: true, data: { supported: true, staged: false, stage: '待发运', transferId: 'T1', transferNumber: 'TRF-1', inTransitByUnit: {}, items: [] } });
    const view = render(React.createElement(TransferDetailScreen));
    await waitFor(() => expect(mockGetDelivery).toHaveBeenCalledTimes(1));
    fireEvent.press(view.getByTestId('transfer-detail-refresh'));
    await waitFor(() => expect(mockGetDelivery.mock.calls.length).toBeGreaterThanOrEqual(2));
  });
  it('mounts 0.04 success, remounts, then submits 0.06 with a fresh UUID', async () => {
    const base = { supported: true, staged: true, stage: '部分待收', transferId: 'T1', transferNumber: 'TRF-1', inTransitByUnit: {} };
    const delivery = (pendingQuantity: number, requestKey?: string) => ({ ...base, items: [{ id: 1, itemName: '原料', unit: 'kg', sourceBatchId: 'SRC', shippedQuantity: 0.1, receivedQuantity: 0.1 - pendingQuantity, pendingQuantity, receipts: requestKey ? [{ requestKey, operationId: `op-${requestKey}`, targetBatchId: 'B1', quantity: 0.1 - pendingQuantity, unit: 'kg', actorId: 7, createdAt: '' }] : [] }] });
    mockGetTransfer.mockResolvedValue({ success: true, data: { id: 'T1', transferNumber: 'TRF-1', transferType: 'WAREHOUSE_TO_WAREHOUSE', sourceFactoryId: 'F1', targetFactoryId: 'F1', status: 'APPROVED', totalAmount: 0, transferDate: '', items: [] } });
    mockGetDelivery.mockResolvedValue({ success: true, data: delivery(0.1) });
    mockReceive.mockImplementation(async (_id: string, payload: { requestKey: string; itemQuantities: Record<number, number> }) => ({ success: true, data: delivery(0.1 - payload.itemQuantities[1], payload.requestKey) }));
    const first = render(React.createElement(TransferDetailScreen));
    await waitFor(() => expect(first.getByTestId('warehouse-delivery-qty-1')).toBeTruthy());
    fireEvent.changeText(first.getByTestId('warehouse-delivery-qty-1'), '0.04');
    fireEvent.press(first.getByTestId('warehouse-delivery-submit'));
    await waitFor(() => expect(mockReceive).toHaveBeenCalledTimes(1));
    const firstKey = mockReceive.mock.calls[0][1].requestKey;
    first.unmount();
    const second = render(React.createElement(TransferDetailScreen));
    await waitFor(() => expect(second.getByTestId('warehouse-delivery-qty-1')).toBeTruthy());
    fireEvent.changeText(second.getByTestId('warehouse-delivery-qty-1'), '0.06');
    fireEvent.press(second.getByTestId('warehouse-delivery-submit'));
    await waitFor(() => expect(mockReceive).toHaveBeenCalledTimes(2));
    expect(mockReceive.mock.calls[1][1].itemQuantities).toEqual({ 1: 0.06 });
    expect(mockReceive.mock.calls[1][1].requestKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4/);
    expect(mockReceive.mock.calls[1][1].requestKey).not.toBe(firstKey);
    second.unmount();
  });
  it('hides both delivery actions for a denied warehouse writer without calling mutation APIs', async () => {
    mockCanWriteWarehouse = false;
    mockGetTransfer.mockResolvedValue({ success: true, data: { id: 'T1', transferNumber: 'TRF-1', transferType: 'WAREHOUSE_TO_WAREHOUSE', sourceFactoryId: 'F1', targetFactoryId: 'F1', status: 'APPROVED', totalAmount: 0, transferDate: '', items: [] } });
    mockGetDelivery.mockResolvedValue({ success: true, data: { supported: true, staged: true, stage: '部分待收', transferId: 'T1', transferNumber: 'TRF-1', inTransitByUnit: {}, items: [{ id: 1, itemName: '原料', unit: 'kg', sourceBatchId: 'SRC', shippedQuantity: 0.1, receivedQuantity: 0, pendingQuantity: 0.1, receipts: [] }] } });
    const view = render(React.createElement(TransferDetailScreen));
    await waitFor(() => expect(view.getByTestId('warehouse-delivery-qty-1')).toBeTruthy());
    expect(view.queryByTestId('warehouse-delivery-submit')).toBeNull();
    expect(view.queryByText('确认仓内发运')).toBeNull();
    expect(mockReceive).not.toHaveBeenCalled();
    const { transferApiClient } = require('../../../services/api/transferApiClient');
    expect(transferApiClient.dispatchWarehouseTransfer).not.toHaveBeenCalled();
    view.unmount();
  });
  it('keeps the original pending envelope locked after resolved persistence fails and remounts', async () => {
    const base = { supported: true, staged: true, stage: '部分待收', transferId: 'T1', transferNumber: 'TRF-1', inTransitByUnit: {} };
    const delivery = (requestKey?: string) => ({ ...base, items: [{ id: 1, itemName: '原料', unit: 'kg', sourceBatchId: 'SRC', shippedQuantity: 0.1, receivedQuantity: requestKey ? 0.04 : 0, pendingQuantity: requestKey ? 0.06 : 0.1, receipts: requestKey ? [{ requestKey, operationId: 'op-1', targetBatchId: 'B1', quantity: 0.04, unit: 'kg', actorId: 7, createdAt: '' }] : [] }] });
    mockGetTransfer.mockResolvedValue({ success: true, data: { id: 'T1', transferNumber: 'TRF-1', transferType: 'WAREHOUSE_TO_WAREHOUSE', sourceFactoryId: 'F1', targetFactoryId: 'F1', status: 'APPROVED', totalAmount: 0, transferDate: '', items: [] } });
    mockGetDelivery.mockResolvedValue({ success: true, data: delivery() });
    mockReceive.mockImplementation(async (_id: string, payload: { requestKey: string }) => ({ success: true, data: delivery(payload.requestKey) }));
    const defaultSetItem = (AsyncStorage.setItem as jest.Mock).getMockImplementation()!;
    (AsyncStorage.setItem as jest.Mock).mockImplementation(async (key: string, value: string) => { if (key.includes('warehouse-delivery-pending') && value.includes('"state":"resolved"')) throw new Error('storage full'); return defaultSetItem(key, value); });
    const first = render(React.createElement(TransferDetailScreen));
    await waitFor(() => expect(first.getByTestId('warehouse-delivery-qty-1')).toBeTruthy());
    fireEvent.changeText(first.getByTestId('warehouse-delivery-qty-1'), '0.04');
    fireEvent.press(first.getByTestId('warehouse-delivery-submit'));
    await waitFor(() => expect(mockReceive).toHaveBeenCalledTimes(1));
    first.unmount();
    const second = render(React.createElement(TransferDetailScreen));
    await waitFor(() => expect(second.getByTestId('warehouse-delivery-qty-1')).toBeTruthy());
    fireEvent.changeText(second.getByTestId('warehouse-delivery-qty-1'), '0.06');
    fireEvent.press(second.getByTestId('warehouse-delivery-submit'));
    await new Promise(resolve => setTimeout(resolve, 50));
    expect(mockReceive).toHaveBeenCalledTimes(1);
    second.unmount();
  });
});
