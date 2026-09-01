/**
 * 路由守卫
 * 注意：不在顶层导入 store，避免在 pinia 初始化前访问
 */
import type { Router } from 'vue-router';
import type { ModuleName } from '@/store/modules/permission';
import { isDemoFactoryId, resolveLogisticsDemoRoute } from '@/views/demo/demoRoute';

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
    // 盘点审批详情是个人 OA 的下游只读入口。finance_manager 需要从
    // INVENTORY_ADJUSTMENT 待办查看盘点依据，但不能因此开放其它仓储页面。
    '/warehouse/stocktakes',
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

/**
 * 角色路径白名单判定 —— 「这个角色的 URL 前缀白名单允不允许它去 `path`」。
 * 不在 {@link ROLE_PATH_WHITELIST} 里的角色不受这道闸约束, 返回 `true`。
 *
 * 🔴 **导出的理由**: 让跨模块入口按钮能按「点下去会不会 403」决定显不显示。
 * 缺陷 9 (2026-09-01, LIUSHANMEN 生产走查): 采购订单详情上「查看财务详情」和
 * 「前往仓储收货任务」两个按钮对采购主管是死链 —— 点了整页跳 `/403`, 上下文全丢。
 *
 * ⚠️ 调用方**必须与 beforeEach 用同一份判定**。⛔ 在组件里只写一句
 * `canAccess('finance')` 是不够的: 那只覆盖了下面的模块闸, 漏掉本白名单这一道。
 * 实例: `finance_manager` 的白名单有 `/warehouse/stocktakes` 但**没有**
 * `/warehouse/materials` ⇒ 即使模块权限通, 路径闸照样 403。
 * 两道闸是**串联**的, 隐藏条件必须同时满足两道 (形态 D: 同一个判定有两份就会漂)。
 */
export function isPathAllowedForRole(role: string, path: string): boolean {
  const roleWhitelist = ROLE_PATH_WHITELIST[role];
  if (!roleWhitelist) return true;
  return roleWhitelist.some(prefix => path === prefix || path.startsWith(prefix + '/'));
}

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

    // 🔴 2026-08-25 安全修复: `meta.mockDemo` 曾经在这里 early-return, 位置在**所有**
    // 权限闸之前, 于是 12 条 scheduling 路由的 ROLE_PATH_WHITELIST / module /
    // modulesAll / hideForFactoryTypes / restaurantRoles / roles 全部成了死代码 ——
    // 任何已登录用户手输 URL 就能进, 而且它是**静态 route meta**, 没有任何 env /
    // query / localStorage 开关, 生产构建里恒定生效。
    //
    // 原注释给的理由是「演示页为纯前端 mock、无真实数据」—— 这条**已经过期**:
    // 11 个页面全部 onMounted 打真后端 (@/api/scheduling 与 @/api/logistics),
    // 其中 useLogisticsScheduling.ts 明确写着「API 失败不静默回退 mock」。
    //
    // ⇒ 不是删掉这个演示开关, 而是把它收窄到它**真正需要**的那一项:
    //    注释里点名的问题是 `canAccess('scheduling')` 把演示看板误判成无权限,
    //    那就只豁免 module 那一道闸, 且只对演示租户 (DEMO_* factoryId) 豁免。
    //    其余每一道闸对所有人照跑, 包括演示租户。
    const demoModuleExempt = !!to.meta.mockDemo && isDemoFactoryId(authStore.factoryId);

    // 同步角色、工厂ID、工厂类型到权限 store
    const permissionStore = usePermissionStore();
    permissionStore.setRole(
      authStore.currentRole,
      authStore.factoryId,
      authStore.factoryType,
      authStore.user?.id,
      { skipDbLoad: demoModuleExempt },
    );
    if (!demoModuleExempt) {
      // 非演示租户进这些页面时必须有真实权限数据 —— 之前跟着 mockDemo 一起跳过,
      // 等于拿 hardcoded 矩阵去判一条本来就没在判的路。loadFromDb 内部吞异常,
      // 失败只会把 dbPermissions 置空回落 hardcoded, 不会让导航挂掉。
      await permissionStore.loadFromDb();
    }

    // 检查是否是 Mobile 专属角色 (一线员工: 操作员、质检员、仓库工人)
    // 这些角色只能使用移动端 App，不能登录 Web 管理后台
    if (MOBILE_ONLY_ROLES.includes(authStore.currentRole)) {
      // 先把角色取出来再清登录态 —— clearAuth() 之后 currentRole 恒为
      // 'unactivated' (auth.ts: `if (!u) return 'unactivated'`), 而
      // mobile-only.vue 的「移动端支持的功能」整张清单是按 query.role 分支的,
      // 拿不到真实角色就只剩两条无条件项, 等于告诉一线工人「请用 App」却漏掉
      // 他的本职工作。守卫见 guards.mobileOnlyRole.spec.ts。
      const blockedRole = authStore.currentRole;
      // 清除登录状态
      authStore.clearAuth();
      // 跳转到 Mobile 专属提示页面
      next({ path: '/mobile-only', query: { role: blockedRole } });
      return;
    }

    // 检查角色路由白名单 (判定抽在 isPathAllowedForRole, 与跨模块入口按钮共用同一份)
    if (!isPathAllowedForRole(authStore.currentRole, to.path)) {
      next('/403');
      return;
    }

    // 检查模块权限
    // demoModuleExempt: 演示租户进演示页时豁免这一道 (且**只有**这一道) —— 见上方说明。
    const module = to.meta.module as ModuleName | undefined;
    const modulesAny = to.meta.modulesAny as ModuleName[] | undefined;
    if (module && !modulesAny?.length && !demoModuleExempt) {
      if (!permissionStore.canAccess(module)) {
        next('/403');
        return;
      }
    }

    // modulesAny —— 页面由多个职责域共同使用，但只需拥有其中之一。
    // 例如盘点由仓储执行、财务审批；两者都必须能查看同一张盘点依据。
    // 保留 meta.module 作为菜单/工作区的权威分类，导航守卫则按 any-of 判定。
    if (modulesAny?.length && !demoModuleExempt
        && !modulesAny.some((m) => permissionStore.canAccess(m))) {
      next('/403');
      return;
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
