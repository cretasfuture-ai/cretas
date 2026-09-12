'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { assertDraft, id } = require('../scenarios/workspace-capacity-readonly');
const { describeHarness } = require('../core/run-suite');
test('capacity acceptance rejects lost or cross-wired draft data and is opt-in', () => {
  assertDraft('2030-01-01', '2030-01-01');
  assert.throws(() => assertDraft('', '2030-01-01'));
  assert.throws(() => assertDraft('2030-01-02', '2030-01-01'));
  assert.ok(describeHarness().scenarios.includes(id));
  assert.ok(!describeHarness().defaultScenarios.includes(id));
});
