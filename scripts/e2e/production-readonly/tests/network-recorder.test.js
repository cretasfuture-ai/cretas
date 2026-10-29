'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { installNetworkRecorder } = require('../core/network-recorder');
const { runReadOnlyPageScenario } = require('../scenarios/_shared');

function request(path) {
  return {
    url: () => `https://admin.cretaceousfuture.com${path}`,
    method: () => 'POST',
    postData: () => JSON.stringify({ detail_candidate_ids: ['joint-1'] }),
    postDataJSON: () => ({ detail_candidate_ids: ['joint-1'] }),
    failure: () => ({ errorText: 'net::ERR_NETWORK_CHANGED' }),
  };
}

test('Python SmartBI validation errors and network failures cannot disappear from acceptance', async () => {
  const context = new EventEmitter();
  const recorder = installNetworkRecorder(context);
  const validation = request('/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations');
  context.emit('request', validation);
  context.emit('response', {
    request: () => validation,
    headers: () => ({ 'content-type': 'application/json' }),
    json: async () => ({ success: false, message: '详情图表不在当前授权目录中' }),
    status: () => 422,
  });
  context.emit('requestfailed', request('/smartbi-api/api/smartbi/financial-dashboard/factory-details'));
  const asset = request('/assets/chart.png');
  context.emit('request', asset);
  context.emit('response', { request: () => asset, status: () => 404 });
  await recorder.flush();
  const snapshot = recorder.snapshot();
  assert.equal(snapshot.apiEvidence.length, 1);
  assert.equal(snapshot.httpErrors.length, 1);
  assert.equal(snapshot.httpErrors[0].status, 422);
  assert.equal(snapshot.httpErrors[0].response.success, false);
  assert.equal(snapshot.failedRequests.length, 1);
  assert.match(snapshot.failedRequests[0].url, /factory-details$/);
  recorder.dispose();
  assert.equal(context.listenerCount('response'), 0);
});

test('an unfinished JSON body produces bounded, explicit evidence failure', async () => {
  const context = new EventEmitter();
  const recorder = installNetworkRecorder(context, { responseBodyTimeoutMs: 20 });
  const query = request('/smartbi-api/api/smartbi/financial-dashboard/factory-details');
  context.emit('request', query);
  context.emit('response', {
    request: () => query, headers: () => ({ 'content-type': 'application/json' }),
    json: () => new Promise(() => {}), status: () => 200,
  });
  await recorder.flush();
  const snapshot = recorder.snapshot();
  assert.equal(snapshot.apiEvidence.length, 1);
  assert.equal(snapshot.bodyReadErrors.length, 1);
  assert.match(snapshot.bodyReadErrors[0].error, /did not finish within 20ms/);
  assert.equal(snapshot.apiEvidence[0].response.success, null);
  assert.match(snapshot.apiEvidence[0].bodyReadError, /did not finish/);
  recorder.dispose();
});

test('completed JSON and downloads flush without a body read error', async () => {
  const context = new EventEmitter();
  const recorder = installNetworkRecorder(context, { responseBodyTimeoutMs: 100 });
  for (const [contentType, body] of [['application/json', { success: true }], ['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', null]]) {
    const query = request('/smartbi-api/api/smartbi/financial-dashboard/export-excel');
    context.emit('request', query);
    context.emit('response', {
      request: () => query, headers: () => ({ 'content-type': contentType }),
      json: async () => { assert.ok(body); return body; }, status: () => 200,
    });
  }
  await recorder.flush();
  assert.equal(recorder.snapshot().apiEvidence.length, 2);
  assert.deepEqual(recorder.snapshot().bodyReadErrors, []);
  recorder.dispose();
});

test('a visibly successful scenario fails when its response evidence is incomplete', async () => {
  let inspected = false;
  const emptyGuard = { actualBusinessWrites: 0, blockedMutationAttempts: [] };
  const ctx = {
    baseUrl: 'https://admin.cretaceousfuture.com', scenarioRef: {},
    page: {
      goto: async () => {}, waitForLoadState: async () => {},
      url: () => 'https://admin.cretaceousfuture.com/smart-bi/analysis',
      locator: () => ({ waitFor: async () => {}, innerText: async () => 'Expected rendered results with enough visible page content for the real page gate' }),
    },
    mutationGuard: { snapshot: () => emptyGuard },
    consoleRecorder: { snapshot: () => ({ consoleErrors: [], pageErrors: [], consoleWarnings: [] }) },
    network: {
      flush: async () => {},
      snapshot: () => ({ apiEvidence: [], httpErrors: [], bodyReadErrors: inspected ? [{ error: 'unfinished body' }] : [] }),
    },
  };
  const result = await runReadOnlyPageScenario(ctx, {
    id: 'body-read-contract', path: '/smart-bi/analysis', landmarks: ['Expected'],
    inspect: async () => { inspected = true; return { assessment: { result: 'PASS', rootCauseClass: 'none' } }; },
  });
  assert.equal(result.result, 'TOOL_ERROR');
  assert.equal(result.rootCauseClass, 'tool');
  assert.equal(result.actualBusinessWrites, 0);
  assert.ok(result.pageEvidence.some(item => item.bodyReadErrors?.length === 1));
});

test('login settlement observes delayed SPA requests through body completion', async () => {
  const context = new EventEmitter();
  const recorder = installNetworkRecorder(context);
  let bodyCompleted = false;
  const query = request('/api/mobile/F006/config/disabled-modules');
  setTimeout(() => {
    context.emit('request', query);
    context.emit('response', {
      request: () => query, headers: () => ({ 'content-type': 'application/json' }), status: () => 200,
      json: async () => {
        await new Promise(resolve => setTimeout(resolve, 20));
        bodyCompleted = true;
        context.emit('requestfinished', query);
        return { success: true };
      },
    });
  }, 10);
  await recorder.settle({ quietMs: 20, timeoutMs: 1_000 });
  assert.equal(bodyCompleted, true);
  assert.equal(recorder.snapshot().apiEvidence.length, 1);
  assert.deepEqual(recorder.snapshot().bodyReadErrors, []);
  recorder.dispose();
  assert.equal(context.listenerCount('requestfinished'), 0);
});

test('login settlement rejects an unfinished request instead of silently navigating away', async () => {
  const context = new EventEmitter();
  const recorder = installNetworkRecorder(context);
  context.emit('request', request('/api/mobile/F006/config/disabled-modules'));
  await assert.rejects(recorder.settle({ quietMs: 5, timeoutMs: 20 }), /did not settle.*1 requests/);
  recorder.dispose();
});

test('the network recorder retains login status without duplicating the secret-bearing body', async () => {
  const context = new EventEmitter();
  const recorder = installNetworkRecorder(context);
  const login = request('/api/mobile/auth/unified-login');
  context.emit('request', login);
  context.emit('response', {
    request: () => login, headers: () => ({ 'content-type': 'application/json' }), status: () => 401,
    json: () => { throw new Error('Login body belongs to the independent identity verifier'); },
  });
  context.emit('requestfinished', login);
  await recorder.flush();
  assert.equal(recorder.snapshot().apiEvidence[0].status, 401);
  assert.equal(recorder.snapshot().httpErrors[0].status, 401);
  assert.deepEqual(recorder.snapshot().bodyReadErrors, []);
  recorder.dispose();
});
