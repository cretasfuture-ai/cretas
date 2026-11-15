// AiFloat 可扩展对象类型（DECISIONS A3：全站只有 AiFloat 一个悬浮窗实现，由 D 维护）。
// project / product 由六膳门来源直接解析（store.setCurrent）；其余对象由各线在这里注册适配器：
//   registerAiObject(kind, adapter) —— 页面加载时注册一次
//   setAiObject({kind,id}, '来自哪里')  —— 页面选中对象时调用，悬浮窗围绕它解读；传 undefined 清空
// 适配器只提供数据，不画界面；数量、状态、零值与缺失由各线代码算好放进 facts，模型只能出现在 explain 里。
import {S} from './store'
import type {AiFact,AiState} from './ai-protocol'

export interface AiItem{text:string;who?:string;source:'system'|'model'}
export interface AiExplain{state:AiState|'answered'|'unavailable'|'rejected'|'stale';conclusion:string;items:AiItem[];rejected:{text:string;why:string}[];note?:string;at:string;objectRev?:number|string;ms?:number}
export interface AiObjectAdapter{
  /** 入口文案的对象名，如「这张检验单」；按钮显示为「解读」+ noun */
  noun:string
  /** 对象标签（显示在当前对象条）；找不到返回 undefined */
  describe(id:string):string|undefined
  /** 依据（系统计算）；value 未填用 null，0 用 0，单位放 unit */
  facts(id:string):AiFact[]
  /** 可选：解读。没有时悬浮窗只显示依据，并写明「本对象只提供系统计算」 */
  explain?(id:string,o:{question:string}):Promise<AiExplain>
  /** 可选：结论是否因对象变化而过期 */
  isStale?(id:string,a:AiExplain):boolean
  /** 可选：默认提问 */
  placeholder?:string
}
const registry=new Map<string,AiObjectAdapter>()
export function registerAiObject(kind:string,adapter:AiObjectAdapter){registry.set(kind,adapter)}
export function aiAdapter(kind:string){return registry.get(kind)}
export function setAiObject(o:{kind:string;id:string}|undefined,from='页面'){
  if(!o){S.current=undefined;return}
  const a=registry.get(o.kind);const label=a?.describe(o.id);if(!a||!label)return
  S.current={scope:{kind:o.kind,id:o.id},label,from}
}

// ---- 味美原生页面的对象（A 线 ydwm-plan 等）：适配器挂在外层页面 window.CretasAiAdapters，事件也在外层派发 ----
// cretas-ai-adapter {kind}：适配器就绪；cretas-ai-object {kind,id,label}|undefined：当前对象变化；cretas-ai-open {kind,id,label}：用户点了解读入口
let topBound=false
export function bindTopAdapters(){
  if(topBound)return;topBound=true
  let top:Window&{CretasAiAdapters?:Record<string,AiObjectAdapter>}
  try{top=window.top as typeof top;void top.location.href}catch{return}
  const reg=()=>{const all=top.CretasAiAdapters||{};for(const k of Object.keys(all))if(!registry.has(k))registerAiObject(k,all[k])}
  reg()
  // 事件可能派发在外层 window 或 document 上（A 线派在 document、不冒泡），两处都听；同一事件只处理一次
  const seen=new WeakSet<Event>(),on=(type:string,fn:(ev:Event)=>void)=>{for(const t of [top,top.document] as EventTarget[])t.addEventListener(type,ev=>{if(seen.has(ev))return;seen.add(ev);fn(ev)})}
  on('cretas-ai-adapter',()=>reg())
  const take=(ev:Event,open:boolean)=>{reg();const d=(ev as CustomEvent<{kind:string;id:string;label?:string}|undefined>).detail;if(!d){if(!open)S.current=undefined;return}const a=registry.get(d.kind);if(!a)return;S.current={scope:{kind:d.kind,id:d.id},label:d.label||a.describe(d.id)||d.id,from:'味美页面'};if(open)S.aiOpen=true}
  on('cretas-ai-object',ev=>take(ev,false))
  on('cretas-ai-open',ev=>take(ev,true))
}

// ---- E 线（inspection / task）：E 的适配器 src/collab/e-ai-adapter.ts 已按约定提供，这里注册并转接它的「当前对象」事件 ----
let eLoaded:Promise<void>|undefined
export function loadEAdapters(){
  if(eLoaded)return eLoaded
  eLoaded=import('../collab/e-ai-adapter').then(m=>{
    for(const kind of ['inspection','task'] as const){
      registerAiObject(kind,{noun:kind==='inspection'?'这张检验单':'这个任务',placeholder:kind==='inspection'?'例如：这张检验单为什么判不合格？先找谁处理？':'例如：这个任务进展到哪了？卡在哪里？',
        describe:id=>m.describe(kind,id)?.label,facts:id=>m.facts(kind,id),
        explain:(id,o)=>m.explain(kind,id,{question:o.question}) as Promise<AiExplain>,
        isStale:(id,a)=>m.isStale(kind,id,a as Parameters<typeof m.isStale>[2])})
    }
  }).catch(()=>{/* E 模块未就绪时不注册 */})
  return eLoaded
}
window.addEventListener('e-ai-object',ev=>{const d=(ev as CustomEvent<{kind:string;id:string;label:string}|undefined>).detail;loadEAdapters().then(()=>{if(!d){if(S.current&&['inspection','task'].includes(String(S.current.scope.kind)))S.current=undefined;return}setAiObject({kind:d.kind,id:d.id},d.kind==='inspection'?'质量检验':'协同任务')})})
