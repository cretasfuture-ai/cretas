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

export type DeliveryStatusFilter = 'all' | 'DRAFT' | 'PENDING_WAREHOUSE_CONFIRM' | 'PICKED';

export function filterPendingDeliveries(
  deliveries: WarehouseDeliveryRecord[],
  filter: DeliveryStatusFilter,
  query = '',
): WarehouseDeliveryRecord[] {
  const normalized = query.trim().toLowerCase();
  return deliveries.filter((row) => {
    if (filter !== 'all' && String(row.status).toUpperCase() !== filter) return false;
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
