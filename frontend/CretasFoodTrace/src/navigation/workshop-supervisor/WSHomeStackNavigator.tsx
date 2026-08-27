/**
 * Workshop Supervisor 首页 Stack 导航器
 * 包含: 首页Dashboard、批次详情、员工详情、设备详情、通知、任务引导
 */

import React from "react";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { WSHomeStackParamList } from "../../types/navigation";

// 导入首页相关页面组件
import WSHomeScreen from "../../screens/workshop-supervisor/home/WSHomeScreen";

// 复用现有详情页
import BatchDetailScreen from "../../screens/processing/BatchDetailScreen";
import EquipmentDetailScreen from "../../screens/processing/EquipmentDetailScreen";

// 任务引导流程 (可复用或新建)
import TaskGuideScreen from "../../screens/workshop-supervisor/home/TaskGuideScreen";
import TaskGuideStep2Screen from "../../screens/workshop-supervisor/home/TaskGuideStep2Screen";
import TaskGuideStep3Screen from "../../screens/workshop-supervisor/home/TaskGuideStep3Screen";

// 通知页面 (新建或复用)
import NotificationsScreen from "../../screens/workshop-supervisor/home/NotificationsScreen";

// 员工详情 (复用或新建)
import WorkerDetailScreen from "../../screens/workshop-supervisor/workers/WorkerDetailScreen";

// 快捷操作入口页面
import TeamBatchReportScreen from "../../screens/processing/TeamBatchReportScreen";
import DraftReportsScreen from "../../screens/processing/DraftReportsScreen";

// 生产报工
import DynamicReportScreen from "../../screens/processing/DynamicReportScreen";
import NfcCheckinScreen from "../../screens/processing/NfcCheckinScreen";
import MyWorkReportsScreen from "../../screens/processing/MyWorkReportsScreen";
import ProcessTaskListScreen from "../../screens/processing/ProcessTaskListScreen";
import ProcessTaskDetailScreen from "../../screens/processing/ProcessTaskDetailScreen";
import ProcessRunOverviewScreen from "../../screens/processing/ProcessRunOverviewScreen";
import YieldBatchSelectScreen from "../../screens/processing/YieldBatchSelectScreen";
import YieldStepReportScreen from "../../screens/processing/YieldStepReportScreen";
import ProcessOperationScreen from "../../screens/processing/ProcessOperationScreen";

/*
 * 生产计划管理。
 * 2026-08-25 补登记：WSHomeScreen 顶部的业务流程图 (WorkflowVisualizer) 点节点时
 * navigate('ProductionPlanManagement')，而这个路由只注册在 ProcessingStackNavigator /
 * FAHomeStackNavigator 上 —— 车间主管在 AppNavigator 里是 early return，到不了那两棵树，
 * 于是点节点是个静默 no-op（不报错、不跳转）。
 * 它本来就已经写进 WSHomeStackParamList 了，说明当初就打算给这个角色用，只是漏了注册。
 */
import ProductionPlanManagementScreen from "../../screens/processing/ProductionPlanManagementScreen";
/*
 * 2026-08-26 补登记：审批屏本身是完整的（通过 / 驳回 / 批量 / 五个驳回原因），
 * 但此前**只注册在 FAManagementStackNavigator（工厂管理员树）**上。
 * 车间主管在 AppNavigator 里是 early return，到不了那棵树 ——
 * 于是 ProcessTaskListScreen 顶栏那个审批图标点了**没有任何反应**。
 * 审批本来就是主管的日常动作（后端 approve 端点要 production:read_write，
 * 而 permissionHelper 里 workshop_supervisor 正是 read_write）。
 */
import ProcessTaskApprovalScreen from "../../screens/processing/ProcessTaskApprovalScreen";

const Stack = createNativeStackNavigator<WSHomeStackParamList>();

export function WSHomeStackNavigator() {
  return (
    <Stack.Navigator
      /*
       * 2026-08-26：落地屏从 ProcessTaskList 换成 WSHome。
       *
       * 原来车间主管打开 App 直接落在「工序任务」—— 一个**答不了他问题**的屏：
       * 卡片没有日期、筛选只有三个状态、第一个弹窗写的还是给工人看的
       * 「提交后等待主管审批」（而看的人就是主管）。
       *
       * WSHome 一直存在（1107 行、调真接口、15 个导航目标全部可达），
       * 只是**从来不是初始路由** ⇒ 它连带的通知收件箱、快捷操作宫格、
       * AI 对话、新手引导**全部到不了**；WSProfileScreen 那个写着「去首页」的按钮
       * 跳 WSHomeTab 之后，也是被这一行送回工序任务。
       *
       * ⚠️ 这一行的来历查不到 —— 它来自 4f8f09c051「Initial company baseline」
       * (2026-08-23 仓库压平重建)，原始意图连同历史一起没了。
       * 仓管角色已经做过同样的收敛并用测试钉住 (warehouseNavSimplification.test.ts)。
       *
       * ProcessTaskList 仍在本栈注册，WSHome 上有入口，主管照样一步到达。
       */
      initialRouteName="WSHome"
      screenOptions={{
        headerShown: false,
      }}
    >
      {/* 首页Dashboard */}
      <Stack.Screen name="WSHome" component={WSHomeScreen} />

      {/* 批次详情 (复用现有) */}
      <Stack.Screen
        name="BatchDetail"
        component={BatchDetailScreen}
        options={{ title: "批次详情" }}
      />

      {/* 员工详情 */}
      <Stack.Screen
        name="WorkerDetail"
        component={WorkerDetailScreen}
        options={{ title: "员工详情" }}
      />

      {/* 设备详情 (复用现有) */}
      <Stack.Screen
        name="EquipmentDetail"
        component={EquipmentDetailScreen}
        options={{ title: "设备详情" }}
      />

      {/* 通知 */}
      <Stack.Screen
        name="Notifications"
        component={NotificationsScreen}
        options={{ title: "通知" }}
      />

      {/* 任务引导流程 - 步骤1: 前往工位 */}
      <Stack.Screen
        name="TaskGuide"
        component={TaskGuideScreen}
        options={{ title: "任务引导" }}
      />

      {/* 任务引导流程 - 步骤2: 确认设备 */}
      <Stack.Screen
        name="TaskGuideStep2"
        component={TaskGuideStep2Screen}
        options={{ title: "确认设备" }}
      />

      {/* 任务引导流程 - 步骤3: 召集人员 */}
      <Stack.Screen
        name="TaskGuideStep3"
        component={TaskGuideStep3Screen}
        options={{ title: "召集人员" }}
      />

      {/* 快捷操作入口 */}
      <Stack.Screen name="TeamBatchReport" component={TeamBatchReportScreen} options={{ title: "班组报工" }} />
      <Stack.Screen name="DraftReports" component={DraftReportsScreen} options={{ title: "草稿管理" }} />

      {/* 生产报工 */}
      <Stack.Screen name="DynamicReport" component={DynamicReportScreen} options={{ title: "生产报工" }} />
      <Stack.Screen name="NfcCheckin" component={NfcCheckinScreen} options={{ title: "扫码签到" }} />
      <Stack.Screen name="MyWorkReports" component={MyWorkReportsScreen} options={{ title: "我的报工" }} />
      <Stack.Screen name="ProcessTaskList" component={ProcessTaskListScreen} options={{ title: "工序任务" }} />
      <Stack.Screen name="ProcessTaskApproval" component={ProcessTaskApprovalScreen} options={{ title: "报工审批" }} />
      <Stack.Screen name="ProcessTaskDetail" component={ProcessTaskDetailScreen} options={{ title: "任务详情" }} />
      <Stack.Screen name="ProcessRunOverview" component={ProcessRunOverviewScreen} options={{ title: "生产单总览" }} />
      <Stack.Screen name="YieldBatchSelect" component={YieldBatchSelectScreen} options={{ title: "逐道报工" }} />
      <Stack.Screen name="YieldStepReport" component={YieldStepReportScreen} options={{ title: "逐道报工" }} />
      <Stack.Screen name="ProcessOperation" component={ProcessOperationScreen} options={{ title: "工序操作" }} />

      {/* 业务流程图节点的下钻目标 */}
      <Stack.Screen name="ProductionPlanManagement" component={ProductionPlanManagementScreen} options={{ title: "生产计划" }} />
    </Stack.Navigator>
  );
}

export default WSHomeStackNavigator;
