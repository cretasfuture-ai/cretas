import { beforeEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { sourceWithChinese } from '@/i18n-pilot/sourceForTests';
import {
  normalizeVisibleColumnKeys,
  readVisibleColumnKeys,
} from '../businessTableColumns';

describe('business list column contract', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('keeps only known unique columns and enforces the configured maximum', () => {
    expect(normalizeVisibleColumnKeys(
      ['date', 'unknown', 'date', 'amount', 'source'],
      ['date', 'amount', 'source'],
      ['date'],
      2,
    )).toEqual(['date', 'amount']);
  });

  it('falls back to valid defaults when persisted selection is empty or malformed', () => {
    window.localStorage.setItem('columns', '{broken json');
    expect(readVisibleColumnKeys(
      'columns',
      ['date', 'amount', 'source'],
      ['date', 'source'],
      2,
    )).toEqual(['date', 'source']);
  });

  it('drops price columns that are no longer available after a permission change', () => {
    window.localStorage.setItem('columns', JSON.stringify(['date', 'amount']));
    expect(readVisibleColumnKeys(
      'columns',
      ['date', 'source'],
      ['date', 'source'],
      2,
    )).toEqual(['date']);
  });
});

describe('sales, procurement and production list integration', () => {
  const salesSource = readSource('../../../views/sales/orders/list.vue');
  const procurementSource = sourceWithChinese(readSource('../../../views/procurement/orders/list.vue'));
  const productionSource = readSource('../../../views/production/plans/list.vue');

  it('sales list (Nuxt UI UTable) keeps the shared grid class and limited column selector', () => {
    expect(salesSource).toContain('business-list-table');
    expect(salesSource).toContain('<ListColumnSelector');
    expect(salesSource).toContain('<UTable');
  });

  it.each([
    ['procurement', procurementSource],
    ['production', productionSource],
  ])('%s list uses the shared fixed-grid and limited column selector', (_name, source) => {
    expect(source).toContain('business-list-table');
    expect(source).toContain('<ListColumnSelector');
    expect(source).toContain('table-layout="fixed"');
  });

  it('removes unexplained row markers and only shows sales/production selection in batch mode', () => {
    expect(salesSource).not.toContain('<RowMarkerCell');
    expect(procurementSource).not.toContain('<RowMarkerCell');
    expect(procurementSource).not.toContain('type="selection"');
    // Nuxt UI 版: 勾选列只在 salesBatchMode 下进列定义
    expect(salesSource).toContain("salesBatchMode.value && { id: 'select' }");
    expect(productionSource).toContain('v-if="productionBatchMode" type="selection"');
  });

  it('keeps the procurement fixed columns sortable/filterable without adding bulk controls', () => {
    expect(procurementSource).toContain('prop="orderNumber" label="订单编号" width="170" sortable');
    expect(procurementSource).toContain(':filters="purchaseTypeColumnFilters"');
    expect(procurementSource).toContain('prop="orderDate"');
    // PROTO-DEMO-PO-TAX: totalAmount 是未税总额, 列名照实写 (原「总金额」看不出含不含税)。
    expect(procurementSource).toContain('label="未税总额"');
    expect(procurementSource).toContain(':filters="purchaseStatusColumnFilters"');
  });
});

function readSource(relativePath: string): string {
  return readFileSync(new URL(relativePath, import.meta.url), 'utf8');
}
