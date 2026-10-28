'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const lifecycle = require('../scenarios/smartbi-factory-analysis-lifecycle');

const baselineData = {
  factoryId: 'F006', source: 'business', period: { periodType: 'all', year: 2026, startMonth: 1, endMonth: 12, label: '全部历史' },
  analysisSubject: 'P1', facts: [{ id: 'f1', label: '产量', value: 12, unit: 'kg', source: '业务系统' }],
  recommendations: [{ id: 'production-output', source: 'business', unit: 'kg', detailSnapshotKey: 'snap-1', chart: { chartType: 'bar' }, kpis: [{ label: '产量', value: 12 }] }],
};

const baseline = lifecycle.lifecycleBaseline(baselineData, { source: 'business', periodType: 'all', year: 2026, startMonth: 1, endMonth: 12, analysisSubject: 'P1' });

test('lifecycle follow-up contract is context-only, bounded, and preserves source scope', () => {
  const body = { success: true, data: { ...baselineData, status: 'answered', contextId: 'ctx-2' } };
  const result = lifecycle.validateContextOnlyTurn({
    baseline,
    body,
    request: { question: '再看质量', contextId: 'ctx-1' },
    round: 12,
    expectedContextId: 'ctx-1',
    requireFactsStable: true,
  });
  assert.equal(result.pass, true);
  assert.equal(result.checks.contextOnlyRequest, true);
  assert.equal(result.checks.boundedRecommendations, true);
  assert.equal(result.checks.source, true);
  assert.equal(result.checks.period, true);
  assert.equal(result.checks.requestContextMatches, true);
  assert.equal(result.checks.selectedDetailsStable, true);
});

test('unavailable or scope drift is never accepted as lifecycle PASS', () => {
  const unavailable = lifecycle.validateContextOnlyTurn({
    baseline,
    body: { success: true, data: { ...baselineData, status: 'unavailable', recommendations: [] } },
    request: { question: '再看质量', contextId: 'ctx-1' },
    round: 1,
  });
  assert.equal(unavailable.pass, false);
  assert.equal(unavailable.unavailable, true);
  const drift = lifecycle.validateContextOnlyTurn({
    baseline,
    body: { success: true, data: { ...baselineData, status: 'answered', source: 'upload' } },
    request: { question: '再看质量', contextId: 'ctx-1' },
    round: 2,
    expectedContextId: 'ctx-2',
  });
  assert.equal(drift.pass, false);
  assert.equal(drift.checks.source, false);
  assert.equal(drift.checks.requestContextMatches, false);
  const selectedDrift = lifecycle.validateContextOnlyTurn({
    baseline,
    body: { success: true, data: { ...baselineData, status: 'answered', recommendations: [{ ...baselineData.recommendations[0], detailSnapshotKey: 'snap-new' }] } },
    request: { question: '再看质量', contextId: 'ctx-1' },
    round: 3,
  });
  assert.equal(selectedDrift.pass, false);
  assert.equal(selectedDrift.checks.selectedDetailsStable, false);
});

test('refresh requires immutable snapshot identity and rejects changed rows', () => {
  const original = baselineData.recommendations[0];
  const good = lifecycle.validateRefresh({
    original,
    refreshed: { success: true, data: baselineData },
    detail: { success: true, data: { candidateId: original.id, rows: [{ id: 1 }] } },
    baseline,
    originalDetail: { data: { rows: [{ id: 1 }] } },
  });
  assert.equal(good.pass, true);
  const changed = lifecycle.validateRefresh({
    original,
    refreshed: { success: true, data: { ...baselineData, recommendations: [{ ...original, detailSnapshotKey: 'snap-new' }] } },
    detail: { success: true, data: { candidateId: original.id, rows: [{ id: 2 }] } },
    baseline,
  });
  assert.equal(changed.pass, false);
  assert.equal(changed.dataChanged, true);
  const changedRows = lifecycle.validateRefresh({
    original,
    refreshed: { success: true, data: baselineData },
    detail: { success: true, data: { candidateId: original.id, rows: [{ id: 2 }] } },
    originalDetail: { data: { rows: [{ id: 1 }] } },
    baseline,
  });
  assert.equal(changedRows.pass, false);
});

test('expiry wait uses at most 30-second steps and 360-second total bound', async () => {
  let clock = 0;
  const waits = [];
  const result = await lifecycle.waitForExpiry({}, '1970-01-01T00:00:00.120Z', {
    now: () => clock,
    sleep: async (ms) => { waits.push(ms); clock += ms; },
    startedAt: 0,
  });
  assert.equal(result.state, 'expired');
  assert.ok(waits.every((ms) => ms <= lifecycle.MAX_WAIT_STEP_MS));
  assert.ok(result.elapsedMs <= lifecycle.MAX_WAIT_TOTAL_MS);
  const missing = await lifecycle.waitForExpiry({}, null, { now: () => 0, startedAt: 0, sleep: async () => {} });
  assert.equal(missing.state, 'unavailable');
});

