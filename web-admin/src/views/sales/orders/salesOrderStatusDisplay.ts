/**
 * 销售订单状态的中文名 + 徽标语义色。
 * 措辞与语义必须与 orders/list.vue 的 statusMap 一致(那份在 3509 行的 SFC 里没法 import,
 * 由 __tests__/salesOrderStatusDisplay.spec.ts 逐项钉住「两份一致」, 订单列表迁移时再合并成这一份)。
 */
import type { ElementTagType } from '@/utils/csBadge';

export const SALES_ORDER_STATUS_DISPLAY: Record<string, { text: string; type: ElementTagType }> = {
  DRAFT: { text: '草稿', type: 'info' },
  CONFIRMED: { text: '已确认', type: '' },
  PENDING_FINANCE_REVIEW: { text: '待 OA 审批', type: 'warning' },
  FINANCE_APPROVED: { text: '审批已通过', type: 'success' },
  FINANCE_REJECTED: { text: '审批已驳回', type: 'danger' },
  PROCESSING: { text: '处理中', type: 'warning' },
  PARTIAL_DELIVERED: { text: '部分发货', type: 'warning' },
  COMPLETED: { text: '已完成', type: 'success' },
  CANCELLED: { text: '已取消', type: 'danger' },
  SHORT_CLOSED: { text: '短交结案', type: 'info' },
};

export const UNKNOWN_SALES_ORDER_STATUS = '未知状态';

/** 未知/空状态只显示「未知状态」, 不把后端枚举原文露给用户。 */
export function salesOrderStatusText(status: unknown): string {
  return SALES_ORDER_STATUS_DISPLAY[String(status ?? '')]?.text ?? UNKNOWN_SALES_ORDER_STATUS;
}

export function salesOrderStatusTagType(status: unknown): ElementTagType {
  return SALES_ORDER_STATUS_DISPLAY[String(status ?? '')]?.type ?? 'info';
}
