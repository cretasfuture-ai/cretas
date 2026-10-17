import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(import.meta.dirname, '../new.vue'), 'utf8');

describe('sales order full-page pricing and unit UI', () => {
  it('takes tax-inclusive price and editable tax point instead of untaxed price', () => {
    expect(source).toContain('label="含税单价"');
    expect(source).toContain('v-model="row.taxIncludedUnitPrice"');
    expect(source).toContain('label="税点(%)"');
    expect(source).toContain('v-model="row.taxRate"');
    expect(source).not.toContain('label="未税单价"');
  });

  it('uses a governed product-unit select and submits the real-time untaxed snapshot', () => {
    expect(source).toContain('v-for="unit in salesOrderUnitOptions(row.product)"');
    expect(source).not.toContain('<el-input v-model="row.unit"');
    expect(source).toContain('function recalculateSalesUnitPrice(line: SalesLine)');
    expect(source).toContain('line.unitPrice = untaxedUnitPrice(line.taxIncludedUnitPrice, line.taxRate)');
    expect(source).toContain('@update:model-value="recalculateSalesUnitPrice(row)"');
    expect(source).toContain('unitPrice: line.unitPrice');
    expect(source).not.toContain('unitPrice: untaxedUnitPrice(line.taxIncludedUnitPrice, line.taxRate)');
    expect(source).toContain('taxRate: line.taxRate');
    expect(source).toContain('packagingSpecId: packagingSpecIdForUnit(line.product, line.unit, line.packagingSpecId)');
    expect(source).toContain('salesPackagingSelectionError(line.product, line.unit, line.packagingSpecId)');
    expect(source).toContain('row.packagingSpecId = undefined; dirty = true');
    expect(source).toContain('v-model="row.packagingSpecId" :options="salesPackagingOptions(row.product, row.unit)"');
    expect(source).toContain('product = { ...product, packagingSpecs: response.data }');
    expect(source).toContain('包装规格加载失败，未添加产品，请重试');
  });
});
