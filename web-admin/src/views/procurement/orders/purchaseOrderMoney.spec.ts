import { describe, expect, it } from 'vitest';
import {
  formatPurchaseTaxRate,
  purchaseLineMoney,
  purchaseOrderMoneySummary,
  roundMoney,
} from './purchaseOrderMoney';
import { purchaseUntaxedUnitPrice } from './purchaseOrderTaxInclusivePricing';

describe('PROTO-DEMO-PO-TAX 采购金额口径 (与后端 PurchaseOrderItem 同口径)', () => {
  it('含税 2、税率 9%、数量 10 → 未税 18.35、税额 1.65、含税 20.00', () => {
    const unitPrice = purchaseUntaxedUnitPrice(2, 9);
    expect(unitPrice).toBe(1.8349);
    // 后端 getLineAmount: round2(10 × 1.8349) = 18.35; getLineAmountWithTax: round2(18.35 × 1.09) = 20.00
    expect(purchaseLineMoney(10 * (unitPrice as number), 9)).toEqual({ untaxed: 18.35, tax: 1.65, taxIncluded: 20 });
  });

  it('后端给了 lineAmountWithTax 就用它, 税额 = 含税 − 未税', () => {
    expect(purchaseLineMoney(20.18, 9, 22)).toEqual({ untaxed: 20.18, tax: 1.82, taxIncluded: 22 });
  });

  it('税率缺失 / 金额缺失返回 null, ⛔ 不当 0% 或 0 元', () => {
    expect(purchaseLineMoney(18.35, null)).toBeNull();
    expect(purchaseLineMoney(null, 9)).toBeNull();
    expect(purchaseLineMoney(18.35, '')).toBeNull();
  });

  it('单头: 未税总额 + 税额合计 = 含税总额; 任一被剥为 null 时整体 null', () => {
    expect(purchaseOrderMoneySummary({ totalAmount: 20.18, taxAmount: 1.82 })).toEqual({ untaxed: 20.18, tax: 1.82, taxIncluded: 22 });
    expect(purchaseOrderMoneySummary({ totalAmount: 0.1, taxAmount: 0.2 })?.taxIncluded).toBe(0.3);
    expect(purchaseOrderMoneySummary({ totalAmount: null, taxAmount: 1.82 })).toBeNull();
    expect(purchaseOrderMoneySummary(null)).toBeNull();
  });

  it('roundMoney 是 HALF_UP 且不受二进制尾差影响', () => {
    expect(roundMoney(1.005)).toBe(1.01);
    expect(roundMoney(20.0015)).toBe(20);
    expect(roundMoney(2.675)).toBe(2.68);
    expect(roundMoney(-1.005)).toBe(-1.01);
  });

  it('税率按百分数显示: 9 → 9%, 0.13 → 0.13% (后端也按 0.13% 算税), 缺失 → -', () => {
    expect(formatPurchaseTaxRate(9)).toBe('9%');
    expect(formatPurchaseTaxRate('13.00')).toBe('13%');
    expect(formatPurchaseTaxRate(0)).toBe('0%');
    expect(formatPurchaseTaxRate(0.13)).toBe('0.13%');
    expect(formatPurchaseTaxRate(null)).toBe('-');
    expect(formatPurchaseTaxRate('')).toBe('-');
  });
});
