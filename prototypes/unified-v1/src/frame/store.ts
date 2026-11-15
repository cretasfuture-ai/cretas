// 六膳门工作区共享状态：来源快照、保存区、当前对象（AI 悬浮窗的唯一上下文）、记录详情。
// 只读黑湖来源快照；保存只写六膳门原型自身的工作区。不读写味美任何数据。
import {reactive} from 'vue'
import type {Row,Source,Workspace,Scope,MenuEntry} from './types'
import {parseHash} from './routes'

export interface Current{scope:Scope;label:string;from:string}
export const S=reactive({
  source:undefined as Source|undefined,
  workspace:undefined as Workspace|undefined,
  busy:false,error:'',notice:'',
  path:'/dashboard',query:new URLSearchParams() as URLSearchParams,
  menu:[] as MenuEntry[],
  current:undefined as Current|undefined,
  detail:{open:false,kind:'',row:undefined as Row|undefined,chainProjectId:''},
  aiOpen:false,
  // 本地工艺编辑器打开时，它自带的「检查配置」就是该页唯一的 AI 入口，悬浮窗入口隐藏
  editorOpen:false
})
export const UI_KEY='unified-lsm-ui-v1'

export async function api<T>(url:string,data?:unknown):Promise<T>{
  const r=await fetch(url,{method:data===undefined?'GET':'POST',headers:data===undefined?{}:{'Content-Type':'application/json','x-prototype-action':'explicit'},body:data===undefined?undefined:JSON.stringify(data)})
  let b:{success?:boolean;message?:string;data?:T}={}
  try{b=await r.json()}catch{throw Error('读取失败（'+r.status+'）')}
  if(!r.ok||!b.success)throw Error(b.message||'读取失败')
  return b.data as T
}
export async function load(){
  S.busy=true;S.error=''
  try{const b=await api<{source:Source;workspace:Workspace}>('/api/workspace');S.source=b.source;S.workspace=b.workspace}
  catch(e){S.error=e instanceof Error?e.message:'读取失败'}
  finally{S.busy=false}
}
export const rs=(k:string):Row[]=>S.source?.resources[k]||[]
export const rowId=(r:Row)=>String(r.id??r.projectId??'')
export const costOf=(pid:unknown)=>S.source?.costs.find(c=>String(c.projectId)===String(pid))
export function productByCode(code:unknown){if(typeof code!=='string'||!code)return undefined;const a=rs('product').filter(p=>p.productCode===code);return a.length===1?a[0]:undefined}
export const text=(v:unknown)=>v==null||v===''?'未填写':String(v)
export const num=(v:unknown)=>v==null||v===''?'未填写':(typeof v==='number'||typeof v==='string'&&/^-?\d+(?:\.\d+)?$/.test(v))&&Number.isFinite(Number(v))?Number(v).toLocaleString('zh-CN',{maximumFractionDigits:4}):String(v)
export const fmtDate=(v:unknown)=>!v?'未填写':/T.*(?:Z|[+-]\d\d:\d\d)$/.test(String(v))?new Date(String(v)).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):String(v).replace('T',' ').slice(0,19)
export const stateLabel=(v:unknown,approval=false)=>approval?({'10':'已批准','0':'待审批','20':'未通过'} as Record<string,string>)[String(v)]||text(v):({'0':'新建','10':'执行中','20':'已结束','30':'已取消'} as Record<string,string>)[String(v)]||text(v)
export const scopeKey=(s:Scope)=>s.kind+':'+s.id

// 当前对象：页面选中产品/工单时设置；AI 悬浮窗只围绕它解读。
export function setCurrent(scope:Scope|undefined,from:string){
  if(!scope){S.current=undefined;return}
  const list=scope.kind==='project'?rs('project'):rs('product'),r=list.find(x=>rowId(x)===scope.id)
  if(!r)return
  const label=scope.kind==='project'?'工单 '+text(r.projectCode)+' · '+text(r.productName):'产品 '+text(r.productName)+' · '+text(r.productCode)
  S.current={scope,label,from}
}
export function scopeOfRow(kind:string,r:Row|undefined):Scope|undefined{
  if(!r)return undefined
  if(kind==='project')return {kind:'project',id:rowId(r)}
  if(kind==='product')return {kind:'product',id:rowId(r)}
  if((kind==='output'||kind==='task')&&r.projectId!=null)return {kind:'project',id:String(r.projectId)}
  if(kind==='bom'){const p=productByCode(r.lastProductCode);return p?{kind:'product',id:rowId(p)}:undefined}
  return undefined
}
export function inspect(kind:string,r:Row|undefined){
  if(!r)return
  if(kind==='project')S.detail.chainProjectId=rowId(r);else if(kind==='output'||kind==='task')S.detail.chainProjectId=String(r.projectId??'');else if(kind==='product')S.detail.chainProjectId=''
  S.detail.kind=kind;S.detail.row=r;S.detail.open=true
  const sc=scopeOfRow(kind,r);if(sc)setCurrent(sc,'记录详情')
}
export function linked(kind:string,key:unknown){const r=rs(kind).find(r=>String(r.id)===String(key));if(!r){S.notice='来源没有可核实的对应记录，未按名称猜配。';return}inspect(kind,r)}
export function openProduct(code:unknown){const p=productByCode(code);if(p)inspect('product',p);else S.notice='产品编码缺失或不唯一，未猜测对应产品。'}

// 导航：只改本工作区地址片段，并告诉外壳同步菜单高亮与面包屑。
export function navigate(path:string,query?:Record<string,string>){
  const q=query?new URLSearchParams(query).toString():''
  const h='#'+path+(q?'?'+q:'')
  if(location.hash!==h)location.hash=h;else syncRoute()
}
export function syncRoute(){
  const r=parseHash(location.hash)
  // 换页面时清空悬浮窗对象（审计 14）：新页面选中记录时再设；未选时显示「未选对象」
  if(S.path!==r.path)S.current=undefined
  S.path=r.path;S.query=r.query;S.detail.open=false
  const qs=r.query.toString();try{window.parent.postMessage({source:'lsm-frame',type:'route',route:r.path+(qs?'?'+qs:'')},location.origin)}catch{/* 独立打开时无外壳 */}
}
export function openMenu(e:MenuEntry&{page?:string}){navigate(e.path)}
