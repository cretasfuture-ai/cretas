import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { salesOrderReceivableAmount, salesOrderRemainingAmount } from '../salesOrderDisplay';
describe('quote-linked receipt amount', () => {
  it('uses server gross payable124 instead of net109.73', () => {
    expect(salesOrderReceivableAmount({ totalAmount: 109.73, taxAmount: 14.27, payableAmount: 124 })).toBe(124);
    expect(salesOrderReceivableAmount({ totalAmount: 109.73, taxAmount: 14.27 })).toBe(124);
  });
  it('includes discount, zero tax and legacy nullable fields', () => {
    expect(salesOrderReceivableAmount({ totalAmount: 100, taxAmount: 13, discountAmount: 10 })).toBe(103);
    expect(salesOrderReceivableAmount({ totalAmount: 100 })).toBe(100);
    expect(salesOrderReceivableAmount({ totalAmount: null, taxAmount: 13 })).toBe(0);
    expect(salesOrderReceivableAmount(null)).toBe(0);
  });
  it('keeps both real payment entry points on gross receivable balance', () => {
    const detail = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');
    const finance = readFileSync(resolve(import.meta.dirname, '../../../finance/payments/list.vue'), 'utf8');
    expect(detail).toContain('return salesOrderRemainingAmount(order.value);');
    expect(finance).toContain('return salesOrderRemainingAmount(selectedSO.value);');
    expect(detail).toContain('amount: remaining,');
    expect(finance).toContain('recordForm.value.amount = remainingAmount.value;');
    expect(detail).toContain('应收总额（含税）'); expect(finance).toContain('应收总额（含税）');
  });
  it('submits exact two-decimal remainder for fractional payments', () => {
    expect(salesOrderRemainingAmount({ totalAmount: 124, paidAmount: 8.04 })).toBe(115.96);
    expect(salesOrderRemainingAmount({ totalAmount: 124, paidAmount: 109.73 })).toBe(14.27);
    expect(salesOrderRemainingAmount({ totalAmount: 124, paidAmount: 125 })).toBe(0);
  });
});
