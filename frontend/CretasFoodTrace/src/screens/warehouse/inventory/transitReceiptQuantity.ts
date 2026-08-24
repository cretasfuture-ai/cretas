export interface ReceiptQuantityLine {
  reportedQuantity?: number | null;
  receivedQuantity?: number | null;
}

export function remainingReceiptQuantity(line: ReceiptQuantityLine): number {
  const reported = Number(line.reportedQuantity ?? 0);
  const received = Number(line.receivedQuantity ?? 0);
  if (!Number.isFinite(reported) || !Number.isFinite(received)) return 0;
  return Math.max(0, Math.round((reported - received) * 10_000) / 10_000);
}

export function isValidReceiptDelta(
  value: number | null,
  line: ReceiptQuantityLine,
): boolean {
  if (value == null || !Number.isFinite(value) || value <= 0) return false;
  return value <= remainingReceiptQuantity(line);
}

export function receiptDeltaError(
  value: number | null,
  line: ReceiptQuantityLine,
  unit?: string | null,
): string | null {
  if (value == null || !Number.isFinite(value) || value <= 0) {
    return '本次实收数量必须大于 0，不允许空值或负数。';
  }
  const remaining = remainingReceiptQuantity(line);
  if (value > remaining) {
    const suffix = unit ? ` ${unit}` : '';
    return `本次实收 ${value}${suffix} 超过待收 ${remaining}${suffix}，系统不会入库；请按现场实际剩余数量重新填写。`;
  }
  return null;
}
