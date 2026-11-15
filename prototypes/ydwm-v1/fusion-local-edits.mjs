import crypto from 'node:crypto'
import { fusionWorkspace } from './fusion-data.mjs'
export function handleFusionLocalEdit({parent,role,session,method,target,body,send,res}){
  const p=target.split('?')[0],schedule=p.match(/^\/api\/mobile\/FOOD_0000_001\/production-plans\/([^/]+)\/schedule-meta$/),copy=p.match(/^\/api\/mobile\/FOOD_0000_001\/production-plans\/([^/]+)\/copy$/),tenant=p.match(/^\/api\/platform\/factories\/([^/]+)$/)
  if(!(role==='factory'&&(schedule&&method==='PUT'||copy&&method==='POST')||role==='platform'&&tenant&&method==='PUT'))return false
  let input;try{input=JSON.parse(body?.toString()||'{}')}catch{send(res,400,{success:false,message:'无效表单'});return true}
  const w=fusionWorkspace(parent,role,session),base=role==='factory'?'/api/mobile/FOOD_0000_001/production-plans':'/api/platform/factories',model=w.models.get(base),id=(schedule||copy||tenant)[1],old=model&&w.row(model,id),fail=(message,status=422)=>{send(res,status,{success:false,message,prototypeLocalOnly:true});return true}
  if(!old)return fail('未找到当前工作区的记录',404)
  if(old.isLocked&&schedule)return fail('计划已锁定，请先解锁再修改',409)
  const at=new Date().toISOString();let next={...old,updatedAt:at,prototypeLocalOnly:true},action='update',recordId=id
  if(schedule){const allowed=['expectedCompletionDate','estimatedWorkers','assignedSupervisorId','notes'];if(Object.keys(input).some(k=>!allowed.includes(k)))return fail('已开工计划仅能修改排产信息');if(/COMPLETED|CANCELLED|CLOSED/.test(old.status))return fail('已结束计划不能编辑排产信息',409);for(const k of allowed)if(Object.hasOwn(input,k))next[k]=input[k];if(next.estimatedWorkers!=null&&(!Number.isInteger(next.estimatedWorkers)||next.estimatedWorkers<1||next.estimatedWorkers>500))return fail('预计工人数应为1至500的整数')}
  if(tenant){if(Object.keys(input).some(k=>k!=='aiWeeklyQuota'))return fail('此入口仅更新所选工厂的AI周配额');if(!Number.isInteger(input.aiWeeklyQuota)||input.aiWeeklyQuota<0)return fail('AI配额必须为非负整数');next.aiWeeklyQuota=input.aiWeeklyQuota}
  if(copy){action='create';recordId=crypto.randomUUID();next={...next,id:recordId,planNumber:'PLAN-LOCAL-'+Date.now()+'-'+recordId.slice(0,8).toUpperCase(),status:'PENDING',statusDisplayName:'待执行',actualQuantity:null,allocatedQuantity:0,isFullyMatched:false,isLocked:false,createdAt:at,createdBy:1703};for(const key of Object.keys(next))if(/^actual.*Cost|^approval|^locked|^(?:startTime|endTime|currentProbability|probabilityUpdatedAt)$/.test(key))next[key]=null;for(const field of ['batchId','batchNumber','settlementId','preparationTransferId','prototypeSettlement','actualQuantity','startTime','endTime'])next[field]=null;next.requiresApproval=false;next.isForceInserted=false}
  w.set(model,recordId,next);w.log({method,path:p,type:action,id:recordId,before:copy?null:old,after:next,at});send(res,200,{success:true,data:next,message:'已保存到本机工作区',prototypeLocalOnly:true});return true
}
