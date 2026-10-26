'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('./visual-followup.test');
require('./scheduling-capabilities-readonly.test');
require('./bom-canvas-readonly-shared.test');

// Stable cross-platform entry: PowerShell does not expand test globs in the
// same way as POSIX shells. These modules register their cases with node:test.
require('./sanitizer.test');
require('./console-recorder.test');
require('./whitelist.test');
require('./mutation-guard.test');
require('./result-schema.test');
require('./mcp-entry.test');
require('./skill-drift.test');
require('./production-plan-routing.test');
require('./material-consumption-price-mask-readonly.test');
require('./role-menu-census-readonly.test');
require('./restaurant-live-command-readonly.test');
require('./restaurant-staffing-readonly.test');
require('./sales-order-lifecycle-readonly.test');
require('./url-utils.test');
require('./ui-login.test');
require('./visual-page-census-readonly.test');
require('./smartbi-factory-details.test');

const {
  clickAttempts,
  detailCellEvidence,
  echartsAssetUrl,
  estimateVirtualRowCount,
  mountedEChartsPointCoordinate,
  mountedTableEvidenceComplete,
  retryMountedTableEvidence,
  readVirtualTableMetrics,
  selectEChartsAssetUrls,
  selectPointCandidate,
  availablePointAttempts,
  POINT_CLICK_BUDGET_MS,
} = require('../scenarios/smartbi-factory-readonly');

test('virtual row geometry uses the inner content height instead of the viewport height', () => {
  assert.notEqual(estimateVirtualRowCount({ contentHeight: 620, rowHeight: 44 }), 51);
  assert.equal(estimateVirtualRowCount({ contentHeight: 51 * 44, rowHeight: 44 }), 51);
  assert.equal(estimateVirtualRowCount({ contentHeight: 49.4 * 44, rowHeight: 44 }), null);
  assert.equal(estimateVirtualRowCount({ contentHeight: 51 * 44 }), null);
  assert.equal(estimateVirtualRowCount({ contentHeight: Number.NaN, rowHeight: 44 }), null);
});

test('mounted point probing is bounded when the reserved click window is too short', () => {
  assert.equal(availablePointAttempts(1_000, 4_499), 0);
  assert.equal(availablePointAttempts(1_000, 4_500), 1);
  assert.ok(POINT_CLICK_BUDGET_MS >= 7 * 3_500);
});

test('bar point coordinates honor the ECharts grid and value axis', () => {
  const candidate = {
    chart: {
      chartType: 'bar_horizontal',
      echartsOption: {
        grid: { left: 100, right: 40, top: 20, bottom: 30 },
        xAxis: { type: 'value', min: 0, max: 100 },
        yAxis: { type: 'category', data: ['A', 'B'], inverse: false },
        series: [{ type: 'bar', name: '数量', data: [20, 80] }],
      },
    },
  };
  const point = { name: 'A', seriesName: '数量', dataIndex: 0, kind: 'bar_horizontal' };
  const attempts = clickAttempts({ x: 10, y: 20, width: 500, height: 400 }, candidate, point);
  assert.equal(attempts.length, 7);
  assert.ok(Math.abs(attempts[0].x - 146) < 0.001);
  assert.ok(Math.abs(attempts[0].y - 302.5) < 0.001);
  assert.ok(attempts.every((item) => item.x >= 11 && item.x <= 509 && item.y >= 21 && item.y <= 419));

  candidate.chart.echartsOption.yAxis.inverse = true;
  const inverseAttempts = clickAttempts({ x: 10, y: 20, width: 500, height: 400 }, candidate, point);
  assert.ok(Math.abs(inverseAttempts[0].y - 127.5) < 0.001);
});

test('same-origin ECharts asset selection is exact and bounded', () => {
  const origin = 'https://smartbi.example.test';
  assert.equal(echartsAssetUrl('/assets/echarts-ab12.js', origin), `${origin}/assets/echarts-ab12.js`);
  assert.equal(echartsAssetUrl('/assets/other-ab12.js', origin), null);
  assert.equal(echartsAssetUrl('https://elsewhere.test/assets/echarts-ab12.js', origin), null);
  const selected = selectEChartsAssetUrls([
    { name: `${origin}/assets/echarts-a.js` },
    { name: `${origin}/assets/echarts-b.js` },
    { name: `${origin}/assets/echarts-c.js` },
    { name: `${origin}/assets/echarts-d.js` },
    { name: `${origin}/assets/echarts-e.js` },
    { name: `${origin}/assets/not-echarts.js` },
  ], origin);
  assert.deepEqual(selected, ['https://smartbi.example.test/assets/echarts-a.js', 'https://smartbi.example.test/assets/echarts-b.js', 'https://smartbi.example.test/assets/echarts-c.js', 'https://smartbi.example.test/assets/echarts-d.js']);
});

test('virtual table metrics use ElementPlus inner content and visible concrete cells', () => {
  const cell = text => ({ textContent: text });
  const row = {
    getBoundingClientRect: () => ({ height: 44 }),
    querySelectorAll: () => [cell('B1'), cell('0'), cell('{"来源":"业务系统"}'), cell('—')],
  };
  const content = { firstElementChild: null, scrollHeight: 2156, getBoundingClientRect: () => ({ height: 2156 }) };
  const viewport = { firstElementChild: content, getBoundingClientRect: () => ({ height: 576 }) };
  const body = { firstElementChild: viewport, getBoundingClientRect: () => ({ height: 576 }) };
  const requestedSelectors = [];
  const table = { querySelector: selector => {
    requestedSelectors.push(selector);
    if (selector === '.el-table-v2__main > .el-vl__wrapper.el-table-v2__body') return body;
    if (selector === '.el-table-v2__row') return row;
    return null;
  } };
  const metrics = readVirtualTableMetrics(table);
  assert.deepEqual(metrics.visibleCellTexts, ['B1', '0', '{"来源":"业务系统"}', '—']);
  assert.equal(metrics.contentHeight, 2156);
  assert.equal(metrics.viewportHeight, 576);
  assert.equal(metrics.rowHeight, 44);
  assert.ok(requestedSelectors.includes('.el-table-v2__main > .el-vl__wrapper.el-table-v2__body'));
  assert.ok(!requestedSelectors.includes('.el-table-v2__main .el-vl__wrapper > .el-table-v2__body'));
  const evidence = detailCellEvidence({ columns: ['批次', '数量', '备注', '空值'], rows: [{ 批次: 'B1', 数量: 0, 备注: { 来源: '业务系统' }, 空值: null }] }, metrics.visibleCellTexts);
  assert.equal(evidence.displayedValues, true);
  assert.equal(evidence.concreteValueVisible, true);
  assert.equal(detailCellEvidence({ columns: ['批次'], rows: [{ 批次: 'B1' }] }, ['—']).displayedValues, false);
});

test('point selection carries the matching series index and prefers a small nonzero mounted chart', () => {
  const multiSeries = {
    chart: { chartType: 'bar', echartsOption: { xAxis: { data: ['A'] }, series: [{ type: 'line', name: '基线', data: [1] }, { type: 'bar', name: '收入', data: [30] }] } },
    detailContext: { id: 'ctx-1' },
  };
  assert.equal(require('../scenarios/smartbi-factory-readonly').chartPointForCandidate(multiSeries).seriesIndex, 1);
  const wide = { candidate: { id: 'wide', chart: { chartType: 'bar_horizontal', echartsOption: { yAxis: { data: Array.from({ length: 47 }, (_, i) => `W${i}`) }, series: [{ type: 'bar', data: [30] }] } } } };
  const small = { candidate: { id: 'small', chart: { chartType: 'bar_horizontal', echartsOption: { yAxis: { data: ['S'] }, series: [{ type: 'bar', data: [30] }] } } } };
  assert.equal(selectPointCandidate([wide, small]).candidate.id, 'small');
  const pie = { candidate: { id: 'pie', chart: { chartType: 'pie', echartsOption: { series: [{ type: 'pie', data: [{ name: '部门', value: 30 }] }] } } } };
  assert.equal(selectPointCandidate([pie, small]).candidate.id, 'small');
  assert.equal(selectPointCandidate([pie]).candidate.id, 'pie');
});

test('mounted ECharts lookup filters resources in Node and wires seriesIndex into the browser callback', async () => {
  const calls = [];
  let callbackTarget;
  const host = {
    count: async () => 1,
    evaluate: async (_callback, target) => {
      calls.push(target);
      if (!target) return {
        origin: 'https://smartbi.example.test',
        resources: [
          'https://smartbi.example.test/assets/echarts-a.js',
          'https://other.example.test/assets/echarts-b.js',
          'https://smartbi.example.test/assets/not-echarts.js',
          'https://smartbi.example.test/assets/echarts-c.js',
        ],
      };
      callbackTarget = target;
      return { x: 251.4, y: 138, source: 'mounted-echarts-convertToPixel' };
    },
  };
  const result = await mountedEChartsPointCoordinate(host, { kind: 'bar_horizontal', name: 'QA直营', seriesIndex: 1 }, 30);
  assert.deepEqual(result, { x: 251.4, y: 138, source: 'mounted-echarts-convertToPixel' });
  assert.equal(calls.length, 2);
  assert.deepEqual(callbackTarget.resources, ['https://smartbi.example.test/assets/echarts-a.js', 'https://smartbi.example.test/assets/echarts-c.js']);
  assert.equal(callbackTarget.seriesIndex, 1);
});

test('mounted table evidence retries a delayed virtual DOM before deciding', async () => {
  let samples = 0;
  const complete = { drawerVisible: true, tableVisible: true, rowCountMatches: true, displayedValues: true, concreteValueVisible: true };
  const delayed = await retryMountedTableEvidence(async () => {
    samples += 1;
    return samples === 1 ? { drawerVisible: true, tableVisible: true, rowCountMatches: false, displayedValues: false, concreteValueVisible: false } : complete;
  }, { timeoutMs: 100, sleep: async () => {} });
  assert.equal(delayed.sampleAttempts, 2);
  assert.equal(mountedTableEvidenceComplete(delayed), true);

  const neverMounted = await retryMountedTableEvidence(async () => ({ drawerVisible: true, tableVisible: true, rowCountMatches: true, displayedValues: false, concreteValueVisible: false }), { timeoutMs: 1, sleep: async () => {} });
  assert.equal(mountedTableEvidenceComplete(neverMounted), false);
});
