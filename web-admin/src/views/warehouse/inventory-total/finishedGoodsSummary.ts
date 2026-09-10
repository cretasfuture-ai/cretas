import { aggregateInventory, numericQuantity, type InventoryAggregate } from '../../inventory/by-warehouse/inventoryAggregation';

export interface FinishedStockBatch {
  id: string;
  factoryId: string;
  warehouseId?: string;
  productTypeId?: string;
  productName?: string;
  unit?: string;
  status?: string;
  ownership?: string;
  ownerCustomerId?: string;
  producedQuantity?: number;
  shippedQuantity?: number;
  reservedQuantity?: number;
}

export interface StockPage { content: FinishedStockBatch[]; totalElements: number }

/** Load every page before publishing any total; reject drift and repeated pages. */
export async function loadAllFinishedStock(factoryId: string, fetchPage: (page: number) => Promise<StockPage>, signal?: AbortSignal): Promise<FinishedStockBatch[]> {
  const result: FinishedStockBatch[] = [];
  const seen = new Set<string>();
  let expected: number | undefined;
  for (let page = 1; page <= 1000; page++) {
    signal?.throwIfAborted();
    const data = await fetchPage(page);
    signal?.throwIfAborted();
    if (!Array.isArray(data.content) || !Number.isInteger(data.totalElements) || data.totalElements < 0
      || (expected != null && data.totalElements !== expected)) throw new Error('成品库存总数变化或响应不完整，请刷新重试');
    expected = data.totalElements;
    for (const row of data.content) {
      if (!row.id || seen.has(row.id) || row.factoryId !== factoryId) throw new Error('成品库存批次重复或租户不一致，无法汇总');
      seen.add(row.id);
      result.push(row);
    }
    if (result.length === expected) return result;
    if (!data.content.length || result.length > expected) throw new Error('成品库存分页不完整，无法汇总');
  }
  throw new Error('成品库存超过本次查询范围，无法显示全量汇总');
}

export function summarizeFinishedStock(rows: FinishedStockBatch[]): InventoryAggregate[] {
  return aggregateInventory(rows.map(row => {
    const produced = numericQuantity(row.producedQuantity);
    const shipped = numericQuantity(row.shippedQuantity);
    const reserved = numericQuantity(row.reservedQuantity);
    const quantity = produced == null || shipped == null ? null : produced - shipped;
    return { ...row, warehouseId: row.warehouseId || '', itemId: row.productTypeId, name: row.productName,
      quantity, available: quantity == null || reserved == null ? null : quantity - reserved, value: null as number | null };
  }));
}
