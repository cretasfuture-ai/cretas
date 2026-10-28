'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { assessCapability, create, workers, CREATE_EXPECTED, CREATE_BUTTON } = require('../scenarios/scheduling-capabilities-readonly');
const ui = { automationDisabled: true, reasonVisible: true, manualExplanationVisible: true };
const payload = { success: true, data: { contractVersion: 'scheduling-capabilities/v1',
  aiGeneration: { status: 'UNAVAILABLE', mode: 'UNAVAILABLE', reason: 'Not available' } } };

test('capability acceptance requires the actual matching contract and mounted UI state', () => {
  assert.equal(assessCapability('aiGeneration', payload, ui).result, 'PASS');
  assert.deepEqual(assessCapability('aiGeneration', null, ui), { result: 'UNVERIFIED', rootCauseClass: 'tool' });
  for (const body of [{ success: false, data: payload.data }, { success: true, data: {} }]) {
    assert.equal(assessCapability('aiGeneration', body, ui).result, 'PARTIAL_DEFECT');
    assert.equal(assessCapability('aiGeneration', body, ui).rootCauseClass, 'backend');
  }
  for (const field of Object.keys(ui)) {
    assert.equal(assessCapability('aiGeneration', payload, { ...ui, [field]: false }).result, 'PARTIAL_DEFECT');
  }
  const available = structuredClone(payload);
  available.data.aiGeneration.status = 'AVAILABLE';
  assert.equal(assessCapability('aiGeneration', available, ui).result, 'PARTIAL_DEFECT');
});

test('APS v2: the create scenario expects AVAILABLE/DAILY_CAPACITY, an enabled button, and rejects the v1 mode', () => {
  assert.deepEqual({ ...CREATE_EXPECTED }, { status: 'AVAILABLE', mode: 'DAILY_CAPACITY' });
  assert.equal(CREATE_BUTTON, '按日产能自动排程');
  const live = structuredClone(payload);
  live.data.aiGeneration = { status: 'AVAILABLE', mode: 'DAILY_CAPACITY', reason: '按产品「一天能做多少」按天排' };
  const enabled = { ...ui, automationDisabled: false };
  assert.equal(assessCapability('aiGeneration', live, enabled, CREATE_EXPECTED).result, 'PASS');
  assert.equal(assessCapability('aiGeneration', live, ui, CREATE_EXPECTED).result, 'PARTIAL_DEFECT');
  assert.equal(assessCapability('aiGeneration', payload, enabled, CREATE_EXPECTED).rootCauseClass, 'backend');
  // 线上还是 v1 (按工艺图 + 工时) 的后端: 判后端缺陷, 不能算过
  const stale = structuredClone(live);
  stale.data.aiGeneration.mode = 'DAG_DURATION';
  const verdict = assessCapability('aiGeneration', stale, enabled, CREATE_EXPECTED);
  assert.equal(verdict.result, 'PARTIAL_DEFECT');
  assert.equal(verdict.rootCauseClass, 'backend');
});

test('new live scenarios are explicit opt-ins and do not broaden default campaigns', () => {
  assert.equal(create.defaultEnabled, false);
  assert.equal(workers.defaultEnabled, false);
});
