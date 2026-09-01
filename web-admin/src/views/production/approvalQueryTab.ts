/**
 * 报工审批 hub tab 解析 —— LIUSHANMEN 生产管理 IA 收拢 (owner 拍板, 2026-09-02)。
 *
 * 把「报工审批」+「撤回审批」合并到一个入口 (仍是 /production/approval，⛔ 路径不变 ——
 * 见下方「为什么路径不能是新的」)。?tab= query 同步，与 inventoryQueryTab.ts /
 * stocktakeQueryTab.ts (LIUSHANMEN 仓储收拢) 同模式 —— 纯函数，方便单测。
 *
 * 🔴 为什么路径不能是新的: /production/approval 是
 * reportApprovalMenuMatchesBackendRoles.spec.ts 的判定目标 (原按精确路径字符串在
 * menuConfig 里查找菜单项)。换成新路径会让那道闸再也找不到菜单项，闸的意图落空。
 * ⇒ hub 复用旧路径, 旧的「撤回审批」路径 (/production/reversals) 反过来重定向进来。
 *
 * 🔴 2026-09-02 二次修正 (coordinator 复核 #487 指出的回归):
 *
 * 第一版把 roles[] 原样搬到 menuConfig 的 hub 菜单项上 (`/production/approval`
 * 整条带 9 角色白名单)。这是错的 —— **roles[] 原本只属于「报工审批」一个视角**，
 * 合并前「撤回审批」是 module:'production' 无 roles[] 门控 (谁有 production 模块
 * 权限就看得到)。把它套在整个 hub 上, 会让原本能看「撤回审批」但不在 9 角色白名单
 * 里的角色 (如 dispatcher, production:'rw') **连菜单项都看不到** —— 这是删功能,
 * 不是可登记的副作用。
 *
 * ⇒ 修正: roles[] 挪进本文件 (APPROVAL_TAB_ROLES), 只门控「报工审批」这一个 tab
 * 的**渲染** (hub 组件内部 v-if, 见 views/production/approval-hub/index.vue)。
 * menuConfig 里的 hub 菜单项本身**不再带 roles[]**, 退回到与合并前「撤回审批」
 * 一致的最广条件 (module:'production') —— hub 菜单项可见性 = 两个 tab 可见性的
 * 并集: 「撤回审批」的可见集合(任何有 production 模块权限的角色)已经是「报工审批」
 * 9 角色白名单的超集, 并集就是「撤回审批」那个更宽的集合, 所以菜单层不需要
 * (也不应该) 再叠一层 roles[]。
 *
 * reportApprovalMenuMatchesBackendRoles.spec.ts 现在断言的是 **APPROVAL_TAB_ROLES**
 * (本文件导出), 不再是 menuConfig 菜单项的 roles[] 字段 —— 判据本体没变 (仍从 Java
 * 源码 @RequireRole + PLATFORM_ADMIN_ROLES 推导), 只是搬了断言的落点。
 */
export type ApprovalQueryTab = 'approval' | 'reversal';

export const APPROVAL_QUERY_TABS: ApprovalQueryTab[] = ['approval', 'reversal'];

/** 默认 tab = 报工审批 (原 /production/approval 入口，合并前最常用)。 */
export const DEFAULT_APPROVAL_QUERY_TAB: ApprovalQueryTab = 'approval';

/**
 * 「报工审批」tab 的角色白名单 —— 唯一来源, menuConfig 不再重复声明。
 *
 * ① ProcessWorkReportingController#getPendingApprovals 的 @RequireRole
 * ② RequireRoleInterceptor.PLATFORM_ADMIN_ROLES(无条件放行)
 *
 * ⚠️ 两份一致由 reportApprovalMenuMatchesBackendRoles.spec.ts 钉住 (从 Java 源码
 * 推导期望值, 不手抄), 别手工改这个数组而不管那道闸。
 */
export const APPROVAL_TAB_ROLES: string[] = [
  'factory_super_admin', 'permission_admin', 'production_manager',
  'production_clerk', 'workshop_supervisor',
  'super_admin', 'platform_admin', 'developer', 'platform_super_admin',
];

/** 「报工审批」tab 对这个角色可见吗 —— 与后端 @RequireRole 判定逐字节等价。 */
export function canSeeApprovalTab(role: string | null | undefined): boolean {
  return !!role && APPROVAL_TAB_ROLES.includes(role);
}

/**
 * 从 ?tab= query 解析激活 tab。
 *
 * @param query      路由 query
 * @param role       当前角色 (usePermissionStore().currentRole) —— 用于判断
 *                   「报工审批」tab 对这个角色是否可见。不传时按可见处理 (向后兼容
 *                   纯函数调用方, 与 productionAnalysisTab.ts 的 factoryType 参数
 *                   同一处理方式)。
 *
 * 非法 tab、或请求的 tab 对当前角色不可见 → 回落到该角色**能看到的第一个 tab**
 * (⛔ 不能无条件默认 'approval' —— 那会让 dispatcher 之类的角色落地到一个空白/
 * 隐藏的 tab-pane 上, 是 2026-09-02 coordinator 复核指出的第二个坑)。
 */
export function resolveApprovalQueryTab(
  query: Record<string, unknown> | null | undefined,
  role?: string | null,
): ApprovalQueryTab {
  // role 未传 (调用方没有身份上下文) → 按可见处理, 不收窄默认值 (向后兼容)。
  // role 传了 (含 null/'') → 严格按 canSeeApprovalTab 判定, 不可见就回落。
  const canApproval = role === undefined ? true : canSeeApprovalTab(role);
  const fallback: ApprovalQueryTab = canApproval ? 'approval' : 'reversal';

  const t = query?.tab;
  if (!APPROVAL_QUERY_TABS.includes(t as ApprovalQueryTab)) return fallback;
  const tab = t as ApprovalQueryTab;
  if (tab === 'approval' && !canApproval) return 'reversal';
  return tab;
}
