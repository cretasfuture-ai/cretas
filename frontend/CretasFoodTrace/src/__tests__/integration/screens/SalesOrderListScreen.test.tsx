/**
 * SalesOrderListScreen — 状态 tab 必须真的重筛列表 (BUG-SALES-ORDER-LIST-STATUS-TAB, 2026-09-06)
 *
 * 被守的行为(不是某一行实现):
 *   1. 选中某个状态 tab 时, 列表数据必须从**按状态筛的那个端点**取 (/orders/by-status);
 *      打 /orders 是无效的 —— SalesController.listOrders(factoryId, keyword, page, size)
 *      没有 status 形参, Spring 静默丢弃它, 列表恒为全量。
 *   2. 列表里渲染出来的条数, 必须与底部合计「共 N 条」一致 —— 这正是用户眼睛做的那次读数
 *      (线上现象: 「已确认」显示「共 0 条 / ¥0.00」, 下面却摆着 4 张卡片)。
 *
 * ⚠️ 桩的形状按**生产那侧真实的样子**给: listSummaryApiClient 的桩按 status 过滤,
 *    因为后端 ListSummaryServiceImpl.computeSalesOrderSummary 确实过滤 (appendStatusFilter);
 *    列表侧的桩也过滤。两侧一致才是修好之后的样子, 不一致(阴性对照)才是缺陷的样子。
 */

import React from 'react';
import { render, fireEvent, waitFor, within } from '@testing-library/react-native';
import { Alert } from 'react-native';
import SalesOrderListScreen from '../../../screens/factory-admin/inventory/SalesOrderListScreen';
import { salesApiClient } from '../../../services/api/salesApiClient';
import { listSummaryApiClient } from '../../../services/api/listSummaryApiClient';
import { useAuthStore } from '../../../store/authStore';
import type { FactoryUser } from '../../../types/auth';

// ========== Mocks ==========

/**
 * 形态 B: 打桩打在**屏幕真正 import 的那个模块**上。
 * callLog 记录「第一次调的是哪个客户端方法」—— 断言失败时这个读数直接指出
 * 是打回了 /orders 还是根本没发请求 (两者的 "被调用 0 次" 长得一模一样)。
 */
const callLog: string[] = [];

jest.mock('../../../services/api/salesApiClient', () => ({
  salesApiClient: {
    getOrders: jest.fn(),
    getOrdersByStatus: jest.fn(),
    confirmOrder: jest.fn(),
    cancelOrder: jest.fn(),
  },
}));

jest.mock('../../../services/api/listSummaryApiClient', () => ({
  listSummaryApiClient: { getSummary: jest.fn() },
}));

jest.mock('../../../services/api/printApiClient', () => ({
  safePrint: jest.fn(),
}));

/**
 * 只替身 RowActionBottomSheet, ⛔ 不替身 StickyFooterSummary ——
 * 底部合计正是本文件要读的那个数, 替掉它这套断言就成了自问自答。
 *
 * 换掉它的理由与本次改动无关: 共享的 react-native-paper 替身里 useTheme() 返回
 * MD3LightTheme, 上面没有 `custom.spacing`, 而 RowActionBottomSheet 在 makeStyles
 * 里解构它 ⇒ 渲染期 TypeError。那是那份共享替身的缺口, 不在本 PR 范围内
 * (共享文件, 别的 executor 也在改)。
 */
jest.mock('../../../components/list', () => {
  const actual = jest.requireActual('../../../components/list');
  const R = require('react');
  const { View } = require('react-native');
  return {
    ...actual,
    RowActionBottomSheet: (props: Record<string, unknown>) =>
      R.createElement(View, { testID: 'row-action-sheet-mock', ...props }),
  };
});

jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
  SafeAreaProvider: ({ children }: { children: React.ReactNode }) => children,
  SafeAreaView: ({ children, ...props }: Record<string, unknown> & { children?: React.ReactNode }) => {
    const { View } = require('react-native');
    return <View {...props}>{children}</View>;
  },
}));

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();
const mockDispatch = jest.fn();

jest.mock('@react-navigation/native', () => ({
  ...jest.requireActual('@react-navigation/native'),
  useNavigation: () => ({
    navigate: mockNavigate,
    goBack: mockGoBack,
    dispatch: mockDispatch,
    setOptions: jest.fn(),
    addListener: jest.fn(),
  }),
  useRoute: () => ({ params: {} }),
  // ⚠️ deps 必须是 [cb]。真的 useFocusEffect 在回调 identity 变化且聚焦时会重跑,
  // 而 loadOrders 进了 useCallback([statusFilter]) —— 写死 [] 等于「只在挂载时拉一次」,
  // 切 tab 后一次请求都不会发, 断言读到的 "0 次" 是 mock 语义不对, 不是屏幕回归。
  useFocusEffect: (cb: () => void) => {
    const R = require('react');
    R.useEffect(() => { cb(); }, [cb]);
  },
  useNavigationState: (selector: (state: unknown) => unknown) =>
    selector({ index: 0, routes: [{ key: 'test-0', name: 'Test' }] }),
}));

const mockedSalesApi = salesApiClient as jest.Mocked<typeof salesApiClient>;
const mockedSummaryApi = listSummaryApiClient as jest.Mocked<typeof listSummaryApiClient>;

// ========== Test Data ==========

type TestOrder = {
  id: string;
  factoryId: string;
  orderNumber: string;
  customerId: string;
  customerName: string;
  status: string;
  totalAmount: number;
  orderDate: string;
  createdAt: string;
};

const makeOrder = (n: number, status: string): TestOrder => ({
  id: `so-${n}`,
  factoryId: 'F006',
  orderNumber: `SO-2026-${String(n).padStart(3, '0')}`,
  customerId: `cust-${n}`,
  customerName: `客户${n}`,
  status,
  totalAmount: 1000 + n,
  orderDate: '2026-09-01',
  createdAt: '2026-09-01T00:00:00Z',
});

// 5 DRAFT + 4 CONFIRMED + 5 COMPLETED = 14 —— 与线上实测的那一屏同形
// (已确认 4 张卡片配「共 0 条」, 已完成 「共 5 条」配 14 张卡片)。
const ALL_ORDERS: TestOrder[] = [
  ...[1, 2, 3, 4, 5].map((n) => makeOrder(n, 'DRAFT')),
  ...[6, 7, 8, 9].map((n) => makeOrder(n, 'CONFIRMED')),
  ...[10, 11, 12, 13, 14].map((n) => makeOrder(n, 'COMPLETED')),
];

const page = (rows: TestOrder[]) => ({
  success: true as const,
  data: { content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 50 },
});

const ordersWithStatus = (status?: string) =>
  status == null ? ALL_ORDERS : ALL_ORDERS.filter((o) => o.status === status);

/** 后端 ListSummaryServiceImpl 的形状: 按 filterConditions.status 过滤后 COUNT + SUM。 */
const summaryFor = (status?: string) => {
  const rows = ordersWithStatus(status);
  const total = rows.reduce((sum, o) => sum + o.totalAmount, 0);
  return {
    success: true,
    data: {
      entityType: 'salesOrder',
      stats: [
        { label: '共', value: rows.length, format: 'number' as const, unit: '条', canViewPrice: false },
        { label: '总金额', value: total, format: 'currency' as const, unit: '¥', canViewPrice: true },
      ],
    },
  };
};

const mockSalesManager: FactoryUser = {
  id: 77,
  username: 'f006_sales_mgr',
  email: 'sales@cretas.com',
  fullName: '销售经理',
  isActive: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  userType: 'factory',
  factoryId: 'F006',
  factoryUser: { role: 'sales_manager', factoryId: 'F006', permissions: [] },
};

// ========== Helpers ==========

/** 底部合计里「共 N 条」的 N —— 用户眼睛读的那个数。 */
const readFooterCount = (utils: ReturnType<typeof render>): number => {
  const footer = utils.getByTestId('sticky-footer-summary');
  const node = within(footer).getByText(/^[\d,]+条$/);
  const raw = Array.isArray(node.props.children)
    ? node.props.children.join('')
    : String(node.props.children);
  return Number(raw.replace(/[^\d]/g, ''));
};

/** 列表里真正渲染出来的卡片张数。 */
const countRenderedCards = (utils: ReturnType<typeof render>): number =>
  utils.queryAllByTestId(/^sales-order-card-/).length;

/**
 * 探针输出。⚠️ ⛔ 不用 console.log —— src/__tests__/setup.ts 把 log/debug/info
 * 全静默了(只留 error/warn), 用它写出来的读数在报告里**一行都看不到**,
 * 而「看不到」和「没打印」长得一模一样。process.stdout.write 绕过那层替身。
 */
const probe = (line: string) => { process.stdout.write('[SALES-LIST-PROBE] ' + line + '\n'); };

const renderScreen = () => render(<SalesOrderListScreen />);

describe('SalesOrderListScreen — viewer read boundary and masked money', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedSummaryApi.getSummary.mockResolvedValue({
      success: true,
      data: { entityType: 'salesOrder', stats: [
        { label: '共', value: 2, format: 'number', unit: '条', canViewPrice: false },
        { label: '总金额', value: 100, format: 'currency', unit: '¥', canViewPrice: true },
      ] },
    });
    mockedSalesApi.getOrders.mockResolvedValue({
      success: true,
      data: { ...page([]).data, content: [
        { ...makeOrder(1, 'DRAFT'), status: 'DRAFT', totalAmount: null },
        { ...makeOrder(2, 'DRAFT'), status: 'DRAFT', totalAmount: 0 },
      ] },
    });
  });

  it('shows a missing amount distinctly from true zero and removes viewer write actions', async () => {
    const viewer: FactoryUser = {
      ...mockSalesManager,
      factoryUser: { ...mockSalesManager.factoryUser, role: 'viewer', modulePermissions: { sales: 'r' } },
    };
    useAuthStore.setState({ user: viewer, isAuthenticated: true });
    const utils = renderScreen();
    await utils.findByText('金额未提供');
    expect(utils.getByText('¥0')).toBeTruthy();
    expect(utils.queryByTestId('sales-order-create-action')).toBeNull();
    expect(utils.queryByTestId('sales-order-confirm-so-1')).toBeNull();
    expect(utils.queryByTestId('sales-order-cancel-so-1')).toBeNull();
    fireEvent(utils.getByTestId('sales-order-card-so-1'), 'longPress');
    expect(utils.queryByTestId('row-action-sheet-mock')).toBeNull();
    await waitFor(() => expect(readFooterCount(utils)).toBe(2));
    expect(utils.queryByTestId('sticky-footer-ai')).toBeNull();
    expect(mockedSalesApi.confirmOrder).not.toHaveBeenCalled();
    expect(mockedSalesApi.cancelOrder).not.toHaveBeenCalled();
  });

  it('retains creation and confirmation for a sales manager', async () => {
    useAuthStore.setState({ user: mockSalesManager, isAuthenticated: true });
    const utils = renderScreen();
    await utils.findByText('金额未提供');
    expect(utils.getByTestId('sales-order-create-action')).toBeTruthy();
    expect(utils.getByTestId('sales-order-confirm-so-1')).toBeTruthy();
  });
});

const tapTab = async (utils: ReturnType<typeof render>, status: string) => {
  fireEvent.press(utils.getByTestId(`sales-order-list-status-${status}`));
  await waitFor(() => expect(callLog.length).toBeGreaterThan(0));
};

// ========== Tests ==========

describe('SalesOrderListScreen — 状态 tab 重筛', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    callLog.length = 0;

    useAuthStore.setState({
      user: mockSalesManager,
      isAuthenticated: true,
      tokens: null,
      isLoading: false,
    });

    mockedSalesApi.getOrders.mockImplementation((params?: unknown) => {
      callLog.push(`getOrders:${JSON.stringify(params ?? null)}`);
      return Promise.resolve(page(ALL_ORDERS)) as never;
    });
    mockedSalesApi.getOrdersByStatus.mockImplementation((status: string, params?: unknown) => {
      callLog.push(`getOrdersByStatus:${status}:${JSON.stringify(params ?? null)}`);
      return Promise.resolve(page(ordersWithStatus(status))) as never;
    });
    mockedSummaryApi.getSummary.mockImplementation((_entity: unknown, request?: unknown) => {
      const status = (request as { filterConditions?: { status?: string } } | undefined)
        ?.filterConditions?.status;
      return Promise.resolve(summaryFor(status)) as never;
    });
  });

  it('渲染: 默认「全部」档取全量列表, ⛔ 不打 by-status', async () => {
    const utils = renderScreen();

    await waitFor(() => expect(countRenderedCards(utils)).toBe(ALL_ORDERS.length));

    probe(`全部 first client call = ${callLog[0]}`);

    expect(callLog[0]).toBe('getOrders:{"page":1,"size":50}');
    expect(mockedSalesApi.getOrders).toHaveBeenCalledWith({ page: 1, size: 50 });
    expect(mockedSalesApi.getOrdersByStatus).not.toHaveBeenCalled();
  });

  it('点「已完成」⇒ 打 getOrdersByStatus("COMPLETED"), ⛔ 不打 getOrders', async () => {
    const utils = renderScreen();
    await waitFor(() => expect(mockedSalesApi.getOrders).toHaveBeenCalled());

    jest.clearAllMocks();
    callLog.length = 0;
    await tapTab(utils, 'COMPLETED');

    probe(`已完成 first client call = ${callLog[0]}`);

    // 变异「改回 getOrders(params) 且 params.status=...」时, 这一条读出的是
    // getOrders:{"page":1,"size":50,"status":"COMPLETED"} ⇒ 当场红, 且红的信息里
    // 直接写着它打错了哪个端点。
    expect(callLog[0]).toBe('getOrdersByStatus:COMPLETED:{"page":1,"size":50}');
    expect(mockedSalesApi.getOrdersByStatus).toHaveBeenCalledWith('COMPLETED', { page: 1, size: 50 });
    expect(mockedSalesApi.getOrders).not.toHaveBeenCalled();
  });

  it('点「已确认」⇒ 打 getOrdersByStatus("CONFIRMED"), 卡片只剩该状态的 4 条', async () => {
    const utils = renderScreen();
    await waitFor(() => expect(mockedSalesApi.getOrders).toHaveBeenCalled());

    jest.clearAllMocks();
    callLog.length = 0;
    await tapTab(utils, 'CONFIRMED');

    await waitFor(() => expect(countRenderedCards(utils)).toBe(4));
    expect(mockedSalesApi.getOrdersByStatus).toHaveBeenCalledWith('CONFIRMED', { page: 1, size: 50 });
    expect(mockedSalesApi.getOrders).not.toHaveBeenCalled();
  });

  it('从「已完成」切回「全部」⇒ 反向也成立: 打 getOrders, ⛔ 不打 by-status', async () => {
    const utils = renderScreen();
    await waitFor(() => expect(mockedSalesApi.getOrders).toHaveBeenCalled());
    await tapTab(utils, 'COMPLETED');
    await waitFor(() => expect(countRenderedCards(utils)).toBe(5));

    jest.clearAllMocks();
    callLog.length = 0;
    await tapTab(utils, 'all');

    probe(`回到全部 first client call = ${callLog[0]}`);

    expect(callLog[0]).toBe('getOrders:{"page":1,"size":50}');
    expect(mockedSalesApi.getOrders).toHaveBeenCalledWith({ page: 1, size: 50 });
    expect(mockedSalesApi.getOrdersByStatus).not.toHaveBeenCalled();
  });

  it('tab 的 value 逐字等于后端 SalesOrderStatus 枚举名 (拼错不是筛不到, 是 400)', async () => {
    const utils = renderScreen();
    await waitFor(() => expect(mockedSalesApi.getOrders).toHaveBeenCalled());

    // 后端 SalesOrderStatus 里真实存在的名字; 三个可点的状态档必须落在这个集合里。
    const BACKEND_ENUM_NAMES = [
      'DRAFT', 'CONFIRMED', 'PENDING_FINANCE_REVIEW', 'FINANCE_APPROVED', 'FINANCE_REJECTED',
      'PROCESSING', 'PARTIAL_DELIVERED', 'COMPLETED', 'CANCELLED', 'SHORT_CLOSED',
    ];

    for (const status of ['DRAFT', 'CONFIRMED', 'COMPLETED']) {
      jest.clearAllMocks();
      callLog.length = 0;
      await tapTab(utils, status);
      const firstCall = mockedSalesApi.getOrdersByStatus.mock.calls[0];
      expect(firstCall).toBeDefined();
      const sent = String(firstCall?.[0]);
      expect(BACKEND_ENUM_NAMES).toContain(sent);
      expect(sent).toBe(status);
    }
  });

  it('by-status 的 403 原样显示后端文案, ⛔ 不吞成通用的「加载销售单失败」', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const utils = renderScreen();
    await waitFor(() => expect(mockedSalesApi.getOrders).toHaveBeenCalled());

    // SalesController.listOrdersByStatus 对 PENDING_FINANCE_REVIEW 的 RBAC 分支:
    // BusinessException(403) → GlobalExceptionHandler 映射成 HTTP 403 → axios reject。
    mockedSalesApi.getOrdersByStatus.mockImplementation((status: string) => {
      callLog.push(`getOrdersByStatus:${status}:403`);
      return Promise.reject({
        response: { status: 403, data: { message: '无权查看待财审销售单' } },
        message: 'Request failed with status code 403',
      }) as never;
    });

    alertSpy.mockClear();
    callLog.length = 0;
    await tapTab(utils, 'COMPLETED');

    await waitFor(() => expect(alertSpy).toHaveBeenCalled());
    const firstAlert = alertSpy.mock.calls[0];
    expect(firstAlert).toBeDefined();
    const shown = firstAlert?.[1];
    probe(`403 alert body = ${shown}`);
    expect(shown).toBe('无权查看待财审销售单');
    expect(shown).not.toBe('加载销售单失败');

    alertSpy.mockRestore();
  });

  it('网络层失败(后端没给业务文案)仍然回落到「加载销售单失败」, ⛔ 不把 axios 原文案端给用户', async () => {
    const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    const utils = renderScreen();
    await waitFor(() => expect(mockedSalesApi.getOrders).toHaveBeenCalled());

    mockedSalesApi.getOrdersByStatus.mockImplementation((status: string) => {
      callLog.push(`getOrdersByStatus:${status}:network`);
      return Promise.reject(new Error('Network Error')) as never;
    });

    alertSpy.mockClear();
    callLog.length = 0;
    await tapTab(utils, 'COMPLETED');

    await waitFor(() => expect(alertSpy).toHaveBeenCalled());
    const firstAlert = alertSpy.mock.calls[0];
    expect(firstAlert).toBeDefined();
    expect(firstAlert?.[1]).toBe('加载销售单失败');

    alertSpy.mockRestore();
  });
});

/**
 * 屏级不变式: 一页装得下的时候 (返回条数 < size), 列表卡片数 == 底部「共 N 条」。
 *
 * 这条守的正是**用户眼睛做的那次读数**。⚠️ 两个数必须来自两条独立的链路
 * (卡片来自 salesApiClient, 合计来自 listSummaryApiClient), 否则它是恒真式;
 * 下面的阴性对照就是用来证明它不是恒真式的。
 */
describe('SalesOrderListScreen — 列表条数与底部合计的一致性', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    callLog.length = 0;
    useAuthStore.setState({
      user: mockSalesManager,
      isAuthenticated: true,
      tokens: null,
      isLoading: false,
    });
    mockedSummaryApi.getSummary.mockImplementation((_entity: unknown, request?: unknown) => {
      const status = (request as { filterConditions?: { status?: string } } | undefined)
        ?.filterConditions?.status;
      return Promise.resolve(summaryFor(status)) as never;
    });
  });

  it.each([
    ['all', ALL_ORDERS.length],
    ['CONFIRMED', 4],
    ['COMPLETED', 5],
  ])('「%s」档: 渲染卡片数 == 底部合计条数 (期望 %i)', async (status, expected) => {
    mockedSalesApi.getOrders.mockImplementation((params?: unknown) => {
      callLog.push(`getOrders:${JSON.stringify(params ?? null)}`);
      return Promise.resolve(page(ALL_ORDERS)) as never;
    });
    mockedSalesApi.getOrdersByStatus.mockImplementation((s: string, params?: unknown) => {
      callLog.push(`getOrdersByStatus:${s}:${JSON.stringify(params ?? null)}`);
      return Promise.resolve(page(ordersWithStatus(s))) as never;
    });

    const utils = renderScreen();
    await waitFor(() => expect(mockedSalesApi.getOrders).toHaveBeenCalled());
    if (status !== 'all') await tapTab(utils, status);

    await waitFor(() => expect(countRenderedCards(utils)).toBe(expected));
    await waitFor(() => expect(readFooterCount(utils)).toBe(expected));

    const rendered = countRenderedCards(utils);
    const footer = readFooterCount(utils);
    probe(`tab=${status} rendered=${rendered} footer=${footer}`);

    // 返回条数 < size(50) ⇒ 不存在分页差, 两个数必须相等。
    expect(rendered).toBeLessThan(50);
    expect(rendered).toBe(footer);
  });

  it('阴性对照: 服务端忽略了状态筛(缺陷原形) ⇒ 这条不变式必须读出 14 ≠ 5', async () => {
    // 列表侧返回全量(= 修复前 /orders 丢掉 status 的效果), 合计侧照常按状态筛。
    mockedSalesApi.getOrders.mockImplementation(() => Promise.resolve(page(ALL_ORDERS)) as never);
    mockedSalesApi.getOrdersByStatus.mockImplementation((s: string) => {
      callLog.push(`getOrdersByStatus:${s}:IGNORED_FILTER`);
      return Promise.resolve(page(ALL_ORDERS)) as never;
    });

    const utils = renderScreen();
    await waitFor(() => expect(mockedSalesApi.getOrders).toHaveBeenCalled());
    await tapTab(utils, 'COMPLETED');

    await waitFor(() => expect(readFooterCount(utils)).toBe(5));
    const rendered = countRenderedCards(utils);
    const footer = readFooterCount(utils);
    probe(`negative fixture rendered=${rendered} footer=${footer}`);

    // 仪器读得出差异 ⇒ 上面那条 expect(rendered).toBe(footer) 不是恒真式。
    expect(rendered).toBe(ALL_ORDERS.length);
    expect(footer).toBe(5);
    expect(rendered).not.toBe(footer);
  });
});
