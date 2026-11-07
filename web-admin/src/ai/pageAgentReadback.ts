import type { AgentWireRequest, AgentWireResult } from './pageAgentTransport';
type Dict = Record<string, unknown>;
type PayloadRequest = AgentWireRequest & {
    payload?: unknown;
};
type Workflow = {
    instanceId?: string;
    moduleCode?: 'PURCHASE_ORDER' | 'SALES_ORDER';
    businessEntityId?: string;
    expectedNodeId?: string;
    action?: 'APPROVE';
    demoRunKey?: string;
};
type Binding = {
    name: string;
    match: (method: string, path: string) => RegExpMatchArray | null;
    prepare: (payload: Dict, match: RegExpMatchArray, body: Dict) => {
        path: string;
        label: string;
    };
    verify: (value: unknown, payload: Dict, body: Dict, match: RegExpMatchArray, factoryId: string) => string;
};
const MUTATIONS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
const dict = (v: unknown): Dict => v !== null && typeof v === 'object' && !Array.isArray(v) ? v as Dict : {};
const unwrap = (v: unknown): unknown => { const o = dict(v); if (o.success === false) throw new Error('回读服务未确认成功'); return o.data === undefined ? v : o.data; };
const entity = (v: unknown): Dict => { const u = unwrap(v); return Array.isArray(u) ? dict(u[0]) : dict(u); };
function rows(v: unknown): Dict[] { const u = unwrap(v); if (Array.isArray(u))
    return u.map(dict); const o = dict(u); for (const k of ['content', 'items', 'records', 'rows', 'data'])
    if (Array.isArray(o[k]))
        return (o[k] as unknown[]).map(dict); return []; }
const asId = (v: unknown): string | undefined => v === undefined || v === null || v === '' ? undefined : String(v);
function required(v: unknown, label: string): string { const id = asId(v); if (!id)
    throw new Error(`${label}缺少稳定ID，已拒绝写入后的回读`); return id; }
function resultId(b: Dict, label: string): string { return required(b.id ?? b.orderId ?? b.receiptId ?? b.deliveryId ?? b.shipmentId ?? b.planId ?? b.batchId ?? b.productionBatchId ?? b.requisitionId, label); }
function payloadOf(r: AgentWireRequest): Dict { const v = (r as PayloadRequest).payload; const body = typeof v === 'string' ? (() => { try {
    return dict(JSON.parse(v));
}
catch {
    return {};
} })() : dict(v); const query = (r as AgentWireRequest & {
    query?: Readonly<Record<string, string>>;
}).query; const workflow = (r as AgentWireRequest & {
    workflow?: Workflow;
}).workflow; return { ...body, ...(query ?? {}), ...(workflow ? { __workflow: workflow } : {}) }; }
function need(p: Dict, keys: string[], label: string): void { for (const k of keys)
    if (p[k] === undefined || p[k] === null || p[k] === '')
        throw new Error(`${label}缺少必要字段 ${k}`); }
function needItems(p: Dict, label: string): Dict[] { if (!Array.isArray(p.items) || !p.items.length)
    throw new Error(`${label}缺少明细`); const a = p.items.map(dict); for (const i of a)
    need(i, ['quantity', 'unit', 'unitPrice'], `${label}明细`); return a; }
function needReceivingItems(p: Dict): Dict[] { if (!Array.isArray(p.items) || !p.items.length)
    throw new Error('收货单缺少明细'); const a = p.items.map(dict); for (const i of a)
    need(i, ['purchaseOrderItemId', 'materialTypeId', 'receivedQuantity', 'unit'], '收货单明细'); return a; }
function needDeliveryItems(p: Dict): Dict[] { if (!Array.isArray(p.items) || !p.items.length)
    throw new Error('发货安排缺少明细'); const a = p.items.map(dict); for (const i of a)
    need(i, ['salesOrderItemId', 'productTypeId', 'deliveredQuantity', 'unit', 'unitPrice'], '发货安排明细'); return a; }
function factory(v: Dict, f: string): void { const a = v.factoryId ?? v.factory_id ?? v.tenantId ?? v.tenant_id; if (a === undefined || String(a) !== f)
    throw new Error('回读实体缺少匹配当前账号的 factoryId'); }
function same(v: Dict, k: string, e: unknown): void { if (!asId(v[k]) || asId(v[k]) !== asId(e))
    throw new Error(`回读 ${k} 与本次请求不匹配`); }
function check(v: unknown, f: string, expected: string, parents: Array<[
    string,
    unknown
]> = []): Dict { const o = entity(v); factory(o, f); const actual = asId(o.id ?? o.orderId ?? o.receiptId ?? o.deliveryId ?? o.shipmentId ?? o.planId ?? o.batchId ?? o.productionBatchId ?? o.requisitionId); if (!actual || actual !== expected)
    throw new Error('回读实体ID与写入响应不匹配'); for (const [k, e] of parents)
    same(o, k, e); return o; }
function state(v: Dict): string { return String(v.status ?? v.state ?? v.submissionStatus ?? v.postingStatus ?? ''); }
function exact(v: Dict, allowed: string[]): void { if (!allowed.includes(state(v)))
    throw new Error(`回读状态不符合本步骤契约：${state(v) || '缺失'}`); }
function summary(label: string, v: Dict): string { return `${label} ${required(v.orderNumber ?? v.receiptNumber ?? v.deliveryNumber ?? v.shipmentNumber ?? v.planNumber ?? v.batchNumber ?? v.finishedBatchNumber ?? v.id ?? v.settlementId ?? v.productionPlanId, label)}，状态 ${state(v) || '已回读'}`; }
function pathMatch(method: string, re: RegExp, path: string): RegExpMatchArray | null { return re.test(path) ? path.match(re) : null; }
/** Child collections have no ordering contract. Consume each matching row once,
 * so duplicate lines cannot hide a missing or differently priced line. */
function matchItems(got: Dict[], want: Dict[], keys: string[], numeric: string[], label: string): void {
    if (got.length !== want.length) throw new Error(`${label}回读明细数量不匹配`);
    const remaining = [...got];
    for (const expected of want) {
        const index = remaining.findIndex(actual => keys.every(key => {
            const value = key === 'unitPrice' ? actual.unitPrice ?? actual.price : actual[key];
            if (value === undefined || value === null || expected[key] === undefined || expected[key] === null) return false;
            return numeric.includes(key) ? Number.isFinite(Number(value)) && Number(value) === Number(expected[key]) : String(value) === String(expected[key]);
        }));
        if (index < 0) throw new Error(`${label}回读明细、数量、单位或价格不匹配`);
        remaining.splice(index, 1);
    }
}
function verifyPurchase(value: unknown, payload: Dict, expectedId: string, factoryId: string): string {
    const order = check(value, factoryId, expectedId, [['supplierId', payload.supplierId]]);
    matchItems(rows(order.items), needItems(payload, '采购单'), ['materialTypeId', 'quantity', 'unit', 'unitPrice'], ['quantity', 'unitPrice'], '采购单');
    if (Object.hasOwn(payload, 'salesOrderId') || Object.hasOwn(payload, 'relatedSalesOrderId')) {
        const expected = Object.hasOwn(payload, 'salesOrderId') ? payload.salesOrderId : payload.relatedSalesOrderId;
        const actual = Object.hasOwn(order, 'salesOrderId') ? order.salesOrderId : order.relatedSalesOrderId;
        if (asId(actual) !== asId(expected)) throw new Error('采购单 salesOrderId 与本次关联请求不匹配');
    }
    return summary('采购单', order);
}
const bindings: Binding[] = [
    { name: 'purchase-update', match: (m, p) => m === 'PUT' ? p.match(/^\/purchase\/orders\/([^/]+)$/) : null,
      prepare: (p, m) => { need(p, ['supplierId'], '采购单'); needItems(p, '采购单'); return { path: `/purchase/orders/${m[1]}`, label: '采购单' }; },
      verify: (v, p, _b, m, f) => verifyPurchase(v, p, required(m[1], '采购单'), f) },
    { name: 'order-submit', match: (m, p) => m === 'POST' ? p.match(/^\/(purchase\/orders\/([^/]+)\/submit|sales\/orders\/([^/]+)\/submit-for-review)$/) : null,
      prepare: (_p, m) => ({ path: m[2] ? `/purchase/orders/${m[2]}` : `/sales/orders/${m[3]}`, label: '提交审批' }),
      verify: (v, _p, _b, m, f) => { const o = check(v, f, required(m[2] ?? m[3], '订单')); exact(o, [m[2] ? 'WORKFLOW_RUNNING' : 'PENDING_FINANCE_REVIEW']); return summary('已提交审批', o); } },
    { name: 'purchase-create', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/purchase\/orders$/, p) : null, prepare: (p, _m, b) => { need(p, ['supplierId'], '采购单'); const a = needItems(p, '采购单'); for (const i of a)
            need(i, ['materialTypeId'], '采购单明细'); return { path: `/purchase/orders/${resultId(b, '采购单')}`, label: '采购单' }; }, verify: (v, p, b, _m, f) => verifyPurchase(v, p, resultId(b, '采购单'), f) },
    { name: 'sales-create', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/sales\/orders$/, p) : null, prepare: (p, _m, b) => { need(p, ['customerId'], '销售单'); const a = needItems(p, '销售单'); for (const i of a)
            need(i, ['productTypeId'], '销售单明细'); return { path: `/sales/orders/${resultId(b, '销售单')}`, label: '销售单' }; }, verify: (v, p, b, _m, f) => { const o = check(v, f, resultId(b, '销售单'), [['customerId', p.customerId]]); const got = rows(o.items), want = needItems(p, '销售单'); if (got.length !== want.length)
            throw new Error('销售单回读明细数量不匹配'); matchItems(got, want, ['productTypeId', 'quantity', 'unit', 'unitPrice'], ['quantity', 'unitPrice'], '销售单'); return summary('销售单', o); } },
    { name: 'receiving-create', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/warehouse\/receiving\/receipts$/, p) : null, prepare: (p, _m, b) => { need(p, ['purchaseOrderId', 'supplierId', 'warehouseId'], '收货单'); needReceivingItems(p); return { path: `/warehouse/receiving/receipts/${resultId(b, '收货单')}`, label: '收货单' }; }, verify: (v, p, b, _m, f) => { const o = check(v, f, resultId(b, '收货单'), [['purchaseOrderId', p.purchaseOrderId], ['supplierId', p.supplierId], ['warehouseId', p.warehouseId]]); const got = rows(o.items), want = needReceivingItems(p); if (got.length !== want.length)
            throw new Error('收货单回读明细数量不匹配'); matchItems(got, want, ['purchaseOrderItemId', 'materialTypeId', 'receivedQuantity', 'unit'], ['receivedQuantity'], '收货单'); return summary('收货单', o); } },
    { name: 'receiving-confirm', match: (m, p) => ['POST', 'PUT'].includes(m) ? pathMatch(m, /^\/warehouse\/receiving\/receipts\/([^/]+)\/confirm$/, p) : null, prepare: (p, m) => { need(p, [], '收货确认'); return { path: `/warehouse/receiving/receipts/${required(m[1], '收货单')}`, label: '收货单' }; }, verify: (v, _p, _b, m, f) => { const o = check(v, f, required(m[1], '收货单')); exact(o, ['CONFIRMED']); const items = rows(o.items); if (!items.length || items.some(i => !i.materialBatchId))
            throw new Error('收货确认回读缺少非空 items/materialBatchId'); return summary('收货单', o); } },
    { name: 'production-plan-create', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/production-plans\/batch-from-so$/, p) : null, prepare: (p, _m, b) => { need(p, ['sourceOrderId', 'itemIds'], '生产计划'); if (!Array.isArray(p.itemIds) || p.itemIds.length !== 1)
            throw new Error('生产计划当前仅支持单销售明细的确定性回读'); return { path: `/production-plans/${resultId(b, '生产计划')}`, label: '生产计划' }; }, verify: (v, p, b, _m, f) => { const o = check(v, f, resultId(b, '生产计划'), [['sourceOrderId', p.sourceOrderId]]); if (String(o.sourceOrderItemId) !== String((p.itemIds as unknown[])[0]))
            throw new Error('生产计划销售明细不匹配'); return summary('生产计划', o); } },
    { name: 'production-batch-create', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/production-plans\/([^/]+)\/create-batch$/, p) : null, prepare: (p, _m, b) => { need(p, [], '生产批次'); return { path: `/processing/batches/${resultId(b, '生产批次')}`, label: '生产批次' }; }, verify: (v, _p, b, m, f) => summary('生产批次', check(v, f, resultId(b, '生产批次'), [['productionPlanId', m[1]]])) },
    { name: 'requisition-generate', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/material-requisitions\/generate$/, p) : null, prepare: (p, _m, b) => { need(p, ['productionPlanId'], '领料单'); if (p.plannedRawInputKg != null) throw new Error('AI暂不能核对人工预估投入对调料需求的影响，请交还原页面手动生成领料单'); return { path: `/material-requisitions/${resultId(b, '领料单')}`, label: '领料单' }; }, verify: (v, p, b, _m, f) => summary('领料单', check(v, f, resultId(b, '领料单'), [['productionPlanId', p.productionPlanId]])) },
    { name: 'requisition-state', match: (m, p) => ['PUT', 'POST'].includes(m) ? pathMatch(m, /^\/material-requisitions\/([^/]+)\/(start-picking|confirm-picking|transfer|receive)$/, p) : null, prepare: (p, m) => { if (m[2] === 'confirm-picking') {
            need(p, ['items'], '领料确认');
            if (!Array.isArray(p.items) || !p.items.length)
                throw new Error('领料确认缺少明细');
            for (const i of p.items.map(dict))
                need(i, ['itemId', 'pickedQty'], '领料确认明细');
        } return { path: `/material-requisitions/${required(m[1], '领料单')}`, label: '领料单' }; }, verify: (v, p, _b, m, f) => { const o = check(v, f, required(m[1], '领料单')); const expected: Record<string, string[]> = { 'start-picking': ['PICKING'], 'confirm-picking': ['PICKING', 'PICKED'], transfer: ['TRANSFERRED'], receive: ['ISSUED'] }; exact(o, expected[m[2]]); if (m[2] === 'confirm-picking') {
            if (!o.pickedAt)
                throw new Error('领料确认回读缺少 pickedAt');
            const got = rows(o.items);
            for (const sent of p.items as unknown[]) {
                const s = dict(sent);
                const row = got.find(i => String(i.id) === String(s.itemId));
                if (!row)
                    throw new Error('领料确认回读缺少同一明细');
                if (Number(row.pickedQty ?? row.pickedQuantity) !== Number(s.pickedQty))
                    throw new Error('领料确认数量不匹配');
                const batches = Array.isArray(row.batchNumbers) ? row.batchNumbers.map(dict) : [];
                const expectedBatches = Array.isArray(s.batchNumbers) ? s.batchNumbers.map(dict) : [];
                if (s.batchNumbers !== undefined && (!expectedBatches.length || batches.length !== expectedBatches.length || expectedBatches.some(e => !batches.some(g => String(g.batchId) === String(e.batchId) && Number(g.qty) === Number(e.qty)))))
                    throw new Error('领料确认批次/数量不匹配');
            }
        } return summary('领料单', o); } },
    { name: 'process-row', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/production-plans\/([^/]+)\/process-sheet\/row\/submit$/, p) : null, prepare: (p, m) => { need(p, ['clientRowId', 'processCode', 'processOrder', 'inputQuantity', 'outputQuantity'], '工序报工'); return { path: `/production-plans/${required(m[1], '生产计划')}/process-sheet/rows?process=${encodeURIComponent(String(p.processCode))}&processOrder=${encodeURIComponent(String(p.processOrder))}`, label: '工序报工' }; }, verify: (v, p, _b, m, _f) => { const row = rows(v).find(i => String(i.clientRowId) === String(p.clientRowId)); if (!row)
            throw new Error('工序报工回读缺少同一 clientRowId'); exact(row, ['SUBMITTED']); const child = entity(row.payload); if (String(child.clientRowId ?? row.clientRowId) !== String(p.clientRowId) || String(child.processCode) !== String(p.processCode) || Number(child.processOrder) !== Number(p.processOrder) || Number(child.inputQuantity) !== Number(p.inputQuantity) || Number(child.outputQuantity) !== Number(p.outputQuantity))
            throw new Error('工序报工 payload 字段或数量回读不匹配'); return `工序报工 ${required(row.clientRowId, '工序行')}，状态 ${row.submissionStatus}`; } },
    { name: 'settle', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/production-plans\/([^/]+)\/settle$/, p) : null, prepare: (p, m) => { need(p, ['confirm'], '生产结单'); if (p.actualFinishedQuantity !== undefined)
            need(p, ['quantityUnit'], '结单数量覆盖'); return { path: `/production-plans/${required(m[1], '生产计划')}/settlement`, label: '生产结单' }; }, verify: (v, p, _b, m, f) => { const plan = entity((p as Dict).__parent); if (!plan.factoryId)
            throw new Error('结单父计划未完成工厂证明'); const o = entity(v); if (String(o.productionPlanId) !== String(m[1]))
            throw new Error('结单 productionPlanId 不匹配'); if (!o.actualFinishedQuantity || !o.quantityUnit)
            throw new Error('结单回读缺少服务端数量/单位'); if (p.actualFinishedQuantity !== undefined && (Number(o.actualFinishedQuantity) !== Number(p.actualFinishedQuantity) || String(o.quantityUnit) !== String(p.quantityUnit)))
            throw new Error('结单覆盖数量/单位不匹配'); if (String(o.postingStatus) !== 'PENDING_WAREHOUSE_RECEIPT')
            throw new Error('结单尚未进入待成品入库状态'); return summary('生产结单', o); } },
    { name: 'warehouse-receipt', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/production-plans\/([^/]+)\/warehouse-receipt$/, p) : null, prepare: (p, m) => { if ((!Array.isArray(p.outputLines) || !p.outputLines.length) && p.receivedQuantity === undefined)
            throw new Error('成品入库缺少 outputLines 或 receivedQuantity'); if (p.receivedQuantity !== undefined)
            need(p, ['quantityUnit'], '成品入库单产出'); if (Array.isArray(p.outputLines))
            for (const i of p.outputLines.map(dict))
                need(i, ['productTypeId', 'batchNumber', 'receivedQuantity', 'quantityUnit'], '成品入库产出'); return { path: `/production-plans/${required(m[1], '生产计划')}/settlement`, label: '成品入库' }; }, verify: (v, p, _b, m, _f) => { const o = entity(v); if (String(o.productionPlanId) !== String(m[1]))
            throw new Error('成品入库 productionPlanId 不匹配'); if (!['POSTED', 'POSTED_WITH_TOLERANCE'].includes(String(o.postingStatus)))
            throw new Error('成品入库尚未 POSTED'); const got = rows(o.outputLines); if (!got.length)
            throw new Error('成品入库回读缺少 outputLines');
        if (!Array.isArray(p.outputLines) || p.outputLines.length === 0) {
            if (got.length !== 1 || Number(o.warehouseReceivedQuantity) !== Number(p.receivedQuantity) || String(o.quantityUnit) !== String(p.quantityUnit)) throw new Error('成品入库单产出总量或单位不匹配');
            const parent = entity(p.__parent);
            if (String(got[0].productTypeId) !== String(parent.productTypeId) || !got[0].reportedBatchNumber || Number(got[0].receivedQuantity) !== Number(p.receivedQuantity) || String(got[0].quantityUnit) !== String(p.quantityUnit)) throw new Error('成品入库单产出产品或批次数量不匹配');
        }
        const want = Array.isArray(p.outputLines) ? p.outputLines.map(dict) : [];
        if (want.length) matchItems(got.map(line => ({ ...line, batchNumber: line.reportedBatchNumber })), want, ['productTypeId', 'batchNumber', 'receivedQuantity', 'quantityUnit'], ['receivedQuantity'], '成品入库');
        return `成品入库 生产计划 ${required(m[1], '生产计划')}，状态 ${o.postingStatus}`; } },
    { name: 'cost-update', match: (m, p) => m === 'PUT' ? pathMatch(m, /^\/production-plans\/([^/]+)\/costs(?:\?.*)?$/, p) : null, prepare: (p, m) => { if (p.equipmentCost === undefined && p.otherCost === undefined)
            throw new Error('成本更新至少需要一个 query 成本字段'); return { path: `/production-plans/${required(m[1], '生产计划')}`, label: '生产成本' }; }, verify: (v, p, _b, m, f) => { const o = check(v, f, required(m[1], '生产计划')); for (const [key, actual] of [['equipmentCost', o.actualEquipmentCost], ['otherCost', o.actualOtherCost]] as const)
            if (p[key] !== undefined && Number(actual) !== Number(p[key]))
                throw new Error(`成本 ${key} 回读不匹配`); return summary('生产成本', o); } },
    { name: 'delivery-create', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/sales\/deliveries$/, p) : null, prepare: (p, _m, b) => { need(p, ['salesOrderId', 'customerId'], '发货安排'); needDeliveryItems(p); return { path: `/sales/deliveries/${resultId(b, '发货安排')}`, label: '发货安排' }; }, verify: (v, p, b, _m, f) => { const o = check(v, f, resultId(b, '发货安排'), [['salesOrderId', p.salesOrderId], ['customerId', p.customerId]]); const got = rows(o.items), want = needDeliveryItems(p); if (got.length !== want.length)
            throw new Error('发货安排回读明细数量不匹配'); matchItems(got, want, ['salesOrderItemId', 'productTypeId', 'deliveredQuantity', 'unit', 'unitPrice'], ['deliveredQuantity', 'unitPrice'], '发货安排'); return summary('发货安排', o); } },
    { name: 'shipment-create', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/sales\/deliveries\/([^/]+)\/shipments$/, p) : null, prepare: (p, _m, b) => { need(p, ['items'], '发运单'); for (const i of (p.items as unknown[]).map(dict))
            need(i, ['parentDeliveryItemId', 'quantity'], '发运单明细'); return { path: `/sales/deliveries/${resultId(b, '发运单')}`, label: '发运单' }; }, verify: (v, p, b, m, f) => { const o = check(v, f, resultId(b, '发运单'), [['parentDeliveryId', m[1]]]); const got = rows(o.items), want = p.items as unknown[], parents = (p.__parentItems as Dict[] | undefined) ?? []; if (got.length !== want.length)
            throw new Error('发运单明细数量不匹配'); for (const w of want) {
            const e = dict(w), parent = parents.find(i => String(i.id) === String(e.parentDeliveryItemId));
            if (!parent)
                throw new Error('发运单请求父明细不属于母发货单');
            const row = got.find(i => String(i.salesOrderItemId) === String(parent.salesOrderItemId));
            if (!row || Number(row.deliveredQuantity) !== Number(e.quantity))
                throw new Error('发运单销售行或发货数量不匹配');
        } return summary('发运单', o); } },
    { name: 'batch-allocation', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/sales-deliveries\/items\/([^/]+)\/batch-allocations$/, p) : null, prepare: (p, m) => { if (!Array.isArray(p.allocations) || !p.allocations.length)
            throw new Error('批次分配缺少 allocations'); return { path: `/sales-deliveries/items/${required(m[1], '销售明细')}/batch-allocations`, label: '批次分配' }; }, verify: (v, p, _b, _m, f) => { const list = rows(v); if (!list.length)
            throw new Error('批次分配回读为空'); for (const wanted of p.allocations as unknown[]) {
            const e = dict(wanted);
            const found = list.find(i => String(i.finishedGoodsBatchId) === String(e.finishedGoodsBatchId) && Number(i.allocatedQty) === Number(e.allocatedQty));
            if (!found)
                throw new Error('批次分配回读缺少同批次同数量记录');
            factory(found, f);
        } return `批次分配 ${list.length} 条，状态 已回读`; } },
    { name: 'warehouse-confirm', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/warehouse\/deliveries\/([^/]+)\/confirm$/, p) : null, prepare: (p, m) => { need(p, ['actualQuantities'], '销售出库确认'); return { path: `/sales/deliveries/${required(m[1], '发运单')}`, label: '销售出库' }; }, verify: (v, p, _b, m, f) => { const o = check(v, f, required(m[1], '发运单')); exact(o, ['SHIPPED', 'DELIVERED']); const got = rows(o.items), quantities = dict(p.actualQuantities); if (!got.length)
            throw new Error('销售出库回读缺少明细'); const ids = got.map(row => String(row.id)).sort(); const sentIds = Object.keys(quantities).sort(); if (ids.length !== sentIds.length || ids.some((id, i) => id !== sentIds[i]))
            throw new Error('销售出库 actualQuantities 与回读明细集合不一致'); for (const row of got) {
            const expected = quantities[String(row.id)];
            if (Number(row.deliveredQuantity ?? row.quantity) !== Number(expected))
                throw new Error('销售出库明细数量回读不匹配');
        } return summary('销售出库', o); } },
    { name: 'workflow-approve', match: (m, p) => m === 'POST' ? pathMatch(m, /^\/workflow\/instances\/([^/]+)\/(?:actions|demo-actions)$/, p) : null, prepare: (p, m, b) => { const w = (p.__workflow ?? {}) as Workflow; need(w as Dict, ['instanceId', 'moduleCode', 'businessEntityId', 'expectedNodeId', 'action'], '审批上下文'); if (String(w.instanceId) !== String(m[1]) || w.action !== 'APPROVE')
            throw new Error('审批上下文与请求路径不匹配'); need(p, ['expectedNodeId', 'idempotencyKey'], '审批请求'); if (w.demoRunKey && !String(p.notes ?? '').includes(w.demoRunKey))
            throw new Error('演示审批缺少本轮标记'); const root = w.moduleCode === 'PURCHASE_ORDER' ? 'purchase/orders' : w.moduleCode === 'SALES_ORDER' ? 'sales/orders' : ''; if (!root)
            throw new Error('不支持的审批模块'); return { path: `/${root}/${required(w.businessEntityId, '审批业务实体')}`, label: '审批业务单' }; }, verify: (v, p, _b, _m, f) => summary('审批业务单', check(v, f, required((p.__workflow as Workflow).businessEntityId, '审批业务实体'))) },
];
function bindingFor(r: AgentWireRequest): {
    binding: Binding;
    match: RegExpMatchArray;
} { const found = bindings.map(binding => ({ binding, match: binding.match(r.method.toUpperCase(), r.canonicalPath) })).find(i => i.match); if (!found)
    throw new Error(`未注册可确定回读的业务 mutation：${r.method} ${r.canonicalPath}`); return found as {
    binding: Binding;
    match: RegExpMatchArray;
}; }
export function assertVerifiablePageMutation(request: AgentWireRequest): void {
    if (!MUTATIONS.has(request.method.toUpperCase()) || request.kind !== 'business') throw new Error('只有业务 mutation 才允许进入回读闸');
    const { binding, match } = bindingFor(request);
    // Validate the endpoint and original payload before the create response ID exists.
    // This placeholder is never sent, persisted, or accepted as readback evidence.
    binding.prepare(payloadOf(request), match, { id: 'preflight-placeholder' });
}
export async function verifyPageMutation(result: AgentWireResult, factoryId: string, read: (canonicalPath: string) => Promise<unknown>): Promise<string> {
    if (!result.ok || result.status < 200 || result.status >= 300) throw new Error('业务写入未成功，禁止执行回读');
    if (!factoryId) throw new Error('缺少当前工厂，禁止回读');
    const { binding, match } = bindingFor(result);
    const payload = payloadOf(result);
    const body = entity(result.body);
    if (binding.name === 'production-plan-create' && Array.isArray(unwrap(result.body)) && (unwrap(result.body) as unknown[]).length !== 1) throw new Error('创建计划的响应数量与本次单销售明细不匹配');
    if (['settle', 'warehouse-receipt', 'cost-update', 'process-row'].includes(binding.name)) {
    const parent = entity(await read(`/production-plans/${required(match[1], '生产计划')}`));
    factory(parent, factoryId);
    if (String(parent.id) !== String(match[1]))
        throw new Error('生产计划父级ID不匹配');
    payload.__parent = parent;
    }
    if (binding.name === 'shipment-create') {
    const parent = entity(await read(`/sales/deliveries/${required(match[1], '母发货单')}`));
    factory(parent, factoryId);
    payload.__parentItems = rows(parent.items);
    }
    const target = binding.prepare(payload, match, body);
    const value = await read(target.path);
    if (binding.name === 'workflow-approve') {
    const w = payload.__workflow as Workflow;
    if (String(body.instanceId) !== String(w.instanceId) || String(body.businessEntityId) !== String(w.businessEntityId) || body.workflowStatus === undefined || body.businessStatus === undefined)
        throw new Error('审批响应缺少或不匹配实例/业务状态');
    const root = w.moduleCode === 'PURCHASE_ORDER' ? 'purchase/orders' : 'sales/orders';
    const progress = entity(await read(`/${root}/${required(w.businessEntityId, '审批业务实体')}/approval-progress`));
    if (progress.factoryId !== undefined)
        factory(progress, factoryId);
    if (progress.hasInstance !== true || String(progress.instanceId) !== String(w.instanceId) || progress.status !== body.workflowStatus)
        throw new Error('审批进度实例或流程状态与本次响应不匹配');
    const expectedBusiness = body.workflowStatus === 'APPROVED' ? 'FINANCE_APPROVED' : (w.moduleCode === 'PURCHASE_ORDER' ? 'WORKFLOW_RUNNING' : 'PENDING_FINANCE_REVIEW');
    if (!['RUNNING', 'APPROVED'].includes(String(body.workflowStatus)) || body.businessStatus !== expectedBusiness || entity(value).status !== expectedBusiness)
        throw new Error('审批响应/业务状态不匹配');
    if (!Array.isArray(progress.currentNodeIds) || progress.currentNodeIds.some(node => String(node) === String(w.expectedNodeId)))
        throw new Error('审批进度节点缺失或仍停留旧节点');
    }
    return binding.verify(value, payload, body, match, factoryId);
}
