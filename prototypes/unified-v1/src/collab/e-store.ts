// E 线（质量检验分阶段 / 协同任务）前端共享状态与接口。
// 写入只走 /__unified/e/api/*；六膳门对象来自黑湖快照（服务端读取），味美对象由浏览器只读调用味美接口。
import {reactive} from 'vue'
import {sourceTagHTML,fmtQty,fmtNumber,changeLogHTML} from '../frame/ai-protocol'
import type {DataSource} from '../frame/ai-protocol'

export type Ws='lsm'|'ydwm'
export type StageKey='incoming'|'production'|'warehousing'|'shipping'|'other'
export interface Ref{type:string;id:string;code:string;name:string;source?:string;verified?:boolean}
export interface StdItem{id:string;name:string;method:string;tool?:string;kind:'numeric'|'judge'|'option';required:boolean;lower?:number|null;upper?:number|null;unit?:string;target?:number|null;options?:string[];passOptions?:string[]}
export interface StdVersion{v:number;name:string;scope:'all'|'object';objectRef:Ref|null;items:StdItem[];firstCheck?:{processes:string[]}|null;at:string;by:string;reason:string}
export interface Standard{id:string;no:string;stage:StageKey;active:boolean;source:string;createdAt:string;createdBy:string;versions:StdVersion[];log:{at:string;by:string;action:string;reason?:string;before?:string;after?:string}[]}
export interface BatchRef{batchNo:string;kind:'purchase'|'production';productCode:string;productName:string;productionDate:string;expiryDate:string;qty:number|null;unit:string;warehouse:string;lakeInboundNo:string;productType?:string}
export interface BatchHold{batchNo:string;status:'HOLD'|'BLOCKED';label:string;inspectionNo:string;inspectionId:string;stage:string;reason:string;since:string}
export interface Inspection{id:string;no:string;stage:StageKey;kind?:'first'|'routine';processCode?:string;batchRef?:BatchRef|null;batchClose?:{result:string;reason:string;at:string;by:string}|null;status:'DRAFT'|'SUBMITTED'|'VOID';source:string;standardId:string;standardVersion:number;objectRef:Ref;related:Ref[];results:{itemId:string;value:number|string|null}[];sampleQty:number|null;sampleUnit:string;inspector:string;inspectedAt:string;note:string;verdict:'PASS'|'FAIL'|'INCOMPLETE';failItems:string[];missingItems:string[];disposal?:string;disposalReason?:string;voidReason?:string;createdAt:string;createdBy:string;updatedAt:string;rev:number;log:{at:string;by:string;action:string;detail?:string}[]}
export interface Comment{id:string;at:string;by:string;text:string;fromSummary?:boolean}
export interface Task{id:string;no:string;type?:string;fields?:Record<string,string|number|null>;title:string;description:string;assignee:{kind:string;role:string;name:string};due:string;priority:string;related:Ref[];status:'TODO'|'DOING'|'DONE'|'CANCELLED';origin:{kind:string;ref:string;draftBy?:string};createdAt:string;createdBy:string;updatedAt:string;doneAt?:string;rev:number;comments:Comment[];log:{at:string;by:string;action:string;reason?:string;changes:{field:string;before:string;after:string}[]}[]}
export interface Meta{stages:Record<StageKey,{label:string;objectTypes:string[];objectLabel:string;hint:string}>;roles:string[];priority:string[];taskStatus:Record<string,string>;next:Record<string,string[]>;disposals:Record<string,string[]>;verdict:Record<string,string>;today:string}
export interface Defect{id:string;code:string;name:string;category:string;note:string;processes:string[];active:boolean;source:string;createdAt:string;createdBy:string;updatedAt:string;log:{at:string;by:string;action:string;reason?:string;before?:string;after?:string}[]}
export interface TaskTypeField{key:string;label:string;kind:'text'|'number';max?:number;required?:boolean}
export interface TaskType{label:string;color:string;role:string;fields:TaskTypeField[]}
export interface Reminder{id:string;rule:string;key:string;title:string;role:string;ref:{type:string;id:string;code:string;process?:string};status:'open'|'resolved'|'dismissed';at:string;closedAt?:string;closedBy?:string;reason?:string}
export interface RuleLog{id:string;at:string;rule:string;key:string;result:string;detail:string;by:string}
export interface RuleSummary{id:string;name:string;trigger:string;action:string;role:string;enabled:boolean;lastAt:string|null;count3d:number;open:number}
export interface EState{version:number;standards:Standard[];inspections:Inspection[];tasks:Task[];defects:Defect[];meta:Meta;timings:Record<string,{respondedAt:string|null;responseMin:number|null;handleMin:number|null}>;taskTypes:Record<string,TaskType>;rulesSummary:RuleSummary[];reminders:Reminder[];ruleLog:RuleLog[]}

export interface LakeInspection{id:string;lakeId:string;no:string;stage:StageKey;stageReason:string;source:string;object:{code:string;name:string;spec:string;unit:string};sourceDoc:{no:string;type:string;status:string};spec:string;process:string;submittedQty:number|null;inspectedQty:number|null;qualifiedQty:number|null;unqualifiedQty:number|null;orderStatus:number;hasResult:boolean;result:unknown;items:unknown[];planFinishedTime:string;createdAt:string;updatedAt:string}
export interface Lake{available:boolean;reason?:string;revision?:string;checkedAt?:string;rows:LakeInspection[];counts:Record<string,number|null>}
export const STAGE_ORDER:StageKey[]=['incoming','production','warehousing','shipping','other']
export const TYPE_LABEL:Record<string,string>={material:'原材料',product:'成品',project:'工单',inspection:'检验单',task:'任务',other:'其他'}

function readParam(k:string){
  const own=new URLSearchParams(location.search).get(k)||new URLSearchParams((location.hash.split('?')[1])||'').get(k)
  if(own)return own
  try{return new URLSearchParams(window.top!.location.search).get(k)}catch{return null}
}
// 工作区只看地址：D 的味美 frame 路由表一律带 ws=ydwm；没有就是六膳门工作区。不读浏览器存储（会残留上一次切换）
function initialWs():Ws{const own=new URLSearchParams(location.search).get('ws')||new URLSearchParams((location.hash.split('?')[1])||'').get('ws');return own==='ydwm'?'ydwm':'lsm'}

// 味美工作区经菜单进入时，外壳可能没把验收会话带进 frame 地址（BATCH3-AUDIT 第 4 条）。
// 只在味美工作区、且地址里没有会话时，从味美自己的 sessionStorage「ydwm-acceptance-workspace」补上；格式不对就不用，避免误写默认区
function initialSession(){const own=readParam('acceptanceSession');if(own)return {s:own,from:''};if(initialWs()!=='ydwm')return {s:'',from:''};try{const v=window.top!.sessionStorage.getItem('ydwm-acceptance-workspace')||'';if(/^test-[a-z0-9-]{8,64}$/.test(v))return {s:v,from:'味美当前验收会话'}}catch{/* 跨域时不补 */}return {s:'',from:''}}
const SESSION0=initialSession()
export const E=reactive({
  ws:initialWs() as Ws,
  session:SESSION0.s,
  sessionFrom:SESSION0.from,
  state:undefined as EState|undefined,
  loading:false,error:'',notice:'',
  objects:{material:[],product:[],project:[]} as Record<string,Ref[]>,
  objectsSource:'',objectsNote:'',
  actor:'',
  // 悬浮窗当前对象：选中的检验单或任务
  current:undefined as undefined|{kind:'inspection'|'task';id:string},
  // 从不合格检验单「生成任务」或提醒带到协同任务新建表单的草稿种子
  taskPrefill:undefined as undefined|{text:string;inspectionId?:string;type?:string;fields?:Record<string,unknown>;related?:Ref[];reminderId?:string},
  // 从首检列表「首检」带到检验单新建表单
  // 黑湖检验单等只读数据（DECISIONS 第十节）：只在六膳门工作区读取
  lake:undefined as undefined|Lake,
  inspPrefill:undefined as undefined|{stage:StageKey;standardId:string;objCode:string;processCode:string},
  drawerOpen:false
})
// 同一页内只改 hash（不重新加载）时，工作区和会话来源要重新判断（BATCH4-AUDIT 退回 E1）。有变化返回 true，由调用方重新读取数据
export function syncContext():boolean{const ws=initialWs(),s=initialSession();if(ws===E.ws&&s.s===E.session&&s.from===E.sessionFrom)return false;E.ws=ws;E.session=s.s;E.sessionFrom=s.from;E.state=undefined;E.lake=undefined;E.current=undefined;E.objects={material:[],product:[],project:[]};E.objectsSource='';E.objectsNote='';E.error='';return true}
const ACTOR_KEY='cretas-e-actor-v1'
try{E.actor=localStorage.getItem(ACTOR_KEY)||''}catch{/* 未保存 */}
export function setActor(v:string){E.actor=v;try{localStorage.setItem(ACTOR_KEY,v)}catch{/* 浏览器存储不可用不影响业务 */}}

export function q(path:string){const p=new URLSearchParams({ws:E.ws});if(E.session)p.set('acceptanceSession',E.session);return '/__unified/e/api/'+path+(path.includes('?')?'&':'?')+p.toString()}
export async function get<T>(path:string):Promise<T>{
  const r=await fetch(q(path),{cache:'no-store'});let b:{success?:boolean;message?:string;data?:T}={}
  try{b=await r.json()}catch{throw Error('读取失败（'+r.status+'）')}
  if(!r.ok||!b.success)throw Error(b.message||'读取失败（'+r.status+'）')
  return b.data as T
}
export async function post<T>(path:string,body:Record<string,unknown>):Promise<T>{
  const r=await fetch(q(path),{method:'POST',headers:{'Content-Type':'application/json','x-prototype-action':'explicit'},body:JSON.stringify({ws:E.ws,actor:E.actor,candidates:candidates(),...body})})
  let b:{success?:boolean;message?:string;data?:T}={}
  try{b=await r.json()}catch{throw Error('提交失败（'+r.status+'）')}
  if(!r.ok||!b.success)throw Error(b.message||'提交失败（'+r.status+'）')
  return b.data as T
}
// 写入：带版本 → 成功后独立 GET 回读
export async function write(path:string,body:Record<string,unknown>):Promise<{id:string;version:number}>{
  if(!E.state)throw Error('尚未读取')
  const r=await post<{id:string;version:number}>(path,{expectedVersion:E.state.version,...body})
  await reload()
  if(E.state.version!==r.version)throw Error('已提交，回读不一致（版本 '+E.state.version+' / '+r.version+'）')
  return r
}
export async function reload(){
  E.loading=true;E.error=''
  try{E.state=await get<EState>('state')}catch(e){E.error=e instanceof Error?e.message:'读取失败';E.state=undefined}finally{E.loading=false}
  try{E.lake=await get<Lake>('lake')}catch{E.lake={available:false,reason:'黑湖数据读取失败',rows:[],counts:{}}}
}
function candidates(){return E.ws==='ydwm'?[...E.objects.material,...E.objects.product,...E.objects.project]:undefined}

// 味美对象：浏览器只读调用味美接口（绝对地址，绕开六膳门 /api 改写）；返回 0 条时如实为空。
const YD='/api/mobile/FOOD_0000_001/'
async function ydList(path:string):Promise<Record<string,unknown>[]>{
  const r=await fetch(location.origin+YD+path,{cache:'no-store',credentials:'same-origin'});const b=await r.json().catch(()=>({}))
  if(!r.ok||(b.success===false&&b.code!==200))throw Error((b.message||'味美接口返回 '+r.status)+'（'+path.split('?')[0]+'）')
  const d=b.data;return Array.isArray(d)?d:Array.isArray(d?.content)?d.content:Array.isArray(d?.records)?d.records:[]
}
const s=(v:unknown)=>v==null?'':String(v)
export async function loadObjects(){
  E.objectsNote=''
  if(E.ws==='lsm'){
    try{const d=await get<{source:string;revision:string;objects:Record<string,Ref[]>}>('objects');E.objects=d.objects;E.objectsSource='黑湖快照 '+d.revision.slice(0,12)}
    catch(e){E.objectsNote='对象列表读取失败：'+(e instanceof Error?e.message:'')}
    return
  }
  const out:Record<string,Ref[]>={material:[],product:[],project:[]},miss:string[]=[]
  const jobs:[string,string,(x:Record<string,unknown>)=>Ref][]=[
    ['material','raw-material-types?page=1&size=200',x=>({type:'material',id:s(x.id),code:s(x.materialTypeCode??x.code??x.id),name:s(x.name??x.materialName)})],
    ['product','product-types?page=1&size=200&productCategory=FINISHED_PRODUCT',x=>({type:'product',id:s(x.id),code:s(x.code??x.productCode??x.id),name:s(x.name??x.productName)})],
    ['project','production-plans?page=1&size=200',x=>({type:'project',id:s(x.id),code:s(x.planNumber??x.planNo??x.id),name:s(x.productName??x.productTypeName)})]
  ]
  await Promise.all(jobs.map(async([k,p,map])=>{try{out[k]=(await ydList(p)).map(map).filter(r=>r.code)}catch(e){miss.push(e instanceof Error?e.message:p)}}))
  E.objects=out;E.objectsSource='味美接口（统一入口独立会话）'
  if(miss.length)E.objectsNote='部分味美对象未读取：'+miss.join('；')
}

export const fmt2=(v:unknown)=>v==null||v===''?'未填写':Number(v).toFixed(2)
// 来源标记：统一用 D 线共享件（design-system.js · sourceTagHTML）；共享脚本未加载时退回纯文字
const SRC_TEXT:Record<DataSource,string>={blacklake:'黑湖',cretas:'Cretas 补充',ydwm:'味美',missing:'未返回',system:'系统计算'}
export function tag(s:DataSource,o?:{at?:string;by?:string}){try{return sourceTagHTML(s,o)}catch{return '<span class="e-src">'+SRC_TEXT[s]+'</span>'}}
export function refTag(r?:Ref|null){if(!r)return '';if(r.type==='inspection'||r.type==='task')return tag('cretas');if(!r.verified)return '<span class="e-tag unv" title="不在本工作区来源中，按手填保存">未核实</span>';return tag(r.source==='黑湖'?'blacklake':'ydwm')}
export function qty(v:unknown,unit?:string){try{return fmtQty(v,unit)}catch{return v==null||v===''?'—':Number(v).toFixed(2)+' '+(unit||'')}}
// 修改记录：统一用 D 共享件 changeLogHTML（B6）。本线四种 log 形状在这里换成 ChangeEntry
type AnyLog={at:string;by?:string;action?:string;reason?:string;detail?:string;before?:string;after?:string;changes?:{field:string;before:string;after:string}[]}
export function logHTML(log:AnyLog[],field='内容'){
  const entries=log.slice().reverse().map(l=>({at:l.at,by:l.by,action:l.action,reason:l.reason&&l.reason!==l.action?l.reason:l.detail,source:'cretas' as const,changes:l.changes?.length?l.changes.map(c=>({field:c.field,label:c.field,before:c.before||'—',after:c.after||'—'})):l.before&&l.after!==undefined?[{field,label:field,before:l.before,after:l.after||'—'}]:[]}))
  try{return changeLogHTML(entries,{empty:'还没有修改记录'})}catch{return '<ul>'+entries.map(e=>'<li>'+fmtTime(e.at)+' · '+(e.by||'')+' · '+(e.action||'')+(e.reason?'（'+e.reason+'）':'')+'</li>').join('')+'</ul>'}
}
export function num2(v:unknown){try{return fmtNumber(v)}catch{return v==null?'—':Number(v).toFixed(2)}}
export const fmtTime=(v?:string)=>!v?'未填写':new Date(v).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}).replace(/\//g,'-')
export const refText=(r?:Ref|null)=>!r?'未填写':(r.code+(r.name?' · '+r.name:''))
export function range(it:StdItem){if(it.kind==='option')return '合格：'+(it.passOptions||[]).join('、');if(it.kind==='judge')return '判定项';const u=it.unit||'';if(it.lower!=null&&it.upper!=null)return fmt2(it.lower)+' ~ '+fmt2(it.upper)+' '+u;if(it.lower!=null)return '≥ '+fmt2(it.lower)+' '+u;if(it.upper!=null)return '≤ '+fmt2(it.upper)+' '+u;return '判定项'}
// 前端即时判定（与服务端 qc-rules.cjs 同口径；保存后以服务端回读为准）
export function itemResult(it:StdItem,value:unknown):'PASS'|'FAIL'|'MISSING'{
  if(value==null||String(value).trim()==='')return 'MISSING'
  if(it.kind==='judge')return value==='合格'?'PASS':'FAIL'
  if(it.kind==='option')return (it.passOptions||[]).includes(String(value))?'PASS':'FAIL'
  const v=Number(value);if(!Number.isFinite(v))return 'MISSING'
  if(it.lower!=null&&v<it.lower)return 'FAIL';if(it.upper!=null&&v>it.upper)return 'FAIL';return 'PASS'
}
// 数值输入：最多两位小数（与服务端同规则）
export const bad2=(v:unknown)=>v!=null&&String(v).trim()!==''&&!/^-?\d+(\.\d{1,2})?$/.test(String(v).trim())
// 有数值项超过两位小数 → 「录入有误」，不给合格/不合格结论（BATCH2-AUDIT 第 13 条补充）
export function overall(items:StdItem[],vals:Record<string,unknown>){if(items.some(it=>it.kind==='numeric'&&bad2(vals[it.id])))return 'INVALID';let fail=false,miss=false;for(const it of items){const r=itemResult(it,vals[it.id]);if(r==='FAIL')fail=true;else if(r==='MISSING'&&it.required)miss=true}return fail?'FAIL':miss?'INCOMPLETE':'PASS'}
// 分钟 → 「x 小时 y 分」，缺失显示「未响应 / 未完成」由调用方给
export function dur(m:number|null){if(m==null)return '';if(m<60)return m+' 分钟';const h=Math.floor(m/60),r=m%60;return h<48?h+' 小时'+(r?' '+r+' 分':''):Math.round(h/24)+' 天'}
export function overdueDays(t:Task,today:string){if(['DONE','CANCELLED'].includes(t.status)||!t.due||t.due>=today)return 0;return Math.round((Date.parse(today)-Date.parse(t.due))/864e5)}
export function stdVersion(st:Standard|undefined,v?:number){if(!st)return undefined;return v?st.versions.find(x=>x.v===v):st.versions[st.versions.length-1]}
export function routeQuery(){return new URLSearchParams(location.hash.split('?')[1]||'')}
export function setQuery(patch:Record<string,string>){
  const [p,qs]=location.hash.replace(/^#/,'').split('?'),u=new URLSearchParams(qs||'')
  for(const [k,v] of Object.entries(patch)){if(v)u.set(k,v);else u.delete(k)}
  const h='#'+p+(u.toString()?'?'+u.toString():'');if(location.hash!==h)history.replaceState(null,'',location.pathname+location.search+h)
  // 告诉外壳当前路由与页面参数（与 frame/store.ts 同一消息 {source:'lsm-frame',type:'route'}），由外壳回写顶层地址，刷新后不丢（FINAL-AUDIT 13/16）
  const page=new URLSearchParams(u);page.delete('ws');page.delete('acceptanceSession')
  try{window.parent.postMessage({source:'lsm-frame',type:'route',route:p+(page.toString()?'?'+page.toString():''),ws:E.ws},location.origin)}catch{/* 独立打开时无外壳 */}
}
// 在本工作区对象列表（六膳门黑湖快照 / 味美接口）里找到的引用，预览时就标已核实（FINAL-AUDIT 17）；服务端保存时仍会再核实
export function verifiedRef(o:Ref):Ref{return {...o,verified:true,source:E.ws==='lsm'?'黑湖':'味美'}}
export function openTarget(r:Ref){
  if(r.type==='inspection'){location.hash='#/quality/inspections?open='+encodeURIComponent(r.id)+(E.ws==='ydwm'?'&ws=ydwm':'');return}
  if(r.type==='task'){location.hash='#/collab/tasks?open='+encodeURIComponent(r.id)+(E.ws==='ydwm'?'&ws=ydwm':'');return}
  if(E.ws==='lsm'&&r.type==='project'&&r.verified){location.hash='#/production/plans?project='+encodeURIComponent(r.id);return}
  if(E.ws==='lsm'&&(r.type==='product'||r.type==='material')&&r.verified){location.hash='#/system/products?product='+encodeURIComponent(r.code);return}
  E.notice='单号 '+r.code+' 在本工作区没有可打开的原页面'+(r.verified?'':'（未核实引用）')+'。'
}
