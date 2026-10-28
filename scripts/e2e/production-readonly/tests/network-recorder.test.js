'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { installNetworkRecorder } = require('../core/network-recorder');

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
