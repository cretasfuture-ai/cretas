/**
 * Warehouse 首页 Stack 导航器
 * 包含: 首页Dashboard、发货详情、入库详情、预警处理、温控监测
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WHHomeStackParamList } from "../../types/navigation";

// 导入首页相关页面组件
import WHHomeScreen from "../../screens/warehouse/home/WHHomeScreen";

// 详情页 (先用占位符，后续实现)
import WHOutboundDetailScreen from "../../screens/warehouse/outbound/WHOutboundDetailScreen";
import WHInboundDetailScreen from "../../screens/warehouse/inbound/WHInboundDetailScreen";
import WHAlertHandleScreen from "../../screens/warehouse/shared/WHAlertHandleScreen";
import WHTempMonitorScreen from "../../screens/warehouse/inventory/WHTempMonitorScreen";
import WHLocationManageScreen from "../../screens/warehouse/inventory/WHLocationManageScreen";
import TransferListScreen from "../../screens/factory-admin/inventory/TransferListScreen";
import TransferDetailScreen from "../../screens/factory-admin/inventory/TransferDetailScreen";

const Stack = createNativeStackNavigator<WHHomeStackParamList>();

export function WHHomeStackNavigator() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 首页Dashboard */}
      <Stack.Screen name="WHHome" component={WHHomeScreen} />

      {/* 发货详情 */}
      <Stack.Screen
        name="OutboundDetail"
        component={WHOutboundDetailScreen}
        options={{ title: "发货详情" }}
      />

      {/* 入库详情 */}
      <Stack.Screen
        name="InboundDetail"
        component={WHInboundDetailScreen}
        options={{ title: "入库详情" }}
      />

      {/* 预警处理 */}
      <Stack.Screen
        name="AlertHandle"
        component={WHAlertHandleScreen}
        options={{ title: "预警处理" }}
      />

      {/* 温控监测 */}
      <Stack.Screen
        name="TempMonitor"
        component={WHTempMonitorScreen}
        options={{ title: "温控监测" }}
      />

      {/* 仓库地图 / 库位 —— 2026-08-25 补。
          原本只挂在 WHInventoryStack 下, 而那个 Tab 被 SIMPLIFIED_WAREHOUSE_NAV
          按 owner 裁定隐藏, 于是仓管在手机上看不到 Web 端配好的仓储空间。
          地图是现场找货的参照, 不是库存查询, 所以单独挂在这里;
          ⛔ 不整体翻那个开关(会把 18 个库存查询屏一起放出来)。*/}
      <Stack.Screen
        name="WHLocationManage"
        component={WHLocationManageScreen}
        options={{ title: "库位与仓库地图" }}
      />

      {/* 同一个温控屏的第二个注册名。WHLocationManageScreen 里写死 navigate("WHTempMonitor"),
          而本栈原有的注册名是 `TempMonitor` —— 不补这一条, 那个按钮在首页栈里就是死路。*/}
      <Stack.Screen
        name="WHTempMonitor"
        component={WHTempMonitorScreen}
        options={{ title: "温控监测" }}
      />

      {/* 调拨（领料审批） */}
      <Stack.Screen
        name="TransferList"
        component={TransferListScreen as any}
        options={{ title: "领料调拨" }}
      />
      <Stack.Screen
        name="TransferDetail"
        component={TransferDetailScreen as any}
        options={{ title: "调拨详情" }}
      />
    </Stack.Navigator>
  );
}

export default WHHomeStackNavigator;
