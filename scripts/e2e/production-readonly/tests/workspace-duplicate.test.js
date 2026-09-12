'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const scenario = require('../scenarios/workspace-duplicate-readonly');
const { describeHarness } = require('../core/run-suite');
test('workspace duplicate acceptance rejects identity loss and compounded/repeated labels', () => {
  scenario.verifyLabels(['新建销售订单', '新建销售订单 3', '新建销售订单 2']);
  for (const labels of [
    ['新建销售订单', '新建销售订单 2'],
    ['新建销售订单', '新建销售订单 2', '新建销售订单 2 3'],
    ['新建销售订单', '新建销售订单 3', '新建销售订单 3'],
  ]) assert.throws(() => scenario.verifyLabels(labels));
  assert.ok(describeHarness().scenarios.includes(scenario.id));
  assert.ok(!describeHarness().defaultScenarios.includes(scenario.id));
});
