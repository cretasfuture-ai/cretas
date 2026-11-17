/**
 * 客户详情页签共用: 状态/类型中文 + 徽标颜色 + 备注里内部锚点的清洗。
 * 中文表全部来自 utils/enumDisplay.ts (不在页签里另写一套), 这里只补颜色与清洗。
 */
import {
  enumLabel,
  INVOICE_STATUS_LABELS,
  INVOICE_TYPE_LABELS,
  PAYMENT_RECORD_STATUS_LABELS,
  QUOTE_STATUS_LABELS,
  RETURN_ORDER_STATUS_LABELS,
} from '@/utils/enumDisplay';
import type { CsBadgeColor } from '@/utils/csBadge';

export const invoiceStatusText = (c: unknown) => enumLabel(c, INVOICE_STATUS_LABELS);
export const invoiceTypeText = (c: unknown) => enumLabel(c, INVOICE_TYPE_LABELS);
export const paymentStatusText = (c: unknown) => enumLabel(c, PAYMENT_RECORD_STATUS_LABELS);
export const paymentMethodText = (c: unknown) => enumLabel(c);
export const quoteStatusText = (c: unknown) => enumLabel(c, QUOTE_STATUS_LABELS);
export const returnStatusText = (c: unknown) => enumLabel(c, RETURN_ORDER_STATUS_LABELS);

const SUCCESS = new Set(['ISSUED', 'VERIFIED', 'APPROVED', 'COMPLETED', 'FINANCE_APPROVED', 'CONFIRMED', 'QUOTED']);
const WARNING = new Set(['REQUESTED', 'PENDING', 'PENDING_QUOTE', 'PENDING_APPROVAL', 'SUBMITTED', 'PROCESSING']);
const DANGER = new Set(['REJECTED', 'FINANCE_REJECTED']);

/** 通用状态码 → 徽标色: 完成/通过=绿, 待处理=橙, 驳回=红, 其余(草稿/取消/过期)=灰。 */
export function statusColor(code: unknown): CsBadgeColor {
  const c = String(code ?? '');
  if (SUCCESS.has(c)) return 'success';
  if (WARNING.has(c)) return 'warning';
  if (DANGER.has(c)) return 'error';
  return 'neutral';
}

/**
 * 退货单备注里系统写入的内部锚点 (`RECALL_EVENT_ID=2;SHIPMENT_ID=<uuid>;`) 不给用户看:
 * 只留用户自己写的那部分; 整条都是锚点时显示与退货详情页一致的说明, 没有备注显示「—」。
 * 锚点里只有内部 id、没有可读编号, 所以不换成编号。
 */
const HAS_ANCHOR = /(?:RECALL_EVENT_ID|SHIPMENT_ID)=/;
const ANCHOR = /(?:RECALL_EVENT_ID|SHIPMENT_ID)=[^;\s]*;?/g;
export function cleanRemark(raw: unknown): string {
  const text = String(raw ?? '');
  if (!text.trim()) return '—';
  if (!HAS_ANCHOR.test(text)) return text;
  const rest = text.replace(ANCHOR, '').replace(/^[\s;；,，]+|[\s;；,，]+$/g, '');
  return rest || '由批次召回登记';
}
