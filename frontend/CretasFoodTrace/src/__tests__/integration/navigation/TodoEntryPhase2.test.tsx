/**
 * B4 第二期: 财务底栏 / 餐饮管理底栏 / 兜底锁定页的「待办」入口只认后端 /my-todos/types。
 * 真实导航器 + 真实 useMyTodoEntry, 只桩 HTTP 客户端。
 * 后端给了类型 → 有待办格 (财务沿用 FinanceTodoTab 路由名, 徽标按 count); 空 → 没有这一格也不查条数。
 * 锁定页: 没待办时仍只有锁定说明 (阴性), 有待办时多一格待办但仍不出现业务入口 (阳性)。
 */
import React from 'react';
import { act, render, waitFor } from '@testing-library/react-native';
import type { FactoryUser } from '../../../types/auth';
import { useAuthStore } from '../../../store/authStore';
import FinanceNavigator from '../../../navigation/FinanceNavigator';
import RestrictedRoleNavigator from '../../../navigation/RestrictedRoleNavigator';
import { FactoryAdminTabNavigator } from '../../../navigation/FactoryAdminTabNavigator';

const mockGetTypes = jest.fn();
const mockGetCount = jest.fn();
jest.mock('../../../services/api/myTodoApiClient', () => ({
  myTodoApiClient: {
    getMyTodoTypes: (...a: unknown[]) => mockGetTypes(...a),
    getMyTodoCount: (...a: unknown[]) => mockGetCount(...a),
    getMyTodos: jest.fn(),
  },
}));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ dispatch: jest.fn(), navigate: jest.fn() }),
  useRoute: () => ({ params: { moduleSet: 'finance' } }),
  CommonActions: { navigate: (p: unknown) => ({ type: 'NAVIGATE', payload: p }) },
}));

function mockCreateNavigator() {
  const R: typeof React = require('react');
  const { View, Text, Pressable } = require('react-native');
  type Registration = {
    name: string;
    component: React.ComponentType;
    options?: { tabBarButtonTestID?: string; tabBarBadge?: number };
  };
  return {
    Screen: () => null,
    Navigator: ({ children, initialRouteName }: { children: React.ReactNode; initialRouteName?: string }) => {
      const screens = R.Children.toArray(children).filter(R.isValidElement) as React.ReactElement<Registration>[];
      const [selected] = R.useState(initialRouteName ?? screens[0]?.props.name);
      const active = screens.find((s) => s.props.name === selected) ?? screens[0];
      return (
        <View>
          {screens.map((s) => (
            <Pressable key={s.props.name} testID={s.props.options?.tabBarButtonTestID ?? `route-${s.props.name}`}>
              <Text>{s.props.name}</Text>
              {s.props.options?.tabBarBadge !== undefined && (
                <Text testID={`badge-${s.props.name}`}>{String(s.props.options.tabBarBadge)}</Text>
              )}
            </Pressable>
          ))}
          {active && R.createElement(active.props.component)}
        </View>
      );
    },
  };
}
jest.mock('@react-navigation/bottom-tabs', () => ({ createBottomTabNavigator: mockCreateNavigator }));
jest.mock('@react-navigation/native-stack', () => ({ createNativeStackNavigator: mockCreateNavigator }));
jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }) }));
jest.mock('../../../navigation/OATodoStackNavigator', () => () => null);
jest.mock('../../../navigation/OperatingReportStackNavigator', () => () => null);
jest.mock('../../../navigation/AnalyticsReportStackNavigator', () => () => null);
jest.mock('../../../navigation/ProfileStackNavigator', () => () => null);
jest.mock('../../../navigation/SmartBIStackNavigator', () => () => null);
jest.mock('../../../navigation/factory-admin/FAHomeStackNavigator', () => () => null);
jest.mock('../../../navigation/factory-admin/FAAIStackNavigator', () => () => null);
jest.mock('../../../navigation/factory-admin/FAManagementStackNavigator', () => () => null);
jest.mock('../../../navigation/factory-admin/FAProfileStackNavigator', () => () => null);
jest.mock('../../../screens/main/HomeScreen', () => () => null);
jest.mock('../../../screens/factory-admin/finance/CashierPaymentListScreen', () => () => null);
jest.mock('../../../screens/factory-admin/inventory/ArApOverviewScreen', () => () => null);
jest.mock('../../../screens/factory-admin/inventory/PriceListScreen', () => () => null);

function actor(): FactoryUser {
  return {
    id: 7,
    username: 'phase2',
    email: '',
    fullName: '测试',
    userType: 'factory',
    createdAt: '',
    updatedAt: '',
    isActive: true,
    factoryUser: {
      factoryId: 'F006',
      role: 'finance_manager',
      permissions: ['finance:*'],
      modulePermissions: { finance: 'rw' },
    },
  };
}

beforeEach(() => {
  mockGetTypes.mockReset();
  mockGetCount.mockReset();
  mockGetCount.mockResolvedValue({ success: true, data: 4 });
  act(() => {
    useAuthStore.setState({ user: actor(), isAuthenticated: true });
  });
});
afterEach(() => {
  act(() => {
    useAuthStore.setState({ user: null, isAuthenticated: false });
  });
});

describe('财务底栏待办按后端类型', () => {
  it('阳性: 后端给了类型 → FinanceTodoTab 在, 徽标 4', async () => {
    mockGetTypes.mockResolvedValue({ success: true, data: ['PURCHASE'] });
    const view = render(<FinanceNavigator />);
    await waitFor(() => expect(view.getByTestId('finance-tab-oa-todo')).toBeTruthy());
    await waitFor(() => expect(view.getByTestId('badge-FinanceTodoTab').props.children).toBe('4'));
  });

  it('阴性: 后端说空 → 没有待办格, 也不查条数', async () => {
    mockGetTypes.mockResolvedValue({ success: true, data: [] });
    const view = render(<FinanceNavigator />);
    await waitFor(() => expect(mockGetTypes).toHaveBeenCalled());
    expect(view.queryByTestId('finance-tab-oa-todo')).toBeNull();
    expect(mockGetCount).not.toHaveBeenCalled();
  });
});

describe('餐饮管理底栏待办按后端类型', () => {
  it('阳性 / 阴性', async () => {
    mockGetTypes.mockResolvedValue({ success: true, data: ['PURCHASE'] });
    const on = render(<FactoryAdminTabNavigator />);
    await waitFor(() => expect(on.getByTestId('fa-tab-oa-todo')).toBeTruthy());
    on.unmount();
    mockGetTypes.mockResolvedValue({ success: true, data: [] });
    const off = render(<FactoryAdminTabNavigator />);
    await waitFor(() => expect(mockGetTypes).toHaveBeenCalledTimes(2));
    expect(off.queryByTestId('fa-tab-oa-todo')).toBeNull();
  });
});

describe('兜底锁定页待办', () => {
  it('阴性: 没待办 → 只有锁定说明, 没有待办格', async () => {
    mockGetTypes.mockResolvedValue({ success: true, data: [] });
    const view = render(<RestrictedRoleNavigator />);
    await waitFor(() => expect(mockGetTypes).toHaveBeenCalled());
    expect(view.getByText('暂无可用的移动工作台')).toBeTruthy();
    expect(view.queryByTestId('restricted-tab-oa-todo')).toBeNull();
  });

  it('阳性: 有待办 → 多一格待办, 锁定说明仍在 (不开放其它业务入口)', async () => {
    mockGetTypes.mockResolvedValue({ success: true, data: ['PURCHASE'] });
    const view = render(<RestrictedRoleNavigator />);
    await waitFor(() => expect(view.getByTestId('restricted-tab-oa-todo')).toBeTruthy());
    expect(view.getByText('暂无可用的移动工作台')).toBeTruthy();
  });
});
