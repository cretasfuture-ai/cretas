'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {assertMetadataPage,assertRecordedComparison,chooseRecordedPair}=require('../scenarios/decision-history-review-readonly');
const record=id=>({id,decisionType:'RULE_EXECUTION',decisionCode:null,entityType:'ProductionPlan',entityId:'plan',
  ruleConfigId:null,ruleConfigVersion:null,createdAt:null});
const page=()=>({records:[record('new'),record('old')],page:0,pageSize:20,totalElements:2,totalPages:1});
const missing=()=>({decisionId:'new',baselineId:'old',status:'NOT_COMPARABLE',reason:'SNAPSHOT_MISSING_OR_INVALID',
  canonicalRecordedOutputHash:null,canonicalBaselineOutputHash:null,integrityVerified:false,
  recordedOutputRelation:'NOT_EVALUATED',comparisonBasis:'NOT_EVALUATED',businessExecutionPerformed:false});

test('live metadata assertions reject leaked fields, duplicate references and contradictory page facts',()=>{
  assert.equal(assertMetadataPage(page()),true);
  for (const mutate of [p=>p.records[0].inputContext='private',p=>p.records[1].id='new',p=>p.totalPages=0,
    p=>{p.totalElements=0;p.totalPages=0;},p=>p.records=[],p=>p.page=1,p=>p.records[0].id='../foreign']) {
    const p=page();mutate(p);assert.throws(()=>assertMetadataPage(p));
  }
});
test('record selection uses an actual older same-business row, never fabricates a missing baseline',()=>{
  assert.equal(chooseRecordedPair([record('only')]),null);
  const rows=[{...record('old'),createdAt:'2026-11-01T12:00:00'},
    {...record('new'),createdAt:'2026-11-02T12:00:00'}];
  const pair=chooseRecordedPair(rows);assert.equal(pair.current.id,'new');assert.equal(pair.baseline.id,'old');
  assert.equal(pair.matchingBusinessScope,true);
});
test('unavailable and semantic-only evidence remain not comparable without execution',()=>{
  assert.equal(assertRecordedComparison(missing(),'new','old'),true);
  const semantic={...missing(),reason:'CHECKSUM_MISMATCH_NO_INTEGRITY_PROOF',
    canonicalRecordedOutputHash:'a'.repeat(64),canonicalBaselineOutputHash:'a'.repeat(64),
    recordedOutputRelation:'SAME',comparisonBasis:'PERSISTED_COLUMNS_SEMANTIC_ONLY'};
  assert.equal(assertRecordedComparison(semantic,'new','old'),true);
  assert.throws(()=>assertRecordedComparison({...semantic,integrityVerified:true},'new','old'));
  assert.throws(()=>assertRecordedComparison({...semantic,recordedOutputRelation:'DIFFERENT'},'new','old'));
});
test('verified output requires correct identity, consistent full hashes and false execution',()=>{
  const good={...missing(),status:'SAME_RESULT',reason:'RECORDED_OUTPUT_EQUAL',integrityVerified:true,
    recordedOutputRelation:'SAME',comparisonBasis:'CHECKSUM_AND_COLUMNS',
    canonicalRecordedOutputHash:'a'.repeat(64),canonicalBaselineOutputHash:'a'.repeat(64)};
  assert.equal(assertRecordedComparison(good,'new','old'),true);
  for (const patch of [{decisionId:'other'},{businessExecutionPerformed:true},{canonicalRecordedOutputHash:null},
    {canonicalBaselineOutputHash:'b'.repeat(64)},{canonicalBaselineOutputHash:'a'}]) {
    assert.throws(()=>assertRecordedComparison({...good,...patch},'new','old'));
  }
});
