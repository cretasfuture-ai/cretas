import { describe, expect, it } from 'vitest';
import { formatUnitPrice, salesOrderDisplayMoney, warehouseNameById } from '../salesOrderDisplay';

describe('sales order detail display reconciliation', () => {
  it('resolves a customer-supplied target warehouse by id without leaking a UUID', () => {
    const id = 'c865c899-f228-4613-a86c-911475753888';
    expect(warehouseNameById([{ id, name: '生产仓' }], id)).toBe('生产仓');
    expect(warehouseNameById([], id)).toBe('仓库名称未加载');
  });

  it('shows the persisted precision used by the untaxed calculation', () => {
    expect(formatUnitPrice(5.7522)).toBe('¥5.7522');
  });

  it('reconciles the LIUSHANMEN 6.50 tax-included unit price with 1000 units', () => {
    expect(salesOrderDisplayMoney([{ quantity: 1000, unitPrice: 5.7522, taxRate: 13 }])).toEqual({
      untaxed: 5752.2,
      tax: 747.8,
      taxIncluded: 6500,
    });
  });
});
