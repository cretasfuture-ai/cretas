'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { DEFAULT_NOISE_RULES, matchesNoiseRule } = require('../core/console-recorder');

test('filters only the documented capability-rollout 503 resource error', () => {
  const rule = DEFAULT_NOISE_RULES.find((item) => item.id === 'capability-gradual-rollout-503');
  assert.ok(rule);
  const message = 'Failed to load resource: the server responded with a status of 503 ()';
  assert.equal(matchesNoiseRule(
    rule,
    message,
    'https://admin.example/smartbi-api/api/smartbi/capability/F006',
  ), true);
  assert.equal(matchesNoiseRule(
    rule,
    message,
    'https://admin.example/api/mobile/F006/sales/orders',
  ), false);
  assert.equal(matchesNoiseRule(
    rule,
    'Failed to load resource: the server responded with a status of 500 ()',
    'https://admin.example/smartbi-api/api/smartbi/capability/F006',
  ), false);
});
