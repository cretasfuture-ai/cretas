/**
 * Workshop Supervisor 个人中心 Stack 导航器
 * 包含: 个人中心、个人信息、修改密码、通知设置、系统设置、关于
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WSProfileStackParamList } from "../../types/navigation";

// 导入页面组件
import WSProfileScreen from "../../screens/workshop-supervisor/profile/WSProfileScreen";
import PersonalInfoScreen from "../../screens/factory-admin/profile/PersonalInfoScreen";
import ChangePasswordScreen from "../../screens/factory-admin/profile/ChangePasswordScreen";
import NotificationSettingsScreen from "../../screens/factory-admin/profile/NotificationSettingsScreen";
import SystemSettingsScreen from "../../screens/factory-admin/profile/SystemSettingsScreen";
import AboutScreen from "../../screens/factory-admin/profile/AboutScreen";

// 复用现有Profile页面
import FeedbackScreen from "../../screens/profile/FeedbackScreen";
import { screenErrorBoundary } from '../../components/common/ScreenErrorBoundary';

const Stack = createNativeStackNavigator<WSProfileStackParamList>();

export function WSProfileStackNavigator() {
  return (
    <Stack.Navigator screenLayout={screenErrorBoundary}
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 个人中心主页 */}
      <Stack.Screen name="WSProfile" component={WSProfileScreen} />

      {/* 个人信息 */}
      <Stack.Screen
        name="PersonalInfo"
        component={PersonalInfoScreen}
        options={{ title: "个人信息" }}
      />

      {/* 修改密码 */}
      <Stack.Screen
        name="ChangePassword"
        component={ChangePasswordScreen}
        options={{ title: "修改密码" }}
      />

      {/* 通知设置 */}
      <Stack.Screen
        name="NotificationSettings"
        component={NotificationSettingsScreen}
        options={{ title: "通知设置" }}
      />

      {/* 系统设置 */}
      <Stack.Screen
        name="Settings"
        component={SystemSettingsScreen}
        options={{ title: "系统设置" }}
      />

      {/* 关于 */}
      <Stack.Screen
        name="About"
        component={AboutScreen}
        options={{ title: "关于" }}
      />

      {/* 意见反馈 */}
      <Stack.Screen
        name="Feedback"
        component={FeedbackScreen}
        options={{ title: "意见反馈" }}
      />
    </Stack.Navigator>
  );
}

export default WSProfileStackNavigator;
