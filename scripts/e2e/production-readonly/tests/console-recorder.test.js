'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { DEFAULT_NOISE_RULES, matchesNoiseRule } = require('../core/console-recorder');
const { installConsoleRecorder } = require('../core/console-recorder');
const vm = require('node:vm');

test('window error bridge classifies browser events without leaking raw diagnostics', () => {
  const callbacks = {};
  const output = [];
  let init;
  installConsoleRecorder({ addInitScript(fn) { init = fn; }, pages() { return []; }, on() {} });
  vm.runInNewContext(`(${init.toString()})()`, {
    window: { addEventListener(name, callback) { callbacks[name] = callback; } },
    console: { error(value) { output.push(value); } },
  });
  for (const message of ['ResizeObserver loop completed with undelivered notifications.',
    'ResizeObserver loop limit exceeded', 'Script error.', 'secret@example.com token=private']) {
    callbacks.error({ message, filename: 'https://private.example/secret', error: new Error('private') });
  }
  assert.deepEqual(output, ['[window_error_class] resize_observer_delivery',
    '[window_error_class] resize_observer_delivery', '[window_error_class] opaque_script_error',
    '[window_error_class] other_window_error']);
  assert.equal(DEFAULT_NOISE_RULES.some(rule => rule.pattern.test(output[0])), false);
});

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
