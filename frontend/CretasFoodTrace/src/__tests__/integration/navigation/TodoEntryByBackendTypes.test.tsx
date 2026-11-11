/**
 * 纪律排查 B4「App 按权限拼界面」第一步：各岗位底栏的「待办」入口显不显示，只认后端 /my-todos/types。
 *
 * 用真实的岗位导航器（调度员 / 生产经理 / 操作员）+ 真实 useMyTodoEntry，只把 HTTP 客户端换成桩：
 * - 后端说有能办的类型 → 有「待办」这一格，点进去挂的是待办栈，徽标按 count 出；
 * - 后端说空 / 请求失败 → 这一格不注册，也不去查条数；
 * - 换账号重新问一次（上一个账号的结论不带给下一个）。
 * App 里没有岗位 → 待办的映射：同一个操作员导航器，后端改口给了类型，入口就出现（阳性对照）。
 */
import React from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import type { FactoryUser } from '../../../types/auth';
import { useAuthStore } from '../../../store/authStore';
import DispatcherTabNavigator from '../../../navigation/DispatcherTabNavigator';
import ProductionManagerNavigator from '../../../navigation/ProductionManagerNavigator';
import OperatorNavigator from '../../../navigation/OperatorNavigator';

const mockGetTypes = jest.fn();
const mockGetCount = jest.fn();
jest.mock('../../../services/api/myTodoApiClient', () => ({
  myTodoApiClient: {
    getMyTodoTypes: (...args: unknown[]) => mockGetTypes(...args),
    getMyTodoCount: (...args: unknown[]) => mockGetCount(...args),
    getMyTodos: jest.fn(),
  },
}));

// 真实导航器的条件注册 + 选中 tab 后挂载的组件
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
      const [selected, select] = R.useState(initialRouteName ?? screens[0]?.props.name);
      const active = screens.find((s) => s.props.name === selected) ?? screens[0];
      return (
        <View>
          {screens.map((s) => (
            <Pressable
              key={s.props.name}
              testID={s.props.options?.tabBarButtonTestID ?? `route-${s.props.name}`}
              onPress={() => select(s.props.name)}
            >
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
jest.mock('../../../navigation/OATodoStackNavigator', () => {
  const { Text } = require('react-native');
  return () => <Text testID="mounted-oa-todo-stack">待办栈</Text>;
});
jest.mock('../../../navigation/dispatcher/DSHomeStackNavigator', () => () => null);
jest.mock('../../../navigation/dispatcher/DSPlanStackNavigator', () => () => null);
jest.mock('../../../navigation/dispatcher/DSAIStackNavigator', () => () => null);
jest.mock('../../../navigation/dispatcher/DSPersonnelStackNavigator', () => () => null);
jest.mock('../../../navigation/dispatcher/DSProfileStackNavigator', () => () => null);
jest.mock('../../../navigation/SmartBIStackNavigator', () => () => null);
jest.mock('../../../navigation/ProfileStackNavigator', () => () => null);
jest.mock('../../../screens/processing/ProductionPlanManagementScreen', () => () => null);
jest.mock('../../../screens/common/MobileAccountScreen', () => () => null);
jest.mock('../../../screens/processing/OperatorAssignedProcessScreen', () => () => null);
jest.mock('../../../screens/processing/YieldStepReportScreen', () => () => null);
jest.mock('../../../screens/processing/ShortageAlertDetailScreen', () => () => null);
jest.mock('../../../screens/factory-admin/ai-analysis/AIChatScreen', () => () => null);
jest.mock('../../../hooks/useFrontlineAIEntry', () => ({ useFrontlineAIEntryEnabled: () => false }));

function actor(role: FactoryUser['factoryUser']['role'], id: number): FactoryUser {
  return {
    id, username: `${role}-${id}`, email: '', fullName: role, userType: 'factory',
    createdAt: '', updatedAt: '', isActive: true,
    factoryUser: { factoryId: 'F006', role, permissions: [] },
  };
}
function setActor(user: FactoryUser | null) {
  act(() => { useAuthStore.setState({ user, isAuthenticated: !!user }); });
}

describe('「待办」入口只认后端 /my-todos/types', () => {
  beforeEach(() => {
    mockGetTypes.mockReset();
    mockGetCount.mockReset();
    mockGetCount.mockResolvedValue({ success: true, data: 2 });
  });
  afterEach(() => setActor(null));

  it('调度员: 后端给了强制插单 → 有「待办」格, 徽标 2, 点开挂的是待办栈', async () => {
    setActor(actor('dispatcher', 101));
    mockGetTypes.mockResolvedValue({ success: true, data: ['FORCE_INSERT_APPROVAL'] });
    const view = render(<DispatcherTabNavigator />);

    const tab = await view.findByTestId('dispatcher-tab-oa-todo');
    await waitFor(() => expect(view.getByTestId('badge-OATodoTab').props.children).toBe('2'));
    fireEvent.press(tab);
    expect(view.getByTestId('mounted-oa-todo-stack')).toBeTruthy();
    expect(mockGetTypes).toHaveBeenCalledTimes(1);
  });

  it('生产经理: 后端给了类型但此刻 0 条 → 入口照样在 (不随条数忽隐忽现), 没有徽标', async () => {
    setActor(actor('production_manager', 102));
    mockGetTypes.mockResolvedValue({ success: true, data: ['FORCE_INSERT_APPROVAL'] });
    mockGetCount.mockResolvedValue({ success: true, data: 0 });
    const view = render(<ProductionManagerNavigator />);

    fireEvent.press(await view.findByTestId('production-manager-tab-oa-todo'));
    expect(view.getByTestId('mounted-oa-todo-stack')).toBeTruthy();
    expect(view.queryByTestId('badge-OATodoTab')).toBeNull();
  });

  it('操作员: 后端说空 → 没有「待办」格, 也不查条数', async () => {
    setActor(actor('operator', 103));
    mockGetTypes.mockResolvedValue({ success: true, data: [] });
    const view = render(<OperatorNavigator />);

    await waitFor(() => expect(mockGetTypes).toHaveBeenCalledTimes(1));
    expect(view.getByTestId('operator-tab-profile')).toBeTruthy(); // 底栏确实渲染了
    expect(view.queryByTestId('operator-tab-oa-todo')).toBeNull();
    expect(mockGetCount).not.toHaveBeenCalled();
  });

  it('阳性对照: 同一个操作员导航器, 后端改口给了类型 → 入口出现 (App 里没有岗位白名单)', async () => {
    setActor(actor('operator', 104));
    mockGetTypes.mockResolvedValue({ success: true, data: ['WASTAGE_APPROVAL'] });
    const view = render(<OperatorNavigator />);

    expect(await view.findByTestId('operator-tab-oa-todo')).toBeTruthy();
  });

  it('请求失败 (网络 / 500) → 不显示入口, 不出一个点开就报错的空入口', async () => {
    setActor(actor('dispatcher', 105));
    mockGetTypes.mockRejectedValue(new Error('Network Error'));
    const view = render(<DispatcherTabNavigator />);

    await waitFor(() => expect(mockGetTypes).toHaveBeenCalledTimes(1));
    expect(view.queryByTestId('dispatcher-tab-oa-todo')).toBeNull();
    expect(mockGetCount).not.toHaveBeenCalled();
  });

  it('换账号重新问一次: 上一个账号有入口, 下一个账号后端说空 → 入口消失', async () => {
    setActor(actor('dispatcher', 106));
    mockGetTypes.mockResolvedValueOnce({ success: true, data: ['FORCE_INSERT_APPROVAL'] });
    const view = render(<DispatcherTabNavigator />);
    expect(await view.findByTestId('dispatcher-tab-oa-todo')).toBeTruthy();

    mockGetTypes.mockResolvedValueOnce({ success: true, data: [] });
    setActor(actor('dispatcher', 107));
    await waitFor(() => expect(mockGetTypes).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(view.queryByTestId('dispatcher-tab-oa-todo')).toBeNull());
  });

  it('未登录 → 不问后端, 不显示', async () => {
    setActor(null);
    const view = render(<ProductionManagerNavigator />);
    expect(view.queryByTestId('production-manager-tab-oa-todo')).toBeNull();
    expect(mockGetTypes).not.toHaveBeenCalled();
  });
});
