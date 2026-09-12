'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { assertGrid } = require('../scenarios/table-grid-readonly');
const { summarizeMeasurements } = require('../scenarios/table-grid-readonly');
const { sanitizeValue } = require('../core/sanitizer');
test('grid measurement rejects missing data, drift, missing cell lines and missing outer frame', () => {
  const data = { rows: 1, outline: '1px', columns: [{ header: 120, body: 120, right: '1px', bottom: '1px' }] };
  assert.doesNotThrow(() => assertGrid(data));
  assert.throws(() => assertGrid({ ...data, rows: 0 }));
  assert.throws(() => assertGrid({ ...data, columns: [] }));
  assert.throws(() => assertGrid({ ...data, outline: '0px' }));
  for (const patch of [{ body: 80 }, { right: '0px' }, { bottom: '0px' }]) {
    assert.throws(() => assertGrid({ ...data, columns: [{ ...data.columns[0], ...patch }] }));
  }
});
test('numeric grid receipts survive the existing evidence depth limit', () => {
  const summary = summarizeMeasurements([{ width: 1024, rows: 10, rowHeight: 65, outline: '1px',
    columns: [{ header: 120, body: 120, right: '1px', bottom: '1px' }] }]);
  const report = sanitizeValue({ scenarios: [{ pageEvidence: [summary] }] });
  assert.deepEqual(report.scenarios[0].pageEvidence[0], summary);
  assert.deepEqual(summary.firstRowHeights, [65]);
  assert.equal(summary.maximumHeaderBodyDifference, 0);
});
