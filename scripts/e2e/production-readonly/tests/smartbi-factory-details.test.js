'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  assessDetailsResponse,
  assessPageContinuity,
  chartPointForCandidate,
} = require('../scenarios/smartbi-factory-readonly');

const expected = {
  factoryId: 'F006',
  candidateId: 'output:bar',
  source: '业务系统',
  period: '2026年全部期间',
  contextId: 'ctx-opaque-123',
};

function response({ rows = [{ 批次: 'B-1' }], total = rows.length, page = 1, hasMore = false, factoryId = 'F006', candidateId = expected.candidateId, source = expected.source, period = expected.period, pageSize = 50, availableFilters = [] } = {}) {
  return {
    status: 200,
    request: {
      context_id: expected.contextId,
      page,
      page_size: pageSize,
    },
    body: {
      success: true,
      data: {
        factoryId,
        candidateId,
        source,
        period,
        page,
        pageSize,
        rows,
        total,
        hasMore,
        columns: ['批次'],
        availableFilters,
      },
    },
  };
}

test('details evidence requires tenant, candidate, source, period, context, and page contract', () => {
  const result = assessDetailsResponse({ ...response(), expected });
  assert.equal(result.result, 'PASS');
  assert.equal(result.checks.factory, true);
  assert.equal(result.checks.context, true);
  assert.equal(result.checks.pageSize, true);
  assert.equal(result.rowCount, 1);

  const wrongTenant = assessDetailsResponse({ ...response({ factoryId: 'F007' }), expected });
  assert.equal(wrongTenant.result, 'CONFIRMED_DEFECT');
  assert.equal(wrongTenant.checks.factory, false);
});

test('point filtered evidence requires the real point payload and at least one returned row', () => {
  const point = { name: '2026-03', seriesName: '产量', dataIndex: 0 };
  const result = assessDetailsResponse({
    ...response({ rows: [{ 月份: '2026-03', 产量: 10 }], total: 1, availableFilters: [{ field: '月份', label: '月份', kind: 'date' }] }),
    request: { ...response().request, point },
    expected,
    point,
  });
  assert.equal(result.result, 'PASS');
  assert.equal(result.checks.pointRequest, true);
  assert.equal(result.checks.pointRows, true);

  const missingPoint = assessDetailsResponse({ ...response(), expected, point });
  assert.equal(missingPoint.result, 'CONFIRMED_DEFECT');
  assert.equal(missingPoint.checks.pointRequest, false);
});

test('a wide unfiltered response cannot pass point filtering without an allowed matching dimension', () => {
  const point = { name: '2026-03', seriesName: '产量', dataIndex: 0 };
  const wide = assessDetailsResponse({
    ...response({ rows: [{ 月份: '2026-03', 产量: 10 }, { 月份: '2026-04', 产量: 20 }], total: 2, availableFilters: [{ field: '月份', label: '月份', kind: 'date' }] }),
    request: { ...response().request, point },
    expected,
    point,
  });
  assert.notEqual(wide.result, 'PASS');
  assert.equal(wide.checks.pointRowsMatchDimension, false);
});

test('pagination preserves repeated business rows while proving the page boundary and counts', () => {
  const repeated = { 批次: 'same-business-row', 数量: 1 };
  const first = response({ rows: Array.from({ length: 50 }, () => ({ ...repeated })), total: 52, hasMore: true });
  const second = response({ rows: [{ ...repeated }, { ...repeated }], total: 52, page: 2, hasMore: false });
  const result = assessPageContinuity(first, second);
  assert.equal(result.result, 'PASS');
  assert.equal(result.loadedRowCount, 52);
  assert.equal(result.duplicateRowCount, 51);
  assert.equal(result.completeness, 'UNVERIFIED');
  assert.match(result.note, /not content-deduplicated/);
});

test('pagination failure cannot be reported as PASS when page two is skipped or short', () => {
  const first = response({ rows: Array.from({ length: 50 }, (_, index) => ({ id: index })), total: 51, hasMore: true });
  const skipped = response({ rows: [{ id: 50 }], total: 51, page: 3, hasMore: false });
  const result = assessPageContinuity(first, skipped);
  assert.notEqual(result.result, 'PASS');
  assert.equal(result.checks.secondPage, false);
});

test('pagination request and response identity mismatches cannot pass the navigation contract', () => {
  const first = response({ rows: Array.from({ length: 50 }, (_, index) => ({ id: index })), total: 51, hasMore: true });
  const second = response({ rows: [{ id: 50 }], total: 51, page: 2, hasMore: false, source: '错误来源' });
  second.request.context_id = 'different-context';
  const result = assessPageContinuity(first, second, expected);
  assert.notEqual(result.result, 'PASS');
  assert.equal(result.checks.secondRequestContext, false);
  assert.equal(result.checks.secondSource, false);
});

test('point target selection only qualifies mounted pie or bar option data', () => {
  const bar = chartPointForCandidate({
    chart: {
      chartType: 'bar',
      echartsOption: {
        xAxis: { data: ['2026-03', '2026-04'] },
        series: [{ type: 'bar', name: '产量', data: [10, 20] }],
      },
    },
  });
  assert.deepEqual(bar, { name: '2026-03', seriesName: '产量', dataIndex: 0, dimension: null, kind: 'bar' });

  assert.equal(chartPointForCandidate({ detailContext: { drilldown: false }, chart: { chartType: 'bar', echartsOption: {} } }), null);

  assert.equal(chartPointForCandidate({ chart: { chartType: 'line', echartsOption: { series: [{ type: 'line', data: [1] }] } } }), null);
});
