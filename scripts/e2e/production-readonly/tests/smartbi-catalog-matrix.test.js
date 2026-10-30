'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const catalog = require('../scenarios/smartbi-catalog-matrix-readonly');

function candidate(id, family, extra = {}) {
  return {
    id,
    title: family,
    source: 'business',
    unit: 'kg',
    analysis: { familyId: family, familyLabel: family },
    chart: { chartType: 'bar', echartsOption: { series: [] } },
    ...extra,
  };
}

test('catalog options are bounded, unique, and fail closed', () => {
  assert.deepEqual(catalog.validateCatalogFamilies(['production.output', 'cost.unit']), ['production.output', 'cost.unit']);
  assert.equal(catalog.validateCatalogFamilies(null), null);
  assert.throws(() => catalog.validateCatalogFamilies('production.output'), /array/);
  assert.throws(() => catalog.validateCatalogFamilies(['same', 'same']), /duplicate/);
  assert.throws(() => catalog.validateCatalogFamilies(Array.from({ length: 61 }, (_, i) => `family-${i}`)), /at most 60/);
  assert.deepEqual(catalog.validateCatalogCandidateIds(['same@kg', 'same@件']), ['same@kg', 'same@件']);
  assert.throws(() => catalog.validateCatalogCandidateIds(['same@kg', 'same@kg']), /duplicate/);
  assert.throws(() => catalog.validateCatalogCandidateIds(Array.from({ length: 121 }, (_, i) => `candidate-${i}`)), /at most 120/);
  assert.equal(catalog.validateCatalogSubjectId('product-a'), 'product-a');
  assert.equal(catalog.validateCatalogSubjectId(null), null);
  assert.throws(() => catalog.validateCatalogSubjectId(''), /non-empty/);
});

test('explicit candidate IDs keep same-family unit/source variants visible', () => {
  const recommendations = [
    candidate('family@kg@business', 'production.output', { unit: 'kg', source: 'business' }),
    candidate('family@件@business', 'production.output', { unit: '件', source: 'business' }),
    candidate('family@kg@xiaomi', 'production.output', { unit: 'kg', source: 'xiaomi' }),
  ];
  const selected = catalog.selectCatalogCandidates({ recommendations }, null, ['family@kg@business', 'family@件@business', 'family@kg@xiaomi']);
  assert.deepEqual(selected.selected.map((item) => item.candidate.id), ['family@kg@business', 'family@件@business', 'family@kg@xiaomi']);
  assert.deepEqual(selected.requestedCandidateUnknown, []);
  assert.deepEqual(catalog.selectCatalogCandidates({ recommendations }, null, ['missing']).requestedCandidateUnknown, ['missing']);
});

test('catalog matrix groups variants and caps selected families at four', () => {
  const recommendations = Array.from({ length: 6 }, (_, index) => candidate(`c-${index}`, `family-${index}`));
  recommendations.push(candidate('restaurant', 'restaurant.sales', { domain: 'restaurant' }));
  const grouped = catalog.groupCandidates(recommendations);
  assert.equal(grouped.length, 7);
  const selected = catalog.selectCatalogCandidates({ recommendations }, null);
  assert.equal(selected.selected.length, 6);
  assert.equal(selected.deferred.length, 0);
  assert.equal(selected.excluded.length, 1);
  assert.equal(selected.excluded[0].reason, 'restaurant-only');
});

test('the 60-family run bound is explicit and cannot report a partial matrix as green', () => {
  const recommendations = Array.from({ length: 61 }, (_, index) => candidate(`c-${index}`, `family-${index}`));
  const selected = catalog.selectCatalogCandidates({ recommendations }, null);
  assert.equal(selected.selected.length, 60);
  assert.equal(selected.deferred.length, 1);
  const assessment = catalog.assessCatalog({
    families: selected.selected.map((item) => ({ familyId: item.familyId, status: 'PASS', checkFailures: [] })),
    requestedUnknown: [], deferred: selected.deferred, excluded: [], ui: {},
  });
  assert.equal(assessment.result, 'UNVERIFIED');
  assert.equal(assessment.remainingFamilyCount, 0);
});

test('catalog selection records unknown requested families instead of silently dropping them', () => {
  const selected = catalog.selectCatalogCandidates({ recommendations: [candidate('c-1', 'production.output')] }, ['missing.family']);
  assert.deepEqual(selected.selected, []);
  assert.deepEqual(selected.requestedUnknown, ['missing.family']);
});

test('detail-ineligible or unavailable candidates never become qualified', () => {
  assert.equal(catalog.candidateIsQualified(candidate('ok', 'production.output')), true);
  assert.equal(catalog.candidateIsQualified(candidate('no-detail', 'production.output', { detailStatus: { state: 'capacity', message: 'busy' } })), false);
  assert.equal(catalog.candidateIsQualified(candidate('no-chart', 'production.output', { chart: null })), false);
  const blocked = catalog.selectCatalogCandidates({ recommendations: [candidate('capacity', 'production.output', { detailStatus: { state: 'capacity', message: 'busy' } })] }, null);
  assert.equal(blocked.selected.length, 0);
  assert.equal(blocked.blocked[0].reason, 'capacity');
});

test('quantity-family facts cannot silently drift to another unit', () => {
  assert.equal(catalog.candidateUnitsAligned(candidate('kg-ok', 'production.output', { kpis: [{ label: '产量', value: 1, unit: 'kg' }] })), true);
  assert.equal(catalog.candidateUnitsAligned(candidate('kg-drift', 'production.output', { kpis: [{ label: '产量', value: 1, unit: '件' }] })), false);
  assert.equal(catalog.candidateUnitsAligned(candidate('financial-mixed', 'finance.margin', { unit: '', kpis: [{ label: '金额', value: 1, unit: '元' }, { label: '比率', value: 2, unit: '%' }] })), true);
  assert.equal(catalog.candidateUnitsAligned(candidate('plan', 'production.plan', { kpis: [{ label: '实际产量', value: 10, unit: 'kg' }, { label: '计划达成率', value: 90, unit: '%' }] })), true);
  assert.equal(catalog.candidateUnitsAligned(candidate('wrong-percent', 'production.output', { kpis: [{ label: '产量', value: 10, unit: '%' }] })), false);
});

test('purchase price accepts only trusted nonadditive per-material table/chart facts', () => {
  const good = candidate('purchase-price', 'purchase.price', {
    source: 'purchase · authorized receipt lines', unit: '元/kg',
    metadata: { aggregation: 'nonadditive' },
    kpis: [],
    tableData: [{ label: 'A', value: 2.5 }, { label: 'B', value: 3 }],
    chart: { chartType: 'bar_horizontal', echartsOption: { yAxis: { data: ['B', 'A'] }, series: [{ data: [3, 2.5] }] } },
  });
  assert.deepEqual(catalog.purchasePriceEvidence(good).points.map((point) => point.value), [2.5, 3]);
  assert.equal(catalog.purchasePriceEvidence({ ...good, source: 'business' }), null);
  assert.equal(catalog.purchasePriceEvidence({ ...good, unit: 'kg' }), null);
  assert.equal(catalog.purchasePriceEvidence({ ...good, chart: { ...good.chart, echartsOption: { yAxis: { data: ['A', 'B'] }, series: [{ data: [2.5, 99] }] } } }), null);
  assert.equal(catalog.purchasePriceEvidence({ ...good, tableData: { headers: ['label', 'value'], rows: [{ label: 'A', value: null }, { label: 'B', value: 3 }] } }), null);
  assert.equal(catalog.purchasePriceEvidence({ ...good, tableData: { headers: ['label', 'value'], rows: [{ label: 'A', value: '' }, { label: 'B', value: 3 }] } }), null);
  assert.equal(catalog.purchasePriceEvidence({ ...good, tableData: { headers: [], rows: [] } }), null);
  assert.equal(catalog.purchasePriceEvidence({ ...good, tableData: [{ label: 'A', value: 2.5 }, { label: 'A', value: 2.5 }] }), null);
});

test('stateless discovery snapshot can load details lazily without admitting unavailable or full contexts', () => {
  const lazy = candidate('lazy', 'production.output', {
    detailSnapshotKey: 'opaque-snapshot',
    detailStatus: { state: 'unavailable', message: '点击查看时加载详情' },
  });
  assert.equal(catalog.candidateIsQualified(lazy), true);
  assert.equal(catalog.candidateIsQualified({ ...lazy, detailSnapshotKey: ' ' }), false);
  assert.equal(catalog.candidateIsQualified({ ...lazy, detailSnapshotKey: null }), false);
  assert.equal(catalog.candidateIsQualified({ ...lazy, detailStatus: { state: 'capacity' } }), false);
  assert.equal(catalog.candidateIsQualified({ ...lazy, chart: null }), false);
  assert.equal(catalog.selectCatalogCandidates({ recommendations: [lazy] }, null).selected.length, 1);
});

test('WIP voucher counts require exact unique original rows without relaxing physical units', () => {
  const good = candidate('wip', 'wip-plan-flow.plan-flow', {
    source: 'wip-plan-flow · 已过账工序流转',
    kpis: [{ label: '已过账领用量', value: 1, unit: 'kg' }, { label: '独立流转条数', value: 1, unit: '条' }],
    tableData: { headers: ['数量', '单位', 'MC凭证'], rows: [[1, 'kg', 'MC:3986']] },
  });
  assert.equal(catalog.candidateUnitsAligned(good), true);
  assert.equal(catalog.candidateUnitsAligned({ ...good, source: 'business' }), false);
  assert.equal(catalog.candidateUnitsAligned({ ...good, analysis: { familyId: 'production.output' } }), false);
  assert.equal(catalog.candidateUnitsAligned({ ...good, kpis: [{ label: '独立流转条数', value: 2, unit: '条' }] }), false);
  assert.equal(catalog.candidateUnitsAligned({ ...good, kpis: [{ label: '产量', value: 1, unit: '条' }] }), false);
  assert.equal(catalog.candidateUnitsAligned({ ...good, kpis: [{ label: '已过账领用量', value: 1, unit: '件' }] }), false);
  for (const rows of [[[1, '件', 'MC:3986']], [[1, 'kg', 'MC:3986'], [1, 'kg', 'MC:3986']], [[1, 'kg', 'unknown']], [[0, 'kg', 'MC:3986']]]) {
    assert.equal(catalog.candidateUnitsAligned({ ...good, tableData: { ...good.tableData, rows } }), false);
  }
});

test('export coverage is based on actual payload candidate IDs', () => {
  assert.deepEqual(catalog.summarizeExportIds({ charts: [{ id: 'one' }, { candidate_id: 'two' }], selected_ids: ['two', 'three'] }), ['one', 'two', 'three']);
  assert.deepEqual(catalog.summarizeExportIds({ charts: [] }), []);
});

test('matrix assessment does not green missing details or exports', () => {
  const assessment = catalog.assessCatalog({
    families: [{ familyId: 'production.output', checkFailures: ['detailRows'], status: 'UNVERIFIED' }],
    requestedUnknown: [], deferred: [], excluded: [], ui: {},
  });
  assert.equal(assessment.result, 'UNVERIFIED');
  assert.deepEqual(assessment.checkFailures, ['production.output.detailRows']);
});

test('optional qualification gaps remain explicit without failing qualified families', () => {
  const assessment = catalog.assessCatalog({
    families: [{ familyId: 'production.output', checkFailures: [], status: 'PASS' }],
    requestedUnknown: [], deferred: [], excluded: [],
    eligibilityGaps: [{ id: 'inventory.stock', missing: ['库存流水'] }],
    sourceErrors: [],
    ui: {},
  });
  assert.equal(assessment.result, 'PASS');
  assert.deepEqual(assessment.checkFailures, []);
});

test('source errors remain explicit and fail closed', () => {
  const assessment = catalog.assessCatalog({
    families: [{ familyId: 'production.output', checkFailures: [], status: 'PASS' }],
    requestedUnknown: [], deferred: [], excluded: [], eligibilityGaps: [],
    sourceErrors: [{ source: 'business', message: 'fixture source error' }], ui: {},
  });
  assert.equal(assessment.result, 'UNVERIFIED');
  assert.deepEqual(assessment.checkFailures, ['source.business']);
});
