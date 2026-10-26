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
  estimateVirtualRowCount,
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
