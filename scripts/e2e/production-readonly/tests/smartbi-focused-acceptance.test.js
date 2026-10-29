'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const scenario = require('../scenarios/smartbi-focused-acceptance-readonly');

const commerce = scenario.validateCommerceOptions({
  expectedFamilies: ['commerce-joint.sales.stock', 'commerce-joint.customer.channel'],
  expectedKpisByUnit: { 个: { orders: 3, shipments: 3, stock: 7 } },
  expectedChannelAmount: 30,
});

function commerceBody(overrides = {}) {
  return {
    success: true,
    data: {
      factoryId: 'F006', source: 'business', period: { label: '全部历史', periodType: 'all', year: 2026 }, status: 'answered',
      recommendations: [
        { id: 'sales-stock', source: 'commerce-joint · 同厂关联', detailContext: { id: 'ctx-sales' }, analysis: { familyId: 'commerce-joint.sales.stock' }, unit: '个', kpis: [
          { label: '订单', value: 3 }, { label: '发货', value: 3 }, { label: '库存', value: 7 },
        ] },
        { id: 'customer-channel', source: 'commerce-joint · 同厂关联', detailContext: { id: 'ctx-channel' }, analysis: { familyId: 'commerce-joint.customer.channel' }, unit: '元', kpis: [
          { label: '渠道销售金额', value: 30, unit: '元' },
        ] },
      ],
      facts: [
        { id: 'fact-sales', candidateId: 'sales-stock', label: '订单', unit: '个', value: 3 },
        { id: 'fact-shipments', candidateId: 'sales-stock', label: '发货', unit: '个', value: 3 },
        { id: 'fact-stock', candidateId: 'sales-stock', label: '库存', unit: '个', value: 7 },
        { id: 'fact-channel', candidateId: 'customer-channel', label: '渠道销售金额', unit: '元', value: 30 },
      ].map((fact) => ({ ...fact, source: 'commerce-joint · 同厂关联' })),
      ...overrides,
    },
  };
}

const commerceUi = { visibleChartCount: 2, renderedCanvasCount: 2, factsVisible: true, detailsAllCharts: true, detailsPointFree: true, excelExported: true };

test('commerce joint acceptance requires exactly two grounded families and scoped facts', () => {
  const result = scenario.assessCommerce({
    body: commerceBody(),
    request: { source: 'business', question: '联合看客户渠道销售、发货和库存' },
    ui: commerceUi,
    expected: commerce,
  });
  assert.equal(result.result, 'PASS');
  assert.deepEqual(result.families.sort(), commerce.expectedFamilies.sort());
});

test('commerce negative cases reject missing family, fallback provider, and unrelated fact', () => {
  for (const body of [
    commerceBody({ recommendations: [{ id: 'sales-stock', analysis: { familyId: 'commerce-joint.sales.stock' } }] }),
    commerceBody({ status: 'unavailable' }),
    commerceBody({ facts: [{ id: 'foreign', candidateId: 'unrelated-family', value: 99 }] }),
  ]) {
    const result = scenario.assessCommerce({ body, request: { source: 'business', question: '联合看客户渠道销售、发货和库存' }, ui: commerceUi, expected: commerce });
    assert.equal(result.result, 'UNVERIFIED');
  }
});

test('legacy KPI acceptance requires the exact money values, not row count or truncated display', () => {
  const expected = { totalCost: 2500, materialCost: 1600, laborCost: 500, equipmentCost: 300 };
  assert.equal(scenario.assessLegacyKpi({
    visibleText: '总成本\n2,500\n材料成本\n1,600\n人工成本\n500\n设备成本\n300', expected,
  }).result, 'PASS');
  const wrong = scenario.assessLegacyKpi({
    visibleText: '总成本\n2\n材料成本\n1\n人工成本\n500\n设备成本\n300', expected,
  });
  assert.equal(wrong.result, 'PARTIAL_DEFECT');
  assert.equal(wrong.checks.totalCost, false);
  assert.equal(wrong.checks.materialCost, false);
});

test('focused scenarios are opt-in and option IDs are never hard-coded', () => {
  assert.equal(scenario.commerceJoint.defaultEnabled, false);
  assert.equal(scenario.legacyKpi.defaultEnabled, false);
  assert.throws(() => scenario.validateCommerceOptions({ expectedFamilies: ['one'] }), /two distinct/);
  assert.throws(() => scenario.validateLegacyOptions({ uploadIds: { cost: 0 }, expected: {} }), /positive IDs/);
});

test('focused scenarios reject another factory before opening any business page', async () => {
  const ctx = { expectedFactoryId: 'F007', smartbiCommerceJoint: commerce,
    smartbiLegacyKpi: { uploadIds: { production: 1, quality: 2, cost: 3 },
      expected: { totalCost: 2500, materialCost: 1600, laborCost: 500, equipmentCost: 300 } } };
  await assert.rejects(scenario.commerceJoint.run(ctx), /limited to F006/);
  await assert.rejects(scenario.legacyKpi.run(ctx), /limited to F006/);
});

test('legacy source IDs resolve to one UI batch without inventing a batch', () => {
  const uploads = [
    { id: 5287, fileName: 'QA.xlsx', sheetName: '成本', createdAt: '2026-10-29T04:00:02Z' },
    { id: 5286, fileName: 'QA.xlsx', sheetName: '质检', createdAt: '2026-10-29T04:00:01Z' },
    { id: 5285, fileName: 'QA.xlsx', sheetName: '生产', createdAt: '2026-10-29T04:00:00Z' },
    { id: 5000, fileName: 'other.xlsx', sheetName: '成本', createdAt: '2026-10-28T04:00:00Z' },
  ];
  assert.equal(scenario.findLegacyBatchIndex(uploads, { production: 5285, quality: 5286, cost: 5287 }), 0);
  assert.equal(scenario.findLegacyBatchIndex(uploads, { production: 9999, quality: 9998, cost: 9997 }), null);
});

test('purchase inventory requires exact trusted family, four equal facts and conservation', () => {
  const expected = scenario.validatePurchaseInventoryOptions({ expectedKpisByUnit: { kg: { received: 595, used: 20, reserved: 5, available: 570 } } });
  const candidate = { id: 'purchase-inventory-kg', source: 'purchase-batch-lineage · 已确认收货与物料批次双向来源', unit: 'kg', analysis: { familyId: 'purchase-batch-lineage.purchase.inventory' }, detailContext: { id: 'ctx-purchase' }, kpis: [
    { label: '收货', value: 595, unit: 'kg' }, { label: '已用', value: 20, unit: 'kg' }, { label: '预留', value: 5, unit: 'kg' }, { label: '当前可用', value: 570, unit: 'kg' },
  ] };
  const body = { success: true, data: { factoryId: 'F006', source: 'business', period: { label: '全部历史' }, limitations: ['库存为当前时点快照，不代表历史期末余额'], status: 'answered', recommendations: [candidate], facts: candidate.kpis.map((kpi, i) => ({ ...kpi, id: `fact-${i}`, candidateId: candidate.id, source: candidate.source })) } };
  const ui = { factsVisible: true, visibleChartCount: 1, renderedCanvasCount: 1, detailsValid: true, excelExported: true };
  assert.equal(scenario.assessPurchaseInventory({ body, request: { question: '联合分析采购收货与当前库存', source: 'business' }, ui, expected }).result, 'PASS');
  assert.equal(scenario.assessPurchaseInventory({ body: { ...body, data: { ...body.data, facts: body.data.facts.map((fact) => fact.label === '当前可用' ? { ...fact, value: 999 } : fact) } }, request: { question: '联合分析采购收货与当前库存', source: 'business' }, ui, expected }).result, 'UNVERIFIED');
  for (const wrong of [{ source: 'other' }, { candidateId: 'other' }, { unit: '箱' }]) {
    assert.equal(scenario.assessPurchaseInventory({ body: { ...body, data: { ...body.data, facts: body.data.facts.map((fact) => ({ ...fact, ...wrong })) } }, request: { question: '联合分析采购收货与当前库存', source: 'business' }, ui, expected }).result, 'UNVERIFIED');
  }
  assert.equal(scenario.assessPurchaseInventory({ body: { ...body, data: { ...body.data, limitations: [] } }, request: { question: '联合分析采购收货与当前库存', source: 'business' }, ui, expected }).result, 'UNVERIFIED');
});

test('purchase original details bind request pagination, owner scope, unique vouchers and reserved quantities', () => {
  const candidate = { id: 'p1', unit: 'kg', source: 'purchase-batch-lineage · confirmed', detailContext: { id: 'ctx1' } };
  const request = { context_id: 'ctx1', page: 1, page_size: 50, filters: [] };
  const row = { 收货单ID: 'receipt1', 收货行ID: 12, 物料批次ID: 'batch1', 数量单位: 'kg', 批次入库数量: 10, 批次已用数量: 2, 批次预留数量: 3, 当前可用数量: 5 };
  const body = { success: true, data: { factoryId: 'F006', candidateId: 'p1', source: candidate.source, period: '全部历史', rows: [row], total: 1 } };
  const args = { status: 200, body, request, candidate, period: '全部历史' };
  assert.equal(scenario.purchaseDetailMatches(args), true);
  for (const changed of [{ point: 'bad' }, { page: 2 }, { page_size: 500 }, { context_id: 'other' }]) assert.equal(scenario.purchaseDetailMatches({ ...args, request: { ...request, ...changed } }), false);
  for (const changed of [{ 数量单位: '箱' }, { 收货行ID: '' }, { 当前可用数量: 8 }, { 批次预留数量: null }]) assert.equal(scenario.purchaseDetailMatches({ ...args, body: { ...body, data: { ...body.data, rows: [{ ...row, ...changed }] } } }), false);
  for (const changed of [{ factoryId: 'F002' }, { source: 'other' }, { candidateId: 'other' }, { period: '2025' }, { total: undefined }, { total: 2, rows: [row, row] }]) assert.equal(scenario.purchaseDetailMatches({ ...args, body: { ...body, data: { ...body.data, ...changed } } }), false);
});
