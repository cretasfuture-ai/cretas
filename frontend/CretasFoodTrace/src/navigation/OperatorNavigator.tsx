import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import ProfileStackNavigator from './ProfileStackNavigator';

import OperatorAssignedProcessScreen from '../screens/processing/OperatorAssignedProcessScreen';
import YieldStepReportScreen from '../screens/processing/YieldStepReportScreen';
import ShortageAlertDetailScreen from '../screens/processing/ShortageAlertDetailScreen';
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';
// 一线 AI 助手 (Line B 2026-10-05): 复用 FA 端 AIChatScreen, 熔断开关关掉时连路由都不注册
import AIChatScreen from '../screens/factory-admin/ai-analysis/AIChatScreen';
import { useFrontlineAIEntryEnabled } from '../hooks/useFrontlineAIEntry';

const Tab = createBottomTabNavigator<any>();
const ReportStack = createNativeStackNavigator<any>();
const RootStack = createNativeStackNavigator<any>();

/**
 * Operator 报工 Stack 导航器
 * 小组长只处理系统分配好的当前工序，不暴露任务列表/三步报工/选批次入口。
 * N1b: ShortageAlertDetail 屏注册在此 Stack，从任务列表缺料 badge 跳入。
 */
function OperatorReportStackNavigator() {
  return (
    <ReportStack.Navigator screenLayout={screenErrorBoundary} initialRouteName="OperatorAssignedProcess" screenOptions={{ headerShown: false }}>
      <ReportStack.Screen name="OperatorAssignedProcess" component={OperatorAssignedProcessScreen} />
      <ReportStack.Screen name="YieldStepReport" component={YieldStepReportScreen} />
      {/* N1b: 缺料预警明细屏 (从任务列表的 ⚠缺料 badge 跳入) */}
      <ReportStack.Screen name="ShortageAlertDetail" component={ShortageAlertDetailScreen} />
    </ReportStack.Navigator>
  );
}

/**
 * Operator专用底部Tab导航器
 * 考勤、报工、工作、个人中心 四个tab
 */
function OperatorTabNavigator() {
  // C1: Use safe-area insets so the tab bar clears Android gesture navigation bar.
  // insets.bottom is 0 on devices with hardware buttons; positive on gesture-nav devices.
  const insets = useSafeAreaInsets();

  return (
    <Tab.Navigator screenLayout={screenErrorBoundary}
      initialRouteName="OperatorReportTab"
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#2196F3',
        tabBarInactiveTintColor: '#757575',
        tabBarStyle: {
          backgroundColor: "#ffffff",
          borderTopWidth: 1,
          borderTopColor: "#e0e0e0",
          paddingBottom: insets.bottom + 5,
          paddingTop: 5,
          height: 60 + insets.bottom,
        },
        tabBarLabelStyle: {
          fontSize: 12,
          fontWeight: "500",
        },
      }}
    >
      {/* 工序Tab - 小组长/操作员登录后直接进入被分配工序 */}
      <Tab.Screen
        name="OperatorReportTab"
        component={OperatorReportStackNavigator}
        options={{
          tabBarButtonTestID: 'operator-tab-report',
          title: '工序',
          tabBarIcon: ({ color, size }) => (
            <Icon source="format-list-checks" size={size} color={color} />
          ),
        }}
      />

      {/* 个人中心Tab - 账号信息、退出登录 */}
      <Tab.Screen
        name="OperatorProfileTab"
        component={ProfileStackNavigator}
        options={{
          tabBarButtonTestID: 'operator-tab-profile',
          title: '我的',
          tabBarIcon: ({ color, size }) => (
            <Icon source="account-outline" size={size} color={color} />
          ),
        }}
      />
    </Tab.Navigator>
  );
}

/**
 * Operator 根导航器: Tab 外面包一层 Stack, 让 AI 对话盖住 tab 栏 (与仓储主管/车间主任同一结构)。
 * AIChat 只在一线 AI 入口开关打开时注册 —— 熔断后首页按钮消失, 路由也不存在。
 */
export function OperatorNavigator() {
  const aiEntryEnabled = useFrontlineAIEntryEnabled();
  return (
    <RootStack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}>
      <RootStack.Screen name="OperatorMain" component={OperatorTabNavigator} />
      {aiEntryEnabled && (
        <RootStack.Screen name="AIChat" component={AIChatScreen} options={{ headerShown: true, title: 'AI 助手' }} />
      )}
    </RootStack.Navigator>
  );
}

export default OperatorNavigator;
