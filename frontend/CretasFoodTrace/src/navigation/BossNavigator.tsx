import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import BossOverviewScreen from '../screens/factory-admin/home/BossOverviewScreen';
import ProductionPlanManagementScreen from '../screens/processing/ProductionPlanManagementScreen';
import SalesOrderListScreen from '../screens/factory-admin/inventory/SalesOrderListScreen';
import SalesOrderDetailScreen from '../screens/factory-admin/inventory/SalesOrderDetailScreen';
import PurchaseOrderListScreen from '../screens/factory-admin/inventory/PurchaseOrderListScreen';
import PurchaseOrderDetailScreen from '../screens/factory-admin/inventory/PurchaseOrderDetailScreen';
import FinishedGoodsListScreen from '../screens/factory-admin/inventory/FinishedGoodsListScreen';
// 通用现场能力屏 —— 复用 ProcessingStackNavigator 里注册的同一批组件，⛔ 不复制屏代码。
import { TraceabilityScreen, TraceabilityDetailScreen } from '../screens/traceability';
import VisionVerifyScreen from '../screens/shared/VisionVerifyScreen';
import SourceIndividualFormScreen from '../screens/shared/SourceIndividualFormScreen';
import SourceIndividualDetailScreen from '../screens/shared/SourceIndividualDetailScreen';
import SourceIndividualListScreen from '../screens/shared/SourceIndividualListScreen';
import SmartBIStackNavigator from './SmartBIStackNavigator';
import FAReportsStackNavigator from './factory-admin/FAReportsStackNavigator';
import MobileAccountScreen from '../screens/common/MobileAccountScreen';
import { useFactoryFeatureStore } from '../store/factoryFeatureStore';
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';

const Tab = createBottomTabNavigator();
const OverviewStack = createNativeStackNavigator();

function BossOverviewStack() {
  return (
    <OverviewStack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}>
      <OverviewStack.Screen name="BossOverview" component={BossOverviewScreen} />
      <OverviewStack.Screen name="BossProductionPlans" component={ProductionPlanManagementScreen} />
      <OverviewStack.Screen name="BossSalesOrders" component={SalesOrderListScreen} />
      <OverviewStack.Screen name="SalesOrderDetail" component={SalesOrderDetailScreen} />
      <OverviewStack.Screen name="BossPurchaseOrders" component={PurchaseOrderListScreen} />
      <OverviewStack.Screen name="PurchaseOrderDetail" component={PurchaseOrderDetailScreen} />
      <OverviewStack.Screen name="BossFinishedGoods" component={FinishedGoodsListScreen} />
      {/*
        🔴 2026-09-15 接入三个通用现场能力屏 —— 此前工厂管理员**一个都走不到**。

        `Traceability` / `TraceabilityDetail` / `VisionVerify` / `SourceIndividualForm`
        此前唯一的注册点是 `ProcessingStackNavigator.tsx`，而那棵树唯一的挂载点是
        `MainNavigator`（platform_admin / equipment_admin / quality_manager /
        permission_admin）。非餐饮的 `factory_super_admin` 走的是本文件这棵树
        （`AppNavigator:108` `isRestaurant(user) ? <FactoryAdminNavigator /> : <BossNavigator />`）
        ⇒ 屏在、组件在、后端接口在，只是这条路上没人注册它们。

        ⚠️ 四条一起注册，⛔ 不能只注册三个入口屏：它们是一张联通图 ——
          · `TraceabilityScreen`  → `navigate('TraceabilityDetail', { batchNumber })`
          · `VisionVerifyScreen`  → `navigate('SourceIndividualForm', …)`（两处）
        漏掉详情屏的话，入口点得开、下一跳 route not found。

        由 `__tests__/gates/factoryBossReachesFieldEntries.test.ts` 三层钉住
        （可达性 / 同一个 stack / 总览页真按得到）。
      */}
      <OverviewStack.Screen name="Traceability" component={TraceabilityScreen} />
      <OverviewStack.Screen name="TraceabilityDetail" component={TraceabilityDetailScreen} />
      <OverviewStack.Screen name="VisionVerify" component={VisionVerifyScreen} />
      <OverviewStack.Screen name="SourceIndividualForm" component={SourceIndividualFormScreen} />
      <OverviewStack.Screen name="SourceIndividualDetail" component={SourceIndividualDetailScreen} />
      {/* 2026-09-16: 首页「源头个体」瓷砖改落到只读列表 (点行进详情, 右上 + 才进登记表)。 */}
      <OverviewStack.Screen name="SourceIndividualList" component={SourceIndividualListScreen} />
    </OverviewStack.Navigator>
  );
}

export default function BossNavigator() {
  const insets = useSafeAreaInsets();
  const smartBiEnabled = useFactoryFeatureStore((state) => state.isScreenEnabled('SmartBI'));
  const reportsEnabled = useFactoryFeatureStore((state) => state.isScreenEnabled('Reports'));

  return (
    <Tab.Navigator screenLayout={screenErrorBoundary}
      initialRouteName="BossOverviewTab"
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: '#174EA6',
        tabBarInactiveTintColor: '#757575',
        tabBarStyle: { height: 60 + insets.bottom, paddingTop: 5, paddingBottom: insets.bottom + 5 },
      }}
    >
      <Tab.Screen
        name="BossOverviewTab"
        component={BossOverviewStack}
        options={{ title: '总览', tabBarIcon: ({ color, size }) => <Icon source="view-dashboard-outline" color={color} size={size} /> }}
      />
      {/*
        2026-08-15 移除「审批」tab —— 它对工厂超管**永远是空的**。

        后端 MyTodoAggregatorServiceImpl.ROLE_TYPES 只有 finance_manager / cashier 两个 key,
        factory_super_admin 不在其中 → getOrDefault 返空集 → 空列表。
        而这个角色在权限矩阵里是全模块 read_write, **过得了** MyTodoController 上的
        @RequirePermission({"finance:read", ...}) —— 所以不是 403, 是 **HTTP 200 + 空列表**,
        界面显示「暂无待办」, 长得像「你没有待办」而不是「这里查不到你的待办」。

        prod 实测 (2026-08-15) 还查到更根本的一层: approval_workflow_instances **0 行**,
        approval_history **0 行** —— 61 条审批链只是**配置**, 一条实例都没产生过。
        配了采购审批链的两家 (F006 / LIUSHANMEN) **从来没有过任何采购单**(含软删除计 0),
        而有采购单的三家一条链都没配。所以就算把角色加进 ROLE_TYPES, 这个 tab 依然是空的。

        🔴 2026-08-27 订正: **上面那段「0 行」已经过期了** —— 同一条查询今天读到
           approval_workflow_instances **44 行**(43 APPROVED / 1 REJECTED)、
           approval_history **104 行**。审批链现在真的在跑。
           ⚠️ 但**摘掉这个 tab 的决定不变**, 因为第一条理由还站着:
           `MyTodoAggregatorServiceImpl.ROLE_TYPES` 至今**仍然只有**
           finance_manager / cashier 两个 key(2026-08-27 复查), factory_super_admin
           拿到的依旧是空集 ⇒ 对这个角色这个 tab 照样是空的。
           ⇒ 要恢复它, 得先把角色加进 ROLE_TYPES **并**确认那个角色真有待办,
             ⛔ 不是看到「审批有实例了」就接回来。
           📌 本仓已有一条同形教训: 「某功能零实例」**有保质期**, 引用前当场重测那个 count。

        ⇒ 留一个永远空的入口比没有入口更糟, 先摘掉。
        OA 待办中心当前定位: **财务/出纳专用**(MainNavigator / FinanceNavigator 已按角色守住)。
        采购审批走 web-admin 或 OA 审批中心 (POST /workflow/instances/{id}/actions)。
      */}
      {/*
        🔴 2026-08-27 接入「报表」—— 此前工厂用户**一个报表都走不到**。

        这套东西两端都建好了，只是挂错了地方：`FAReportsStackNavigator`(18 个屏)
        原本唯一的挂载点是 `FactoryAdminTabNavigator` 的 `FAReportsTab`，
        条件写着 `isScreenEnabled('Reports') && !isRestaurantMode`；而那棵子树
        只在 `isRestaurant(user)` 为真时挂载（AppNavigator:92）⇒ **恒为假**。
        PR #209 删掉了那个恒假的注册，本 PR 把它接到工厂用户真正走的这棵树上。

        接之前逐条量过（⛔ 不是「看起来有就接」）：

          · 后端 `ReportController` 27 个端点。拿 f006_admin 在服务器本机实打 22 个只读 GET：
            **21 个返回真数据**，坏的 1 个是 `/inventory`（已由 PR #221 修）——
            而本 Tab 的 18 个屏**没有一个调它**。
          · 16 个可达屏里 **14 个真取数**；`TrendReport` / `ForecastReport` 是静态说明页，
            但落地页 `ReportDashboard` 不链它们（只链 11 个），用户点不到。
            ⚠️ `DataExport` 曾被我数成「0 次取数」——那是仪器错：它手拼 URL 打
            `/reports/export/*`，不走 apiClient。它是真的。
          · `isScreenEnabled('Reports')` 映射到 `production` 模块，而该函数在模块
            **未配置时默认 true**（只有显式 enabled=false 才关）。生产实测 F006 的
            `factory_module_configs` 只有 `sales_order` 一行 ⇒ 这里为真，
            ⛔ 不会重蹈「加了个恒假守卫」的覆辙。
      */}
      {reportsEnabled && (
        <Tab.Screen
          name="BossReportsTab"
          component={FAReportsStackNavigator}
          options={{ title: '报表', tabBarIcon: ({ color, size }) => <Icon source="file-chart-outline" color={color} size={size} /> }}
        />
      )}
      {smartBiEnabled && (
        <Tab.Screen
          name="BossAnalysisTab"
          component={SmartBIStackNavigator}
          options={{ title: '分析', tabBarIcon: ({ color, size }) => <Icon source="chart-line" color={color} size={size} /> }}
        />
      )}
      <Tab.Screen
        name="BossProfileTab"
        component={MobileAccountScreen}
        options={{ title: '我的', tabBarIcon: ({ color, size }) => <Icon source="account-outline" color={color} size={size} /> }}
      />
    </Tab.Navigator>
  );
}
