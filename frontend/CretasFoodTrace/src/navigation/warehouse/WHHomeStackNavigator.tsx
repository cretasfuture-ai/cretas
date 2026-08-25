/**
 * Warehouse 首页 Stack 导航器
 * 包含: 首页Dashboard、仓库地图/库位、温控监测、领料调拨
 *
 * ⚠️ 「发货详情 / 入库详情 / 预警处理」曾经也注册在这里, 2026-08-26 退役 ——
 *    见下方那段退役留痕。它们仍然存在, 只是各自只剩一个 WH 前缀的注册名。
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WHHomeStackParamList } from "../../types/navigation";

// 导入首页相关页面组件
import WHHomeScreen from "../../screens/warehouse/home/WHHomeScreen";

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

      {/* ⛔ 2026-08-26 退役: 本栈原有四条**无 WH 前缀**的重复注册
          (`OutboundDetail` / `InboundDetail` / `AlertHandle` / `TempMonitor`)。
          四条各自都有一个**同组件、有人跳**的 WH 前缀孪生注册, 没有任何地方跳这四个名字:

            OutboundDetail -> WHOutboundDetail (WHOutboundStackNavigator)
                              WHOutboundListScreen 跳它 (三处)
            InboundDetail  -> WHInboundDetail  (WHInboundStackNavigator)
            AlertHandle    -> WHAlertHandle    (WHProfileStackNavigator)
                              WHAlertListScreen 跳它 (两处)
            TempMonitor    -> WHTempMonitor    (本文件下方)
                              WHLocationManageScreen 跳它

          这正是「同一个东西有两份, 它一定会漂」—— 下方 `WHTempMonitor` 那条注释
          就是漂出来的产物: 有人发现名字对不上, 于是**又加了一个注册名**而不是收敛成一个。
          ⚠️ 退役的是**注册名**, 不是屏 —— 四个组件一个都没删, 都还挂在各自的 WH 名字下。

          ⚠️ 留痕: `InboundDetail` 的孪生 `WHInboundDetail` **自己也没人跳**
          (WHInboundListScreen 的卡片点击跳的是 WHReceiptCreate)。所以入库详情屏
          在两个名字下都走不到 —— 这一条不是本次退役造成的, 退役前后同样走不到。
          可达性闸看不见它, 是因为屏自己那行 `RouteProp<…, "WHInboundDetail">`
          里的字符串被算成了「有人引用」。已在 PR 描述里单列。*/}

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
