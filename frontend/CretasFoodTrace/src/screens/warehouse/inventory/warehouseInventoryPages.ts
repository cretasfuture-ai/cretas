export async function loadAllWarehouseMaterialBatches<T extends { id: string; factoryId: string }>(
  factoryId: string,
  fetchPage: (page: number) => Promise<{ content: T[]; totalElements: number }>,
): Promise<T[]> {
  const rows: T[] = [];
  const seen = new Set<string>();
  let expectedTotal: number | undefined;
  for (let page = 1; page <= 1000; page++) {
    const result = await fetchPage(page);
    if (!Array.isArray(result?.content) || !Number.isInteger(result.totalElements) || result.totalElements < 0
      || (expectedTotal !== undefined && expectedTotal !== result.totalElements)) {
      throw new Error('库存分页结果不完整或总数发生变化，请重试');
    }
    expectedTotal = result.totalElements;
    for (const row of result.content) {
      if (!row.id || seen.has(row.id) || row.factoryId !== factoryId) {
        throw new Error('库存批次重复或所属工厂不一致，请重试');
      }
      seen.add(row.id);
      rows.push(row);
    }
    if (rows.length === expectedTotal) return rows;
    if (!result.content.length || rows.length > expectedTotal) throw new Error('库存分页缺页，请重试');
  }
  throw new Error('库存批次超过本次查询范围，请联系管理员');
}
