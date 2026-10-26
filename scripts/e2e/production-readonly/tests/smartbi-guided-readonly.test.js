'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const scenario = require('../scenarios/smartbi-guided-readonly');

function response(data, request = {}) { return { status: 200, body: { success: true, data }, request }; }
const data = { factoryId: 'F006', source: 'business', analysisScopeLabel: '全厂范围', period: { label: '全部历史' }, subjects: Array.from({ length: 100 }, (_, i) => ({ id: `product-${i}`, label: `产品${i}` })), recommendations: [
  { id: 'inventory-stock', domain: '库存', analysis: { familyId: 'inventory.stock' } },
  { id: 'production-output', domain: '生产', analysis: { familyId: 'production.output' } },
] };

function baseVisible(values) { return { count: values.length, values }; }

test('guided assessment reads actual visible cards and product echo', async () => {
  const result = await scenario.assess({
    initial: response(data, { guided: true }),
    product: response({ ...data, analysisSubject: 'product-0' }, { guided: true, analysis_subject: 'product-0' }),
    baseline: response(data, { guided: true, analysis_subject: null }),
    initialVisible: baseVisible([{ id: 'inventory-stock', familyId: 'inventory.stock', domain: '库存' }]),
    categoryVisible: baseVisible([{ id: 'inventory-stock', familyId: 'inventory.stock', domain: '库存' }]),
    categoryDomain: '库存', familyRows: 1, beforeGuide: 'a', categoryShot: 'b', productShot: 'c',
  });
  assert.equal(result.result, 'PASS');
  assert.equal(result.subjectCount, 100);
});

test('guided assessment fails on duplicate visible families and overbound cards', async () => {
  const result = await scenario.assess({
    initial: response(data, { guided: true }),
    initialVisible: baseVisible([
      { id: 'inventory-stock', familyId: 'inventory.stock', domain: '库存' },
      { id: 'production-output', familyId: 'inventory.stock', domain: '库存' },
      { id: 'inventory-stock', familyId: 'inventory.stock', domain: '库存' },
      { id: 'production-output', familyId: 'inventory.stock', domain: '库存' },
      { id: 'inventory-stock', familyId: 'inventory.stock', domain: '库存' },
    ]),
    categoryVisible: baseVisible([{ id: 'inventory-stock', familyId: 'inventory.stock', domain: '库存' }]),
    categoryDomain: '库存', familyRows: 1, beforeGuide: 'a', categoryShot: 'b',
  });
  assert.equal(result.result, 'CONFIRMED_DEFECT');
  assert.equal(result.checks.visibleCardsBounded, false);
  assert.equal(result.checks.visibleFamiliesDistinct, false);
});

test('guided assessment fails when category cards cross selected domain', async () => {
  const result = await scenario.assess({
    initial: response(data, { guided: true }),
    initialVisible: baseVisible([{ id: 'inventory-stock', familyId: 'inventory.stock', domain: '库存' }]),
    categoryVisible: baseVisible([{ id: 'production-output', familyId: 'production.output', domain: '生产' }]),
    categoryDomain: '库存', familyRows: 1, beforeGuide: 'a', categoryShot: 'b',
  });
  assert.equal(result.result, 'CONFIRMED_DEFECT');
  assert.equal(result.checks.categoryCardsMatchDomain, false);
});
