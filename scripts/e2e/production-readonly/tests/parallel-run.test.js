'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { validateParallelRuns, runParallelSuites, runPassed } = require('../core/parallel-run');

function parentBrowser({ closeFailures = [] } = {}) {
  const contexts = [];
  const browser = {
    async newContext() {
      const index = contexts.length;
      const context = {
        async newPage() { return { context: () => context }; },
        async close() { if (closeFailures.includes(index)) throw new Error(`close-${index}`); context.closed = true; },
        closed: false,
      };
      contexts.push(context);
      return context;
    },
    contexts,
  };
  return { page: { context: () => ({ browser: () => browser }) }, browser };
}

function options(overrides = {}) {
  return {
    runId: 'parallel-unit', evidenceDir: 'test-output/sc40/parallel-unit', expectedFactoryId: 'F006',
    parallelRuns: [
      { username: 'actor-a', expectedUsername: 'actor-a', expectedActorId: '1309', runId: 'lane-a' },
      { username: 'actor-b', expectedUsername: 'actor-b', expectedActorId: '1551', runId: 'lane-b' },
    ], ...overrides,
  };
}

function suiteResult(actorId, overrides = {}) {
  return {
    actorProof: { factoryId: 'F006', actorId, matchesExpectedUsername: true, matchesExpectedActorId: true },
    safetyPassed: true, scenarios: [{ result: 'PASS' }],
    network: { apiEvidence: [{ method: 'POST', url: 'https://fixture/api/mobile/F006/smart-bi/factory-analysis', status: 200, at: '2026-10-29T00:00:00.000Z', durationMs: 100 }], httpErrors: [], failedRequests: [], bodyReadErrors: [] },
    console: { consoleErrors: [], pageErrors: [] }, ...overrides,
  };
}

test('parallel suites pass with overlapping F006 analysis and close every context', async () => {
  const { page, browser } = parentBrowser();
  const result = await runParallelSuites(page, options(), async (_page, run) => suiteResult(run.expectedActorId));
  assert.equal(result.result, 'PASS');
  assert.equal(result.allBusinessScenariosPassed, true);
  assert.equal(result.distinctActors, true);
  assert.equal(result.concurrentRequestsObserved, true);
  assert.equal(browser.contexts.every(context => context.closed), true);
});

test('one throwing lane does not cancel the other lane and failure is not PASS', async () => {
  const { page, browser } = parentBrowser();
  const result = await runParallelSuites(page, options(), async (_page, run) => {
    if (run.expectedActorId === '1309') throw new Error('lane failure');
    return suiteResult(run.expectedActorId);
  });
  assert.equal(result.allSettled, true);
  assert.equal(result.result, 'UNVERIFIED');
  assert.equal(result.runs.some(run => run.result?.result === 'TOOL_ERROR'), true);
  assert.equal(browser.contexts.length, 2);
  assert.equal(browser.contexts[1].closed, true);
});

test('close failure, actor/F006 mismatch, scenario/error evidence, and no overlap cannot pass', async () => {
  const close = await runParallelSuites(parentBrowser({ closeFailures: [0] }).page, options(), async (_p, run) => suiteResult(run.expectedActorId));
  assert.equal(close.result, 'UNVERIFIED');
  const actor = await runParallelSuites(parentBrowser().page, options(), async (_p, run) => suiteResult(run.expectedActorId, { actorProof: { factoryId: 'F006', actorId: run.expectedActorId, matchesExpectedUsername: false, matchesExpectedActorId: false } }));
  assert.equal(actor.result, 'UNVERIFIED');
  const bad = await runParallelSuites(parentBrowser().page, options(), async (_p, run) => suiteResult(run.expectedActorId, { scenarios: [{ result: 'UNVERIFIED' }], network: { apiEvidence: [], httpErrors: [{ status: 500 }], failedRequests: [], bodyReadErrors: [] }, console: { consoleErrors: [{ text: 'unexpected' }], pageErrors: [{ text: 'unexpected' }] } }));
  assert.equal(bad.result, 'UNVERIFIED');
  const noOverlap = await runParallelSuites(parentBrowser().page, options(), async (_p, run) => suiteResult(run.expectedActorId, { network: { ...suiteResult(run.expectedActorId).network, apiEvidence: [{ method: 'POST', url: 'https://fixture/api/mobile/F006/smart-bi/factory-analysis', status: 200, at: run.expectedActorId === '1309' ? '2026-10-29T00:00:00.000Z' : '2026-10-29T00:01:00.000Z', durationMs: 10 }] } }));
  assert.equal(noOverlap.result, 'UNVERIFIED');
  assert.throws(() => validateParallelRuns(options().parallelRuns.map(run => ({ ...run, expectedFactoryId: 'F001' })), options()), /F006/);
});

test('parallel validation rejects unsafe cardinality, tenant, and identity collisions', () => {
  const parent = { runId: 'unit', evidenceDir: 'test-output/sc40/unit-parallel', expectedFactoryId: 'F006' };
  assert.throws(() => validateParallelRuns([{ username: 'a', expectedUsername: 'a', expectedActorId: '1' }], parent), /2 or 3/);
  assert.throws(() => validateParallelRuns([
    { username: 'a', expectedUsername: 'a', expectedActorId: '1', expectedFactoryId: 'F001' },
    { username: 'b', expectedUsername: 'b', expectedActorId: '2' },
  ], parent), /F006/);
  assert.throws(() => validateParallelRuns([
    { username: 'a', expectedUsername: 'a', expectedActorId: '1', runId: 'same' },
    { username: 'b', expectedUsername: 'b', expectedActorId: '2', runId: 'same' },
  ], parent), /unique/);
  assert.throws(() => validateParallelRuns([
    { username: 'a', expectedUsername: 'a', expectedActorId: '1' },
    { username: 'b', expectedUsername: 'b', expectedActorId: '1' },
  ], parent), /distinct positive/);
  assert.equal(runPassed({ safetyPassed: true, actorProof: { factoryId: 'F006', matchesExpectedUsername: true, matchesExpectedActorId: true }, scenarios: [{ result: 'PASS' }], network: { httpErrors: [], failedRequests: [], bodyReadErrors: [] }, console: { consoleErrors: [], pageErrors: [] } }), true);
  assert.equal(runPassed({ safetyPassed: true, actorProof: { factoryId: 'F006', matchesExpectedUsername: true, matchesExpectedActorId: true }, scenarios: [{ result: 'UNVERIFIED' }], network: { httpErrors: [], failedRequests: [], bodyReadErrors: [] }, console: { consoleErrors: [], pageErrors: [] } }), false);
});
