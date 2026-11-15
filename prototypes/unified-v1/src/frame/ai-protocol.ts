// 跨页 AI 协议与共享格式的类型化入口。唯一实现在味美设计系统 design-system.js（window.CretasUI），
// 统一入口的六膳门工作区通过 index.html 加载同一份脚本；A/B/C/E 线在本目录的 Vue 组件里从这里引用，不要另写一套。
import type {CretasSystem,AiState,AiFact,DataSource,SourceTagKind,FormulaSpec,ChangeEntry,FieldChange} from '../../../ydwm-v1/public/design-system'
export type {AiState,AiFact,DataSource,SourceTagKind,FormulaSpec,ChangeEntry,FieldChange}
function ui():CretasSystem{const u=(window as unknown as {CretasUI?:CretasSystem}).CretasUI;if(!u)throw Error('共享设计系统未加载（/design-system.js）');return u}
export const fmtMoney=(v:unknown,o?:{currency?:string;digits?:number;missing?:string})=>ui().fmtMoney(v,o)
export const fmtQty=(v:unknown,unit?:string|null,o?:{digits?:number;missing?:string})=>ui().fmtQty(v,unit,o)
export const fmtNumber=(v:unknown,o?:{digits?:number;missing?:string})=>ui().fmtNumber(v,o)
export const fmtPct=(v:unknown,o?:{digits?:number;missing?:string})=>ui().fmtPct(v,o)
export const valueKind=(v:unknown)=>ui().valueKind(v)
export const safeRatio=(a:unknown,b:unknown,reason?:string)=>ui().safeRatio(a,b,reason)
export const ai=()=>ui().ai
export const aiState=(s:AiState)=>ui().ai.STATES[s]
export const sourceTagHTML=(s:SourceTagKind,o?:{at?:string;by?:string})=>ui().sourceTagHTML(s,o)
export const cretasUI=ui
// B3 口径弹层 / B6 操作日志（共享组件，命令式调用；Vue 里在点击处理里调用即可）
export const openFormula=(anchor:HTMLElement,spec:FormulaSpec)=>ui().openFormula(anchor,spec)
export const diffFields=(b:Record<string,unknown>|null|undefined,a:Record<string,unknown>|null|undefined,labels?:Record<string,string>)=>ui().diffFields(b,a,labels)
export const changeLogHTML=(entries:ChangeEntry[],o?:{empty?:string})=>ui().changeLogHTML(entries,o)
export const blacklakeLog:CretasSystem['blacklakeLog']=r=>ui().blacklakeLog(r)
export const loadBlacklakeLog:CretasSystem['loadBlacklakeLog']=(q,o)=>ui().loadBlacklakeLog(q,o)
export const blacklakeLogDrawer:CretasSystem['blacklakeLogDrawer']=o=>ui().blacklakeLogDrawer(o)
export const changeLogDrawer=(o:{title?:string;object?:string;load:()=>ChangeEntry[]|Promise<ChangeEntry[]>})=>ui().changeLogDrawer(o)
// BL 21 / 22 / 23 共享列表组件（命令式，在 onMounted 里挂到容器上）
export const parseNos=(t:string)=>ui().parseNos(t)
export const matchNos=<T>(rows:T[],nos:string[],get:(r:T)=>unknown)=>ui().matchNos(rows,nos,get)
export const batchNoSearch:CretasSystem['batchNoSearch']=(p,o)=>ui().batchNoSearch(p,o)
export const relativeRange=(k:string,today?:Date)=>ui().relativeRange(k,today)
export const relativeDate:CretasSystem['relativeDate']=(p,o)=>ui().relativeDate(p,o)
export const viewTabs:CretasSystem['viewTabs']=(p,o)=>ui().viewTabs(p,o)
// 第五批（R21）：替换浏览器原生 prompt / confirm 的共享小弹层（返回 Promise，取消得 null）
export const askText:CretasSystem['askText']=o=>ui().askText(o)
export const confirmReason:CretasSystem['confirmReason']=o=>ui().confirmReason(o)
export const confirmAction:CretasSystem['confirmAction']=o=>ui().confirmAction(o)
// 第六批（R23）：Cretas ↔ 黑湖对账（只读）。reconcile 纯函数；reconcileView 在 onMounted 里挂到容器上
export type {ReconcileOptions,ReconcileResult,ReconcileRow,ReconcileField,ReconcileState} from '../../../ydwm-v1/public/design-system'
export const reconcile:CretasSystem['reconcile']=o=>ui().reconcile(o)
export const reconcileView:CretasSystem['reconcileView']=(p,o)=>ui().reconcileView(p,o)
export const reconcileCSV:CretasSystem['reconcileCSV']=(r,o)=>ui().reconcileCSV(r,o)
