import { formatDate } from '@/utils/dateFormat';

/**
 * 入库记录的「入库时间」取值。
 *
 * 后端 MaterialBatchDTO 只输出 receiptDate / inboundDate（二者同值，LocalDate，仅日期），
 * 从不输出 receivedDate —— 之前页面读 receivedDate，线上整列恒空。
 * 无值如实显示「无入库时间」，不造日期、不退回 createdAt（那是录入时间，不是入库日）。
 */
export interface ReceiptDateSource {
  receiptDate?: string | null;
  inboundDate?: string | null;
}

export const NO_RECEIPT_DATE_TEXT = '无入库时间';

export function materialReceiptDateText(row: ReceiptDateSource): string {
  const raw = row.receiptDate || row.inboundDate;
  if (!raw) return NO_RECEIPT_DATE_TEXT;
  // LocalDate 本就是 YYYY-MM-DD，直接取前 10 位，避免经 Date 解析引入时区偏移
  const m = /^\d{4}-\d{2}-\d{2}/.exec(raw);
  if (m) return m[0];
  const text = formatDate(raw);
  return text && text !== '-' ? text : NO_RECEIPT_DATE_TEXT;
}
