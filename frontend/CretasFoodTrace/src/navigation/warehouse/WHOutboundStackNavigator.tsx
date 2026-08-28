/**
 * Warehouse 发货 Stack 导航器
 * 包含: 待发货确认、历史发货列表与详情、物流跟踪、订单详情、扫码作业
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WHOutboundStackParamList } from "../../types/navigation";

// 导入发货相关页面组件
import WHDeliveryConfirmListScreen from "../../screens/warehouse/outbound/WHDeliveryConfirmListScreen";
import WHDeliveryConfirmScreen from "../../screens/warehouse/outbound/WHDeliveryConfirmScreen";
import WHOutboundListScreen from "../../screens/warehouse/outbound/WHOutboundListScreen";
import WHOutboundDetailScreen from "../../screens/warehouse/outbound/WHOutboundDetailScreen";
import WHTrackingDetailScreen from "../../screens/warehouse/outbound/WHTrackingDetailScreen";
import WHOrderDetailScreen from "../../screens/warehouse/outbound/WHOrderDetailScreen";
import WHOutboundIssueScreen from "../../screens/warehouse/outbound/WHOutboundIssueScreen";
import WHScanOperationScreen from "../../screens/warehouse/shared/WHScanOperationScreen";
// 调拨 —— 2026-08-21 从 WHHomeStack 迁来 (首页 tab 收敛), 见下方 Screen 处说明。
import TransferListScreen from "../../screens/factory-admin/inventory/TransferListScreen";
import TransferDetailScreen from "../../screens/factory-admin/inventory/TransferDetailScreen";
import { screenErrorBoundary } from '../../components/common/ScreenErrorBoundary';

const Stack = createNativeStackNavigator<WHOutboundStackParamList>();

export function WHOutboundStackNavigator() {
  return (
    <Stack.Navigator screenLayout={screenErrorBoundary}
      initialRouteName="WHDeliveryConfirmList"
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 真发货 (DLV-*) — 发货 tab 主入口: 销售发货单待仓库确认 → 确认扣减成品库存 */}
      <Stack.Screen name="WHDeliveryConfirmList" component={WHDeliveryConfirmListScreen} />

      {/* 发货确认 (填实际数量, 扣库存) */}
      <Stack.Screen
        name="WHDeliveryConfirm"
        component={WHDeliveryConfirmScreen}
        options={{ title: "发货确认" }}
      />

      {/* 手工发货登记 (旧 SH-* 流程, 不扣库存) — 降级为二级入口 */}
      <Stack.Screen name="WHOutboundList" component={WHOutboundListScreen} />

      {/* 发货详情 */}
      <Stack.Screen
        name="WHOutboundDetail"
        component={WHOutboundDetailScreen}
        options={{ title: "发货详情" }}
      />

      {/* 物流跟踪 */}
      <Stack.Screen
        name="WHTrackingDetail"
        component={WHTrackingDetailScreen}
        options={{ title: "物流跟踪" }}
      />

      {/* 订单详情 */}
      <Stack.Screen
        name="WHOrderDetail"
        component={WHOrderDetailScreen}
        options={{ title: "订单详情" }}
      />

      {/* 扫码作业 */}
      <Stack.Screen
        name="WHScanOperation"
        component={WHScanOperationScreen}
        options={{ title: "扫码作业" }}
      />

      {/*
        调拨(领料审批) —— 2026-08-21 从 WHHomeStack 迁来。
        ⚠️ 迁移的理由不是整理: 首页 tab 被收敛掉后, WHDeliveryConfirmScreen 的
        「库存不足 → 去调拨」会跳到一个不再存在的 tab 上。那条出路是 2026-08-20 刚接好的,
        两个改动各自都对, 叠起来就是缺陷 —— 所以隐藏 tab 与迁移目的地必须是同一次改动。
      */}
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

      {/* 扫码出库确认 (领料/出库, 扫物料批次标签后 2 字段闭环) */}
      <Stack.Screen
        name="WHOutboundIssue"
        component={WHOutboundIssueScreen}
        options={{ title: "扫码出库确认" }}
      />
    </Stack.Navigator>
  );
}

export default WHOutboundStackNavigator;
