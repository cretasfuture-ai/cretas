// 工单进度 vs 时间进度（BL-borrow B5）。代码判定，不让模型算。
// 工单进度 = 已完成良品 ÷ 计划量（计划量缺失或为 0 → 不出百分比）；
// 时间进度 = (今天 − 计划开始) ÷ (计划结束 − 计划开始)，按天计（味美计划只有日期）；计划时间缺失 → 「无计划时间，不能判断工期」。
// 工期状态：已完成 / 已取消 / 已超期（过了计划结束仍未完成）/ 落后（时间进度比工单进度多 10 个百分点以上）/ 正常 / 未开始 / 不能判断。
export type Schedule='done'|'cancelled'|'overdue'|'behind'|'normal'|'not-started'|'unknown'
export interface Progress{work:number|null;workReason?:string;time:number|null;timeReason?:string;schedule:Schedule;label:string}
const DAY=86400000
const day=(v:unknown):number|null=>{if(v==null||v==='')return null;const s=String(v);const t=/^\d{4}-\d{2}-\d{2}$/.test(s)?Date.parse(s+'T00:00:00+08:00'):Date.parse(s);if(!Number.isFinite(t))return null;const d=new Date(t+8*3600000);return Date.UTC(d.getUTCFullYear(),d.getUTCMonth(),d.getUTCDate())}
const num=(v:unknown):number|null=>v==null||v===''?null:Number.isFinite(Number(v))?Number(v):null
export const LABEL:Record<Schedule,string>={done:'已完成',cancelled:'已取消',overdue:'已超期',behind:'落后',normal:'正常','not-started':'未开始',unknown:'不能判断'}
export function progress(o:{planned:unknown;done:unknown;start:unknown;end:unknown;finished?:boolean;cancelled?:boolean;now?:number}):Progress{
  const planned=num(o.planned),done=num(o.done)
  const work=planned===null?null:planned<=0?null:done===null?null:done/planned
  const workReason=planned===null?'计划量未填写':planned<=0?'计划量为 0，不能算比例':done===null?'未录入完成量':undefined
  const s=day(o.start),e=day(o.end),today=day(new Date(o.now??Date.now()).toISOString())!
  let time:number|null=null,timeReason:string|undefined
  if(s===null||e===null)timeReason='无计划时间，不能判断工期'
  else if(e<s)timeReason='计划结束早于开始，不能判断工期'
  else time=Math.max(0,(today-s+DAY)/(e-s+DAY))   // 含当天：开始当天算已用 1 天
  let schedule:Schedule
  if(o.cancelled)schedule='cancelled'
  else if(o.finished)schedule='done'
  else if(time===null)schedule='unknown'
  else if(s!==null&&today<s)schedule='not-started'
  else if(e!==null&&today>e)schedule='overdue'
  else if(work!==null&&Math.min(time,1)-work>=0.1)schedule='behind'
  else if(work===null)schedule='unknown'
  else schedule='normal'
  return {work,workReason,time,timeReason,schedule,label:LABEL[schedule]}
}
import {fmtPct} from '../frame/ai-protocol'
// 百分比统一用共享格式（T5）；不能算时显示原因
export const pct=(v:number|null,reason?:string)=>v===null?(reason||'—'):fmtPct(v*100,{digits:2})
