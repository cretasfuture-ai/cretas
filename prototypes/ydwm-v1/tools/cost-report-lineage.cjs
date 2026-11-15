'use strict';
// Prove plan-container -> submitted report -> its own batch; never reassign output or costs.
const crypto=require('crypto');
function lineage(batch,plan,sheet,batches){
 const factory='FOOD_0000_001',same=(a,b)=>a!=null&&b!=null&&String(a)===String(b);
 if(batch.factoryId!==factory||plan?.factoryId!==factory||!same(plan.id,batch.productionPlanId)||!same(plan.batchId,batch.id)||plan.batchNumber!==batch.batchNumber||plan.productTypeId!==batch.productTypeId||batch.prototypeLocalOnly!==true)return null;
 const reports=[];
 for(const r of sheet){
  if(r.factoryId!==factory||!same(r.planId,plan.id)||r.submissionStatus!=='SUBMITTED'||r.materialized!==true||!r.clientRowId||!r.submittedAt||!r.processCode||!r.processName||r.productTypeId!==batch.productTypeId||same(r.batchId,batch.id))continue;
  const targets=batches.filter(b=>b.factoryId===factory&&same(b.id,r.batchId)&&b.batchNumber===r.batchNumber&&same(b.productionPlanId,plan.id)&&b.productTypeId===r.productTypeId);
  if(targets.length!==1||sheet.filter(x=>x.clientRowId===r.clientRowId).length!==1)continue;
  reports.push({reportId:r.clientRowId,batchId:String(targets[0].id),batchNumber:targets[0].batchNumber,processId:r.processCode,processName:r.processName,submittedAt:r.submittedAt,requestFingerprint:r.requestFingerprint||null});
 }
 if(!reports.length)return null;
 const evidence={factoryId:factory,planId:plan.id,containerId:String(batch.id),containerNumber:batch.batchNumber,reports};
 return {...evidence,kind:'PLAN_START_RECORD',proofHash:crypto.createHash('sha256').update(JSON.stringify(evidence)).digest('hex'),basis:'计划.batchId/批次号 → 同计划已提交且已物化报工 → 报工.batchId/批次号 → 实际产出批次',boundary:'本条是计划开工记录；已提交报工生成了独立批次。只证明上下游关系，不把其产出、材料费或人工费重复归入本条。'};
}
module.exports={lineage};
