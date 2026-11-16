import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/sales/orders/list.vue'),
  'utf8',
);

describe('sales order ordinary create entry', () => {
  it('opens the governed ordinary form directly and keeps AI entry separate', () => {
    expect(source).toContain('@click="openCreateDialog"');
    expect(source).toContain('@click="aiEntryVisible = true"');
    expect(source).not.toContain('CreateModeSelector');
    expect(source).not.toContain('BatchCreateDialog');
    expect(source).not.toContain('QuickCreateDialog');
    expect(source).not.toContain('BomExpansionDialog');
    expect(source).not.toContain('选择录入方式');
  });

  it('renders the form before loading selectable reference data in parallel', () => {
    const start = source.indexOf('async function openCreateDialog()');
    const end = source.indexOf('\n}', source.indexOf('createOptionsLoading.value = false;', start));
    const body = source.slice(start, end);
    const visibleAt = body.indexOf('dialogVisible.value = true;');
    const loadAt = body.indexOf('void Promise.allSettled([');

    expect(visibleAt).toBeGreaterThanOrEqual(0);
    expect(loadAt).toBeGreaterThan(visibleAt);
    expect(body).toContain('loadCustomers()');
    expect(body).toContain('loadProducts()');
    expect(body).toContain('loadSalesEmployees()');
    expect(body).toContain('loadWarehouses()');
  });

  it('adds data-type appropriate sorting and categorical header filters to the order table', () => {
    // Nuxt UI 版: 排序走 UTable 列定义 (文本列带中文 sortingFn, 金额列 accessorFn 出数字), 表头筛选搬到工具栏下拉
    expect(source).toContain("id: 'orderNumber', accessorKey: 'orderNumber', header: '订单编号', sortingFn: sortText");
    expect(source).toContain('v-model="columnFilters.processingMode" :items="processingModeFilterItems"');
    expect(source).toContain('v-model="columnFilters.materialSupplyMode" :items="materialSupplyFilterItems"');
    expect(source).toContain('v-model="columnFilters.invoiceStatus" :items="invoiceStatusFilterItems"');
    expect(source).toContain('v-model="columnFilters.status" :items="orderStatusFilterItems"');
    expect(source).toContain("id: 'totalAmount', accessorFn: (r) => (r.totalAmount == null ? null : Number(r.totalAmount)), header: '总金额'");
  });

  it('preserves source warehouse through create, edit and payload submission', () => {
    expect(source).toContain('sourceWarehouseCode: getRememberedWarehouse()');
    expect(source).toContain('sourceWarehouseCode: String(item.sourceWarehouseCode || \'\')');
    expect(source).toContain('items: toOrderItemPayload(selectedItems)');
    expect(source).toContain('v-model="item.sourceWarehouseCode"');
  });
});
