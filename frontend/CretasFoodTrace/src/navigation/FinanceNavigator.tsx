import OperatingReportStackNavigator from './OperatingReportStackNavigator';
import { canAccessOperatingReports } from '../utils/operatingReportAccess';
import { useFactoryFeatureStore } from '../store/factoryFeatureStore';
import React, { useRef } from "react";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { Icon, Text } from "react-native-paper";
import { View } from 'react-native';

import HomeScreen from "../screens/main/HomeScreen";
import CashierPaymentListScreen from "../screens/factory-admin/finance/CashierPaymentListScreen";
import ArApOverviewScreen from '../screens/factory-admin/inventory/ArApOverviewScreen';
import PriceListScreen from '../screens/factory-admin/inventory/PriceListScreen';
import OATodoStackNavigator from "./OATodoStackNavigator";
import ProfileStackNavigator from "./ProfileStackNavigator";
import { useAuthStore } from "../store/authStore";
import { getUserRole } from "../types/auth";
import { useMyTodoCount } from "../hooks/useMyTodos";
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';
import { getMobileFinancialScope } from '../utils/mobileFinancialAccess';

const Tab = createBottomTabNavigator<any>();
const PaymentStack = createNativeStackNavigator<any>();
type FinancialStackParamList = { ArApOverview: undefined; PriceList: undefined };
const FinancialStack = createNativeStackNavigator<FinancialStackParamList>();

function FinancialWorkspaceNavigator() {
  const user = useAuthStore((state) => state.user);
  const financeScope = getMobileFinancialScope(user, 'finance');
  const priceScope = getMobileFinancialScope(user, 'price');
  if (!financeScope && !priceScope) {
    return <View testID="finance-workspace-unavailable" style={{ flex: 1, justifyContent: 'center', alignItems: 'center' }}>
      <Text>当前账号没有财务或价格读取权限</Text>
    </View>;
  }
  return (
    <FinancialStack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}
      key={JSON.stringify([financeScope?.key, priceScope?.key])}
      initialRouteName={financeScope ? 'ArApOverview' : 'PriceList'}>
      {financeScope && <FinancialStack.Screen name="ArApOverview" component={ArApOverviewScreen} />}
      {priceScope && <FinancialStack.Screen name="PriceList" component={PriceListScreen} />}
    </FinancialStack.Navigator>
  );
}

function CashierPaymentStackNavigator() {
  return (
    <PaymentStack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}>
      <PaymentStack.Screen name="CashierPaymentList" component={CashierPaymentListScreen} />
    </PaymentStack.Navigator>
  );
}

export default function FinanceNavigator() {
  const { user } = useAuthStore();
  const actorRef = useRef({ user, generation: 0 });
  if (actorRef.current.user !== user) {
    actorRef.current = { user, generation: actorRef.current.generation + 1 };
  }
  const financeScope = getMobileFinancialScope(user, 'finance');
  const priceScope = getMobileFinancialScope(user, 'price');
  const role = getUserRole(user);
  const { count: todoCount } = useMyTodoCount(undefined, 30000);
  const isCashier = role === "cashier";
  const smartBiEnabled = useFactoryFeatureStore((state) => state.isScreenEnabled('SmartBI'));
  const operatingReportsEnabled = smartBiEnabled && canAccessOperatingReports(user);

  return (
    <Tab.Navigator key={`${actorRef.current.generation}:${user?.id ?? 'none'}`}
      screenLayout={screenErrorBoundary}
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: "#1B65A8",
        tabBarInactiveTintColor: "#757575",
      }}
    >
      <Tab.Screen
        name="FinanceHomeTab"
        component={HomeScreen}
        initialParams={{ moduleSet: 'finance' }}
        options={{
          title: "首页",
          tabBarButtonTestID: "finance-tab-home",
          tabBarIcon: ({ color, size }) => <Icon source="home" size={size} color={color} />,
        }}
      />
      {(financeScope || priceScope) && (
        <Tab.Screen name="FinanceWorkspaceTab" component={FinancialWorkspaceNavigator}
          options={{ title: '账务', tabBarButtonTestID: 'finance-tab-workspace',
            tabBarIcon: ({ color, size }) => <Icon source="book-open-outline" color={color} size={size} /> }} />
      )}
      <Tab.Screen
        name="FinanceTodoTab"
        component={OATodoStackNavigator}
        options={{
          title: "待办",
          tabBarButtonTestID: "finance-tab-oa-todo",
          tabBarIcon: ({ color, size }) => <Icon source="clipboard-check-outline" size={size} color={color} />,
          tabBarBadge: todoCount > 0 ? todoCount : undefined,
        }}
      />
      {isCashier && (
        <Tab.Screen
          name="CashierPaymentTab"
          component={CashierPaymentStackNavigator}
          options={{
            title: "付款",
            tabBarButtonTestID: "cashier-tab-payment",
            tabBarIcon: ({ color, size }) => <Icon source="bank-transfer-out" size={size} color={color} />,
          }}
        />
      )}
      {operatingReportsEnabled && (
        <Tab.Screen name="FinanceOperatingReportsTab" component={OperatingReportStackNavigator}
          options={{ title: '经营', tabBarButtonTestID: 'finance-tab-operating-reports',
            tabBarIcon: ({ color, size }) => <Icon source="file-chart-outline" color={color} size={size} /> }} />
      )}
      <Tab.Screen
        name="FinanceProfileTab"
        component={ProfileStackNavigator}
        options={{
          title: "我的",
          tabBarButtonTestID: "finance-tab-profile",
          tabBarIcon: ({ color, size }) => <Icon source="account" size={size} color={color} />,
        }}
      />
    </Tab.Navigator>
  );
}
