'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const test = require('node:test');
const scenario = require('../scenarios/smartbi-scale-readonly');

const options = scenario.validateScaleOptions({});
const rawProductId = index => `QA-SC28-SKU-${String(index + 1).padStart(3, '0')}`;
const opaqueProductId = raw => `product-${crypto.createHash('sha256').update(`smartbi-subject-v2:id:${raw}`).digest('hex').slice(0, 16)}`;
const subjects = Array.from({ length: 100 }, (_, index) => ({
  id: opaqueProductId(rawProductId(index)),
  label: `测试产品 ${index + 1}`,
}));
const period = '全部历史';
const candidateSource = 'uploaded-production-inspection-fused · 显式上传生产/检验/成本';

function candidate(subjectId, includeTarget = subjectId === options.targetProductId, unit = 'kg') {
  const rawId = rawProductId(subjects.findIndex(item => item.id === subjectId));
  return {
    id: `production-inspection-fused-${subjectId}`, source: candidateSource, unit, analysis: { familyId: 'production.inspection_fused' },
    detailContext: { id: `detail-${subjectId}`, expiresAt: '2099-12-31T00:00:00Z', drilldown: false },
    kpis: includeTarget ? [{ value: 500, unit }, { value: '96%', unit: '%' }, { value: '95%', unit: '%' }, { value: '6,250', unit: '元' }] : [{ value: 1, unit }],
    tableData: { rows: Array.from({ length: 5 }, (_, index) => ({ '产品ID': rawId, date: `2026-0${index + 1}-01`, '单位': unit })) },
  };
}

function selection(subjectId, visibleChartCount = 1, unit = 'kg') {
  const data = {
    factoryId: 'F006', source: 'upload', uploadId: 5278,
    jointUploads: { inspectionUploadId: 5277, costUploadId: 5279 },
    period, analysisSubject: subjectId, recommendations: [candidate(subjectId, subjectId === options.targetProductId, unit)],
  };
  return {
    requestedSubjectId: subjectId, selectedSubjectId: subjectId, unit,
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
  const detailFor = (subjectId, unit) => { const rawId = rawProductId(subjects.findIndex(item => item.id === subjectId)); return scenario.assessProductDetail({ attempted: true, status: 200, body: { success: true, data: { factoryId: 'F006', candidateId: `production-inspection-fused-${subjectId}`, source: candidateSource, period, total: 5, rows: Array.from({ length: 5 }, (_, index) => ({ '产品ID': rawId, date: `2026-0${index + 1}-01`, '单位': unit })) } }, request: { context_id: `detail-${subjectId}`, page: 1, page_size: 50, filters: [] } }, options, `production-inspection-fused-${subjectId}`, `detail-${subjectId}`, period, candidate(subjectId, subjectId === options.targetProductId, unit), subjectId, { rawProductId: rawId, derivedSubjectId: subjectId, matches: opaqueProductId(rawId) === subjectId }); };
  const selections = [selection(subjects[0].id, 1, 'kg'), selection(subjects[50].id, 1, '盒'), selection(subjects[99].id, 1, '袋')];
  const details = selections.map((item) => ({ selectedSubjectId: item.selectedSubjectId, detail: detailFor(item.selectedSubjectId, item.selectedSubjectId === options.targetProductId ? 'kg' : item.selectedSubjectId === subjects[50].id ? '盒' : '袋') }));
  const detail = details[0].detail;
  return scenario.assessScale({ factoryId: 'F006', options, initial, selections, detail, details, rawPagination: { result: 'PASS' }, ...overrides });
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
    { status: 200, body: { success: true, data: { total: 500, page: 0, totalPages: 10, data: Array.from({ length: 50 }, (_, i) => ({ row: i })) } }, request: { page: 0, page_size: 50 }, mountedRows: 50, sourceSelected: true, uiMatchesRows: true },
    { status: 200, body: { success: true, data: { total: 500, page: 1, totalPages: 10, data: Array.from({ length: 50 }, (_, i) => ({ row: 50 + i })) } }, request: { page: 1, page_size: 50 }, mountedRows: 50, sourceSelected: true, uiMatchesRows: true },
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

test('detail source must equal the selected candidate source, not the generic upload source', () => {
  const subjectId = subjects[50].id;
  const rawId = rawProductId(50);
  const detail = scenario.assessProductDetail({
    attempted: true,
    status: 200,
    body: { success: true, data: { factoryId: 'F006', candidateId: `production-inspection-fused-${subjectId}`, source: 'upload', period, total: 5, rows: Array.from({ length: 5 }, () => ({ '产品ID': rawId, '单位': '盒' })) } },
    request: { context_id: `detail-${subjectId}`, page: 1, page_size: 50, filters: [] },
  }, options, `production-inspection-fused-${subjectId}`, `detail-${subjectId}`, period, candidate(subjectId, false, '盒'), subjectId, { rawProductId: rawId, derivedSubjectId: subjectId, matches: true });
  assert.equal(detail.checks.source, false);
  assert.equal(detail.result, 'UNVERIFIED');
});

test('detail product IDs must remain equal to the selected subject stable key', () => {
  const subjectId = subjects[50].id;
  const rawId = rawProductId(50);
  const detail = scenario.assessProductDetail({
    attempted: true,
    status: 200,
    body: { success: true, data: { factoryId: 'F006', candidateId: `production-inspection-fused-${subjectId}`, source: candidateSource, period, total: 5, rows: Array.from({ length: 5 }, () => ({ '产品ID': 'foreign-product', '单位': '盒' })) } },
    request: { context_id: `detail-${subjectId}`, page: 1, page_size: 50, filters: [] },
  }, options, `production-inspection-fused-${subjectId}`, `detail-${subjectId}`, period, candidate(subjectId, false, '盒'), subjectId, { rawProductId: rawId, derivedSubjectId: subjectId, matches: true });
  assert.equal(detail.checks.productKeys, false);
  assert.equal(detail.result, 'UNVERIFIED');
});

test('detail physical unit must match candidate KPI first unit and every raw row', () => {
  const subjectId = subjects[99].id;
  const rawId = rawProductId(99);
  const detail = scenario.assessProductDetail({
    attempted: true,
    status: 200,
    body: { success: true, data: { factoryId: 'F006', candidateId: `production-inspection-fused-${subjectId}`, source: candidateSource, period, total: 5, rows: Array.from({ length: 5 }, () => ({ '产品ID': rawId, '单位': '盒' })) } },
    request: { context_id: `detail-${subjectId}`, page: 1, page_size: 50, filters: [] },
  }, options, `production-inspection-fused-${subjectId}`, `detail-${subjectId}`, period, candidate(subjectId, false, '袋'), subjectId, { rawProductId: rawId, derivedSubjectId: subjectId, matches: true });
  assert.equal(detail.checks.units, false);
  assert.equal(detail.result, 'UNVERIFIED');
});

test('missing raw upload pagination evidence remains unverified', () => {
  const result = base({ rawPagination: { result: 'UNVERIFIED' } });
  assert.equal(result.checks.pagination, false);
  assert.equal(result.result, 'UNVERIFIED');
});
