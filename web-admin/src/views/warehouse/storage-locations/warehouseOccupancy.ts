import { WAREHOUSE_TYPE_LABELS, type FactoryWarehouse } from '@/api/factoryWarehouse';

export interface LocatedBatch {
  location: string;
  warehouseId: string;
}

/** A physical location may hold stock from several business warehouses. */
export function warehouseOccupancyAt(
  locationCode: string,
  batches: LocatedBatch[],
  warehouses: FactoryWarehouse[],
): string[] {
  const counts = new Map<string, number>();
  for (const batch of batches) {
    if (batch.location !== locationCode) continue;
    const id = batch.warehouseId.trim();
    counts.set(id, (counts.get(id) ?? 0) + 1);
  }
  return [...counts].map(([id, count]) => {
    const warehouse = warehouses.find((item) => item.id === id || item.code === id);
    const name = warehouse?.name?.trim() || (id ? `仓库 ${id}` : '仓库未登记');
    const type = warehouse?.type ? WAREHOUSE_TYPE_LABELS[warehouse.type] : undefined;
    return `${name}${type ? `（${type}）` : ''} ${count} 批`;
  });
}
