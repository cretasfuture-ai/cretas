import { formatDateTime } from '@/utils/dateFormat';

/**
 * 入库批次的展示层辅助 (只改显示, 不改库存状态机)。
 */

/** ISO 时间 → "YYYY-MM-DD HH:mm"; 空值 '-'; 解析不了原样返回。复用统一的 formatDateTime, 只截到分钟。 */
export function formatDateTimeMinute(value?: string | null): string {
  const full = formatDateTime(value);
  return /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(full) ? full.slice(0, 16) : full;
}

/**
 * 状态为「可用」但剩余数量读到的是 0 的批次, 显示「已用完」而不是「可用」。
 * 只在 currentQuantity 明确读到数值 0 时触发: 读不到 (null/undefined/空串) 不猜, 保持原状态。
 * 数量与状态都用后端原值, 这里不写回任何状态。
 */
export function isDepletedAvailableBatch(row: { status?: unknown; currentQuantity?: unknown } | null | undefined): boolean {
  if (!row) return false;
  if (String(row.status ?? '').toUpperCase() !== 'AVAILABLE') return false;
  const q = row.currentQuantity;
  if (q === null || q === undefined || q === '') return false;
  const n = Number(q);
  return Number.isFinite(n) && n === 0;
}

export function batchStatusDisplay(
  row: { status?: unknown; currentQuantity?: unknown } | null | undefined,
  statusText: (status: string) => string,
): { text: string; depleted: boolean } {
  if (isDepletedAvailableBatch(row)) return { text: '已用完', depleted: true };
  return { text: statusText(String(row?.status ?? '')), depleted: false };
}
