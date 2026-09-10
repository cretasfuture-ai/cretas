import { describe, expect, it } from 'vitest';
import { aggregateInventory, isCompleteInventory, numericQuantity, type InventoryQuantityRow } from '../inventoryAggregation';

const batch = (overrides: Partial<InventoryQuantityRow> = {}): InventoryQuantityRow => ({
  id: 'b1', itemId: 'm1', name: '同名物品', factoryId: 'TEST', warehouseId: 'w1',
  unit: 'kg', status: 'AVAILABLE', quantity: 10, available: 8, value: 20, ...overrides,
});

describe('warehouse inventory aggregation', () => {
  it('adds all batches for one identity and keeps ledger and available separate', () => {
    expect(aggregateInventory([batch(), batch({ id: 'b2', quantity: 5, available: 3, value: 15 })]))
      .toMatchObject([{ quantity: 15, available: 11, value: 35, batchCount: 2 }]);
  });
  it('never combines names across IDs, units, warehouses, tenants or quality states', () => {
    const rows = [batch(), batch({ itemId: 'm2' }), batch({ unit: 'box' }),
      batch({ warehouseId: 'w2' }), batch({ factoryId: 'OTHER' }), batch({ status: 'DEFECTIVE' })];
    expect(aggregateInventory(rows)).toHaveLength(6);
  });
  it('keeps batches with unknown identity or unit separate', () => {
    expect(aggregateInventory([batch({ itemId: undefined }), batch({ itemId: undefined })])).toHaveLength(2);
    expect(aggregateInventory([batch({ unit: undefined }), batch({ unit: undefined })])).toHaveLength(2);
  });
  it('does not pretend missing quantities or prices are zero', () => {
    expect(aggregateInventory([batch(), batch({ quantity: null, available: null, value: null })]))
      .toMatchObject([{ quantity: null, available: null, value: null }]);
    expect(numericQuantity(undefined)).toBeNull();
    expect(numericQuantity('invalid')).toBeNull();
    expect(numericQuantity(0)).toBe(0);
  });
  it('rejects partial pages and missing counts', () => {
    expect(isCompleteInventory([batch()], 200)).toBe(false);
    expect(isCompleteInventory([batch()], NaN)).toBe(false);
    expect(isCompleteInventory([batch()], 1)).toBe(true);
    expect(isCompleteInventory([], 0)).toBe(true);
  });
  it('preserves decimal quantities and keeps customer ownership separate', () => {
    const result = aggregateInventory([batch({ quantity: 0.25 }), batch({ quantity: 1.5 }),
      batch({ ownership: 'CUSTOMER_OWNED', ownerCustomerId: 'c1' }),
      batch({ ownership: 'CUSTOMER_OWNED', ownerCustomerId: 'c2' })]);
    expect(result).toHaveLength(3);
    expect(result[0].quantity).toBe(1.75);
  });
});
