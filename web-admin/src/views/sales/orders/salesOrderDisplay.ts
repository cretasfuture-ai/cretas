import { roundMoney, type RowLike } from './salesOrderGuards';

/** Server payableAmount includes tax and discount; legacy responses may omit this getter. */
interface ReceivableOrder {
  totalAmount?: unknown; payableAmount?: unknown; discountAmount?: unknown; taxAmount?: unknown;
  paidAmount?: unknown;
}

export function salesOrderReceivableAmount(order: ReceivableOrder | null | undefined): number {
  if (!order || order.totalAmount == null) return 0;
  return Math.max(0, roundMoney(Number(order.payableAmount
    ?? (Number(order.totalAmount) - Number(order.discountAmount ?? 0) + Number(order.taxAmount ?? 0)))));
}

export function salesOrderRemainingAmount(order: ReceivableOrder | null | undefined): number {
  return roundMoney(Math.max(0, salesOrderReceivableAmount(order) - Number(order?.paidAmount ?? 0)));
}

export interface WarehouseIdNameLike {
  id?: string | number | null;
  name?: string | null;
}

export function warehouseNameById(
  warehouses: WarehouseIdNameLike[] | null | undefined,
  id: unknown,
): string {
  const key = String(id ?? '').trim();
  if (!key) return '未维护';
  const match = warehouses?.find((warehouse) => String(warehouse.id ?? '') === key);
  return String(match?.name || '').trim() || '仓库名称未加载';
}

export function formatUnitPrice(value: unknown): string {
  const amount = Number(value);
  if (!Number.isFinite(amount)) return '—';
  return `¥${amount.toLocaleString('zh-CN', { minimumFractionDigits: 4, maximumFractionDigits: 4 })}`;
}

/**
 * Sales entry is tax-included-unit-price first. The backend persists the reverse-calculated
 * untaxed unit price, so reconstruct and round the contractual unit price before multiplying.
 * This keeps the displayed unit price and line/summary totals arithmetically reconcilable.
 */
export function salesOrderDisplayMoney(items: RowLike[]): { untaxed: number; tax: number; taxIncluded: number } {
  return items.reduce<{ untaxed: number; tax: number; taxIncluded: number }>((sum, item) => {
    const quantity = Number(item.quantity ?? 0);
    const unitPrice = Number(item.unitPrice ?? 0);
    const taxRate = Number(item.taxRate ?? 0);
    const untaxed = roundMoney(quantity * unitPrice);
    const taxIncludedUnitPrice = roundMoney(unitPrice * (1 + taxRate / 100));
    const taxIncluded = roundMoney(quantity * taxIncludedUnitPrice);
    return {
      untaxed: roundMoney(sum.untaxed + untaxed),
      tax: roundMoney(sum.tax + taxIncluded - untaxed),
      taxIncluded: roundMoney(sum.taxIncluded + taxIncluded),
    };
  }, { untaxed: 0, tax: 0, taxIncluded: 0 });
}
