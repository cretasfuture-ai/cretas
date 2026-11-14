import type { ModuleName } from '@/store/modules/permission';
// 菜单配置
export interface MenuItem {
  path: string;
  title: string;
  icon: string;
  module: ModuleName;
  roles?: string[];
  hideForFactoryTypes?: string[];
  /** Additive allowlist used only by the factory workspace navigation group. */
  allowedFactoryTypes?: string[];
  /** Require a concrete non-platform factory context before showing this item. */
  requiresFactoryContext?: boolean;
  /** 报废审批资格 (disposal:approve): 菜单按后端 approval-permission 判, 不按岗位名。 */
  requiresDisposalApproval?: boolean;
  children?: MenuItem[];
  groupLabel?: string;
  /** Backend-authoritative feature gate for platform-only assembly surfaces. */
  assemblyFeature?: boolean;
  /** 真实待办计数 key。接口失败时隐藏，不允许用 0 伪装成功。 */
  badgeKey?: import('@/store/modules/taskBadges').TaskBadgeKey;
  /**
   * P2 原型侧栏分区（只在 groupedMenuConfig 的顶层项上出现）：
   * top = 今天/待办/发现的问题这类独立入口；all = 「全部功能」；settings = 底部「设置」。
   */
  section?: MenuSection;
  /**
   * P2 纯展示分组（概览 / 数据与分析 / 主数据）：不是路由、本身不带任何权限判据，
   * 子项全部不可见时整组隐藏。⛔ 不要给 container 加 module/roles —— 判据只在叶子与 guardAncestors 上。
   */
  container?: boolean;
  /**
   * P2 从原分组搬出来的叶子：原来那几层父分组（去掉 children）。侧栏判可见时这些父分组也必须可见，
   * 与搬家前「父组被隐藏 ⇒ 子项跟着隐藏」逐项等价（disabledMenuPaths 写父路径、menuVisible、模块开关都算）。
   */
  guardAncestors?: MenuItem[];
}

export type MenuSection = 'top' | 'all' | 'settings';
export const MENU_SECTION_TITLES: Record<MenuSection, string> = {
  top: '',
  all: '全部功能',
  settings: '设置',
};

const PLATFORM_ADMIN_ONLY = ['platform_admin'];
const FACTORY_ASSEMBLY_ROLES = ['platform_admin'];

// 财务主管专用菜单 - 简化版
// WS4: 财务看板/财务分析/销售分析 合并为单一「经营分析」hub (财务/销售/趋势/KPI tab)。
export const financeManagerMenu: MenuItem[] = [
  { path: '/analytics/ai-reports', title: 'AI经营报告', icon: 'Document', module: 'analytics', hideForFactoryTypes: ['RESTAURANT', 'LOGISTICS'] },
  {
    path: '/workflow', title: '个人 OA', icon: 'Checked', module: 'dashboard',
    children: [
      { path: '/workflow/pending', title: '待我审批', icon: '', module: 'dashboard' },
      { path: '/workflow/my-created', title: '我发起的', icon: '', module: 'dashboard' },
      { path: '/workflow/acted', title: '已处理', icon: '', module: 'dashboard' },
      { path: '/workflow/copied', title: '抄送我的', icon: '', module: 'dashboard' },
    ],
  },
  // 🔴 2026-08-22 owner: 「数据分析全部隐藏, 工厂暂时用不着」
  // ⇒ 用 hideForFactoryTypes 对**工厂租户**隐藏; 餐饮租户不受影响(那是另一个体系)。
  // ⚠️ 这是**菜单级隐藏**, ⛔ 不等于关权限 —— analytics 模块权限未动,
  //    手输 URL 仍进得去。要彻底关就再砍模块 + 同步 guards.ts 的路径白名单
  //    (两处都得改 —— 2026-08-22 只改一处导致财务主入口 403, 已吃过一次)。
  // ⇒ 选「隐藏」而不是「砍权限」正是因为 owner 说的是**暂时**, 一行可逆。
  { path: '/smart-bi/dashboard', title: '经营驾驶舱', icon: 'Odometer', module: 'analytics', hideForFactoryTypes: ['FACTORY'] },
  { path: '/smart-bi/analysis-hub', title: '经营分析', icon: 'TrendCharts', module: 'analytics', hideForFactoryTypes: ['FACTORY'] },
  { path: '/smart-bi/query', title: 'AI问答', icon: 'ChatDotRound', module: 'analytics', hideForFactoryTypes: ['FACTORY'] },
  { path: '/smart-bi/query-templates', title: '查询模板管理', icon: 'Tickets', module: 'analytics', hideForFactoryTypes: ['FACTORY'] },
  { path: '/smart-bi/analysis', title: '智能数据分析', icon: 'DataAnalysis', module: 'analytics', hideForFactoryTypes: ['FACTORY'] },
  // 🔴 2026-08-22: 加 hideForFactoryTypes。这两个是**餐饮口径**页(路由 module 是
  //   restaurantFinance, 路径在 /restaurant/ 下), 却出现在工厂租户的财务菜单里
  //   ⇒ owner 实测「答案都没有」—— 不是 403, 是工厂租户上**根本没有这类数据**。
  //   ▎「打不开」和「打开了但是空的」是两件事, 修法完全不同。
  // 工厂财务只保留本职工作台。销售/采购审批统一在个人 OA，财务菜单不再重复挂审批索引。
  { path: '/finance/ar-ap', title: '应收应付', icon: 'Wallet', module: 'finance', hideForFactoryTypes: ['RESTAURANT'] },
  { path: '/finance/adjustments', title: '调整审批', icon: 'Checked', module: 'finance', hideForFactoryTypes: ['RESTAURANT'] },
  { path: '/finance/disposal-approvals', title: '报废审批', icon: 'DocumentChecked', module: 'finance', requiresDisposalApproval: true, hideForFactoryTypes: ['RESTAURANT'] },
  { path: '/finance/invoices?status=REQUESTED', title: '销售开票', icon: 'Tickets', module: 'finance', hideForFactoryTypes: ['RESTAURANT'] },
  { path: '/finance/purchase-invoices', title: '采购收票', icon: 'DocumentChecked', module: 'finance', hideForFactoryTypes: ['RESTAURANT'] },
  { path: '/finance/payments', title: '收款管理', icon: 'Money', module: 'finance', hideForFactoryTypes: ['RESTAURANT'] },
  { path: '/finance/accounting-period', title: '会计期间', icon: 'Calendar', module: 'finance', hideForFactoryTypes: ['RESTAURANT'] },
];

const rawMenuConfig: MenuItem[] = [
  { path: '/analytics/ai-reports', title: 'AI经营报告', icon: 'Document', module: 'analytics', roles: ['factory_super_admin', 'finance_manager'], hideForFactoryTypes: ['RESTAURANT', 'LOGISTICS'] },
  { path: '/dashboard', title: '首页', icon: 'House', module: 'dashboard', hideForFactoryTypes: ['LOGISTICS'] },
  {
    path: '/workspaces',
    title: '工厂管理工作区',
    icon: 'Odometer',
    module: 'dashboard',
    roles: ['factory_super_admin', 'permission_admin'],
    hideForFactoryTypes: ['RESTAURANT', 'LOGISTICS'],
    allowedFactoryTypes: ['FACTORY', 'CENTRAL_KITCHEN'],
    requiresFactoryContext: true,
    children: [
      { path: '/workspaces/overview', title: '经营总览', icon: '', module: 'dashboard', roles: ['factory_super_admin', 'permission_admin'], hideForFactoryTypes: ['RESTAURANT', 'LOGISTICS'], allowedFactoryTypes: ['FACTORY', 'CENTRAL_KITCHEN'], requiresFactoryContext: true },
      { path: '/workspaces/exceptions', title: '异常中心', icon: '', module: 'dashboard', roles: ['factory_super_admin', 'permission_admin'], hideForFactoryTypes: ['RESTAURANT', 'LOGISTICS'], allowedFactoryTypes: ['FACTORY', 'CENTRAL_KITCHEN'], requiresFactoryContext: true },
      { path: '/workspaces/plans-approvals', title: '方案与审批', icon: '', module: 'dashboard', roles: ['factory_super_admin', 'permission_admin'], hideForFactoryTypes: ['RESTAURANT', 'LOGISTICS'], allowedFactoryTypes: ['FACTORY', 'CENTRAL_KITCHEN'], requiresFactoryContext: true },
      { path: '/workspaces/trace-review', title: '追溯与复盘', icon: '', module: 'dashboard', roles: ['factory_super_admin', 'permission_admin'], hideForFactoryTypes: ['RESTAURANT', 'LOGISTICS'], allowedFactoryTypes: ['FACTORY', 'CENTRAL_KITCHEN'], requiresFactoryContext: true },
    ],
  },
  // 🔴 2026-08-07: 这一项此前**只有路由没有菜单入口** —— `router/index.ts` 里
  // `dashboard/ai-value` 声明齐全(title/icon/module 都有)，但 menuConfig 里零命中，
  // 于是用户**点不到它**。小蓝店长第 ③ 块「AI 价值汇总」的页面一直在，却够不着。
  //
  // 判据(本轮反复用到的那条): **「代码在那儿」不等于「用户到得了」**。
  // 判可达性要从菜单/路由两侧一起看 —— 只看路由会以为它通了。
  // 🔴 2026-08-21: 补 roles 白名单。原来只声明 module:'dashboard', 而**几乎每个角色都有
  // dashboard:read** ⇒ 人人都看得见这个入口, 点进去却 403「权限不足 · 数据加载失败」
  // (owner 用销售主管实测)。
  // ▎菜单按「有没有 dashboard 权限」判可见, 页面按「有没有 AI 数据权限」判可用 ——
  //   两个判据不是同一个, 中间的差就是「看得见点不开」。
  // ⛔ 不是给销售加 AI 权限: owner 明确「数据分析应该是管理员或者老板这样子阶级的」。
  {
    path: '/dashboard/ai-value', title: 'AI 工作台', icon: 'MagicStick', module: 'dashboard',
    roles: ['factory_super_admin', 'platform_admin', 'permission_admin'],
  },
  {
    path: '/workdesk',
    title: '工作台',
    icon: 'Odometer',
    module: 'dashboard',
    children: [
      { path: '/workdesk/sales-owner', title: '销售老板工作台', icon: '', module: 'sales', groupLabel: '经营管理' },
      { path: '/workdesk/finance-manager', title: '财务主管工作台', icon: '', module: 'finance' },
      { path: '/workdesk/quality-manager', title: '质量主管工作台', icon: '', module: 'quality' },
      { path: '/workdesk/production-manager', title: '生产经理工作台', icon: '', module: 'production' },
      { path: '/workdesk/warehouse-keeper', title: '仓管员工作台', icon: '', module: 'warehouse', groupLabel: '一线执行' },
      { path: '/workdesk/purchaser', title: '采购员工作台', icon: '', module: 'procurement' },
      { path: '/workdesk/quality-chief', title: '质检主管工作台', icon: '', module: 'quality' },
    ],
  },
  {
    // Personal OA is available to every authenticated role through dashboard access.
    // Backend workflow role/user/factory rules decide which tasks are visible/actionable.
    path: '/workflow', title: '个人 OA', icon: 'Checked', module: 'dashboard',
    children: [
      { path: '/workflow/pending', title: '待我审批', icon: '', module: 'dashboard' },
      { path: '/workflow/my-created', title: '我发起的', icon: '', module: 'dashboard' },
      { path: '/workflow/acted', title: '已处理', icon: '', module: 'dashboard' },
      { path: '/workflow/copied', title: '抄送我的', icon: '', module: 'dashboard' },
    ],
  },
  // 研发管理 (2026-09-01 owner 拍板 A2: 重新独立成组)
  //
  // ⚠️ 这是**反转**当年那条「UX P2-5: 合并"研发管理" 1-item 组, 减少顶级菜单」。
  //    反转的理由是**当年那条理由已经不成立**: 合并时它确实只有 1 条, 但
  //    /rd/converted(已转样品库)一直没有任何入口 —— 菜单里没有, 全仓也 grep 不到
  //    一处跳转, 用户根本走不到。露出它之后这一组是 2 条, 不再是「1-item 组」。
  //    (报价详情/三价对比/中报价是从样品页跳进去的 detail 页, 本来就不该进菜单 ——
  //     samples/list.vue 里有跳转, 它们是可达的, ⛔ 别把它们也当成孤儿页补进来。)
  //
  // 🔴 module 一律 'rd' 不是 'production' (2026-08-22 的结论, 随条目一起搬过来):
  //    声明 production ⇒ 几乎所有生产侧角色都看得见, 但页面调的 /rd/* 端点要 rd
  //    (只有 12 个角色有) ⇒ 差集那 9 个角色点进去必 403。
  //    ▎菜单按「有没有 production」判可见, 页面按「有没有 rd」判可用 ——
  //      中间的差就是「看得见点不开」。⛔ 不是给那 9 个角色开 rd 权限。
  //
  // ⚠️ hideForFactoryTypes 必须带上: 原来 /rd/samples 是靠**生产组**的
  //    hideForFactoryTypes:['RESTAURANT'] 对餐饮租户隐藏的。搬出来不带这个标记
  //    就等于顺手把研发暴露给餐饮租户 —— 这里保持原可见性不变。
  {
    path: '/rd', title: '研发管理', icon: 'Aim', module: 'rd',
    children: [
      { path: '/rd/samples', title: '研发样品', icon: '', module: 'rd' },
      { path: '/rd/converted', title: '已转样品库', icon: '', module: 'rd' },
    ],
  },

  {
    // P1-5: restaurants 默认不见 "生产管理" (BOM/批次是 manufacturing 语言,
    // 餐饮用配方/备餐在 /restaurant/recipes)
    // UX P2-5 曾把"研发管理"并进来(理由: 它只有 1 条, 减少顶级菜单);
    // 2026-09-01 owner 拍板 A2 又拆回独立组 —— 理由见 /rd 组上的注释。
    path: '/production', title: '生产管理', icon: 'Operation', module: 'production',
    children: [
      { path: '/production/plans', title: '生产计划', icon: '', module: 'production', groupLabel: '生产过程' },
      { path: '/production/shortage-cases', title: '缺料异常', icon: '', module: 'production' },
      { path: '/production/delivery-risks', title: '交付风险', icon: '', module: 'production' },
      // 🔴 2026-08-26: B8 生产进度数字大屏 —— 客户 2026-04 原话点名要的东西
      //   (ProductionProgressDashboardController:19 「可以做一个数字打屏嘛…看到当天所有商品
      //   工序生产的一个进度」), 2026-04-08 就做完了(后端 203 行零硬编码 + 162 行单测,
      //   前端深色大屏版式 + 30 秒自动刷新), 而 menuConfig 里一直**零命中** ⇒ 用户点不到。
      //   ⚠️ routedPagesAreReachable 曾把它豁免掉, 理由写的是「从首页卡片进入」——
      //      实测全 web-admin 零处引用, 那个理由是假的; 豁免已同步撤掉。
      //
      // ⛔ module 必须是 'production', **不能**是 'analytics':
      //   · 后端 @RequireModule("production_plan") → ProductionModuleRegistry:20 映射
      //     permissionModule = 'production' → UserModuleAccessServiceImpl.roleDefaultAllows
      //     查 production:read / :write / :read_write。
      //   · workshop_supervisor 的 analytics 是 '-'(permission.ts:311), production 是 'r'。
      //   ⇒ 挂 analytics 车间主管一辈子打不开 —— 而他正是这块墙的受众。
      //   ▎菜单闸判的模块与后端闸判的模块在这里是**同一个**, 这是刻意对齐, 别改。
      //
      // ⛔ 不加 roles 白名单。/dashboard/ai-value 那条加 roles 是因为它 module:'dashboard'
      //   而 dashboard:read 人人都有(菜单宽、页面窄 ⇒ 看得见点不开); 这里 module 本身就是
      //   后端要的那个, 再叠 roles 只会把车间主管挡在外面 —— 那是把问题反着修一遍。
      { path: '/dashboard/production-progress', title: '生产进度大屏', icon: '', module: 'production' },
      { path: '/production/batches', title: '生产批次', icon: '', module: 'production' },
      // 溯源查询 (2026-09-15) —— 一条批次从源头个体到出货的完整链路, 投屏讲「一条链路」
      // 时不用举手机。⛔ 不加 roles 白名单: 页面只读, 后端闸是 module 级 (production),
      // 与本菜单项判的模块是同一个 (同上面 /dashboard/production-progress 那段的理由)。
      { path: '/production/traceability', title: '溯源查询', icon: '', module: 'production' },
      // 标准出成率 (2026-09-05) —— 备料计算的基准线, 唯一的人工写入口。
      // ⛔ 不加 roles 白名单: 与上面 /dashboard/production-progress 同一个理由 ——
      //   页面本身对无权者是**可读**的 (只是改按钮置灰 + 明示找谁改), 菜单挡住反而
      //   让人看不到当前标准填没填。写权限的真门在后端 bom:yield_standard:write。
      { path: '/production/yield-standard', title: '标准出成率', icon: '', module: 'production' },
      // 2026-09-02 LIUSHANMEN 生产管理菜单收拢 (owner 拍板): 19 条合并为 ~9 条。
      // 生产看板 hub = 未完成计划队列 + 备货看板 + 交货预警 (el-tabs 承载, 组件
      // 原样未改动)。3 个旧路径 (/production/pending-queue、/production/restock-board、
      // /production/delivery-warnings) 仍可直接访问 (router redirect 保书签，见
      // router/index.ts + views/production/kanban/index.vue)。
      { path: '/production/kanban', title: '生产看板', icon: '', module: 'production' },
      // 🔴 2026-09-01 生产实测: lsm008(六膳门质量经理) 打开本页看到「待审批 0」+ 暂无数据,
      //   而同一 URL 用 lsm015(生产经理) 是「待审批 26」。那个 0 不是空列表, 是挡在
      //   26 条真实待办前面的伪造读数(GET 403 被渲染成了一个肯定的数字)。
      //
      //   ⚠️ 这条**不适用**上面 /dashboard/production-progress 那段「⛔ 不加 roles 白名单」——
      //   那段的前提是「菜单闸判的模块与后端闸判的模块是同一个」。本条**不满足**该前提:
      //     · 本页 GET 的闸是 @RequireRole 角色白名单(ProcessWorkReportingController:41),
      //       **不是**模块判据。菜单按 module:'production' 放行, 而 quality_manager 的
      //       production 是 'r'(四份声明一致, 且是刻意保留的跨模块只读) ⇒ 菜单放行、接口拒绝。
      //     · 即那段注释自己写的另一半:「菜单宽、页面窄 ⇒ 看得见点不开」。
      //   ⇒ 这是 /dashboard/ai-value 那一类, 不是 /dashboard/production-progress 那一类。
      //
      //   ⛔ 不要改 permission.ts 的 production:'r' —— 那是四份声明交叉确认过的刻意设计,
      //      撤它会打断一批角色的合法跨模块只读。要对齐的是**菜单侧的角色约束**。
      //   ⚠️ workshop_supervisor 在白名单里(受众没被挡掉); 另外 4 个是
      //      RequireRoleInterceptor.PLATFORM_ADMIN_ROLES —— 它们**绕过** @RequireRole,
      //      漏掉就会把本来打得开的页从平台管理员菜单里藏掉。
      //   ▎两份一致原来由 reportApprovalMenuMatchesBackendRoles.spec.ts 钉在**本菜单项**
      //      的 roles[] 上；2026-09-02 报工审批+撤回审批合并成一个 hub 之后，判定目标
      //      挪到了 tab 级 (见下方说明)，别再往这条上加 roles[]。
      //
      // 2026-09-02 同上收拢: 报工审批 hub = 报工审批 + 撤回审批, ⛔ 路径不变
      // (仍是 /production/approval —— reportApprovalMenuMatchesBackendRoles.spec.ts
      // 按精确路径字符串查找菜单项)。旧路径 /production/reversals 仍可直接访问
      // (router redirect 保书签，见 views/production/approval-hub/index.vue)。
      //
      // 🔴 二次修正 (coordinator 复核指出的回归): 上面 @RequireRole 的 9 角色白名单
      // **只属于「报工审批」这一个视角**，合并前「撤回审批」是 module:'production'
      // 无 roles[] 门控 (production:read_write 模块权限即可, 比 9 角色白名单宽 ——
      // 例如 dispatcher 有 production:'rw' 但不在 9 角色白名单里)。第一版把 roles[]
      // 整个套在 hub 菜单项上，会让 dispatcher 连「撤回审批」菜单项都看不到——是
      // 删功能，不是可登记的副作用。
      //
      // ⇒ roles[] 已挪进 views/production/approvalQueryTab.ts 的 APPROVAL_TAB_ROLES，
      // 只门控 hub 内部「报工审批」那一个 tab 的渲染 (v-if，见 approval-hub/index.vue)；
      // 本菜单项退回到与合并前「撤回审批」一致的最广条件 (纯 module:'production')。
      // hub 菜单项可见性 = 两个 tab 可见性的并集 —— 「撤回审批」的可见集合 (任何有
      // production 模块权限的角色) 已经是「报工审批」9 角色白名单的超集，并集就是
      // 「撤回审批」那个更宽的集合，菜单层不需要 (也不应该) 再叠一层 roles[]。
      // ▎判据: 以 dispatcher 身份 mount hub ⇒ 看得到「撤回审批」tab、看不到「报工
      // 审批」tab；以 quality_manager ⇒ 看不到「报工审批」tab；以 factory_super_admin
      // ⇒ 两个 tab 都在。见 approval-hub/__tests__/approvalHub.mount.spec.ts。
      { path: '/production/approval', title: '报工审批', icon: '', module: 'production' },
      // 2026-09-02 同上收拢: 领料 hub = 领料单 (原「物料需求单」, #460 已改名) +
      // 退料记录。2 个旧路径 (/production/material-requisitions、
      // /production/material-returns) 仍可直接访问 (router redirect 保书签，见
      // views/production/material-flow/index.vue)。⛔ 两个子页面
      // (views/factory/material-requisitions/**、views/factory/material-returns/**)
      // 内容不动，只搬菜单入口。
      { path: '/production/material-flow', title: '领料', icon: '', module: 'production' },
      // 这3项是「生产管理」下的生产配置项 (虽路由在 /system/* 或已合并进 /production/*),
      // module 归 'production' 而非 'system' — 否则会被 demo 策展
      // DEMO_HIDE_MODULES_BY_TYPE['FACTORY'] 的 'system' 规则连带隐藏 (本意只藏系统管理顶级组)。
      { path: '/system/products', title: 'SKU 管理', icon: '', module: 'production', groupLabel: '生产配置' },
      // 2026-09-02 同上收拢: 工序 hub = 工序管理 + 产品-工序配置。2 个旧路径
      // (/system/work-processes、/system/product-processes) 仍可直接访问
      // (router redirect 保书签，见 views/production/process-hub/index.vue)。
      // ⛔ 只搬菜单入口/路由挂载点，不碰 WorkProcess 实体/Service/DTO (与在飞的
      // FEATURE-F006-R2-WORKPROCESS-DEDUP-001 不冲突，那条任务锁的是后端代码)。
      { path: '/production/process-hub', title: '工序', icon: '', module: 'production' },
      // 调料维护已并入产品-工序配置画布的「辅料 cell」；旧路由仅作兼容重定向
      // T125: 转换率配置菜单入口早已隐藏 — 后端 API/表/fallback 仍保留 (F001等老工厂
      // BomExpansionService 依赖)。2026-08-05 拍板转换率不再往画布搬, 维护走 API/DB。
      // BOM 配置已并入产品-工序配置画布(辅料/包材 cell + 草稿生效横幅), 菜单入口于
      // 2026-08-05 摘除, 页面本体于 2026-08-07 阶段 5 删除。
      // 老地址(/production/bom 及 versions/tree)改成 redirect 到画布, 既有深链仍不 404
      // —— 判据见 workflow/__tests__/bomMenuRetired.source.spec.ts。
      // (副产 2026-08-07 阶段 2 起是工序上的真实产出节点, 不再是 BOM 的一类 cell。)
      { path: '/production/bom/ecns', title: '工程变更通知', icon: '', module: 'production' },
      // D-S5-09: 采购价变动生成的 BOM 调价建议在这里批准 / 驳回 (此前只能调接口)
      { path: '/production/bom/price-adjustments', title: 'BOM调价建议', icon: '', module: 'production' },
      // 2026-09-02 同上收拢: 生产分析 hub = 车间实时生产报表 + 生产数据分析 +
      // BOM达成率分析 + 工序投入产出对比 + 人效双口径对比 + 成品出厂核算。
      // 6 个旧路径 (/analytics/production-report、/production-analytics/production、
      // /production/bom-achievement、/production/process-io、
      // /production/labor-efficiency、/production-analytics/yield-cost) 仍可直接
      // 访问 (router redirect 保书签，见 views/production/analysis/index.vue)。
      //
      // ⚠️ 合并前的业态门控不是同一份: 车间实时生产报表/生产数据分析/成品出厂核算
      // 原本各自 hideForFactoryTypes:['RESTAURANT','FACTORY']，另 3 个无此限制。
      // 路由级 meta 只能挂一份, 这里不设 hideForFactoryTypes (取并集，与合并前
      // 3 个「production 系」tab 的可见范围一致) —— 业态门控挪到 hub 组件内部,
      // 按 tab 各自 v-if 保留 (与合并前逐字节等价), 见 productionAnalysisTab.ts
      // 头部说明 + views/production/analysis/index.vue 的 showAnalyticsTabs。
      // 🔴 module 不叠 modulesAny (menuConfig 的 MenuItem 类型 / AppSidebar 的
      // canSeeMenuItem 只按单个 item.module 过滤菜单可见性，不支持 modulesAny) ——
      // 路由级另有 modulesAny:['production','analytics'] 放宽 (见 router/index.ts),
      // 两者不必一致: 父级 /production 组已强制要求 production, 能看到这个菜单项的
      // 人本来就有 production, 所以这里写 module:'production' 不会让任何原本进不了
      // /production 组的人多得到菜单可见性。
      { path: '/production/analysis-query', title: '生产分析', icon: '', module: 'production',
        groupLabel: '生产分析' },
      // 不在本轮合并范围内 (owner 拍板的合并表未列它) —— 保持独立菜单项。
      // 食品 DAG 缺口 09: 与财务管理 → 成本差异同一页, 生产经理从这里进
      { path: '/production/cost-variance', title: '成本差异', icon: '', module: 'production' },
      { path: '/production/cost-deviation-cases', title: '成本损耗异常', icon: '', module: 'production' },
      { path: '/production-analytics/cost-summary', title: '成本汇总', icon: '', module: 'analytics' },
    ]
  },
  {
    path: '/analytics/indicators', title: '经营指标', icon: 'Histogram', module: 'analytics',
    roles: ['factory_super_admin', 'quality_manager', 'finance_manager'],
  },
  {
    // P1-5: restaurants 默认不见 "仓储管理" (食材库存在 /restaurant/stocktaking)
    // UX P2-5: 合并"调拨管理" 1-item 组到这里,减少顶级菜单数
    path: '/warehouse', title: '仓储管理', icon: 'Box', module: 'warehouse',
    children: [
      { path: '/warehouse/unordered-inbound-applications', title: '无订单入库申请', icon: '', module: 'warehouse', groupLabel: '仓储作业', badgeKey: 'unorderedInbound' },
      { path: '/warehouse/materials', title: '入库任务与批次', icon: '', module: 'warehouse', badgeKey: 'warehouseReceiving' },
      { path: '/warehouse/shipments', title: '出货管理', icon: '', module: 'warehouse' },
      { path: '/warehouse/material-requisitions', title: '领料单', icon: '', module: 'warehouse' },
      { path: '/transfer/list', title: '调拨单', icon: '', module: 'inventory' },  // 🔴 2026-08-22: warehouse -> inventory, 端点要 inventory:write, 与 /rd/* 同形
      // SP7 六扇门 ERP-lite 报损管理 (仓库→财务 / 生产→厂长 双轨)
      { path: '/warehouse/wastage-reports', title: '报损管理', icon: '', module: 'warehouse' },
      // 2026-09-01 LIUSHANMEN 仓储「库存盘点」菜单收拢 (owner 拍板): 6 个条目
      // 合并为 2 个 hub 入口 —— owner 原话「这么多库存盘点跟那个仓库查询太乱了」。
      //   库存查询 hub = 分仓库存查询 + 总库存查询 + 库存批次查询 + 半成品库存
      //   盘点 hub    = 盘点与期初库存 + 半成品盘点
      // 4 个查询视角 / 2 个盘点视角全部保留 (el-tabs 承载, 组件原样未改),
      // 6 个旧路径 (/inventory/by-warehouse、/warehouse/inventory-total、
      // /warehouse/inventory、/warehouse/stocktakes、/warehouse/semi-finished、
      // /warehouse/semi-finished-stocktakes) 仍可直接访问 (router redirect 保
      // 书签, 见 router/index.ts + views/warehouse/{inventory-query,
      // stocktake-query}/index.vue)。
      { path: '/warehouse/inventory-query', title: '库存查询', icon: '', module: 'warehouse', groupLabel: '库存盘点' },
      { path: '/warehouse/stocktake-query', title: '盘点', icon: '', module: 'warehouse' },
      // 2026-08-25: PR#76 上线了 /warehouse/storage-locations 但从未登记进本菜单,
      // 结果全生产 0 个租户配置过仓储空间 —— 没有入口就没人找得到。
      { path: '/warehouse/storage-locations', title: '仓储空间与现场盘点', icon: '', module: 'warehouse', groupLabel: '仓储配置' },
      { path: '/warehouse/material-types', title: '原料类型字典', icon: '', module: 'warehouse' },
      { path: '/warehouse/source-individuals', title: '源头个体档案', icon: '', module: 'warehouse' },
      { path: '/warehouse/material-segments', title: '物料分类字典', icon: '', module: 'warehouse' },
      { path: '/warehouse/material-price-trend', title: '物料均价趋势', icon: '', module: 'warehouse', groupLabel: '仓储分析' },
    ]
  },
  {
    // P1-5: restaurants 默认不见 "质量管理" (食品安全走 食检,不是 ISO 质检)
    path: '/quality', title: '质量管理', icon: 'Checked', module: 'quality',
    children: [
      { path: '/quality/inspections', title: '质检记录', icon: '', module: 'quality', groupLabel: '质量检验' },
      // 缺口 11: 需检产品产出待检 → 质检员在这里放行/判不合格
      { path: '/quality/release', title: '质检放行', icon: '', module: 'quality' },
      // 缺口 13: 报工录 CCP, 超限偏离在这里处理; 按批次导出 CCP 记录
      { path: '/quality/ccp', title: 'CCP 监控记录', icon: '', module: 'quality' },
      // 报工填的工艺参数按当时标准判定的偏高/偏低, 按工序+参数汇总并下钻
      { path: '/quality/param-deviation', title: '工艺参数偏离', icon: '', module: 'quality' },
      // 缺口 15: 冷链超温告警 → 受影响批次 (暴露时长) → 一键转质检
      { path: '/quality/cold-chain', title: '冷链超温批次', icon: '', module: 'quality' },
      { path: '/quality/label-qc', title: '包装标签拍检', icon: '', module: 'quality' },
      { path: '/quality/vision-training', title: '视觉质检训练', icon: '', module: 'quality',
        roles: ['factory_super_admin', 'quality_manager'] },
      // Sprint4-H Q-PROCESS-1: 工序质检不良闭环
      { path: '/quality/defects', title: '工序质检不良', icon: '', module: 'quality' },
      // Sprint4-H Q-RETURN-1: 质检退回单 (上游退回, 不含客户销售退货)
      { path: '/quality/returns', title: '质检退回单', icon: '', module: 'quality' },
      // 纪律排查 B14 (Steve 2026-11-10): 不良品处置 / 批次召回按「质检模块权限」出入口, 不再另加岗位白名单。
      // 后端这两组接口的门槛就是 quality:read / quality:read_write (召回后端本来没有岗位限制),
      // 前端多叠的一层岗位名单只会把有质检权限的人挡在外面。要收紧就改权限配置, 不在这里写岗位名。
      {
        path: '/quality/disposition', title: '不良品处置', icon: '', module: 'quality', groupLabel: '处置闭环',
      },
      {
        path: '/quality/recalls', title: '批次召回', icon: '', module: 'quality',
      },
      { path: '/quality/disposals', title: '废弃处理', icon: '', module: 'quality' },
      // S4 缺料异常: 质检放行会减少缺口, 质检也要能看; 与生产组同一页面, 本组自己的路由 (面包屑=质量管理)
      { path: '/quality/shortage-cases', title: '缺料异常', icon: '', module: 'quality' },
      { path: '/quality/standards', title: '质检标准', icon: '', module: 'quality', groupLabel: '质量配置' }
    ]
  },
  {
    // P1-5 fix: restaurants 默认不见 "采购管理" (进货走 /restaurant/requisitions)
    // Canvas 管理员可细粒度开启 purchase_order 码解锁.
    path: '/procurement', title: '采购管理', icon: 'ShoppingCart', module: 'procurement',
    children: [
      { path: '/procurement/requisitions/my', title: '我的请购', icon: '', module: 'procurement', groupLabel: '请购流程',
        roles: ['factory_super_admin', 'platform_admin', 'procurement_manager', 'department_admin', 'permission_admin',
          'restaurant_purchaser', 'restaurant_owner'] },
      { path: '/procurement/requisitions/pending-approval', title: '待审批请购', icon: '', module: 'procurement',
        roles: ['factory_super_admin', 'platform_admin', 'procurement_manager', 'department_admin', 'permission_admin'] },
      { path: '/procurement/requisitions', title: '全部请购单', icon: '', module: 'procurement',
        roles: ['factory_super_admin', 'platform_admin', 'procurement_manager', 'permission_admin'] },
      { path: '/procurement/orders', title: '采购订单', icon: '', module: 'procurement', groupLabel: '采购执行' },
      { path: '/procurement/ai-purchase', title: 'AI受控采购', icon: '', module: 'procurement' },
      // S4 缺料异常: 采购交接 (采购草稿) 在这里做, 与生产组同一页面, 本组自己的路由 (面包屑=采购管理)
      { path: '/procurement/shortage-cases', title: '缺料异常', icon: '', module: 'procurement' },
      { path: '/procurement/suppliers', title: '供应商管理', icon: '', module: 'procurement', groupLabel: '供应商与价格' },
      { path: '/procurement/price-lists', title: '价格表管理', icon: '', module: 'procurement' }
    ]
  },
  {
    path: '/sales', title: '销售管理', icon: 'Goods', module: 'sales',
    children: [
      { path: '/sales/orders', title: '销售订单', icon: '', module: 'sales', groupLabel: '销售业务', badgeKey: 'salesOrders' },
      // 2026-06-17: 「成品库存」从销售菜单移除 (Steve: 不需要留在销售)。
      // 成品/库存归仓储管理口径; 路由 /sales/finished-goods 保留 (深链可达), 仅撤销售侧入口。
      // Apr 24 UX: /sales/shipments 等 manufacturing-only 概念对餐饮隐藏 (无批次/发货)。
      { path: '/sales/customers', title: '客户管理', icon: '', module: 'sales' },
      // S4 缺料异常: 销售看订单为什么还没排上 / 交期会不会延误, 与生产组同一页面, 本组自己的路由 (面包屑=销售管理)
      { path: '/sales/shortage-cases', title: '缺料异常', icon: '', module: 'sales' },
      { path: '/sales/delivery-risks', title: '交付风险', icon: '', module: 'sales' },
      { path: '/sales/shipments', title: '出货记录', icon: '', module: 'sales' },
      // T-RTA fix (audit B2 BLOCKER 2026-05-13): /sales/returns route was added by
      // PR #549 but NEVER surfaced in sidebar — customer service / finance roles
      // had no menu entry, only "申请退货" button on individual sales order detail.
      // Now discoverable for 历史退货 review + status-tracking workflows.
      { path: '/sales/returns', title: '销售退货', icon: '', module: 'sales' },
      // #739/#746: 销售方向付款申请 (退款/返利/销售费用) — roles 镜像 router/index.ts SalesPaymentRequests
      { path: '/sales/payment-requests', title: '销售付款申请', icon: '', module: 'sales',
        roles: ['factory_super_admin', 'platform_admin', 'sales_manager', 'salesperson', 'finance_manager', 'cashier'] },
      { path: '/sales/vehicles', title: '车辆字典', icon: '', module: 'sales', groupLabel: '销售配置' }
    ]
  },
  {
    // P1-5: restaurants 默认不见 "人事管理" (小连锁常无 HR 系统,Canvas 可开启 hr_employee)
    path: '/hr', title: '人事管理', icon: 'User', module: 'hr',
    children: [
      // 2026-09-01 owner 拍板: 账号管理**留在人事管理**, 三处不一致改**面包屑**去跟随它
      // (router 的 meta.breadcrumbParent, 见 router/index.ts)。
      //
      // 曾短暂改成移去「系统管理」以对齐路由前缀, owner 否决 —— 理由是业务归属:
      // 建号/改角色是 HR 的活, 放 HR 底下才符合谁在用它。URL 前缀是历史, 不是分类依据。
      //
      // ⚠️ 已知且**被接受**的后果: 本组带 hideForFactoryTypes: ['RESTAURANT'],
      //    而 AppSidebar.filterMenuItem 先判父级 ⇒ 餐饮租户侧边栏里没有账号管理入口。
      //    owner 2026-09-01: 「工厂侧跟餐饮一点关系都没有」—— 这个页面是工厂侧工具,
      //    餐饮租户不从这里管账号。⛔ 不要把它当成待修缺陷「顺手修好」。
      { path: '/system/users', title: '账号管理', icon: '', module: 'system', groupLabel: '账号权限' },
      { path: '/system/roles', title: '角色权限', icon: '', module: 'system' },
      // 权限矩阵只读页 (ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906)。
      // module: 'system' 与后端端点的 @RequirePermission 同码 —— ⛔ 不加 roles 白名单,
      // 否则「菜单可见」与「接口可进」会分叉。
      { path: '/system/permission-matrix', title: '权限矩阵', icon: '', module: 'system' },
      { path: '/hr/employees', title: '员工档案', icon: '', module: 'hr', groupLabel: '员工组织' },
      { path: '/hr/departments', title: '部门管理', icon: '', module: 'hr' },
      { path: '/hr/work-types', title: '工种字典', icon: '', module: 'hr' },
      { path: '/hr/attendance', title: '考勤管理', icon: '', module: 'hr', groupLabel: '入职考勤' },
      { path: '/hr/whitelist', title: '账号邀请', icon: '', module: 'hr' }
    ]
  },
  // UX P2-5 merged into 仓储管理: /transfer 原独立顶级组 (1 项), 合并节省 1 顶级项
  {
    // P1-5: restaurants 默认不见 "设备管理" (manufacturing 专属)
    path: '/equipment', title: '设备管理', icon: 'Monitor', module: 'equipment',
    children: [
      { path: '/equipment/list', title: '设备列表', icon: '', module: 'equipment', groupLabel: '设备台账' },
      { path: '/equipment/maintenance', title: '维护记录', icon: '', module: 'equipment', groupLabel: '维护监控' },
      { path: '/equipment/alerts', title: '告警管理', icon: '', module: 'equipment' }
    ]
  },
  {
    // P1-5: restaurants 默认不见 "财务管理" (SmartBI 有 /smart-bi/finance 简化版,
    // manufacturing 的 ar-ap/invoices/sku-margin 对餐饮无意义)
    path: '/finance', title: '财务管理', icon: 'Money', module: 'finance',
    children: [
      { path: '/finance/adjustments', title: '调整审批', icon: '', module: 'finance', groupLabel: '审核队列' },
      { path: '/finance/disposal-approvals', title: '报废审批', icon: '', module: 'finance',
        requiresDisposalApproval: true },
      { path: '/finance/costs', title: '财务概览', icon: '', module: 'finance', groupLabel: '财务核算' },
      { path: '/finance/three-statements', title: '财务报表', icon: '', module: 'finance' },
      { path: '/finance/reports', title: '财务分析(Excel)', icon: '', module: 'finance' },
      { path: '/finance/ar-ap', title: '应收应付', icon: '', module: 'finance' },
      { path: '/finance/invoices', title: '销售开票', icon: '', module: 'finance' },
      { path: '/finance/purchase-invoices', title: '采购收票', icon: '', module: 'finance',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'finance_manager'] },
      { path: '/finance/payments', title: '收款管理', icon: '', module: 'finance' },
      // 会计凭证 —— 2026-08-27 补入口。此前这三个页面【菜单零登记、App 也没有】,
      //   只能手打 URL 才进得去; 而生产库里已有 凭证 18 张 / 分录 46 条 /
      //   导出记录 106 条 / 模板 7 个 ⇒ 凭证在生成、有人在导, 却没有任何界面能看。
      //   (可达性闸: web-admin/src/router/__tests__/routeReachability.spec.ts)
      { path: '/finance/voucher-list', title: '会计凭证', icon: '', module: 'finance', groupLabel: '会计凭证' },
      { path: '/finance/voucher-export', title: '凭证导出', icon: '', module: 'finance', groupLabel: '会计凭证' },
      { path: '/finance/voucher-template', title: '凭证模板', icon: '', module: 'finance', groupLabel: '会计凭证',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'finance_manager'] },
      { path: '/finance/sku-margin', title: 'SKU毛利率分析', icon: '', module: 'finance', groupLabel: '财务分析' },
      // 食品 DAG 缺口 09: 成本差异 (标准 = 锁定配方用量 × 开计划时单价快照, 拆价格差/用量差/替代料差/人工差)
      { path: '/finance/cost-variance', title: '成本差异', icon: '', module: 'finance' },
      { path: '/finance/cost-deviation-cases', title: '成本损耗异常', icon: '', module: 'finance' },
      { path: '/finance/gross-margin-redline', title: '毛利红线配置', icon: '', module: 'finance', groupLabel: '财务配置' },
      { path: '/finance/accounting-period', title: '会计期间', icon: '', module: 'finance' }
    ]
  },
  // UX P2-5 merged into 生产管理: /rd 原独立顶级组 (1 项), 研发样品并入生产
  {
    path: '/system', title: '系统管理', icon: 'Setting', module: 'system',
    children: [
      { path: '/system/logs', title: '操作日志', icon: '', module: 'system', groupLabel: '系统运维' },
      { path: '/system/settings', title: '系统设置', icon: '', module: 'system', groupLabel: '工厂配置' },
      // 2026-08-28: 阈值参数中心工厂侧入口 —— owner 原话「得有一个模块专门去设置这个东西」。
      // ⚠️ groupLabel 只标记组的第一条 (见 childGroupLabels 测试), 沿用上一条 '工厂配置'
      //    这个组, 不重复声明 groupLabel, 否则会被判定成"多开了一个同名组".
      { path: '/system/business-thresholds', title: '业务阈值配置', icon: '', module: 'system' },
      { path: '/system/approval-chains', title: '审批链配置', icon: '', module: 'system' },
      { path: '/system/workflow-designer', title: '工作流设计器', icon: '', module: 'system' },
      { path: '/system/features', title: '功能模块配置', icon: '', module: 'system' },
      // 2026-10-06 owner: 一线角色 (操作员/出成率录入员/仓库员/质检员) 的 AI 写操作
      //   默认只开日常工作, 其余由工厂管理员在这里逐个开放。餐饮租户没有这四个角色 ⇒ 隐藏。
      { path: '/system/ai-frontline-grants', title: '一线角色 AI 操作权限', icon: '', module: 'system' },
      // Apr 18 2026 bug #48: Canvas 编辑器 router 限制 roles, sidebar 跟上不让 dispatcher/
      // 其他 system:read 角色看到菜单 (否则点进去 /403 体验差)
      { path: '/canvas-editor', title: 'Canvas 配置编辑器', icon: '', module: 'system', roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] },
      { path: '/system/badge-generator', title: '员工工牌生成', icon: '', module: 'system' },
      // T123: 计量单位字典 — 客户要求"留个给我自己修改"的单位配置模块
      // 后端: GET/POST/PUT/DELETE /api/mobile/{factoryId}/system-config/units
      { path: '/unit-dictionary', title: '计量单位字典', icon: '', module: 'system',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] },
      // 操作日志 —— 2026-08-27 补入口。审计用途, 生产已有 758 条且 2026-08-25 仍在写,
      //   而此前【没有任何界面能查】。审计记录查不到, 等于没有审计。
      { path: '/system/operation-log', title: '操作日志', icon: '', module: 'system' },
      // 2026-10-24 隐私条款第六、十一条对客户开启: 工艺保密管理 / 平台人员访问记录
      { path: '/system/process-secret', title: '工艺保密', icon: '', module: 'system' },
      { path: '/system/platform-access-log', title: '平台访问记录', icon: '', module: 'system',
        roles: ['factory_super_admin', 'permission_admin'] },
      // 2026-10-27 按工厂备份套餐: 工厂只读看本厂记录 / 平台开通与修改
      { path: '/system/data-backups', title: '数据备份', icon: '', module: 'system',
        roles: ['factory_super_admin', 'permission_admin'] },
      // 2026-10-29 S6-02 / S6-04: 可复用 Skill; 定时任务写入 (总开关默认关, 须本厂超级管理员打开)
      { path: '/system/reusable-skills', title: '可复用 Skill', icon: '', module: 'system',
        roles: ['factory_super_admin', 'permission_admin'] },
      { path: '/system/autonomous-writes', title: '定时任务写入', icon: '', module: 'system',
        roles: ['factory_super_admin'] },
      // 2026-10-29 S6-07: 工厂管理员(仅本厂)可看 AI 运行轨迹与评测; 路由同白名单, 不再只能手输 URL
      { path: '/ops/agent-ops', title: 'Agent 运行与评测', icon: '', module: 'analytics',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] },
      { path: '/system/ai-intents', title: 'AI意图配置', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY, groupLabel: '平台治理' },
      { path: '/system/tenant-backups', title: '工厂数据备份', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/skill-tools', title: 'Skill/Tool治理', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/llm-usage', title: 'LLM 用量监控', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      // 标签识别诊断 / 单盒标签精修 —— 2026-08-27 补入口。
      //   这两个平台侧排障页此前【菜单零登记】, 只能手打 URL; 而它们服务的子系统
      //   是生产上数据量最大的之一: 托盘裁图 11409 / 标注 9583 / 照片 5850 / 任务 1495。
      //   操作侧的「包装标签拍检」(/quality/label-qc) 一直在菜单里, 唯独【排障的人打不开】。
      //   ⇒ 不是冗余页面, 是重度使用子系统的诊断工具。
      { path: '/system/label-qc-platform-diagnostics', title: '标签识别诊断', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/label-qc-tray-crops', title: '单盒标签精修', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/encoding-rules', title: '编码规则字典', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/ai-quota', title: 'AI 配额规则', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/pos', title: 'POS集成', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/smartbi-config', title: 'SmartBI配置', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      // UX P2-5: 行为校准 (1 项) 合并入系统管理下, 不单做顶级组
      { path: '/calibration/list', title: '行为校准', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      // 餐饮 Phase A A-3 Task 3.5: data quality queue admin page
      { path: '/system/data-quality-queue', title: '数据质量队列', icon: '', module: 'system',
        roles: PLATFORM_ADMIN_ONLY },
      { path: '/factory-onboarding', title: '对话建厂', icon: 'ChatDotRound', module: 'system',
        roles: FACTORY_ASSEMBLY_ROLES, assemblyFeature: true, groupLabel: '平台建厂' },
      { path: '/factory-review', title: '建厂复核', icon: 'DocumentChecked', module: 'system',
        roles: FACTORY_ASSEMBLY_ROLES, assemblyFeature: true },
      // The factory's own read-only readiness. No assemblyFeature: the sidebar enables that flag for
      // platform super admins only, so it would hide this entry from every factory account.
      { path: '/factory-readiness', title: '建厂就绪', icon: 'DocumentChecked', module: 'system',
        roles: ['factory_super_admin'] },
    ]
  },
  {
    path: '/scheduling', title: '智能调度', icon: 'Calendar', module: 'scheduling',
    children: [
      { path: '/scheduling/logistics/workbench', title: '排线工作台', icon: '', module: 'scheduling', groupLabel: '日常调度',
        hideForFactoryTypes: ['FACTORY'] },
      { path: '/scheduling/logistics/records', title: '调度记录', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['FACTORY'] },
      { path: '/scheduling/logistics/orders', title: '门店与订单', icon: '', module: 'scheduling', groupLabel: '基础资料',
        hideForFactoryTypes: ['FACTORY'] },
      { path: '/scheduling/logistics/resources', title: '车辆与司机', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['FACTORY'] },
      { path: '/scheduling/logistics/stores', title: '门店库', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['FACTORY'] },
      { path: '/scheduling/overview', title: '调度中心', icon: '', module: 'scheduling', groupLabel: '工厂智能调度',
        hideForFactoryTypes: ['LOGISTICS'] },
      { path: '/scheduling/plans', title: '调度计划', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['LOGISTICS'] },
      { path: '/scheduling/proposal-comparison', title: '多方案比较', icon: '', module: 'scheduling',
        roles: ['factory_super_admin', 'permission_admin'], hideForFactoryTypes: ['LOGISTICS'] },
      { path: '/scheduling/realtime', title: '实时监控', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['LOGISTICS'] },
      { path: '/scheduling/workers', title: '人员分配', icon: '', module: 'scheduling', groupLabel: '资源与预警',
        hideForFactoryTypes: ['LOGISTICS'] },
      { path: '/scheduling/alerts', title: '告警管理', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['LOGISTICS'] },
      { path: '/scheduling/settings', title: '排产设置', icon: '', module: 'scheduling', groupLabel: '调度配置',
        hideForFactoryTypes: ['LOGISTICS'] }
    ]
  },
  {
    // UX 2026-06-01: 合并「经营报表」(/analytics) + 「智能分析」(/smart-bi) 为单一
    // 「数据与分析」组 (spec 2026-06-01-web-admin-analytics-ia-redesign-design.md)。
    // WS4 (2026-06-02): 财务看板/销售分析/趋势分析/KPI看板/指标中心 6 页合并为单一
    // 「经营分析」(BusinessAnalysisHub, 4 tab) — 菜单项移除, 由 redirect 桥接。
    // 删 AI分析报告 (#9)。AI 运维 3 项收 platform_admin/permission_admin 门控 (移 admin 区)。
    // 经营驾驶舱置顶主入口。各页后端不变 (部分页 Java reports + Python 混合)。
    // 2026-08-22 owner 曾令「数据分析全部隐藏, 工厂暂时用不着」(hideForFactoryTypes: ['FACTORY'])。
    // P2b（迁移方案 M3，2026-11-14 Steve 定）：对工厂**默认显示，按厂可关**。
    //   关 = feature-config analytics 行 config.showInSidebar === false（平台「工厂菜单」页写入），
    //   判据在 factoryMenuVisibility.isFactoryAnalyticsGroupHidden；模块/角色判据不变。
    //   路由 meta 从来没对工厂挡这些页（只挡菜单），所以放出菜单不会「看得见点不开」。
    // 闸 menuPermissionParity.spec.ts 的 EXPECTED_CHANGES 逐条列了这次变化的 14 个叶子。
    path: '/smart-bi', title: '数据与分析', icon: 'TrendCharts', module: 'analytics',
   
    children: [
      // ★ 主入口 (无 groupLabel, 置顶)
      { path: '/smart-bi/dashboard', title: '经营驾驶舱', icon: 'Monitor', module: 'analytics' },
      // -- AI 探索 --
      // P3: /smart-bi/query 已合并入此页 query tab (redirect /smart-bi/query → /smart-bi/analysis?tab=query), 菜单项移除
      { path: '/smart-bi/analysis', title: 'AI 问答 / 数据分析', icon: 'DataAnalysis', module: 'analytics', groupLabel: 'AI 探索' },
      // -- 经营分析 --
      // WS4: 财务/销售/趋势/KPI·指标 合并为单一 hub。旧路径 (financial-dashboard/sales/
      // trends/kpi/indicator-center) 由 router redirect 保书签 → /smart-bi/analysis-hub?tab=。
      { path: '/smart-bi/analysis-hub', title: '经营分析', icon: 'TrendCharts', module: 'analytics', groupLabel: '经营分析' },
      { path: '/smart-bi/revenue-report', title: '收入管理报表', icon: 'Money', module: 'analytics',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'finance_manager', 'restaurant_manager', 'restaurant_owner'],
        hideForFactoryTypes: ['FACTORY'] },
      { path: '/analytics/alert-dashboard', title: '异常预警', icon: 'Warning', module: 'analytics' },
      { path: '/analytics/supply-chain', title: '进销存总览', icon: 'Histogram', module: 'analytics' },
      // -- 数据管理 --
      { path: '/smart-bi/upload', title: 'Excel 上传', icon: 'Upload', module: 'analytics', groupLabel: '数据管理' },
      { path: '/smart-bi/query-templates', title: '查询模板', icon: 'Tickets', module: 'analytics' },
      { path: '/smart-bi/data-completeness', title: '数据完整度', icon: 'DataAnalysis', module: 'analytics' },
      // Phase 0: 字段映射复核 — Excel 列无法自动映射时由人工确认并写入 pin (2026-06-15)
      { path: '/smart-bi/mapping-review', title: '字段映射复核', icon: 'EditPen', module: 'analytics' },
      // D-6 保守保留: 分析概览 (与驾驶舱/hub 重叠但数据源不同, P5 凭埋点再决定真删)
      { path: '/analytics/overview', title: '分析概览', icon: 'DataAnalysis', module: 'analytics' },
      // -- AI 运维 (admin) — WS4: 收 admin 门控 (普通经营用户不需要) --
      { path: '/smart-bi/food-kb-feedback', title: '知识库反馈', icon: 'ChatDotRound', module: 'analytics', groupLabel: 'AI 运维',
        roles: ['platform_admin', 'permission_admin', 'factory_super_admin'] },
      { path: '/smart-bi/fallback-log', title: 'AI 追问日志', icon: 'DataLine', module: 'analytics',
        roles: ['platform_admin', 'permission_admin', 'factory_super_admin'] },
      { path: '/smart-bi/calibration', title: '行为校准监控', icon: 'Aim', module: 'analytics', roles: ['platform_admin'] },
    ]
  },
];

const TOP_LEVEL_FLOW_ORDER: Record<string, number> = {
  '/dashboard': 10,
  '/workspaces': 15,
  '/workdesk': 20,
  '/workflow': 25,
  '/procurement': 30,
  '/sales': 40,
  '/finance': 50,
  '/analytics/ai-reports': 52,
  '/rd': 55,
  '/production': 60,
  '/warehouse': 70,
  '/quality': 80,
  '/smart-bi': 90,
  '/analytics/indicators': 95,
  '/system': 100,
  '/scheduling': 110,
  '/hr': 120,
  '/equipment': 130,
  '/restaurant': 140,
};

function sortTopLevelMenu(items: MenuItem[]): MenuItem[] {
  return [...items].sort((a, b) => {
    const left = TOP_LEVEL_FLOW_ORDER[a.path] ?? 1000;
    const right = TOP_LEVEL_FLOW_ORDER[b.path] ?? 1000;
    return left - right;
  });
}

export const menuConfig: MenuItem[] = sortTopLevelMenu(rawMenuConfig);

/**
 * Platform operators have a separate daily workspace. Keep this list made
 * from real platform routes; factory operations are intentionally not part of
 * the platform sidebar. A factory read-only context switches back to the
 * normal menu tree without changing the platform identity.
 */
export const platformMenuConfig: MenuItem[] = [
  {
    path: '/platform',
    title: '平台管理',
    icon: 'Setting',
    module: 'system',
    roles: ['platform_admin'],
    children: [
      { path: '/platform/factories', title: '工厂管理', icon: '', module: 'system', roles: ['platform_admin'] },
      { path: '/system/llm-usage', title: 'LLM 用量监控', icon: '', module: 'system', roles: ['platform_admin'] },
    ],
  },
  {
    // 2026-11-03 Steve: 平台专属工具(对话建厂/建厂复核/平台治理/诊断等)回到平台侧栏。
    // 取自 menuConfig 里 roles 恰为 ['platform_admin'] 的条目, 不另维护第二份名单;
    // 工厂日常菜单(生产/仓储/采购/销售…)仍不进平台侧栏。
    path: '/platform-tools',
    title: '平台工具',
    icon: 'Monitor',
    module: 'system',
    roles: ['platform_admin'],
    children: collectPlatformOnlyItems(menuConfig)
      .filter((item) => item.path !== '/system/llm-usage'),
  },
];

/** menuConfig 中只有平台超级管理员可见的叶子条目(roles 恰为 ['platform_admin'])。 */
function collectPlatformOnlyItems(items: MenuItem[]): MenuItem[] {
  const out: MenuItem[] = [];
  for (const item of items) {
    if (item.children?.length) {
      out.push(...collectPlatformOnlyItems(item.children));
    } else if (item.roles?.length === 1 && item.roles[0] === 'platform_admin') {
      out.push(item);
    }
  }
  return out;
}

/** 平台身份在不进入工厂查看上下文时可以打开的路由前缀(与平台侧栏同源)。 */
export const platformWorkspacePaths: string[] = platformMenuConfig
  .flatMap((group) => group.children ?? [])
  .map((item) => item.path);

// ============================================================================
// P2（MIGRATION-PLAN 第一块 1.2 / DECISIONS 第十八节 M1–M8）：统一原型的侧栏分组。
//
// 原则：上面的 rawMenuConfig / menuConfig 一字不改，仍是每个菜单项（path + 权限字段）的唯一来源；
// 这里只是把同一批对象重新摆位置。
//   · 整组搬：直接引用原分组对象（权限字段天然相同）。
//   · 单个叶子搬出原分组：带上 guardAncestors = 原来那几层父分组，侧栏判可见时父组也要过，
//     所以「关掉父组路径 / 模块 ⇒ 子项跟着消失」的行为不变。
//   · 新分组（概览 / 数据与分析 / 主数据）是 container：纯展示，本身不带判据。
// 闸：__tests__/gates/menuPermissionParity.spec.ts 拿改前快照逐项比对每个叶子的
//     「自身 + 祖先链」权限字段，数量也要相等。
//
// 本批的取舍（P2 brief 要求写清）：
//   · 顶层「今天 /today」「待办 /todo」P3 已加，「发现的问题 /issues」P4 已加（TOP_ENTRIES）；
//     P4 同时把概览里的「异常中心 /workspaces/exceptions」拿掉（旧路由跳到 /issues），闸里 EXPECTED_REMOVALS 登记。
//   · （P2 原文：三页要到 P3/P4 才有，当时不显示。）
//     不指向现有近似页的理由：那样会让同一 path 在菜单里出现两次（选中高亮/面包屑二义），
//     而新 path 又还没有路由。section 'top' 的位置已留好，P3/P4 只加条目。
//   · 「数字班组」组本批不出现（M4）。委外收发/客户价目表/库存调整单/协同任务 4 页不做（M7）。
//   · 「数据与分析」对工厂默认显示（M3）在 P2b 落地：/smart-bi 去掉 hideForFactoryTypes FACTORY，
//     按厂关闭改为 feature-config showInSidebar === false；闸里 EXPECTED_CHANGES 白名单逐条列出。
//   · 餐饮 / 物流工厂与平台侧栏不在原型范围（MIGRATION-PLAN 1.4）：继续用原分组，只换 token 与外壳。
//   · 财务主管专用菜单 financeManagerMenu 不动（P9 财务换外壳时再看）。
// ============================================================================

function stripChildren(item: MenuItem): MenuItem {
  const { children: _children, ...rest } = item;
  return rest;
}

interface Located { node: MenuItem; ancestors: MenuItem[] }

function locate(items: readonly MenuItem[], path: string, ancestors: MenuItem[] = []): Located | null {
  for (const item of items) {
    if (item.path === path) return { node: item, ancestors };
    if (item.children?.length) {
      const hit = locate(item.children, path, [...ancestors, item]);
      if (hit) return hit;
    }
  }
  return null;
}

function mustLocate(path: string): Located {
  const hit = locate(menuConfig, path);
  if (!hit) throw new Error(`groupedMenuConfig: 菜单项 ${path} 不在 menuConfig 里（改了 path 要同步这里）`);
  return hit;
}

/** 把一个叶子从原分组里拿出来，带上原父组判据。groupLabel（原组内小标题）不带走。 */
function moveLeaf(path: string): MenuItem {
  const { node, ancestors } = mustLocate(path);
  if (node.children?.length) throw new Error(`groupedMenuConfig: ${path} 是分组，整组搬用 takeGroup`);
  const { groupLabel: _groupLabel, ...leaf } = node;
  const guards = [...(node.guardAncestors ?? []), ...ancestors.map(stripChildren)];
  return guards.length ? { ...leaf, guardAncestors: guards } : leaf;
}

/** 一个分组的全部子项都按 moveLeaf 搬出（父组本身不再出现，但作为 guardAncestors 继续把关）。 */
function moveAllChildren(groupPath: string): MenuItem[] {
  const { node } = mustLocate(groupPath);
  return (node.children ?? []).map((child) => {
    if (child.children?.length) throw new Error(`groupedMenuConfig: ${child.path} 有下级，不能平铺`);
    const { node: c, ancestors } = mustLocate(child.path);
    return { ...c, guardAncestors: [...(c.guardAncestors ?? []), ...ancestors.map(stripChildren)] };
  });
}

/** 整组引用，去掉搬走的子项；被搬走的子项若带小标题，交给下一个留下的兄弟。 */
function takeGroup(path: string, removed: ReadonlySet<string>, overrides: Partial<MenuItem> = {}): MenuItem {
  const { node } = mustLocate(path);
  if (!node.children?.length) return { ...node, ...overrides };
  const kept: MenuItem[] = [];
  let pendingLabel: string | undefined;
  for (const child of node.children) {
    if (removed.has(child.path)) {
      if (child.groupLabel) pendingLabel = child.groupLabel;
      continue;
    }
    if (pendingLabel && !child.groupLabel) kept.push({ ...child, groupLabel: pendingLabel });
    else kept.push(child);
    pendingLabel = undefined;
  }
  return { ...node, ...overrides, children: kept };
}

function container(path: string, title: string, icon: string, children: MenuItem[], section: MenuSection = 'all'): MenuItem {
  // module 只为满足类型；container 在侧栏 canSeeMenuItem 里直接放行，不按它判。
  return { path, title, icon, module: 'dashboard', container: true, section, children };
}

/** 设置 › 主数据：只改菜单位置，路由不变（MIGRATION-PLAN 1.2）。 */
const MASTER_DATA_PATHS = [
  '/system/products',
  '/procurement/suppliers',
  '/sales/customers',
  '/warehouse/material-types',
  '/warehouse/material-segments',
  '/sales/vehicles',
  '/unit-dictionary',
] as const;
const SETTINGS_LEAF_PATHS = ['/quality/vision-training'] as const;

const MOVED_OUT = new Set<string>([...MASTER_DATA_PATHS, ...SETTINGS_LEAF_PATHS]);

/**
 * P3：顶层独立入口「今天」「待办」。两页只读聚合各来源接口，自己没有数据权限；
 * 每个来源在页面里按各自模块权限决定读不读（权限不够的来源不发请求、写明原因），所以入口本身对所有工厂账号开放，
 * 与「个人 OA」同判据（module: 'dashboard'，人人都有）。新增项的权限字段在 menuPermissionParity 里列为预期新增。
 * P4：「发现的问题 /issues」同判据——问题从各来源接口读（各按自己的模块权限），处置写入时后端再按来源查权限。
 */
const TOP_ENTRIES: MenuItem[] = [
  { path: '/today', title: '今天', icon: 'Sunny', module: 'dashboard', section: 'top' },
  { path: '/todo', title: '待办', icon: 'Finished', module: 'dashboard', section: 'top' },
  { path: '/issues', title: '发现的问题', icon: 'WarningFilled', module: 'dashboard', hideForFactoryTypes: ['RESTAURANT', 'LOGISTICS'], section: 'top' },
];

/** P4：原「异常中心」由顶层「发现的问题」取代，概览里不再列（原分组 menuConfig 不动，餐饮 / 物流本来就看不到它）。 */
const REPLACED_BY_ISSUES = new Set<string>(['/workspaces/exceptions']);

function buildGroupedMenu(): MenuItem[] {
  const overview = container('#group/overview', '概览', 'House', [
    mustLocate('/dashboard').node,
    mustLocate('/workdesk').node,
    takeGroup('/workspaces', REPLACED_BY_ISSUES),
    mustLocate('/dashboard/ai-value').node,
  ]);
  const analytics = container('#group/analytics', '数据与分析', 'DataAnalysis', [
    mustLocate('/analytics/ai-reports').node,
    mustLocate('/analytics/indicators').node,
    ...moveAllChildren('/smart-bi'),
  ]);
  const all: MenuItem[] = [
    overview,
    takeGroup('/workflow', MOVED_OUT),
    takeGroup('/procurement', MOVED_OUT),
    takeGroup('/sales', MOVED_OUT),
    takeGroup('/production', MOVED_OUT),
    takeGroup('/warehouse', MOVED_OUT),
    takeGroup('/quality', MOVED_OUT),
    analytics,
    // 原型关掉、正式端保留的模块（第三块）：只换外观与位置
    takeGroup('/rd', MOVED_OUT),
    takeGroup('/hr', MOVED_OUT),
    takeGroup('/equipment', MOVED_OUT),
    takeGroup('/finance', MOVED_OUT),
    takeGroup('/scheduling', MOVED_OUT),
  ].map((item) => ({ ...item, section: 'all' as const }));

  const settings: MenuItem[] = [
    container('#group/master-data', '主数据', 'Tickets', MASTER_DATA_PATHS.map(moveLeaf), 'settings'),
    ...SETTINGS_LEAF_PATHS.map((p) => ({ ...moveLeaf(p), icon: 'Aim', section: 'settings' as const })),
    { ...takeGroup('/system', MOVED_OUT), section: 'settings' as const },
  ];
  return [...TOP_ENTRIES, ...all, ...settings];
}

/** FACTORY / CENTRAL_KITCHEN（及未带工厂类型的工厂账号）用的原型分组侧栏。 */
export const groupedMenuConfig: MenuItem[] = buildGroupedMenu();

/** 餐饮 / 物流不在原型范围，继续用原分组。 */
const LEGACY_LAYOUT_FACTORY_TYPES = new Set(['RESTAURANT', 'LOGISTICS']);

export function factoryMenuForType(factoryType: string | null | undefined): MenuItem[] {
  return factoryType && LEGACY_LAYOUT_FACTORY_TYPES.has(factoryType) ? menuConfig : groupedMenuConfig;
}

/** 面包屑「组 › 项 › 子项」：按菜单树找当前路径的祖先标题；section 不进面包屑。找不到返回 null。 */
export function menuTrail(items: readonly MenuItem[], path: string): MenuItem[] | null {
  const pathname = path.split(/[?#]/)[0];
  let best: MenuItem[] | null = null;
  const visit = (nodes: readonly MenuItem[], trail: MenuItem[]) => {
    for (const node of nodes) {
      const next = [...trail, node];
      if (node.children?.length) {
        visit(node.children, next);
        continue;
      }
      const leafPath = node.path.split(/[?#]/)[0];
      if (pathname === leafPath || pathname.startsWith(leafPath + '/')) {
        // 取最长匹配（/finance/invoices 与 /finance/invoices/123 之类）
        if (!best || leafPath.length > best[best.length - 1].path.split(/[?#]/)[0].length) best = next;
      }
    }
  };
  visit(items, []);
  return best;
}
