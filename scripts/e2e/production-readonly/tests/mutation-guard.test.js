'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { installMutationGuard } = require('../core/mutation-guard');
const { READONLY_POST_WHITELIST, chartInsightReadonlyPost } = require('../config/readonly-post-whitelist');

class FakeContext {
  constructor() { this.listeners = new Map(); }
  async route(_pattern, handler) { this.handler = handler; }
  async unroute(_pattern, handler) { assert.equal(handler, this.handler); }
  on(name, handler) { this.listeners.set(name, handler); }
  off(name, handler) { assert.equal(this.listeners.get(name), handler); this.listeners.delete(name); }
}

function fakeRoute(method, url, body = null) {
  const request = { method: () => method, url: () => url, postDataJSON: () => body };
  const calls = [];
  return {
    request,
    calls,
    route: {
      request: () => request,
      continue: async () => calls.push('continue'),
      abort: async (reason) => calls.push(`abort:${reason}`),
    },
  };
}

test('blocks an unexpected mutation before send and records the active scenario', async () => {
  const context = new FakeContext();
  const guard = await installMutationGuard(context, { scenarioRef: { value: 'bom-readonly' } });
  const attempt = fakeRoute('PATCH', 'https://admin.example/api/mobile/F006/boms/276');
  await context.handler(attempt.route);
  assert.deepEqual(attempt.calls, ['abort:blockedbyclient']);
  assert.equal(guard.actualBusinessWrites, 0);
  assert.equal(guard.blockedMutationAttempts.length, 1);
  assert.equal(guard.blockedMutationAttempts[0].scenario, 'bom-readonly');
  assert.equal(guard.blockedMutationAttempts[0].blockedBeforeSend, true);
  await guard.dispose();
});

test('allows UI login and exact read-only query POSTs', async () => {
  const context = new FakeContext();
  const guard = await installMutationGuard(context);
  const login = fakeRoute('POST', 'https://admin.example/api/mobile/auth/unified-login');
  const query = fakeRoute('POST', 'https://admin.example/api/mobile/F006/list-summary/purchaseOrder');
  await context.handler(login.route);
  await context.handler(query.route);
  assert.deepEqual(login.calls, ['continue']);
  assert.deepEqual(query.calls, ['continue']);
  assert.equal(guard.authRequests.length, 1);
  assert.equal(guard.readonlyPostRequests.length, 1);
  assert.equal(guard.blockedMutationAttempts.length, 0);
  await guard.dispose();
});

const origin = 'https://admin.example';
const chartInsightPath = '/smartbi-api/api/smartbi/chart-insight';
const insightBody = { factory_id: 'LIUSHANMEN' };

async function checkChartInsight(
  { method = 'POST', url = origin + chartInsightPath, body = insightBody, provenFactory = 'LIUSHANMEN', active = true },
  allowed,
) {
  const context = new FakeContext();
  const entry = chartInsightReadonlyPost(origin, () => active, () => provenFactory);
  const guard = await installMutationGuard(context, { readonlyPostWhitelist: [...READONLY_POST_WHITELIST, entry] });
  const attempt = fakeRoute(method, url, body);
  await context.handler(attempt.route);
  assert.deepEqual(attempt.calls, [allowed ? 'continue' : 'abort:blockedbyclient']);
  if (allowed) {
    context.listeners.get('response')({ request: () => attempt.request, status: () => 200 });
    assert.equal(guard.readonlyPostRequests[0].classificationId, 'smartbi-chart-insight');
  } else {
    assert.equal(guard.blockedMutationAttempts[0].blockedBeforeSend, true);
  }
  assert.equal(guard.actualBusinessWrites, 0);
  await guard.dispose();
}

test('chart insight permits the exact POST for a scoped live login', async () => {
  await checkChartInsight({}, true);
});

for (const [name, options] of Object.entries({
  'other SmartBI POST': { url: origin + '/smartbi-api/api/smartbi/insight/feedback' },
  'trailing-slash alias': { url: origin + chartInsightPath + '/' },
  'alternate gateway path': { url: origin + '/api/smartbi/chart-insight' },
  'wrong verb': { method: 'PUT' },
  'another origin': { url: 'https://other.example' + chartInsightPath },
  'different tenant': { body: { factory_id: 'F006' } },
  'LSMDEMO tenant even with a matching proof': { body: { factory_id: 'LSMDEMO' }, provenFactory: 'LSMDEMO' },
  'missing live proof': { provenFactory: null },
  'missing wire factory_id': { body: { factoryId: 'LIUSHANMEN' } },
  'outside the approved scenarios': { active: false },
})) {
  test(`chart insight blocks ${name} before send`, async () => {
    await checkChartInsight(options, false);
  });
}

test('allows only the exact indicator query body on the shared AI endpoint', async () => {
  const context = new FakeContext();
  const guard = await installMutationGuard(context);
  const readonlyIndicator = fakeRoute(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    {
      userInput: '查询指标 stock_turnover',
      intentCode: 'INDICATOR_QUERY',
      context: { indicator_code: 'stock_turnover' },
      sessionId: 'indicator-card-stock_turnover-1',
      skipSlotFilling: true,
    },
  );
  const actionIntent = fakeRoute(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    {
      userInput: '查询指标 stock_turnover',
      intentCode: 'APPROVAL_ACTION_EXECUTE',
      context: { indicator_code: 'stock_turnover' },
      sessionId: 'indicator-card-stock_turnover-1',
      skipSlotFilling: true,
    },
  );
  await context.handler(readonlyIndicator.route);
  await context.handler(actionIntent.route);
  assert.deepEqual(readonlyIndicator.calls, ['continue']);
  assert.deepEqual(actionIntent.calls, ['abort:blockedbyclient']);
  assert.equal(guard.readonlyPostRequests[0].classificationId, 'indicator-query');
  assert.equal(guard.blockedMutationAttempts.length, 1);
  await guard.dispose();
});
