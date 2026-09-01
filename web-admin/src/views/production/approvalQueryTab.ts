/**
 * 报工审批 hub tab 解析 —— LIUSHANMEN 生产管理 IA 收拢 (owner 拍板, 2026-09-02)。
 *
 * 把「报工审批」+「撤回审批」合并到一个入口 (仍是 /production/approval，⛔ 路径不变 ——
 * 见下方「为什么路径不能是新的」)。?tab= query 同步，与 inventoryQueryTab.ts /
 * stocktakeQueryTab.ts (LIUSHANMEN 仓储收拢) 同模式 —— 纯函数，方便单测。
 *
 * 🔴 为什么路径不能是新的: /production/approval 的 menuConfig 项带着一份 roles[]
 * 白名单 (#450，与后端 ProcessWorkReportingController#getPendingApprovals 的
 * @RequireRole + RequireRoleInterceptor.PLATFORM_ADMIN_ROLES 逐字对齐)，由
 * reportApprovalMenuMatchesBackendRoles.spec.ts 按**这个精确路径字符串**查找菜单项。
 * 换成新路径会让那道闸再也找不到菜单项 (findMenuItem 返回 null)，闸的意图落空。
 * ⇒ hub 复用旧路径, 旧的「撤回审批」路径 (/production/reversals) 反过来重定向进来。
 *
 * ⚠️ 副作用 (已知, 详见 PR 说明): 撤回审批列表接口 (ReportReversalController#listReversals)
 * 只要求 production:read_write 模块权限, 比报工审批的 5 角色白名单更宽 —— 例如
 * dispatcher 有 production:'rw' 却不在白名单里。合并前 dispatcher 能看到「撤回审批」
 * 菜单项 (module:'production' 无 roles[])，合并后连同 hub 一起被 roles[] 挡住。
 * 这是刻意的取舍: reportApprovalMenuMatchesBackendRoles.spec.ts 明确要求 roles[]
 * 不能被拓宽 (拓宽 = quality_manager 那类角色重新看得见、点进去 403 的缺陷 42 复发)。
 */
export type ApprovalQueryTab = 'approval' | 'reversal';

export const APPROVAL_QUERY_TABS: ApprovalQueryTab[] = ['approval', 'reversal'];

/** 默认 tab = 报工审批 (原 /production/approval 入口，合并前最常用)。 */
export const DEFAULT_APPROVAL_QUERY_TAB: ApprovalQueryTab = 'approval';

/**
 * 从 ?tab= query 解析激活 tab。非法/缺省 → 默认 approval。
 */
export function resolveApprovalQueryTab(query: Record<string, unknown> | null | undefined): ApprovalQueryTab {
  const t = query?.tab;
  return APPROVAL_QUERY_TABS.includes(t as ApprovalQueryTab)
    ? (t as ApprovalQueryTab)
    : DEFAULT_APPROVAL_QUERY_TAB;
}
