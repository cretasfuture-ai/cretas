'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { renderBundle } = require('../build-mcp-entry');

test('MCP filename entry is a single async page function with no top-level execution', () => {
  const source = fs.readFileSync(path.join(__dirname, '..', 'mcp-entry.js'), 'utf8');
  const entry = vm.runInNewContext(`(${source})`);
  assert.equal(typeof entry, 'function');
  assert.equal(entry.constructor.name, 'AsyncFunction');
  assert.match(source, /runSuiteWithPage\(page/);
  assert.equal(source, renderBundle(), 'mcp-entry.js must match the deterministic bundle from canonical core/scenarios');
  assert.doesNotMatch(source, /require\(['"]node:|\bprocess\.(?:env|cwd)/);
  const callbackStart = source.indexOf('chartHost.evaluate(async (element, target) => {');
  const callbackEnd = source.indexOf('}, { kind: point.kind', callbackStart);
  assert.ok(callbackStart >= 0 && callbackEnd > callbackStart, 'ECharts import callback must remain auditable');
  const auditedCallback = source.slice(callbackStart, callbackEnd);
  assert.equal((source.match(/import\(/g) || []).length, 1, 'only the audited browser callback may use import()');
  assert.match(auditedCallback, /await import\(resource\)/);
  const outsideCallback = source.slice(0, callbackStart) + source.slice(callbackEnd);
  assert.doesNotMatch(outsideCallback, /import\(/);
  assert.doesNotMatch(source, /eval\(|new Function\(/);
  assert.doesNotMatch(source, /chromium\.launch/);
});
