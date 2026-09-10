export interface InventoryQuantityRow {
  id: string;
  itemId?: string;
  name?: string;
  code?: string;
  factoryId: string;
  warehouseId: string;
  unit?: string;
  status?: string;
  ownership?: string;
  ownerCustomerId?: string;
  quantity: number | null;
  available: number | null;
  value: number | null;
}

export interface InventoryAggregate extends InventoryQuantityRow {
  batchCount: number;
}

export function numericQuantity(value: unknown): number | null {
  if (value == null || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

/** An incomplete response must never be labelled as the warehouse total. */
export function isCompleteInventory(rows: readonly unknown[], count: number): boolean {
  return Number.isInteger(count) && count >= 0 && rows.length === count;
}

export function aggregateInventory(rows: readonly InventoryQuantityRow[]): InventoryAggregate[] {
  const groups = new Map<string, InventoryAggregate>();
  rows.forEach((row, index) => {
    // Unknown identity/unit stays a separate batch; names are display values only.
    const key = JSON.stringify([
      row.factoryId, row.warehouseId, row.itemId, row.unit, row.status, row.ownership, row.ownerCustomerId,
      !row.itemId || !row.unit ? [row.id, index] : null,
    ]);
    const existing = groups.get(key);
    if (!existing) {
      groups.set(key, { ...row, id: key, batchCount: 1 });
      return;
    }
    existing.batchCount += 1;
    for (const field of ['quantity', 'available', 'value'] as const) {
      const amount = row[field];
      existing[field] = existing[field] == null || amount == null ? null : existing[field] + amount;
    }
  });
  return [...groups.values()];
}
