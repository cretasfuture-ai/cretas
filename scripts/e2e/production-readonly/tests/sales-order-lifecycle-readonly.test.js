'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { ROUTES } = require('../config/routes');
const salesOrderLifecycleScenario = require('../scenarios/sales-order-lifecycle-readonly');
const { SCENARIOS, resolveScenarios } = require('../core/run-suite');

test('registers the sales order lifecycle page as a production read-only scenario', () => {
  assert.equal(ROUTES.salesOrders, '/sales/orders');
  assert.equal(salesOrderLifecycleScenario.id, 'sales-order-lifecycle-readonly');
  assert.equal(salesOrderLifecycleScenario.path, ROUTES.salesOrders);
  assert.ok(SCENARIOS.includes(salesOrderLifecycleScenario));
  assert.equal(resolveScenarios([salesOrderLifecycleScenario.id])[0], salesOrderLifecycleScenario);
});
