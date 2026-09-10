'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { VISUAL_PAGES, runVisualCensus, routeIdentity } = require('../scenarios/visual-page-census-readonly');
const { describeHarness } = require('../core/run-suite');

test('census route identity works without MCP global URL or URLSearchParams', () => {
  const vm = require('node:vm');
  const { splitUrl, resolveUrl } = require('../core/url-utils');
  const context = vm.createContext({ splitUrl, resolveUrl, URL: undefined, URLSearchParams: undefined });
  const check = vm.runInContext(`(${routeIdentity.toString()})`, context);
  assert.equal(check('/warehouse/inventory-query?tab=total', 'https://fixture', 'https://fixture/warehouse/inventory-query?tab=total').routeMatches, true);
  assert.equal(check('/warehouse/inventory-query?tab=total', 'https://fixture', 'https://fixture/warehouse/inventory-query?tab=batch').routeMatches, false);
  assert.equal(check('/dashboard', 'https://fixture', 'https://fixture/login').routeMatches, false);
});

test('visual census is opt-in and bounded to existing read-only page routes', () => {
  const description = describeHarness();
  assert.ok(description.scenarios.includes('visual-page-census-readonly'));
  assert.ok(!description.defaultScenarios.includes('visual-page-census-readonly'));
  assert.equal(new Set(VISUAL_PAGES.map(item => item.id)).size, VISUAL_PAGES.length);
  for (const item of VISUAL_PAGES) {
    assert.match(item.path, /^\/[a-z]/);
    assert.doesNotMatch(item.path, /(?:\/new|\/edit|https?:|\/restaurant|\/delete|\/submit)/);
  }
  assert.ok(VISUAL_PAGES.some(item => item.path === '/transfer/list'));
  assert.ok(VISUAL_PAGES.some(item => item.path === '/warehouse/inventory-query?tab=total'));
  assert.ok(VISUAL_PAGES.some(item => item.path === '/quality/inspections'));
});

test('visual census never navigates when a mutation was already blocked', async () => {
  const ctx = { page: { url: () => 'about:blank', goto: () => assert.fail('must not navigate') }, mutationGuard: { snapshot: () => ({ actualBusinessWrites: 0, blockedMutationAttempts: [{}] }) } };
  const result = await runVisualCensus(ctx);
  assert.equal(result.pageEvidence.length, 0);
  assert.notEqual(result.result, 'PASS');
});
