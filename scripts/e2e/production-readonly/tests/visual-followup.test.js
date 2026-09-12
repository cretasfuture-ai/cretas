'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {step,FOLLOWUP_PAGES}=require('../scenarios/visual-unverified-followup-readonly');
const {describeHarness}=require('../core/run-suite');
const boards=require('../scenarios/production-boards-readonly');
const {verifyTarget}=require('../scenarios/visual-unverified-followup-readonly');
const {waitForQueueRows}=require('../scenarios/visual-unverified-followup-readonly');

test('queue readiness rejects stuck loading and rechecks the mutation guard',async()=>{
  const ctx={mutationGuard:{snapshot:()=>({actualBusinessWrites:0,blockedMutationAttempts:[]})}};
  await assert.rejects(waitForQueueRows({locator:()=>({waitFor:async()=>{throw new Error('loading timeout');}})},ctx),/loading timeout/);
  const page={locator:selector=>{assert.equal(selector,'.queue-table .el-loading-mask');return {waitFor:async options=>assert.equal(options.state,'hidden')};}};
  await waitForQueueRows(page,ctx);
  ctx.mutationGuard.snapshot=()=>({actualBusinessWrites:1,blockedMutationAttempts:[]});
  await assert.rejects(waitForQueueRows(page,ctx),/guard stopped/);
});

test('target verification waits for route completion and retains exact query checks',async()=>{
  const expected='https://example.test/production/kanban?tab=restock-board';
  let current='https://example.test/production/kanban?tab=pending-queue';
  const element={first(){return this;},locator(){return this;},waitFor:async()=>{},
    getAttribute:async()=> 'true',isVisible:async()=>true};
  const page={url:()=>current,locator:()=>element,waitForURL:async predicate=>{
    assert.equal(predicate(new URL(current)),false);
    assert.equal(predicate(new URL(expected+'&unexpected=1')),false);
    current=expected; assert.equal(predicate(new URL(current)),true);
  }};
  const result=await verifyTarget(page,{baseUrl:'https://example.test',mutationGuard:{snapshot:()=>({actualBusinessWrites:0,blockedMutationAttempts:[]})}},'/production/kanban?tab=restock-board',null,'restock-board');
  assert.equal(result.routeMatches,true);
});

test('board acceptance is bounded while the full followup keeps label QC',async()=>{
  assert.deepEqual(boards.targets.map(p=>p.id),['pending-queue','restock-board','bom-achievement']);
  assert.ok(FOLLOWUP_PAGES.some(p=>p.id==='label-qc'));
  assert.ok(describeHarness().scenarios.includes(boards.id));
  assert.ok(!describeHarness().defaultScenarios.includes(boards.id));
  await assert.rejects(boards.run({mutationGuard:{snapshot:()=>({actualBusinessWrites:0,blockedMutationAttempts:[{}]})}}),/guard stopped/);
});
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
