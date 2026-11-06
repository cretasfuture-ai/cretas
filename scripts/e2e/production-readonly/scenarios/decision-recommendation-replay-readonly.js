'use strict';
const {runReadOnlyPageScenario}=require('./_shared');
const {assertCoordinatedActor}=require('./proposal-comparison-readonly');
const {assertMetadataPage}=require('./decision-history-review-readonly');
const id='decision-recommendation-replay-readonly';
const keys=['decisionId','status','reason','evaluationScope','evaluatorId','evaluatorVersion','ruleBundleHash','snapshotHash',
  'recordedRecommendation','replayedRecommendation','recordedConfidence','replayedConfidence',
  'snapshotConsistencyVerified','ruleEvaluationPerformed','businessExecutionPerformed','approvalEvaluated'];
const actions=['RELEASE','CONDITIONAL_RELEASE','REWORK','SCRAP','SPECIAL_APPROVAL','HOLD'];
const hash=/^[a-f0-9]{64}$/i;
const decimal=/^(?:0|[1-9]\d*)(?:\.\d+)?$/;
function requireCondition(value,message){if(!value)throw new Error(message);}
function assertReplay(data,decisionId){
  requireCondition(data && Object.keys(data).length===keys.length && Object.keys(data).every(key=>keys.includes(key)),
    'Replay exposed an unexpected field or omitted its read-only evidence');
  requireCondition(data.decisionId===decisionId && data.evaluationScope==='QUALITY_RECOMMENDATION_ONLY'
    && data.businessExecutionPerformed===false && data.approvalEvaluated===false,'Replay scope or nonexecution changed');
  if(data.status==='UNAVAILABLE'){
    requireCondition(data.snapshotConsistencyVerified===false && data.ruleEvaluationPerformed===false
      && typeof data.reason==='string' && /^[A-Z][A-Z0-9_]{1,79}$/.test(data.reason)
      && ['evaluatorId','evaluatorVersion','ruleBundleHash','snapshotHash','recordedRecommendation','replayedRecommendation',
        'recordedConfidence','replayedConfidence'].every(key=>data[key]===null),'Missing snapshot was promoted to a verified result');
    return true;
  }
  requireCondition(['REPLAY_MATCH','REPLAY_DIFFERENT'].includes(data.status)
    && data.snapshotConsistencyVerified===true && data.ruleEvaluationPerformed===true
    && data.evaluatorId==='QUALITY_RECOMMENDATION' && data.evaluatorVersion==='1.0.0'
    && typeof data.ruleBundleHash==='string' && hash.test(data.ruleBundleHash)
    && typeof data.snapshotHash==='string' && hash.test(data.snapshotHash)
    && actions.includes(data.recordedRecommendation) && actions.includes(data.replayedRecommendation)
    && typeof data.recordedConfidence==='string' && decimal.test(data.recordedConfidence)
    && typeof data.replayedConfidence==='string' && decimal.test(data.replayedConfidence),'Supported replay evidence is incomplete');
  const sameAction=data.recordedRecommendation===data.replayedRecommendation;
  const equal=sameAction && data.recordedConfidence===data.replayedConfidence;
  const expectedReason=equal?'RECORDED_RECOMMENDATION_MATCHES_REPLAY':sameAction
    ?'RECORDED_CONFIDENCE_DIFFERS_FROM_REPLAY':'RECORDED_RECOMMENDATION_DIFFERS_FROM_REPLAY';
  requireCondition((data.status==='REPLAY_MATCH')===equal && data.reason===expectedReason,
  'Replay status contradicts its recorded and evaluated pure outputs');
  return true;
}
async function read(page,path){return page.evaluate(async url=>{
  const token=localStorage.getItem('cretas_access_token');if(!token)throw new Error('Fresh session missing');
  const response=await fetch(url,{headers:{Authorization:`Bearer ${token}`}});const body=await response.json();
  if(!response.ok||body.success!==true)throw new Error('Readonly identity context read failed');return body.data;
},path);}
async function inspect(page,_body,ctx){
  requireCondition(ctx.expectedFactoryId==='F006','F006 context required');
  assertCoordinatedActor(await read(page,'/api/mobile/auth/me'),ctx.expectedUsername);
  requireCondition(await read(page,'/api/mobile/F006/demo-sandbox/active-session')===null,'Active sandbox requires coordination');
  const wanted=ctx.decisionReplay;
  requireCondition(wanted && wanted.syntheticQa===true && typeof wanted.decisionId==='string'
    && /^[A-Za-z0-9_-]{1,128}$/.test(wanted.decisionId),'An actual owned synthetic QA audit ID is required');
  const panel=page.getByTestId('decision-history-panel');await panel.waitFor({state:'visible'});
  const list=page.waitForResponse(response=>{
    const url=new URL(response.url());return response.request().method()==='GET' && url.pathname==='/api/mobile/F006/decision-history'
      && url.searchParams.get('page')==='0'&&url.searchParams.get('size')==='20';
  });
  await panel.getByTestId('history-load').click();const response=await list;const body=await response.json();
  requireCondition(response.ok()&&body.success===true,'Actual history list failed');assertMetadataPage(body.data);
  const current=body.data.records.find(row=>row.id===wanted.decisionId);
  if(!current)return {expectedDecisionFound:false,sourceIsSyntheticQa:true,
    screenshots:[await ctx.screenshot(id,'record-unavailable')],assessment:{result:'UNVERIFIED',rootCauseClass:'data'}};
  const observations=[];
  const legacy=body.data.records.find(row=>row.id!==current.id && row.entityType!=='QualityInspection');
  for(const [record,expected] of [[current,'REPLAY_MATCH'],...(legacy?[[legacy,'UNAVAILABLE']]:[])]){
    await panel.getByTestId(`history-current-${record.id}`).check();
    const button=panel.getByTestId('history-replay');await button.waitFor({state:'visible'});
    requireCondition(await button.isEnabled(),'Required system and quality read permission is absent');
    requireCondition(await read(page,'/api/mobile/F006/demo-sandbox/active-session')===null,'Sandbox changed before replay GET');
    const awaited=page.waitForResponse(candidate=>candidate.request().method()==='GET'
      &&new URL(candidate.url()).pathname===`/api/mobile/F006/decision-history/${record.id}/replay-evaluation`);
    await button.click();const replayResponse=await awaited;const replayBody=await replayResponse.json();
    requireCondition(replayResponse.ok()&&replayBody.success===true,'Actual replay evaluation GET failed');
    assertReplay(replayBody.data,record.id);requireCondition(replayBody.data.status===expected,'Unexpected actual replay outcome');
    const result=panel.getByTestId('history-replay-result');await result.waitFor({state:'visible'});
    const text=await result.innerText();requireCondition(text.includes(replayBody.data.reason)
      &&text.includes('不是防篡改签名')&&text.includes('不审批、不执行业务'),'Visible replay lost its scope or integrity limits');
    if(expected==='REPLAY_MATCH')requireCondition(text.includes(replayBody.data.evaluatorVersion)
      &&text.includes(replayBody.data.ruleBundleHash)&&text.includes(replayBody.data.recordedConfidence),'Visible pure output/provenance is incomplete');
    else requireCondition(text.includes('不可复算')&&!text.includes('（已核验）'),'Legacy row is shown as verified replay');
    await result.scrollIntoViewIfNeeded();observations.push({decisionId:record.id,status:replayBody.data.status,reason:replayBody.data.reason,
      screenshot:await ctx.screenshot(id,expected==='REPLAY_MATCH'?'actual-match':'legacy-unavailable')});
  }
  return {factoryId:'F006',actorId:1309,sourceIsSyntheticQa:true,productionMeasurementsVerified:false,
    businessExecutionPerformed:false,approvalEvaluated:false,observations,
    screenshots:observations.map(item=>item.screenshot),assessment:{result:'PASS',rootCauseClass:'none'}};
}
module.exports={id,defaultEnabled:false,assertReplay,run:ctx=>runReadOnlyPageScenario(ctx,
  {id,path:'/workspaces/trace-review',landmarks:['追溯与复盘'],screenshot:false,inspect})};
