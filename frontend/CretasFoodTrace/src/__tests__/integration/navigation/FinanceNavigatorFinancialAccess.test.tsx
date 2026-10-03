import React from 'react';
import { act, fireEvent, render } from '@testing-library/react-native';
import type { FactoryUser } from '../../../types/auth';
import { useAuthStore } from '../../../store/authStore';
import FinanceNavigator from '../../../navigation/FinanceNavigator';

const mockDispatch = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ dispatch: mockDispatch }),
  useRoute: () => ({ params: { moduleSet: 'finance' } }),
  CommonActions: { navigate: (payload: unknown) => ({ type: 'NAVIGATE', payload }) },
}));

// Exercise the real navigator's conditional registrations and mounted child lifetime.
function mockCreateNavigator() {
  const R: typeof React = require('react');
  const { View, Text, Pressable } = require('react-native');
  type Registration = { name: string; component: React.ComponentType; options?: { tabBarButtonTestID?: string } };
  return {
    Screen: () => null,
    Navigator: ({ children, initialRouteName }: { children: React.ReactNode; initialRouteName?: string }) => {
      const screens = R.Children.toArray(children).filter(R.isValidElement) as React.ReactElement<Registration>[];
      const [selected, select] = R.useState(initialRouteName ?? screens[0]?.props.name);
      const active = screens.find(s => s.props.name === selected) ?? screens[0];
      return <View>
        {screens.map(s => <Pressable key={s.props.name}
          testID={s.props.options?.tabBarButtonTestID ?? `route-${s.props.name}`}
          onPress={() => select(s.props.name)}><Text>{s.props.name}</Text></Pressable>)}
        {active && R.createElement(active.props.component)}
      </View>;
    },
  };
}
jest.mock('@react-navigation/bottom-tabs', () => ({ createBottomTabNavigator: mockCreateNavigator }));
jest.mock('@react-navigation/native-stack', () => ({ createNativeStackNavigator: mockCreateNavigator }));
jest.mock('../../../hooks/useMyTodos', () => ({ useMyTodoCount: () => ({ count: 0 }) }));
jest.mock('../../../navigation/OperatingReportStackNavigator', () => () => null);
jest.mock('../../../navigation/OATodoStackNavigator', () => () => null);
jest.mock('../../../navigation/ProfileStackNavigator', () => () => null);
jest.mock('../../../screens/factory-admin/finance/CashierPaymentListScreen', () => () => null);
jest.mock('../../../screens/main/components/QuickStatsPanel', () => ({ QuickStatsPanel: () => null }));
jest.mock('../../../screens/factory-admin/inventory/ArApOverviewScreen', () => {
  const R: typeof React = require('react');
  const { Text } = require('react-native');
  const { useAuthStore: store } = require('../../../store/authStore');
  return () => {
    const [owner] = R.useState(() => store.getState().user?.factoryUser?.factoryId);
    return <Text testID="mounted-arap">{owner}</Text>;
  };
});
jest.mock('../../../screens/factory-admin/inventory/PriceListScreen', () => {
  const R: typeof React = require('react');
  const { Text } = require('react-native');
  const { useAuthStore: store } = require('../../../store/authStore');
  return () => {
    const [owner] = R.useState(() => store.getState().user?.factoryUser?.factoryId);
    return <Text testID="mounted-price">{owner}</Text>;
  };
});

function actor(role: FactoryUser['factoryUser']['role'] = 'finance_manager', finance: 'r' | 'rw' | '-' = 'rw', factoryId = 'F006', id = 61): FactoryUser {
  return {
    id, username: `finance-${id}`, email: '', fullName: '财务测试', userType: 'factory',
    createdAt: '', updatedAt: '', isActive: true,
    factoryUser: { factoryId, role, permissions: ['finance:*'], modulePermissions: { finance } },
  };
}
function setActor(user: FactoryUser | null) {
  act(() => { useAuthStore.setState({ user, isAuthenticated: !!user }); });
}

describe('finance workspace entries use each endpoint scope', () => {
  beforeEach(() => { mockDispatch.mockClear(); setActor(actor()); });
  afterEach(() => setActor(null));

  it('offers real home cards, tab and both registered financial screens to finance managers', () => {
    const view = render(<FinanceNavigator />);
    fireEvent.press(view.getByTestId('home-module-arAp'));
    expect(mockDispatch).toHaveBeenLastCalledWith({ type: 'NAVIGATE', payload: {
      name: 'FinanceWorkspaceTab', params: { screen: 'ArApOverview' },
    } });
    fireEvent.press(view.getByTestId('home-module-priceList'));
    expect(mockDispatch).toHaveBeenLastCalledWith({ type: 'NAVIGATE', payload: {
      name: 'FinanceWorkspaceTab', params: { screen: 'PriceList' },
    } });
    expect(view.queryByTestId('cashier-tab-payment')).toBeNull();
    fireEvent.press(view.getByTestId('finance-tab-workspace'));
    expect(view.getByTestId('mounted-arap').props.children).toBe('F006');
    fireEvent.press(view.getByTestId('route-PriceList'));
    expect(view.getByTestId('mounted-price').props.children).toBe('F006');
  });

  it('keeps cashier payment entry and finance reader, without granting price scope', () => {
    setActor(actor('cashier', 'r'));
    const view = render(<FinanceNavigator />);
    expect(view.getByTestId('cashier-tab-payment')).toBeTruthy();
    expect(view.getByTestId('home-module-arAp')).toBeTruthy();
    expect(view.queryByTestId('home-module-priceList')).toBeNull();
    fireEvent.press(view.getByTestId('finance-tab-workspace'));
    expect(view.getByTestId('mounted-arap')).toBeTruthy();
    expect(view.queryByTestId('route-PriceList')).toBeNull();
  });

  it('retains independently authorized prices after finance permission is revoked', () => {
    const view = render(<FinanceNavigator />);
    fireEvent.press(view.getByTestId('finance-tab-workspace'));
    expect(view.getByTestId('mounted-arap')).toBeTruthy();
    setActor(actor('finance_manager', '-'));
    expect(view.queryByTestId('mounted-arap')).toBeNull();
    expect(view.queryByTestId('home-module-arAp')).toBeNull();
    expect(view.getByTestId('home-module-priceList')).toBeTruthy();
    fireEvent.press(view.getByTestId('finance-tab-workspace'));
    expect(view.getByTestId('mounted-price')).toBeTruthy();
    expect(view.queryByTestId('route-ArApOverview')).toBeNull();
  });

  it('unmounts displayed data on actor change and resets the same actor access level', () => {
    const view = render(<FinanceNavigator />);
    fireEvent.press(view.getByTestId('finance-tab-workspace'));
    expect(view.getByTestId('mounted-arap').props.children).toBe('F006');
    setActor(actor('finance_manager', 'rw', 'F007', 62));
    expect(view.queryByTestId('mounted-arap')).toBeNull();
    fireEvent.press(view.getByTestId('finance-tab-workspace'));
    expect(view.getByTestId('mounted-arap').props.children).toBe('F007');
    setActor(actor('finance_manager', 'r', 'F007', 62));
    expect(view.queryByTestId('mounted-arap')).toBeNull();
    fireEvent.press(view.getByTestId('finance-tab-workspace'));
    expect(view.getByTestId('mounted-arap').props.children).toBe('F007');
  });

  it('removes business routes and cards immediately when both scopes disappear', () => {
    setActor(actor('cashier', 'r'));
    const view = render(<FinanceNavigator />);
    fireEvent.press(view.getByTestId('finance-tab-workspace'));
    setActor(actor('cashier', '-'));
    expect(view.queryByTestId('mounted-arap')).toBeNull();
    expect(view.queryByTestId('finance-tab-workspace')).toBeNull();
    expect(view.queryByTestId('home-module-arAp')).toBeNull();
    expect(view.queryByTestId('home-module-priceList')).toBeNull();
  });

  it('does not register finance or price routes without a factory identity', () => {
    setActor(actor('finance_manager', 'rw', ''));
    const view = render(<FinanceNavigator />);
    expect(view.queryByTestId('finance-tab-workspace')).toBeNull();
    expect(view.queryByTestId('home-module-arAp')).toBeNull();
    expect(view.queryByTestId('home-module-priceList')).toBeNull();
  });
});
