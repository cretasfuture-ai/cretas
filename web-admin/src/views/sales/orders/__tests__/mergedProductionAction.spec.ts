import { describe, expect, it } from 'vitest';
import { productionActionState } from '../salesOrderGuards';

const main = { id: 9101, productTypeId: 'SKU', quantity: 10, unit: 'kg' };
const extra = { id: 9202, productTypeId: 'SKU', quantity: 5, unit: 'box' };
const plan = { id: 'MERGED', sourceOrderId: 'MAIN', sourceOrderIds: ['MAIN', 'EXTRA'],
  sourceOrderItemId: 9101, productTypeId: 'SKU', sourceDisplayQuantity: 10,
  plannedQuantity: 1010, status: 'IN_PROGRESS' };
const state = (items: Record<string, unknown>[], plans = [plan], id = 'EXTRA') =>
  productionActionState(items, plans, false, false, false, id);

describe('merged sales order production state', () => {
  it('recognizes extra order coverage without comparing its box quantity to the merged kg total', () => {
    expect(state([extra])).toMatchObject({ label: '生产中', disabled: true, relatedId: 'MERGED' });
    expect(state([main], [plan], 'MAIN')).toMatchObject({ label: '生产中', disabled: true });
  });
  it('covers multiple matching extra lines, as batch-from-so does, but not another product', () => {
    expect(state([extra, { ...extra, id: 9203, quantity: 2 }]).disabled).toBe(true);
    expect(state([extra, { ...extra, id: 9203, productTypeId: 'OTHER' }]).disabled).toBe(false);
  });
  it('requires explicit order membership and the primary source, not just the same product', () => {
    expect(state([extra], [plan], 'UNRELATED').disabled).toBe(false);
    expect(state([extra], [plan], '').disabled).toBe(false);
    expect(state([extra], [{ ...plan, sourceOrderId: '' }]).disabled).toBe(false);
    expect(state([extra], [{ ...plan, sourceOrderIds: ['MAIN'] }]).disabled).toBe(false);
  });
  it('keeps the primary order partial quantity and display unit authoritative', () => {
    expect(state([main], [{ ...plan, sourceDisplayQuantity: 4 }], 'MAIN').disabled).toBe(false);
    expect(state([{ ...main, deliveredQuantity: 6 }], [{ ...plan, sourceDisplayQuantity: 4 }], 'MAIN').disabled).toBe(true);
    expect(state([{ ...main, deliveredQuantity: 10 }], [], 'MAIN').label).toBe('开始生产');
  });
  it('releases cancelled/rejected/returned plans and distinguishes completion', () => {
    for (const status of ['CANCELLED', 'REJECTED', 'RETURNED']) {
      expect(state([extra], [{ ...plan, status }]).disabled).toBe(false);
    }
    expect(state([extra], [{ ...plan, status: 'COMPLETED' }])).toMatchObject({ label: '已生产', disabled: true });
  });
  it('retains permission, request failure and loading guards', () => {
    expect(productionActionState([extra], [plan], true, false, false, 'EXTRA').kind).toBe('loading');
    expect(productionActionState([extra], [plan], false, true, false, 'EXTRA').kind).toBe('error');
    expect(productionActionState([extra], [plan], false, false, true, 'EXTRA').label).toBe('生产状态不可见');
  });
});
