<script setup lang="ts">
// 成本分析模块入口（六膳门工作区）。组件与味美同一份（ProductCostWorkspace 等，字节一致），这里只注入六膳门工作区配置：
// 接口 /__lsm/product-cost、偏好键 LIUSHANMEN、来源跳转回本工作区的工单/工序/领退料、能力开关（无逐单核对，W11）。
// 悬浮窗：按 D 的 ai-objects 协议注册三类成本对象（本工单 / 本产品 / 当前筛选），依据由成本适配层的报表生成，
// 补充值标 source:'cretas'；解读只给规则结论（本工作区未接成本模型），不改任何数字。
import {computed,onBeforeUnmount,onMounted,watch} from 'vue'
import CostWorkspace from './ProductCostWorkspace.vue'
import BomPriceAdvice from './BomPriceAdvice.vue'
import {coverage,gapOf,signals,unitCost,fmtCost,fmtPct,stageLabel,followLabel,stateLabel,type ShiftHours,type CostWorkspaceConfig,type SourceKind,type CostOrder,type CostProduct} from './cost-review'
import {S} from '../frame/store'
import {registerAiObject,setAiObject,type AiExplain} from '../frame/ai-objects'
import type {AiFact} from '../frame/ai-protocol'
type Report={factoryId:string;filters:Record<string,string>;products:CostProduct[];sourceRevision?:string;currencySupplement?:{value:string}|null;dateContext:{label:string}}
type Env=Window&{CretasSupplyAnalysis?:{openCost:(f:Record<string,unknown>)=>Promise<void>;refresh:()=>Promise<void>;panelClose:()=>void};CretasCostScope?:{report?:()=>Report|null}}
const env=window as Env
// 第八批：BOM 调价建议与成本分析共用本入口（路由由 D 登记为 view=cost），按路径分流；味美工作区只给精简视图，不读六膳门数据
const isBomPrice=computed(()=>S.path==='/production/bom/price-adjustments'||S.query.get('page')==='bom-price')   // ?page=bom-price：路由登记前后都能直达的备用深链
const bomWs=computed<'lsm'|'ydwm'>(()=>S.query.get('ws')==='ydwm'?'ydwm':'lsm')
// 深链（DECISIONS 六之后，A/C 已实现）：工单 project=、工序任务 task=、BOM 行 bom=、报工/领退料单号 no=
function sourceLink(kind:SourceKind,o:CostOrder,id?:string):string|null{
 const q=(o2:Record<string,string>)=>'?'+new URLSearchParams(o2).toString()
 if(kind==='plan')return '#/production/plans'+q({project:o.id})
 if(kind==='canvas')return '#/production/process-hub'+q({project:o.id})
 if(kind==='task')return id?'#/production/process-hub'+q({project:o.id,task:id}):null
 if(kind==='material')return id?'#/production/material-flow'+q({no:id}):null
 if(kind==='report')return id?'#/production/approval'+q({no:id}):null
 if(kind==='bom')return id?'#/production/process-hub'+q({project:o.id,bom:id}):null
 return null}
// BL15：效率与达标率由生产线（A）计算（production/efficiency.ts 纯函数 + A 的标准工时接口），成本页只读呈现；null 显示原因，不当 0
function topSession(){try{return new URL((window.top||window).location.href).searchParams.get('acceptanceSession')}catch{return null}}
async function shiftHours(o:CostOrder):Promise<ShiftHours|null>{
 const q=new URLSearchParams({ws:'lsm',project:o.id}),sess=topSession();if(sess)q.set('acceptanceSession',sess)
 const r=await fetch('/__unified/a/api/shift-hours?'+q,{headers:sess?{'x-acceptance-session':sess}:{}});if(!r.ok)return null
 const b=await r.json(),d=b.data||b;if(!d||d.available===false)return {available:false,ruleName:null,formula:'',rows:[],reason:d?.reason||'生产线没有返回班次工时'}
 return {available:true,ruleName:d.rule?(d.rule.shifts||[]).map((s:{name:string;start:string;end:string;breaks?:{start:string;end:string}[]})=>s.name+' '+s.start+'–'+s.end+((s.breaks||[]).length?' 休 '+(s.breaks||[]).map(b=>b.start+'–'+b.end).join('、'):'')).join('；'):null,formula:d.formula||'',rows:d.rows||[]}}
async function efficiency(o:CostOrder){
 const q=new URLSearchParams({ws:'lsm'}),sess=topSession();if(sess)q.set('acceptanceSession',sess)
 q.set('project',o.id)
 // 直接用 A 的效率接口（计算只在 A 一处），B 只读呈现；null 显示 reason，不当 0
 const r=await fetch('/__unified/a/api/efficiency?'+q,{headers:sess?{'x-acceptance-session':sess}:{}});if(!r.ok)return null
 const b=await r.json(),d=b.data||b;if(!d||d.available===false||!Array.isArray(d.processes)||!d.processes.length)return null
 type P={processCode:string;processName:string;standard:{secondsPerUnit:number;unit:string}|null;tasks:number;computable:number;rate:number|null;totalEffectiveSeconds:number|null;efficiency:number|null;reason:string|null}
 const rows=(d.processes as P[]).map(p=>({process:p.processName||p.processCode,standard:p.standard?fmtCost(p.standard.secondsPerUnit)+' 秒/'+p.standard.unit+'（Cretas 补充）':null,actual:p.totalEffectiveSeconds!=null?fmtCost(p.totalEffectiveSeconds/3600)+' 小时有效工时':null,efficiency:p.efficiency,passRate:p.rate,reason:p.reason||(p.computable<p.tasks?(p.tasks-p.computable)+' 个任务不可计算':null)}))
 return {rows,note:'由生产线计算（'+(d.rule||'')+'）；标准工时为 Cretas 补充，在「生产总览 → 效率与达标率」维护。'}}
const config=computed<CostWorkspaceConfig>(()=>({factoryId:'LIUSHANMEN',label:'六膳门',api:'/__lsm/product-cost',currencyNote:'币种待确认',orderTerm:'工单',
 features:{weighing:true,scopeAnalysis:true,batchAnalysis:false,wageRules:true,valuation:true,efficiency,shiftHours,periodAttribution:'实际完工日期（缺失时依次取实际开工、计划开工、创建日期）'},sourceLink,initialOrderId:S.query.get('order')}))

// ---- 悬浮窗对象：依据全部由代码给出 ----
const report=()=>env.CretasCostScope?.report?.()||null
const orders=()=>report()?.products.flatMap(p=>p.orders)||[]
const money=(v:number|null|undefined,cur:string)=>v==null?null:fmtCost(v)+' '+(cur==='CNY'?'元':'（'+(cur||'币种待确认')+'）')
const src=(o:CostOrder,field:string)=>o.supplementFields?.includes(field)?'cretas' as const:undefined
function orderFacts(o:CostOrder):AiFact[]{const g=gapOf(o),u=unitCost(o)
 return [{label:'工单',value:o.projectCode},{label:'产品',value:o.product},{label:'状态',value:stateLabel(o.status)},
  {label:'良品产出',value:o.finishedGood===null?null:fmtCost(o.finishedGood)+' '+(o.unit||'（单位未填）')},
  {label:'材料费',value:money(o.materialCost,o.currency),source:src(o,'材料费')||(o.materialCost===null?'missing':undefined)},
  {label:'人工费',value:money(o.laborCost,o.currency),source:src(o,'人工费')||(o.laborCost===null?'missing':undefined)},
  {label:'单位已识别直接成本',value:u===null?null:money(u,o.currency)+' / '+o.unit},
  {label:'实际参与人数',value:o.participants,source:src(o,'实际参与名单')||(o.participants===null?'missing':undefined)},
  {label:'人均人工费',value:money(o.perPerson,o.currency)},
  {label:'主要缺口',value:g?g.label+'：'+g.reasons.join('；'):'无'},{label:'找谁',value:g?.owner??'—'},{label:'跟进',value:followLabel(o.followup?.status)}]}
function productFacts(p:CostProduct):AiFact[]{const c=coverage(p.orders),sup=p.orders.some(o=>o.supplementFields?.some(f=>f==='材料费'||f==='人工费'))
 return [{label:'产品',value:p.product+' · '+(p.productCode||'编码待核对')},{label:'阶段 / 单位',value:stageLabel(p.stage)+' / '+(p.unit||'（单位未填）')},
  {label:'可算 / 工单',value:c.ready+' / '+c.total},{label:'平均单位直接成本',value:money(p.weightedUnitCost,p.currency),source:sup?'cretas' as const:undefined},
  {label:'材料 / 单位',value:money(p.weightedMaterialUnitCost,p.currency)},{label:'人工 / 单位',value:money(p.weightedLaborUnitCost,p.currency)},
  {label:'主要缺口',value:c.primary.map(x=>x.label+' '+x.count).join('、')||'无'}]}
function scopeFacts(r:Report):AiFact[]{const all=r.products.flatMap(p=>p.orders),c=coverage(all),sig=signals(r.products),sup=all.filter(o=>o.supplementFields?.length).length
 return [{label:'范围',value:scopeText(r)},{label:'可算 / 有效工单',value:c.ready+' / '+c.total+'（'+fmtPct(c.total?c.ready/c.total:null)+'）'},
  {label:'不能核算主分类',value:c.primary.map(x=>x.label+' '+x.count).join('、')||'无'},{label:'经营信号',value:sig.filter(s=>s.family==='business_signal').length+' 条'},
  {label:'含 Cretas 补充的工单',value:sup,source:sup?'cretas' as const:undefined},{label:'币种',value:r.currencySupplement?r.currencySupplement.value+'（Cretas 补充）':'币种待确认',source:r.currencySupplement?'cretas' as const:undefined}]}
const ruleExplain=(conclusion:string,items:string[]):AiExplain=>({state:'answered',conclusion,items:items.map(text=>({text,source:'system' as const})),rejected:[],note:'仅规则结论：本工作区未接成本模型解读；数字由代码计算，不改任何记录。',at:new Date().toISOString(),objectRev:report()?.sourceRevision})
const scopeKey=(r:Report)=>JSON.stringify(r.filters)
const scopeText=(r:Report)=>[r.dateContext.label,r.filters.keyword?'关键词「'+r.filters.keyword+'」':'',r.filters.unit?'单位 '+r.filters.unit:'',r.filters.scopeProductKey?'指定产品':'',r.filters.attention&&r.filters.attention!=='all'?'关注：'+({missing:'资料待补',readiness:'口径待核',difference:'账面差额可对照'} as Record<string,string>)[r.filters.attention]:''].filter(Boolean).join(' · ')
function registerCost(){
 registerAiObject('cost-order',{noun:'这张工单',placeholder:'例如：这张工单的成本从哪来？缺什么，找谁补？',
  describe:id=>{const o=orders().find(o=>o.id===id);return o?'工单 '+o.projectCode+' · '+o.product:undefined},
  facts:id=>{const o=orders().find(o=>o.id===id);return o?orderFacts(o):[]},
  explain:async id=>{const o=orders().find(o=>o.id===id);if(!o)return ruleExplain('当前筛选里找不到这张工单，请恢复范围后再问。',[]);const g=gapOf(o),u=unitCost(o)
   return ruleExplain(u!==null?'每'+o.unit+'已识别直接成本 '+fmtCost(u)+'（材料＋人工，不含制造费用）。':'暂不能计算单位直接成本：'+(g?.label||'资料或口径待核')+'。',
    [...(g?['找 '+g.owner+'：'+g.reasons.join('；')+'。补齐后：'+g.unlocks+'。']:[]),...(o.supplementFields?.length?['其中 '+o.supplementFields.join('、')+' 来自 Cretas 补充，其余来自黑湖。']:[]),'可能原因一律待核对，不能据此断言浪费或涨价。'])}})
 registerAiObject('cost-product',{noun:'这个产品',placeholder:'例如：这个产品的平均成本怎么算？先核哪张工单？',
  describe:id=>{const p=report()?.products.find(p=>p.key===id);return p?'产品 '+p.product+' · '+(p.unit||'单位待核对'):undefined},
  facts:id=>{const p=report()?.products.find(p=>p.key===id);return p?productFacts(p):[]},
  explain:async id=>{const p=report()?.products.find(p=>p.key===id);if(!p)return ruleExplain('当前筛选里找不到这个产品。',[]);const c=coverage(p.orders),first=c.gapRows[0]
   return ruleExplain(p.weightedUnitCost!==null?'可比较的 '+c.ready+' 张，平均每'+p.unit+'已识别直接成本 '+fmtCost(p.weightedUnitCost)+'。':'这个产品暂无可计算单位成本的工单。',
    [...(c.ready<3&&c.ready>0?['可比样本少于 3 张，只能作核对线索，不判断差异。']:[]),...(first?['先核 '+first.o.projectCode+'（'+first.g.label+'），找 '+first.g.owner+'。']:[])])}})
 registerAiObject('cost-scope',{noun:'当前筛选的成本',placeholder:'例如：哪些产品能比？先补哪一类资料收益最大？',
  describe:id=>{const r=report();if(!r)return undefined;return '成本分析 · '+scopeText(r)+(id===scopeKey(r)?'':'（依据已变）')},
  facts:()=>{const r=report();return r?scopeFacts(r):[]},
  isStale:id=>{const r=report();return !r||id!==scopeKey(r)},
  explain:async()=>{const r=report();if(!r)return ruleExplain('报表尚未读取。',[]);const c=coverage(r.products.flatMap(p=>p.orders)),top=c.primary[0]
   return ruleExplain(c.ready+' / '+c.total+' 张工单可算已识别直接成本（工单数覆盖，不是金额覆盖）。',
    [...(top?['最大的缺口是「'+top.label+'」'+top.count+' 张，找 '+(c.gapRows.find(x=>x.g.code===top.code)?.g.owner||'成本核对人')+'；补齐后：'+(c.gapRows.find(x=>x.g.code===top.code)?.g.unlocks||'')]:[]),'先补资料，再看成本差异；跨产品、跨单位不比较。'])}})}
function focusProject(){const id=S.query.get('order')||S.query.get('project');if(id&&orders().some(o=>o.id===id))setAiObject({kind:'cost-order',id},'成本分析')}
onMounted(()=>{registerCost();env.CretasSupplyAnalysis={
  async openCost(f){const orderId=typeof f.orderId==='string'?f.orderId:'',productKey=typeof f.productKey==='string'?f.productKey:'',r=report()
    if(orderId&&orders().some(o=>o.id===orderId))setAiObject({kind:'cost-order',id:orderId},'成本分析')
    else if(productKey&&r?.products.some(p=>p.key===productKey))setAiObject({kind:'cost-product',id:productKey},'成本分析')
    else if(r)setAiObject({kind:'cost-scope',id:scopeKey(r)},'成本分析')
    else{S.notice='报表尚未读取完成，请稍后再问。';return}
    S.aiOpen=true},
  async refresh(){/* 补充保存后报表已重读；悬浮窗依据每次从报表现取 */},
  panelClose(){S.aiOpen=false}}
  focusProject()})
watch(()=>S.query.toString(),focusProject)
onBeforeUnmount(()=>{delete env.CretasSupplyAnalysis})
</script>
<template><div class="unified-cost cretas-nuxt-surface"><BomPriceAdvice v-if="isBomPrice" :ws="bomWs"/><CostWorkspace v-else :config="config"/></div></template>
