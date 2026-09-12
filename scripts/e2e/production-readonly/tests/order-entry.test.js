'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { assertHeaderContrast } = require('../scenarios/order-entry-readonly');

test('header contrast acceptance rejects empty, incomplete and low contrast measurements', () => {
  assert.throws(() => assertHeaderContrast([]));
  assert.throws(() => assertHeaderContrast([{ ratio: NaN }]));
  assert.throws(() => assertHeaderContrast([{ ratio: 13 }, { ratio: 3.08 }]));
  assert.doesNotThrow(() => assertHeaderContrast([{ ratio: 4.5 }, { ratio: 13 }]));
});
