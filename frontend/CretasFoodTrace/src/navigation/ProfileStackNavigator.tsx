import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { ProfileStackParamList } from '../types/navigation';
import ProfileScreen from '../screens/profile/ProfileScreen';

// 我的工资 (2026-08-25) — 只读本人工资, 所有角色都看得到自己那一份
import MyWageScreen from '../screens/profile/MyWageScreen';

// Phase 3 P2 - 意见反馈
import FeedbackScreen from '../screens/profile/FeedbackScreen';

// Phase 3 P2 - 数据导出
import DataExportScreen from '../screens/reports/DataExportScreen';

// 开发者工具 - 服务器连接测试
import ServerConnectivityTestScreen from '../screens/test/ServerConnectivityTestScreen';

// 开发者工具 - 意图执行测试
import IntentExecutionTestScreen from '../screens/test/IntentExecutionTestScreen';

// S4-4 通知中心 - 所有角色可访问
import NotificationCenterScreen from '../screens/common/NotificationCenterScreen';
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';

const Stack = createNativeStackNavigator<ProfileStackParamList>();

/**
 * 个人中心模块导航器
 * Phase 3 P2新增
 */
export function ProfileStackNavigator() {
  return (
    <Stack.Navigator screenLayout={screenErrorBoundary}
      screenOptions={{
        headerShown: false,
      }}
    >
      <Stack.Screen
        name="ProfileHome"
        component={ProfileScreen}
        options={{ title: '个人中心' }}
      />

      {/*
        我的工资 — 只读本人。
        ⚠️ 这个 Navigator 是 6 个角色共用的 (operator / MainNavigator / finance /
        procurement / sales / viewer), 所以注册在这里 = 这 6 类账号都能进。
        这是**想要的**: 后端端点 self-scoped, 每个人只看得到自己那一份,
        而每个人都该看得到自己的工资。⛔ 不要在这里按角色藏路由 ——
        藏了只会让某个岗位「看不到自己工资」, 而看不到的人不会来报 bug。
      */}
      <Stack.Screen
        name="MyWage"
        component={MyWageScreen}
        options={{ title: '我的工资' }}
      />

      {/* Phase 3 P2 - 意见反馈 */}
      <Stack.Screen
        name="Feedback"
        component={FeedbackScreen}
        options={{ title: '意见反馈' }}
      />

      {/* Phase 3 P2 - 数据导出 */}
      <Stack.Screen
        name="DataExport"
        component={DataExportScreen}
        options={{ title: '数据导出' }}
      />

      {/* 开发者工具 - 服务器连接测试 */}
      <Stack.Screen
        name="ServerConnectivityTest"
        component={ServerConnectivityTestScreen}
        options={{ title: '服务器连接测试' }}
      />

      {/* 开发者工具 - 意图执行测试 */}
      <Stack.Screen
        name="IntentExecutionTest"
        component={IntentExecutionTestScreen}
        options={{ title: '意图执行测试' }}
      />

      {/* S4-4 通知中心 - 所有角色可访问 */}
      <Stack.Screen
        name="NotificationCenter"
        component={NotificationCenterScreen}
        options={{ title: '通知中心' }}
      />
    </Stack.Navigator>
  );
}

export default ProfileStackNavigator;
