import { describe, expect, it } from 'vitest';
import { buildSalesProductPickerOptions, productIdsFromCustomerOrders } from '../salesOrderProductPicker';

const products = [
  { id: 'fg-high', name: '高库存成品', productCategory: 'FINISHED_PRODUCT' },
  { id: 'fg-zero', name: '零库存成品', productCategory: 'FINISHED_PRODUCT' },
  { id: 'semi', name: '半成品', productCategory: 'SEMI_FINISHED' },
];

describe('sales order product picker ranking', () => {
  it('puts customer history first, then positive inventory descending, then zero inventory', () => {
    const history = productIdsFromCustomerOrders([{ items: [{ productTypeId: 'fg-zero' }] }]);
    const rows = buildSalesProductPickerOptions(products, [
      { productTypeId: 'fg-high', availableQuantity: 12, unit: 'box' },
      { productTypeId: 'semi', availableQuantity: 3, unit: 'kg' },
    ], history, '', 'ALL');
    expect(rows.map((row) => row.id)).toEqual(['fg-zero', 'fg-high', 'semi']);
    expect(rows[0].availableQuantity).toBe(0);
  });

  it('filters finished and semi-finished categories independently', () => {
    expect(buildSalesProductPickerOptions(products, [], new Map(), '', 'FINISHED_PRODUCT').map((row) => row.id))
      .toEqual(['fg-high', 'fg-zero']);
    expect(buildSalesProductPickerOptions(products, [], new Map(), '', 'SEMI_FINISHED').map((row) => row.id))
      .toEqual(['semi']);
  });
});
