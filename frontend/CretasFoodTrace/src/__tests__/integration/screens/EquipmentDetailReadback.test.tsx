import React from 'react';
import { act, cleanup, fireEvent, render, waitFor, within } from '@testing-library/react-native';

const mockNavigate = jest.fn();
const mockRouteParams = { equipmentId: '42' };
type ErrorGlobal = typeof globalThis & { __equipmentDetailHandleErrors?: unknown[][] };

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate, goBack: jest.fn() }),
  useRoute: () => ({ params: mockRouteParams }),
  useFocusEffect: (effect: () => void | (() => void)) => React.useEffect(effect, [effect]),
}));
jest.mock('../../../utils/errorHandler', () => ({
  handleError: (...args: unknown[]) => {
    const scope = globalThis as ErrorGlobal;
    (scope.__equipmentDetailHandleErrors ??= []).push(args);
  },
  getErrorMsg: (error: unknown) => error instanceof Error ? error.message : '',
}));
jest.mock('../../../utils/logger', () => {
  const context = { error: jest.fn(), debug: jest.fn(), info: jest.fn(), warn: jest.fn() };
  return { logger: { createContextLogger: () => context } };
});
jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, values?: { value?: unknown }) => values?.value === undefined ? key : `${key}:${values.value}`,
  }),
}));
jest.mock('react-native-paper', () => {
  const Native = require('react-native') as typeof import('react-native');
  const Box = ({ children, ...props }: { children?: React.ReactNode; [key: string]: unknown }) => <Native.View {...props}>{children}</Native.View>;
  const Button = ({ children, onPress, ...props }: { children?: React.ReactNode; onPress?: () => void; [key: string]: unknown }) => (
    <Native.TouchableOpacity {...props} onPress={onPress}>{children}</Native.TouchableOpacity>
  );
  const Table = ({ children }: { children?: React.ReactNode }) => <Native.View>{children}</Native.View>;
  return {
    Text: Native.Text,
    Appbar: { Header: Box, BackAction: Button, Content: Box, Action: Button },
    Surface: Box,
    Chip: Box,
    Divider: Box,
    ActivityIndicator: Box,
    Button,
    DataTable: Object.assign(Table, { Header: Box, Title: Box, Row: Box, Cell: Box }),
    ProgressBar: Box,
  };
});

import EquipmentDetailScreen from '../../../screens/processing/EquipmentDetailScreen';
import { equipmentApiClient } from '../../../services/api/equipmentApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { User } from '../../../types/auth';
import { logger } from '../../../utils/logger';

function equipmentUser(equipment: 'r' | 'rw' | '-', factoryId = 'F006'): User {
  return {
    id: 11,
    username: 'equipment-admin',
    email: 'equipment@example.test',
    createdAt: '',
    updatedAt: '',
    isActive: true,
    userType: 'factory',
    factoryId: 'stale-top-level-factory',
    factoryUser: { role: 'equipment_admin', factoryId, permissions: [], modulePermissions: { equipment } },
  } as User;
}

function equipment(id = 42, name = '切片设备', factoryId = 'F006') {
  return {
    id,
    factoryId,
    name,
    code: `EQ-${id}`,
    type: 'processing' as const,
    model: 'M-1',
    manufacturer: '真实制造商',
    purchaseDate: '2026-01-01',
    status: 'active' as const,
    location: '一号车间',
  };
}

function efficiency(overrides: Record<string, unknown> = {}) {
  return {
    equipmentId: 42,
    equipmentName: '切片设备',
    period: '30d',
    actualRunningHours: 0,
    plannedHours: 32,
    utilizationRate: 0,
    availability: 0,
    oee: 0,
    performance: 85,
    quality: 95,
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

beforeEach(() => {
  jest.clearAllMocks();
  (globalThis as ErrorGlobal).__equipmentDetailHandleErrors = [];
  mockRouteParams.equipmentId = '42';
  useAuthStore.setState({ user: equipmentUser('rw'), isAuthenticated: true });
});

afterEach(() => {
  cleanup();
  jest.restoreAllMocks();
  useAuthStore.setState({ user: null, isAuthenticated: false });
});

function mockSupportingReads() {
  jest.spyOn(equipmentApiClient, 'getEquipmentMaintenanceHistory').mockResolvedValue([]);
  jest.spyOn(equipmentApiClient, 'calculateDepreciatedValue').mockResolvedValue(null as never);
}

describe('EquipmentDetailScreen real readback', () => {
  it.each([
    ['denied equipment read', '-', '42', 'F006'],
    ['invalid equipment ID', 'rw', 'invalid', 'F006'],
    ['missing canonical factory', 'rw', '42', ''],
  ] as const)('sends none of the five reads for %s', async (_label, level, id, factoryId) => {
    useAuthStore.setState({ user: equipmentUser(level, factoryId) });
    mockRouteParams.equipmentId = id;
    const reads = [
      jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(equipment()),
      jest.spyOn(equipmentApiClient, 'getEquipmentMaintenanceHistory').mockResolvedValue([]),
      jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(85),
      jest.spyOn(equipmentApiClient, 'calculateDepreciatedValue').mockResolvedValue(0),
      jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(efficiency() as never),
    ];
    const view = render(<EquipmentDetailScreen />);
    await waitFor(() => expect(view.getByText('equipmentDetail.messages.notFound')).toBeTruthy());
    for (const read of reads) expect(read).not.toHaveBeenCalled();
  });

  it('renders OEE and efficiency report percentage points, including successful zero, with unknown alerts', async () => {
    mockSupportingReads();
    const getEquipment = jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(equipment());
    const oee = jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(85);
    const report = jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(efficiency() as never);
    const maintenanceAlerts = jest.spyOn(equipmentApiClient, 'getEquipmentNeedingMaintenance');
    const warrantyAlerts = jest.spyOn(equipmentApiClient, 'getEquipmentWithExpiringWarranty');
    const view = render(<EquipmentDetailScreen />);

    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    expect(getEquipment).toHaveBeenCalledWith(42, 'F006');
    expect(oee).toHaveBeenCalledWith(42, expect.objectContaining({ startDate: expect.any(String), endDate: expect.any(String) }), 'F006');
    expect(report).toHaveBeenCalledWith(42, expect.any(Object), 'F006');
    expect(view.getByText('85.0%')).toBeTruthy();
    expect(view.getByText('0.0%')).toBeTruthy();
    expect(view.getAllByText('equipmentDetail.stats.unknown').length).toBeGreaterThanOrEqual(1);
    expect(view.getAllByText('equipmentDetail.hours:0').length).toBeGreaterThan(0);
    expect(maintenanceAlerts).not.toHaveBeenCalled();
    expect(warrantyAlerts).not.toHaveBeenCalled();

    fireEvent.press(view.getByTestId('equipment-detail-alerts-button'));
    expect(mockNavigate).toHaveBeenCalledWith('EquipmentAlerts', {});
  });

  it('renders the business equipment code instead of the database id', async () => {
    mockSupportingReads();
    mockRouteParams.equipmentId = '900';
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue({
      ...equipment(900),
      code: 'E2E-F006-MOBILE-R64-20261005',
      equipmentCode: 'E2E-F006-MOBILE-R64-20261005',
    });
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(efficiency({ equipmentId: 900 }) as never);
    const view = render(<EquipmentDetailScreen />);

    await waitFor(() => expect(view.getByTestId('equipment-detail-equipment-code')).toBeTruthy());
    expect(view.getByTestId('equipment-detail-equipment-code').props.children).toBe('E2E-F006-MOBILE-R64-20261005');
    expect(view.queryByText('900')).toBeNull();
  });

  it('keeps inactive status distinct and does not fall back from a missing code to the database id', async () => {
    mockSupportingReads();
    mockRouteParams.equipmentId = '900';
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue({
      ...equipment(900),
      code: '',
      equipmentCode: undefined,
      status: 'inactive',
    });
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(efficiency({ equipmentId: 900 }) as never);
    const view = render(<EquipmentDetailScreen />);

    await waitFor(() => expect(view.getByTestId('equipment-detail-equipment-code')).toBeTruthy());
    expect(view.getByTestId('equipment-detail-equipment-code').props.children).toBe('equipmentDetail.missingValue');
    expect(view.getByTestId('equipment-detail-status').props.children).toBe('equipmentDetail.status.inactive');
    expect(view.queryByText('900')).toBeNull();
  });

  it('shows N/A for missing efficiency metrics and failed OEE instead of inventing values', async () => {
    mockSupportingReads();
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(equipment());
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockRejectedValue(new Error('oee unavailable'));
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(efficiency({ actualRunningHours: null, plannedHours: null, utilizationRate: undefined, availability: undefined }) as never);
    const view = render(<EquipmentDetailScreen />);

    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    expect(view.queryByText('85.0%')).toBeNull();
    expect(view.getAllByText('N/A').length).toBeGreaterThan(0);
    expect(view.queryByText('95%')).toBeNull();
  });

  it('displays a reported zero depreciated value even when efficiency and OEE reads fail', async () => {
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(equipment());
    jest.spyOn(equipmentApiClient, 'getEquipmentMaintenanceHistory').mockResolvedValue([]);
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockRejectedValue(new Error('unavailable'));
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockRejectedValue(new Error('unavailable'));
    jest.spyOn(equipmentApiClient, 'calculateDepreciatedValue').mockResolvedValue(0);
    const view = render(<EquipmentDetailScreen />);

    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    expect(view.getByText('¥0.00')).toBeTruthy();
    expect(view.queryByText('95%')).toBeNull();
    expect(view.getByText('equipmentDetail.stats.reportHint')).toBeTruthy();
  });

  it('preserves a real zero maintenance cost and marks missing or invalid cost as N/A', async () => {
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(equipment());
    jest.spyOn(equipmentApiClient, 'getEquipmentMaintenanceHistory').mockResolvedValue([
      { id: 1, equipmentId: 42, maintenanceDate: '2026-10-01', cost: 0, description: 'zero', performedBy: 'tech' },
      { id: 2, equipmentId: 42, maintenanceDate: '2026-10-02', cost: null, description: 'missing', performedBy: 'tech' },
      { id: 3, equipmentId: 42, maintenanceDate: '2026-10-03', cost: -1, description: 'invalid', performedBy: 'tech' },
      { id: 4, equipmentId: 42, maintenanceDate: undefined, cost: undefined, description: undefined, performedBy: undefined },
    ] as never);
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'calculateDepreciatedValue').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(efficiency() as never);
    const view = render(<EquipmentDetailScreen />);

    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    expect(view.getByTestId('equipment-detail-maintenance-cost-1').props.children).toBe('¥0');
    expect(view.getByTestId('equipment-detail-maintenance-cost-2').props.children).toBe('equipmentDetail.maintenanceRecords.missingCost');
    const missingRecord = view.getByTestId('equipment-detail-maintenance-card-4');
    expect(within(missingRecord).getByText('equipmentDetail.maintenanceRecords.date')).toBeTruthy();
    expect(within(missingRecord).getByText('equipmentDetail.maintenanceRecords.missingDate')).toBeTruthy();
    expect(within(missingRecord).getByText('equipmentDetail.maintenanceRecords.missingTechnician')).toBeTruthy();
    expect(within(missingRecord).getByText('equipmentDetail.maintenanceRecords.missingDescription')).toBeTruthy();
  });

  it('shows real maintenance descriptions and expands all returned records without inventing a type', async () => {
    const history = Array.from({ length: 6 }, (_, index) => ({
      id: index + 1,
      equipmentId: 42,
      maintenanceDate: `2026-10-${String(index + 1).padStart(2, '0')}`,
      cost: index === 0 ? 0 : null,
      description: index === 5
        ? '审计探测2-验证maintenanceType是否生效-完整维护内容不应被截断'
        : `真实维护内容-${index + 1}`,
      performedBy: '现场技术员',
    }));
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(equipment());
    jest.spyOn(equipmentApiClient, 'getEquipmentMaintenanceHistory').mockResolvedValue(history as never);
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'calculateDepreciatedValue').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(null as never);
    const view = render(<EquipmentDetailScreen />);

    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    expect(view.getByTestId('equipment-detail-maintenance-description-1').props.children).toBe('真实维护内容-1');
    expect(view.queryByTestId('equipment-detail-maintenance-description-6')).toBeNull();
    expect(view.queryByText('Routine Maintenance')).toBeNull();

    fireEvent.press(view.getByTestId('equipment-detail-maintenance-toggle'));
    const fullDescription = view.getByTestId('equipment-detail-maintenance-description-6');
    expect(fullDescription.props.children).toBe('审计探测2-验证maintenanceType是否生效-完整维护内容不应被截断');
    expect(fullDescription.props.numberOfLines).toBeUndefined();
    fireEvent.press(view.getByTestId('equipment-detail-maintenance-toggle'));
    expect(view.queryByTestId('equipment-detail-maintenance-description-6')).toBeNull();
  });

  it.each([0, 5])('does not render a dead expand or maintenance-action button for %s records', async (count) => {
    const history = Array.from({ length: count }, (_, index) => ({
      id: index + 1,
      equipmentId: 42,
      maintenanceDate: `2026-10-${String(index + 1).padStart(2, '0')}`,
      cost: 0,
      description: `维护内容-${index + 1}`,
      performedBy: '现场技术员',
    }));
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(equipment());
    jest.spyOn(equipmentApiClient, 'getEquipmentMaintenanceHistory').mockResolvedValue(history as never);
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'calculateDepreciatedValue').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(null as never);
    const view = render(<EquipmentDetailScreen />);

    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    expect(view.queryByTestId('equipment-detail-maintenance-toggle')).toBeNull();
    expect(view.queryByText('equipmentDetail.actions.maintenanceRecord')).toBeNull();
    expect(view.getByTestId('equipment-detail-alerts-button')).toBeTruthy();
  });

  it('shows an explicit realtime-not-connected message when no parameters are returned', async () => {
    mockSupportingReads();
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(equipment());
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(null as never);
    const view = render(<EquipmentDetailScreen />);

    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    expect(view.getByTestId('equipment-detail-realtime-not-connected')).toBeTruthy();
    expect(view.getByText('equipmentDetail.realtimeParams.notConnected')).toBeTruthy();
  });

  it('collapses maintenance history and rejects an old expand callback after actor change', async () => {
    const firstHistory = Array.from({ length: 6 }, (_, index) => ({
      id: index + 1,
      equipmentId: 42,
      maintenanceDate: `2026-10-${String(index + 1).padStart(2, '0')}`,
      cost: 0,
      description: `旧身份维护-${index + 1}`,
      performedBy: '旧技术员',
    }));
    const secondHistory = Array.from({ length: 6 }, (_, index) => ({
      id: index + 11,
      equipmentId: 42,
      maintenanceDate: `2026-09-${String(index + 1).padStart(2, '0')}`,
      cost: 0,
      description: `新身份维护-${index + 1}`,
      performedBy: '新技术员',
    }));
    jest.spyOn(equipmentApiClient, 'getEquipmentById')
      .mockResolvedValueOnce(equipment())
      .mockResolvedValueOnce(equipment(42, '身份切换设备'));
    jest.spyOn(equipmentApiClient, 'getEquipmentMaintenanceHistory')
      .mockResolvedValueOnce(firstHistory as never)
      .mockResolvedValueOnce(secondHistory as never);
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'calculateDepreciatedValue').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(null as never);
    const view = render(<EquipmentDetailScreen />);
    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    const oldExpand = view.getByTestId('equipment-detail-maintenance-toggle').props.onPress as () => void;
    fireEvent.press(view.getByTestId('equipment-detail-maintenance-toggle'));
    expect(view.getByTestId('equipment-detail-maintenance-description-6').props.children).toBe('旧身份维护-6');

    act(() => { useAuthStore.setState({ user: { ...equipmentUser('rw'), id: 12, username: 'equipment-admin-2' } }); });
    await waitFor(() => expect(view.getByText('身份切换设备')).toBeTruthy());
    expect(view.queryByTestId('equipment-detail-maintenance-description-16')).toBeNull();
    await act(async () => { oldExpand(); await Promise.resolve(); });
    expect(view.queryByTestId('equipment-detail-maintenance-description-16')).toBeNull();
  });

  it('collapses maintenance history and rejects an old expand callback after device change', async () => {
    const firstHistory = Array.from({ length: 6 }, (_, index) => ({
      id: index + 1,
      equipmentId: 42,
      maintenanceDate: `2026-10-${String(index + 1).padStart(2, '0')}`,
      cost: 0,
      description: `旧设备维护-${index + 1}`,
      performedBy: '旧技术员',
    }));
    const secondHistory = Array.from({ length: 6 }, (_, index) => ({
      id: index + 21,
      equipmentId: 43,
      maintenanceDate: `2026-09-${String(index + 1).padStart(2, '0')}`,
      cost: 0,
      description: `新设备维护-${index + 1}`,
      performedBy: '新技术员',
    }));
    jest.spyOn(equipmentApiClient, 'getEquipmentById')
      .mockResolvedValueOnce(equipment())
      .mockResolvedValueOnce(equipment(43, '设备切换后', 'F007'));
    jest.spyOn(equipmentApiClient, 'getEquipmentMaintenanceHistory')
      .mockResolvedValueOnce(firstHistory as never)
      .mockResolvedValueOnce(secondHistory as never);
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'calculateDepreciatedValue').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(null as never);
    const view = render(<EquipmentDetailScreen />);
    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    const oldExpand = view.getByTestId('equipment-detail-maintenance-toggle').props.onPress as () => void;
    fireEvent.press(view.getByTestId('equipment-detail-maintenance-toggle'));
    expect(view.getByTestId('equipment-detail-maintenance-description-6').props.children).toBe('旧设备维护-6');

    mockRouteParams.equipmentId = '43';
    act(() => { useAuthStore.setState({ user: equipmentUser('rw', 'F007') }); });
    await waitFor(() => expect(view.getByText('设备切换后')).toBeTruthy());
    expect(view.queryByTestId('equipment-detail-maintenance-description-26')).toBeNull();
    await act(async () => { oldExpand(); await Promise.resolve(); });
    expect(view.queryByTestId('equipment-detail-maintenance-description-26')).toBeNull();
  });

  it('uses a safe fixed error category for the current detail failure', async () => {
    mockSupportingReads();
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockRejectedValue(new Error('sensitive backend detail'));
    const view = render(<EquipmentDetailScreen />);

    await waitFor(() => expect(view.getByText('equipmentDetail.messages.notFound')).toBeTruthy());
    const calls = (globalThis as ErrorGlobal).__equipmentDetailHandleErrors || [];
    expect(calls).toHaveLength(1);
    expect(calls[0]?.[1]).toEqual(expect.objectContaining({
      logError: false,
      customMessage: 'equipmentDetail.messages.loadError',
    }));
    const safeError = jest.mocked(logger.createContextLogger('EquipmentDetail').error);
    expect(safeError).toHaveBeenCalledTimes(1);
    expect(safeError).toHaveBeenCalledWith('设备详情读取失败', expect.objectContaining({ message: 'equipment_detail_read_failed' }));
  });

  it.each([43, undefined])('does not render an efficiency report for equipmentId %s', async (reportedId) => {
    mockSupportingReads();
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(equipment());
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(
      efficiency({ equipmentId: reportedId, actualRunningHours: 123, availability: 77, utilizationRate: 76 }) as never,
    );
    const view = render(<EquipmentDetailScreen />);
    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    expect(view.queryByText('77%')).toBeNull();
    expect(view.queryByText('76.0%')).toBeNull();
    expect(view.queryByText('equipmentDetail.hours:123')).toBeNull();
    expect(view.getAllByText('equipmentDetail.stats.unknown').length).toBeGreaterThan(0);
  });

  it('stops refreshing when the new factory and equipment load completes while an old refresh is pending', async () => {
    mockSupportingReads();
    const oldRefresh = deferred<ReturnType<typeof equipment>>();
    const getEquipment = jest.spyOn(equipmentApiClient, 'getEquipmentById')
      .mockResolvedValueOnce(equipment())
      .mockReturnValueOnce(oldRefresh.promise as never)
      .mockResolvedValueOnce(equipment(43, '新设备', 'F007'));
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(efficiency() as never);
    const view = render(<EquipmentDetailScreen />);
    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    const refresh = view.UNSAFE_getByType(require('react-native').ScrollView).props.refreshControl.props.onRefresh as () => Promise<void>;
    await act(async () => { void refresh(); await Promise.resolve(); });
    expect(getEquipment).toHaveBeenCalledTimes(2);
    mockRouteParams.equipmentId = '43';
    act(() => { useAuthStore.setState({ user: equipmentUser('rw', 'F007') }); });
    await waitFor(() => expect(view.getByText('新设备')).toBeTruthy());
    expect(view.UNSAFE_getByType(require('react-native').ScrollView).props.refreshControl.props.refreshing).toBe(false);
    await act(async () => { oldRefresh.resolve(equipment()); await oldRefresh.promise; });
    expect(view.queryByText('切片设备')).toBeNull();
    expect(view.UNSAFE_getByType(require('react-native').ScrollView).props.refreshControl.props.refreshing).toBe(false);
  });

  it('keeps valid zero and rejects out-of-range percentage points as N/A', async () => {
    mockSupportingReads();
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(equipment());
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(101);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(
      efficiency({ availability: -1, utilizationRate: 101 }) as never,
    );
    const view = render(<EquipmentDetailScreen />);

    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    expect(view.queryByText('101.0%')).toBeNull();
    expect(view.queryByText('-1.0%')).toBeNull();
    expect(view.getAllByText('N/A').length).toBeGreaterThan(0);
  });

  it('masks old equipment data and ignores a late success after factory and equipment change', async () => {
    mockSupportingReads();
    const oldRead = deferred<ReturnType<typeof equipment>>();
    const getEquipment = jest.spyOn(equipmentApiClient, 'getEquipmentById')
      .mockReturnValueOnce(oldRead.promise as never)
      .mockResolvedValueOnce(equipment(43, '新设备', 'F007'));
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(efficiency() as never);
    const view = render(<EquipmentDetailScreen />);
    await waitFor(() => expect(getEquipment).toHaveBeenCalledTimes(1));

    mockRouteParams.equipmentId = '43';
    act(() => { useAuthStore.setState({ user: equipmentUser('rw', 'F007') }); });
    expect(view.queryByText('切片设备')).toBeNull();
    await waitFor(() => expect(view.getByText('新设备')).toBeTruthy());
    await act(async () => { oldRead.resolve(equipment()); await oldRead.promise; });
    expect(view.queryByText('切片设备')).toBeNull();
    expect(view.getByText('新设备')).toBeTruthy();
  });

  it('ignores a late error after the current factory and equipment succeeds', async () => {
    mockSupportingReads();
    const oldRead = deferred<ReturnType<typeof equipment>>();
    const getEquipment = jest.spyOn(equipmentApiClient, 'getEquipmentById')
      .mockReturnValueOnce(oldRead.promise as never)
      .mockResolvedValueOnce(equipment(43, '新设备', 'F007'));
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(efficiency() as never);
    const view = render(<EquipmentDetailScreen />);
    await waitFor(() => expect(getEquipment).toHaveBeenCalledTimes(1));

    mockRouteParams.equipmentId = '43';
    act(() => { useAuthStore.setState({ user: equipmentUser('rw', 'F007') }); });
    await waitFor(() => expect(view.getByText('新设备')).toBeTruthy());
    await act(async () => { oldRead.reject(new Error('stale failure')); await Promise.resolve(); });
    expect(view.getByText('新设备')).toBeTruthy();
    expect(view.queryByText('equipmentDetail.messages.notFound')).toBeNull();
  });

  it.each([
    ['cross-factory', equipment(42, '跨工厂设备', 'F007')],
    ['mismatched-equipment', equipment(99, '错设备', 'F006')],
  ])('rejects a %s detail response before rendering it', async (_label, response) => {
    mockSupportingReads();
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(response as never);
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(efficiency() as never);
    const view = render(<EquipmentDetailScreen />);

    await waitFor(() => expect(view.getByText('equipmentDetail.messages.notFound')).toBeTruthy());
    expect(view.queryByText('跨工厂设备')).toBeNull();
    expect(view.queryByText('错设备')).toBeNull();
  });

  it('hides old data and blocks a retained refresh after equipment read permission is revoked', async () => {
    mockSupportingReads();
    const getEquipment = jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(equipment());
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(efficiency() as never);
    const view = render(<EquipmentDetailScreen />);
    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    const scrollView = view.UNSAFE_getByType(require('react-native').ScrollView);
    const refresh = scrollView.props.refreshControl.props.onRefresh as () => void;
    const callsBefore = getEquipment.mock.calls.length;

    act(() => { useAuthStore.setState({ user: equipmentUser('-', 'F006') }); });
    view.rerender(<EquipmentDetailScreen />);
    expect(view.queryByText('切片设备')).toBeNull();
    await act(async () => { refresh(); await Promise.resolve(); });
    expect(getEquipment.mock.calls.length).toBe(callsBefore);
    await waitFor(() => expect(view.getByText('equipmentDetail.messages.notFound')).toBeTruthy());
  });

  it('blocks a retained factory-alert navigation after equipment read permission is revoked', async () => {
    mockSupportingReads();
    jest.spyOn(equipmentApiClient, 'getEquipmentById').mockResolvedValue(equipment());
    jest.spyOn(equipmentApiClient, 'calculateOEE').mockResolvedValue(null as never);
    jest.spyOn(equipmentApiClient, 'getEquipmentEfficiencyReport').mockResolvedValue(efficiency() as never);
    const view = render(<EquipmentDetailScreen />);
    await waitFor(() => expect(view.getByText('切片设备')).toBeTruthy());
    const retainedNavigation = view.getByTestId('equipment-detail-alerts-button').props.onPress as () => void;
    mockNavigate.mockClear();

    act(() => { useAuthStore.setState({ user: equipmentUser('-', 'F006') }); });
    view.rerender(<EquipmentDetailScreen />);
    await act(async () => { retainedNavigation(); await Promise.resolve(); });
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
