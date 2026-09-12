'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { assertPreserved } = require('../scenarios/transfer-draft-readonly');
test('draft comparison rejects each lost field, including a cleared quantity', () => {
  const before = { remark: 'draft', date: '2030-01-15', source: 'warehouse', quantity: '1.000' };
  assert.doesNotThrow(() => assertPreserved(before, { ...before }));
  for (const key of Object.keys(before)) assert.throws(() => assertPreserved(before, { ...before, [key]: '' }), /lost field/);
});
