import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { deliveryMoney } from '../salesOrderGuards';

describe('报价订单发货含税金额', () => {
  const line = { unitPrice: 54.8673, taxRate: 13, quotedGrossUnitPrice: 62, unit: '个' };
  it('两件全量发货保持批准含税124元，税额14.27', () => {
    expect(deliveryMoney([{ ...line, deliveredQuantity: 2 }]))
      .toEqual({ untaxed: 109.73, tax: 14.27, taxIncluded: 124 });
  });
  it('实际发1件为62元，1000件不会累积净价反算误差', () => {
    expect(deliveryMoney([{ ...line, deliveredQuantity: 1 }]).taxIncluded).toBe(62);
    expect(deliveryMoney([{ ...line, deliveredQuantity: 1000 }]).taxIncluded).toBe(62000);
  });
  it('历史无快照行保留原计算', () => {
    expect(deliveryMoney([{ ...line, quotedGrossUnitPrice: null, deliveredQuantity: 2 }]))
      .toEqual({ untaxed: 109.73, tax: 14.26, taxIncluded: 123.99 });
  });
  it('真实发货窗口带入订单快照并使用同一行税额计算', () => {
    const source = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');
    expect(source.includes('quotedGrossUnitPrice: it.quotedGrossUnitPrice')).toBe(true);
    expect(source.includes('deliveryMoney([row]).tax')).toBe(true);
  });
});
