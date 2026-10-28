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
