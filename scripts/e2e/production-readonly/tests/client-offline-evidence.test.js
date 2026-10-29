'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { installNetworkRecorder } = require('../core/network-recorder');
const { installConsoleRecorder } = require('../core/console-recorder');
const URL = 'https://admin.cretaceousfuture.com/api/mobile/F006/smart-bi/factory-analysis';
const req = (url = URL, error = 'net::ERR_INTERNET_DISCONNECTED') => ({ url: () => url, method: () => 'POST', failure: () => ({ errorText: error }) });

test('expected client offline evidence cannot hide a second failure, other tenant or blocked request', () => {
  const ctx = new EventEmitter(); const recorder = installNetworkRecorder(ctx);
  const first = req(); ctx.emit('requestfailed', first); recorder.expectClientOfflineFailure(first);
  const other = req(URL.replace('F006', 'F002')); ctx.emit('requestfailed', other);
  assert.throws(() => recorder.expectClientOfflineFailure(other), /verified/);
  const blocked = req(URL, 'net::ERR_BLOCKED_BY_CLIENT'); ctx.emit('requestfailed', blocked);
  assert.throws(() => recorder.expectClientOfflineFailure(blocked), /verified/);
  const second = req(); ctx.emit('requestfailed', second);
  assert.throws(() => recorder.expectClientOfflineFailure(second), /exactly one/);
  assert.equal(recorder.snapshot().expectedTransportFailures.length, 1);
  assert.equal(recorder.snapshot().failedRequests.length, 2);
  recorder.dispose();
});

test('only the exact verified resource error is expected; product errors and page errors remain failures', () => {
  const page = new EventEmitter(); const context = new EventEmitter();
  context.pages = () => [page]; context.addInitScript = () => {};
  const recorder = installConsoleRecorder(context);
  const emit = (text, location = URL) => page.emit('console', { type: () => 'error', text: () => text, location: () => ({ url: location }) });
  emit('Failed to load resource: net::ERR_INTERNET_DISCONNECTED');
  emit('TypeError: broken state'); page.emit('pageerror', new Error('Unexpected application rejection'));
  recorder.expectClientOfflineResourceError(req());
  assert.equal(recorder.snapshot().expectedConsoleErrors.length, 1);
  assert.equal(recorder.snapshot().consoleErrors.length, 1);
  assert.equal(recorder.snapshot().pageErrors.length, 1);
  emit('Failed to load resource: net::ERR_INTERNET_DISCONNECTED', URL.replace('F006', 'F002'));
  assert.equal(recorder.snapshot().consoleErrors.length, 2);
  assert.throws(() => recorder.expectClientOfflineResourceError(req(URL, 'net::ERR_BLOCKED_BY_CLIENT')), /Unverified/);
  recorder.dispose();
});
