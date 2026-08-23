import type { PurchaseOrder } from '../../services/api/purchaseApiClient';
import type { WarehouseDeliveryRecord } from '../../services/api/warehouseDeliveryApiClient';

export type ReceivingStatus = 'pending' | 'partial' | 'completed';

export interface InboundCounts {
  all: number;
  pending: number;
  partial: number;
  completed: number;
}

const RECEIVING_STATUS_PRIORITY: Record<ReceivingStatus, number> = {
  pending: 0,
  partial: 1,
  completed: 2,
};

const PURCHASE_STATUS_PRIORITY: Partial<Record<PurchaseOrder['status'], number>> = {
  FINANCE_APPROVED: 0,
  PARTIAL_RECEIVED: 1,
  COMPLETED: 2,
  CLOSED: 2,
};

export function toReceivingStatus(status: PurchaseOrder['status']): ReceivingStatus {
  if (status === 'PARTIAL_RECEIVED') return 'partial';
  if (status === 'COMPLETED' || status === 'CLOSED') return 'completed';
  return 'pending';
}

export function countInboundTasks(
  pages: Partial<Record<PurchaseOrder['status'], { totalElements?: number; content?: PurchaseOrder[] }>>,
  unorderedInboundCount = 0,
): InboundCounts {
  const count = (status: PurchaseOrder['status']) => {
    const page = pages[status];
    return page?.totalElements ?? page?.content?.length ?? 0;
  };
  const pending = count('FINANCE_APPROVED') + unorderedInboundCount;
  const partial = count('PARTIAL_RECEIVED');
  const completed = count('COMPLETED') + count('CLOSED');
  return { all: pending + partial + completed, pending, partial, completed };
}

/** Skip sources that are exhausted when appending another page. */
export function statusesToFetchForReceivingPage(
  statuses: PurchaseOrder['status'][],
  hasMoreByStatus: Partial<Record<PurchaseOrder['status'], boolean>>,
  append: boolean,
): PurchaseOrder['status'][] {
  return append ? statuses.filter((status) => hasMoreByStatus[status] !== false) : statuses;
}

function receivingBusinessTime(order: PurchaseOrder): string {
  return order.expectedDeliveryDate || order.orderDate || order.updatedAt || order.createdAt || '';
}

/** 先状态，再按业务时间倒序；分页合并后调用方应再次执行此函数。 */
export function sortReceivingOrders(orders: PurchaseOrder[]): PurchaseOrder[] {
  return [...orders].sort((a, b) => {
    const aStatus = PURCHASE_STATUS_PRIORITY[a.status] ?? 99;
    const bStatus = PURCHASE_STATUS_PRIORITY[b.status] ?? 99;
    if (aStatus !== bStatus) return aStatus - bStatus;
    return receivingBusinessTime(b).localeCompare(receivingBusinessTime(a));
  });
}

/** Merge a newly fulfilled source page without dropping already loaded rows. */
export function mergeReceivingOrders(
  existing: PurchaseOrder[],
  incoming: PurchaseOrder[],
): PurchaseOrder[] {
  const byId = new Map(existing.map((order) => [order.id, order]));
  incoming.forEach((order) => byId.set(order.id, order));
  return sortReceivingOrders([...byId.values()]);
}

/** Filter after all loaded status pages have been merged. */
export function filterReceivingOrders(
  orders: PurchaseOrder[],
  filter: ReceivingStatus | 'all',
  query = '',
): PurchaseOrder[] {
  const normalized = query.trim().toLowerCase();
  return orders.filter((order) => {
    if (filter !== 'all' && toReceivingStatus(order.status) !== filter) return false;
    if (!normalized) return true;
    return order.orderNumber.toLowerCase().includes(normalized)
      || (order.supplierName || '').toLowerCase().includes(normalized)
      || (order.items || []).some((item) => (item.materialName || '').toLowerCase().includes(normalized));
  });
}

export type DeliveryStatusFilter =
  | 'all'
  | 'pending'
  | 'completed'
  | 'DRAFT'
  | 'PENDING_WAREHOUSE_CONFIRM'
  | 'PICKED';

/** 后端已确认/历史状态；这些卡片只能回看，不能再次进入确认写流程。 */
export const COMPLETED_DELIVERY_STATUSES = [
  'SHIPPED',
  'DELIVERED',
  'SIGNED',
  'CANCELLED',
  'RETURNED',
  'PARTIALLY_SHIPPED',
] as const;

export const CONFIRMABLE_DELIVERY_STATUSES = [
  'DRAFT',
  'PENDING_WAREHOUSE_CONFIRM',
  'PICKED',
] as const;

export function isCompletedDeliveryStatus(status: WarehouseDeliveryRecord['status']): boolean {
  return COMPLETED_DELIVERY_STATUSES.includes(String(status).toUpperCase() as (typeof COMPLETED_DELIVERY_STATUSES)[number]);
}

export function isConfirmableDeliveryStatus(status: WarehouseDeliveryRecord['status']): boolean {
  return CONFIRMABLE_DELIVERY_STATUSES.includes(String(status).toUpperCase() as (typeof CONFIRMABLE_DELIVERY_STATUSES)[number]);
}

export function deliveryRecordKey(row: WarehouseDeliveryRecord): string {
  return String(row.id || row.deliveryNumber);
}

/**
 * Merge the two independently paginated sources. History is authoritative when
 * the same id is present in both responses: a just-confirmed row must not remain
 * actionable merely because the pending page is stale.
 */
export function mergeDeliveryRecords(
  pending: WarehouseDeliveryRecord[],
  history: WarehouseDeliveryRecord[],
): WarehouseDeliveryRecord[] {
  const historyByKey = new Map(history.map((row) => [deliveryRecordKey(row), row]));
  const seen = new Set<string>();
  const merged: WarehouseDeliveryRecord[] = [];
  for (const row of pending) {
    const key = deliveryRecordKey(row);
    if (seen.has(key)) continue;
    seen.add(key);
    merged.push(historyByKey.get(key) ?? row);
  }
  for (const row of history) {
    const key = deliveryRecordKey(row);
    if (seen.has(key)) continue;
    seen.add(key);
    merged.push(row);
  }
  return merged;
}

export function filterPendingDeliveries(
  deliveries: WarehouseDeliveryRecord[],
  filter: DeliveryStatusFilter,
  query = '',
): WarehouseDeliveryRecord[] {
  const normalized = query.trim().toLowerCase();
  return deliveries.filter((row) => {
    const status = String(row.status).toUpperCase();
    if (filter === 'pending' && !isConfirmableDeliveryStatus(status)) return false;
    if (filter === 'completed' && !isCompletedDeliveryStatus(status)) return false;
    if (!['all', 'pending', 'completed'].includes(filter) && status !== filter) return false;
    if (!normalized) return true;
    const haystack = [
      row.deliveryNumber,
      row.orderNumber,
      row.salesOrderId,
      row.customerName,
      row.customerId,
      ...(row.items || []).flatMap((item) => [item.productName, item.productTypeId]),
    ].filter(Boolean).join(' ').toLowerCase();
    return haystack.includes(normalized);
  });
}

// Kept as a named value for callers/tests that need the stable UI order.
export { RECEIVING_STATUS_PRIORITY };
