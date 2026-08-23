/**
 * Warehouse Manager Tab 导航器
 * 用于仓储管理员 (warehouse_manager) 和仓储员工 (warehouse_worker) 角色
 *
 * ── 2026-08-21 收敛 ──────────────────────────────────────────────────────
 * owner 裁定: 仓管在 App 上唯一要做的是「确认入库(含拍照)」和「发货」,
 * 不再让他查库存 —— 那些是办公室在 web 上做的事。
 *
 * 5 个 Tab → 4 个:  首页 / 入库 / 发货 / 我的
 *
 *   首页改为两个大入口: 不再复制任务与统计，只负责把仓管带到入库/发货首页。
 *   隐藏 库存: owner 明确不让他查; 且这个栈底下挂着 18 个屏。
 *   保留 我的: ⛔ 不能砍 —— 它是登出/切账号的**唯一出口**,
 *              砍掉等于把用户锁死在一个账号里 (防呆 Rule 5)。
 *
 * ⛔ 隐藏不是删除: 库存 Stack 与其下屏幕全部保留在代码里, 只是不再从
 *    底部导航进入。此前 owner 原话「我们先把它隐藏起来, 我们看看效果」——
 *    把 SIMPLIFIED_WAREHOUSE_NAV 改成 false 即整体恢复, 一行。
 *
 * ⚠️ 与本次同一个 commit 的必要配套: TransferList/TransferDetail 已从
 *    WHHomeStack 迁到 WHOutboundStack。首页一旦不出现,
 *    WHDeliveryConfirmScreen 的「库存不足 → 去调拨」就会跳到一个不存在的 tab。
 *    那条出路是 2026-08-20 刚接好的 —— 两个改动各自都对, 叠起来是缺陷。
 */

import React from "react";
import { StyleSheet, View } from "react-native";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { Icon, Text } from "react-native-paper";
import { WarehouseManagerTabParamList } from "../types/navigation";
import { useFactoryFeatureStore } from "../store/factoryFeatureStore";

// 导入5个Stack导航器
import WHHomeStackNavigator from "./warehouse/WHHomeStackNavigator";
import WHInboundStackNavigator from "./warehouse/WHInboundStackNavigator";
import WHOutboundStackNavigator from "./warehouse/WHOutboundStackNavigator";
import WHInventoryStackNavigator from "./warehouse/WHInventoryStackNavigator";
import WHProfileStackNavigator from "./warehouse/WHProfileStackNavigator";

const Tab = createBottomTabNavigator<WarehouseManagerTabParamList>();

// Tab栏配色 - 仓储主题色 (绿色)
const TAB_COLORS = {
  active: "#4CAF50",   // 仓储绿色
  inactive: "#757575",
};

/**
 * 收敛开关 —— 改成 false 即恢复原来的 5 Tab (首页/入库/发货/库存/我的)。
 *
 * 具名常量而不是直接删代码, 是因为 owner 要的是「先隐藏看看效果」。
 * ⛔ 不要写成 `{false && <Tab.Screen .../>}` 那种匿名写法: 那样以后没人知道
 *    这是一次有意的产品裁定还是谁调试时注掉忘了恢复。
 */
export const SIMPLIFIED_WAREHOUSE_NAV = true;

function InboundUnavailableScreen() {
  return <WarehouseFeatureUnavailable title="入库功能未开通" />;
}

function OutboundUnavailableScreen() {
  return <WarehouseFeatureUnavailable title="发货功能未开通" />;
}

function WarehouseFeatureUnavailable({ title }: { title: string }) {
  return (
    <View style={styles.unavailable} testID="wh-feature-unavailable">
      <Icon source="lock-outline" size={44} color="#616161" />
      <Text variant="titleLarge" style={styles.unavailableTitle}>{title}</Text>
      <Text variant="bodyLarge" style={styles.unavailableHint}>请联系管理员开通后再操作。</Text>
    </View>
  );
}

export function WarehouseManagerTabNavigator() {
  const { isScreenEnabled } = useFactoryFeatureStore();
  const inboundEnabled = isScreenEnabled('InboundManagement');
  const outboundEnabled = isScreenEnabled('OutboundManagement');

  return (
    <Tab.Navigator
      initialRouteName="WHHomeTab"
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
      {/* 首页只做入库/发货分流，不重复任务列表。 */}
      <Tab.Screen
        name="WHHomeTab"
        component={WHHomeStackNavigator}
        options={{
          title: "首页",
          tabBarButtonTestID: "wh-tab-home",
          tabBarIcon: ({ color, size }) => (
            <Icon source="home" size={size} color={color} />
          ),
        }}
      />

      {/* 入库Tab */}
      <Tab.Screen
        name="WHInboundTab"
        component={inboundEnabled ? WHInboundStackNavigator : InboundUnavailableScreen}
        options={{
          title: "入库",
          tabBarButtonTestID: "wh-tab-inbound",
          tabBarIcon: ({ color, size }) => (
            <Icon source="package-down" size={size} color={color} />
          ),
        }}
      />

      {/* 发货Tab */}
      <Tab.Screen
        name="WHOutboundTab"
        component={outboundEnabled ? WHOutboundStackNavigator : OutboundUnavailableScreen}
        options={{
          title: "发货",
          tabBarButtonTestID: "wh-tab-outbound",
          tabBarIcon: ({ color, size }) => (
            <Icon source="package-up" size={size} color={color} />
          ),
        }}
      />

      {/* 库存Tab —— 收敛时隐藏 (owner: 不让仓管在 App 上查库存) */}
      {!SIMPLIFIED_WAREHOUSE_NAV && (
      <Tab.Screen
        name="WHInventoryTab"
        component={WHInventoryStackNavigator}
        options={{
          title: "库存",
          tabBarButtonTestID: "wh-tab-inventory",
          tabBarIcon: ({ color, size }) => (
            <Icon source="warehouse" size={size} color={color} />
          ),
        }}
      />
      )}

      {/* 我的Tab */}
      <Tab.Screen
        name="WHProfileTab"
        component={WHProfileStackNavigator}
        options={{
          title: "我的",
          tabBarButtonTestID: "wh-tab-profile",
          tabBarIcon: ({ color, size }) => (
            <Icon source="account" size={size} color={color} />
          ),
        }}
      />
    </Tab.Navigator>
  );
}

export default WarehouseManagerTabNavigator;

const styles = StyleSheet.create({
  unavailable: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    gap: 12,
    paddingHorizontal: 32,
    backgroundColor: "#F7F8FA",
  },
  unavailableTitle: {
    color: "#212121",
    fontWeight: "700",
    textAlign: "center",
  },
  unavailableHint: {
    color: "#616161",
    textAlign: "center",
  },
});
