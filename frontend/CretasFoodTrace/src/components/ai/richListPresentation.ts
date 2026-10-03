/** Keep stock quantities visible even when DTO metadata comes first. */
export function getListDisplayKeys(row: Record<string, unknown>): string[] {
  if ('batchNumber' in row && 'materialName' in row) {
    const quantityKey = 'currentQuantity' in row ? 'currentQuantity' : 'quantity';
    const unitKey = typeof row.quantityUnit === 'string' && row.quantityUnit.trim() ? 'quantityUnit' : 'unit';
    const statusKey = row.statusDisplayName != null ? 'statusDisplayName' : 'status';
    return ['batchNumber', 'materialName', quantityKey, unitKey, statusKey];
  }
  return Object.keys(row)
    .filter(key => !key.endsWith('Id') && key !== 'id' && key !== 'factoryId' &&
      typeof row[key] !== 'object')
    .slice(0, 5);
}

const MATERIAL_STATUS_NAMES: Record<string, string> = {
  IN_STOCK: '库存中', AVAILABLE: '可用', FRESH: '鲜品', FROZEN: '冻品',
  DEPLETED: '已耗尽', USED_UP: '已用完', EXPIRED: '已过期', INSPECTING: '质检中',
  SCRAPPED: '已报废', RESERVED: '已预留', PRODUCING_RESERVED: '生产预留', DEFECTIVE: '不良品',
};

export function getListCellValue(row: Record<string, unknown>, key: string): unknown {
  const value = row[key];
  if (key === 'status' && 'batchNumber' in row && 'materialName' in row && typeof value === 'string') {
    return MATERIAL_STATUS_NAMES[value.toUpperCase()] ?? value;
  }
  return value;
}
