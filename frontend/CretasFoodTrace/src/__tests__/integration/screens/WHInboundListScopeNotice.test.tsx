import React from 'react';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { cleanup, render, waitFor } from '@testing-library/react-native';

jest.mock('@react-navigation/native', () => {
  const React = require('react');
  return {
    ...jest.requireActual('@react-navigation/native'),
    useNavigation: () => ({ navigate: jest.fn(), dispatch: jest.fn(), getParent: () => undefined, getState: () => ({ routeNames: [] }) }),
    useFocusEffect: (effect: () => void | (() => void)) => React.useEffect(effect, [effect]),
  };
});
jest.mock('react-native-safe-area-context', () => {
  const { View } = require('react-native');
  return { SafeAreaView: View, useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }) };
});
jest.mock('../../../services/api/purchaseApiClient', () => ({
  purchaseApiClient: { getOrdersByStatus: jest.fn() },
}));
jest.mock('../../../services/api/warehouseReceivingApiClient', () => ({
  warehouseReceivingApiClient: { listCustomerMaterialArrivalTasks: jest.fn() },
}));
jest.mock('../../../store/authStore', () => ({
  useAuthStore: (selector: (state: { user: { factoryId: string } }) => unknown) => selector({ user: { factoryId: 'F006' } }),
}));
jest.mock('../../../components/ai/FrontlineAIEntry', () => ({ FrontlineAIEntry: () => null }));
jest.mock('../../../utils/errorHandler', () => ({ handleError: jest.fn() }));

import WHInboundListScreen from '../../../screens/warehouse/inbound/WHInboundListScreen';
import { purchaseApiClient } from '../../../services/api/purchaseApiClient';
import { warehouseReceivingApiClient } from '../../../services/api/warehouseReceivingApiClient';
import { handleError } from '../../../utils/errorHandler';

const getOrdersByStatus = jest.mocked(purchaseApiClient.getOrdersByStatus);
const listArrivals = jest.mocked(warehouseReceivingApiClient.listCustomerMaterialArrivalTasks);

const SCOPED_HINT = '按你岗位当前的数据范围显示：只列出你（或你可查看的同事）创建的采购单。'
  + '需要处理全工厂收货，请工厂总监在网页后台「角色权限 → 数据范围」把本岗位改为「全工厂」。';
const REFUSAL_HINT = '你的岗位数据范围不是「全工厂」，这里汇总的是全工厂数据，所以不能查看；'
  + '需要的话请工厂总监在网页后台「角色权限 → 数据范围」调整本岗位。';

type OrdersPage = Awaited<ReturnType<typeof purchaseApiClient.getOrdersByStatus>>;
type Arrivals = Awaited<ReturnType<typeof warehouseReceivingApiClient.listCustomerMaterialArrivalTasks>>;

function page(actionHint?: string): OrdersPage {
  const body = {
    success: true,
    data: { content: [], totalElements: 0, totalPages: 0, page: 1, size: 20 },
    ...(actionHint ? { actionHint } : {}),
  };
  return body as unknown as OrdersPage;
}

function httpError(status: number, data: Record<string, unknown>): AxiosError {
  const config = { headers: {} } as InternalAxiosRequestConfig;
  const response = { status, statusText: '', headers: {}, config, data } as AxiosResponse;
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, undefined, response);
}

const scopeRefusal = () => httpError(403, {
  success: false,
  code: 403,
  errorCode: 'DATA_SCOPE_UNSUPPORTED',
  message: '当前数据范围不支持此快捷查询或批量操作，请使用主列表',
  actionHint: REFUSAL_HINT,
});

function expectEntriesUsable(view: ReturnType<typeof render>) {
  expect(view.getByText('待收货任务')).toBeTruthy();
  expect(view.getByTestId('wh-scan-inbound')).toBeTruthy();
  expect(view.getByTestId('wh-production-receipt-entry')).toBeTruthy();
}

beforeEach(() => {
  listArrivals.mockResolvedValue({ success: true, data: [] } as unknown as Arrivals);
});

afterEach(() => {
  cleanup();
  jest.clearAllMocks();
});

describe('收货页: 岗位数据范围受限时 (2026-11-13 F006 仓库工人)', () => {
  it('受限范围: 4 个状态都按范围成功返回, 不弹错误窗, 入口可用, 显示后端说明', async () => {
    getOrdersByStatus.mockResolvedValue(page(SCOPED_HINT));

    const view = render(<WHInboundListScreen />);

    await waitFor(() => expect(view.getByTestId('wh-inbound-scope-notice')).toBeTruthy());
    expect(view.getByText(SCOPED_HINT)).toBeTruthy();
    expect(view.queryByText('入库任务加载失败')).toBeNull();
    expect(view.queryByText('重试')).toBeNull();
    expect(handleError).not.toHaveBeenCalled();
    expectEntriesUsable(view);
    expect(getOrdersByStatus).toHaveBeenCalledTimes(4);
  });

  it('真正的范围拒绝: 显示原因和找谁调整, 不弹错误窗, 不提示重试', async () => {
    getOrdersByStatus.mockRejectedValue(scopeRefusal());

    const view = render(<WHInboundListScreen />);

    await waitFor(() => expect(view.getByText('部分入库任务不在你的数据范围内')).toBeTruthy());
    expect(view.getByText(REFUSAL_HINT)).toBeTruthy();
    expect(view.queryByText('入库任务加载失败')).toBeNull();
    expect(view.queryByText('部分入库任务加载失败')).toBeNull();
    expect(view.queryByText('重试')).toBeNull();
    expect(view.queryByText('重试失败来源')).toBeNull();
    expect(handleError).not.toHaveBeenCalled();
    expectEntriesUsable(view);
  });

  it('阴性对照: 网络/服务错误仍按原样报错并给重试', async () => {
    getOrdersByStatus.mockRejectedValue(httpError(500, { success: false, message: '服务暂时不可用' }));

    const view = render(<WHInboundListScreen />);

    await waitFor(() => expect(view.getByText('入库任务加载失败')).toBeTruthy());
    expect(view.getByText('重试')).toBeTruthy();
    expect(handleError).toHaveBeenCalled();
    expect(view.queryByTestId('wh-inbound-scope-notice')).toBeNull();
  });

  it('全工厂范围 (阴性对照): 没有说明条', async () => {
    getOrdersByStatus.mockResolvedValue(page());

    const view = render(<WHInboundListScreen />);

    await waitFor(() => expect(view.getByText('当前筛选下没有入库任务')).toBeTruthy());
    expect(view.queryByTestId('wh-inbound-scope-notice')).toBeNull();
    expect(handleError).not.toHaveBeenCalled();
  });
});
