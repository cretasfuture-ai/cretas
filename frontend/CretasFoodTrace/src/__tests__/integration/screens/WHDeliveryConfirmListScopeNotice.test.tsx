import React from 'react';
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
jest.mock('../../../services/api/warehouseDeliveryApiClient', () => ({
  warehouseDeliveryApiClient: { getPendingDeliveries: jest.fn(), getDeliveryHistory: jest.fn() },
}));
jest.mock('../../../components/ai/FrontlineAIEntry', () => ({ FrontlineAIEntry: () => null }));
jest.mock('../../../utils/errorHandler', () => ({ handleError: jest.fn() }));

import { WHDeliveryConfirmListScreen } from '../../../screens/warehouse/outbound/WHDeliveryConfirmListScreen';
import { warehouseDeliveryApiClient } from '../../../services/api/warehouseDeliveryApiClient';
import { handleError } from '../../../utils/errorHandler';

const getPending = jest.mocked(warehouseDeliveryApiClient.getPendingDeliveries);
const getHistory = jest.mocked(warehouseDeliveryApiClient.getDeliveryHistory);

const HINT = '按你岗位当前的数据范围显示：只列出你（或你可查看的同事）创建的销售单、客户对应的发货单。'
  + '需要处理全工厂发货，请工厂总监在网页后台「角色权限 → 数据范围」把本岗位改为「全工厂」。';

function emptyPage(actionHint?: string) {
  return {
    success: true,
    message: '查询成功',
    data: { content: [], totalElements: 0, totalPages: 0, page: 1, size: 20 },
    ...(actionHint ? { actionHint } : {}),
  } as unknown as Awaited<ReturnType<typeof warehouseDeliveryApiClient.getPendingDeliveries>>;
}

afterEach(() => {
  cleanup();
  jest.clearAllMocks();
});

describe('发货页: 岗位数据范围受限时的说明 (2026-11-13 F006 仓库工人)', () => {
  it('受限范围: 两条队列都成功返回, 不报错, 列表上方显示后端给的说明', async () => {
    getPending.mockResolvedValue(emptyPage(HINT));
    getHistory.mockResolvedValue(emptyPage(HINT));

    const view = render(<WHDeliveryConfirmListScreen />);

    await waitFor(() => expect(view.getByTestId('wh-outbound-scope-notice')).toBeTruthy());
    expect(view.getByText(HINT)).toBeTruthy();
    expect(view.queryByTestId('wh-outbound-load-error')).toBeNull();
    expect(view.queryByTestId('wh-outbound-partial-error')).toBeNull();
    expect(handleError).not.toHaveBeenCalled();
    // 扫码出库所在的手工发货入口对本岗位同样可见
    expect(view.getByTestId('wh-goto-manual-shipment')).toBeTruthy();
  });

  it('全工厂范围 (阴性对照): 没有说明条', async () => {
    getPending.mockResolvedValue(emptyPage());
    getHistory.mockResolvedValue(emptyPage());

    const view = render(<WHDeliveryConfirmListScreen />);

    await waitFor(() => expect(view.getByText('暂无待确认发货单')).toBeTruthy());
    expect(view.queryByTestId('wh-outbound-scope-notice')).toBeNull();
  });
});
