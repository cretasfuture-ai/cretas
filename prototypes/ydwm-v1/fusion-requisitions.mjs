import {createRequire} from 'node:module';
import {randomUUID} from 'node:crypto';
import {fusionWorkspace} from './fusion-data.mjs';
const require=createRequire(import.meta.url),{envelope,error}=require('./tools/engine/workspace.cjs'),BASE='/api/mobile/FOOD_0000_001';
export function handleRequisitions({root,session,method,target,body,send,res}){
 const p=new URL(target,'http://local').pathname,m=p.match(/^\/api\/mobile\/FOOD_0000_001\/purchase-requisitions(?:\/([^/]+)(?:\/(submit|approve|reject|convert-to-po))?)?$/);
 if(!m||['GET','HEAD'].includes(method))return false;const w=fusionWorkspace(root,'factory',session),model=w.ensureModel(BASE+'/purchase-requisitions'),data=typeof body==='object'&&!Buffer.isBuffer(body)?body:JSON.parse(String(body||'{}')),at=new Date().toISOString(),old=m[1]?w.row(model,m[1]):null;
 const fail=message=>{send(res,422,error(message));return true},ok=value=>{send(res,200,envelope(value));return true};
 if(!m[1]&&method==='POST'){
  if(!data.reason?.trim()||!Array.isArray(data.requestedItems)||!data.requestedItems.length)return fail('请填写申请原因和请购物料。');
  const materials=w.rows(w.models.get(BASE+'/raw-material-types')),suppliers=w.rows(w.models.get(BASE+'/suppliers'));
  for(const i of data.requestedItems)if(!materials.some(r=>r.id===i.materialTypeId)||!Number.isFinite(Number(i.quantity))||Number(i.quantity)<=0||!i.unit||i.suggestedSupplierId&&!suppliers.some(r=>r.id===i.suggestedSupplierId))return fail('请核对本工厂的物料、供应商、数量和单位。');
  const record={...data,id:'PROTO-'+randomUUID(),factoryId:'FOOD_0000_001',requisitionNumber:'PROTO-PR-'+Date.now(),requesterId:w.baseline.identity.userId,status:'DRAFT',version:0,createdAt:at,updatedAt:at,prototypeLocalOnly:true};w.set(model,record.id,record);w.log({method,path:p,type:'create',base:model.base,id:record.id,before:null,after:record,at});return ok(record);
 }
 if(!old)return fail('请购单不存在。');
 if(method==='DELETE'){if(old.status!=='DRAFT'||old.requesterId!==w.baseline.identity.userId)return fail('只能删除本人创建的草稿。');w.set(model,old.id,old,true);w.log({method,path:p,type:'delete',base:model.base,id:old.id,before:old,after:null,at});return ok(null)}
 const action=m[2];if(method!=='POST'||!action)return false;
 if(action==='convert-to-po'&&old.convertedPoId){const po=w.row(w.models.get(BASE+'/purchase/orders'),old.convertedPoId);return po?ok({id:po.id,orderNumber:po.orderNumber,status:po.status}):fail('关联采购单不存在，请检查保存记录。');}
 const allowed={submit:['DRAFT','REJECTED'],approve:['PENDING_APPROVAL'],reject:['PENDING_APPROVAL'],'convert-to-po':['APPROVED']};if(!allowed[action].includes(old.status))return fail('当前请购单状态不能执行此操作。');
 if(action==='submit'&&old.requesterId!==w.baseline.identity.userId)return fail('只能提交本人创建的请购单。');
 if(action==='reject'&&!String(data.reason||data.rejectionReason||'').trim())return fail('驳回必须填写原因。');
 let next={...old,version:old.version+1,updatedAt:at},effects=[];
 if(action==='submit')next.status='PENDING_APPROVAL';
 if(action==='approve')Object.assign(next,{status:'APPROVED',approverId:w.baseline.identity.userId,approvedAt:at});
 if(action==='reject')Object.assign(next,{status:'REJECTED',rejectionReason:data.reason||data.rejectionReason});
 if(action==='convert-to-po'){
  const supplier=w.rows(w.models.get(BASE+'/suppliers')).find(r=>r.id===data.supplierId);if(!supplier)return fail('请选择本工厂的供应商。');
  const poModel=w.ensureModel(BASE+'/purchase/orders'),id='PROTO-'+randomUUID(),items=old.requestedItems.map(i=>({id:'PROTO-'+randomUUID(),materialTypeId:i.materialTypeId,materialName:i.materialName,quantity:Number(i.quantity),unit:i.unit,receivedQuantity:0,unitPrice:null,taxRate:null,remark:i.remark})),po={id,factoryId:'FOOD_0000_001',orderNumber:'PROTO-PO-'+Date.now(),requisitionId:old.id,supplierId:supplier.id,supplierName:supplier.name,status:'DRAFT',purchaseType:'DIRECT',items,expectedDeliveryDate:old.expectedDate,remark:old.remark,version:0,createdAt:at,updatedAt:at,prototypeLocalOnly:true};
  // 未配置采购价与税率保持空值，转单后必须在采购草稿核对。
  w.set(poModel,id,po);effects=[{resource:poModel.base,id,type:'create',after:po}];Object.assign(next,{status:'CONVERTED_TO_PO',convertedPoId:id,convertedAt:at});
 }
 w.set(model,old.id,next);w.log({method,path:p,type:'update',base:model.base,id:old.id,before:old,after:next,effects,at});return ok(action==='convert-to-po'?{id:next.convertedPoId,orderNumber:effects[0].after.orderNumber,status:'DRAFT'}:next);
}
