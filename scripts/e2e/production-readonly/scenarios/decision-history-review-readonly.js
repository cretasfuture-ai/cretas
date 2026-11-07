'use strict';

const {runReadOnlyPageScenario} = require('./_shared');
const {assertCoordinatedActor} = require('./proposal-comparison-readonly');
const id = 'decision-history-review-readonly';
const route = '/workspaces/trace-review';
const recordKeys = ['id','decisionType','decisionCode','entityType','entityId','ruleConfigId','ruleConfigVersion','createdAt'];
const reasons = ['INVALID_HISTORICAL_BASELINE','BUSINESS_SCOPE_MISMATCH','SNAPSHOT_MISSING_OR_INVALID','INPUT_MISMATCH'];
const hash = /^[0-9a-f]{64}$/i;
function requireCondition(value,message) { if (!value) throw new Error(message); }

function assertMetadataPage(data) {
  requireCondition(data && Array.isArray(data.records) && data.page===0 && data.pageSize===20,
    'Expected the actual first bounded history page');
  requireCondition(Number.isSafeInteger(data.totalElements) && data.totalElements>=0
    && data.totalPages===Math.ceil(data.totalElements/20) && data.records.length<=20
    && data.records.length<=data.totalElements
    && (data.totalElements===0 ? data.records.length===0 : data.records.length>0)
    && new Set(data.records.map(row=>row.id)).size===data.records.length,'History pagination or identity is inconsistent');
  for (const row of data.records) {
    requireCondition(row && Object.keys(row).length===recordKeys.length
      && Object.keys(row).every(key=>recordKeys.includes(key)),'History exposed unexpected private fields');
    requireCondition(typeof row.id==='string' && /^[A-Za-z0-9_-]{1,128}$/.test(row.id)
      && typeof row.entityType==='string' && typeof row.entityId==='string','History record reference is invalid');
  }
  return true;
}

function assertRecordedComparison(data,currentId,baselineId) {
  requireCondition(data && data.decisionId===currentId && data.baselineId===baselineId
    && data.businessExecutionPerformed===false,'Comparison identity or nonexecution contract changed');
  const left=data.canonicalRecordedOutputHash; const right=data.canonicalBaselineOutputHash;
  if (data.status==='SAME_RESULT' || data.status==='DIFFERENT_RESULT') {
    const same=data.status==='SAME_RESULT';
    requireCondition(data.integrityVerified===true && data.comparisonBasis==='CHECKSUM_AND_COLUMNS'
      && data.recordedOutputRelation===(same?'SAME':'DIFFERENT')
      && data.reason===(same?'RECORDED_OUTPUT_EQUAL':'RECORDED_OUTPUT_DIFFERENT')
      && typeof left==='string' && hash.test(left) && typeof right==='string' && hash.test(right)
      && (left===right)===same,'Verified result does not have consistent recorded hashes');
  } else {
    requireCondition(data.status==='NOT_COMPARABLE' && data.integrityVerified===false,
      'Unknown evidence was promoted to comparable');
    if (data.reason==='CHECKSUM_MISMATCH_NO_INTEGRITY_PROOF') {
      requireCondition(['SAME','DIFFERENT'].includes(data.recordedOutputRelation)
        && data.comparisonBasis==='PERSISTED_COLUMNS_SEMANTIC_ONLY'
        && typeof left==='string' && hash.test(left) && typeof right==='string' && hash.test(right)
        && (left===right)===(data.recordedOutputRelation==='SAME'),
      'Semantic-only comparison lost its integrity limitation');
    } else {
      requireCondition(reasons.includes(data.reason) && data.recordedOutputRelation==='NOT_EVALUATED'
        && data.comparisonBasis==='NOT_EVALUATED' && left===null && right===null,
      'Unavailable historical evidence has contradictory result fields');
    }
  }
  return true;
}

function chooseRecordedPair(records) {
  for (const current of records) for (const baseline of records) {
    if (current.id!==baseline.id && current.entityType===baseline.entityType && current.entityId===baseline.entityId
      && current.decisionType===baseline.decisionType && current.createdAt && baseline.createdAt
      && Date.parse(baseline.createdAt)<Date.parse(current.createdAt)) return {current,baseline,matchingBusinessScope:true};
  }
  return records.length>=2 ? {current:records[0],baseline:records[1],matchingBusinessScope:false} : null;
}

async function scopedRead(page,path) {
  return page.evaluate(async url=>{
    const token=localStorage.getItem('cretas_access_token');
    if (!token) throw new Error('Fresh session missing');
    const response=await fetch(url,{headers:{Authorization:`Bearer ${token}`}});
    const body=await response.json();
    if (!response.ok || body.success!==true) throw new Error('History context read failed');
    return body.data;
  },path);
}

async function inspect(page,_body,ctx) {
  requireCondition(ctx.expectedFactoryId==='F006' && ctx.loginEvidence.factoryId==='F006','F006 context required');
  assertCoordinatedActor(await scopedRead(page,'/api/mobile/auth/me'),ctx.expectedUsername);
  requireCondition(await scopedRead(page,'/api/mobile/F006/demo-sandbox/active-session')===null,'Active sandbox requires coordination');
  await page.getByRole('heading',{name:'追溯与复盘',exact:true}).waitFor({state:'visible'});
  const panel=page.getByTestId('decision-history-panel'); await panel.waitFor({state:'visible'});
  const listResponse=page.waitForResponse(response=>{
    const url=new URL(response.url());
    return response.request().method()==='GET' && url.pathname==='/api/mobile/F006/decision-history'
      && url.searchParams.get('page')==='0' && url.searchParams.get('size')==='20';
  },{timeout:30000});
  await panel.getByTestId('history-load').click();
  const response=await listResponse; const body=await response.json();
  requireCondition(response.status()===200 && body.success===true,'Real history list failed');
  assertMetadataPage(body.data);
  const pair=chooseRecordedPair(body.data.records);
  if (!pair) return {factoryId:'F006',recordCount:body.data.records.length,
    screenshots:[await ctx.screenshot(id,'insufficient-real-history')],
    assessment:{result:'UNVERIFIED',rootCauseClass:'data'}};
  await panel.getByTestId(`history-current-${pair.current.id}`).check();
  await panel.getByTestId(`history-baseline-${pair.baseline.id}`).check();
  requireCondition(await scopedRead(page,'/api/mobile/F006/demo-sandbox/active-session')===null,'Sandbox changed before read comparison');
  const comparisonResponse=page.waitForResponse(candidate=>{
    const url=new URL(candidate.url());
    return candidate.request().method()==='GET'
      && url.pathname===`/api/mobile/F006/decision-history/${pair.current.id}/comparison`
      && url.searchParams.get('baselineId')===pair.baseline.id;
  },{timeout:30000});
  await panel.getByTestId('history-compare').click();
  const compared=await comparisonResponse; const comparedBody=await compared.json();
  requireCondition(compared.status()===200 && comparedBody.success===true,'Real record comparison failed');
  assertRecordedComparison(comparedBody.data,pair.current.id,pair.baseline.id);
  const result=panel.getByTestId('history-result'); await result.waitFor({state:'visible'});
  const text=await result.innerText();
  requireCondition(text.includes(comparedBody.data.reason) && text.includes('不会重新执行规则'),
    'Visible comparison lost its actual reason or nonexecution declaration');
  if (comparedBody.data.status==='NOT_COMPARABLE') {
    requireCondition(text.includes('不可比较') && !text.includes('（已核验）'),
      'Unavailable historical evidence is shown as verified success');
  }
  await result.scrollIntoViewIfNeeded();
  return {factoryId:'F006',actorId:1309,listedRecords:body.data.records.length,
    decisionId:pair.current.id,baselineId:pair.baseline.id,matchingBusinessScope:pair.matchingBusinessScope,
    observedStatus:comparedBody.data.status,observedReason:comparedBody.data.reason,
    businessExecutionPerformed:false,integrityVerified:comparedBody.data.integrityVerified,
    screenshots:[await ctx.screenshot(id,'recorded-result')],
    assessment:{result:'PASS',rootCauseClass:'none'}};
}

module.exports={id,defaultEnabled:false,assertMetadataPage,assertRecordedComparison,chooseRecordedPair,
  run:ctx=>runReadOnlyPageScenario(ctx,{id,path:route,landmarks:['追溯与复盘'],screenshot:false,inspect})};
