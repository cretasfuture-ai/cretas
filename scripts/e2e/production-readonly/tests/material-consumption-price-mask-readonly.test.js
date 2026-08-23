'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { ROUTES } = require('../config/routes');
const { SCENARIOS, resolveScenarios } = require('../core/run-suite');
const scenario = require('../scenarios/material-consumption-price-mask-readonly');

test('registers the F006 quality-inspector material-consumption price-mask scenario', () => {
  assert.equal(ROUTES.bomAchievement, '/production/bom-achievement');
  assert.equal(scenario.targetBatchNumber, 'PB-PLAN-1787036063746-8719B078-74084');
  assert.equal(scenario.expectedItemCount, 4);
  assert.equal(scenario.expectedRole, 'quality_inspector');
  assert.deepEqual(scenario.rootPriceKeys, [
    'totalPlannedCost',
    'totalActualCost',
    'totalCost',
    'subtotal',
  ]);
  assert.deepEqual(scenario.itemPriceKeys, [
    'unitPrice',
    'cost',
    'totalCost',
    'subtotal',
    'lineAmount',
  ]);
  assert.equal(scenario.priceKeyPattern.test('unitPrice'), true);
  assert.equal(scenario.priceKeyPattern.test('totalActualCost'), true);
  assert.equal(scenario.priceKeyPattern.test('actualQty'), false);
  assert.equal(scenario.path, ROUTES.bomAchievement);
  assert.equal(SCENARIOS.includes(scenario), true);
  assert.equal(resolveScenarios([scenario.id])[0], scenario);
});
