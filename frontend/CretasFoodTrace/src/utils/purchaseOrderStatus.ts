/**
 * 采购单状态显示 —— App 侧单一来源。
 *
 * 背景 (测试反馈 2026-08-12「审批后状态直接变成财务已审核」):
 *
 * 后端 `PurchaseServiceImpl.projectWorkflowState` 把 OA 实例的 `APPROVED`
 * 直接投影成 `PurchaseOrderStatus.FINANCE_APPROVED` —— 因为收货门禁认的就是
 * 这个状态。工厂没配财务节点时(F006 的链在金额 <= 30000 时走 `end_auto`,
 * 一个人工节点都不经过), 单据照样落到 FINANCE_APPROVED, 但
 * **没有任何人做过财务审核**, 此时后端不写 `financeReviewedBy` (#2660)。
 *
 * web-admin 在 #2660 里已经把这一层说清楚了
 * (`web-admin/src/views/procurement/orders/detail.vue` 的 `purchaseStatusText`),
 * 但 App 侧当时没跟上:
 *   · 两块屏的 STATUS_MAP 里**根本没有** FINANCE_APPROVED / PENDING_FINANCE_REVIEW /
 *     WORKFLOW_RUNNING, 于是 `STATUS_MAP[item.status] || { label: item.status }`
 *     的兜底把**原始枚举串**显示给用户;
 *   · OA 待办详情页显示的是「财务已审」, 而财务并没有审过 —— 就是在骗人。
 *
 * ⚠️ 本文件是 App 侧采购状态文案的唯一定义。两块屏都从这里取,
 * 不要在屏里再抄一份 (抄一份就一定会漂)。
 */

export interface PurchaseOrderStatusView {
  label: string;
  color: string;
}

/** 与后端 `PurchaseOrderStatus` 枚举一一对应。 */
export const PURCHASE_ORDER_STATUS_MAP: Record<string, PurchaseOrderStatusView> = {
  DRAFT: { label: '草稿', color: '#909399' },
  SUBMITTED: { label: '已提交', color: '#e6a23c' },
  WORKFLOW_RUNNING: { label: '审批中', color: '#e6a23c' },
  APPROVED: { label: '已审批', color: '#409eff' },
  PENDING_FINANCE_REVIEW: { label: '待财务审核', color: '#e6a23c' },
  FINANCE_APPROVED: { label: '财务已审核', color: '#67c23a' },
  FINANCE_REJECTED: { label: '财务驳回', color: '#f56c6c' },
  REJECTED: { label: '已驳回', color: '#f56c6c' },
  PARTIAL_RECEIVED: { label: '部分收货', color: '#e6a23c' },
  COMPLETED: { label: '已收货', color: '#67c23a' },
  CANCELLED: { label: '已取消', color: '#909399' },
  CLOSED: { label: '已关闭', color: '#909399' },
};

/** 没有财务节点走完时的说法 —— 单据能往下走, 但不许说「财务已审核」。 */
export const NO_FINANCE_REVIEW_LABEL = '无需财务审核（未设置审批节点）';

/**
 * 把后端状态翻译成给用户看的文案。
 *
 * `FINANCE_APPROVED` 要分两种情况说:
 *   · `financeReviewedBy` 有值 —— 财务确实批过, 显示「财务已审核」;
 *   · `financeReviewedBy` 为空 —— 链上没有财务节点, 显示「无需财务审核（未设置审批节点）」。
 *
 * 未知状态回落到原始串 (不吞掉), 但那属于契约漂移, 应当补进上面的表。
 */
export function resolvePurchaseOrderStatus(
  order: { status?: string | null; financeReviewedBy?: number | null } | null | undefined,
): PurchaseOrderStatusView {
  const status = order?.status;
  if (!status) return { label: '-', color: '#909399' };
  if (status === 'FINANCE_APPROVED' && !order?.financeReviewedBy) {
    return { label: NO_FINANCE_REVIEW_LABEL, color: '#909399' };
  }
  return PURCHASE_ORDER_STATUS_MAP[status] || { label: status, color: '#909399' };
}
