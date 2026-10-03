import OperatingReportStackNavigator from './OperatingReportStackNavigator';
import { canAccessOperatingReports } from '../utils/operatingReportAccess';
import { useFactoryFeatureStore } from '../store/factoryFeatureStore';
import React from "react";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { Icon } from "react-native-paper";

import HomeScreen from "../screens/main/HomeScreen";
import CashierPaymentListScreen from "../screens/factory-admin/finance/CashierPaymentListScreen";
import OATodoStackNavigator from "./OATodoStackNavigator";
import ProfileStackNavigator from "./ProfileStackNavigator";
import { useAuthStore } from "../store/authStore";
import { getUserRole } from "../types/auth";
import { useMyTodoCount } from "../hooks/useMyTodos";
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';

const Tab = createBottomTabNavigator<any>();
const PaymentStack = createNativeStackNavigator<any>();

function CashierPaymentStackNavigator() {
  return (
    <PaymentStack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}>
      <PaymentStack.Screen name="CashierPaymentList" component={CashierPaymentListScreen} />
    </PaymentStack.Navigator>
  );
}

export default function FinanceNavigator() {
  const { user } = useAuthStore();
  const role = getUserRole(user);
  const { count: todoCount } = useMyTodoCount(undefined, 30000);
  const isCashier = role === "cashier";
  const smartBiEnabled = useFactoryFeatureStore((state) => state.isScreenEnabled('SmartBI'));
  const operatingReportsEnabled = smartBiEnabled && canAccessOperatingReports(user);

  return (
    <Tab.Navigator screenLayout={screenErrorBoundary}
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
