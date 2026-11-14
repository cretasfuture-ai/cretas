<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAppStore } from '@/store/modules/app';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore, ModuleName } from '@/store/modules/permission';
import { useTaskBadgeStore } from '@/store/modules/taskBadges';
import { useDisposalApprovalStore } from '@/store/modules/disposalApproval';
import { useWorkspaceStore } from '@/store/modules/workspace';
import { useNavigationVisibilityStore, type NavigationVisibilityIdentity } from '@/store/modules/navigationVisibility';
import { usePlatformFactoryContextStore, isFactoryViewPageRoute } from '@/store/modules/platformFactoryContext';
import type { User as AuthUser } from '@/types/auth';
import { get } from '@/api/request';
import { getFactoryAssemblyEnabled, isFactoryAssemblyPlatformUser } from '@/api/factoryAssemblyCapabilities';
import { startVisibleBadgeRefresh } from '@/utils/badgeRefreshTimer';
import {
  House, Operation, Box, Checked, ShoppingCart, Goods,
  User, Monitor, Money, Setting, DataAnalysis, Calendar,
  TrendCharts, Sell, Upload, ChatDotRound, Aim, Odometer, Tickets,
  Histogram, StarFilled
} from '@element-plus/icons-vue';
import { factoryMenuForType, platformMenuConfig, financeManagerMenu, MENU_SECTION_TITLES, type MenuItem, type MenuSection } from './menuConfig';
import { resolveWorkspaceFactoryType, resolveWorkspaceFactoryTypeFromUser } from '@/config/workspaces';
import SidebarMenuNode from './SidebarMenuNode.vue';
import MenuSearch from './MenuSearch.vue';
import { PINNED_MENU_LIMIT, pinnedMenuStorageKey, usePinnedMenu } from './pinnedMenu';
import { getF006DemoMenu } from './f006DemoMenu';
import {
  isFactoryAnalyticsSidebarTurnedOff, isFactoryAnalyticsGroupHidden,
  getDisabledFactoryMenuPaths, getHiddenFactoryMenuModules, isFactoryMenuHidden,
} from './factoryMenuVisibility';
import { secrecyFromFeatureConfig, secrecyHiddenMenuPaths } from '@/api/factorySecrecy';

const router = useRouter();
const route = useRoute();
const appStore = useAppStore();
const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const taskBadges = useTaskBadgeStore();
const disposalApproval = useDisposalApprovalStore();
const workspace = useWorkspaceStore();
const navigationVisibility = useNavigationVisibilityStore();
const factoryView = usePlatformFactoryContextStore();

// 当前用户角色
const roleCode = computed(() => authStore.currentRole);
const effectiveFactoryId = computed(() => factoryView.factoryId || authStore.factoryId);
const effectiveFactoryType = computed(() => factoryView.context?.factoryType || authStore.factoryType);
const workspaceFactoryType = computed(() => resolveWorkspaceFactoryType(
  factoryView.isViewingFactory
    ? factoryView.context?.factoryType
    : resolveWorkspaceFactoryTypeFromUser(authStore.user),
));

// R19: Canvas module enable/disable — hide disabled modules from sidebar
const disabledModuleCodes = ref<string[]>([]);
const factoryAnalyticsTurnedOff = ref(false);
const hiddenFactoryMenuPaths = ref<string[]>([]);
const hiddenFactoryMenuModules = ref<string[]>([]);
// Backend-authoritative assembly switch. Keep the default closed until the
// platform capability endpoint explicitly returns enabled=true.
const factoryAssemblyEnabled = ref(false);
const MODULE_CODE_TO_SIDEBAR: Record<string, string> = {
  sales_order: 'sales', purchase_order: 'procurement', production_plan: 'production',
  quality_inspection: 'quality', hr_employee: 'hr', equipment: 'equipment',
  finance_ar: 'finance', finance_ap: 'finance', warehouse: 'warehouse',
  scheduling: 'scheduling',
};

// 路演 demo 租户 (DEMO_*) 侧边栏策展: 隐藏内部/无数据模块, 让可见模块都有充足数据。
// 工厂 demo (DEMO_FACTORY2, 源 F006 六扇门 卤味加工厂) 以生产报工链为核心 — 财务无凭证 →
// 藏 finance; 餐饮 demo (DEMO_REST) 无工厂销售订单 → 藏 sales。system 内部管理两业态都藏。
const DEMO_HIDE_MODULES_BY_TYPE: Record<string, string[]> = {
  // 财务模块已补数据 (ar_ap_transactions 204 / invoice 97 / payment 97 → 财务概览/应收应付/
  // 开票/收款 有数据) → 不再整组藏 finance, 只藏空的 reports/adjustments 子页 (见 PATHS)。
  FACTORY: ['quality', 'equipment', 'scheduling', 'system'],
  LOGISTICS: ['production', 'warehouse', 'quality', 'procurement', 'sales', 'hr', 'equipment', 'finance', 'system', 'restaurant', 'analytics'],
};
// 路径前缀级隐藏 (按业态). 2026-06-14 DB ground-truth (每表 count) + headed 逐页核实:
//   工厂 (DEMO_FACTORY2, F006): 报工链是亮点 — 生产批次(104)/计划(133)/报工审批·工序投入产出·
//     人效双口径(production_reports 335)/BOM达成率/生产数据分析(fact_production_report 335)/
//     成品(30)/采购(30)/销售订单(97)/客户(16)/物料(73)/经营驾驶舱(agg_daily 合成 ¥710万)/
//     AI问答/进销存/员工(36) 都有数据 → 留。藏 (count=0): 物料需求/研发样品/备货看板(当日空)/
//     报损/盘点/经营分析hub(smart_bi_finance=0)/异常预警/车间报表(当日空)/成本分析(fact_cost_line=0)/
//     人效分析(数据与分析组, 与生产组人效双口径重复) + 数据管理/AI运维工具组。
//   餐饮 (DEMO_REST): 留 经营驾驶舱/经营分析hub/收入报表/AI问答/菜品分析/门店对比/价格预警/
//     配方/损耗。藏 AI体检(降级)/异常预警(空)/经营看板role-kpi(毛利计算异常)/平台口碑(无点评)/
//     ETL状态·菜品名称匹配·数据完整度(内部工具) + 数据管理/AI运维工具组。
const DEMO_HIDE_PATHS_BY_TYPE: Record<string, string[]> = {
  FACTORY: [
    '/hr/attendance', '/hr/departments', '/hr/work-types', '/hr/whitelist',
    '/sales/payment-requests', '/sales/returns',
    // Phase2/3/4 已补数据 → 不再隐藏: 报损/调整审批/财务报表/备货看板/异常预警/车间报表
    // + Excel上传(smart_bi_pg_excel_uploads 4文件)/数据完整度(计算综合20.7%/生产批次82/物料73)/
    // 分析概览/知识库反馈(5反馈)/AI追问日志(smart_bi_llm_fallback_log 5条 卤味问答)。
    // 查询模板: 后端缺失的 CRUD 端点已补 (PR #860 SmartBiQueryTemplateController, 修了对所有
    // 工厂的预存 404 bug), 6 个卤味分析模板 → un-hide。仅剩 2 项隐藏:
    '/transfer/list',                     // 调拨单: internal_transfers 是工厂↔工厂调拨 (source/target_factory_id), 单租户 demo 无此类数据; 且空数据时 list API 返 400 (预存 service 问题)
    '/smart-bi/analysis-hub',             // 经营分析hub: 与经营驾驶舱功能重复 (旗舰已覆盖, 冗余); 财务tab 另需完整 Excel 上传数据源选择交互
    '/smart-bi/calibration',              // 行为校准监控: roles=['platform_admin'], demo 账号本就被角色门控隐藏 (非数据问题)
  ],
  LOGISTICS: [
    '/scheduling/overview',
    '/scheduling/plans',
    '/scheduling/realtime',
    '/scheduling/workers',
    '/scheduling/alerts',
    '/scheduling/settings',
  ],
};
function isDemoTenant(factoryId: string | undefined): boolean {
  return /^DEMO_/.test(factoryId || '');
}
// WS6 reactivity fix: previously this ran in onMounted and bailed out with
// `if (!authStore.factoryId) return;`. If the sidebar mounted before `user`
// hydrated (factoryId still ''), the fetch never ran AND never retried →
// disabled modules stayed visible until a manual page refresh. Watching
// factoryId (immediate) re-runs the fetch the moment it becomes available,
// and re-runs again if the user/factory identity changes (re-login). Updates
// the reactive `disabledModuleCodes` ref so `disabledSidebarModules` (and thus
// `filteredMenu`) recompute automatically.
async function fetchDisabledModules(factoryId: string) {
  const token = navigationVisibility.beginLoading();
  factoryAnalyticsTurnedOff.value = false;
  // 保密配置读到 `=== true` 之前, 依赖它的菜单 (工艺保密) 一律不显示
  hiddenFactoryMenuPaths.value = secrecyHiddenMenuPaths({});
  hiddenFactoryMenuModules.value = [];
  if (!factoryId || isDemoTenant(factoryId)) {
    disabledModuleCodes.value = [];
    navigationVisibility.markUnavailableIfCurrent(token);
    return;
  }
  try {
    const [disabledResult, featuresResult] = await Promise.allSettled([
      get(`/${factoryId}/config/disabled-modules`),
      get(`/${factoryId}/feature-config`),
    ]);
    // Guard against stale responses if factoryId changed mid-flight (re-login).
    if (!navigationVisibility.isCurrentLoad(token)) return;
    const res = disabledResult.status === 'fulfilled' ? disabledResult.value : null;
    const disabledPayloadValid = res?.success === true && Array.isArray(res.data);
    const featuresPayload = featuresResult.status === 'fulfilled' ? featuresResult.value : null;
    const featuresPayloadValid = featuresPayload?.success === true && Array.isArray(featuresPayload.data);
    if (disabledPayloadValid) {
      disabledModuleCodes.value = res.data;
    } else {
      disabledModuleCodes.value = [];
    }
    if (!disabledPayloadValid || !featuresPayloadValid) {
      hiddenFactoryMenuModules.value = [];
      navigationVisibility.markErrorIfCurrent(token);
      return;
    }
    factoryAnalyticsTurnedOff.value = isFactoryAnalyticsSidebarTurnedOff(featuresPayload.data);
    // 工厂级保密配置 (平台设置, 只读): 「工艺保密」不是明确开启 (=== true) 时隐藏工艺保密菜单
    const secrecyFlags = secrecyFromFeatureConfig(featuresPayload.data);
    hiddenFactoryMenuPaths.value = [
      ...getDisabledFactoryMenuPaths(featuresPayload.data),
      ...secrecyHiddenMenuPaths(secrecyFlags),
    ];
    hiddenFactoryMenuModules.value = getHiddenFactoryMenuModules(featuresPayload.data);
    navigationVisibility.markReadyIfCurrent(token);
  } catch {
    /* config not set up for this factory */
    if (navigationVisibility.isCurrentLoad(token)) {
      disabledModuleCodes.value = [];
      hiddenFactoryMenuModules.value = [];
      navigationVisibility.markErrorIfCurrent(token);
    }
  }
}
watch(
  () => effectiveFactoryId.value,
  (factoryId) => { void fetchDisabledModules(factoryId); },
  { immediate: true },
);
watch(
  () => authStore.user,
  async (user) => {
    factoryAssemblyEnabled.value = isFactoryAssemblyPlatformUser(user as AuthUser | null)
      ? await getFactoryAssemblyEnabled()
      : false;
  },
  { immediate: true },
);
const disabledSidebarModules = computed(() => {
  const set = new Set<string>();
  for (const code of disabledModuleCodes.value) {
    const sidebar = MODULE_CODE_TO_SIDEBAR[code];
    if (sidebar) set.add(sidebar);
  }
  return set;
});

const isCollapsedSidebar = computed(() => appStore.sidebarCollapsed && !appStore.isMobile);

// Element Plus moves leaf titles into tooltips when collapse becomes true.
// Keep them in place until the 140ms fade finishes, and cancel on reversal.
const menuCollapsed = ref(isCollapsedSidebar.value);
let collapseTimer: ReturnType<typeof setTimeout> | undefined;
watch(isCollapsedSidebar, (collapsed) => {
  clearTimeout(collapseTimer);
  if (!collapsed || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    menuCollapsed.value = collapsed;
    return;
  }
  collapseTimer = setTimeout(() => { menuCollapsed.value = true; }, 160);
});
onBeforeUnmount(() => clearTimeout(collapseTimer));

// 图标映射
const iconMap: Record<string, any> = {
  House, Operation, Box, Checked, ShoppingCart, Goods,
  User, Monitor, Money, Setting, DataAnalysis, Calendar,
  TrendCharts, Sell, Upload, ChatDotRound, Aim, Odometer, Tickets,
  Histogram
};

// 菜单配置已抽到 ./menuConfig.ts (可单测) — MenuItem / menuConfig / financeManagerMenu 由顶部 import 引入

// 检查菜单项是否可见（基于角色限制 + 工厂类型限制）
//
// WS6 reactivity fix: read every reactive source EAGERLY up-front before any
// short-circuit, so Vue registers `filteredMenu`'s dependency on all of them on
// the very first evaluation. Previously the early `return false` branches and
// the `&&` short-circuit meant `permissionStore.canAccess(...)` /
// `permissionStore.currentRole` were sometimes never read for an item when
// auth/permission state was still stale on first render → that access path was
// never tracked → the menu could fail to re-evaluate when permissions finished
// loading, requiring a manual refresh. Reading them all unconditionally makes
// the computed depend on factoryType, the disabled-module set, currentRole and
// the (DB-or-fallback) permission level for the module — so any of them
// changing reliably re-runs the filter.
// P2：侧栏节点可见性 = 节点自身判据（canSeeMenuItem）+ 原型分组带来的两条规则。
// 每次都先跑 canSeeMenuItem(item)，让它的响应式读取（见 AppSidebar.reactivity.spec）照常注册。
function canSeeMenuNode(item: MenuItem): boolean {
  const own = canSeeMenuItem(item);
  // 纯展示分组不带判据，可见性只由子项决定（filterMenuItem 里子项全隐藏则整组隐藏）。
  if (item.container) {
    // 「数据与分析」容器认「按厂关掉」开关（只看这一个开关，不套其它判据；其余容器无判据）
    return !isFactoryAnalyticsGroupHidden(
      effectiveFactoryType.value || authStore.factoryType, item.path, factoryAnalyticsTurnedOff.value,
    );
  }
  // 从原分组搬出来的叶子，原父组也必须可见（与搬家前「父组隐藏 ⇒ 子项隐藏」等价）。
  const ancestorsVisible = (item.guardAncestors ?? []).map((ancestor) => canSeeMenuItem(ancestor)).every(Boolean);
  return own && ancestorsVisible;
}

function canSeeMenuItem(item: MenuItem): boolean {
  // Keep the auth identity read eager for Vue dependency tracking; a factory
  // view context only overrides the effective tenant below.
  const authFactoryType = authStore.factoryType;
  const factoryType = effectiveFactoryType.value || authFactoryType;
  const disabledSet = disabledSidebarModules.value;
  const currentRole = permissionStore.currentRole;
  const canAccess = permissionStore.canAccess(item.module);
  const factoryAnalyticsHidden = isFactoryAnalyticsGroupHidden(
    factoryType, item.path, factoryAnalyticsTurnedOff.value,
  );
  const assemblyEnabled = factoryAssemblyEnabled.value;
  const hiddenByFactory = isFactoryMenuHidden(item.path, hiddenFactoryMenuPaths.value);
  const hiddenByModule = hiddenFactoryMenuModules.value.includes(item.module);

  if (hiddenByFactory || hiddenByModule || factoryAnalyticsHidden) return false;

  const itemFactoryType = item.requiresFactoryContext ? workspaceFactoryType.value : factoryType;
  if (item.allowedFactoryTypes && !item.allowedFactoryTypes.includes(itemFactoryType)) return false;
  if (item.requiresFactoryContext && (!effectiveFactoryId.value?.trim() || authStore.isPlatform || !workspaceFactoryType.value)) return false;

  // 路演 demo 租户策展: 隐藏内部/无数据模块 (按业态)
  if (isDemoTenant(effectiveFactoryId.value)) {
    const hidePaths = DEMO_HIDE_PATHS_BY_TYPE[factoryType] || [];
    if (hidePaths.some(p => item.path === p || item.path.startsWith(p + '/'))) {
      return false;
    }
    if ((DEMO_HIDE_MODULES_BY_TYPE[factoryType] || []).includes(item.module)) {
      return false;
    }
  }

  if (item.hideForFactoryTypes?.includes(factoryType)) {
    return false;
  }
  // R19: Canvas module enable/disable — hide disabled modules
  if (disabledSet.has(item.module)) {
    return false;
  }
  if (item.assemblyFeature && !assemblyEnabled) {
    return false;
  }
  if (item.requiresDisposalApproval && !disposalApproval.canApprove) return false;
  if (!item.roles || item.roles.length === 0) {
    return canAccess;
  }
  return item.roles.includes(currentRole) && canAccess;
}

function filterMenuItem(item: MenuItem): MenuItem | null {
  if (factoryView.isViewingFactory && !item.children?.length && !isFactoryViewPageRoute(item.path)) return null;
  if (!canSeeMenuNode(item)) return null;
  if (!item.children?.length) return item;

  const filteredChildren = item.children
    .map(child => filterMenuItem(child))
    .filter((child): child is MenuItem => child !== null);

  if (filteredChildren.length === 0) return null;
  return { ...item, children: filteredChildren };
}

// 过滤有权限的菜单。财务主管虽使用简化菜单，也必须经过同一套业态、模块和角色过滤；
// 否则 hideForFactoryTypes 只写在配置里却从未执行，会产生「菜单可见、点击 403」。
const filteredMenu = computed(() => {
  const sourceMenu = authStore.isPlatform && !factoryView.isViewingFactory
    ? platformMenuConfig
    : roleCode.value === 'finance_manager' ? financeManagerMenu : factoryMenuForType(effectiveFactoryType.value);
  const demoMenu = factoryView.isViewingFactory ? [] : getF006DemoMenu(effectiveFactoryId.value, roleCode.value);
  return [...demoMenu, ...sourceMenu]
    .map(item => filterMenuItem(item))
    .filter((item): item is MenuItem => item !== null);
});

function collectVisiblePaths(items: readonly MenuItem[]): string[] {
  return items.flatMap((item) => [
    // container 的 path 是 '#group/...' 不是路由，不发布；搬出来的叶子把原父组路径一起发布（与搬家前一致）
    ...(item.container ? [] : [item.path]),
    ...(item.guardAncestors ?? []).map((ancestor) => ancestor.path),
    ...(item.children ? collectVisiblePaths(item.children) : []),
  ]);
}

// P2「常用」：浏览器本地按「账号 id + 工厂 id」存 path；只显示当前侧栏看得见的项（权限仍由上面判）。
const pinnedMenu = usePinnedMenu();
watch(
  () => [authStore.user?.id, effectiveFactoryId.value] as const,
  ([userId, factoryId]) => pinnedMenu.bind(pinnedMenuStorageKey(userId, factoryId)),
  { immediate: true },
);
function visibleLeaves(items: readonly MenuItem[]): MenuItem[] {
  return items.flatMap((item) => (item.children?.length ? visibleLeaves(item.children) : [item]));
}
// 侧栏过滤后的可见叶子交给「常用」：顶栏星标只能钉这些（审查 #1692 第 4 条）。
watch(
  () => (authStore.isPlatform && !factoryView.isViewingFactory ? [] : visibleLeaves(filteredMenu.value).map((leaf) => leaf.path)),
  (paths) => pinnedMenu.setVisibleLeafPaths(paths),
  { immediate: true },
);
const pinnedItems = computed<MenuItem[]>(() => {
  if (authStore.isPlatform && !factoryView.isViewingFactory) return [];
  const byPath = new Map(visibleLeaves(filteredMenu.value).map((leaf) => [leaf.path, leaf]));
  return pinnedMenu.pinned.value
    .map((path) => byPath.get(path))
    .filter((leaf): leaf is MenuItem => !!leaf)
    .slice(-PINNED_MENU_LIMIT);
});
const PINNED_INDEX_PREFIX = 'pin:';

/** 侧栏分区标题：在每个分区的第一项前显示一次（「全部功能」「设置」；top 分区无标题）。 */
function sectionTitleBefore(index: number): string {
  const items = filteredMenu.value;
  const section: MenuSection | undefined = items[index]?.section;
  if (!section) return '';
  if (index > 0 && items[index - 1]?.section === section) return '';
  return MENU_SECTION_TITLES[section];
}

const navigationIdentity = computed<NavigationVisibilityIdentity>(() => ({
  factoryId: effectiveFactoryId.value || '',
  role: roleCode.value,
  factoryType: workspaceFactoryType.value,
  isPlatform: authStore.isPlatform,
}));

// Publish only the already fully filtered sidebar paths. Consumers must match
// this identity exactly; a missing or stale snapshot fails closed.
watch(
  [filteredMenu, navigationIdentity, () => navigationVisibility.status],
  ([visibleMenu, identity, status]) => {
    if (status !== 'ready') return;
    navigationVisibility.publish({
      ...identity,
      paths: collectVisiblePaths(visibleMenu),
    });
  },
  { immediate: true, deep: true },
);

const LOGISTICS_TITLE_OVERRIDES: Record<string, string> = {
  '/scheduling': '物流调度',
  '/scheduling/logistics/workbench': '排线工作台',
  '/scheduling/logistics/records': '调度记录',
  '/scheduling/logistics/orders': '门店与订单',
  '/scheduling/logistics/resources': '车辆与司机',
};

function titleForItem(item: MenuItem): string {
  if (authStore.factoryType === 'LOGISTICS' && LOGISTICS_TITLE_OVERRIDES[item.path]) {
    return LOGISTICS_TITLE_OVERRIDES[item.path];
  }
  return item.title;
}

function badgeForItem(item: MenuItem): number | 'unknown' | null {
  if (factoryView.isViewingFactory) return null;
  if (taskBadges.isUnknown(item.badgeKey)) return 'unknown';
  return taskBadges.badge(item.badgeKey);
}

let stopBadgeTimer: (() => void) | undefined;
let badgeRefreshTimer: ReturnType<typeof setTimeout> | undefined;
let badgeRefreshForced = false;
function scheduleBadgeRefresh(force = false): void {
  if (factoryView.isViewingFactory) return;
  badgeRefreshForced ||= force;
  if (badgeRefreshTimer) clearTimeout(badgeRefreshTimer);
  badgeRefreshTimer = setTimeout(() => {
    const shouldForce = badgeRefreshForced;
    badgeRefreshForced = false;
    if (!factoryView.isViewingFactory) void taskBadges.loadAll(shouldForce);
  }, 350);
}
function forceBadgeRefresh(): void {
  scheduleBadgeRefresh(true);
}
function refreshBadgesWhenVisible(): void {
  if (document.visibilityState === 'visible') scheduleBadgeRefresh();
}

function refreshFactoryFeatures(event: Event): void {
  const factoryId = (event as CustomEvent<{ factoryId: string }>).detail?.factoryId;
  if (factoryId && factoryId === authStore.factoryId) void fetchDisabledModules(factoryId);
}

watch(
  () => authStore.factoryId,
  (factoryId) => {
    taskBadges.setFactory(factoryView.isViewingFactory ? '' : (factoryId || ''));
    scheduleBadgeRefresh();
  },
  { immediate: true },
);

watch(() => route.path, () => scheduleBadgeRefresh());

// 报废审批入口按后端 approval-permission 判 (disposal:approve); 没有 finance 模块的人不问 (不发必 403 的请求)。
watch(
  () => [authStore.factoryId, authStore.user?.id, permissionStore.canAccess('finance')] as const,
  ([factoryId, userId, hasFinance]) => {
    if (factoryView.isViewingFactory || !hasFinance) { disposalApproval.reset(); return; }
    void disposalApproval.ensureLoaded(String(factoryId || ''), String(userId ?? ''));
  },
  { immediate: true },
);

onMounted(() => {
  stopBadgeTimer = startVisibleBadgeRefresh(
    () => { if (!factoryView.isViewingFactory) void taskBadges.loadAll(); },
    () => document.visibilityState === 'visible',
  );
  window.addEventListener('cretas:task-badges-refresh', forceBadgeRefresh);
  window.addEventListener('cretas:factory-features-updated', refreshFactoryFeatures);
  document.addEventListener('visibilitychange', refreshBadgesWhenVisible);
});

onBeforeUnmount(() => {
  navigationVisibility.clear();
  stopBadgeTimer?.();
  if (badgeRefreshTimer) clearTimeout(badgeRefreshTimer);
  badgeRefreshForced = false;
  window.removeEventListener('cretas:task-badges-refresh', forceBadgeRefresh);
  window.removeEventListener('cretas:factory-features-updated', refreshFactoryFeatures);
  document.removeEventListener('visibilitychange', refreshBadgesWhenVisible);
});

// 当前激活的菜单
const activeMenu = computed(() => route.path);

function findOpenParents(path: string, items: MenuItem[], parents: string[] = []): string[] | null {
  for (const item of items) {
    if (!item.children?.length) continue;
    const nextParents = [...parents, item.path];
    if (item.children.some(child => path === child.path || path.startsWith(child.path + '/'))) {
      return nextParents;
    }
    const nestedParents = findOpenParents(path, item.children, nextParents);
    if (nestedParents) return nestedParents;
  }
  return null;
}

/**
 * 顶级菜单少到一定程度时，整棵树默认铺开 —— ⛔ 不让用户点开下拉才看得到自己能干什么。
 *
 * 2026-08-21 owner 实测（销售主管）：「因为这是单独的权限，他就不需要这种导航栏下拉了，
 * 他也全部铺开就好了」。销售主管收敛后只剩 工作台 / 个人OA / 销售管理，
 * 三项还要逐个点开才看得见子项，纯属多此一举。
 *
 * ⚠️ 阈值是**顶级可见项数**，⛔ 不是角色名 —— 按角色写死会漏掉以后新增的角色，
 *    而且同一个角色在不同工厂启用的模块数也不一样（菜单还按 module 启停过滤）。
 */
/**
 * P2 改组后的阈值（审查 #1692 第 3 条）：原来数**顶级可见项**（≤ 4 铺开）。改组后顶级多了
 * 「概览」「主数据」这类展示分组，同一个销售主管顶级从 3–5 项变成 5–6 项，铺开整体失效；
 * 而且「工作台」嵌在「概览」里，只开顶级也还要再点一次。
 * ⇒ 改数**可见叶子数**（真正能点的页面），≤ 24 就整棵树（含嵌套分组）全部铺开。
 *   依据：本地实测单部门角色的可见叶子——销售主管、采购经理都在 24 以内（整页滚动可看完）；
 *   车间主任这类跨部门管理角色 60+，铺开反而找不到东西，保持「只展开当前所在组」。
 *   仍然 ⛔ 不按角色名写死（同一角色在不同工厂启用的模块不同）。
 */
const FLATTEN_WHEN_VISIBLE_LEAVES_AT_MOST = 24;

// ⚠️ 铺开态下必须把 el-menu 的 unique-opened 关掉（模板里 :unique-opened="!shouldFlatten"）：
//    它强制「同时只能展开一个」，会把 defaultOpeneds 里除第一个以外的全部收起来，
//    于是 defaultOpeneds 看起来生效了、实际只开了一个。
const shouldFlatten = computed(() => visibleLeaves(filteredMenu.value).length <= FLATTEN_WHEN_VISIBLE_LEAVES_AT_MOST);

function allGroupPaths(items: readonly MenuItem[]): string[] {
  return items.flatMap((m) => (m.children?.length ? [m.path, ...allGroupPaths(m.children)] : []));
}

const defaultOpeneds = computed(() => {
  if (shouldFlatten.value) {
    // 全铺开：所有层级的分组都列进去（「概览 › 工作台」这种嵌套的也开）。
    return allGroupPaths(filteredMenu.value);
  }
  return findOpenParents(route.path, filteredMenu.value) ?? [];
});

function handleSelect(index: string) {
  const path = index.startsWith(PINNED_INDEX_PREFIX) ? index.slice(PINNED_INDEX_PREFIX.length) : index;
  const target = router.resolve(path);
  void router.push(workspace.menuRoute({
    path: target.path,
    fullPath: target.fullPath,
    query: target.query,
    title: typeof target.meta.title === 'string' ? target.meta.title : undefined,
  }));
  if (appStore.isMobile) {
    appStore.closeMobileMenu();
  }
}
</script>

<template>
  <!-- 移动端遮罩层 -->
  <div
    v-if="appStore.isMobile && appStore.mobileMenuOpen"
    class="sidebar-overlay"
    @click="appStore.closeMobileMenu()"
  />

  <aside
    class="app-sidebar"
    :class="{
      'is-collapsed': appStore.sidebarCollapsed && !appStore.isMobile,
      'mobile-open': appStore.isMobile && appStore.mobileMenuOpen
    }"
  >
    <!-- Logo -->
    <div class="sidebar-logo">
      <img :src="authStore.factoryLogoUrl" :alt="authStore.brandTitle" class="logo-icon" />
      <span class="logo-copy" :aria-hidden="isCollapsedSidebar">
        <span class="logo-title">{{ authStore.brandTitle }}</span>
        <span v-if="authStore.brandSubtitle" class="logo-subtitle">{{ authStore.brandSubtitle }}</span>
      </span>
    </div>

    <!-- P2 菜单搜索（Ctrl+K），只搜当前侧栏可见项 -->
    <MenuSearch
      :menu="filteredMenu"
      :collapsed="isCollapsedSidebar"
      :title-for-item="titleForItem"
      @select="handleSelect"
    />

    <!-- 菜单 -->
    <el-scrollbar class="sidebar-menu-wrap" @wheel.stop>
      <el-menu
        class="sidebar-menu"
        :default-active="activeMenu"
        :default-openeds="defaultOpeneds"
        :collapse="menuCollapsed"
        :collapse-transition="false"
        :unique-opened="!shouldFlatten"
        background-color="transparent"
        text-color="#ffffffa6"
        active-text-color="#ffffff"
        @select="handleSelect"
      >
        <template v-if="pinnedItems.length">
          <div
            class="menu-section-title"
            :class="{ 'is-collapsed': isCollapsedSidebar }"
            :aria-hidden="isCollapsedSidebar"
            role="presentation"
          >常用</div>
          <el-menu-item
            v-for="pin in pinnedItems"
            :key="`pin-${pin.path}`"
            :index="`${PINNED_INDEX_PREFIX}${pin.path}`"
            :aria-label="`常用：${titleForItem(pin)}`"
            class="pinned-menu-item"
          >
            <el-icon><StarFilled /></el-icon>
            <template #title>
              <span class="menu-title-copy">{{ titleForItem(pin) }}</span>
              <button
                type="button"
                class="pinned-remove"
                :aria-label="`从常用移除：${titleForItem(pin)}`"
                title="从常用移除"
                @click.stop="pinnedMenu.toggle(pin.path)"
              >×</button>
            </template>
          </el-menu-item>
        </template>
        <template v-for="(item, index) in filteredMenu" :key="item.path">
        <div
          v-if="sectionTitleBefore(index)"
          class="menu-section-title"
          :class="{ 'is-collapsed': isCollapsedSidebar }"
          :aria-hidden="isCollapsedSidebar"
          role="presentation"
        >{{ sectionTitleBefore(index) }}</div>
        <SidebarMenuNode
          :item="item"
          :collapsed="isCollapsedSidebar"
          :icon-map="iconMap"
          :title-for-item="titleForItem"
          :badge-for-item="badgeForItem"
          :level="1"
        />
        </template>
      </el-menu>
    </el-scrollbar>
  </aside>
</template>

<style lang="scss" scoped>
.app-sidebar {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  width: var(--cs-sidebar, 220px);
  background: linear-gradient(180deg, #0C1929 0%, #132238 60%, #0F1D2E 100%);
  transition: width 0.3s ease-in-out;
  z-index: 100;
  display: flex;
  flex-direction: column;

  &.is-collapsed {
    width: var(--cs-sidebar-collapsed, 64px);
  }
}

.sidebar-logo {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding: 0 16px;
  position: relative;

  // Gradient glow line instead of solid border
  &::after {
    content: '';
    position: absolute;
    bottom: 0;
    left: 16px;
    right: 16px;
    height: 1px;
    background: linear-gradient(90deg, transparent, rgba(43, 126, 193, 0.4), transparent);
  }

  .logo-icon {
    flex-shrink: 0;
    width: 32px;
    height: 32px;
    object-fit: contain;
    border-radius: 6px;
    background: #fff;
  }

  .logo-copy {
    margin-left: 12px;
    min-width: 0;
    display: flex;
    flex-direction: column;
    justify-content: center;
    line-height: 1.1;
    max-width: 144px;
    overflow: hidden;
    opacity: 1;
    visibility: visible;
    transition: opacity 140ms ease-out, max-width 0.3s ease-in-out,
      margin-left 0.3s ease-in-out, visibility 0s;
    transition-behavior: allow-discrete;
  }

  .logo-title {
    color: #fff;
    font-size: 15px;
    font-weight: 600;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .logo-subtitle {
    margin-top: 3px;
    color: rgba(255, 255, 255, 0.62);
    font-size: 11px;
    font-weight: 500;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

.sidebar-menu-wrap {
  flex: 1;
  padding: 8px;
}

// P2「常用」项：右侧移除按钮悬停才出现
:deep(.pinned-menu-item) {
  .pinned-remove {
    margin-left: auto;
    padding: 0 4px;
    border: 0;
    background: transparent;
    color: rgba(255, 255, 255, 0.5);
    font-size: 16px;
    line-height: 1;
    cursor: pointer;
    opacity: 0;
    transition: opacity var(--cs-duration-fast, 140ms);
  }
  &:hover .pinned-remove,
  .pinned-remove:focus-visible { opacity: 1; color: #fff; }
}

// P2 原型侧栏分区标题（「全部功能」「设置」）
.menu-section-title {
  padding: 14px 12px 6px;
  font-size: var(--cs-font-small, 12px);
  font-weight: 600;
  letter-spacing: 0.04em;
  color: rgba(255, 255, 255, 0.42);
  white-space: nowrap;
  overflow: hidden;
  user-select: none;
  transition: opacity 140ms ease-out;

  &:first-child { padding-top: 4px; }

  &.is-collapsed {
    opacity: 0;
    height: 8px;
    padding: 0;
  }
}

// Keep the icon at the same x position in both layouts. Scope label rules to
// root items so teleported submenus and their tooltip text remain readable.
:deep(.sidebar-menu) {
  --el-menu-base-level-padding: 12px;
  width: 100%;
}

:deep(.sidebar-menu > .el-menu-item .menu-title-copy),
:deep(.sidebar-menu > .el-sub-menu > .el-sub-menu__title > .menu-title-copy),
:deep(.sidebar-menu > .el-menu-item .menu-task-badge),
:deep(.sidebar-menu > .el-sub-menu > .el-sub-menu__title > .menu-task-badge) {
  height: auto;
  width: auto;
  max-width: 160px;
  visibility: visible;
  opacity: 1;
  overflow: hidden;
  white-space: nowrap;
  transition: opacity 140ms ease-out, max-width 0.3s ease-in-out, visibility 0s;
  transition-behavior: allow-discrete;
}

.is-collapsed {
  .logo-copy {
    max-width: 0;
    margin-left: 0;
    opacity: 0;
    visibility: hidden;
    transition-delay: 0s, 0s, 0s, 140ms;
  }

  :deep(.sidebar-menu > .el-menu-item .menu-title-copy),
  :deep(.sidebar-menu > .el-sub-menu > .el-sub-menu__title > .menu-title-copy),
  :deep(.sidebar-menu > .el-menu-item .menu-task-badge),
  :deep(.sidebar-menu > .el-sub-menu > .el-sub-menu__title > .menu-task-badge) {
    max-width: 0;
    opacity: 0;
    visibility: hidden;
    transition-delay: 0s, 0s, 140ms;
  }
}

@starting-style {
  :deep(.sidebar-menu > .el-menu-item .menu-title-copy),
  :deep(.sidebar-menu > .el-menu-item .menu-task-badge),
  :deep(.sidebar-menu > .el-sub-menu > .el-sub-menu__title > .menu-title-copy),
  :deep(.sidebar-menu > .el-sub-menu > .el-sub-menu__title > .menu-task-badge) { opacity: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .app-sidebar,
  .sidebar-logo .logo-copy,
  :deep(.sidebar-menu > .el-menu-item .menu-title-copy),
  :deep(.sidebar-menu > .el-sub-menu > .el-sub-menu__title > .menu-title-copy),
  :deep(.sidebar-menu > .el-menu-item .menu-task-badge),
  :deep(.sidebar-menu > .el-sub-menu > .el-sub-menu__title > .menu-task-badge) {
    transition: none;
  }
}

:deep(.el-menu) {
  border-right: none;

  .el-menu-item,
  .el-sub-menu__title {
    margin: 2px 0;
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.2s ease;

    &:hover {
      background-color: rgba(255, 255, 255, 0.06) !important;
      color: #fff !important;
    }

    // Icon micro-interaction: subtle scale + 1px lift on hover. Plain CSS
    // transition (not GSAP) — this is a pure :hover state with no JS-driven
    // sequencing/stagger/cleanup logic needed, so CSS is cheaper and simpler
    // than spinning up a GSAP hover handler for every one of ~20 menu items.
    // GSAP is reserved for useCountUp/vReveal where it earns its keep
    // (once-only mount tweens, formatted-value tweening). Scale/duration
    // values intentionally mirror ICON_HOVER_SCALE/ICON_HOVER_DURATION_S in
    // src/utils/motion/constants.ts so the sidebar reads as the same motion
    // vocabulary as the rest of the app.
    .el-icon {
      transition: transform 0.15s ease-out;
    }

    &:hover .el-icon {
      transform: scale(1.12) translateY(-1px);
    }
  }

  .el-menu-item.is-active {
    background-color: rgba(27, 101, 168, 0.25) !important;
    position: relative;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 25%;
      height: 50%;
      width: 3px;
      border-radius: 0 3px 3px 0;
      background: var(--cs-primary, #1b65a8);
    }
  }

  // Nested sub-menu items
  .el-sub-menu .el-menu-item {
    margin: 1px 4px;
    border-radius: 6px;
    padding-left: 48px !important;
  }

  // Reduced motion: no icon transform at all, even instantly on hover —
  // background/color hover feedback (above) still works, just no motion.
  @media (prefers-reduced-motion: reduce) {
    .el-menu-item .el-icon,
    .el-sub-menu__title .el-icon {
      transition: none;
    }

    .el-menu-item:hover .el-icon,
    .el-sub-menu__title:hover .el-icon {
      transform: none;
    }
  }
}

.sidebar-overlay {
  display: none;
}

@media (max-width: 768px) {
  .app-sidebar {
    transform: translateX(-100%);
    z-index: 1001;
    width: 260px;

    &.mobile-open {
      transform: translateX(0);
    }
  }

  .sidebar-overlay {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
    z-index: 1000;
  }
}

:global(.app-sidebar-menu-popper) {
  border: 1px solid rgba(255, 255, 255, 0.08) !important;
  border-radius: 8px !important;
  background: #102033 !important;
  box-shadow: 0 16px 40px rgba(3, 10, 20, 0.35) !important;
}

:global(.app-sidebar-menu-popper::before) {
  content: '';
  position: absolute;
  top: -8px;
  bottom: -8px;
  left: -24px;
  width: 32px;
  pointer-events: auto;
}

</style>
