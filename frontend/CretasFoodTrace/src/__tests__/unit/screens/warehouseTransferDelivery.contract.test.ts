// @ts-nocheck
import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import { createWarehouseDeliveryRequestKey, getOrCreateWarehouseDeliveryRequestKey, validateWarehouseReceipt } from '../../../utils/warehouseTransferDelivery';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
const screen = readFileSync(resolve(__dirname, '../../../screens/factory-admin/inventory/TransferDetailScreen.tsx'), 'utf8');
const mockGetTransfer = jest.fn(); const mockGetDelivery = jest.fn();
jest.mock('../../../services/api/transferApiClient', () => ({ transferApiClient: { getTransfer: (...args: unknown[]) => mockGetTransfer(...args), getWarehouseDelivery: (...args: unknown[]) => mockGetDelivery(...args), dispatchWarehouseTransfer: jest.fn(), receiveWarehouseTransfer: jest.fn() } }));
jest.mock('../../../store/authStore', () => ({ useAuthStore: () => ({ user: { id: 7, factoryId: 'F1', factoryUser: { factoryId: 'F1' } } }) }));
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ goBack: jest.fn() }), useRoute: () => ({ params: { transferId: 'T1' } }) }));
const TransferDetailScreen = require('../../../screens/factory-admin/inventory/TransferDetailScreen').default;

describe('warehouse delivery contract', () => {
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
    expect(screen).toContain('rotateWarehouseDeliveryRequestKey');
    expect(screen).toContain('targetBatchId');
    expect(screen).toContain("hasModulePermission(user, 'warehouse', 'write')");
    expect(screen).toContain('res.message ||');
    expect(screen).toContain('onPress={refreshAll}');
  });
  it('mounts RN screen and refreshes delivery after the first delivery GET fails', async () => {
    mockGetTransfer.mockResolvedValue({ success: true, data: { id: 'T1', transferNumber: 'TRF-1', transferType: 'WAREHOUSE_TO_WAREHOUSE', sourceFactoryId: 'F1', targetFactoryId: 'F1', status: 'APPROVED', totalAmount: 0, transferDate: '', items: [] } });
    mockGetDelivery.mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ success: true, data: { supported: true, staged: false, stage: '待发运', transferId: 'T1', transferNumber: 'TRF-1', inTransitByUnit: {}, items: [] } });
    const view = render(React.createElement(TransferDetailScreen));
    await waitFor(() => expect(mockGetDelivery).toHaveBeenCalledTimes(1));
    fireEvent.press(view.getByTestId('transfer-detail-refresh'));
    await waitFor(() => expect(mockGetDelivery.mock.calls.length).toBeGreaterThanOrEqual(2));
  });
});
