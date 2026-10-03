/**
 * 首页「功能模块」宫格 —— 按 **导航器** 取模块集。
 *
 * ## 这个文件为什么存在（BUG-HOME-MODULES-EMPTY-FOR-SALES-20260906）
 *
 * `screens/main/HomeScreen.tsx` 被 **5 个** 导航器复用（MainNavigator /
 * ProcurementManagerNavigator / FinanceNavigator / SalesManagerNavigator /
 * ViewerNavigator），而它里面写死了一张 5 项的表，全部要
 * `production:read` / `warehouse:read` / `trace:read` / `system:read`。
 *
 * 后端 `FactoryUserRole.getPermissionPrefix()` 给每个角色发的是
 * `<prefix>:*` 一条（实测 `f006_sales_mgr` 的登录响应就是 `["sales:*"]`）。
 * 于是落在这张表上的 9 个角色里 **7 个筛完是空的**：
 *
 *   equipment_admin / quality_manager / procurement_manager / finance_manager
 *   / cashier / sales_manager / viewer  → 首页「功能模块」标题下面一张卡都没有
 *
 * 只有 `permission_admin` / `platform_admin`（`*:*`）看得到卡。
 *
 * ## 🔴 二阶缺陷：光加卡片会加出一堆点不动的卡
 *
 * 原来那 3 个路由（`ProcessingTab` / `LogisticsTab` / `ManagementTab`）**只在
 * MainNavigator 里注册过**。给销售/财务的首页加一张「生产模块」卡，它会
 * `navigate('ProcessingTab')` 到一个那棵树里不存在的路由 —— 卡在，点了没反应。
 *
 * ⇒ 所以模块集是 **按导航器** 分的，且每一项的 `route` / `nestedScreen`
 *   都必须在**那个导航器**里真的注册过。这条由
 *   `src/__tests__/gates/homeModulesNonEmptyPerRole.test.ts` 读导航器的 AST 钉死，
 *   ⛔ 不靠人肉核对路由名。
 *
 * ## 跨 Tab 跳转为什么要 `nestedScreen`
 *
 * React Navigation 的 navigate 只**向上冒泡**，不会钻进兄弟 Tab 的子栈去找路由名。
 * 仓管首页早就踩过并写对了（`WHHomeScreen.tsx`）：
 *
 *   tabNavigation.navigate('WHInboundTab', { screen: 'WHInboundList' })
 *
 * 本文件沿用同一形状：`route` = 该导航器根部注册的 Tab 名，
 * `nestedScreen` = 那个 Tab 内嵌栈里的屏名。MainNavigator 那 5 项本来就直接
 * 指 Tab，所以 `nestedScreen` 留空，行为逐字不变。
 *
 * ## ⚠️ 已知留白（登记，⛔ 不静默）
 *
 *  1. `equipment_admin` / `quality_manager` 走 MainNavigator，而 MainNavigator
 *     里**没有注册任何** equipment / quality 屏（只有考勤 + 我的）。
 *     ⇒ 这两个角色仍然是空宫格，靠 HomeScreen 的「空则不渲染整段」兜住，
 *     不再显示一个孤零零的「功能模块」标题。给它们造入口需要先往
 *     MainNavigator 注册屏，那是另一件事。
 *  2. `trace:read` / `system:read` 这两个前缀**任何角色都拿不到**
 *     （后端只有 `*` 能覆盖），所以 MainNavigator 的 trace/admin/settings 三项
 *     实际只对 `*:*` 可见。这是**存量**行为，本次逐字保留。
 *  3. `isModuleEnabled(id)` 沿用原来的「按模块自身 id 查工厂特性开关」。
 *     工厂特性表里的 id 是粗粒度的（production/logistics/trace/finance/system），
 *     新增这些细粒度 id 查不到 ⇒ 一律视为启用（store 的既有兜底）。
 *     ⛔ 没有把新卡挂到粗粒度开关上 —— 那会新造一条「又变回空宫格」的路径。
 */
import type { User } from '../../types/auth';
import { getUserRole, hasPermission } from '../../types/auth';
import type { ModuleConfig } from '../../types/navigation';
import { hasProductionCapability } from '../../utils/factoryType';
import { hasModulePermission } from '../../utils/permissionHelper';

/**
 * 部署核验用的 ASCII 标记。
 * ⚠️ 必须是纯 ASCII：Hermes 字节码不以 UTF-8 直存中文，中文标记 grep 恒为 0。
 */
export const HOME_MODULE_SETS_MARKER = 'HOME_MODULE_SETS_V1_NAVIGATOR_AWARE';

/** 模块集 id。每个复用 HomeScreen 的导航器用 `initialParams` 指定一个。 */
export type HomeModuleSetId = 'main' | 'sales' | 'procurement' | 'finance' | 'viewer';

export interface HomeModuleSpec {
  /** React key + 工厂特性开关查询键 */
  id: string;
  /** home 命名空间下的 i18n key，zh-CN 与 en-US 都必须有 */
  nameKey: string;
  descriptionKey: string;
  icon: string;
  color: string;
  /** 命中任意一条即可见（沿用原来的 `.some()` 语义） */
  requiredPermissions: string[];
  /** 页面实际接口要求的模块权限，命中任意一项才显示入口。 */
  requiredReadModulesAny?: string[];
  /** 该导航器**根部**注册的路由名（通常是 Tab 名） */
  route?: string;
  /** `route` 那个 Tab 的内嵌栈里的目标屏 */
  nestedScreen?: string;
  /** 只对这些角色可见（用于「只在 cashier 那棵树里注册」的 Tab） */
  requiredRoles?: string[];
  /** 只在有生产能力的工厂可见（餐饮/总部/分店没有） */
  requiresProductionCapability?: boolean;
  /** 进度徽标（存量 processing 卡上有 27%） */
  progress?: number;
}

export type ResolvedHomeModule = ModuleConfig & { nestedScreen?: string };

/** MainNavigator —— 存量 5 项，逐字不动（含 trace/admin/settings 的存量权限）。 */
export const MAIN_HOME_MODULES: HomeModuleSpec[] = [
  {
    id: 'processing',
    nameKey: 'modules.processing.name',
    descriptionKey: 'modules.processing.description',
    icon: 'cube-outline',
    color: '#1890FF',
    requiredPermissions: ['production:read'],
    route: 'ProcessingTab',
    requiresProductionCapability: true,
    progress: 27,
  },
  {
    id: 'logistics',
    nameKey: 'modules.logistics.name',
    descriptionKey: 'modules.logistics.description',
    icon: 'truck-delivery',
    color: '#FAAD14',
    requiredPermissions: ['warehouse:read'],
    route: 'LogisticsTab',
  },
  {
    id: 'trace',
    nameKey: 'modules.trace.name',
    descriptionKey: 'modules.trace.description',
    icon: 'qrcode-scan',
    color: '#722ED1',
    requiredPermissions: ['trace:read'],
    route: 'ProcessingTab',
  },
  {
    id: 'admin',
    nameKey: 'modules.admin.name',
    descriptionKey: 'modules.admin.description',
    icon: 'account-cog',
    color: '#13C2C2',
    requiredPermissions: ['system:read'],
    route: 'ManagementTab',
  },
  {
    id: 'settings',
    nameKey: 'modules.settings.name',
    descriptionKey: 'modules.settings.description',
    icon: 'cog',
    color: '#595959',
    requiredPermissions: ['system:read'],
  },
];

/** SalesManagerNavigator —— 路由取自 `SalesManagerNavigator.tsx` 的 SalesStack / CustomerStack。 */
export const SALES_HOME_MODULES: HomeModuleSpec[] = [
  {
    id: 'salesOrders',
    nameKey: 'modules.salesOrders.name',
    descriptionKey: 'modules.salesOrders.description',
    icon: 'cart-outline',
    color: '#FF6B35',
    requiredPermissions: ['sales:read'],
    route: 'SMSalesTab',
    nestedScreen: 'SalesOrderList',
  },
  {
    id: 'customers',
    nameKey: 'modules.customers.name',
    descriptionKey: 'modules.customers.description',
    icon: 'account-group-outline',
    color: '#1890FF',
    requiredPermissions: ['sales:read'],
    route: 'SMCustomerTab',
    nestedScreen: 'CustomerManagement',
  },
  {
    id: 'arAp',
    nameKey: 'modules.arAp.name',
    descriptionKey: 'modules.arAp.description',
    icon: 'cash-multiple',
    color: '#13C2C2',
    requiredPermissions: ['sales:read'],
    route: 'SMSalesTab',
    nestedScreen: 'ArApOverview',
  },
  {
    id: 'priceList',
    nameKey: 'modules.priceList.name',
    descriptionKey: 'modules.priceList.description',
    icon: 'tag-multiple-outline',
    color: '#722ED1',
    requiredPermissions: ['sales:read'],
    route: 'SMSalesTab',
    nestedScreen: 'PriceList',
  },
  {
    id: 'returnOrders',
    nameKey: 'modules.returnOrders.name',
    descriptionKey: 'modules.returnOrders.description',
    icon: 'keyboard-return',
    color: '#FAAD14',
    requiredPermissions: ['sales:read'],
    route: 'SMSalesTab',
    nestedScreen: 'ReturnOrderList',
  },
];

/** ProcurementManagerNavigator —— 路由取自 `ProcurementManagerNavigator.tsx`。 */
export const PROCUREMENT_HOME_MODULES: HomeModuleSpec[] = [
  {
    id: 'purchaseOrders',
    nameKey: 'modules.purchaseOrders.name',
    descriptionKey: 'modules.purchaseOrders.description',
    icon: 'package-down',
    color: '#2E7D32',
    requiredPermissions: ['procurement:read'],
    route: 'PMPurchaseTab',
    nestedScreen: 'PurchaseOrderList',
  },
  {
    id: 'suppliers',
    nameKey: 'modules.suppliers.name',
    descriptionKey: 'modules.suppliers.description',
    icon: 'truck-delivery-outline',
    color: '#1890FF',
    requiredPermissions: ['procurement:read'],
    route: 'PMSupplierTab',
    nestedScreen: 'SupplierManagement',
  },
  {
    id: 'supplierDelivery',
    nameKey: 'modules.supplierDelivery.name',
    descriptionKey: 'modules.supplierDelivery.description',
    icon: 'clipboard-check-outline',
    color: '#13C2C2',
    requiredPermissions: ['procurement:read'],
    requiredReadModulesAny: ['warehouse', 'finance'],
    route: 'PMPurchaseTab',
    nestedScreen: 'SupplierDeliveryList',
  },
  {
    id: 'purchaseExceptions',
    nameKey: 'modules.purchaseExceptions.name',
    descriptionKey: 'modules.purchaseExceptions.description',
    icon: 'alert-outline',
    color: '#FAAD14',
    requiredPermissions: ['procurement:read'],
    route: 'PMPurchaseTab',
    nestedScreen: 'PurchaseExceptionList',
  },
  {
    id: 'priceList',
    nameKey: 'modules.priceList.name',
    descriptionKey: 'modules.priceList.description',
    icon: 'tag-multiple-outline',
    color: '#722ED1',
    requiredPermissions: ['procurement:read'],
    route: 'PMPurchaseTab',
    nestedScreen: 'PriceList',
  },
];

/**
 * FinanceNavigator —— ⚠️ 这棵树只注册了「待办」和（仅 cashier）「付款」两条业务线。
 * 付款 Tab 外面套着 `{isCashier && …}`，所以 `finance_manager` 拿它会点到不存在的路由，
 * 必须用 `requiredRoles` 拦住。
 */
export const FINANCE_HOME_MODULES: HomeModuleSpec[] = [
  {
    id: 'todoApprovals',
    nameKey: 'modules.todoApprovals.name',
    descriptionKey: 'modules.todoApprovals.description',
    icon: 'clipboard-check-outline',
    color: '#1B65A8',
    requiredPermissions: ['finance:read'],
    route: 'FinanceTodoTab',
    nestedScreen: 'MyTodoList',
  },
  {
    id: 'cashierPayments',
    nameKey: 'modules.cashierPayments.name',
    descriptionKey: 'modules.cashierPayments.description',
    icon: 'bank-transfer-out',
    color: '#13C2C2',
    requiredPermissions: ['finance:read'],
    requiredRoles: ['cashier'],
    route: 'CashierPaymentTab',
    nestedScreen: 'CashierPaymentList',
  },
];

/** ViewerNavigator —— 全只读。后端给 viewer 的前缀是 `view`（实测 `getPermissionPrefix()`）。 */
export const VIEWER_HOME_MODULES: HomeModuleSpec[] = [
  {
    id: 'salesOrders',
    nameKey: 'modules.salesOrders.name',
    descriptionKey: 'modules.salesOrders.description',
    icon: 'cart-outline',
    color: '#607D8B',
    requiredPermissions: ['view:read'],
    route: 'VWSalesTab',
    nestedScreen: 'SalesOrderList',
  },
  {
    id: 'purchaseOrders',
    nameKey: 'modules.purchaseOrders.name',
    descriptionKey: 'modules.purchaseOrders.description',
    icon: 'package-down',
    color: '#2E7D32',
    requiredPermissions: ['view:read'],
    route: 'VWPurchaseTab',
    nestedScreen: 'PurchaseOrderList',
  },
  {
    id: 'finishedGoods',
    nameKey: 'modules.finishedGoods.name',
    descriptionKey: 'modules.finishedGoods.description',
    icon: 'warehouse',
    color: '#FAAD14',
    requiredPermissions: ['view:read'],
    route: 'VWPurchaseTab',
    nestedScreen: 'FinishedGoodsList',
  },
  {
    id: 'priceList',
    nameKey: 'modules.priceList.name',
    descriptionKey: 'modules.priceList.description',
    icon: 'tag-multiple-outline',
    color: '#722ED1',
    requiredPermissions: ['view:read'],
    route: 'VWSalesTab',
    nestedScreen: 'PriceList',
  },
  {
    id: 'arAp',
    nameKey: 'modules.arAp.name',
    descriptionKey: 'modules.arAp.description',
    icon: 'cash-multiple',
    color: '#13C2C2',
    requiredPermissions: ['view:read'],
    route: 'VWSalesTab',
    nestedScreen: 'ArApOverview',
  },
];

export const HOME_MODULE_SETS: Record<HomeModuleSetId, HomeModuleSpec[]> = {
  main: MAIN_HOME_MODULES,
  sales: SALES_HOME_MODULES,
  procurement: PROCUREMENT_HOME_MODULES,
  finance: FINANCE_HOME_MODULES,
  viewer: VIEWER_HOME_MODULES,
};

const VALID_SET_IDS = new Set<string>(Object.keys(HOME_MODULE_SETS));

/** 路由参数里拿到的 moduleSet 收敛成合法 id；认不出来时退回 `main`（存量行为）。 */
export function toHomeModuleSetId(raw: unknown): HomeModuleSetId {
  return typeof raw === 'string' && VALID_SET_IDS.has(raw) ? (raw as HomeModuleSetId) : 'main';
}

/**
 * 把某个模块集按当前 user 筛成可渲染的卡片。
 *
 * ⚠️ HomeScreen 与契约测试调的是**同一个函数** —— 测试断言的就是屏上那份，
 * 不是另抄一份筛选逻辑（形态 D）。
 */
export function resolveHomeModules(
  setId: HomeModuleSetId,
  user: User | null | undefined,
  isModuleEnabled: (moduleId: string) => boolean,
  t: (key: string) => string
): ResolvedHomeModule[] {
  if (!user) return [];
  const role = getUserRole(user);

  return HOME_MODULE_SETS[setId]
    .filter((spec) => {
      if (!isModuleEnabled(spec.id)) return false;
      if (spec.requiresProductionCapability && !hasProductionCapability(user)) return false;
      if (spec.requiredRoles && !spec.requiredRoles.includes(role)) return false;
      if (spec.requiredReadModulesAny && !spec.requiredReadModulesAny.some(module => hasModulePermission(user, module, 'read'))) return false;
      return spec.requiredPermissions.some((permission) => hasPermission(user, permission));
    })
    .map((spec) => ({
      id: spec.id,
      name: t(spec.nameKey),
      icon: spec.icon,
      description: t(spec.descriptionKey),
      status: 'available' as const,
      progress: spec.progress,
      requiredPermissions: spec.requiredPermissions,
      route: spec.route,
      nestedScreen: spec.nestedScreen,
      color: spec.color,
    }));
}
