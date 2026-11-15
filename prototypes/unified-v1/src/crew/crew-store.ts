// F 线 · 数字班组共享状态：六膳门黑湖快照（复用 frame store）、库存快照、当前登录人权限、班组自己的保存区、E 线协同任务状态。
// 写操作一律「生成预览 → 进等我确认 → 有权限的人确认 → 调现有接口 → 回读核实 → 记工作记录」。
import {reactive} from 'vue'
import {S,load} from '../frame/store'
import {acceptanceSession} from '../production/session'
import {CREW,DEMO_ROLES,canWrite,type CrewId} from './crew-config'
import type {Inv,Src} from './crew-facts'

export interface PreviewRow{label:string;value:string}
export interface Preview{rows:PreviewRow[];impact:string[];basis:string[];notDone:string[]}
export interface InboxAction{type:'e-task'|'demo'|'crew-onboard';payload?:Record<string,unknown>;reason?:string}
export interface InboxItem{id:string;employee:CrewId;kind:string;title:string;module:string;sourceKey:string;context:string;preview:Preview;action:InboxAction;status:'pending'|'executing'|'handed'|'done'|'failed'|'unknown'|'cancelled';createdAt:string;createdBy:string;confirmedAt?:string;confirmedBy?:string;result?:{outcome:string;label:string;ref:{type:string;id:string;no:string;label:string}|null;note:string;at:string};log:{at:string;by:string;action:string;reason?:string}[]}
export interface WorkRecord{id:string;inboxId:string;employee:CrewId;kind:string;title:string;at:string;confirmedBy:string;outcome:'handed'|'done'|'failed'|'unknown'|'demo';label:string;quota:number;ref:{type:string;id:string;no:string;label:string}|null;note:string}
export interface CrewState{version:number;config:{enabled:Record<CrewId,boolean>;log:{at:string;by:string;action:string;reason?:string}[]};inbox:InboxItem[];records:WorkRecord[];reports:Record<string,{status:string;at:string;by:string;reason:string;conclusion?:string;inboxId:string}>;onboard:{id:string;at:string;by:string;productName:string;state:string;steps:unknown[]}[];writable:boolean}

const ROLE_KEY='unified-f-crew-role-v1'
function savedRole(){try{const v=localStorage.getItem(ROLE_KEY)||'';return DEMO_ROLES[v]?v:'self'}catch{return 'self'}}
export const C=reactive({
  ws:'lsm' as 'lsm'|'ydwm',session:'',role:savedRole(),
  perms:null as Record<string,string>|null,permsError:'',
  inv:null as Inv|null,state:null as CrewState|null,
  e:null as {tasks:{id:string;no:string;title:string;status:string}[];standards:unknown[];inspections:unknown[];version:number}|null,
  user:null as {name:string;username:string}|null,
  loading:false,error:'',notice:'',busyId:''
})
// persist=false：只在本页生效、不记到浏览器（一线手机端的演示岗位，离开或刷新就回到原身份）
export function setRole(r:string,persist=true){C.role=DEMO_ROLES[r]?r:'self';if(!persist)return;try{localStorage.setItem(ROLE_KEY,C.role)}catch{/* 浏览器不让存时只在本次生效 */}}
export const roleLabel=()=>DEMO_ROLES[C.role].label
// F-14：留痕与计费记登录人（统一入口账号 users/current）；用演示身份时在后面注明，演示身份不是真实账号
export const whoAmI=()=>{const u=C.user?C.user.name+'（'+C.user.username+'）':'未读到登录人';return C.role==='self'?u:u+' · 演示身份 '+DEMO_ROLES[C.role].label.replace('（演示）','')}
export const perms=()=>C.role==='self'?C.perms:DEMO_ROLES[C.role].perms
// 工作区隔离：只有六膳门工作区用黑湖快照；味美工作区一律没有演示数据（不能借六膳门的数）
export const src=()=>C.ws==='lsm'?S.source as unknown as Src|undefined:undefined
const qs=(o:Record<string,string>={})=>{const p=new URLSearchParams({ws:C.ws,...o});if(C.session)p.set('acceptanceSession',C.session);return p.toString()}
export const fUrl=(sub:string)=>'/__unified/f/api/'+sub+'?'+qs()
export const eUrl=(sub:string)=>'/__unified/e/api/'+sub+'?'+qs()

async function j<T>(url:string,body?:unknown):Promise<T>{
  const r=await fetch(url,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json','x-prototype-action':'explicit'},body:JSON.stringify(body)})
  let b:{success?:boolean;message?:string;data?:T}={};try{b=await r.json()}catch{throw Error('读取失败（'+r.status+'）')}
  if(!r.ok||!b.success){const e=Error(b.message||'读取失败') as Error&{status?:number};e.status=r.status;throw e}
  return b.data as T
}
export function syncContext(){const h=location.hash,i=h.indexOf('?'),q=new URLSearchParams(i<0?'':h.slice(i+1));C.ws=q.get('ws')==='ydwm'?'ydwm':'lsm';C.session=acceptanceSession()}
export async function loadState(){C.state=await j<CrewState>(fUrl('state'))}
// R29：把「已交办」按协同任务的实际状态回读更新（任务完成才算已办成）；只在可写会话里做
async function syncRecords(){const st=C.state;if(!st||!st.writable)return;if(!st.records.some(r=>r.ref?.type==='task'&&['handed','done','failed'].includes(r.outcome)))return
  try{const r=await j<{changed?:number;version:number}>(fUrl('records/sync'),{ws:C.ws,expectedVersion:st.version,by:'回读协同任务'});if(r.changed!==0)await loadState()}catch{/* 回读失败时保留原状态，下次再试 */}}
export async function loadE(){try{C.e=await j(eUrl('state'))}catch{C.e=null}}
// 同一时刻只跑一次读取（班组首页的卡片和模块根会同时要求读取，并发会让旧结果覆盖回读后的新状态）
let inflight:Promise<void>|null=null
export function loadAll():Promise<void>{if(inflight)return inflight;inflight=loadAllOnce().finally(()=>{inflight=null});return inflight}
async function loadAllOnce(){
  syncContext();C.loading=true;C.error=''
  try{
    const tasks:Promise<unknown>[]=[loadState().then(syncRecords),loadE()]
    if(!S.source&&C.ws==='lsm')tasks.push(load())
    if(C.ws==='lsm'&&!C.inv)tasks.push(j<Inv>('/__lsm/api/inventory-source').then(v=>{C.inv=v}).catch(()=>{C.inv=null}))
    // 当前登录人权限：统一入口的账号权限接口（与 D「动作权限对照」同一来源）；味美接口用完整地址，避免被框架改写成六膳门转发
    if(!C.user)tasks.push(j<{name:string;username:string}>(location.origin+'/api/mobile/FOOD_0000_001/users/current').then(v=>{C.user={name:v.name,username:v.username}}).catch(()=>{C.user=null}))
    tasks.push(j<Record<string,string>>(location.origin+'/api/mobile/FOOD_0000_001/permissions/current').then(v=>{C.perms=v;C.permsError=''}).catch(e=>{C.perms=null;C.permsError=e instanceof Error?e.message:'读取失败'}))
    await Promise.all(tasks)
  }catch(e){C.error=e instanceof Error?e.message:'读取失败'}finally{C.loading=false}
}
export const enabled=()=>CREW.filter(c=>C.state?C.state.config.enabled[c.id]!==false:true)
const bjDay=(iso:string)=>new Date(Date.parse(iso)+8*3600e3).toISOString().slice(0,10)
// R28：六膳门所有「今天 / 近 N 天 / 周五」按快照日（checkedAt 的北京日期）；味美按本机日期。
// 工作记录是 Cretas 里真实发生的事，按本机日期（realToday）。
export const realToday=()=>bjDay(new Date().toISOString())
export const today=()=>C.ws==='lsm'&&S.source?bjDay(String(S.source.checkedAt)):realToday()
export const snapshotDay=()=>C.ws==='lsm'&&S.source?bjDay(String(S.source.checkedAt)):''
export function countsOf(id:CrewId){
  const st=C.state;if(!st)return {done:0,pending:0,failed:0,handed:0}
  const t=realToday(),rec=st.records.filter(r=>r.employee===id&&bjDay(r.at)===t)
  return {done:rec.filter(r=>r.outcome==='done').length,pending:st.inbox.filter(x=>x.employee===id&&x.status==='pending').length,failed:rec.filter(r=>r.outcome==='failed'||r.outcome==='unknown').length,handed:st.records.filter(r=>r.employee===id&&r.outcome==='handed').length}
}
// 「等我确认」按当前登录人权限过滤：只显示他对该模块有办理权限（rw）的事项
export const mayConfirm=(x:InboxItem)=>canWrite(x.module,perms())

async function post<T>(sub:string,body:Record<string,unknown>):Promise<T>{
  if(!C.state)await loadState()
  const run=()=>j<T>(fUrl(sub),{...body,ws:C.ws,expectedVersion:C.state!.version,by:whoAmI()})
  try{return await run()}catch(e){if((e as {status?:number}).status===409&&/已被更新/.test((e as Error).message)){await loadState();return await run()}throw e}
}
export async function createInbox(x:{employee:CrewId;kind:string;title:string;module:string;sourceKey?:string;context?:string;preview:Preview;action:InboxAction}):Promise<InboxItem>{
  const r=await post<{item:InboxItem}>('inbox/create',x);await loadState();return r.item
}
export async function cancelInbox(id:string,reason:string){await post('inbox/cancel',{id,reason});await loadState()}
export async function saveConfig(en:Record<string,boolean>,reason:string){await post('config/save',{enabled:en,reason});await loadState()}
export async function reportState(key:string,status:string,reason='',inboxId='',conclusion=''){await post('reports/state',{key,status,reason,inboxId,conclusion});await loadState()}

// 确认并办理。失败、超时都写明「没有执行任何写操作」或「结果不明」，不假装办成。
export async function confirmAndRun(x:InboxItem):Promise<InboxItem>{
  if(!mayConfirm(x))throw Error('你没有这件事所在模块的办理权限')
  C.busyId=x.id
  try{
    await post('inbox/confirm',{id:x.id,perm:'rw'});await loadState()
    const cur=()=>C.state!.inbox.find(y=>y.id===x.id)!
    if(x.action.type==='crew-onboard')return cur()
    if(x.action.type==='demo'){await post('inbox/result',{id:x.id,outcome:'demo'});await loadState();return cur()}
    // e-task：调 E 线现有接口建协同任务，再由 F 后端回读 E 保存区核实
    let taskId='',sent=false
    try{
      const es=await j<{version:number}>(eUrl('state'))
      sent=true
      // 凭证绑定（F-9）：任务来源写「F:<事项 id>」，F 后端回读时据此核对是不是本事项建的任务
      const pl=x.action.payload as Record<string,unknown>,origin={...(pl.origin as Record<string,unknown>||{}),kind:'draft',ref:'F:'+x.id}
      const r=await j<{id:string}>(eUrl('tasks/save'),{...pl,origin,ws:C.ws,expectedVersion:es.version,actor:whoAmI().slice(0,40)})
      taskId=r.id
    }catch(e){
      const msg=e instanceof Error?e.message:'失败'
      await post('inbox/result',{id:x.id,outcome:sent&&(e as {status?:number}).status===undefined?'unknown':'failed',message:'协同任务没建成：'+msg+'。没有执行任何写操作。'});await loadState();await loadE();return cur()
    }
    await post('inbox/result',{id:x.id,outcome:'done',taskId});await loadState();await loadE();return cur()
  }finally{C.busyId=''}
}
export const STATUS_TEXT:Record<string,{label:string;tone:string}>={
  executing:{label:'办理中',tone:'info'},handed:{label:'已交办',tone:'info'},pending:{label:'等你确认',tone:'warning'},done:{label:'已办成',tone:'success'},failed:{label:'没办成',tone:'danger'},unknown:{label:'结果不明',tone:'neutral'},cancelled:{label:'不办了',tone:'neutral'}}
export function itemStatus(x:InboxItem){if(x.status==='done'&&x.result?.outcome==='demo')return {label:'已确认 · 原型演示，未执行',tone:'neutral'};return STATUS_TEXT[x.status]||{label:x.status,tone:'neutral'}}
export function nextFriday(from=today()){const d=new Date(from+'T00:00:00Z');const add=(5-d.getUTCDay()+7)%7||7;return new Date(d.getTime()+add*864e5).toISOString().slice(0,10)}
