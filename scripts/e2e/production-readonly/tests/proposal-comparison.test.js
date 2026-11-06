'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {classifyMutation} = require('../core/mutation-guard');
const {proposalComparisonBodyMatches} = require('../config/readonly-post-whitelist');
const {assertCalculation, assertCoordinatedActor} = require('../scenarios/proposal-comparison-readonly');

function request() {
  const common = {comparisonBasisId: 'QA', currency: 'CNY', costUnit: '每批', resourceUnit: 'PERSON_HOUR'};
  return {...common, windowStart: '2026-11-07T00:00:00Z', windowEnd: '2026-11-10T00:00:00Z',
    requiredBy: null, availableResource: 20,
    candidates: ['A','B'].map(id => ({...common, id, cost: 100,
      completionTime: '2026-11-08T00:00:00Z', requiredResource: 8}))};
}
const endpoint = 'https://admin.example/api/mobile/F006/proposal-comparisons/evaluate';

test('accepts the actual flat auth/me UserDTO with exact active coordinated actor', () => {
  assert.deepEqual(assertCoordinatedActor({factoryId:'F006',id:1309,username:'f006_admin',
    roleCode:'factory_super_admin',isActive:true},'f006_admin'),
  {factoryId:'F006',actorId:1309,role:'factory_super_admin',active:true});
});

for (const patch of [{factoryId:'F001'},{id:1310},{username:'other'},{roleCode:'platform_admin'},
  {isActive:false},{isActive:'true'}]) {
  test('rejects independently altered flat identity: '+JSON.stringify(patch), () => {
    assert.throws(() => assertCoordinatedActor({factoryId:'F006',id:1309,username:'f006_admin',
      roleCode:'factory_super_admin',isActive:true,...patch},'f006_admin'));
  });
}

test('exact bounded pure calculation remains readonly, including absent and mixed supplied facts', () => {
  const body = request();
  assert.equal(classifyMutation('POST', endpoint, undefined, body).kind, 'readonly-post');
  body.candidates[0].cost = null;
  body.candidates[1].currency = 'USD';
  body.candidates[1].resourceUnit = 'MACHINE_HOUR';
  assert.equal(proposalComparisonBodyMatches(body), true);
});

for (const extra of ['factoryId','execute','reserve','approvalToken']) {
  test(`rejects extra root and candidate ${extra} keys before send`, () => {
    const body = request();
    body[extra] = true;
    assert.equal(classifyMutation('POST', endpoint, undefined, body).kind, 'business-mutation');
    const candidateExtra = request();
    candidateExtra.candidates[0][extra] = true;
    assert.equal(proposalComparisonBodyMatches(candidateExtra), false);
  });
}

test('unknown paths, mutations and absent body cannot use the calculation query exception', () => {
  for (const method of ['PUT','PATCH','DELETE']) {
    assert.equal(classifyMutation(method, endpoint, undefined, request()).kind, 'business-mutation');
  }
  for (const path of [endpoint+'/apply', endpoint.replace('/evaluate','/execute'),
    endpoint.replace('/evaluate','/reserve'), 'https://admin.example/api/mobile/F006/config/v2/ai/apply-diffs']) {
    assert.equal(classifyMutation('POST', path, undefined, request()).kind, 'business-mutation');
  }
  assert.equal(classifyMutation('POST', endpoint).kind, 'business-mutation');
});

test('bounded typed payload rejects invalid units, amounts, date shapes and candidate budgets', () => {
  for (const change of [
    value => {value.resourceUnit = 'HOURS'},
    value => {value.currency = ['CNY']},
    value => {value.candidates[0].cost = '100'},
    value => {value.availableResource = -1},
    value => {value.candidates[0].requiredResource = Infinity},
    value => {value.windowStart = '2026-11-07'},
    value => {value.candidates = value.candidates.slice(0,1)},
    value => {value.candidates = Array.from({length:21}, () => value.candidates[0])},
    value => {value.candidates[0].id = '../outside'},
    value => {value.comparisonBasisId = 'x'.repeat(65)},
  ]) {
    const body = request(); change(body);
    assert.equal(proposalComparisonBodyMatches(body), false);
  }
});

function result() {
  return {comparisonBasisId:'QA-FOOD-PROPOSAL-UI-20261106', status:'SUPPLIED_FACTS_COMPARABLE',
    factsVerified:false,businessExecutionPerformed:false,resourcesReserved:false,
    nonDominatedCandidateIds:['A','B'], candidates:[
      {id:'A',reasons:[],dominatedByCandidateIds:[]},
      {id:'B',reasons:[],dominatedByCandidateIds:[]},
      {id:'C',reasons:[],dominatedByCandidateIds:['A','B']},
    ]};
}

test('production assertions require distinct tradeoffs, exact candidate identity and all three false declarations', () => {
  assert.equal(assertCalculation(result(),'positive'), true);
  for (const field of ['factsVerified','businessExecutionPerformed','resourcesReserved']) {
    const bad=result();bad[field]=true;
    assert.throws(() => assertCalculation(bad,'positive'));
  }
  const wrong=result();wrong.nonDominatedCandidateIds=['C'];
  assert.throws(() => assertCalculation(wrong,'positive'));
  const mixed=result();mixed.status='NOT_COMPARABLE';mixed.nonDominatedCandidateIds=[];
  mixed.candidates[0].reasons=['CURRENCY_MISMATCH'];
  assert.equal(assertCalculation(mixed,'mixed-currency'),true);
  assert.throws(() => assertCalculation(mixed,'missing-cost'));
});
