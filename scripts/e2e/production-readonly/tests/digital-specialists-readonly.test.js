'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const scenario = require('../scenarios/digital-specialists-readonly');
const { describeHarness, resolveScenarios } = require('../core/run-suite');

test('digital specialist production scenario is explicit opt-in and excludes logistics', () => {
  assert.equal(scenario.defaultEnabled, false);
  assert.equal(describeHarness().scenarios.includes(scenario.id), true);
  assert.equal(describeHarness().defaultScenarios.includes(scenario.id), false);
  assert.equal(resolveScenarios([scenario.id])[0], scenario);
  assert.equal(scenario.casesForFactory('WHOLESALE_0000_001').length, 3);
  assert.deepEqual(scenario.casesForFactory('WHOLESALE_0000_001').map(({ id }) => id), ['sales', 'purchase', 'inventory']);
  assert.equal(scenario.casesForFactory('F006').length, 8);
  assert.equal(scenario.casesForFactory('F006').some(({ id }) => id === 'logistics'), false);
});

test('digital specialist route contract carries the current headings and business links', () => {
  const cases = scenario.casesForFactory('F006');
  assert.deepEqual(cases.map(({ path }) => path), [
    '/workdesk/sales-owner',
    '/workdesk/purchaser',
    '/workdesk/warehouse-keeper',
    '/workdesk/finance-manager',
    '/workdesk/quality-manager',
    '/workdesk/production-manager',
    '/workdesk/quality-chief',
    '/dashboard/ai-value',
  ]);
  for (const item of cases) {
    assert.match(item.title, /数字专员工作台$/);
    assert.ok(item.links.length > 0);
  }
  assert.equal(cases.find(({ id }) => id === 'finance').nativeFact.test('实际期间：2026-11 至 2026-11\n营业收入 100\n净利润 20'), true);
});

test('sales acceptance cannot pass with only a user bubble or an error banner', () => {
  const baseline = {
    hasNewAssistant: true,
    errorVisible: false,
    assistantText: '真实查询结果',
    shortSingleLine: true,
    responseIsNotTerminalNotice: true,
  };
  assert.equal(scenario.salesQuestionPasses(baseline), true);
  assert.equal(scenario.salesQuestionPasses({ ...baseline, hasNewAssistant: false, assistantText: '', errorVisible: true }), false);
  assert.equal(scenario.salesQuestionPasses({ ...baseline, assistantText: '', responseIsNotTerminalNotice: false }), false);
});
