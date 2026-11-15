// 成本分析共用的确定性计算（味美与六膳门两个工作区同一份；unified-v1/src/cost/cost-review.ts 必须与本文件字节一致）。
// 规格：unified-spec/B-cost-analysis.md。数字、分类、信号全部由代码给出，模型只解释。
export interface MaterialTrace {id:string;documentId:string;document:string;status:string;scope:'plan';material:string;unit:string|null;required:number|null;issued:number|null;consumed:number|null;returned:number|null;recordedLineCost:number|null;batches:string[]}
export interface CostProcess {id:string;name:string;personHours:number|null;participants:number|null;allocatedLabor:number|null;source:string;ruleLabor?:number|null;ruleReason?:string|null}
export interface ReportLineage {kind:"PLAN_START_RECORD";proofHash:string;basis:string;boundary:string;reports:{reportId:string;batchId:string;batchNumber:string;processId:string;processName:string;submittedAt:string}[]}
export interface BlItem {field:string;blacklake:number|null;cretas:number|null;delta:number|null;reason:{code:string;text:string}}
export interface BlCost {available:boolean;reason?:string;projectCostId?:number;status?:string;updatedAt?:string;items?:BlItem[];total?:{blacklake:number|null;cretas:number|null;delta:number|null};output?:{blacklake:number|null;cretas:number|null;unitBlacklake:string|null;unitCretas:string|null};unitCost?:{blacklake:number|null;cretas:number|null;reason:{code:string;text:string}|null};estimate?:{total:number|null;unit:number|null;material:number|null;labor:number|null;differenceAmount:number|null;differenceRate:number|null};breakdown?:{type:string;source:string;count:number;amount:number}[];lines?:{type:string;source:string;name:string;qty:number|null;unit:string|null;unitPrice:number|null;hours:number|null;biz:string|null;amount:number|null}[];detailCount?:number;adopted?:'blacklake'|'cretas'|null;adoptedNote?:string;supplementState?:string|null;supersededFields?:string[];adoptedView?:{material:AdoptPart;labor:AdoptPart;outsourceOther:{value:number;source:string}|null;output:{value:number|null;unit:string|null;source:string;note:string|null};total:number|null;unitCost:number|null;label:string;blockedReason?:string|null};adoptedLabel?:string}
export interface AdoptPart {value:number|null;source:'blacklake'|'cretas'|'supplement'|'cretas-primary'|null;note:string|null;blocked?:boolean}
// 采用值（有黑湖成本时）或 Cretas 计算：列表、构成等处统一取这一个，并给出来源标签
// 第六批 R23：工单级采用值统一从这里取（有 Cretas 称重时产出与单位成本改用称重；没有黑湖成本但有称重的单也有采用值）
export type AdoptView=NonNullable<BlCost['adoptedView']>&{weighed?:boolean}
export const adoptedOf=(o:CostOrder):AdoptView|null=>(o.adopted as AdoptView|null|undefined)??(o.blacklake?.available?o.blacklake.adoptedView||null:null)
export interface WeighView {complete:boolean;incomplete:string|null;basis:string;missing:string|null;firstProcess:{code:string;name:string;weighed:boolean}|null;routeFinal:{code:string;name:string}|null;source:'cretas-primary';records:number|null;goodKg:number|null;inputKg:number|null;defectKg:number|null;yieldRate:number|null;byproducts:{productCode:string|null;name:string;kg:number}[];byproductKg:number;finalProcess:{code:string;name:string}|null;processes:{code:string;name:string;inputKg:number|null;goodKg:number|null;defectKg:number|null;records:number|null;yieldRate:number|null;isFirst:boolean;isLast:boolean;routeIndex:number|null}[];startAt:string|null;endAt:string|null;lastAt:string|null;rule:{mode:string;mainShare:number|null;text:string};total:number|null;mainCost:number|null;byproductCost:number|null;unitCost:number|null;materialUnit:number|null;laborUnit:number|null;blockedReason:string|null;note:string|null;compare:{blacklake:{value:number|null;unit:string|null}|null;cretasReport:{value:number|null;unit:string|null}}}
export const SOURCE_LEGEND='来源：「黑湖」＝黑湖接口；「Cretas」＝Cretas 主来源（报工称重、入库批次，R23）；「Cretas 计算」＝由黑湖原始记录算出；「Cretas 补充」＝人工补的缺值，黑湖有值时被取代。'
export function adoptedUnit(o:CostOrder):{value:number|null;label:string;reason:string|null}{const v=adoptedOf(o);if(v)return {value:v.unitCost,label:'采用：'+v.label+(v.weighed?' · 产出 Cretas 称重':''),reason:v.blockedReason||(v.weighed&&o.weigh?.note)||null};return {value:unitCost(o),label:'Cretas 计算',reason:null}}
// 第五批①：两张单对照用的逐项值（有黑湖成本用采用值，否则 Cretas 计算）；R15 被阻断的项值为空并带原因
export interface CmpCell {value:number|null;source:string;note:string|null}
export function compareCells(o:CostOrder){const v=adoptedOf(o)
 if(v){const part=(p:AdoptPart)=>({value:p.value,source:p.blocked?'—':sourceName(p.source),note:p.note})
  return {material:part(v.material),labor:part(v.labor),extra:(v.outsourceOther?{value:v.outsourceOther.value,source:'黑湖',note:null}:{value:null,source:'—',note:(o.blacklake?.items||[]).some(i=>(i.field==='委外'||i.field==='其他')&&i.blacklake===0)?'黑湖记为 0（未计价，按 R13 不当作读数）；Cretas 不含此项，合计按未发生计':'黑湖未返回委外/其他；Cretas 不含此项'}) as CmpCell,total:{value:v.total,source:v.label==='—'?'—':v.label,note:v.blockedReason||null} as CmpCell,output:{value:v.output.value,source:sourceName(v.output.source)==='—'?'黑湖':sourceName(v.output.source),note:v.weighed?('出成率 '+(o.weigh?.yieldRate==null?'—':(o.weigh.yieldRate*100).toFixed(2)+'%')+(o.weigh?.byproductKg?'，副产品 '+o.weigh.byproductKg.toFixed(2)+' kg':'')):(o.weigh&&!o.weigh.complete?'完工工序「'+(o.weigh.routeFinal?.name||o.weigh.finalProcess?.name||'未认定')+'」还没有称重，产出暂按来源，不用中间工序顶替'+(v.output.note?'；'+v.output.note:''):v.output.note)} as CmpCell,unit:v.output.unit||o.unit,unitCost:{value:v.unitCost,source:v.label==='—'?'—':v.label+(v.weighed?' ÷ Cretas 称重':''),note:v.blockedReason||(v.unitCost===null?(v.weighed?o.weigh?.note||'不能计算':'实际产量为 0 或缺失'):(v.weighed?o.weigh?.rule.text||null:null))} as CmpCell}}
 const u=unitCost(o);return {material:{value:o.materialCost,source:'Cretas 计算',note:null},labor:{value:o.laborCost,source:'Cretas 计算',note:null},extra:{value:null,source:'—',note:'Cretas 直接成本不含委外/其他'},total:{value:o.directCost,source:'Cretas 计算',note:null},output:{value:o.finishedGood,source:'Cretas 计算',note:null},unit:o.unit,unitCost:{value:u,source:'Cretas 计算',note:u===null?(gapOf(o)?.label||'不能计算'):null}}}
// 第五批审计 B-1：产品级材料费 / 人工费 / 直接成本一律显示采用值并标来源；工作区没有黑湖成本时就是 Cretas 计算
const aggSrc=(a:AdoptAgg)=>[a.blacklake?'黑湖 '+a.blacklake+' 张':'',a.cretas?'Cretas 计算 '+a.cretas+' 张':'',a.supplement?'Cretas 补充 '+a.supplement+' 张':''].filter(Boolean).join(' · ')||'—'
export function productAdopted(p:CostProduct,k:'material'|'labor'|'direct'){if(k==='direct')return p.adoptedDirect?{value:p.adoptedDirect.value,partial:p.adoptedDirect.partial,src:'采用值（材料＋人工）'}:{value:p.knownDirectCost,partial:p.directPartial,src:'Cretas 计算'}
 const a=k==='material'?p.adoptedMaterial:p.adoptedLabor;return a?{value:a.value,partial:a.partial,src:aggSrc(a)}:{value:k==='material'?p.knownMaterialCost:p.knownLaborCost,partial:k==='material'?p.materialPartial:p.laborPartial,src:'Cretas 计算'}}
export const supersededText=(list?:SupersededSupp[]|null)=>list?.length?list.map(s=>s.projectCode+' 的 Cretas 补充'+s.field+' '+fmtCost(s.value)+' 已被黑湖来源值 '+fmtCost(s.adopted)+' 取代').join('；')+'，不参与采用值。':''
// 第五批审计 B-2：数据时点一律北京时间
export const bjTime=(iso?:string|null)=>{if(!iso)return '—';const d=new Date(iso);if(Number.isNaN(+d))return '—';return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(d)+'（北京时间）'}
export const sourceName=(s:string|null|undefined)=>({blacklake:'黑湖',cretas:'Cretas 计算',supplement:'Cretas 补充','cretas-primary':'Cretas'} as Record<string,string>)[s||'']||'—'
export interface CostOrder {weigh?:WeighView|null;adopted?:unknown;blacklake?:BlCost;reportLineage?:ReportLineage|null;synthetic?:boolean;id:string;projectCode:string;product:string;status:string;currency:string;planId:string|null;planNumber:string|null;unit:string|null;finishedGood:number|null;materialCost:number|null;laborCost:number|null;sourceLaborCost:number|null;directCost:number|null;unitCostAvailable:boolean;sourceHash:string;productTypeId:string;workflowProductTypeId:string;supplementEvidence:string|null;supplementAt:string|null;requests:{field:string;owner:string;action:string}[];supplementStatus:string;participants:number|null;perPerson:number|null;processes:CostProcess[];missing:string[];readinessIssues:string[];followup:{status:string;note:string;at:string}|null;materialTrace:MaterialTrace[];at?:string|null;sourceMaterialCost?:number|null;supplementFields?:string[];supplementNote?:string|null;ruleLabor?:number|null;ruleLaborDelta?:number|null;ruleLaborPartial?:boolean}
export interface AdoptAgg {value:number|null;partial:boolean;blacklake:number;cretas:number;supplement:number;missing:number}
export interface SupersededSupp {orderId:string;projectCode:string;field:string;value:number|null;adopted:number|null}
export interface CostProduct {weighAgg?:{pending?:number;count:number;costed:number;goodKg:number|null;unitCost:number|null;materialUnit:number|null;byproductKg:number};adoptedMaterial?:AdoptAgg;adoptedLabor?:AdoptAgg;adoptedDirect?:{value:number|null;partial:boolean};supersededSupplements?:SupersededSupp[];blCovered?:number;blWeightedUnitCost?:number|null;blMaterialUnitCost?:number|null;blLaborUnitCost?:number|null;key:string;product:string;productCode:string|null;productTypeId:string;stage:string;unit:string|null;orders:CostOrder[];linkedPlanCount:number;unlinkedBatchCount:number;orderCount:number;eligibleCount:number;blockedCount:number;knownOutput:number|null;outputPartial:boolean;materialPartial:boolean;laborPartial:boolean;knownMaterialCost:number|null;knownLaborCost:number|null;knownDirectCost:number|null;weightedUnitCost:number|null;partial:boolean;directPartial:boolean;currency:string;missing:string[];weightedMaterialUnitCost:number|null;weightedLaborUnitCost:number|null}

// ---- 数字格式：一律走 D 线共享 CretasUI（SHARED.md，T5）；两位小数，缺失按共享规则显示「—」，0 保留 0.00；单位由模板另行显示 ----
type SharedFmt={fmtNumber:(v:unknown,o?:{digits?:number;missing?:string})=>string;fmtPct:(v:unknown,o?:{digits?:number;missing?:string})=>string}
const shared=()=>(window as unknown as {CretasUI?:SharedFmt}).CretasUI
// 共享脚本未加载时的兜底只为不白屏，口径与共享实现一致（两位小数、缺失不补 0）
const local=(n:unknown)=>typeof n!=='number'||!Number.isFinite(n)?'—':n.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})
export const fmtCost=(n:number|null|undefined)=>{const v=typeof n==='number'&&Math.abs(n)<0.005?0:n;const u=shared();return u?u.fmtNumber(v,{digits:2}):local(v)}
export const fmtPct=(n:number|null|undefined)=>{const u=shared(),v=n==null||!Number.isFinite(n)?null:n*100;return u?u.fmtPct(v,{digits:2,missing:'—'}):v===null?'—':v.toFixed(2)+'%'}
export const moneyUnit=(currency:string)=>currency==='CNY'?'元':currency
export const unitCost=(o:CostOrder)=>o.unitCostAvailable&&o.finishedGood!==null&&o.finishedGood>0&&o.directCost!==null?o.directCost/o.finishedGood:null
export const unitPart=(o:CostOrder,k:'materialCost'|'laborCost')=>unitCost(o)!==null&&o[k]!==null?o[k]!/o.finishedGood!:null
export const stateLabel=(s:string)=>({COMPLETED:'已完成',IN_PROGRESS:'进行中',DRAFT:'草稿',PENDING:'待备料',ISSUED:'已签收',PICKING:'备料中',TRANSFERRED:'已调拨',IN_USE:'使用中',CLOSED:'已关闭',CANCELLED:'已取消',RECORDED:'已记录'}[s]||s)
export const stageLabel=(s:string)=>({FINISHED_PRODUCT:'成品',SEMI_FINISHED:'半成品',SEMI_FINISHED_PRODUCT:'半成品','待核对':'阶段待核'} as Record<string,string>)[s]||s
export const followLabel=(s?:string|null)=>({OPEN:'未处理',MISSING:'待补资料',CHECKED:'已核对'} as Record<string,string>)[s||'']||'未处理'
export function analysisLines(answer:string){return answer.split(/\r?\n/).map(line=>line.trim()).filter(Boolean).map(line=>{const heading=/^#{1,6}\s+/.test(line)||/^\*\*[^*]+\*\*$/.test(line);return {heading,text:line.replace(/^#{1,6}\s+/,'').replace(/^\*\*([^*]+)\*\*$/,'$1').replace(/^-\s+/,'• ')}})}

// ---- 缺口：每张工单一个互斥主分类（BLOCKED93 方法），其余原因列为「另有」 ----
export type GapCode='NOT_FINISHED'|'NO_PROCESS'|'OUTPUT'|'MATERIAL_LINK'|'MATERIAL_ZERO'|'MATERIAL_PARTIAL'|'LABOR'|'UNIT'|'OTHER'|'ROSTER'|'STALE'
export interface GapInfo {code:GapCode;label:string;blocksUnitCost:boolean;owner:string;unlocks:string;reasons:string[];others:string[]}
export const GAP_LABEL:Record<GapCode,string>={NOT_FINISHED:'未完工',NO_PROCESS:'无工序对应',OUTPUT:'无正数良品产出',MATERIAL_LINK:'无可靠领退料关联',MATERIAL_ZERO:'材料金额为零',MATERIAL_PARTIAL:'部分零价、缺价或异常',LABOR:'人工费缺失或零工资',UNIT:'产出单位缺失',OTHER:'其他口径待核',ROSTER:'只缺名单或人时',STALE:'补充资料待复核'}
const GAP_ORDER:GapCode[]=['NOT_FINISHED','NO_PROCESS','OUTPUT','MATERIAL_LINK','MATERIAL_ZERO','MATERIAL_PARTIAL','LABOR','UNIT','OTHER','STALE','ROSTER']
const UNLOCK:Record<GapCode,string>={NOT_FINISHED:'完工后才进入单位成本比较',NO_PROCESS:'可补人工资料并按工序分摊',OUTPUT:'有了正数良品才能算单位成本',MATERIAL_LINK:'关联后材料费进入单位直接成本',MATERIAL_ZERO:'确认估值后材料费可信，单位成本可用于比较',MATERIAL_PARTIAL:'逐料补价后材料费完整',LABOR:'人工费确定后算出单位直接成本',UNIT:'单位确定后才能和同产品比较',OTHER:'口径核清后进入比较',ROSTER:'补名单算人均；补人时算工序分摊（单位成本不变）',STALE:'复核后补充资料重新生效'}
const OWNER:Record<GapCode,string>={NOT_FINISHED:'生产负责人',NO_PROCESS:'生产负责人 / 报工人',OUTPUT:'生产负责人 / 报工人',MATERIAL_LINK:'仓库 / 成本核对人',MATERIAL_ZERO:'仓库 / 财务',MATERIAL_PARTIAL:'仓库 / 财务',LABOR:'车间主管 / 成本核对人',UNIT:'生产负责人',OTHER:'成本核对人',ROSTER:'班组长 / 车间主管',STALE:'成本核对人'}
function codesOf(o:CostOrder):{code:GapCode;reason:string}[]{
 const out:{code:GapCode;reason:string}[]=[],m=new Set(o.missing),add=(code:GapCode,reason:string)=>out.push({code,reason})
 if(!['COMPLETED','CLOSED'].includes(o.status))add('NOT_FINISHED','状态：'+stateLabel(o.status))
 if(m.has('工序对应关系')&&!o.reportLineage)add('NO_PROCESS','工序对应关系待补')
 if(m.has('实际良品产出'))add('OUTPUT','实际良品产出未填写')
 else if(o.finishedGood===0)add('OUTPUT','良品已记录为 0')
 if(m.has('产出单位'))add('UNIT','产出单位未填写')
 if(m.has('材料费')&&!o.readinessIssues.some(i=>/领退料|材料/.test(i)))add('MATERIAL_LINK','材料费未提供')
 if(m.has('人工费'))add('LABOR','人工费未提供')
 for(const i of o.readinessIssues){
  if(/未完工|尚未完工/.test(i)){if(!out.some(x=>x.code==='NOT_FINISHED'))add('NOT_FINISHED',i)}
  else if(/良品|产出已记录为0/.test(i)){if(!out.some(x=>x.code==='OUTPUT'))add('OUTPUT',i)}
  else if(/未关联/.test(i))add('MATERIAL_LINK',i)
  else if(/全部为源端零值|材料净额为零/.test(i))add('MATERIAL_ZERO',i)
  else if(/部分领料|单价|符号|为负/.test(i))add('MATERIAL_PARTIAL',i)
  else if(/工资|人工/.test(i))add('LABOR',i)
  else if(/单位/.test(i))add('UNIT',i)
  else if(/人时/.test(i))add('ROSTER',i)
  else add('OTHER',i)}
 if(o.supplementStatus==='STALE')add('STALE','源记录变化，旧补充待复核')
 if(m.has('有效人时'))add('ROSTER','有效人时未填写')
 if(m.has('实际参与名单'))add('ROSTER','实际参与名单未提供')
 return out}
export function gapOf(o:CostOrder):GapInfo|null{
 const all=codesOf(o);if(!all.length)return null
 const primary=GAP_ORDER.find(c=>all.some(x=>x.code===c))!
 const req=o.requests.find(r=>primary==='ROSTER'?/名单|人时/.test(r.field):primary.startsWith('MATERIAL')?/材料/.test(r.field):primary==='LABOR'?/人工/.test(r.field):primary==='NO_PROCESS'?/工序/.test(r.field):/产出|良品|单位/.test(r.field))
 return {code:primary,label:GAP_LABEL[primary],blocksUnitCost:unitCost(o)===null,owner:req?.owner||OWNER[primary],unlocks:UNLOCK[primary],reasons:all.filter(x=>x.code===primary).map(x=>x.reason),others:[...new Set(all.filter(x=>x.code!==primary).map(x=>GAP_LABEL[x.code]))]}}
export function coverage(orders:CostOrder[]){
 const live=orders.filter(o=>o.status!=='CANCELLED'),ready=live.filter(o=>unitCost(o)!==null),gaps=(live.map(o=>({o,g:gapOf(o)})).filter(x=>x.g) as {o:CostOrder;g:GapInfo}[]).sort((a,b)=>Number(b.g.blocksUnitCost)-Number(a.g.blocksUnitCost)||GAP_ORDER.indexOf(a.g.code)-GAP_ORDER.indexOf(b.g.code)||a.o.projectCode.localeCompare(b.o.projectCode))
 const primary=new Map<GapCode,number>(),overlap=new Map<GapCode,number>()
 for(const {o,g} of gaps){if(g.blocksUnitCost)primary.set(g.code,(primary.get(g.code)||0)+1);for(const c of new Set(codesOf(o).map(x=>x.code)))overlap.set(c,(overlap.get(c)||0)+1)}
 const traces=live.flatMap(o=>o.materialTrace||[]),procs=live.flatMap(o=>o.processes)
 return {total:live.length,cancelled:orders.length-live.length,ready:ready.length,blocked:live.length-ready.length,gapRows:gaps,
  primary:GAP_ORDER.filter(c=>primary.get(c)).map(c=>({code:c,label:GAP_LABEL[c],count:primary.get(c)!})),
  overlap:GAP_ORDER.filter(c=>overlap.get(c)).map(c=>({code:c,label:GAP_LABEL[c],count:overlap.get(c)!})),
  lineCost:{total:traces.length,known:traces.filter(t=>t.recordedLineCost!==null).length,zero:traces.filter(t=>t.recordedLineCost===0).length},
  hours:{total:procs.length,known:procs.filter(p=>p.personHours!==null).length}}}

// ---- 经营信号（COST-ALERT-CONTRACT v1 的可审查初始阈值，不是行业标准；只看可核算工单） ----
export interface CostSignal {id:string;type:string;family:'business_signal'|'readiness';title:string;evidence:string;threshold:string;owner:string;productKey:string;orderIds:string[]}
export function signals(products:CostProduct[]):CostSignal[]{
 const out:CostSignal[]=[]
 for(const p of products){
  const ready=p.orders.filter(o=>unitCost(o)!==null)
  for(const o of ready){const d=o.directCost!;if(d<=0)continue
   const ms=o.materialCost!/d,ls=o.laborCost!/d
   if(ms<0.1)out.push({id:'MATERIAL_SHARE_LOW:'+o.id,type:'MATERIAL_SHARE_LOW',family:'business_signal',title:'材料占比偏低',evidence:o.projectCode+' 材料占已识别直接成本 '+fmtPct(ms),threshold:'< 10.00%',owner:'仓库 / 成本核对人',productKey:p.key,orderIds:[o.id]})
   if(ls>0.65)out.push({id:'LABOR_SHARE_HIGH:'+o.id,type:'LABOR_SHARE_HIGH',family:'business_signal',title:'人工占比偏高',evidence:o.projectCode+' 人工占已识别直接成本 '+fmtPct(ls)+'；先核材料是否漏记',threshold:'> 65.00%',owner:'车间主管 / 成本核对人',productKey:p.key,orderIds:[o.id]})
   if(o.laborCost===0)out.push({id:'LABOR_ZERO_UNVERIFIED:'+o.id,type:'LABOR_ZERO_UNVERIFIED',family:'business_signal',title:'人工费记录为零',evidence:o.projectCode+' 人工费 0.00，不代表效率更高',threshold:'= 0',owner:'车间主管',productKey:p.key,orderIds:[o.id]})}
  if(ready.length>=3){const c=ready.map(unitCost) as number[],lo=Math.min(...c),hi=Math.max(...c)
   if(lo>0&&(hi-lo)/lo>=0.2)out.push({id:'UNIT_COST_SPREAD:'+p.key,type:'UNIT_COST_SPREAD',family:'business_signal',title:'同产品单位成本差异',evidence:p.product+' '+ready.length+' 张可比，最高比最低高 '+fmtPct((hi-lo)/lo),threshold:'≥ 20.00%，样本 ≥ 3',owner:'成本核对人',productKey:p.key,orderIds:ready.map(o=>o.id)})}
  else if(ready.length>0)out.push({id:'PEER_SAMPLE_SMALL:'+p.key,type:'PEER_SAMPLE_SMALL',family:'readiness',title:'可比样本不足',evidence:p.product+' 只有 '+ready.length+' 张可比，不做差异判定',threshold:'样本 < 3',owner:'成本核对人',productKey:p.key,orderIds:ready.map(o=>o.id)})}
 return out}

// ---- 同产品对照：差额拆成材料差＋人工差，逐物料只比两边都有行成本的 ----
export function pairDelta(high:CostOrder,low:CostOrder){
 const h=unitCost(high),l=unitCost(low);if(h===null||l===null)return null
 const material=unitPart(high,'materialCost')!-unitPart(low,'materialCost')!,labor=unitPart(high,'laborCost')!-unitPart(low,'laborCost')!
 const key=(t:MaterialTrace)=>t.material+'|'+(t.unit||''),pick=(o:CostOrder)=>{const m=new Map<string,{name:string;unit:string|null;cost:number|null;qty:number|null}>();for(const t of o.materialTrace||[]){const k=key(t),prev=m.get(k);m.set(k,{name:t.material,unit:t.unit,cost:t.recordedLineCost===null?null:(prev?.cost??0)+t.recordedLineCost,qty:t.consumed===null?prev?.qty??null:(prev?.qty??0)+t.consumed})}return m}
 const a=pick(high),b=pick(low),materials=[...new Set([...a.keys(),...b.keys()])].map(k=>{const x=a.get(k),y=b.get(k),ok=x?.cost!=null&&y?.cost!=null
  return {name:(x||y)!.name,unit:(x||y)!.unit,highUnit:x?.cost!=null?x.cost/high.finishedGood!:null,lowUnit:y?.cost!=null?y.cost/low.finishedGood!:null,delta:ok?x!.cost!/high.finishedGood!-y!.cost!/low.finishedGood!:null}}).sort((p,q)=>Math.abs(q.delta??0)-Math.abs(p.delta??0))
 const pmap=(o:CostOrder)=>new Map(o.processes.map(p=>[p.name,p.allocatedLabor])),pa=pmap(high),pb=pmap(low)
 const processes=[...new Set([...pa.keys(),...pb.keys()])].map(n=>{const x=pa.get(n),y=pb.get(n);return {name:n,highUnit:x!=null?x/high.finishedGood!:null,lowUnit:y!=null?y/low.finishedGood!:null,delta:x!=null&&y!=null?x/high.finishedGood!-y/low.finishedGood!:null}})
 const warn=[high,low].filter(o=>o.laborCost===0||o.readinessIssues.length||o.missing.some(f=>f!=='实际参与名单'&&f!=='有效人时')).map(o=>o.projectCode)
 return {total:h-l,material,labor,balanced:Math.abs(material+labor-(h-l))<0.005,materials,processes,warn}}
export function productReview(p:CostProduct){
 const eligible=p.orders.filter(o=>unitCost(o)!==null),ordered=[...eligible].sort((a,b)=>unitCost(a)!-unitCost(b)!),lower=ordered[0],higher=ordered.at(-1),delta=ordered.length>1?unitCost(higher!)!-unitCost(lower)!:null
 const gaps=coverage(p.orders).gapRows,missing=gaps.filter(r=>r.g.blocksUnitCost).map(r=>r.o),rosterOnly=gaps.filter(r=>!r.g.blocksUnitCost).map(r=>r.o)
 return {eligible,ordered,missing,rosterOnly,checks:gaps.map(r=>r.o),lower,higher,delta,materialDelta:lower&&higher&&delta!==null?unitPart(higher,'materialCost')!-unitPart(lower,'materialCost')!:null,laborDelta:lower&&higher&&delta!==null?unitPart(higher,'laborCost')!-unitPart(lower,'laborCost')!:null,trace:[...new Map(p.orders.flatMap(o=>o.materialTrace||[]).map(t=>[t.id,t])).values()]}}

// ---- 工作区：同一套组件服务味美与六膳门，接口、偏好键、来源跳转按工作区注入（规格 0.3、0.4） ----
export type SourceKind='plan'|'canvas'|'task'|'report'|'material'|'bom'
// 第四批③：按班次规则的有效工时（A 计算，B 只读显示；不改黑湖人工值）
export interface ShiftRow {reportId:string;taskId:string;start:string|null;end:string|null;blacklakeEffectiveSeconds:number|null;status:string;totalSeconds?:number|null;breakSeconds?:number|null;overtimeSeconds?:number|null;effectiveSeconds?:number|null}
export interface ShiftHours {available:boolean;ruleName:string|null;formula:string;rows:ShiftRow[];reason?:string|null}
export interface CostWorkspaceConfig {factoryId:string;label:string;api:string;currencyNote:string;orderTerm:string;features:{weighing?:boolean;scopeAnalysis:boolean;batchAnalysis:boolean;periodAttribution:string;wageRules?:boolean;valuation?:boolean;shiftHours?:(o:CostOrder)=>Promise<ShiftHours|null>;efficiency?:(o:CostOrder)=>Promise<{rows:{process:string;standard:string|null;actual:string|null;efficiency:number|null;passRate:number|null;reason:string|null}[];note?:string}|null>};sourceLink:(kind:SourceKind,o:CostOrder,id?:string)=>string|null;initialOrderId?:string|null;initialLocate?:{planId?:string|null;no?:string|null}}
const carry=()=>{const out=new URLSearchParams(),cur=new URL(location.href).searchParams;for(const k of ['acceptanceSession','componentBuild','uiBuild'])if(cur.get(k))out.set(k,cur.get(k)!);return out}
export const YDWM_COST:CostWorkspaceConfig={factoryId:'YDWM',label:'味美',api:'/__account/product-cost',currencyNote:'CNY（元）',orderTerm:'工单（批次）',features:{scopeAnalysis:true,batchAnalysis:true,periodAttribution:'生产日期（未填写时取创建日期）；本工作区没有完工日期'},
 // 反向「看成本」（A 线计划/批次行）：orderId（兼容）、planId、no（批次号或计划号）
 initialOrderId:new URL(location.href).searchParams.get('orderId'),initialLocate:{planId:new URL(location.href).searchParams.get('planId'),no:new URL(location.href).searchParams.get('no')},
 sourceLink(kind,o,id){const q=carry();if(kind==='plan'||kind==='canvas'){if(!o.planId)return null;q.set('chainPlanId',o.planId);q.set('chainStage',kind==='canvas'?'canvas':'plan');if(kind==='canvas'){q.set('tab','product-processes');q.set('productTypeId',o.workflowProductTypeId)}return (kind==='canvas'?'/production/process-hub':'/production/plans')+'?'+q}
  if(kind==='task'){if(!o.planId)return null;q.set('chainPlanId',o.planId);q.set('chainStage','canvas');q.set('tab','product-processes');q.set('productTypeId',o.workflowProductTypeId);return '/production/process-hub?'+q}
  // 深链约定（DECISIONS 六之后）：单号一律 ?no=
  if(kind==='material'&&id){q.set('no',id);return '/production/material-flow?'+q}
  return null}}

// ---- 第四批 BL14：味美进销存成本金额估值口径（B 出函数，C 在进销存/库存显示；只读、纯函数） ----
// 原料/包材：批次库存金额＝批次当前数量 × 批次入库单价（unitPrice，同批次单位）；单价缺失或为 0 → 「无单价」，金额为空，不补 0。
// 按物料汇总：同物料、同单位的批次 Σ金额 ÷ Σ数量 得加权单价；有任一批次无单价时汇总标「部分批次无单价」，金额只含有单价的批次。
// 成品/半成品：取成本分析的单位直接成本（Cretas 计算，材料＋人工，不含制造费用）；没有可算工单 → 「无单价」。不同单位不换算。
export interface YdwmBatchLike {id?:string;materialTypeId?:string|null;materialName?:string|null;batchNumber?:string|null;unit?:string|null;unitPrice?:number|null;currentQuantity?:number|null;remainingQuantity?:number|null;quantity?:number|null}
export interface ValuationRow {key:string;name:string;unit:string|null;qty:number|null;unitPrice:number|null;amount:number|null;source:'ydwm-batch'|'cost-analysis'|'missing';basis:string;partial?:boolean}
export const VALUATION_RULE='原料/包材：批次当前数量×批次入库单价，同单位才乘；单价缺或为 0 记「无单价」不补 0。成品：成本分析单位直接成本（Cretas 计算，不含制造费用）。不同单位不换算。'
const qtyOf=(b:YdwmBatchLike)=>{for(const v of [b.currentQuantity,b.remainingQuantity,b.quantity])if(typeof v==='number'&&Number.isFinite(v))return v;return null}
export function valueBatch(b:YdwmBatchLike):ValuationRow{const q=qtyOf(b),p=typeof b.unitPrice==='number'&&b.unitPrice>0?b.unitPrice:null
 return {key:String(b.id||b.batchNumber||''),name:(b.materialName||'未命名物料')+(b.batchNumber?' · '+b.batchNumber:''),unit:b.unit||null,qty:q,unitPrice:p,amount:p!==null&&q!==null?Math.round(q*p*100)/100:null,source:p===null?'missing':'ydwm-batch',basis:p===null?'无单价（批次未记录入库单价）':'味美批次入库单价'}}
export function valueMaterials(batches:YdwmBatchLike[]):ValuationRow[]{const g=new Map<string,{name:string;unit:string|null;qty:number;amount:number;priced:number;n:number;missing:number}>()
 for(const b of batches){const r=valueBatch(b),k=(b.materialTypeId||b.materialName||'?')+'|'+(b.unit||''),x=g.get(k)||{name:b.materialName||'未命名物料',unit:b.unit||null,qty:0,amount:0,priced:0,n:0,missing:0};x.n++;if(r.qty!==null)x.qty+=r.qty;if(r.amount!==null){x.amount+=r.amount;x.priced+=r.qty||0}else x.missing++;g.set(k,x)}
 return [...g].map(([key,x])=>({key,name:x.name,unit:x.unit,qty:Math.round(x.qty*100)/100,unitPrice:x.priced>0?x.amount/x.priced:null,amount:x.n>x.missing?Math.round(x.amount*100)/100:null,source:x.missing===x.n?'missing' as const:'ydwm-batch' as const,basis:x.missing===x.n?'无单价（所有批次未记录入库单价）':x.missing?'味美批次入库单价 · '+x.missing+'/'+x.n+' 个批次无单价，金额只含有单价的批次':'味美批次入库单价（'+x.n+' 个批次加权）',partial:x.missing>0&&x.missing<x.n}))}
export function valueFinished(products:CostProduct[]):ValuationRow[]{return products.map(p=>({key:p.key,name:p.product,unit:p.unit,qty:null,unitPrice:p.weightedUnitCost,amount:null,source:p.weightedUnitCost===null?'missing' as const:'cost-analysis' as const,basis:p.weightedUnitCost===null?'无单价（没有可算单位成本的工单）':'成本分析单位直接成本（Cretas 计算，'+p.eligibleCount+' 张工单，不含制造费用）'}))}
