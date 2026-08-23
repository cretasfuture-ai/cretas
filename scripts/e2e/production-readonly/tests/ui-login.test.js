'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { allowsMissingFactoryName, waitForLoginOutcome } = require('../core/ui-login');

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
