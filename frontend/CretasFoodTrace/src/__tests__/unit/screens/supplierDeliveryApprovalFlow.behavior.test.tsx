import React from 'react';
import { Alert } from 'react-native';
import { fireEvent, render, waitFor } from '@testing-library/react-native';

const getPendingPriceAnomalyApprovals = jest.fn();
const getSupplierDeliveryNotes = jest.fn();
const approvePriceAnomaly = jest.fn();
const navigate = jest.fn();
let currentUser: any;

jest.mock('../../../services/api/inventorySupplierDeliveryApiClient', () => ({
  inventorySupplierDeliveryApiClient: {
    getPendingPriceAnomalyApprovals,
    getSupplierDeliveryNotes,
    approvePriceAnomaly,
    rejectPriceAnomaly: jest.fn(),
  },
}));

jest.mock('../../../store/authStore', () => ({
  useAuthStore: () => ({ user: currentUser }),
}));

jest.mock('@react-navigation/native', () => ({
  useFocusEffect: (effect: () => void) => {
    const ReactRuntime = require('react');
    ReactRuntime.useEffect(() => effect(), [effect]);
  },
  useNavigation: () => ({ goBack: jest.fn(), navigate }),
}));

import PriceAnomalyApprovalScreen from '../../../screens/inventory/supplier-delivery/PriceAnomalyApprovalScreen';
import SupplierDeliveryListScreen from '../../../screens/inventory/supplier-delivery/SupplierDeliveryListScreen';
import { hasModulePermission } from '../../../utils/permissionHelper';

const note = (id: string) => ({
  id,
  noteNumber: id,
  supplierName: '供应商',
  deliveryDate: '2026-11-05',
  lines: [{ priceAnomalyFlag: true }],
});

describe('工厂供货价格异常审批 flow', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    currentUser = {
      userType: 'factory',
      factoryId: 'F006',
      factoryUser: { role: 'procurement_manager', permissions: ['warehouse:read'] },
    };
    jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
      const approveButton = buttons?.find((button) => button.text === '批准');
      approveButton?.onPress?.();
    });
    approvePriceAnomaly.mockResolvedValue(note('DN-1'));
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('loads page two when totalElements exposes a 51st approval', async () => {
    getPendingPriceAnomalyApprovals
      .mockResolvedValueOnce({ data: Array.from({ length: 50 }, (_, i) => note(`DN-${i + 1}`)), totalElements: 51 })
      .mockResolvedValueOnce({ data: [note('DN-51')], totalElements: 51 });

    const view = render(<PriceAnomalyApprovalScreen />);
    await waitFor(() => expect(view.getByText('DN-50')).toBeTruthy());
    expect(view.queryByText('DN-51')).toBeNull();

    fireEvent.press(view.getByText('加载更多'));

    await waitFor(() => expect(view.getByText('DN-51')).toBeTruthy());
    expect(getPendingPriceAnomalyApprovals).toHaveBeenNthCalledWith(2, { page: 2, size: 50 });
  });

  it('sends comment and refreshes page one after approval without retaining the approved item', async () => {
    getPendingPriceAnomalyApprovals
      .mockResolvedValueOnce({ data: [note('DN-1')], totalElements: 1 })
      .mockResolvedValueOnce({ data: [], totalElements: 0 });

    const view = render(<PriceAnomalyApprovalScreen />);
    await waitFor(() => expect(view.getByText('DN-1')).toBeTruthy());
    fireEvent.changeText(view.getByPlaceholderText('审批意见（驳回必填）'), '价格核对通过');
    fireEvent.press(view.getByText('批准'));

    await waitFor(() => expect(approvePriceAnomaly).toHaveBeenCalledWith('DN-1', '价格核对通过'));
    await waitFor(() => expect(view.queryByText('DN-1')).toBeNull());
    expect(getPendingPriceAnomalyApprovals).toHaveBeenLastCalledWith({ page: 1, size: 50 });
  });

  it('uses modulePermissions for factory warehouse read/write visibility', async () => {
    getSupplierDeliveryNotes.mockResolvedValue({ data: [], totalElements: 0 });
    currentUser.factoryUser.permissions = [];
    currentUser.factoryUser.modulePermissions = { warehouse: 'rw' };
    expect(hasModulePermission(currentUser, 'warehouse', 'write')).toBe(true);
    const writable = render(<SupplierDeliveryListScreen />);
    await waitFor(() => expect(getSupplierDeliveryNotes).toHaveBeenCalled());
    expect(hasModulePermission(currentUser, 'warehouse', 'write')).toBe(true);
    expect(writable.getByText('暂无待验收送货单')).toBeTruthy();

    currentUser.factoryUser.modulePermissions = { warehouse: 'r' };
    writable.unmount();
    const readOnly = render(<SupplierDeliveryListScreen />);
    await waitFor(() => expect(getSupplierDeliveryNotes).toHaveBeenCalledTimes(2));
    expect(hasModulePermission(currentUser, 'warehouse', 'write')).toBe(false);
    expect(readOnly.getByText('暂无待验收送货单')).toBeTruthy();
  });

});
