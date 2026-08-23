'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { SCENARIOS, resolveScenarios } = require('../core/run-suite');
const scenario = require('../scenarios/role-menu-census-readonly');

test('registers the generic F006 role menu census scenario', () => {
  assert.equal(scenario.path, '/dashboard');
  assert.equal(scenario.errorMarkers.includes('403'), true);
  assert.equal(scenario.errorMarkers.includes('无权访问'), true);
  assert.equal(SCENARIOS.includes(scenario), true);
  assert.equal(resolveScenarios([scenario.id])[0], scenario);
});
