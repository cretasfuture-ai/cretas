'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { assessCapability, create, workers } = require('../scenarios/scheduling-capabilities-readonly');
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

test('APS v1: aiGeneration is AVAILABLE/DAG_DURATION and its button must be enabled', () => {
  const expected = { status: 'AVAILABLE', mode: 'DAG_DURATION' };
  const live = structuredClone(payload);
  live.data.aiGeneration = { status: 'AVAILABLE', mode: 'DAG_DURATION', reason: '按工艺图排' };
  const enabled = { ...ui, automationDisabled: false };
  assert.equal(assessCapability('aiGeneration', live, enabled, expected).result, 'PASS');
  assert.equal(assessCapability('aiGeneration', live, ui, expected).result, 'PARTIAL_DEFECT');
  assert.equal(assessCapability('aiGeneration', payload, enabled, expected).rootCauseClass, 'backend');
});

test('new live scenarios are explicit opt-ins and do not broaden default campaigns', () => {
  assert.equal(create.defaultEnabled, false);
  assert.equal(workers.defaultEnabled, false);
});
