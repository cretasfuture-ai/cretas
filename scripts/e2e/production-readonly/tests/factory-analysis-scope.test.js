'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const { FACTORY_ANALYSIS_FACTORY_IDS, assertFactoryAnalysisFactory } = require('../config/factory-analysis-scope');
const { classifyMutation } = require('../core/mutation-guard');
const { installConsoleRecorder } = require('../core/console-recorder');
const { installNetworkRecorder } = require('../core/network-recorder');
const { runParallelSuites } = require('../core/parallel-run');
const scenario = require('../scenarios/smartbi-factory-analysis-readonly');

const analysisUrl = factory => `https://fixture.test/api/mobile/${factory}/smart-bi/factory-analysis`;
const request = factory => ({
  method: () => 'POST',
  url: () => analysisUrl(factory),
  failure: () => ({ errorText: 'net::ERR_INTERNET_DISCONNECTED' }),
});

test('factory analysis allows F006 and LIUSHANMEN and refuses demo, unknown and missing tenants', async () => {
  assert.deepEqual(FACTORY_ANALYSIS_FACTORY_IDS, ['F006', 'LIUSHANMEN']);
  for (const factoryId of ['F006', 'LIUSHANMEN']) {
    assert.doesNotThrow(() => scenario.validateFactory(factoryId));
    const response = { request: () => request(factoryId), url: () => analysisUrl(factoryId) };
    assert.equal(scenario.isAnalysisResponse(response, factoryId), true);
    assert.equal(scenario.isAnalysisResponse(response, 'LSMDEMO'), false);
    assert.equal(scenario.summaryFromResponse(200, { data: { factoryId } }, {}, factoryId).factoryScope, true);
    assert.equal(scenario.summaryFromResponse(200, { data: { factoryId: 'LSMDEMO' } }, {}, factoryId).factoryScope, false);
    assert.equal(classifyMutation('POST', analysisUrl(factoryId), undefined, { contextId: 'ctx-fixture', question: '再看质量' }).kind, 'readonly-post');
  }
  for (const factoryId of ['LSMDEMO', 'UNKNOWN', undefined]) {
    assert.throws(() => assertFactoryAnalysisFactory(factoryId), /not allowed/);
    await assert.rejects(scenario.run({ expectedFactoryId: factoryId }), /not allowed/);
    assert.equal(scenario.isAnalysisResponse({ request: () => request(factoryId), url: () => analysisUrl(factoryId) }), false);
    assert.equal(classifyMutation('POST', analysisUrl(factoryId), undefined, { contextId: 'ctx-fixture', question: '再看质量' }).kind, 'business-mutation');
  }
  await assert.rejects(scenario.run({ expectedFactoryId: 'LIUSHANMEN', loginEvidence: { factoryId: 'F006' } }), /matching authenticated factory/);
});

test('console and network recorders accept the verified LIUSHANMEN offline request and retain demo failures', () => {
  const context = new EventEmitter();
  const page = new EventEmitter();
  context.pages = () => [page];
  context.addInitScript = () => {};
  const network = installNetworkRecorder(context);
  const consoleRecorder = installConsoleRecorder(context);
  const emit = factory => {
    const req = request(factory);
    context.emit('requestfailed', req);
    page.emit('console', { type: () => 'error', text: () => 'Failed to load resource: net::ERR_INTERNET_DISCONNECTED', location: () => ({ url: req.url() }) });
    return req;
  };
  try {
    const lsm = emit('LIUSHANMEN');
    const demo = emit('LSMDEMO');
    assert.throws(() => network.expectClientOfflineFailure(demo), /verified/);
    assert.throws(() => consoleRecorder.expectClientOfflineResourceError(demo), /Unverified/);
    network.expectClientOfflineFailure(lsm);
    consoleRecorder.expectClientOfflineResourceError(lsm);
    assert.deepEqual(network.snapshot().expectedTransportFailures.map(item => item.url), [lsm.url()]);
    assert.deepEqual(network.snapshot().failedRequests.map(item => item.url), [demo.url()]);
    assert.deepEqual(consoleRecorder.snapshot().expectedConsoleErrors.map(item => item.location), [lsm.url()]);
    assert.deepEqual(consoleRecorder.snapshot().consoleErrors.map(item => item.location), [demo.url()]);
  } finally {
    network.dispose();
    consoleRecorder.dispose();
  }
});

test('parallel recorder counts allowed analysis intervals only for the reported tenant', async () => {
  const browser = { newContext: async () => ({ newPage: async () => ({}), close: async () => {} }) };
  const page = { context: () => ({ browser: () => browser }) };
  const options = { parallelRuns: [1, 2].map(id => ({ username: `fixture-${id}`, expectedActorId: id })) };
  const record = (actorFactory, requestFactory) => runParallelSuites(page, options, async (_page, lane) => ({
    actorProof: { actorId: lane.expectedActorId, factoryId: actorFactory },
    network: { apiEvidence: [{ method: 'POST', url: analysisUrl(requestFactory), status: 200, at: '2026-11-20T00:00:00Z', durationMs: 250 }] },
  }));
  assert.equal((await record('LIUSHANMEN', 'LIUSHANMEN')).concurrentRequestsObserved, true);
  assert.equal((await record('F006', 'LIUSHANMEN')).concurrentRequestsObserved, false);
  assert.equal((await record('LSMDEMO', 'LSMDEMO')).concurrentRequestsObserved, false);
});

test('analysis scenario and all three recorders use the shared factory-scope module', () => {
  for (const file of [
    'scenarios/smartbi-factory-analysis-readonly.js', 'core/console-recorder.js',
    'core/network-recorder.js', 'core/parallel-run.js',
  ]) {
    const source = readFileSync(path.join(__dirname, '..', file), 'utf8');
    assert.match(source, /\bconst \{[^}]*\bisFactoryAnalysisUrl\b[^}]*\} = require\('\.\.\/config\/factory-analysis-scope'\)/u, file);
    assert.match(source, /\bisFactoryAnalysisUrl\(/u, file);
    assert.doesNotMatch(source.replaceAll('\\/', '/'), /\/api\/mobile\/F006\/smart-bi\/factory-analysis/u, file);
  }
});
