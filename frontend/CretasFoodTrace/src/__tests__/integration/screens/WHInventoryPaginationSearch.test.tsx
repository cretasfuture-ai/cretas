import React from 'react';
import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react-native';

jest.mock('@react-navigation/native', () => {
  const navigationMock = {
    navigate: jest.fn(),
    dispatch: jest.fn(),
    rootRouteNames: ['AIChat'] as string[],
  };
  const rootNavigation = {
    getState: () => ({ routeNames: navigationMock.rootRouteNames }),
  };
  const warehouseNavigation = {
    getState: () => ({ routeNames: ['WarehouseManagerTab'] }),
    getParent: () => rootNavigation,
  };
  return {
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({ ...navigationMock, getParent: () => warehouseNavigation }),
  useFocusEffect: (effect: () => void | (() => void)) => React.useEffect(effect, [effect]),
  __navigationMock: navigationMock,
  };
});
jest.mock('../../../components/list', () => {
  const React = require('react');
  const { Text, TouchableOpacity, View } = require('react-native');
  return {
    StickyFooterSummary: ({ onAIAnalyze, stats = [], loading, empty, emptyText }: {
      onAIAnalyze?: () => void;
      stats?: Array<{ label: string; value?: unknown }>;
      loading?: boolean;
      empty?: boolean;
      emptyText?: string;
    }) => React.createElement(View, { testID: 'sticky-footer-summary' },
      loading
        ? React.createElement(Text, { testID: 'sticky-footer-loading' }, 'summary-loading')
        : empty
          ? React.createElement(Text, { testID: 'sticky-footer-empty' }, emptyText ?? '暂无数据')
          : stats.map((stat) => React.createElement(Text, { key: stat.label }, `${stat.label}:${String(stat.value ?? '')}`)),
      onAIAnalyze
        ? React.createElement(TouchableOpacity, { testID: 'sticky-footer-ai', onPress: onAIAnalyze }, React.createElement(Text, null, 'AI'))
        : null,
    ),
    RowActionBottomSheet: ({ visible, title, actions }: {
      visible: boolean;
      title?: string;
      actions?: Array<{ key: string; label: string; onPress?: () => void }>;
    }) => visible
      ? React.createElement(View, { testID: 'wh-row-action-sheet' },
        React.createElement(Text, { testID: 'wh-row-action-title' }, title),
        ...(actions ?? []).map((action) => React.createElement(
          TouchableOpacity,
          { key: action.key, testID: `wh-row-action-${action.key}`, onPress: action.onPress },
          React.createElement(Text, null, action.label),
        )))
      : null,
  };
});
jest.mock('../../../components/ui/AppDialog', () => ({ appAlert: jest.fn() }));
jest.mock('../../../services/api/listSummaryApiClient', () => ({
  listSummaryApiClient: { getSummary: jest.fn() },
}));
jest.mock('../../../hooks/useRowActions', () => ({
  useRowActions: (
    _entity: string,
    context: { id: string },
    options: { handlers: Record<string, (event?: { id: string }) => void> },
  ) => [
    { key: 'view-detail', label: '详情', onPress: () => options.handlers['view-detail']!({ id: context.id }) },
    { key: 'transfer', label: '调拨', onPress: () => options.handlers.transfer!() },
  ],
}));
jest.mock('../../../services/api/materialBatchApiClient', () => ({
  materialBatchApiClient: {
    getMaterialBatches: jest.fn(),
    getInventoryStatistics: jest.fn(),
  },
}));
jest.mock('../../../services/api/materialPackagingApiClient', () => ({ materialPackagingApiClient: { list: jest.fn() } }));
jest.mock('../../../utils/errorHandler', () => ({ handleError: jest.fn() }));
jest.mock('../../../utils/logger', () => ({
  logger: {
    info: jest.fn(),
    warn: jest.fn(),
    error: jest.fn(),
    createContextLogger: jest.fn(() => ({ info: jest.fn(), warn: jest.fn(), error: jest.fn() })),
  },
}));
jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }));

import { WHInventoryListScreen } from '../../../screens/warehouse/inventory/WHInventoryListScreen';
import { materialBatchApiClient, type MaterialBatch } from '../../../services/api/materialBatchApiClient';
import { materialPackagingApiClient } from '../../../services/api/materialPackagingApiClient';
import { listSummaryApiClient } from '../../../services/api/listSummaryApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { FactoryRole, FactoryUser } from '../../../types/auth';

const navigationMock = jest.requireMock('@react-navigation/native').__navigationMock as {
  rootRouteNames: string[];
  dispatch: jest.Mock;
  navigate: jest.Mock;
};

const getBatches = jest.mocked(materialBatchApiClient.getMaterialBatches);
const getStatistics = jest.mocked(materialBatchApiClient.getInventoryStatistics);
const listPackaging = jest.mocked(materialPackagingApiClient.list);
const getSummary = jest.mocked(listSummaryApiClient.getSummary);

function actor(factoryId = 'F006', id = 77, role: FactoryRole = 'warehouse_manager'): FactoryUser {
  return {
    id,
    username: `${role}-${id}`,
    email: `${role}-${id}@example.test`,
    fullName: role,
    createdAt: '2026-10-04',
    updatedAt: '2026-10-04',
    isActive: true,
    userType: 'factory',
    factoryId,
    factoryUser: { factoryId, role, permissions: [], modulePermissions: {} },
  };
}

function batch(
  id: string,
  batchNumber: string,
  materialName: string,
  factoryId = 'F006',
  storageType: string | null = 'fresh',
  status: string = 'AVAILABLE',
): MaterialBatch {
  return {
    id,
    batchNumber,
    factoryId,
    materialTypeId: null as unknown as string,
    materialName,
    inboundQuantity: 12,
    remainingQuantity: 12,
    reservedQuantity: 0,
    usedQuantity: 0,
    unit: 'kg',
    quantityUnit: 'kg',
    unitPrice: 10,
    totalCost: 120,
    supplierId: null as unknown as string,
    inboundDate: '2026-10-01',
    status: status as unknown as MaterialBatch['status'],
    storageType: storageType as unknown as MaterialBatch['storageType'],
    storageLocation: 'WH-RAW',
    expiryDate: '2027-01-01',
    productionDate: '2026-10-01',
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
  };
}

function page(content: MaterialBatch[], totalPages: number, currentPage = 1) {
  return { success: true, code: 200, message: 'ok', data: { content, totalElements: content.length, totalPages, currentPage } };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((nextResolve) => { resolve = nextResolve; });
  return { promise, resolve };
}

function setupStore(user: FactoryUser = actor()) {
  useAuthStore.setState({ user });
  getStatistics.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { totalValue: null, totalBatches: 3, availableBatches: 3, expiringBatchesCount: 0 } });
  listPackaging.mockResolvedValue([]);
  getSummary.mockResolvedValue({ success: true, message: 'ok', data: { entityType: 'inventory', stats: [] } });
}

afterEach(() => {
  cleanup();
  jest.clearAllMocks();
  navigationMock.rootRouteNames = ['AIChat'];
  useAuthStore.setState({ user: null });
});

describe('WHInventoryListScreen pagination and search contract', () => {
  it('loads server pages in order, deduplicates real ids, and searches material name or batch number', async () => {
    setupStore();
    getBatches.mockImplementation(async (params) =>
      (params?.page ?? 1) === 1
        ? page([batch('batch-a', 'B-001', '苹果'), batch('batch-b', 'B-002', '香蕉')], 2)
        : page([batch('batch-b', 'B-002', '香蕉'), batch('batch-c', 'B-003', '橙子')], 2, 2),
    );

    const view = render(<WHInventoryListScreen />);

    await waitFor(() => expect(view.getByText('橙子')).toBeTruthy());
    expect(getBatches).toHaveBeenNthCalledWith(1, { factoryId: 'F006', page: 1, size: 50 });
    expect(getBatches).toHaveBeenNthCalledWith(2, { factoryId: 'F006', page: 2, size: 50 });
    expect(getStatistics).toHaveBeenCalledWith('F006');
    expect(listPackaging).toHaveBeenCalledWith('F006');
    expect(getSummary).toHaveBeenCalledWith('inventory', { filterConditions: {} }, 'F006');
    expect(view.getAllByText('香蕉')).toHaveLength(1);
    expect(view.getByText(/B-003/)).toBeTruthy();

    fireEvent.changeText(view.getByPlaceholderText('搜索物料名称/批次号'), 'B-002');
    expect(view.getByText('香蕉')).toBeTruthy();
    expect(view.queryByText('苹果')).toBeNull();
    expect(view.queryByText('橙子')).toBeNull();
  });

  it('does not show page one as complete when page two fails, and retry reloads all pages', async () => {
    setupStore();
    getBatches
      .mockResolvedValueOnce(page([batch('batch-a', 'B-001', '第一页')], 2, 1))
      .mockRejectedValueOnce(new Error('page two unavailable'))
      .mockResolvedValueOnce(page([batch('batch-a', 'B-001', '第一页')], 2, 1))
      .mockResolvedValueOnce(page([batch('batch-b', 'B-002', '第二页')], 2, 2));

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByTestId('wh-inventory-load-error')).toBeTruthy());
    expect(view.getByText(/未显示不完整数据/)).toBeTruthy();
    expect(view.queryByText('第一页')).toBeNull();

    const retryPress = view.getByTestId('wh-inventory-retry').props.onPress as () => void;
    await act(async () => { retryPress(); });
    await waitFor(() => expect(view.getByText('第二页')).toBeTruthy());
    expect(getBatches).toHaveBeenCalledTimes(4);
  });

  it('keeps a worker response within its one server-authorized page and never fetches detail to expand scope', async () => {
    setupStore(actor('F006', 78, 'warehouse_worker'));
    getBatches.mockResolvedValue(page([batch('worker-batch', 'WB-001', '工人可见')], 1));

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByText('工人可见')).toBeTruthy());
    expect(getBatches).toHaveBeenCalledTimes(1);
    expect(getBatches).toHaveBeenCalledWith({ factoryId: 'F006', page: 1, size: 50 });
    expect(view.queryByTestId('sticky-footer-ai')).toBeNull();
    expect(view.getByText('库存 AI 分析请联系仓储主管')).toBeTruthy();
  });

  it('uses a real two-level ancestor route for manager AI', async () => {
    setupStore(actor('F006', 77, 'warehouse_manager'));
    getBatches.mockResolvedValue(page([batch('manager-batch', 'MB-001', '主管可见')], 1));
    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByTestId('sticky-footer-ai')).toBeTruthy());
    const staleAiPress = view.getByTestId('sticky-footer-ai').props.onPress as () => void;
    await act(async () => { staleAiPress(); });
    expect(navigationMock.dispatch).toHaveBeenCalledWith(expect.objectContaining({
      payload: expect.objectContaining({ name: 'AIChat' }),
    }));
    const dispatchCount = navigationMock.dispatch.mock.calls.length;
    await act(async () => { useAuthStore.setState({ user: actor('F006', 88, 'warehouse_worker') }); });
    await act(async () => { staleAiPress(); });
    expect(navigationMock.dispatch.mock.calls.length).toBe(dispatchCount);
  });

  it('keeps price summary fields out of a denied owner footer and AI prompt', async () => {
    setupStore(actor('F006', 77, 'warehouse_manager'));
    getBatches.mockResolvedValue(page([batch('summary-denied', 'SUM-001', '摘要权限')], 1));
    getSummary.mockResolvedValue({
      success: true,
      message: 'ok',
      data: {
        entityType: 'inventory',
        stats: [
          { label: '金额', value: 999999, format: 'currency', canViewPrice: true },
          { label: '未标记金额', value: 888888, format: 'currency' },
          { label: '受控非货币', value: 777777, format: 'plain', canViewPrice: true },
          { label: '数量', value: 7, format: 'number', unit: '条' },
        ],
      },
    });
    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByText(/数量:7/)).toBeTruthy());
    expect(view.queryByText(/金额:999999/)).toBeNull();
    expect(view.queryByText(/未标记金额:888888/)).toBeNull();
    expect(view.queryByText(/受控非货币:777777/)).toBeNull();
    const aiPress = view.getByTestId('sticky-footer-ai').props.onPress as () => void;
    await act(async () => { aiPress(); });
    const action = navigationMock.dispatch.mock.calls[navigationMock.dispatch.mock.calls.length - 1]?.[0] as { payload?: { params?: { initialMessage?: string } } };
    expect(action.payload?.params?.initialMessage).toContain('数量');
    expect(action.payload?.params?.initialMessage).not.toContain('999999');
    expect(action.payload?.params?.initialMessage).not.toContain('888888');
    expect(action.payload?.params?.initialMessage).not.toContain('777777');
  });

  it('retains price summary fields for an authorized owner footer and AI prompt', async () => {
    setupStore(actor('F006', 77, 'factory_super_admin'));
    getBatches.mockResolvedValue(page([batch('summary-allowed', 'SUM-002', '摘要授权')], 1));
    getSummary.mockResolvedValue({
      success: true,
      message: 'ok',
      data: {
        entityType: 'inventory',
        stats: [
          { label: '金额', value: 999999, format: 'currency', canViewPrice: true },
          { label: '未标记金额', value: 888888, format: 'currency' },
          { label: '受控非货币', value: 777777, format: 'plain', canViewPrice: true },
          { label: '数量', value: 7, format: 'number', unit: '条' },
        ],
      },
    });
    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByText(/金额:999999/)).toBeTruthy());
    expect(view.getByText(/未标记金额:888888/)).toBeTruthy();
    expect(view.getByText(/受控非货币:777777/)).toBeTruthy();
    const aiPress = view.getByTestId('sticky-footer-ai').props.onPress as () => void;
    await act(async () => { aiPress(); });
    const action = navigationMock.dispatch.mock.calls[navigationMock.dispatch.mock.calls.length - 1]?.[0] as { payload?: { params?: { initialMessage?: string } } };
    expect(action.payload?.params?.initialMessage).toContain('¥999,999.00');
    expect(action.payload?.params?.initialMessage).toContain('¥888,888.00');
    expect(action.payload?.params?.initialMessage).toContain('777777');
  });

  it('has no AI action when the real ancestor route is absent', async () => {
    navigationMock.rootRouteNames = [];
    setupStore(actor('F006', 77, 'warehouse_manager'));
    getBatches.mockResolvedValue(page([batch('manager-batch-2', 'MB-002', '主管无路由')], 1));
    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByText('主管无路由')).toBeTruthy());
    expect(view.queryByTestId('sticky-footer-ai')).toBeNull();
  });

  it('rejects malformed page metadata or batch identity with an explicit retry state', async () => {
    setupStore();
    getBatches.mockResolvedValue({ success: true, code: 200, message: 'ok', data: { content: [{ batchNumber: 'BAD' }], totalPages: '2' } } as never);

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByTestId('wh-inventory-load-error')).toBeTruthy());
    expect(view.getByText(/请点击“重试”/)).toBeTruthy();
    expect(view.queryByText('BAD')).toBeNull();
  });

  it('rejects a server success false response without replacing the list', async () => {
    setupStore();
    getBatches.mockResolvedValue({ success: false, code: 403, message: 'forbidden', data: null } as never);

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByTestId('wh-inventory-load-error')).toBeTruthy());
    expect(view.getByText(/未显示不完整数据/)).toBeTruthy();
    expect(view.getByTestId('wh-inventory-retry')).toBeTruthy();
  });

  it('does not apply a late response from the previous actor or request later pages', async () => {
    setupStore(actor('F006', 77));
    let resolveFirst!: (value: unknown) => void;
    const firstPage = new Promise((resolve) => { resolveFirst = resolve; });
    let resolveSecond!: (value: unknown) => void;
    const secondPage = new Promise((resolve) => { resolveSecond = resolve; });
    getBatches.mockReturnValueOnce(firstPage as never).mockReturnValueOnce(secondPage as never);

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(getBatches).toHaveBeenCalledTimes(1));

    await act(async () => { useAuthStore.setState({ user: actor('F007', 88) }); });
    await waitFor(() => expect(getBatches).toHaveBeenCalledTimes(2));
    await act(async () => {
      resolveFirst(page([batch('old-batch', 'OLD-001', '旧账号数据', 'F006')], 2));
    });

    expect(getBatches).not.toHaveBeenCalledWith({ page: 2, size: 50 });
    expect(view.queryByText('旧账号数据')).toBeNull();
    await act(async () => { resolveSecond(page([], 1)); });
  });

  it('hides the completed prior owner snapshot before the replacement request finishes', async () => {
    setupStore(actor('F006', 77));
    const replacementPage = deferred<unknown>();
    getBatches.mockResolvedValueOnce(page([batch('old-visible', 'OLD-VISIBLE', '旧快照')], 1))
      .mockReturnValueOnce(replacementPage.promise as never);

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByText('旧快照')).toBeTruthy());
    await act(async () => { useAuthStore.setState({ user: actor('F007', 88) }); });
    await waitFor(() => expect(getBatches).toHaveBeenCalledWith({ factoryId: 'F007', page: 1, size: 50 }));
    expect(view.queryByText('旧快照')).toBeNull();
    await act(async () => { replacementPage.resolve(page([batch('new-visible', 'NEW-VISIBLE', '新快照', 'F007')], 1)); });
  });

  it('guards the retained sheet actions and refresh callback across an owner switch', async () => {
    setupStore(actor('F006', 77));
    const replacementPage = deferred<unknown>();
    getBatches.mockResolvedValueOnce(page([batch('same-id', 'SAME-001', '旧同ID名称')], 1))
      .mockReturnValueOnce(replacementPage.promise as never);

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByText('旧同ID名称')).toBeTruthy());
    fireEvent(view.getByTestId('wh-inventory-row-same-id'), 'longPress');
    expect(view.getByTestId('wh-row-action-title').props.children).toBe('旧同ID名称');
    const oldDetailAction = view.getByTestId('wh-row-action-view-detail').props.onPress as () => void;
    const oldRefresh = view.getByTestId('wh-inventory-scroll').props.refreshControl.props.onRefresh as () => void;
    oldDetailAction();
    expect(navigationMock.navigate).toHaveBeenCalledWith('WHInventoryDetail', { inventoryId: 'same-id' });
    const navigationCount = navigationMock.navigate.mock.calls.length;

    await act(async () => { useAuthStore.setState({ user: actor('F007', 88) }); });
    await waitFor(() => expect(getBatches).toHaveBeenCalledWith({ factoryId: 'F007', page: 1, size: 50 }));
    expect(view.queryByTestId('wh-row-action-sheet')).toBeNull();
    oldDetailAction();
    await act(async () => { oldRefresh(); });
    expect(navigationMock.navigate.mock.calls.length).toBe(navigationCount);
    expect(getBatches).toHaveBeenCalledTimes(2);

    await act(async () => { replacementPage.resolve(page([batch('same-id', 'SAME-002', '新同ID名称', 'F007')], 1)); });
    await waitFor(() => expect(view.getByText('新同ID名称')).toBeTruthy());
    expect(view.queryByTestId('wh-row-action-sheet')).toBeNull();
  });

  it('hides cached rows, stats, summary, and sheet during a batched ABA replacement', async () => {
    const originalActor = actor('F006', 77);
    setupStore(originalActor);
    const oldRefreshPage = deferred<unknown>();
    const replacementPage = deferred<unknown>();
    const replacementSummary = deferred<unknown>();
    let summaryPhase: 'old' | 'new' = 'old';
    getBatches
      .mockResolvedValueOnce(page([batch('cached-old', 'CACHED-OLD', '旧缓存批次')], 1))
      .mockReturnValueOnce(oldRefreshPage.promise as never)
      .mockReturnValueOnce(replacementPage.promise as never);
    getStatistics.mockResolvedValueOnce({
      success: true,
      code: 200,
      message: 'ok',
      data: { totalValue: null, totalBatches: 99, availableBatches: 99, expiringBatchesCount: 0 },
    });
    getSummary.mockImplementation(async () => summaryPhase === 'old'
      ? { success: true, message: 'ok', data: { entityType: 'inventory', stats: [{ label: '摘要', value: 'OLD-SUMMARY' }] } }
      : replacementSummary.promise as never);

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByText('旧缓存批次')).toBeTruthy());
    await waitFor(() => expect(view.getByText(/OLD-SUMMARY/)).toBeTruthy());
    expect(view.getByText(/在库\s*99\s*批/)).toBeTruthy();
    fireEvent(view.getByTestId('wh-inventory-row-cached-old'), 'longPress');
    expect(view.getByTestId('wh-row-action-sheet')).toBeTruthy();
    const oldRefresh = view.getByTestId('wh-inventory-scroll').props.refreshControl.props.onRefresh as () => void;
    await act(async () => { oldRefresh(); });
    await waitFor(() => expect(getBatches).toHaveBeenCalledTimes(2));

    summaryPhase = 'new';
    await act(async () => {
      useAuthStore.setState({ user: actor('F006', 88) });
      useAuthStore.setState({ user: originalActor });
    });
    await waitFor(() => expect(getBatches).toHaveBeenCalledTimes(3));
    expect(view.queryByText('旧缓存批次')).toBeNull();
    expect(view.queryByText(/在库\s*99\s*批/)).toBeNull();
    expect(view.getByText(/在库\s*0\s*批/)).toBeTruthy();
    expect(view.queryByText(/OLD-SUMMARY/)).toBeNull();
    expect(view.queryByTestId('wh-row-action-sheet')).toBeNull();
    expect(view.getByText('加载库存中...')).toBeTruthy();

    await act(async () => { oldRefreshPage.resolve(page([batch('late-old', 'LATE-OLD', '迟到旧数据')], 1)); });
    expect(view.getByText('加载库存中...')).toBeTruthy();
    expect(view.queryByText('迟到旧数据')).toBeNull();

    await act(async () => {
      replacementPage.resolve(page([batch('fresh-new', 'FRESH-NEW', '新代次批次')], 1));
      replacementSummary.resolve({
        success: true,
        message: 'ok',
        data: { entityType: 'inventory', stats: [{ label: '摘要', value: 'NEW-SUMMARY' }] },
      });
    });
    await waitFor(() => expect(view.getByText('新代次批次')).toBeTruthy());
    expect(view.getByText(/NEW-SUMMARY/)).toBeTruthy();
  });

  it('keeps same-id permission replacement and ABA responses from the prior owner', async () => {
    const originalActor = actor('F006', 77);
    setupStore(originalActor);
    const oldPage = deferred<unknown>();
    getBatches
      .mockReturnValueOnce(oldPage.promise as never)
      .mockResolvedValueOnce(page([], 1))
      .mockResolvedValueOnce(page([], 1));

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(getBatches).toHaveBeenCalledTimes(1));

    const replacementActor = actor('F006', 77);
    replacementActor.factoryUser.modulePermissions = { inventory: 'r' };
    await act(async () => { useAuthStore.setState({ user: replacementActor }); });
    await waitFor(() => expect(getBatches).toHaveBeenCalledTimes(2));
    await act(async () => { useAuthStore.setState({ user: originalActor }); });
    await waitFor(() => expect(getBatches).toHaveBeenCalledTimes(3));

    await act(async () => { oldPage.resolve(page([batch('old-batch', 'OLD-001', '旧权限数据')], 2)); });
    expect(getBatches).not.toHaveBeenCalledWith({ factoryId: 'F006', page: 2, size: 50 });
    expect(view.queryByText('旧权限数据')).toBeNull();
    expect(view.queryByTestId('wh-inventory-load-error')).toBeNull();
  });

  it('invalidates a batched A to B to A auth transition even when React sees only the final A', async () => {
    const originalActor = actor('F006', 77);
    setupStore(originalActor);
    const oldPage = deferred<unknown>();
    getBatches.mockReturnValueOnce(oldPage.promise as never)
      .mockResolvedValueOnce(page([batch('new-aba', 'NEW-ABA', '新代次数据')], 1));

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(getBatches).toHaveBeenCalledTimes(1));
    const replacementActor = actor('F006', 77);
    replacementActor.factoryUser.modulePermissions = { inventory: 'r' };
    await act(async () => {
      useAuthStore.setState({ user: replacementActor });
      useAuthStore.setState({ user: originalActor });
    });
    await waitFor(() => expect(getBatches).toHaveBeenCalledTimes(2));
    await act(async () => { oldPage.resolve(page([batch('old-aba', 'OLD-ABA', '旧代次数据')], 2)); });

    expect(view.queryByText('旧代次数据')).toBeNull();
    expect(view.getByText('新代次数据')).toBeTruthy();
    expect(getBatches).not.toHaveBeenCalledWith({ factoryId: 'F006', page: 2, size: 50 });
  });

  it('ignores a late failure from the previous owner without replacing the current state', async () => {
    const oldActor = actor('F006', 77);
    setupStore(oldActor);
    const oldPage = deferred<unknown>();
    getBatches.mockReturnValueOnce(oldPage.promise as never).mockResolvedValueOnce(page([], 1));

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(getBatches).toHaveBeenCalledTimes(1));
    await act(async () => { useAuthStore.setState({ user: actor('F007', 88) }); });
    await waitFor(() => expect(getBatches).toHaveBeenCalledTimes(2));
    await act(async () => { oldPage.resolve({ success: false, code: 500, message: 'late old failure', data: null }); });

    expect(view.queryByTestId('wh-inventory-load-error')).toBeNull();
    expect(getBatches).not.toHaveBeenCalledWith({ factoryId: 'F006', page: 2, size: 50 });
  });

  it('does not cancel a valid request for a non-owner auth update', async () => {
    setupStore(actor('F006', 77));
    const firstPage = deferred<unknown>();
    getBatches.mockReturnValueOnce(firstPage.promise as never);
    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(getBatches).toHaveBeenCalledTimes(1));

    await act(async () => { useAuthStore.setState({ isLoading: true }); });
    await act(async () => { firstPage.resolve(page([batch('valid-batch', 'VALID-001', '有效请求')], 1)); });
    await waitFor(() => expect(view.getByText('有效请求')).toBeTruthy());
    expect(view.queryByTestId('wh-inventory-load-error')).toBeNull();
  });

  it('does not display a late summary from the previous factory after owner switch', async () => {
    const oldSummary = deferred<unknown>();
    setupStore(actor('F006', 77, 'warehouse_manager'));
    getBatches.mockImplementation(async (params) => page(
      [batch(`summary-${params?.factoryId ?? 'none'}`, 'SUMMARY-001', '摘要批次', params?.factoryId ?? 'F006')],
      1,
    ));
    getSummary.mockImplementation(async (_entity, _request, factoryId) => {
      if (factoryId === 'F006') return oldSummary.promise as never;
      return { success: true, message: 'ok', data: { entityType: 'inventory', stats: [] } };
    });

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByText('摘要批次')).toBeTruthy());
    await waitFor(() => expect(getSummary).toHaveBeenCalledWith('inventory', { filterConditions: {} }, 'F006'));

    await act(async () => { useAuthStore.setState({ user: actor('F007', 88, 'warehouse_manager') }); });
    await waitFor(() => expect(getBatches).toHaveBeenCalledWith({ factoryId: 'F007', page: 1, size: 50 }));
    await act(async () => {
      oldSummary.resolve({
        success: true,
        message: 'ok',
        data: { entityType: 'inventory', stats: [{ label: '旧摘要', value: 'OLD-SUMMARY' }] },
      });
    });
    expect(view.queryByText('OLD-SUMMARY')).toBeNull();
  });

  it('shows a summary failure as a recoverable message and restores it on refresh', async () => {
    setupStore();
    getBatches.mockResolvedValue(page([batch('summary-retry', 'SUMMARY-RETRY', '统计重试批次')], 1));
    let summaryCalls = 0;
    getSummary.mockImplementation(async () => {
      summaryCalls += 1;
      if (summaryCalls <= 1) return { success: false, message: 'summary unavailable', data: null } as never;
      return { success: true, message: 'ok', data: { entityType: 'inventory', stats: [{ label: '摘要', value: 'RECOVERED-SUMMARY' }] } };
    });

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByText('统计暂不可用，可下拉重试')).toBeTruthy());
    const refresh = view.getByTestId('wh-inventory-scroll').props.refreshControl.props.onRefresh as () => void;
    await act(async () => { refresh(); });
    await waitFor(() => expect(view.getByText(/RECOVERED-SUMMARY/)).toBeTruthy());
    expect(summaryCalls).toBeGreaterThanOrEqual(2);
  });

  it('rejects missing factory identity before any inventory request', async () => {
    useAuthStore.setState({ user: null });
    const view = render(<WHInventoryListScreen />);

    await waitFor(() => expect(view.getByTestId('wh-inventory-load-error')).toBeTruthy());
    expect(view.getByText(/缺少工厂身份/)).toBeTruthy();
    expect(getBatches).not.toHaveBeenCalled();
    expect(getStatistics).not.toHaveBeenCalled();
    expect(listPackaging).not.toHaveBeenCalled();
    expect(getSummary).not.toHaveBeenCalled();
  });

  it('rejects a nonempty zero-page response', async () => {
    setupStore();
    getBatches.mockResolvedValueOnce(page([batch('bad-zero', 'BAD-0', '坏分页')], 0));
    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByTestId('wh-inventory-load-error')).toBeTruthy());
  });

  it('rejects invalid quantity instead of substituting zero', async () => {
    setupStore();
    getBatches.mockResolvedValueOnce(page([{ ...batch('bad-quantity', 'BAD-Q', '坏数量'), remainingQuantity: Number.NaN }], 1));
    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByTestId('wh-inventory-load-error')).toBeTruthy());
  });

  it('keeps null storage types explicitly unknown for both available and used-up batches', async () => {
    setupStore();
    getBatches.mockResolvedValueOnce(page([
      batch('unknown-available', 'UNK-A', '未知可用', 'F006', null, 'AVAILABLE'),
      batch('unknown-used', 'UNK-U', '未知已用', 'F006', null, 'USED_UP'),
    ], 1));
    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByText('未知可用')).toBeTruthy());
    expect(view.getByText('未知已用')).toBeTruthy();
    const unknownChip = view.getByTestId('wh-inventory-filter-unknown');
    expect(unknownChip.props.children).toEqual(expect.arrayContaining([2]));
    const freshChip = view.getByTestId('wh-inventory-filter-fresh');
    expect(freshChip.props.children).toEqual(expect.arrayContaining([0]));
  });

  it('rejects a status outside the backend enum instead of guessing a display status', async () => {
    setupStore();
    getBatches.mockResolvedValueOnce(page([
      { ...batch('bad-status', 'BAD-S', '未知状态'), status: 'NOT_A_BACKEND_STATUS' } as never,
    ], 1));
    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByTestId('wh-inventory-load-error')).toBeTruthy());
    expect(view.queryByText('未知状态')).toBeNull();
  });

  it('rejects an unbounded page count without silently truncating', async () => {
    setupStore();
    getBatches.mockResolvedValueOnce(page([], 1001));
    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByTestId('wh-inventory-load-error')).toBeTruthy());
  });

  it('does not let a retry handler captured for the old actor issue a later GET', async () => {
    setupStore(actor('F006', 77));
    const currentPage = deferred<unknown>();
    getBatches
      .mockResolvedValueOnce(page([batch('old-batch', 'OLD-001', '旧账号数据')], 2))
      .mockRejectedValueOnce(new Error('page two unavailable'))
      .mockReturnValueOnce(currentPage.promise as never);

    const view = render(<WHInventoryListScreen />);
    await waitFor(() => expect(view.getByTestId('wh-inventory-retry')).toBeTruthy());
    const staleRetry = view.getByTestId('wh-inventory-retry').props.onPress as () => void;

    const callsBeforeActorChange = getBatches.mock.calls.length;
    await act(async () => { useAuthStore.setState({ user: actor('F007', 88) }); });
    await waitFor(() => expect(getBatches.mock.calls.length).toBeGreaterThan(callsBeforeActorChange));
    const callsAfterActorChange = getBatches.mock.calls.length;
    await act(async () => { staleRetry(); });
    expect(getBatches.mock.calls.length).toBe(callsAfterActorChange);

    await act(async () => { currentPage.resolve(page([], 1)); });
  });
});
