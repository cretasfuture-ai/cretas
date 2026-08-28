/**
 * Workshop Supervisor Tab 导航器
 * 5个主Tab: 首页、批次、人员、设备、我的
 * 用于车间主任 (department_admin) 角色
 */

import React from "react";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { Icon } from "react-native-paper";
import { WorkshopSupervisorTabParamList } from "../types/navigation";
import { useFactoryFeatureStore } from "../store/factoryFeatureStore";

// 导入5个Stack导航器
import WSHomeStackNavigator from "./workshop-supervisor/WSHomeStackNavigator";
import WSBatchesStackNavigator from "./workshop-supervisor/WSBatchesStackNavigator";
import WSWorkersStackNavigator from "./workshop-supervisor/WSWorkersStackNavigator";
import WSEquipmentStackNavigator from "./workshop-supervisor/WSEquipmentStackNavigator";
import WSProfileStackNavigator from "./workshop-supervisor/WSProfileStackNavigator";
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';

const Tab = createBottomTabNavigator<WorkshopSupervisorTabParamList>();

// Tab栏配色 - 车间主任专属主题色
const TAB_COLORS = {
  active: "#667eea",  // 紫蓝渐变主色
  inactive: "#757575",
};

export function WorkshopSupervisorTabNavigator() {
  const { isScreenEnabled } = useFactoryFeatureStore();

  return (
    <Tab.Navigator screenLayout={screenErrorBoundary}
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: TAB_COLORS.active,
        tabBarInactiveTintColor: TAB_COLORS.inactive,
        tabBarStyle: {
          backgroundColor: "#ffffff",
          borderTopWidth: 1,
          borderTopColor: "#e0e0e0",
          paddingBottom: 5,
          paddingTop: 5,
          height: 60,
        },
        tabBarLabelStyle: {
          fontSize: 12,
          fontWeight: "500",
        },
      }}
    >
      {/* 首页Tab - 永远显示 */}
      <Tab.Screen
        name="WSHomeTab"
        component={WSHomeStackNavigator}
        options={{
          title: "首页",
          tabBarButtonTestID: "tab-home",
          tabBarIcon: ({ color, size }) => (
            <Icon source="home" size={size} color={color} />
          ),
        }}
      />

      {/* 批次Tab */}
      {isScreenEnabled('BatchManagement') && (
      <Tab.Screen
        name="WSBatchesTab"
        component={WSBatchesStackNavigator}
        options={{
          title: "批次",
          tabBarButtonTestID: "tab-batches",
          tabBarIcon: ({ color, size }) => (
            <Icon source="view-grid" size={size} color={color} />
          ),
        }}
      />
      )}

      {/* 人员Tab */}
      {isScreenEnabled('WorkerManagement') && (
      <Tab.Screen
        name="WSWorkersTab"
        component={WSWorkersStackNavigator}
        options={{
          title: "人员",
          tabBarButtonTestID: "tab-workers",
          tabBarIcon: ({ color, size }) => (
            <Icon source="account-group" size={size} color={color} />
          ),
        }}
      />
      )}

      {/*
        设备Tab。
        2026-08-25 重新挂回来：此前它被摘掉，注释说「移到快捷操作入口」，
        但那个替代入口指向的 EquipmentMonitoring 只注册在 ProcessingStackNavigator 上，
        而车间主管走的是 WorkshopSupervisorNavigator，根本进不去 —— 那次迁移没做完，
        留下了两处点了没反应的入口（首页设备卡片的「查看全部」+ 快捷操作里的「设备监控」）。
        WSEquipmentScreen 现已改读真实接口，重新挂上不会把假页面暴露给用户。
      */}
      {isScreenEnabled('EquipmentManagement') && (
      <Tab.Screen
        name="WSEquipmentTab"
        component={WSEquipmentStackNavigator}
        options={{
          title: "设备",
          tabBarButtonTestID: "tab-equipment",
          tabBarIcon: ({ color, size }) => (
            <Icon source="cog" size={size} color={color} />
          ),
        }}
      />
      )}

      {/* 我的Tab - 永远显示 */}
      <Tab.Screen
        name="WSProfileTab"
        component={WSProfileStackNavigator}
        options={{
          title: "我的",
          tabBarButtonTestID: "tab-profile",
          tabBarIcon: ({ color, size }) => (
            <Icon source="account" size={size} color={color} />
          ),
        }}
      />
    </Tab.Navigator>
  );
}

export default WorkshopSupervisorTabNavigator;
