import crypto from 'node:crypto';
import {fusionWorkspace} from './fusion-data.mjs';
import {createLocalProductionPlan} from './fusion-production.mjs';
import {getFusionProcessConfig} from './fusion-process-sheet.mjs';
import {getFusionActiveWorkflow} from './fusion-workflow.mjs';
const BASE='/api/mobile/FOOD_0000_001';
export function localWip(w){
 const batches=w.rows(w.models.get(BASE+'/processing/batches')),seen=new Set(),out=[];
 for(const [key,rows]of Object.entries(w.state.configs))if(/\/production-plans\/[^/]+\/process-sheet\/rows$/.test(key)&&Array.isArray(rows))for(const r of rows){
  if(r.submissionStatus!=='SUBMITTED'||r.finished!==false||!r.batchNumber)continue;
  const b=batches.find(b=>b.batchNumber===r.batchNumber);if(!b||seen.has(String(b.id)))continue;seen.add(String(b.id));
  out.push({id:b.id,intermediateBatchNo:b.batchNumber,productName:w.rows(w.models.get(BASE+'/product-types')).find(p=>p.id===b.productTypeId)?.name||null,productTypeName:w.rows(w.models.get(BASE+'/product-types')).find(p=>p.id===b.productTypeId)?.name||null,productionDate:r.processDate||null,productTypeId:b.productTypeId,producedQuantity:Number(b.actualQuantity),consumedQuantity:Number(b.actualQuantity)-Number(b.availableQuantity),availableQuantity:Number(b.availableQuantity),unit:b.unit,status:Number(b.availableQuantity)>0?'AVAILABLE':'CONSUMED',accumulatedCost:b.totalCost??null,unitCost:b.totalCost==null?null:Number(b.totalCost)/Number(b.actualQuantity),batchId:b.id,createdAt:b.createdAt,productionPlanId:b.productionPlanId,processOrder:b.processOrder,prototypeLocalOnly:true});
 }return out;
}
export function handleRemainingProduction({root,session,method,target,body,send,res}){
 const u=new URL(target,'http://local'),p=u.pathname,w=fusionWorkspace(root,'factory',session),rows=s=>w.rows(w.models.get(BASE+s)),ok=data=>{send(res,200,{success:true,data,prototypeLocalOnly:true});return true},fail=(message,status=422)=>{send(res,status,{success:false,message,prototypeLocalOnly:true});return true};let m;
 if(method==='GET'&&(m=p.match(/\/production-plans\/status\/(PLANNED|PENDING|IN_PROGRESS|PAUSED)$/)))return ok(rows('/production-plans').filter(r=>m[1]==='PLANNED'?r.status==='PENDING':r.status===m[1]));
 if(method==='GET'&&p===BASE+'/wip/available')return ok(localWip(w).filter(r=>r.availableQuantity>0));
 if(method==='GET'&&p===BASE+'/semi-finished/inventory'){const planId=u.searchParams.get('planId'),plan=planId&&rows('/production-plans').find(r=>r.id===planId);if(planId&&!plan)return fail('计划不存在。',404);let data=localWip(w).filter(r=>r.availableQuantity>0&&(!u.searchParams.get('productTypeId')||r.productTypeId===u.searchParams.get('productTypeId')||plan&&u.searchParams.get('productTypeId')===plan.productTypeId&&getFusionProcessConfig(w,plan)?.processes.some(step=>step.inputs.some(input=>input.skuId===r.productTypeId)))&&(!u.searchParams.has('maxProcessOrder')||Number(r.processOrder)<Number(u.searchParams.get('maxProcessOrder'))));if(plan)data=data.filter(r=>r.productionPlanId===plan.id||String(r.id)===String(plan.secondarySourceWipId));return ok(data)}
 if(method==='POST'&&p===BASE+'/processing/batches'){const product=rows('/product-types').find(r=>r.id===body?.productTypeId),q=Number(body?.plannedQuantity);if(!product||!String(body?.batchNumber||'').trim()||!Number.isFinite(q)||q<=0||!body.unit)return fail('请填写本工厂产品、批次号、有效数量和单位。');if(rows('/processing/batches').some(r=>r.batchNumber===body.batchNumber))return fail('批次号已存在。',409);const record={...structuredClone(body),id:'PROTO-'+crypto.randomUUID(),factoryId:'FOOD_0000_001',status:'PLANNED',productName:product.name,actualQuantity:null,createdAt:new Date().toISOString(),updatedAt:new Date().toISOString(),prototypeLocalOnly:true};const model=w.ensureModel(BASE+'/processing/batches');w.set(model,record.id,record);w.log({method,path:p,type:'create',base:model.base,id:record.id,before:null,after:record,at:record.createdAt});return ok(record)}
 if(method==='POST'&&p===BASE+'/processing/secondary-plan'){
  const source=localWip(w).find(r=>String(r.id)===String(body?.wipId));if(!source)return fail('请选择本工厂正式报工且仍有余量的半成品。',404);
  const q=Number(body.quantity);if(!Number.isFinite(q)||q<=0||q>source.availableQuantity)return fail('计划加工数量须大于零且不超过半成品可用量。',409);
  const active=getFusionActiveWorkflow(root,session,body.productTypeId);if(!active)return fail('目标成品尚无已发布工艺，请先在工序画布配置并启用。');
  const plan=createLocalProductionPlan(w,{productTypeId:body.productTypeId,selectedWorkflowId:active.definition.id,plannedDate:body.plannedDate||new Date().toISOString().slice(0,10),plannedQuantity:q,sourceType:'SAFETY_STOCK'},new Date().toISOString());
  const config=getFusionProcessConfig(w,plan),entry=config?.processes.find(r=>r.inputs.some(i=>i.materialKind!=='RAW_MATERIAL'&&String(i.skuId)===String(source.productTypeId)));
  if(!entry)return fail('目标工艺没有接收该半成品的投入端口，请选择匹配工艺的成品。');
  const origin=rows('/production-plans').find(r=>r.id===source.productionPlanId);Object.assign(plan,{planNumber:'SEC-LOCAL-'+Date.now()+'-'+plan.id.slice(0,6),planSourceType:'SECONDARY',secondarySourceWipId:source.id,secondarySourcePlanId:source.productionPlanId,secondaryInputQuantity:q,secondaryInputUnit:source.unit,sourceOrderId:origin?.sourceOrderId??null,sourceOrderIds:origin?.sourceOrderIds||[]});
  config.planNumber=plan.planNumber;config.processes=config.processes.filter(r=>r.processOrder>=entry.processOrder);config.source='pinned-workflow-secondary-wip';
  w.set(w.models.get(BASE+'/production-plans'),plan.id,plan);w.state.configs[BASE+'/production-plans/'+plan.id+'/process-sheet/workflow-config']=config;w.log({method,path:p,type:'create',base:BASE+'/production-plans',id:plan.id,before:null,after:plan,at:new Date().toISOString()});return ok(plan);
 }
 if(method==='GET'&&(m=p.match(/\/(?:processing|production)\/batches\/([^/]+)\/(timeline|yield|wip|work-process-tasks)$/))){
  const b=rows('/processing/batches').find(r=>String(r.id)===m[1]);if(!b?.prototypeLocalOnly)return false;
  const entries=(w.state.configs[BASE+'/production-plans/'+b.productionPlanId+'/process-sheet/rows']||[]).filter(r=>r.submissionStatus==='SUBMITTED'),leaf=m[2];
  if(leaf==='timeline')return ok([{id:'created-'+b.id,type:'CREATED',title:'本机批次创建',description:b.notes||b.batchNumber,timestamp:b.createdAt,createdAt:b.createdAt},...entries.filter(r=>r.batchNumber===b.batchNumber).map(r=>({id:r.clientRowId,type:'PROCESS_REPORT',title:r.processName,description:'实际产出 '+r.outputQuantity+' '+r.outputUnit,timestamp:r.submittedAt,createdAt:r.submittedAt}))]);
  if(leaf==='wip')return ok(localWip(w).filter(r=>String(r.batchId)===String(b.id)||r.productionPlanId===b.productionPlanId));
  if(leaf==='work-process-tasks')return ok(rows('/work-process-tasks').filter(r=>String(r.productionBatchId)===String(b.id)));
  return ok({batchId:b.id,steps:entries.map(r=>({processOrder:r.processOrder,processName:r.processName,inputQuantity:r.inputQuantity,outputQuantity:r.outputQuantity,inputUnit:r.inputUnit,outputUnit:r.outputUnit,yieldRate:r.inputQuantity>0&&r.inputUnit===r.outputUnit?r.outputQuantity/r.inputQuantity*100:null})),cumulativeYieldRate:null,lastStepOutput:entries.at(-1)?.outputQuantity??null,lastStepOutputUnit:entries.at(-1)?.outputUnit??null});
 }return false;
}
