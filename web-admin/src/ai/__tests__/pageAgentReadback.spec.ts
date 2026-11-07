import { describe, expect, it, vi } from 'vitest';
import { assertVerifiablePageMutation, verifyPageMutation } from '../pageAgentReadback';
import type { AgentWireRequest, AgentWireResult } from '../pageAgentTransport';

const request = (path: string, payload: unknown, method = 'POST'): AgentWireRequest => ({ method, path, canonicalPath: path, kind: 'business', payload } as AgentWireRequest & { payload: unknown });
const result = (path: string, payload: unknown, body: unknown, method = 'POST'): AgentWireResult => ({ ...request(path, payload, method), status: 200, ok: true, body } as AgentWireResult);

describe('page mutation deterministic readback contracts', () => {
  it('shows a human business number while still checking the immutable entity ID', async () => {
    const payload = { supplierId: 'sup', items: [{ materialTypeId: 'mat', quantity: 1, unit: 'kg', unitPrice: 3 }] };
    const wire = result('/purchase/orders', payload, { id: 'uuid-for-linking' });
    const actual = { id: 'uuid-for-linking', factoryId: 'F006', orderNumber: 'PO-20261107-0001', ...payload };
    await expect(verifyPageMutation(wire, 'F006', async () => actual)).resolves.toContain('PO-20261107-0001');
    await expect(verifyPageMutation(wire, 'F006', async () => ({ ...actual, id: 'another-entity' }))).rejects.toThrow('实体ID');
  });
  it.each([
    ['/purchase/orders', { supplierId: 'sup-1', items: [{ materialTypeId: 'mat-1', quantity: 2, unit: 'kg', unitPrice: 3 }] }, { id: 'po-1', factoryId: 'F006', supplierId: 'sup-1', items: [{ materialTypeId: 'mat-1', quantity: 2, unit: 'kg', unitPrice: 3 }] }, '/purchase/orders/po-1'],
    ['/sales/orders', { customerId: 'cus-1', items: [{ productTypeId: 'sku-1', quantity: 2, unit: 'box', unitPrice: 9 }] }, { id: 'so-1', factoryId: 'F006', customerId: 'cus-1', items: [{ productTypeId: 'sku-1', quantity: 2, unit: 'box', unitPrice: 9 }] }, '/sales/orders/so-1'],
    ['/warehouse/receiving/receipts', { purchaseOrderId: 'po-1', supplierId: 'sup-1', warehouseId: 'wh-1', items: [{ purchaseOrderItemId: 'poi-1', materialTypeId: 'mat-1', receivedQuantity: 2, unit: 'kg' }] }, { id: 'rcv-1', factoryId: 'F006', purchaseOrderId: 'po-1', supplierId: 'sup-1', warehouseId: 'wh-1', items: [{ purchaseOrderItemId: 'poi-1', materialTypeId: 'mat-1', receivedQuantity: 2, unit: 'kg' }] }, '/warehouse/receiving/receipts/rcv-1'],
    ['/production-plans/batch-from-so', { sourceOrderId: 'so-1', itemIds: ['line-1'] }, { id: 'plan-1', sourceOrderId: 'so-1', sourceOrderItemId: 'line-1', factoryId: 'F006' }, '/production-plans/plan-1'],
    ['/material-requisitions/generate', { productionPlanId: 'plan-1' }, { id: 'req-1', productionPlanId: 'plan-1', factoryId: 'F006' }, '/material-requisitions/req-1'],
    ['/sales/deliveries', { salesOrderId: 'so-1', customerId: 'cus-1', items: [{ salesOrderItemId: 'line-1', productTypeId: 'sku-1', deliveredQuantity: 2, unit: 'box', unitPrice: 9 }] }, { id: 'delivery-1', salesOrderId: 'so-1', customerId: 'cus-1', factoryId: 'F006', items: [{ salesOrderItemId: 'line-1', productTypeId: 'sku-1', deliveredQuantity: 2, unit: 'box', unitPrice: 9 }] }, '/sales/deliveries/delivery-1'],
  ])('maps %s to a same-entity GET', async (path, payload, body, expected) => {
    const req = request(path, payload);
    expect(() => assertVerifiablePageMutation(req)).not.toThrow();
    const read = vi.fn(async () => body);
    await expect(verifyPageMutation(result(path, payload, body), 'F006', read)).resolves.toContain(body.id ?? body.orderId ?? body.deliveryId ?? body.planId ?? body.requisitionId);
    expect(read).toHaveBeenCalledWith(expected);
  });

  it('requires status, factory, id and parent to match on readback', async () => {
    const path = '/material-requisitions/req-1/receive';
    const payload = { idempotencyKey: 'x' };
    expect(() => assertVerifiablePageMutation(request(path, payload))).not.toThrow();
    await expect(verifyPageMutation(result(path, payload, { id: 'req-1', factoryId: 'F006', productionPlanId: 'plan-1', status: 'PENDING' }), 'F006', async () => ({ id: 'req-1', factoryId: 'F006', productionPlanId: 'plan-1', status: 'PENDING' }))).rejects.toThrow();
    await expect(verifyPageMutation(result(path, payload, { id: 'req-1', factoryId: 'F007', productionPlanId: 'plan-1', status: 'ISSUED' }), 'F006', async () => ({ id: 'req-1', factoryId: 'F007', productionPlanId: 'plan-1', status: 'ISSUED' }))).rejects.toThrow(/factoryId/);
  });

  it('rejects unknown mutations and incomplete create payloads before send', () => {
    expect(() => assertVerifiablePageMutation(request('/purchase/orders/po-1/void', {}))).toThrow(/未注册/);
    expect(() => assertVerifiablePageMutation(request('/purchase/orders', { supplierId: 'sup-1' }))).toThrow(/明细/);
    expect(() => assertVerifiablePageMutation(request('/sales/orders', { customerId: 'cus-1', items: [] }))).toThrow(/明细/);
  });

  it('rejects HTTP acknowledgement and wrong entity readback', async () => {
    const path = '/purchase/orders'; const payload = { supplierId: 'sup-1', items: [{ materialTypeId: 'mat-1', quantity: 2, unit: 'kg', unitPrice: 3 }] };
    await expect(verifyPageMutation({ ...result(path, payload, { id: 'po-1' }), ok: false, status: 200 }, 'F006', async () => ({ id: 'po-1', factoryId: 'F006' }))).rejects.toThrow();
    await expect(verifyPageMutation(result(path, payload, { id: 'po-1' }), 'F006', async () => ({ id: 'po-2', factoryId: 'F006' }))).rejects.toThrow(/实体ID/);
  });

  it.each([
    ['/warehouse/receiving/receipts', { purchaseOrderId: 'po-1', supplierId: 'sup-1', warehouseId: 'wh-1', items: [{ purchaseOrderItemId: 'poi-1', materialTypeId: 'mat-1', receivedQuantity: 2, unit: 'kg' }] }, { id: 'rcv-1', factoryId: 'F006', purchaseOrderId: 'po-1', supplierId: 'sup-1', warehouseId: 'wh-1', items: [{ purchaseOrderItemId: 'poi-1', materialTypeId: 'mat-1', receivedQuantity: 3, unit: 'kg' }] }],
    ['/sales/deliveries', { salesOrderId: 'so-1', customerId: 'cus-1', items: [{ salesOrderItemId: 'line-1', productTypeId: 'sku-1', deliveredQuantity: 2, unit: 'box', unitPrice: 9 }] }, { id: 'delivery-1', factoryId: 'F006', salesOrderId: 'so-1', customerId: 'cus-1', items: [{ salesOrderItemId: 'line-1', productTypeId: 'sku-1', deliveredQuantity: 3, unit: 'box' }] }],
  ])('rejects wrong quantity readback for %s', async (path, payload, wrong) => {
    await expect(verifyPageMutation(result(path, payload, wrong), 'F006', async () => wrong)).rejects.toThrow(/数量/);
  });

  it('uses the factory-scoped production plan as parent proof for settlement DTOs', async () => {
    const payload = { confirm: true };
    const wire = result('/production-plans/plan-1/settle', payload, { productionPlanId: 'plan-1' });
    const read = vi.fn()
      .mockResolvedValueOnce({ id: 'plan-1', factoryId: 'F006' })
      .mockResolvedValueOnce({ productionPlanId: 'plan-1', status: 'DONE', actualFinishedQuantity: 8, quantityUnit: 'box', postingStatus: 'PENDING_WAREHOUSE_RECEIPT', outputLines: [] });
    await expect(verifyPageMutation(wire, 'F006', read)).resolves.toContain('生产结单');
    expect(read).toHaveBeenNthCalledWith(1, '/production-plans/plan-1');
    expect(read).toHaveBeenNthCalledWith(2, '/production-plans/plan-1/settlement');
  });

  it('rejects settlement without parent proof or the required server quantity/status', async () => {
    const wire = result('/production-plans/plan-1/settle', { confirm: true }, { productionPlanId: 'plan-1' });
    await expect(verifyPageMutation(wire, 'F006', async () => ({ id: 'plan-1', factoryId: 'F006' }))).rejects.toThrow();
    const read = vi.fn().mockResolvedValueOnce({ id: 'plan-1', factoryId: 'F006' }).mockResolvedValueOnce({ productionPlanId: 'plan-1', postingStatus: 'PENDING_WAREHOUSE_RECEIPT' });
    await expect(verifyPageMutation(wire, 'F006', read)).rejects.toThrow(/数量/);
  });

  it('requires the real OA workflow grant and reads order plus approval progress', async () => {
    const wire = { ...result('/workflow/instances/wf-1/demo-actions', { expectedNodeId: 'node-1', idempotencyKey: 'idem-1', notes: 'RUN-1' }, { instanceId: 'wf-1', businessEntityId: 'po-1', workflowStatus: 'RUNNING', businessStatus: 'WORKFLOW_RUNNING' }), workflow: { instanceId: 'wf-1', moduleCode: 'PURCHASE_ORDER', businessEntityId: 'po-1', expectedNodeId: 'node-1', action: 'APPROVE', demoRunKey: 'RUN-1' } } as AgentWireResult & { workflow: unknown };
    const read = vi.fn().mockResolvedValueOnce({ id: 'po-1', factoryId: 'F006', status: 'WORKFLOW_RUNNING' }).mockResolvedValueOnce({ hasInstance: true, instanceId: 'wf-1', status: 'RUNNING', currentNodeIds: ['node-2'] });
    await expect(verifyPageMutation(wire, 'F006', read)).resolves.toContain('审批业务单');
    expect(read).toHaveBeenNthCalledWith(1, '/purchase/orders/po-1');
    expect(read).toHaveBeenNthCalledWith(2, '/purchase/orders/po-1/approval-progress');
    const missing = { ...wire, workflow: undefined } as AgentWireResult;
    expect(() => assertVerifiablePageMutation(missing)).toThrow(/审批上下文/);
  });

  it.each(['purchase', 'sales'])('matches reordered %s lines as a multiset without losing duplicate lines', async domain => {
    const key = domain === 'purchase' ? 'materialTypeId' : 'productTypeId';
    const items = [{ [key]: 'type-a', quantity: 2, unit: 'kg', unitPrice: 3 }, { [key]: 'type-b', quantity: 4, unit: 'kg', unitPrice: 8 }];
    const header = domain === 'purchase' ? { supplierId: 'sup' } : { customerId: 'cus' };
    const wire = result(`/${domain}/orders`, { ...header, items }, { id: 'order' });
    const actual = { id: 'order', factoryId: 'F006', ...header, items: [...items].reverse() };
    await expect(verifyPageMutation(wire, 'F006', async () => actual)).resolves.toContain('order');
    await expect(verifyPageMutation(wire, 'F006', async () => ({ ...actual, items: [items[0], items[0]] }))).rejects.toThrow('明细');
  });

  it.each([['purchase/orders/po/submit', 'WORKFLOW_RUNNING'], ['sales/orders/so/submit-for-review', 'PENDING_FINANCE_REVIEW']])('verifies the original submit endpoint %s', async (path, status) => {
    const id = path.includes('purchase') ? 'po' : 'so';
    const wire = result('/' + path, {}, {});
    expect(() => assertVerifiablePageMutation(wire)).not.toThrow();
    await expect(verifyPageMutation(wire, 'F006', async () => ({ id, factoryId: 'F006', status }))).resolves.toContain(id);
    await expect(verifyPageMutation(wire, 'F006', async () => ({ id, factoryId: 'F006', status: 'DRAFT' }))).rejects.toThrow('状态');
  });

  it('accepts actual process row DTOs with payload fields and parent proof, rejects another quantity', async () => {
    const payload = { clientRowId: 'client1', processCode: 'shuzhi', processOrder: 3, inputQuantity: 2, outputQuantity: 1.8 };
    const wire = result('/production-plans/plan/process-sheet/row/submit', payload, { clientRowId: 'client1' });
    const run = (output: number, factory = 'F006') => verifyPageMutation(wire, 'F006', async path => path.endsWith('/plan') ? { id: 'plan', factoryId: factory } : [{ clientRowId: 'client1', submissionStatus: 'SUBMITTED', payload: { ...payload, outputQuantity: output } }]);
    await expect(run(1.8)).resolves.toContain('client1');
    await expect(run(1.7)).rejects.toThrow('数量');
    await expect(run(1.8, 'F001')).rejects.toThrow('factoryId');
  });

  it('verifies a single-field cost query using only actual plan costs, without a settlement', async () => {
    const wire = { ...result('/production-plans/plan/costs', undefined, {}, 'PUT'), query: { equipmentCost: '0' } };
    expect(() => assertVerifiablePageMutation(wire)).not.toThrow();
    const read = vi.fn(async (path: string) => {
      if (path !== '/production-plans/plan') throw new Error('no settlement exists yet');
      return { id: 'plan', factoryId: 'F006', actualEquipmentCost: 0, actualOtherCost: 99 };
    });
    await expect(verifyPageMutation(wire, 'F006', read)).resolves.toContain('生产成本');
    await expect(verifyPageMutation(wire, 'F006', async () => ({ id: 'plan', factoryId: 'F006', actualEquipmentCost: 10 }))).rejects.toThrow('equipmentCost');
  });

  it('uses the original minimal requisition payload and confirms picked quantities without client batch choices', async () => {
    const generated = result('/material-requisitions/generate', { productionPlanId: 'plan' }, { id: 'req' });
    expect(() => assertVerifiablePageMutation(generated)).not.toThrow();
    await expect(verifyPageMutation(generated, 'F006', async () => ({ id: 'req', factoryId: 'F006', productionPlanId: 'plan' }))).resolves.toContain('req');
    expect(() => assertVerifiablePageMutation(request('/material-requisitions/generate', { productionPlanId: 'plan', plannedRawInputKg: 2 }))).toThrow('人工预估');
    const confirmed = result('/material-requisitions/req/confirm-picking', { items: [{ itemId: 'line', pickedQty: 2 }] }, {}, 'PUT');
    expect(() => assertVerifiablePageMutation(confirmed)).not.toThrow();
    const actual = { id: 'req', factoryId: 'F006', status: 'PICKING', pickedAt: '2026-11-07T12:00:00', items: [{ id: 'line', pickedQuantity: 2 }] };
    await expect(verifyPageMutation(confirmed, 'F006', async () => actual)).resolves.toContain('req');
    await expect(verifyPageMutation(confirmed, 'F006', async () => ({ ...actual, items: [{ id: 'line', pickedQuantity: 1 }] }))).rejects.toThrow('数量');
  });

  it.each([['start-picking', 'PICKING'], ['transfer', 'TRANSFERRED'], ['receive', 'ISSUED']])('checks requisition %s state', async (action, status) => {
    const wire = result(`/material-requisitions/req/${action}`, {}, {}, 'PUT');
    expect(() => assertVerifiablePageMutation(wire)).not.toThrow();
    await expect(verifyPageMutation(wire, 'F006', async () => ({ id: 'req', factoryId: 'F006', status }))).resolves.toContain(status);
    await expect(verifyPageMutation(wire, 'F006', async () => ({ id: 'req', factoryId: 'F006', status: 'PENDING' }))).rejects.toThrow('状态');
  });

  it('reads an actual child delivery via its created ID and maps mother rows to sales row IDs', async () => {
    const wire = result('/sales/deliveries/mother/shipments', { items: [{ parentDeliveryItemId: 'mother-line', quantity: 2 }] }, { id: 'child' });
    const parent = { id: 'mother', factoryId: 'F006', items: [{ id: 'mother-line', salesOrderItemId: 'sales-line', productTypeId: 'sku', unit: 'box' }] };
    const child = { id: 'child', factoryId: 'F006', parentDeliveryId: 'mother', items: [{ id: 'child-line', salesOrderItemId: 'sales-line', deliveredQuantity: 2 }] };
    const read = vi.fn(async path => path.endsWith('/mother') ? parent : child);
    await expect(verifyPageMutation(wire, 'F006', read)).resolves.toContain('child');
    expect(read).toHaveBeenNthCalledWith(2, '/sales/deliveries/child');
    await expect(verifyPageMutation(wire, 'F006', async path => path.endsWith('/mother') ? parent : { ...child, parentDeliveryId: 'other' })).rejects.toThrow('parentDeliveryId');
  });

  it('requires nonempty confirmed receipt batches and exact warehouse dispatch line sets', async () => {
    const receipt = result('/warehouse/receiving/receipts/receipt/confirm', {}, {});
    await expect(verifyPageMutation(receipt, 'F006', async () => ({ id: 'receipt', factoryId: 'F006', status: 'CONFIRMED', items: [{ materialBatchId: 'batch' }] }))).resolves.toContain('receipt');
    await expect(verifyPageMutation(receipt, 'F006', async () => ({ id: 'receipt', factoryId: 'F006', status: 'CONFIRMED', items: [] }))).rejects.toThrow('items');
    const dispatch = result('/warehouse/deliveries/child/confirm', { actualQuantities: { line: 2 } }, {});
    const actual = { id: 'child', factoryId: 'F006', status: 'SHIPPED', items: [{ id: 'line', deliveredQuantity: 2 }] };
    await expect(verifyPageMutation(dispatch, 'F006', async () => actual)).resolves.toContain('child');
    await expect(verifyPageMutation({ ...dispatch, payload: { actualQuantities: { line: 2, stale: 3 } } }, 'F006', async () => actual)).rejects.toThrow('集合');
  });

  it('verifies original purchase edits against the requested linked sale and all line values', async () => {
    const payload = { supplierId: 'sup', salesOrderId: 'so', items: [{ materialTypeId: 'mat', quantity: 2, unit: 'kg', unitPrice: 5 }] };
    const wire = result('/purchase/orders/po', payload, {}, 'PUT');
    const actual = { id: 'po', factoryId: 'F006', ...payload };
    expect(() => assertVerifiablePageMutation(wire)).not.toThrow();
    await expect(verifyPageMutation(wire, 'F006', async () => actual)).resolves.toContain('po');
    await expect(verifyPageMutation(wire, 'F006', async () => ({ ...actual, salesOrderId: 'other' }))).rejects.toThrow('salesOrderId');
    const create = result('/purchase/orders', payload, { id: 'po' });
    await expect(verifyPageMutation(create, 'F006', async () => ({ ...actual, salesOrderId: null }))).rejects.toThrow('salesOrderId');
    const unlink = { ...wire, payload: { ...payload, salesOrderId: null } };
    await expect(verifyPageMutation(unlink, 'F006', async () => actual)).rejects.toThrow('salesOrderId');
    await expect(verifyPageMutation(unlink, 'F006', async () => ({ ...actual, salesOrderId: null }))).resolves.toContain('po');
  });

  it('accepts the actual one-plan list response and checks its source sales line', async () => {
    const wire = result('/production-plans/batch-from-so', { sourceOrderId: 'so', itemIds: ['so-line'] }, [{ id: 'plan' }]);
    const plan = { id: 'plan', factoryId: 'F006', sourceOrderId: 'so', sourceOrderItemId: 'so-line' };
    await expect(verifyPageMutation(wire, 'F006', async () => plan)).resolves.toContain('plan');
    await expect(verifyPageMutation(wire, 'F006', async () => ({ ...plan, sourceOrderItemId: 'other-line' }))).rejects.toThrow('销售明细');
    expect(() => assertVerifiablePageMutation({ ...wire, payload: { sourceOrderId: 'so', itemIds: ['a', 'b'] } })).toThrow('单销售明细');
  });

  it('checks created production batch parent and every requested finished batch allocation', async () => {
    const batch = result('/production-plans/plan/create-batch', {}, { id: 10 });
    await expect(verifyPageMutation(batch, 'F006', async () => ({ id: 10, factoryId: 'F006', productionPlanId: 'plan' }))).resolves.toContain('10');
    await expect(verifyPageMutation(batch, 'F006', async () => ({ id: 10, factoryId: 'F006', productionPlanId: 'other' }))).rejects.toThrow('productionPlanId');
    const allocations = [{ finishedGoodsBatchId: 'batch1', allocatedQty: 1 }, { finishedGoodsBatchId: 'batch2', allocatedQty: 2 }];
    const wire = result('/sales-deliveries/items/line/batch-allocations', { allocations }, {});
    await expect(verifyPageMutation(wire, 'F006', async () => allocations.map(item => ({ ...item, factoryId: 'F006' })).reverse())).resolves.toContain('2 条');
    await expect(verifyPageMutation(wire, 'F006', async () => [{ ...allocations[0], factoryId: 'F006' }])).rejects.toThrow('同批次');
  });

  it('checks finished receipts against output products, batches and quantities with parent factory proof', async () => {
    const output = { productTypeId: 'sku', batchNumber: 'FG-1', receivedQuantity: 4, quantityUnit: 'box' };
    const wire = result('/production-plans/plan/warehouse-receipt', { outputLines: [output] }, {});
    const parent = { id: 'plan', factoryId: 'F006', productTypeId: 'sku' };
    const settlement = { productionPlanId: 'plan', postingStatus: 'POSTED', quantityUnit: 'box', warehouseReceivedQuantity: 4, outputLines: [{ ...output, reportedBatchNumber: output.batchNumber, finishedGoodsBatchId: 'fg' }] };
    await expect(verifyPageMutation(wire, 'F006', async path => path.endsWith('/settlement') ? settlement : parent)).resolves.toContain('成品入库');
    const legacy = { ...wire, payload: { receivedQuantity: 4, quantityUnit: 'box' } };
    expect(() => assertVerifiablePageMutation(legacy)).not.toThrow();
    await expect(verifyPageMutation(legacy, 'F006', async path => path.endsWith('/settlement') ? settlement : parent)).resolves.toContain('成品入库');
    await expect(verifyPageMutation(wire, 'F006', async path => path.endsWith('/settlement') ? { ...settlement, outputLines: [{ ...settlement.outputLines[0], reportedBatchNumber: 'WRONG' }] } : parent)).rejects.toThrow('明细');
  });

  it.each([{ hasInstance: false }, { instanceId: 'another-workflow' }, { status: 'APPROVED' }, { currentNodeIds: ['node-1'] }])('rejects another or stale workflow progress: %j', async changed => {
    const wire: AgentWireResult = { ...result('/workflow/instances/wf-1/actions', { expectedNodeId: 'node-1', idempotencyKey: 'oa-approve-wf-1-node-1' }, { instanceId: 'wf-1', businessEntityId: 'po-1', workflowStatus: 'RUNNING', businessStatus: 'WORKFLOW_RUNNING' }), workflow: { instanceId: 'wf-1', moduleCode: 'PURCHASE_ORDER', businessEntityId: 'po-1', expectedNodeId: 'node-1', action: 'APPROVE' } };
    const read = vi.fn().mockResolvedValueOnce({ id: 'po-1', factoryId: 'F006', status: 'WORKFLOW_RUNNING' }).mockResolvedValueOnce({ hasInstance: true, instanceId: 'wf-1', status: 'RUNNING', currentNodeIds: ['node-2'], ...changed });
    await expect(verifyPageMutation(wire, 'F006', read)).rejects.toThrow(/审批进度/);
  });
});
