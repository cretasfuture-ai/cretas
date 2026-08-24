import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const listSource = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');
const detailSource = readFileSync(resolve(__dirname, '..', 'detail.vue'), 'utf8');

describe('purchase order tax-inclusive pricing UI', () => {
  it('accepts only tax-inclusive unit price and keeps the tax point editable', () => {
    expect(listSource).toContain('v-model="item.taxIncludedUnitPrice"');
    expect(listSource).toContain('含税单价');
    expect(listSource).toContain('税点(%)');
    expect(listSource).toContain('@update:model-value="onTaxRateEdited(item)"');
    expect(listSource).not.toContain('v-model="item.unitPrice"');
  });

  it('derives untaxed price immediately and submit only sends the current snapshot', () => {
    expect(listSource).toContain('function onTaxIncludedPriceEdited(item: ProcurementOrderItem)');
    expect(listSource).toContain('function onTaxRateEdited(item: ProcurementOrderItem)');
    expect(listSource).toContain('item.unitPrice = purchaseUntaxedUnitPrice(item.taxIncludedUnitPrice, item.taxRate)');
    expect(listSource).toContain('unitPrice: i.unitPrice');
    expect(listSource).not.toContain('unitPrice: purchaseUntaxedUnitPrice(_taxIncludedUnitPrice, i.taxRate)');
    expect(listSource).toContain('taxIncludedUnitPrice: _taxIncludedUnitPrice');
  });

  it('keeps purchase units governed by the selected specification', () => {
    expect(listSource).toContain('v-model="item.purchasePackagingSpecId"');
    expect(listSource).toContain('@change="onPurchaseSpecChange(item)"');
    expect(listSource).toContain(':model-value="displayUnit(item.unit) || \'-\'" disabled');
  });

  it('shows the tax-inclusive unit price on the order detail', () => {
    expect(detailSource).toContain('label="含税单价"');
    expect(detailSource).toContain('purchaseTaxIncludedUnitPrice(row.unitPrice, row.taxRate)');
  });
});
