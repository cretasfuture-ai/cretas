'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { assertPosition } = require('../scenarios/table-top-scroll-readonly');
const valid = { external: true, overlap: 0, widthDifference: 0, visible: true, bodyRange: 400, topLeft: 120, bodyLeft: 120, headerLeft: 120 };
test('accepts synchronized external scrollbar geometry', () => assert.doesNotThrow(() => assertPosition(valid)));
test('rejects intruding, missing, non-overflowing and disconnected scrollbar measurements', () => {
  for (const invalid of [{ external: false }, { overlap: 8 }, { widthDifference: 10 }, { visible: false }, { bodyRange: 0 }, { topLeft: 0 }, { headerLeft: 0 }]) {
    assert.throws(() => assertPosition({ ...valid, ...invalid }));
  }
});
