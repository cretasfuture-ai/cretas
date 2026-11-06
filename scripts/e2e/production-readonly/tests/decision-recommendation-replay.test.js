'use strict';
const test=require('node:test');const assert=require('node:assert/strict');
const {assertReplay}=require('../scenarios/decision-recommendation-replay-readonly');
function valid(){return {decisionId:'audit-1',status:'REPLAY_MATCH',reason:'RECORDED_RECOMMENDATION_MATCHES_REPLAY',
  evaluationScope:'QUALITY_RECOMMENDATION_ONLY',evaluatorId:'QUALITY_RECOMMENDATION',evaluatorVersion:'1.0.0',
  ruleBundleHash:'a'.repeat(64),snapshotHash:'b'.repeat(64),recordedRecommendation:'RELEASE',replayedRecommendation:'RELEASE',
  recordedConfidence:'95',replayedConfidence:'95',snapshotConsistencyVerified:true,ruleEvaluationPerformed:true,
  businessExecutionPerformed:false,approvalEvaluated:false};}
test('binds a complete pure action/confidence output and accepts an honestly different confidence',()=>{
  assert.equal(assertReplay(valid(),'audit-1'),true);const changed=valid();changed.status='REPLAY_DIFFERENT';
  changed.reason='RECORDED_CONFIDENCE_DIFFERS_FROM_REPLAY';changed.recordedConfidence='94';assert.equal(assertReplay(changed,'audit-1'),true);
  changed.reason='RECORDED_RECOMMENDATION_DIFFERS_FROM_REPLAY';assert.throws(()=>assertReplay(changed,'audit-1'));
});
test('rejects false matches, hidden writes, raw payload and malformed proof before claiming acceptance',()=>{
  for(const change of [x=>{x.recordedConfidence='94'},x=>{x.businessExecutionPerformed=true},x=>{x.approvalEvaluated=true},
    x=>{x.rawInspection={}},x=>{x.snapshotHash='bad'},x=>{x.replayedConfidence=95},x=>{x.decisionId='other'},
    x=>{x.evaluatorVersion='current'}]){const x=valid();change(x);assert.throws(()=>assertReplay(x,'audit-1'));}
});
test('legacy records remain unavailable with no evaluator/output claims',()=>{
  const x={...valid(),status:'UNAVAILABLE',reason:'SNAPSHOT_MISSING_OR_INVALID',evaluatorId:null,evaluatorVersion:null,
    ruleBundleHash:null,snapshotHash:null,recordedRecommendation:null,replayedRecommendation:null,
    recordedConfidence:null,replayedConfidence:null,snapshotConsistencyVerified:false,ruleEvaluationPerformed:false};
  assert.equal(assertReplay(x,'audit-1'),true);x.recordedConfidence='0';assert.throws(()=>assertReplay(x,'audit-1'));
});
