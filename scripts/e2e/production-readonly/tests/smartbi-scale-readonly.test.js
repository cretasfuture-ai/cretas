'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const scenario = require('../scenarios/smartbi-scale-readonly');

const options = scenario.validateScaleOptions({});
const subjects = Array.from({ length: 100 }, (_, index) => ({
  id: index === 0 ? options.targetProductId : `product-13dbfb8a219e694e-${String(index).padStart(3, '0')}`,
  label: `测试产品 ${index + 1}`,
}));
const period = '全部历史';

function candidate(subjectId, includeTarget = subjectId === options.targetProductId) {
  return {
    id: `production-inspection-fused-${subjectId}`, unit: 'kg', analysis: { familyId: 'production.inspection_fused' },
    detailContext: { id: `detail-${subjectId}`, expiresAt: '2099-12-31T00:00:00Z', drilldown: false },
    kpis: includeTarget ? [{ value: 500, unit: 'kg' }, { value: '96%', unit: '%' }, { value: '95%', unit: '%' }, { value: '6,250', unit: '元' }] : [{ value: 1 }],
    tableData: { rows: Array.from({ length: 5 }, (_, index) => ({ date: `2026-0${index + 1}-01` })) },
  };
}

function selection(subjectId, visibleChartCount = 1) {
  const data = {
    factoryId: 'F006', source: 'upload', uploadId: 5278,
    jointUploads: { inspectionUploadId: 5277, costUploadId: 5279 },
    period, analysisSubject: subjectId, recommendations: [candidate(subjectId)],
  };
  return {
    requestedSubjectId: subjectId, selectedSubjectId: subjectId,
    request: { source: 'upload', uploadId: 5278, inspectionUploadId: 5277, costUploadId: 5279, analysisSubject: subjectId },
    status: 200, body: { success: true, data: { ...data, status: 'answered' } }, visibleChartCount, renderedCanvasCount: visibleChartCount,
  };
}

function base(overrides = {}) {
  const initial = {
    status: 200,
    request: { source: 'upload', uploadId: 5278, inspectionUploadId: 5277, costUploadId: 5279 },
    body: { success: true, data: { factoryId: 'F006', source: 'upload', uploadId: 5278, jointUploads: { inspectionUploadId: 5277, costUploadId: 5279 }, subjects, period } },
  };
  const detail = scenario.assessProductDetail({ attempted: true, status: 200, body: { success: true, data: { factoryId: 'F006', candidateId: `production-inspection-fused-${options.targetProductId}`, source: 'upload', period, total: 5, rows: Array.from({ length: 5 }, () => ({})) } }, request: { context_id: `detail-${options.targetProductId}`, page: 1, page_size: 50, filters: [] } }, options, `production-inspection-fused-${options.targetProductId}`, `detail-${options.targetProductId}`, period, candidate(options.targetProductId));
  return scenario.assessScale({ factoryId: 'F006', options, initial, selections: [selection(subjects[0].id), selection(subjects[50].id), selection(subjects[99].id)], detail, rawPagination: { result: 'PASS' }, ...overrides });
}

test('canonical scale assessment passes only with bounded real-shaped evidence', () => {
  const result = base();
  assert.equal(result.result, 'PASS');
  assert.equal(result.checks.subjectCount, true);
  assert.equal(result.checks.noHundredCharts, true);
  assert.equal(result.checks.pagination, true);
});

test('100-subject omission is unverified, never a scale PASS', () => {
  const result = base({ initial: { status: 200, body: { success: true, data: { factoryId: 'F006', source: 'upload', uploadId: 5278, jointUploads: { inspectionUploadId: 5277, costUploadId: 5279 }, subjects: subjects.slice(0, 99), period } }, request: { source: 'upload', uploadId: 5278, inspectionUploadId: 5277, costUploadId: 5279 } } });
  assert.equal(result.checks.subjectCount, false);
  assert.equal(result.result, 'UNVERIFIED');
  assert.notEqual(result.result, 'PASS');
});

test('wrong selected ID is a scoped defect even when KPI shape looks valid', () => {
  const bad = selection(subjects[0].id);
  bad.selectedSubjectId = 'foreign-subject';
  bad.request.analysisSubject = 'foreign-subject';
  bad.body.data.analysisSubject = 'foreign-subject';
  const result = base({ selections: [bad, selection(subjects[50].id), selection(subjects[99].id)] });
  assert.equal(result.checks.answersMatchSelectedIds, false);
  assert.equal(result.result, 'PARTIAL_DEFECT');
});

test('rendering all 100 products is rejected rather than hidden by a chart count claim', () => {
  const result = base({ selections: [selection(subjects[0].id, 100), selection(subjects[50].id, 1), selection(subjects[99].id, 1)] });
  assert.equal(result.checks.noHundredCharts, false);
  assert.equal(result.result, 'PARTIAL_DEFECT');
});

test('cross-factory or upload-scope drift cannot pass', () => {
  const bad = selection(subjects[0].id);
  bad.body.data.factoryId = 'F007';
  const result = base({ selections: [bad, selection(subjects[50].id), selection(subjects[99].id)] });
  assert.equal(result.checks.answersMatchSelectedIds, false);
  assert.equal(result.result, 'PARTIAL_DEFECT');
});

test('five product rows do not claim pagination; observed 500-row upload pages do', () => {
  const product = scenario.assessProductDetail({ attempted: true, status: 200, body: { success: true, data: { factoryId: 'F006', candidateId: 'c', source: 'upload', period, total: 5, rows: Array.from({ length: 5 }, () => ({})) } }, request: { page: 1, page_size: 50 } }, options, 'c', period);
  assert.equal(product.pagination, 'UNVERIFIED');
  const raw = scenario.assessRawPagination(
    { status: 200, body: { success: true, data: { total: 500, page: 0, totalPages: 10, data: Array.from({ length: 50 }, (_, i) => ({ row: i })) } }, request: { page: 0, page_size: 50 }, mountedRows: 50, sourceSelected: true },
    { status: 200, body: { success: true, data: { total: 500, page: 1, totalPages: 10, data: Array.from({ length: 50 }, (_, i) => ({ row: 50 + i })) } }, request: { page: 1, page_size: 50 }, mountedRows: 50, sourceSelected: true },
    options,
  );
  assert.equal(raw.result, 'PASS');
  assert.equal(raw.total, 500);
});

test('target KPI is checked on the selected target, not inherited from another product', () => {
  const target = selection(options.targetProductId);
  target.body.data.recommendations[0].kpis[3].value = '6,249';
  const result = base({ selections: [target, selection(subjects[50].id), selection(subjects[99].id)] });
  assert.equal(result.checks.targetSelected, true);
  assert.equal(result.checks.targetKpis, false);
  assert.equal(result.result, 'UNVERIFIED');
});

test('wrong candidate unit is a negative answer even when KPI and rows are present', () => {
  const target = selection(options.targetProductId);
  target.body.data.recommendations[0].unit = 'g';
  const result = base({ selections: [target, selection(subjects[50].id), selection(subjects[99].id)] });
  assert.equal(result.checks.unitEach, false);
  assert.equal(result.result, 'UNVERIFIED');
});

test('non-200 detail response cannot satisfy the explicit success/detail contract', () => {
  const detail = scenario.assessProductDetail({
    attempted: true,
    status: 403,
    body: { success: false, data: {} },
    request: { context_id: `detail-${options.targetProductId}`, page: 1, page_size: 50, filters: [] },
  }, options, `production-inspection-fused-${options.targetProductId}`, `detail-${options.targetProductId}`, period, candidate(options.targetProductId));
  assert.equal(detail.checks.http200, false);
  assert.equal(detail.checks.success, false);
  assert.equal(detail.result, 'UNVERIFIED');
});

test('missing raw upload pagination evidence remains unverified', () => {
  const result = base({ rawPagination: { result: 'UNVERIFIED' } });
  assert.equal(result.checks.pagination, false);
  assert.equal(result.result, 'UNVERIFIED');
});
