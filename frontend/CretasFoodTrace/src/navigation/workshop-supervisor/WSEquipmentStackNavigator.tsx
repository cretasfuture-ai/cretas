/**
 * Workshop Supervisor 设备管理 Stack 导航器
 * 包含: 设备列表、设备详情
 *
 * 2026-08-25: 删掉了「设备告警详情」和「设备维护记录」两个屏。
 * 它们的数据是写死在组件里的（固定的「切片机A / 温度异常 82°C」、四条固定维护记录），
 * 全仓没有任何 navigate 入口，两个操作按钮也只 setState + Alert，不写任何后端。
 * 真实能力在别处已经有：维护历史走 EquipmentDetailScreen 的
 * equipmentApiClient.getEquipmentMaintenanceHistory，告警走 /equipment-alerts 接口。
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WSEquipmentStackParamList } from "../../types/navigation";

// 导入设备相关页面组件
import WSEquipmentScreen from "../../screens/workshop-supervisor/equipment/WSEquipmentScreen";

// 复用现有详情页
import EquipmentDetailScreen from "../../screens/processing/EquipmentDetailScreen";
import EquipmentAlertsScreen from '../../screens/processing/EquipmentAlertsScreen';
import { screenErrorBoundary } from '../../components/common/ScreenErrorBoundary';

const Stack = createNativeStackNavigator<WSEquipmentStackParamList>();

export function WSEquipmentStackNavigator() {
  return (
    <Stack.Navigator screenLayout={screenErrorBoundary}
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 设备列表主页 */}
      <Stack.Screen name="WSEquipment" component={WSEquipmentScreen} />

      {/* 设备详情 (复用现有) */}
      <Stack.Screen
        name="EquipmentDetail"
        component={EquipmentDetailScreen}
        options={{ title: "设备详情" }}
      />
      <Stack.Screen name="EquipmentAlerts" component={EquipmentAlertsScreen} />
    </Stack.Navigator>
  );
}

export default WSEquipmentStackNavigator;
