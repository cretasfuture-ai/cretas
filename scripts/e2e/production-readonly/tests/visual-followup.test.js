'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {step,FOLLOWUP_PAGES}=require('../scenarios/visual-unverified-followup-readonly');
const {describeHarness}=require('../core/run-suite');
test('followup scenarios are opt-in and targets use current bounded hubs',()=>{
  const {scenarios,defaultScenarios}=describeHarness();
  for(const id of ['sales-date-draft-readonly','workflow-viewport-readonly','visual-unverified-followup-readonly']){assert.ok(scenarios.includes(id));assert.ok(!defaultScenarios.includes(id));}
  assert.equal(FOLLOWUP_PAGES.length,5);assert.ok(FOLLOWUP_PAGES.some(p=>p.path==='/production/kanban?tab=pending-queue'));
});
test('guarded step refuses execution before and after mutation attempts',async()=>{
  let count=0;const ctx={mutationGuard:{snapshot:()=>({actualBusinessWrites:0,blockedMutationAttempts:count?[{}]:[]})}};
  await assert.rejects(step(ctx,async()=>{count++;}),/guard stopped/);
  await assert.rejects(step(ctx,async()=>assert.fail('must not run')),/guard stopped/);
});
