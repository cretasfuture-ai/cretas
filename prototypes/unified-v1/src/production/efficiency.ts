// 标准工时与效率、达标率（BL-borrow 15；A 出计算，B 在成本页只读呈现）。纯函数，不调模型。
// 口径：
//   标准工时(秒) = 良品数量 × 标准秒/单位（标准来自 Cretas 补充；没有标准 → 「无标准」，不给效率与达标）
//   有效工时(秒) = 黑湖任务 sumEffectiveTime；缺失或 0 → 「无有效工时」，不做除法
//   实际效率     = 标准工时 ÷ 有效工时
//   达标         = 实际效率 ≥ 1（100%）
//   工序达标率   = 达标任务数 ÷ 可计算任务数（只到工序和班组，不做个人排名）
// 单位必须一致：标准的单位与良品单位不同 → 「单位不一致」，不换算。
export interface Standard{processCode:string;secondsPerUnit:number;unit:string;source:'Cretas 补充';at:string;by:string;reason:string;id:string;revoked?:unknown}
export interface TaskInput{taskId:string;processCode:string;processName:string;good:number|null;unit:string|null;effectiveSeconds:number|null}
export type EffStatus='达标'|'未达标'|'无标准'|'无有效工时'|'单位不一致'|'未报良品'
export interface TaskEff{taskId:string;processCode:string;processName:string;standardSeconds:number|null;effectiveSeconds:number|null;efficiency:number|null;status:EffStatus}
export interface ProcessEff{processCode:string;processName:string;tasks:number;computable:number;reached:number;rate:number|null;standard:Standard|null;totalStandardSeconds:number|null;totalEffectiveSeconds:number|null;efficiency:number|null;note:string}
export const RULE='标准工时=良品×标准秒/单位；实际效率=标准工时÷有效工时；达标=效率≥100%；达标率=达标任务÷可计算任务；没有标准或有效工时为 0 时不计算'
export function taskEfficiency(t:TaskInput,std:Standard|null):TaskEff{
  const base={taskId:t.taskId,processCode:t.processCode,processName:t.processName,effectiveSeconds:t.effectiveSeconds}
  if(!std)return {...base,standardSeconds:null,efficiency:null,status:'无标准'}
  if(t.good===null)return {...base,standardSeconds:null,efficiency:null,status:'未报良品'}
  if(t.unit&&std.unit&&t.unit!==std.unit)return {...base,standardSeconds:null,efficiency:null,status:'单位不一致'}
  const ss=t.good*std.secondsPerUnit
  if(!t.effectiveSeconds||t.effectiveSeconds<=0)return {...base,standardSeconds:ss,efficiency:null,status:'无有效工时'}
  const e=ss/t.effectiveSeconds
  return {...base,standardSeconds:ss,efficiency:e,status:e>=1?'达标':'未达标'}
}
export function processEfficiency(tasks:TaskInput[],standards:Standard[]):ProcessEff[]{
  const by=new Map<string,TaskInput[]>()
  for(const t of tasks){const k=t.processCode||'（无工序编码）';if(!by.has(k))by.set(k,[]);by.get(k)!.push(t)}
  return [...by.entries()].map(([code,list])=>{
    const std=standards.find(s=>s.processCode===code&&!s.revoked)||null
    const rows=list.map(t=>taskEfficiency(t,std)),ok=rows.filter(r=>r.efficiency!==null)
    const reached=ok.filter(r=>r.status==='达标').length
    const ts=ok.length?ok.reduce((a,r)=>a+(r.standardSeconds||0),0):null,te=ok.length?ok.reduce((a,r)=>a+(r.effectiveSeconds||0),0):null
    const note=!std?'无标准（请补充标准秒/单位）':!ok.length?'没有可计算的任务（无有效工时、未报良品或单位不一致）':''
    return {processCode:code,processName:list[0].processName,tasks:list.length,computable:ok.length,reached,rate:ok.length?reached/ok.length:null,standard:std,totalStandardSeconds:ts,totalEffectiveSeconds:te,efficiency:ts!==null&&te?ts/te:null,note}
  })
}
