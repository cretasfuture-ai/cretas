// E 线给统一 AiFloat 的对象扩展（DECISIONS A3：AI 只有一个悬浮窗实现，由 D 维护）。
// 本文件不画任何界面，只提供 AiFloat 需要的：对象标签、事实读取、解读调用、创建类草稿与确认写入。
// D 在 AiFloat 里按 kind（'inspection' | 'task'）调用；E 页面通过 setAiObject 把当前对象交给 AiFloat。
import type {AiFact} from '../frame/ai-protocol'
import {E,post,write,refText} from './e-store'

export type EAiKind='inspection'|'task'
export interface EAiObject{kind:EAiKind;id:string;label:string}
export interface EAiItem{text:string;who?:string;source:'system'|'model'}
export interface EAiAnswer{state:'answered'|'unavailable'|'rejected'|'stale';conclusion:string;items:EAiItem[];rejected:{text:string;why:string}[];note:string;at:string;objectRev:number;ms?:number}
export interface EDraft{type?:string;fields?:Record<string,string|number|null>;title:string;description:string;role:string;due:string;priority:string;related:{type:string;id:string;code:string;name:string}[];fieldSource:Record<string,string>;unverified:string[];note:string}

// 当前对象：页面选中检验单/任务时调用；AiFloat 读取 E.current（或监听 'e-ai-object' 事件）
export function setAiObject(o:{kind:EAiKind;id:string}|undefined){E.current=o;window.dispatchEvent(new CustomEvent('e-ai-object',{detail:o?describe(o.kind,o.id):undefined}))}
export function describe(kind:EAiKind,id:string):EAiObject|undefined{
  const s=E.state;if(!s)return undefined
  if(kind==='inspection'){const x=s.inspections.find(i=>i.id===id);return x?{kind,id,label:'检验单 '+x.no+' · '+s.meta.stages[x.stage].label+' · '+refText(x.objectRef)}:undefined}
  const t=s.tasks.find(i=>i.id===id);return t?{kind,id,label:'任务 '+t.no+' · '+t.title}:undefined
}
// 事实（依据区）：全部来自本机保存区，判定、偏离、超期由代码算
export function facts(kind:EAiKind,id:string):AiFact[]{
  const s=E.state;if(!s)return []
  if(kind==='inspection'){
    const x=s.inspections.find(i=>i.id===id);if(!x)return []
    const st=s.standards.find(v=>v.id===x.standardId),ver=st?.versions.find(v=>v.v===x.standardVersion)
    return [{label:'阶段',value:s.meta.stages[x.stage].label},{label:'对象',value:refText(x.objectRef)},{label:'标准',value:ver?ver.name+' v'+ver.v:'标准缺失'},{label:'判定（代码）',value:s.meta.verdict[x.verdict],status:x.verdict},{label:'不合格项',value:x.failItems.join('、')||'无'},{label:'缺项',value:x.missingItems.join('、')||'无'},{label:'处置',value:x.disposal||'未提交'}]
  }
  const t=s.tasks.find(i=>i.id===id);if(!t)return []
  const today=s.meta.today,over=!['DONE','CANCELLED'].includes(t.status)&&t.due<today?Math.round((Date.parse(today)-Date.parse(t.due))/864e5):0
  return [{label:'状态',value:s.meta.taskStatus[t.status]},{label:'负责',value:t.assignee.role+(t.assignee.name?' · '+t.assignee.name:'')},{label:'截止',value:t.due},{label:'超期',value:over?over+' 天':'未超期'},{label:'评论',value:t.comments.length+' 条'},{label:'关联单据',value:t.related.map(r=>r.code).join('、')||'无'}]
}
const rev=(kind:EAiKind,id:string)=>kind==='inspection'?E.state?.inspections.find(i=>i.id===id)?.rev??0:E.state?.tasks.find(i=>i.id===id)?.rev??0
interface InspRes{model:string;modelNote?:string;conclusion:string;actions:{code:string;text:string;who:string}[];objectRev:number;at:string;ms?:number}
interface SumRes{model:string;modelNote?:string;ruleSummary:string;sentences:{text:string;ok:boolean;why?:string}[];objectRev:number;at:string;ms?:number}
// 解读：rulesOnly=true 只取规则结果（AiFloat 接入前页面内显示的「核对方向」）；false 时走本机模型并校验
export async function explain(kind:EAiKind,id:string,o:{question?:string;rulesOnly?:boolean}={}):Promise<EAiAnswer>{
  if(kind==='inspection'){
    const r=await post<InspRes>('ai/inspection',{id,question:o.question||'',rulesOnly:!!o.rulesOnly})
    const src=r.model==='ok'?'model':'system'
    return {state:r.model==='ok'||r.model==='rules'||r.model==='skipped'?'answered':r.model==='rejected'?'rejected':'unavailable',conclusion:r.conclusion,items:r.actions.map(a=>({text:a.text,who:a.who,source:src})),rejected:[],note:r.modelNote||'',at:r.at,objectRev:r.objectRev,ms:r.ms}
  }
  const r=await post<SumRes>('ai/task-summary',{id,rulesOnly:!!o.rulesOnly})
  return {state:r.model==='ok'||r.model==='rules'||r.model==='skipped'?'answered':'unavailable',conclusion:r.ruleSummary,items:r.sentences.filter(x=>x.ok).map(x=>({text:x.text,source:'model'})),rejected:r.sentences.filter(x=>!x.ok).map(x=>({text:x.text,why:x.why||''})),note:r.modelNote||'',at:r.at,objectRev:r.objectRev,ms:r.ms}
}
export const isStale=(kind:EAiKind,id:string,a:EAiAnswer)=>a.objectRev!==rev(kind,id)

// 创建类：整理成任务草稿（预览后由人确认），rulesOnly 时只用系统规则
interface DraftRes{model:string;modelNote?:string;draft:{type?:string;fields?:Record<string,string|number|null>;title:string;description:string;role:string;due:string;priority:string;relatedIds:string[];fieldSource:Record<string,string>};candidates:{type:string;id:string;code:string;name:string}[];unverified:string[]}
export async function draftTask(text:string,o:{inspectionId?:string;rulesOnly?:boolean}={}):Promise<EDraft>{
  const r=await post<DraftRes>('tasks/draft',{text,inspectionId:o.inspectionId,rulesOnly:!!o.rulesOnly})
  return {type:r.draft.type,fields:r.draft.fields,title:r.draft.title,description:r.draft.description,role:r.draft.role,due:r.draft.due,priority:r.draft.priority,related:r.draft.relatedIds.map(c=>r.candidates.find(x=>x.code===c)).filter((x):x is DraftRes['candidates'][number]=>!!x),fieldSource:r.draft.fieldSource,unverified:r.unverified,note:r.modelNote||(r.model==='rules'?'系统规则草稿':'')}
}
// 确认创建 → 写入 → 独立回读，返回回读到的任务
export async function confirmTask(d:EDraft,o:{inspectionId?:string;draftBy:string}){
  const insp=o.inspectionId?E.state?.inspections.find(i=>i.id===o.inspectionId):undefined
  const r=await write('tasks/save',{title:d.title,description:d.description,assignee:{kind:'role',role:d.role,name:''},due:d.due,priority:d.priority,related:d.related,origin:{kind:insp?'inspection':'draft',ref:insp?.no||'',draftBy:o.draftBy}})
  const t=E.state!.tasks.find(x=>x.id===r.id);if(!t)throw Error('已提交，回读不一致：重新读取后找不到任务')
  return t
}
// 把进展概括发表为评论（预览确认后）
export async function confirmComment(taskId:string,text:string){const r=await write('tasks/comment',{id:taskId,text,fromSummary:true});const t=E.state!.tasks.find(x=>x.id===r.id)!;return t.comments[t.comments.length-1]}
