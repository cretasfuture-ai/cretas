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
