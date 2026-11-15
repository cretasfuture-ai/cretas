import {randomUUID} from 'node:crypto';
import {fusionWorkspace} from './fusion-data.mjs';
const BASE='/api/mobile/FOOD_0000_001/manufacturers';
export function handleManufacturers({root,session,method,target,body,send,res}){
 const u=new URL(target,'http://local'),p=u.pathname;if(p!==BASE&&!p.startsWith(BASE+'/'))return false;
 const w=fusionWorkspace(root,'factory',session),model=w.models.get(BASE);if(!model)return false;
 const rows=w.rows(model),id=decodeURIComponent(p.slice(BASE.length+1)),before=id&&w.row(model,id),ok=data=>{send(res,200,{success:true,data,prototypeLocalOnly:true});return true},fail=(message,status=422)=>{send(res,status,{success:false,message,prototypeLocalOnly:true});return true};
 if(method==='GET'&&p===BASE)return ok(rows.filter(r=>!['true'].includes(u.searchParams.get('activeOnly')||u.searchParams.get('active'))||r.isActive!==false));
 if(!['POST','PUT','DELETE'].includes(method))return false;
 if(method==='POST'&&p!==BASE||method!=='POST'&&!before)return fail('厂商登记不存在。',404);
 const at=new Date().toISOString();let next;
 if(method==='DELETE')next={...before,isActive:false,updatedAt:at,prototypeLocalOnly:true};
 else{const code=String(body?.code||'').trim(),name=String(body?.name||'').trim();if(!code||!name)return fail('请填写厂号编码和厂商名称。');if(rows.some(r=>r.id!==before?.id&&r.code===code))return fail('厂号编码已存在。',409);next={...before,id:before?.id||'PROTO-'+randomUUID(),factoryId:'FOOD_0000_001',code,name,originPlace:body.originPlace||null,remark:body.remark||null,isActive:body.isActive!==false,createdAt:before?.createdAt||at,updatedAt:at,prototypeLocalOnly:true};}
 w.set(model,next.id,next);w.log({method,path:p,type:before?'update':'create',base:BASE,id:next.id,before:before||null,after:next,at});return ok(next);
}
