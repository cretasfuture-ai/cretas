import type { ModuleName } from '@/store/modules/permission';
import {
  RESTAURANT_ALL_ROLES,
  RESTAURANT_DATA_STEWARD_ROLES,
  RESTAURANT_DECISION_ROLES,
} from '@/views/restaurant/restaurantRoleExperience';

// 菜单配置
export interface MenuItem {
  path: string;
  title: string;
  icon: string;
  module: ModuleName;
  roles?: string[];
  /** 仅 factoryType=RESTAURANT 时叠加的角色白名单，不影响工厂同一路由。 */
  restaurantRoles?: string[];
  hideForFactoryTypes?: string[];
  children?: MenuItem[];
  groupLabel?: string;
  /** 真实待办计数 key。接口失败时隐藏，不允许用 0 伪装成功。 */
  badgeKey?: import('@/store/modules/taskBadges').TaskBadgeKey;
}

const PLATFORM_ADMIN_ONLY = ['platform_admin'];
const RESTAURANT_ADMIN_ROLES = ['factory_super_admin', 'platform_admin', 'permission_admin'];
const RESTAURANT_ANALYTICS_ALL = [...RESTAURANT_ADMIN_ROLES, ...RESTAURANT_ALL_ROLES];
const RESTAURANT_ANALYTICS_DECISION = [...RESTAURANT_ADMIN_ROLES, ...RESTAURANT_DECISION_ROLES];
const RESTAURANT_ANALYTICS_STEWARDS = [...RESTAURANT_ADMIN_ROLES, ...RESTAURANT_DATA_STEWARD_ROLES];

// 财务主管专用菜单 - 简化版
// WS4: 财务看板/财务分析/销售分析 合并为单一「经营分析」hub (财务/销售/趋势/KPI tab)。
export const financeManagerMenu: MenuItem[] = [
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
  { path: '/restaurant/supplier-reconciliation', title: '供应商月对账', icon: 'Money', module: 'finance', hideForFactoryTypes: ['FACTORY'] },
  { path: '/restaurant/cost-attribution', title: '成本归因', icon: 'Histogram', module: 'finance', hideForFactoryTypes: ['FACTORY'] },
  // 工厂财务只保留本职工作台。销售/采购审批统一在个人 OA，财务菜单不再重复挂审批索引。
  { path: '/finance/ar-ap', title: '应收应付', icon: 'Wallet', module: 'finance', hideForFactoryTypes: ['RESTAURANT'] },
  { path: '/finance/invoices?status=REQUESTED', title: '销售开票', icon: 'Tickets', module: 'finance', hideForFactoryTypes: ['RESTAURANT'] },
  { path: '/finance/purchase-invoices', title: '采购收票', icon: 'DocumentChecked', module: 'finance', hideForFactoryTypes: ['RESTAURANT'] },
  { path: '/finance/payments', title: '收款管理', icon: 'Money', module: 'finance', hideForFactoryTypes: ['RESTAURANT'] },
];

const rawMenuConfig: MenuItem[] = [
  { path: '/dashboard', title: '首页', icon: 'House', module: 'dashboard', hideForFactoryTypes: ['LOGISTICS'] },
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
  {
    // P1-5: restaurants 默认不见 "生产管理" (BOM/批次是 manufacturing 语言,
    // 餐饮用配方/备餐在 /restaurant/recipes)
    // UX P2-5: 合并"研发管理" 1-item 组, 减少顶级菜单
    path: '/production', title: '生产管理', icon: 'Operation', module: 'production',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/production/plans', title: '生产计划', icon: '', module: 'production', groupLabel: '生产过程' },
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
      { path: '/production/pending-queue', title: '未完成计划队列', icon: '', module: 'production' },
      { path: '/production/restock-board', title: '备货看板', icon: '', module: 'production' },
      { path: '/production/delivery-warnings', title: '交货预警', icon: '', module: 'production' },
      { path: '/production/approval', title: '报工审批', icon: '', module: 'production' },
      { path: '/production/reversals', title: '撤回审批', icon: '', module: 'production' },
      { path: '/production/material-requisitions', title: '物料需求单', icon: '', module: 'production' },
      { path: '/production/material-returns', title: '退料记录', icon: '', module: 'production' },
      // 这3项是「生产管理」下的生产配置项 (虽路由在 /system/*), module 归 'production' 而非 'system' —
      // 否则会被 demo 策展 DEMO_HIDE_MODULES_BY_TYPE['FACTORY'] 的 'system' 规则连带隐藏 (本意只藏系统管理顶级组)。
      { path: '/system/products', title: 'SKU 管理', icon: '', module: 'production', groupLabel: '生产配置' },
      { path: '/system/work-processes', title: '工序管理', icon: '', module: 'production',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/system/product-processes', title: '产品-工序配置', icon: '', module: 'production',
        hideForFactoryTypes: ['RESTAURANT'] },
      // 调料维护已并入产品-工序配置画布的「辅料 cell」；旧路由仅作兼容重定向
      // T125: 转换率配置菜单入口早已隐藏 — 后端 API/表/fallback 仍保留 (F001等老工厂
      // BomExpansionService 依赖)。2026-08-05 拍板转换率不再往画布搬, 维护走 API/DB。
      // BOM 配置已并入产品-工序配置画布(辅料/包材 cell + 草稿生效横幅), 菜单入口于
      // 2026-08-05 摘除, 页面本体于 2026-08-07 阶段 5 删除。
      // 老地址(/production/bom 及 versions/tree)改成 redirect 到画布, 既有深链仍不 404
      // —— 判据见 workflow/__tests__/bomMenuRetired.source.spec.ts。
      // (副产 2026-08-07 阶段 2 起是工序上的真实产出节点, 不再是 BOM 的一类 cell。)
      { path: '/production/bom/ecns', title: '工程变更通知', icon: '', module: 'production' },
      // 🔴 2026-08-22: module 从 'production' 改成 'rd'。
      // 原来声明 production ⇒ **几乎所有生产侧角色都看得见**, 但页面调的 /rd/* 端点要 rd
      // (只有 12 个角色有) ⇒ 差集那 9 个角色点进去必 403。
      // ▎菜单按「有没有 production」判可见, 页面按「有没有 rd」判可用 —— 中间的差就是「看得见点不开」。
      // ⛔ 不是给那 9 个角色开 rd 权限: 研发样品/报价是研发数据, 不该对生产一线开放。
      // ⚠️ router/index.ts 里那 5 条 /rd 路由的 meta.module 同步改了 —— 菜单只挡入口,
      //    报价单/三价对比/中间报价是从样品页跳进去的, 不改路由的话深链照样进得去。
      { path: '/rd/samples', title: '研发样品', icon: '', module: 'rd' },
      { path: '/analytics/production-report', title: '车间实时生产报表', icon: '', module: 'analytics',
        groupLabel: '生产分析', hideForFactoryTypes: ['RESTAURANT', 'FACTORY'] },
      { path: '/production-analytics/production', title: '生产数据分析', icon: '', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT', 'FACTORY'] },
      { path: '/production/bom-achievement', title: 'BOM达成率分析', icon: '', module: 'production' },
      { path: '/production/process-io', title: '工序投入产出对比', icon: '', module: 'production' },
      // SP9: 人效双口径对比 (报价 quotedLaborCost vs 实际 actualLaborCost)
      { path: '/production/labor-efficiency', title: '人效双口径对比', icon: '', module: 'production' },
      { path: '/production-analytics/yield-cost', title: '成品出厂核算', icon: '', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT', 'FACTORY'] },
      { path: '/production-analytics/cost-summary', title: '成本汇总', icon: '', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT', 'FACTORY'] },
    ]
  },
  {
    // P1-5: restaurants 默认不见 "仓储管理" (食材库存在 /restaurant/stocktaking)
    // UX P2-5: 合并"调拨管理" 1-item 组到这里,减少顶级菜单数
    path: '/warehouse', title: '仓储管理', icon: 'Box', module: 'warehouse',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/warehouse/unordered-inbound-applications', title: '无订单入库申请', icon: '', module: 'warehouse', groupLabel: '仓储作业', badgeKey: 'unorderedInbound' },
      { path: '/warehouse/materials', title: '入库任务与批次', icon: '', module: 'warehouse', badgeKey: 'warehouseReceiving' },
      { path: '/warehouse/shipments', title: '出货管理', icon: '', module: 'warehouse' },
      { path: '/transfer/list', title: '调拨单', icon: '', module: 'inventory' },  // 🔴 2026-08-22: warehouse -> inventory, 端点要 inventory:write, 与 /rd/* 同形
      // SP7 六扇门 ERP-lite 报损管理 (仓库→财务 / 生产→厂长 双轨)
      { path: '/warehouse/wastage-reports', title: '报损管理', icon: '', module: 'warehouse' },
      { path: '/inventory/by-warehouse', title: '分仓库存查询', icon: '', module: 'warehouse', groupLabel: '库存盘点' },
      // F006 六膳门 — 总库存查询 (工厂级原料总库存, 按物料聚合, 跨所有仓库)
      { path: '/warehouse/inventory-total', title: '总库存查询', icon: '', module: 'warehouse' },
      { path: '/warehouse/inventory', title: '库存批次查询', icon: '', module: 'warehouse' },
      { path: '/warehouse/stocktakes', title: '盘点与期初库存', icon: '', module: 'warehouse' },
      // 半成品库存 / 半成品盘点 —— 2026-08-27 补入口。此前菜单零登记, 只能手打 URL,
      //   而生产上一直在写: 半成品盘点单 29 / 盘点明细 4916 / 半成品库存 8,
      //   最近写入 2026-08-25。⇒ 数据在流, 界面看不到 (与会计凭证、标签识别诊断同形)。
      { path: '/warehouse/semi-finished', title: '半成品库存', icon: '', module: 'warehouse' },
      { path: '/warehouse/semi-finished-stocktakes', title: '半成品盘点', icon: '', module: 'warehouse' },
      // 2026-08-25: PR#76 上线了 /warehouse/storage-locations 但从未登记进本菜单,
      // 结果全生产 0 个租户配置过仓储空间 —— 没有入口就没人找得到。
      { path: '/warehouse/storage-locations', title: '仓储空间与现场盘点', icon: '', module: 'warehouse', groupLabel: '仓储配置' },
      { path: '/warehouse/material-types', title: '原料类型字典', icon: '', module: 'warehouse' },
      { path: '/warehouse/material-segments', title: '物料分类字典', icon: '', module: 'warehouse' },
      { path: '/warehouse/material-price-trend', title: '物料均价趋势', icon: '', module: 'warehouse', groupLabel: '仓储分析' },
    ]
  },
  {
    // P1-5: restaurants 默认不见 "质量管理" (食品安全走 食检,不是 ISO 质检)
    path: '/quality', title: '质量管理', icon: 'Checked', module: 'quality',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/quality/inspections', title: '质检记录', icon: '', module: 'quality', groupLabel: '质量检验' },
      { path: '/quality/label-qc', title: '包装标签拍检', icon: '', module: 'quality' },
      // Sprint4-H Q-PROCESS-1: 工序质检不良闭环
      { path: '/quality/defects', title: '工序质检不良', icon: '', module: 'quality' },
      // Sprint4-H Q-RETURN-1: 质检退回单 (上游退回, 不含客户销售退货)
      { path: '/quality/returns', title: '质检退回单', icon: '', module: 'quality' },
      {
        path: '/quality/disposition', title: '不良品处置', icon: '', module: 'quality', groupLabel: '处置闭环',
        roles: ['quality_manager', 'factory_super_admin', 'platform_admin', 'permission_admin'],
      },
      { path: '/quality/disposals', title: '废弃处理', icon: '', module: 'quality' },
      { path: '/quality/standards', title: '质检标准', icon: '', module: 'quality', groupLabel: '质量配置' }
    ]
  },
  {
    // P1-5 fix: restaurants 默认不见 "采购管理" (进货走 /restaurant/requisitions)
    // Canvas 管理员可细粒度开启 purchase_order 码解锁.
    path: '/procurement', title: '采购管理', icon: 'ShoppingCart', module: 'procurement',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/procurement/orders', title: '采购订单', icon: '', module: 'procurement', groupLabel: '采购执行' },
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
      { path: '/sales/shipments', title: '出货记录', icon: '', module: 'sales',
        hideForFactoryTypes: ['RESTAURANT'] },
      // T-RTA fix (audit B2 BLOCKER 2026-05-13): /sales/returns route was added by
      // PR #549 but NEVER surfaced in sidebar — customer service / finance roles
      // had no menu entry, only "申请退货" button on individual sales order detail.
      // Now discoverable for 历史退货 review + status-tracking workflows.
      { path: '/sales/returns', title: '销售退货', icon: '', module: 'sales' },
      // #739/#746: 销售方向付款申请 (退款/返利/销售费用) — roles 镜像 router/index.ts SalesPaymentRequests
      { path: '/sales/payment-requests', title: '销售付款申请', icon: '', module: 'sales',
        roles: ['factory_super_admin', 'platform_admin', 'sales_manager', 'salesperson', 'finance_manager', 'cashier'] },
      { path: '/sales/vehicles', title: '车辆字典', icon: '', module: 'sales', groupLabel: '销售配置',
        hideForFactoryTypes: ['RESTAURANT'] }
    ]
  },
  {
    // P1-5: restaurants 默认不见 "人事管理" (小连锁常无 HR 系统,Canvas 可开启 hr_employee)
    path: '/hr', title: '人事管理', icon: 'User', module: 'hr',
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/system/users', title: '账号管理', icon: '', module: 'system', groupLabel: '账号权限' },
      { path: '/system/roles', title: '角色权限', icon: '', module: 'system' },
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
    hideForFactoryTypes: ['RESTAURANT'],
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
    hideForFactoryTypes: ['RESTAURANT'],
    children: [
      { path: '/finance/adjustments', title: '调整审批', icon: '', module: 'finance', groupLabel: '审核队列' },
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
      { path: '/finance/gross-margin-redline', title: '毛利红线配置', icon: '', module: 'finance', groupLabel: '财务配置' }
    ]
  },
  // UX P2-5 merged into 生产管理: /rd 原独立顶级组 (1 项), 研发样品并入生产
  {
    path: '/system', title: '系统管理', icon: 'Setting', module: 'system',
    children: [
      { path: '/system/logs', title: '操作日志', icon: '', module: 'system', groupLabel: '系统运维' },
      { path: '/system/settings', title: '系统设置', icon: '', module: 'system', groupLabel: '工厂配置' },
      { path: '/system/approval-chains', title: '审批链配置', icon: '', module: 'system' },
      { path: '/system/workflow-designer', title: '工作流设计器', icon: '', module: 'system' },
      { path: '/system/features', title: '功能模块配置', icon: '', module: 'system' },
      // Apr 18 2026 bug #48: Canvas 编辑器 router 限制 roles, sidebar 跟上不让 dispatcher/
      // 其他 system:read 角色看到菜单 (否则点进去 /403 体验差)
      { path: '/canvas-editor', title: 'Canvas 配置编辑器', icon: '', module: 'system', roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] },
      { path: '/system/badge-generator', title: '员工工牌生成', icon: '', module: 'system',
        hideForFactoryTypes: ['RESTAURANT'] },
      // T123: 计量单位字典 — 客户要求"留个给我自己修改"的单位配置模块
      // 后端: GET/POST/PUT/DELETE /api/mobile/{factoryId}/system-config/units
      { path: '/unit-dictionary', title: '计量单位字典', icon: '', module: 'system',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] },
      // 操作日志 —— 2026-08-27 补入口。审计用途, 生产已有 758 条且 2026-08-25 仍在写,
      //   而此前【没有任何界面能查】。审计记录查不到, 等于没有审计。
      { path: '/system/operation-log', title: '操作日志', icon: '', module: 'system' },
      { path: '/system/ai-intents', title: 'AI意图配置', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY, groupLabel: '平台治理' },
      { path: '/system/skill-tools', title: 'Skill/Tool治理', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      { path: '/system/llm-usage', title: 'LLM 用量监控', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
      // 卡5 (2026-07-28 飞轮回接方案 P4 批): 飞轮运营台 5 个子页, 单一菜单入口跳总览, 页内 tab 切换其余 4 页.
      { path: '/system/ai-flywheel', title: 'AI 飞轮运营台', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY },
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
      { path: '/calibration/list', title: '行为校准', icon: '', module: 'system', roles: PLATFORM_ADMIN_ONLY,
        hideForFactoryTypes: ['RESTAURANT'] },
      // 餐饮 Phase A A-3 Task 3.5: data quality queue admin page
      { path: '/system/data-quality-queue', title: '数据质量队列', icon: '', module: 'system',
        roles: PLATFORM_ADMIN_ONLY }
    ]
  },
  {
    path: '/scheduling', title: '智能调度', icon: 'Calendar', module: 'scheduling',
    children: [
      { path: '/scheduling/logistics/workbench', title: '排线工作台', icon: '', module: 'scheduling', groupLabel: '日常调度',
        hideForFactoryTypes: ['FACTORY', 'RESTAURANT'] },
      { path: '/scheduling/logistics/records', title: '调度记录', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['FACTORY', 'RESTAURANT'] },
      { path: '/scheduling/logistics/orders', title: '门店与订单', icon: '', module: 'scheduling', groupLabel: '基础资料',
        hideForFactoryTypes: ['FACTORY', 'RESTAURANT'] },
      { path: '/scheduling/logistics/resources', title: '车辆与司机', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['FACTORY', 'RESTAURANT'] },
      { path: '/scheduling/logistics/stores', title: '门店库', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['FACTORY', 'RESTAURANT'] },
      { path: '/scheduling/overview', title: '调度中心', icon: '', module: 'scheduling', groupLabel: '工厂智能调度',
        hideForFactoryTypes: ['LOGISTICS'] },
      { path: '/scheduling/plans', title: '调度计划', icon: '', module: 'scheduling',
        hideForFactoryTypes: ['LOGISTICS'] },
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
    // UX 2026-06-02 IA v2: 餐饮组重组为 3 层 (深度分析/日常录入/数据与系统)。
    // 运营总览移除 (Excel 浏览器病症); 经营驾驶舱复用「数据与分析」组 /smart-bi/dashboard
    // (业态自适应, 不重复造); 菜品四象限+毛利合并为 菜品分析双tab; 点评口碑保留显性入口。
    // spec: 2026-06-01-restaurant-web-admin-ia-redesign-design.md v2。
    path: '/restaurant', title: '餐饮运营', icon: 'KnifeFork', module: 'dashboard',
    // 2026-07-31: 补上 owner / purchaser / chef。`roles` 是**允许式白名单**
    // (AppSidebar.canSeeMenuItem: 写了就一票否决), 此前这三个角色即使模块权限
    // 给对了也看不见餐饮组 —— 权限有两个承载点, #2082/#2083 只改了矩阵那一个。
    roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'restaurant_manager', 'restaurant_owner', 'hr_admin', 'restaurant_purchaser', 'restaurant_chef', 'warehouse_manager', 'procurement_manager', 'finance_manager', 'sales_manager'],
    hideForFactoryTypes: ['FACTORY'],
   
    children: [
      // -- 部门驾驶舱 (2026-07-31) --
      // 不写 roles: 由 module 权限门控即可(四个部门键各自决定谁能看见), 再叠一层
      // 角色白名单只会变成第二处要同步的地方 —— 那正是 #2084 修的那个坑。
      { path: '/restaurant/ops', title: '运营', icon: 'Bowl', module: 'restaurantOps', groupLabel: '部门驾驶舱' },
      { path: '/restaurant/marketing', title: '市场', icon: 'TrendCharts', module: 'restaurantMarketing' },
      // 采购 2026-08-06 独立成第五个部门 (Steve 拍板)。同样不写 roles, 由 module 门控。
      { path: '/restaurant/procurement', title: '采购', icon: 'ShoppingCart', module: 'restaurantProcurement' },
      { path: '/restaurant/hr', title: '人事', icon: 'User', module: 'restaurantHr' },
      { path: '/restaurant/staffing', title: '预测排班', icon: 'Calendar', module: 'restaurantHr',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'restaurant_manager', 'restaurant_owner', 'hr_admin'] },
      { path: '/restaurant/finance', title: '财务', icon: 'Money', module: 'restaurantFinance' },
      // ── 运营 ────────────────────────────────────────────────────
      // 后厨供应链: 领料 / 损耗 / 盘点 / 配方
      { path: '/restaurant/requisitions', title: '领料管理', icon: '', module: 'restaurantOps', groupLabel: '运营' },
      { path: '/restaurant/wastage', title: '损耗管理', icon: '', module: 'restaurantOps' },
      { path: '/restaurant/stocktaking', title: '盘点管理', icon: '', module: 'restaurantOps' },
      { path: '/restaurant/recipes', title: '配方管理', icon: '', module: 'restaurantOps' },
      // ⚠️ 下面两项**刻意不改 module**: 它们跨工厂/餐饮两侧使用
      // (warehouse_manager / procurement_manager 也在用, 而这些角色 restaurant='-')。
      // 改成 restaurantOps 会断掉他们的访问 —— 本轮只归组, 标签留待单独评估。
      // ⛔ 这里曾写着「2026-08-06 Steve 拍板: 采购职责并入市场(sales_manager),
      //    厨师长/餐饮采购退役」并据此摘掉了 restaurant_purchaser —— **那一侧是败的**。
      //    同日更晚的 V20261029_58/_63 把采购立为**第五个部门**, 载体角色就是
      //    restaurant_purchaser, 权威 departmentConfig.ts 也把本页列为采购部门的
      //    第一个动作。两处口径打架, 用户可见后果是采购部长点自己部门的动作打不开。
      //    2026-08-07 按五部门口径收口, 把它加回来。
      // module 仍不改(见上方 ⚠️): sales_manager / restaurant_purchaser 都有 dashboard:r。
      { path: '/restaurant/supplier-delivery', title: '供应商进货录入', icon: '', module: 'dashboard',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'restaurant_manager', 'restaurant_owner', 'sales_manager', 'restaurant_purchaser', 'warehouse_manager', 'procurement_manager'] },
      // ⚠️ 本项 module='procurement' 而 sales_manager **没有** procurement 权限,
      // 所以即使加进 roles 也会被 module 闸挡在前面(守卫先查 module 后查 roles)。
      // 餐饮侧要不要保留这条、还是改走 /restaurant/requisitions(module=restaurantOps,
      // 店长已有), 待定 —— 本轮只摘掉两个退役角色, 不擅自改 module 断工厂侧。
      // restaurant_purchaser 同样是被上面那条败的注释一起摘掉的 —— 报货/采购计划
      // 正是采购部门的活。V20261029_64 给了它 procurement:'rw', module 闸能过。
      { path: '/procurement/requisitions/my', title: '报货/采购计划', icon: '', module: 'procurement',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'restaurant_manager', 'restaurant_owner', 'restaurant_purchaser', 'warehouse_manager', 'procurement_manager'] },

      // ── 市场 ────────────────────────────────────────────────────
      // 经营看板归市场: 6 KPI 里日营收/客单价/订单数三项是营收侧, 占多数。
      { path: '/restaurant/analytics/role-kpi', title: '经营看板', icon: '', module: 'restaurantMarketing', groupLabel: '市场' },
      { path: '/restaurant/analytics/dishes', title: '菜品分析', icon: '', module: 'restaurantMarketing' },
      { path: '/restaurant/analytics/stores', title: '门店对比', icon: '', module: 'restaurantMarketing' },
      { path: '/restaurant/analytics/platform', title: '平台分析', icon: 'ChatDotRound', module: 'restaurantMarketing' },
      // 🔴 2026-08-07: 这一项此前**有路由、有组件、但没有菜单入口, 也没有任何
      // 其它页面链接过去** —— 用户到不了。与 `/dashboard/ai-value` 同一类缺陷,
      // 是可达性扫描扫出来的第二个。
      // ⚠️ 我第一版写的是 module: 'restaurant'(跟随路由 meta 的板块准入),
      // 被 `restaurantMenuRouteAlignment.spec.ts` 当场判红 —— 那条契约要求
      // **每个餐饮功能页都挂在五个部门之一**。营销员提成属市场。
      // 菜单可见性(restaurantMarketing)比守卫放行(restaurant)窄是可以的:
      // 部门隔离本来就只做 UI 级防误点, 菜单是入口不是闸。
      { path: '/restaurant/commission', title: '营销员提成', icon: '', module: 'restaurantMarketing' },

      // ── 财务 ────────────────────────────────────────────────────
      // 三项都是金额口径, 而 restaurantFinance 的准入本身就要求 PRICE_VIEW_ROLES,
      // 所以不必再逐项写 roles 白名单 —— 少一处要同步的地方。
      { path: '/restaurant/cost-attribution', title: '成本归因', icon: '', module: 'restaurantFinance', groupLabel: '财务' },
      { path: '/restaurant/supplier-reconciliation', title: '供应商月对账', icon: '', module: 'restaurantFinance' },
      { path: '/restaurant/price-anomaly', title: '价格异常预警', icon: '', module: 'restaurantFinance' },

      // ── 数据与系统 ──────────────────────────────────────────────
      // 跨部门的数据基础设施, 不归属任何业务部门 —— 归进某一个部门会让其余三个
      // 部门的分析师找不到它。
      { path: '/restaurant/data-completeness', title: '数据完整度', icon: '', module: 'restaurant', groupLabel: '数据与系统' },
      { path: '/restaurant/admin/etl-status', title: 'ETL 状态', icon: '', module: 'restaurant',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] },
      { path: '/restaurant/admin/name-resolution', title: '菜品名称匹配', icon: '', module: 'restaurant',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin'] }
    ]
  },
  {
    // UX 2026-06-01: 合并「经营报表」(/analytics) + 「智能分析」(/smart-bi) 为单一
    // 「数据与分析」组 (spec 2026-06-01-web-admin-analytics-ia-redesign-design.md)。
    // WS4 (2026-06-02): 财务看板/销售分析/趋势分析/KPI看板/指标中心 6 页合并为单一
    // 「经营分析」(BusinessAnalysisHub, 4 tab) — 菜单项移除, 由 redirect 桥接。
    // 删 AI分析报告 (#9)。AI 运维 3 项收 platform_admin/permission_admin 门控 (移 admin 区)。
    // 经营驾驶舱置顶主入口。各页后端不变 (部分页 Java reports + Python 混合)。
    // 🔴 2026-08-22 owner:「数据分析全部隐藏, 工厂暂时用不着」
    // ⇒ 整组对**工厂租户**隐藏(餐饮不受影响 —— 那是另一个体系)。
    // ⚠️ 菜单级隐藏, ⛔ 不等于关权限: analytics 模块未动, 手输 URL 仍进得去。
    //    owner 说的是**暂时**, 所以选可逆的这一档。
    path: '/smart-bi', title: '数据与分析', icon: 'TrendCharts', module: 'analytics', hideForFactoryTypes: ['FACTORY'],
   
    children: [
      // ★ 主入口 (无 groupLabel, 置顶)
      { path: '/smart-bi/dashboard', title: '经营驾驶舱', icon: 'Monitor', module: 'analytics',
        restaurantRoles: RESTAURANT_ANALYTICS_ALL },
      // -- AI 探索 --
      // P3: /smart-bi/query 已合并入此页 query tab (redirect /smart-bi/query → /smart-bi/analysis?tab=query), 菜单项移除
      { path: '/smart-bi/analysis', title: 'AI 问答 / 数据分析', icon: 'DataAnalysis', module: 'analytics', groupLabel: 'AI 探索',
        restaurantRoles: RESTAURANT_ANALYTICS_ALL },
      // -- 经营分析 --
      // WS4: 财务/销售/趋势/KPI·指标 合并为单一 hub。旧路径 (financial-dashboard/sales/
      // trends/kpi/indicator-center) 由 router redirect 保书签 → /smart-bi/analysis-hub?tab=。
      { path: '/smart-bi/analysis-hub', title: '经营分析', icon: 'TrendCharts', module: 'analytics', groupLabel: '经营分析',
        restaurantRoles: RESTAURANT_ANALYTICS_DECISION },
      { path: '/smart-bi/revenue-report', title: '收入管理报表', icon: 'Money', module: 'analytics',
        roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'finance_manager', 'restaurant_manager', 'restaurant_owner'],
        restaurantRoles: [...RESTAURANT_ANALYTICS_DECISION, 'finance_manager'],
        hideForFactoryTypes: ['FACTORY'] },
      { path: '/smart-bi/health-report', title: 'AI 经营体检', icon: 'FirstAidKit', module: 'analytics',
        restaurantRoles: RESTAURANT_ANALYTICS_DECISION,
        hideForFactoryTypes: ['FACTORY'] },
      { path: '/analytics/alert-dashboard', title: '异常预警', icon: 'Warning', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT'] },
      { path: '/analytics/supply-chain', title: '进销存总览', icon: 'Histogram', module: 'analytics',
        hideForFactoryTypes: ['RESTAURANT'] },
      // -- 数据管理 --
      { path: '/smart-bi/upload', title: 'Excel 上传', icon: 'Upload', module: 'analytics', groupLabel: '数据管理',
        restaurantRoles: RESTAURANT_ANALYTICS_STEWARDS },
      { path: '/smart-bi/query-templates', title: '查询模板', icon: 'Tickets', module: 'analytics',
        restaurantRoles: RESTAURANT_ANALYTICS_STEWARDS },
      { path: '/smart-bi/data-completeness', title: '数据完整度', icon: 'DataAnalysis', module: 'analytics',
        restaurantRoles: RESTAURANT_ANALYTICS_STEWARDS },
      // Phase 0: 字段映射复核 — Excel 列无法自动映射时由人工确认并写入 pin (2026-06-15)
      { path: '/smart-bi/mapping-review', title: '字段映射复核', icon: 'EditPen', module: 'analytics',
        restaurantRoles: RESTAURANT_ANALYTICS_STEWARDS },
      // D-6 保守保留: 分析概览 (与驾驶舱/hub 重叠但数据源不同, P5 凭埋点再决定真删)
      { path: '/analytics/overview', title: '分析概览', icon: 'DataAnalysis', module: 'analytics',
        restaurantRoles: RESTAURANT_ANALYTICS_DECISION },
      // -- AI 运维 (admin) — WS4: 收 admin 门控 (普通经营用户不需要) --
      { path: '/smart-bi/food-kb-feedback', title: '知识库反馈', icon: 'ChatDotRound', module: 'analytics', groupLabel: 'AI 运维',
        roles: ['platform_admin', 'permission_admin', 'factory_super_admin'] },
      { path: '/smart-bi/fallback-log', title: 'AI 追问日志', icon: 'DataLine', module: 'analytics',
        roles: ['platform_admin', 'permission_admin', 'factory_super_admin'] },
      { path: '/smart-bi/calibration', title: '行为校准监控', icon: 'Aim', module: 'analytics', roles: ['platform_admin'] },
    ]
  },
  {
    // CRM P0「会员与营销」(2026-07-11): 首个 top-level 入口, 会员分析 (RFM 客群分层 +
    // 三维散点 + 生命周期 + 会员画像). Scope-minimal: 只加这一个新顶级组, 不重排其它模块
    // (完整 5-模块 IA 重构是后续 follow-up)。仅餐饮/demo 租户可见 — 与 revenue-report /
    // health-report 同一套 hideForFactoryTypes + roles 白名单 (会员储值/消费金额敏感)。
    path: '/crm', title: '会员与营销', icon: 'User', module: 'analytics',
    hideForFactoryTypes: ['FACTORY'],
    roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'finance_manager', 'restaurant_manager'],
    children: [
      { path: '/crm/member-analysis', title: '会员分析', icon: '', module: 'analytics' },
    ]
  },
  {
    // 运营分析 (2026-07-12): 撤单稽核 + 区域坪效, 从 经营驾驶舱(RestaurantGoldGrid)
    // 迁出的独立顶级组 (驾驶舱瘦身)。与 /crm 同一批 hybrid bullet-point analysis
    // pattern (确定性 bullets + AI 解读 + 整页分析面板), 同一套门控 (会员储值/
    // 撤单金额同样敏感)。Scope-minimal: 只加这一个新顶级组, 不重排其它模块。
    path: '/ops', title: '运营分析', icon: 'Operation', module: 'analytics',
    hideForFactoryTypes: ['FACTORY'],
    roles: ['factory_super_admin', 'platform_admin', 'permission_admin', 'finance_manager', 'restaurant_manager'],
    children: [
      { path: '/ops/operations-analysis', title: '运营分析', icon: '', module: 'analytics' },
    ]
  }
];

const TOP_LEVEL_FLOW_ORDER: Record<string, number> = {
  '/dashboard': 10,
  '/workdesk': 20,
  '/workflow': 25,
  '/procurement': 30,
  '/sales': 40,
  '/finance': 50,
  '/production': 60,
  '/warehouse': 70,
  '/quality': 80,
  '/smart-bi': 90,
  '/system': 100,
  '/scheduling': 110,
  '/hr': 120,
  '/equipment': 130,
  '/restaurant': 140,
  '/ops': 143,
  '/crm': 145,
};

function sortTopLevelMenu(items: MenuItem[]): MenuItem[] {
  return [...items].sort((a, b) => {
    const left = TOP_LEVEL_FLOW_ORDER[a.path] ?? 1000;
    const right = TOP_LEVEL_FLOW_ORDER[b.path] ?? 1000;
    return left - right;
  });
}

export const menuConfig: MenuItem[] = sortTopLevelMenu(rawMenuConfig);
