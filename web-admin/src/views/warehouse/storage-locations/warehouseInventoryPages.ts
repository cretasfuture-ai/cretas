export interface WarehouseBatchPage<T> {
  content: T[];
  totalElements: number;
}

/** Read the complete inventory before publishing counts or location occupancy. */
export async function loadAllWarehouseBatches<T extends { id: string; factoryId: string }>(
  factoryId: string,
  fetchPage: (page: number) => Promise<WarehouseBatchPage<T>>,
  signal?: AbortSignal,
): Promise<T[]> {
  const rows: T[] = [];
  const seen = new Set<string>();
  let expectedTotal: number | undefined;
  for (let page = 1; page <= 1000; page++) {
    signal?.throwIfAborted();
    const result = await fetchPage(page);
    signal?.throwIfAborted();
    if (!Array.isArray(result?.content) || !Number.isInteger(result.totalElements) || result.totalElements < 0
      || (expectedTotal !== undefined && expectedTotal !== result.totalElements)) {
      throw new Error('库存分页结果不完整或总数发生变化，请刷新重试');
    }
    expectedTotal = result.totalElements;
    for (const row of result.content) {
      if (!row.id || seen.has(row.id) || row.factoryId !== factoryId) {
        throw new Error('库存批次重复或所属工厂不一致，无法显示完整盘点');
      }
      seen.add(row.id);
      rows.push(row);
    }
    if (rows.length === expectedTotal) return rows;
    if (result.content.length === 0 || rows.length > expectedTotal) {
      throw new Error('库存分页缺页，无法显示完整盘点');
    }
  }
  throw new Error('库存批次超过本次查询范围，无法显示完整盘点');
}
