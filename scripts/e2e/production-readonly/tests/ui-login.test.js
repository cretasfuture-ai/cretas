'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { allowsMissingFactoryName, performUiLogin, waitForLoginOutcome } = require('../core/ui-login');

test('allows the factory name to be absent only on an explicitly expected mobile-only landing', () => {
  assert.equal(allowsMissingFactoryName('https://example.test/mobile-only?role=quality_inspector', true), true);
  assert.equal(allowsMissingFactoryName('https://example.test/mobile-only?role=quality_inspector', false), false);
  assert.equal(allowsMissingFactoryName('https://example.test/dashboard', true), false);
});

test('waits for the login response through Playwright without host timer globals', async () => {
  const request = { method: () => 'POST', url: () => 'https://example.test/api/mobile/auth/unified-login' };
  const response = { request: () => request };
  const page = {
    waitForResponse: async (predicate, options) => {
      assert.equal(options.timeout, 1234);
      assert.equal(predicate(response), true);
      return response;
    },
  };
  const outcome = await waitForLoginOutcome(page, (candidate) => candidate === request, 1234);
  assert.deepEqual(outcome, { type: 'response', response });
});

test('waits for login app mount before finding or filling credentials', async () => {
  let mounted = false;
  const events = [];
  const page = {
    locator(selector) {
      if (selector === '#app-loader') return { waitFor: async (options) => {
        assert.equal(options.state, 'hidden');
        assert.equal(options.timeout, 90_000);
        await Promise.resolve();
        mounted = true;
        events.push('mounted');
      } };
      assert.equal(mounted, true, 'credential fields must not be queried during splash');
      return { first: () => ({ waitFor: async () => {
        events.push('fields');
        throw new Error('credential-field-reached');
      } }) };
    },
  };
  await assert.rejects(() => performUiLogin(page, {
    username: 'f006_admin', password: 'irrelevant', appMountTimeoutMs: 90_000,
  }), /credential-field-reached/);
  assert.deepEqual(events, ['mounted', 'fields']);
});

test('an unmounted login fails before credentials or an auth request', async () => {
  const page = { locator(selector) {
    assert.equal(selector, '#app-loader');
    return { waitFor: async () => { throw new Error('splash timeout'); } };
  } };
  await assert.rejects(() => performUiLogin(page, {
    username: 'f006_admin', password: 'irrelevant',
  }), /Login application did not mount before credential entry/);
});
