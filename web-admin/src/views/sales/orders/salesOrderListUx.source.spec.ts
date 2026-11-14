import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(process.cwd(), 'src/views/sales/orders/list.vue'), 'utf8');

describe('sales order list lifecycle UX source contract', () => {
  it('uses one lifecycle dropdown (W6, no tab bar) plus independent shipment and payment filters', () => {
    expect(source).toContain('class="sales-lifecycle-select"');
    expect(source).toContain('v-model="activeLifecycle"');
    expect(source).not.toContain('sales-lifecycle-tabs');
    expect(source).toContain('v-model="shipmentFilter"');
    expect(source).toContain('v-model="paymentFilter"');
    expect(source).not.toContain('未出库订单');
    expect(source).not.toContain('部分出库订单');
    expect(source).not.toContain('未收款订单');
    expect(source).not.toContain('部分收款订单');
  });

  it('keeps one order row and expands its item, shipment and payment trace', () => {
    expect(source).toContain('<el-table-column type="expand"');
    expect(source).toContain('订单明细');
    expect(source).toContain('出库履约');
    expect(source).toContain('收款进度');
  });

  it('exposes exactly one emphasized next action and moves secondary actions into the menu', () => {
    expect(source).toContain('label="下一步"');
    expect(source).toContain('@click="handlePrimaryAction(row)"');
    expect(source).toContain(':actions="rowActionsFor(row)"');
  });

  it('W6: default list is 8-9 columns and views are only table|kanban', () => {
    const defaults = /const SALES_DEFAULT_COLUMNS = \[([^\]]*)\]/.exec(source)?.[1] ?? '';
    const optionalDefaults = (defaults.match(/'/g) ?? []).length / 2;
    // 固定列：订单编号、客户、订单状态、出库状态、收款状态、下一步 = 6
    expect(6 + optionalDefaults).toBeGreaterThanOrEqual(8);
    expect(6 + optionalDefaults).toBeLessThanOrEqual(9);
    expect(source).toContain("const SALES_VIEW_MODES: ViewMode[] = ['table', 'kanban']");
    expect(source).toContain(':modes="SALES_VIEW_MODES"');
  });
});
