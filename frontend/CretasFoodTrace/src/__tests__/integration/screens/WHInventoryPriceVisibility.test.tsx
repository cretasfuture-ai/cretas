import React from 'react';
import { act, cleanup, render, waitFor } from '@testing-library/react-native';

jest.mock('@react-navigation/native', () => ({
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({ navigate: jest.fn(), dispatch: jest.fn() }),
  useFocusEffect: (effect: () => void | (() => void)) => React.useEffect(effect, [effect]),
}));

jest.mock('../../../components/list', () => ({
  StickyFooterSummary: () => null,
  RowActionBottomSheet: () => null,
}));
jest.mock('../../../components/ui/AppDialog', () => ({ appAlert: jest.fn() }));
jest.mock('../../../hooks/useListSummary', () => ({
  useListSummary: () => ({ summary: null, refresh: jest.fn() }),
}));
jest.mock('../../../hooks/useRowActions', () => ({
  useRowActions: () => [],
}));
jest.mock('../../../services/api/materialBatchApiClient', () => ({
  materialBatchApiClient: {
    getMaterialBatches: jest.fn(),
    getInventoryStatistics: jest.fn(),
  },
}));
jest.mock('../../../services/api/materialPackagingApiClient', () => ({
  materialPackagingApiClient: { list: jest.fn() },
}));
jest.mock('../../../utils/errorHandler', () => ({ handleError: jest.fn() }));
jest.mock('../../../utils/logger', () => ({
  logger: {
    info: jest.fn(),
    warn: jest.fn(),
    error: jest.fn(),
    createContextLogger: jest.fn(() => ({ info: jest.fn(), warn: jest.fn(), error: jest.fn() })),
  },
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}));

import { WHInventoryListScreen } from '../../../screens/warehouse/inventory/WHInventoryListScreen';
import { materialBatchApiClient, type MaterialBatch } from '../../../services/api/materialBatchApiClient';
import { materialPackagingApiClient } from '../../../services/api/materialPackagingApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { FactoryRole, FactoryUser } from '../../../types/auth';

const getBatches = jest.mocked(materialBatchApiClient.getMaterialBatches);
const getStatistics = jest.mocked(materialBatchApiClient.getInventoryStatistics);
const listPackaging = jest.mocked(materialPackagingApiClient.list);

function actor(role: FactoryRole): FactoryUser {
  return {
    id: 77,
    username: role,
    email: `${role}@example.test`,
    fullName: role,
    createdAt: '2026-10-04',
    updatedAt: '2026-10-04',
    isActive: true,
    userType: 'factory',
    factoryId: 'F006',
    factoryUser: {
      factoryId: 'F006',
      role,
      permissions: [],
      modulePermissions: {},
    },
  };
}

function batch(): MaterialBatch {
  return {
    id: 'batch-1',
    batchNumber: 'B-001',
    factoryId: 'F006',
    materialTypeId: 'material-1',
    materialName: '测试原料',
    inboundQuantity: 12,
    remainingQuantity: 8,
    reservedQuantity: 0,
    usedQuantity: 4,
    unit: 'kg',
    quantityUnit: 'kg',
    unitPrice: 10,
    totalCost: 80,
    supplierId: 'supplier-1',
    inboundDate: '2026-10-01',
    status: 'available',
    createdAt: '2026-10-01',
    updatedAt: '2026-10-04',
  };
}

function setData(totalValue: number | null | undefined) {
  getBatches.mockResolvedValue({
    success: true,
    code: 200,
    message: '',
    data: { content: [batch()], totalElements: 1, totalPages: 1, currentPage: 1 },
  } as never);
  getStatistics.mockResolvedValue({
    success: true,
    code: 200,
    message: '',
    data: { totalValue, totalBatches: 8, availableBatches: 7, expiringBatchesCount: 0 },
  });
  listPackaging.mockResolvedValue([]);
}

beforeEach(() => {
  jest.clearAllMocks();
  setData(1234);
  useAuthStore.setState({ user: actor('warehouse_worker'), isAuthenticated: true });
});

afterEach(() => {
  cleanup();
  useAuthStore.setState({ user: null, isAuthenticated: false });
});

describe('WHInventoryListScreen price visibility', () => {
  it.each(['warehouse_worker', 'warehouse_manager'] as FactoryRole[])('hides price for %s while retaining quantity and count cards', async (role) => {
    useAuthStore.setState({ user: actor(role), isAuthenticated: true });
    const view = render(<WHInventoryListScreen />);

    await waitFor(() => expect(view.getByText('批次总数')).toBeTruthy());
    expect(view.getByText('已加载食材(kg)')).toBeTruthy();
    expect(view.getAllByText('8').length).toBeGreaterThanOrEqual(3);
    expect(view.queryByTestId('wh-inventory-price-card')).toBeNull();
    expect(view.queryByText('¥1K')).toBeNull();
  });

  it('refuses to load inventory without a factory actor and issues no inventory requests', async () => {
    useAuthStore.setState({ user: null, isAuthenticated: false });
    const view = render(<WHInventoryListScreen />);

    await waitFor(() => expect(view.getByTestId('wh-inventory-load-error')).toBeTruthy());
    expect(view.getByText(/缺少工厂身份/)).toBeTruthy();
    expect(getBatches).not.toHaveBeenCalled();
    expect(getStatistics).not.toHaveBeenCalled();
    expect(listPackaging).not.toHaveBeenCalled();
  });

  it('shows an authorized nonzero total value and retains count cards', async () => {
    setData(1234);
    useAuthStore.setState({ user: actor('factory_super_admin'), isAuthenticated: true });
    const view = render(<WHInventoryListScreen />);

    await waitFor(() => expect(view.getByTestId('wh-inventory-price').props.children).toBe('¥1K'));
    expect(view.getByTestId('wh-inventory-price').props.children).toBe('¥1K');
    expect(view.getByText('批次总数')).toBeTruthy();
    expect(view.getByText('已加载食材(kg)')).toBeTruthy();
    expect(view.getAllByText('8').length).toBeGreaterThanOrEqual(3);
  });

  it('preserves an authorized zero total value', async () => {
    setData(0);
    useAuthStore.setState({ user: actor('factory_super_admin'), isAuthenticated: true });
    const view = render(<WHInventoryListScreen />);

    await waitFor(() => expect(view.getByTestId('wh-inventory-price').props.children).toBe('¥0'));
    expect(view.getByTestId('wh-inventory-price').props.children).toBe('¥0');
    expect(view.getByText('批次总数')).toBeTruthy();
  });

  it.each([null, undefined])('shows unavailable instead of zero for authorized missing total value (%s)', async (totalValue) => {
    setData(totalValue);
    useAuthStore.setState({ user: actor('factory_super_admin'), isAuthenticated: true });
    const view = render(<WHInventoryListScreen />);

    await waitFor(() => expect(view.getByTestId('wh-inventory-price')).toBeTruthy());
    expect(view.getByTestId('wh-inventory-price').props.children).toBe('金额未提供');
    expect(view.queryByText('¥0')).toBeNull();
    expect(view.getByText('批次总数')).toBeTruthy();
  });

  it('removes the already rendered price when the live actor loses price access', async () => {
    useAuthStore.setState({ user: actor('factory_super_admin'), isAuthenticated: true });
    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByTestId('wh-inventory-price').props.children).toBe('¥1K'));

    await act(async () => {
      useAuthStore.setState({ user: actor('warehouse_worker'), isAuthenticated: true });
    });

    expect(view.queryByTestId('wh-inventory-price-card')).toBeNull();
    expect(view.queryByText('¥1K')).toBeNull();
    expect(view.getByText('批次总数')).toBeTruthy();
    expect(view.getByText('已加载食材(kg)')).toBeTruthy();
  });
});
