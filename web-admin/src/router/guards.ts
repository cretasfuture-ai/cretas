/**
 * 路由守卫
 * 注意：不在顶层导入 store，避免在 pinia 初始化前访问
 */
import type { Router } from 'vue-router';
import type { ModuleName } from '@/store/modules/permission';
import { resolveLogisticsDemoRoute } from '@/views/demo/demoRoute';

// 白名单路由 - 不需要登录
const whiteList = ['/login', '/403', '/404', '/mobile-only', '/demo'];

// Mobile 专属角色 - 不允许登录 Web 端
// 参考原型文档: docs/prd/prototype/index.html - 14角色权限矩阵
const MOBILE_ONLY_ROLES = [
  'operator',
  'quality_inspector',
  'warehouse_worker'
];

// 角色路由白名单 — 限制特定角色只能访问指定路径前缀
// 防止通过 URL 直接访问超出菜单范围的路由
const ROLE_PATH_WHITELIST: Record<string, string[]> = {
  finance_manager: [
    '/dashboard',
    // Personal OA is the only write surface for finance approval actions.
    // Keep it inside the finance-manager path allowlist; task visibility and
    // action authority are still enforced by the workflow API per factory,
    // current node, role and explicit assignee.
    '/workflow',
    '/finance',
    // 销售/采购审批统一在个人 OA；财务角色不再通过旧销售财审索引跨模块浏览。
    // 🔴 2026-08-22: 补上工作台。财务有 dashboard/finance 模块 ⇒ 菜单显示「工作台」,
    //   但路径不在本白名单里 ⇒ 点进去 403。owner 没点到, 是扫描器 B 类查出来的。
    '/workdesk',
    '/smart-bi/dashboard',
    '/smart-bi/analysis-hub',   // WS4: 经营分析合并模块 (财务/销售/趋势/KPI) — finance_manager 主入口
    '/smart-bi/finance',
    '/smart-bi/financial-dashboard',
    '/smart-bi/sales',
    '/smart-bi/query',
    '/smart-bi/query-templates',
    '/smart-bi/analysis',
    // 餐饮侧的财务口径页。2026-07-31 餐饮拆四部门后, finance_manager 在权限矩阵
    // 里有 `restaurantFinance: 'r'` —— 但那是**模块**闸, 本表是**路径**闸, 两者
    // 串联(见下方 beforeEach: 先过路径白名单再查模块权限)。
    // ⛔ 删掉这两行会让 finance_manager 直接吃 403, 即使模块权限是通的。
    //    要改访问范围, 两处都得改。
    '/restaurant/supplier-reconciliation',
    '/restaurant/cost-attribution',
    '/403',
    '/404',
  ],
};

export function setupRouterGuards(router: Router) {
  // 前置守卫
  router.beforeEach(async (to, _from, next) => {
    // 设置页面标题
    document.title = to.meta.title ? `${to.meta.title} - 白垩纪AI Agent` : '白垩纪AI Agent';

    // R40 BUG-7 fix: ElMessage.error patched to sticky (Bug #312, 2026-04-18)
    // means toasts persist across navigation. On route change, dismiss any
    // visible error/notification toasts so they don't accumulate (R40 audit
    // saw 4+ stale toasts visible simultaneously after a few clicks).
    if (typeof document !== 'undefined') {
      document.querySelectorAll('.el-message, .el-notification').forEach((el) => el.remove());
    }

    // 白名单路由直接放行
    if (whiteList.includes(to.path)) {
      next();
      return;
    }

    // 路由 meta 标记不需要认证的也直接放行 (如分享页)
    if (to.meta.requiresAuth === false) {
      next();
      return;
    }

    // 动态导入 store，确保 pinia 已初始化
    const { useAuthStore } = await import('@/store/modules/auth');
    const { usePermissionStore } = await import('@/store/modules/permission');

    // 检查是否登录
    const authStore = useAuthStore();

    if (!authStore.isAuthenticated) {
      // 未登录，跳转登录页（避免重复跳转）
      if (to.path !== '/login') {
        next({ path: '/login', query: { redirect: to.fullPath } });
      } else {
        next();
      }
      return;
    }

    const logisticsLanding = resolveLogisticsDemoRoute(to.path, authStore.factoryType, authStore.factoryId);
    if (logisticsLanding) {
      next(logisticsLanding);
      return;
    }

    // 同步角色、工厂ID、工厂类型到权限 store
    const permissionStore = usePermissionStore();
    permissionStore.setRole(
      authStore.currentRole,
      authStore.factoryId,
      authStore.factoryType,
      authStore.user?.id,
      { skipDbLoad: !!to.meta.mockDemo },
    );
    if (!to.meta.mockDemo) {
      await permissionStore.loadFromDb();
    }

    // 检查是否是 Mobile 专属角色 (一线员工: 操作员、质检员、仓库工人)
    // 这些角色只能使用移动端 App，不能登录 Web 管理后台
    if (MOBILE_ONLY_ROLES.includes(authStore.currentRole)) {
      // 清除登录状态
      authStore.clearAuth();
      // 跳转到 Mobile 专属提示页面
      next({ path: '/mobile-only', query: { role: authStore.currentRole } });
      return;
    }

    // mockDemo 路由 = 公开演示页：已登录即放行，跳过模块/工厂类型/角色权限门。
    // 演示页为纯前端 mock、无真实数据；scheduling 模块对演示租户默认 '-'，
    // 否则 canAccess('scheduling') 会把演示看板误判成无权限 → /403。
    if (to.meta.mockDemo) {
      next();
      return;
    }

    // 检查角色路由白名单
    const roleWhitelist = ROLE_PATH_WHITELIST[authStore.currentRole];
    if (roleWhitelist) {
      const pathAllowed = roleWhitelist.some(prefix => to.path === prefix || to.path.startsWith(prefix + '/'));
      if (!pathAllowed) {
        next('/403');
        return;
      }
    }

    // 检查模块权限
    const module = to.meta.module as ModuleName | undefined;
    if (module) {
      if (!permissionStore.canAccess(module)) {
        next('/403');
        return;
      }
    }

    // 🔴 2026-08-22: modulesAll —— 「这一页需要**不止一个**模块」。
    //
    // meta.module 只能写一个值, 而有些页面**天然跨两个模块**:
    //   · 财务的「SKU 毛利率分析」  归 finance, 但它读 /processing/batches(要 production)
    //   · 研发的「中报价汇算」      归 rd,      同样读 /processing/batches
    // 后端那道 @RequireModule 是**与**关系(缺任一就 403), 而路由只判了其中一个
    // ⇒ 只有 finance / 只有 rd 的角色(cashier、sales_manager)看得见入口, 点进去必 403。
    //
    // ⛔ 不能靠改 meta.module 解决: 改成 production 就把页面暴露给没有 finance 的生产角色,
    //    那只是把漂移挪个方向。⇒ 需要能表达「两个都要」。
    // ⚠️ 这是**补齐表达能力**, 不是放宽或收紧任何一个角色的权限。
    const modulesAll = to.meta.modulesAll as ModuleName[] | undefined;
    if (modulesAll?.length && !modulesAll.every((m) => permissionStore.canAccess(m))) {
      next('/403');
      return;
    }

    // Apr 24 UX: 工厂类型黑名单 (sidebar hideForFactoryTypes 的 route-level 对应)
    // 防止餐饮租户手输 URL 绕过侧边栏访问 manufacturing-only 页面
    const hideForTypes = to.meta.hideForFactoryTypes as string[] | undefined;
    if (hideForTypes && authStore.factoryType && hideForTypes.includes(authStore.factoryType)) {
      next('/403');
      return;
    }

    // 餐饮角色专属白名单：同一路由在工厂端继续按 module/roles 原规则运行，
    // 只在 RESTAURANT 租户收口老板、店长、采购、厨师长的职责边界。
    const restaurantRoles = to.meta.restaurantRoles as string[] | undefined;
    if (
      authStore.factoryType === 'RESTAURANT'
      && restaurantRoles?.length
      && !restaurantRoles.includes(authStore.currentRole)
    ) {
      next('/403');
      return;
    }

    // 检查角色权限（如果路由指定了 roles）
    const allowedRoles = to.meta.roles as string[] | undefined;
    if (allowedRoles && allowedRoles.length > 0) {
      if (!allowedRoles.includes(authStore.currentRole)) {
        next('/403');
        return;
      }
    }

    next();
  });
}
