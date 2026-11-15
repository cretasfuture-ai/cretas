import {randomUUID} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {fusionWorkspace} from './fusion-data.mjs';
const BASE='/api/mobile/FOOD_0000_001',COL=BASE+'/return-orders';
export function handleReturnOrders({root,session,method,target,body,send,res}){
 const u=new URL(target,'http://local'),p=u.pathname;if(p!==COL&&!p.startsWith(COL+'/'))return false;
 const w=fusionWorkspace(root,'factory',session),model=w.ensureModel(COL),rows=s=>w.rows(w.models.get(BASE+s)),parts=p.slice(COL.length).split('/').filter(Boolean),old=parts[0]&&w.row(model,parts[0]),ok=data=>{send(res,200,{success:true,data,prototypeLocalOnly:true});return true},fail=(message,status=422)=>{send(res,status,{success:false,message,prototypeLocalOnly:true});return true},at=new Date().toISOString();
 if(method==='GET'&&!parts.length){const all=w.rows(model).filter(r=>(!u.searchParams.get('returnType')||r.returnType===u.searchParams.get('returnType'))&&(!u.searchParams.get('status')||r.status===u.searchParams.get('status'))),page=Math.max(1,Number(u.searchParams.get('page')||1)),size=Math.max(1,Math.min(200,Number(u.searchParams.get('size')||10)));return ok({content:all.slice((page-1)*size,page*size),totalElements:all.length,totalPages:Math.ceil(all.length/size)})}
 if(method==='GET')return old?ok(old):fail('退货单不存在。',404);
 if(method!=='POST')return fail('原版退货单不提供此修改操作。',405);
 let next;
 if(!parts.length){
  const purchase=body?.returnType==='PURCHASE_RETURN';if(!purchase&&body?.returnType!=='SALES_RETURN')return fail('请选择有效退货类型。');
  const source=rows(purchase?'/purchase/orders':'/sales/orders').find(r=>r.id===body.sourceOrderId),party=source?.[purchase?'supplierId':'customerId'];if(!source||party!==body.counterpartyId)return fail('来源订单与本工厂的退货往来方不一致。',403);
  if(!/^\d{4}-\d{2}-\d{2}$/.test(body.returnDate||'')||!String(body.reason||'').trim()||!Array.isArray(body.items)||!body.items.length)return fail('请填写退货日期、原因和实际退货明细。');
  const itemKey=purchase?'materialTypeId':'productTypeId',seen=new Set(),items=[];
  for(const i of body.items){const sourceRows=(source.items||[]).filter(r=>r[itemKey]===i[itemKey]);if(sourceRows.length!==1||seen.has(i[itemKey]))return fail('退货产品/物料不存在、重复或来源明细不唯一。');seen.add(i[itemKey]);const line=sourceRows[0],quantity=Number(i.quantity),delivered=Number(line[purchase?'receivedQuantity':'deliveredQuantity']||0),requested=w.rows(model).filter(r=>r.sourceOrderId===source.id&&r.status!=='REJECTED').flatMap(r=>r.items||[]).filter(r=>r[itemKey]===i[itemKey]).reduce((sum,r)=>sum+Number(r.quantity),0);if(!Number.isFinite(quantity)||quantity<=0||quantity+requested>delivered+1e-8)return fail('退货合计不能超过该订单已收货/实发数量。',409);if(i.unitPrice!=null&&Number(i.unitPrice)!==Number(line.unitPrice))return fail('退货单价必须与来源订单一致。');items.push({id:'PROTO-'+randomUUID(),[itemKey]:line[itemKey],itemName:line.productName||line.materialName,quantity,unit:line.unit,unitPrice:line.unitPrice??null,lineAmount:line.unitPrice==null?null:Number((quantity*Number(line.unitPrice)).toFixed(4)),reason:i.reason||null});}
  const known=items.every(r=>r.lineAmount!=null);next={id:'PROTO-'+randomUUID(),factoryId:'FOOD_0000_001',returnNumber:'RT-LOCAL-'+Date.now(),returnType:body.returnType,status:'DRAFT',counterpartyId:party,counterpartyName:source.customerName||source.supplierName,sourceOrderId:source.id,sourceOrderNumber:source.orderNumber,returnDate:body.returnDate,reason:body.reason,withGoods:body.withGoods!==false,remark:body.remark||null,items,totalAmount:known?items.reduce((s,r)=>s+r.lineAmount,0):null,createdBy:w.baseline.identity.userId,createdAt:at,updatedAt:at,prototypeLocalOnly:true};
 }else{
  if(!old)return fail('退货单不存在。',404);const action=parts[1],identity=JSON.parse(fs.readFileSync(path.join(root,'private/identity.json'))),finance=['rw','w'].includes(identity.modulePermissions?.finance);
  if(action==='finance-approve'||action==='complete'||action==='approve'&&old.withGoods===false){if(!finance)return fail('财务模块已关闭；未执行退款记账或退货完成入库。',403);return fail('本机未接入真实退款账务，不能虚构财务审批或完成结果。');}
  const transitions={submit:['DRAFT','SUBMITTED'],approve:['SUBMITTED','APPROVED'],reject:['SUBMITTED','REJECTED']},rule=transitions[action];if(!rule||old.status!==rule[0])return fail('当前退货单状态不能执行此操作。',409);next={...old,status:rule[1],updatedAt:at,...(action==='approve'?{approvedBy:identity.userId,approvedAt:at}:{}),financialPostingStatus:'NOT_POSTED'};
 }
 w.set(model,next.id,next);w.log({method,path:p,type:old?'update':'create',base:COL,id:next.id,before:old||null,after:next,at});return ok(next);
}
