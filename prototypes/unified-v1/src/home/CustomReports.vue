<script setup lang="ts">
// N9 自定义报表＋自定义图表（目标「免去周会」）。规格：unified-spec/D-home-analytics.md D17。
// 数据源字段与汇总全部由代码确定：同单位才相加，缺失不计入合计并单列，0 保留；本页不调用模型。
// 模板与快照只写统一入口 private/（/__unified/reports），不写任何业务数据。两个工作区的模板互相隔离。
import {computed,onMounted,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {S,rs,text,stateLabel} from '../frame/store'
import {fmtNumber,fmtMoney,fmtQty,valueKind,cretasUI} from '../frame/ai-protocol'
type Kind='dim'|'qty'|'money'|'date'
interface Field{key:string;label:string;kind:Kind;get:(r:Record<string,unknown>)=>unknown;unit?:(r:Record<string,unknown>)=>string}
interface Source{id:string;label:string;ws:'lsm'|'ydwm';note:string;fields:Field[]}
interface Filter{field:string;op:'eq'|'contains'|'gte'|'lte';value:string}
interface Template{id?:string;name:string;source:string;fields:string[];groupBy:string;measure:string;agg:'sum'|'count'|'avg';filters:Filter[];chart:'table'|'bar'|'line';period:'none'|'daily'|'weekly'|'monthly';note?:string;updatedAt?:string}
interface Run{id:string;templateId:string;templateName:string;generatedAt:string;sourceRevision:string;sourceLabel:string;columns:string[];rows:(string|number|null)[][];summary:{groups:number;records:number;missing:number;unitSplit:boolean}|null;chart:string}
const ws=computed<'lsm'|'ydwm'>(()=>S.query.get('ws')==='ydwm'?'ydwm':'lsm')
const num=(v:unknown)=>{const k=valueKind(v);return k==='value'||k==='zero'?Number(String(v).replace(/,/g,'')):null}
const day=(v:unknown)=>v==null||v===''?null:String(v).slice(0,10)
const SOURCES:Source[]=[
 {id:'lsm-project',label:'生产工单',ws:'lsm',note:'黑湖只读来源；状态按黑湖工单状态',fields:[
  {key:'projectCode',label:'工单号',kind:'dim',get:r=>r.projectCode},{key:'productName',label:'产品',kind:'dim',get:r=>r.productName},{key:'status',label:'状态',kind:'dim',get:r=>stateLabel(r.status)},
  {key:'planStart',label:'计划开始日',kind:'date',get:r=>day(r.planStartTime)},{key:'planEnd',label:'计划结束日',kind:'date',get:r=>day(r.planEndTime)},
  {key:'planAmount',label:'计划量',kind:'qty',get:r=>r.planAmount,unit:r=>String(r.projectUnit||'')},{key:'good',label:'良品数',kind:'qty',get:r=>r.numberofGoodProducts,unit:r=>String(r.projectUnit||'')},{key:'bad',label:'不良数',kind:'qty',get:r=>r.numberofDefectives,unit:r=>String(r.projectUnit||'')}]},
 {id:'lsm-output',label:'生产报工',ws:'lsm',note:'逐笔报工；审批状态按黑湖口径',fields:[
  {key:'productName',label:'产品',kind:'dim',get:r=>r.productName},{key:'approval',label:'审批状态',kind:'dim',get:r=>stateLabel(r.approvalStatus,true)},{key:'reportDay',label:'报工日期',kind:'date',get:r=>day(r.starttimeofReport)},
  {key:'good',label:'良品数',kind:'qty',get:r=>r.numberofgoodProducts,unit:r=>String(r.stockUnit||'')},{key:'bad',label:'不良数',kind:'qty',get:r=>r.numberofDefectives,unit:r=>String(r.stockUnit||'')}]},
 {id:'lsm-cost',label:'工单直接成本',ws:'lsm',note:'已记录材料与人工；币种待确认；不可比较的工单单位成本为缺失',fields:[
  {key:'product',label:'产品',kind:'dim',get:r=>r.product},{key:'projectCode',label:'工单号',kind:'dim',get:r=>r.projectCode},{key:'status',label:'状态',kind:'dim',get:r=>stateLabel(r.status)},
  {key:'finishedGood',label:'工单良品',kind:'qty',get:r=>r.finishedGood,unit:r=>String(r.unit||'')},{key:'materialCost',label:'已记录材料费',kind:'money',get:r=>r.materialCost},{key:'laborCost',label:'已记录人工费',kind:'money',get:r=>r.laborCost},{key:'unitCost',label:'单位直接成本',kind:'money',get:r=>r.unitCostAvailable?r.unitIdentifiedDirectCost:null}]},
 {id:'ydwm-plan',label:'生产计划',ws:'ydwm',note:'味美首页只读投影；含取消与关闭记录，状态可筛',fields:[
  {key:'number',label:'计划号',kind:'dim',get:r=>r.number},{key:'product',label:'成品',kind:'dim',get:r=>r.product},{key:'status',label:'状态',kind:'dim',get:r=>r.statusLabel},{key:'due',label:'交期',kind:'date',get:r=>r.due},
  {key:'overdue',label:'是否超期',kind:'dim',get:r=>r.overdue?'超期':'未超期'},{key:'quantity',label:'计划量',kind:'qty',get:r=>r.quantity,unit:r=>String(r.unit||'')},{key:'daysLate',label:'超期天数',kind:'qty',get:r=>r.overdue?r.daysLate:null,unit:()=>'天'}]}
]
const sources=computed(()=>SOURCES.filter(s=>s.ws===ws.value))
const ydwmRows=ref<Record<string,unknown>[]>([]),ydwmAt=ref(''),loadErr=ref('')
function rowsOf(src:string):Record<string,unknown>[]{if(src==='lsm-project')return rs('project');if(src==='lsm-output')return rs('output');if(src==='lsm-cost')return (S.source?.costs||[]) as unknown as Record<string,unknown>[];if(src==='ydwm-plan')return ydwmRows.value;return []}
const revision=computed(()=>ws.value==='lsm'?(S.source?.revision||''):ydwmAt.value)
const blank=():Template=>({name:'',source:sources.value[0]?.id||'',fields:[],groupBy:'',measure:'',agg:'count',filters:[],chart:'bar',period:'weekly'})
const store=ref<{version:number;templates:Template[];runs:Run[]}>({version:0,templates:[],runs:[]}),editing=ref<Template|null>(null),viewRun=ref<Run|null>(null),busy=ref(false),err=ref(''),onlyDue=ref(false),preview=ref<ReturnType<typeof compute>|null>(null)
const src=computed(()=>SOURCES.find(s=>s.id===editing.value?.source))
const fieldOf=(s:Source|undefined,k:string)=>s?.fields.find(f=>f.key===k)
function match(f:Field,r:Record<string,unknown>,flt:Filter){const v=f.get(r);if(flt.op==='contains')return String(v??'').includes(flt.value);if(f.kind==='qty'||f.kind==='money'){const a=num(v),b=Number(flt.value);if(a===null||!Number.isFinite(b))return false;return flt.op==='eq'?a===b:flt.op==='gte'?a>=b:a<=b}const a=String(v??'');return flt.op==='eq'?a===flt.value:flt.op==='gte'?a>=flt.value:a<=flt.value}
// 汇总：按分组字段（及计量单位）分组；缺失不计入合计；不同单位不相加
function compute(t:Template){const s=SOURCES.find(x=>x.id===t.source);if(!s)throw Error('数据源不存在');let rows=rowsOf(t.source);for(const flt of t.filters){const f=fieldOf(s,flt.field);if(f&&flt.value!=='')rows=rows.filter(r=>match(f,r,flt))}
 const g=fieldOf(s,t.groupBy),m=fieldOf(s,t.measure),useUnit=!!(m&&m.unit&&t.agg!=='count'),groups=new Map<string,{key:string;unit:string;n:number;valid:number;missing:number;sum:number}>()
 for(const r of rows){const key=g?String(g.get(r)??'未填写'):'全部',unit=useUnit&&m?.unit?(m.unit(r)||'单位未填'):'',k=key+'\u0000'+unit;let x=groups.get(k);if(!x){x={key,unit,n:0,valid:0,missing:0,sum:0};groups.set(k,x)}x.n++;if(m&&t.agg!=='count'){const v=num(m.get(r));if(v===null)x.missing++;else{x.valid++;x.sum+=v}}}
 const out=[...groups.values()].sort((a,b)=>a.key.localeCompare(b.key,'zh-CN')||a.unit.localeCompare(b.unit))
 const valueOf=(x:{n:number;valid:number;sum:number})=>t.agg==='count'?x.n:t.agg==='sum'?(x.valid?x.sum:null):(x.valid?x.sum/x.valid:null)
 const label=t.agg==='count'?'记录数':(t.agg==='sum'?'合计 ':'平均 ')+(m?.label||'')
 const columns=[g?.label||'范围',...(useUnit?['单位']:[]),label,'记录数',...(t.agg!=='count'?['缺失（未计入）']:[])]
 const table=out.map(x=>[x.key,...(useUnit?[x.unit]:[]),valueOf(x),x.n,...(t.agg!=='count'?[x.missing]:[])] as (string|number|null)[])
 const detail=t.fields.length?rows.slice(0,200).map(r=>t.fields.map(k=>{const f=fieldOf(s,k);if(!f)return '';const v=f.get(r);return f.kind==='qty'?fmtQty(v,f.unit?.(r)):f.kind==='money'?fmtMoney(v,{currency:s.ws==='lsm'?'（币种待确认）':'元'}):text(v)})):[]
 return {columns,table,detail,detailColumns:t.fields.map(k=>fieldOf(s,k)?.label||k),valueIndex:useUnit?2:1,unitSplit:useUnit&&new Set(out.map(x=>x.unit)).size>1,summary:{groups:out.length,records:rows.length,missing:out.reduce((n,x)=>n+x.missing,0),unitSplit:useUnit&&new Set(out.map(x=>x.unit)).size>1},kind:m?.kind||'qty',money:m?.kind==='money'}}
function cell(v:unknown,i:number,res:{valueIndex:number;money:boolean;columns:string[]}){if(i===res.valueIndex)return v===null?'—（无有效值）':res.money?fmtMoney(v,{currency:ws.value==='lsm'?'（币种待确认）':'元'}):fmtNumber(v);if(typeof v==='number')return fmtNumber(v,{digits:0});return text(v)}
// 周期：到期 = 设置了周期且本周期内还没有生成过
function periodStart(p:string){const d=new Date();d.setHours(0,0,0,0);if(p==='weekly'){const w=(d.getDay()+6)%7;d.setDate(d.getDate()-w)}else if(p==='monthly')d.setDate(1);return d}
const lastRun=(id?:string)=>store.value.runs.find(r=>r.templateId===id)
const isDue=(t:Template)=>t.period!=='none'&&(!lastRun(t.id)||new Date(lastRun(t.id)!.generatedAt)<periodStart(t.period))
const PERIOD:Record<string,string>={none:'不定期',daily:'每天',weekly:'每周一',monthly:'每月 1 日'}
const listed=computed(()=>store.value.templates.filter(t=>!onlyDue.value||isDue(t)))
// 演示会话（W2）：frame 地址或顶层地址带 acceptanceSession 时，模板与快照读写该会话的保存区（reports-<工作区>-<会话>.json）
const session=computed(()=>{const ok=(v:string|null)=>v&&/^test-[a-z0-9-]{8,64}$/.test(v)?v:'';let v=ok(S.query.get('acceptanceSession'));if(!v)try{v=ok(new URLSearchParams(window.top!.location.search).get('acceptanceSession'))}catch{/* 跨域时只认 frame 地址 */}return v})
const sessHeaders=():Record<string,string>=>session.value?{'x-prototype-test-session':session.value}:{}
async function call(body?:unknown){const r=await fetch('/__unified/reports?ws='+ws.value,{method:body?'POST':'GET',headers:body?{'Content-Type':'application/json','x-prototype-action':'explicit',...sessHeaders()}:sessHeaders(),body:body?JSON.stringify({...body as object,expectedVersion:store.value.version}):undefined});const b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'保存失败（'+r.status+'）');store.value=b.data;return b.data}
async function loadAll(){err.value='';try{await call()}catch(e){err.value=e instanceof Error?e.message:'读取失败'}if(ws.value==='ydwm'){try{const r=await fetch('/__account/home',{headers:sessHeaders()}),b=await r.json();if(!r.ok||!Array.isArray(b.plans))throw Error(b.message||cretasUI().ai.sourceErrorText(r.status,'味美首页投影'));ydwmRows.value=b.plans;ydwmAt.value=b.updatedAt||''}catch(e){loadErr.value=e instanceof Error?e.message:'味美数据读取失败'}}}
function startNew(){viewRun.value=null;editing.value=blank();preview.value=null}
function edit(t:Template){viewRun.value=null;editing.value=JSON.parse(JSON.stringify(t));preview.value=null}
function doPreview(){if(!editing.value)return;try{preview.value=compute(editing.value);err.value=''}catch(e){err.value=e instanceof Error?e.message:'预览失败'}}
function save(){const t=editing.value;if(!t)return;if(!t.name.trim()){err.value='请填写报表名称';return}doPreview();if(!preview.value)return;const C=cretasUI(),box=document.createElement('div');box.innerHTML='<p>保存报表模板「'+C.escape(t.name)+'」：数据源 '+C.escape(src.value?.label||'')+'，'+C.escape(PERIOD[t.period])+'生成；按当前数据预览 '+preview.value.summary.groups+' 组 / '+preview.value.summary.records+' 条记录。</p><p class="cs-supplement-note">只保存模板配置，不改业务数据；快照需点「生成本期」。</p>'
 C.dialog({title:'确认保存报表模板',content:box,actions:[{label:'返回修改',variant:'secondary',onClick:h=>{h.close({force:true})}},{label:'确认保存',variant:'primary',onClick:async h=>{const before=t.id;await call({op:'save',template:t});const saved=before?store.value.templates.find(x=>x.id===before):store.value.templates[store.value.templates.length-1];if(!saved||saved.name!==t.name.trim())throw Error(C.ai.STATES.mismatch.label);editing.value=JSON.parse(JSON.stringify(saved));h.close({force:true});C.toast({message:'已保存报表模板 · 回读一致'})}}]})}
async function generate(t:Template){busy.value=true;err.value='';try{const res=compute(t);const rows=res.table.map(r=>r.map((v,i)=>i===res.valueIndex&&typeof v==='number'?Math.round(v*100)/100:v));const s=SOURCES.find(x=>x.id===t.source);await call({op:'run',id:t.id,run:{rows,columns:res.columns,summary:res.summary,sourceRevision:revision.value,sourceLabel:(s?.label||'')+' · '+(ws.value==='lsm'?'黑湖快照 ':'味美投影 ')+String(revision.value).slice(0,12)}});const r=lastRun(t.id);if(!r)throw Error('生成后未读回快照');viewRun.value=r;editing.value=null;cretasUI().toast({message:'已生成「'+t.name+'」本期快照 · 回读一致'})}catch(e){err.value=e instanceof Error?e.message:'生成失败'}finally{busy.value=false}}
function remove(t:Template){const C=cretasUI();C.dialog({title:'删除报表模板',content:'<p>删除「'+C.escape(t.name)+'」模板。已生成的快照保留，可继续查看。</p>',actions:[{label:'取消',variant:'secondary',onClick:h=>{h.close({force:true})}},{label:'确认删除',variant:'danger',onClick:async h=>{await call({op:'delete',id:t.id});if(editing.value?.id===t.id)editing.value=null;h.close({force:true});C.toast({message:'已删除模板，快照保留'})}}]})}
// 图表：只画一个单位的数据，多单位时按单位分开画
function bars(cols:string[],rows:(string|number|null)[][],vi:number,unitIdx:number){const groups=new Map<string,{label:string;v:number}[]>();for(const r of rows){const v=r[vi];if(typeof v!=='number')continue;const u=unitIdx>=0?String(r[unitIdx]):'';(groups.get(u)||groups.set(u,[]).get(u)!).push({label:String(r[0]),v})}return [...groups.entries()].map(([u,items])=>({unit:u,items:items.slice(0,24),max:Math.max(1,...items.map(i=>Math.abs(i.v)))}))}
const runChart=computed(()=>viewRun.value?bars(viewRun.value.columns,viewRun.value.rows,viewRun.value.columns.indexOf('单位')>=0?2:1,viewRun.value.columns.indexOf('单位')):[])
const previewChart=computed(()=>preview.value?bars(preview.value.columns,preview.value.table,preview.value.valueIndex,preview.value.columns.indexOf('单位')):[])
watch(()=>editing.value?.source,(n,o)=>{if(editing.value&&o&&n!==o){editing.value.fields=[];editing.value.groupBy='';editing.value.measure='';editing.value.filters=[];preview.value=null}})
watch(ws,()=>{editing.value=null;viewRun.value=null;loadAll()})
// 同一页面切换演示会话（地址里的 acceptanceSession 变了）时重新读取该会话的模板与快照（审计轻微项）
watch(session,()=>{editing.value=null;viewRun.value=null;store.value={version:0,templates:[],runs:[]};loadAll()})
onMounted(loadAll)
const fmtTime=(v:string)=>v?new Date(v).toLocaleString('zh-CN',{hour12:false}):'—'
</script>
<template>
<section class="cr-page">
<header class="cr-head"><div><p>数据与分析 · {{ws==='lsm'?'六膳门':'味美'}}工作区</p><h1>自定义报表</h1><small>把周会要看的数字固定成模板，到期生成一份快照留档；本页不调用模型，不改业务数据。<template v-if="session"> · 演示会话 {{session}}</template></small></div><div class="actions"><UButton size="sm" @click="startNew()">新建报表</UButton></div></header>
<p v-if="err" role="alert" class="error">{{err}}</p><p v-if="loadErr" role="alert" class="error">{{loadErr}}</p>
<div class="cr-body">
<aside class="cr-list" aria-label="报表模板">
<div class="cr-list-head"><b>模板 {{store.templates.length}}</b><label><input type="checkbox" v-model="onlyDue"> 只看到期待生成</label></div>
<p v-if="!listed.length" class="muted">{{store.templates.length?'没有到期待生成的模板。':'还没有报表模板。点「新建报表」把周会要看的数字固定下来。'}}</p>
<div v-for="t in listed" :key="t.id" class="cr-item" :class="{on:editing?.id===t.id}"><button type="button" class="cr-item-main" @click="edit(t)"><strong>{{t.name}}</strong><small>{{SOURCES.find(s=>s.id===t.source)?.label}} · {{PERIOD[t.period]}} · 上次 {{lastRun(t.id)?fmtTime(lastRun(t.id)!.generatedAt):'未生成'}}</small></button><span v-if="isDue(t)" class="cs-badge" data-tone="warning">到期待生成</span><div class="cr-item-actions"><UButton size="xs" color="neutral" variant="outline" :loading="busy" @click="generate(t)">生成本期</UButton><UButton size="xs" color="neutral" variant="ghost" @click="remove(t)">删除</UButton></div></div>
<div class="cr-list-head"><b>已生成快照 {{store.runs.length}}</b></div>
<button v-for="r in store.runs.slice(0,30)" :key="r.id" type="button" class="cr-run" :class="{on:viewRun?.id===r.id}" @click="viewRun=r;editing=null"><span>{{r.templateName}}</span><small>{{fmtTime(r.generatedAt)}}</small></button>
</aside>
<main class="cr-main">
<template v-if="editing">
<div class="cr-form">
<label>报表名称<input v-model="editing.name" maxlength="60" placeholder="例如：本周已结束工单产量"></label>
<label>数据源<select v-model="editing.source"><option v-for="s in sources" :key="s.id" :value="s.id">{{s.label}}</option></select><small>{{src?.note}}</small></label>
<label>分组字段<select v-model="editing.groupBy"><option value="">不分组（全部）</option><option v-for="f in src?.fields.filter(f=>f.kind==='dim'||f.kind==='date')" :key="f.key" :value="f.key">{{f.label}}</option></select></label>
<label>汇总方式<select v-model="editing.agg"><option value="count">记录数</option><option value="sum">合计</option><option value="avg">平均</option></select></label>
<label v-if="editing.agg!=='count'">汇总字段<select v-model="editing.measure"><option value="">请选择</option><option v-for="f in src?.fields.filter(f=>f.kind==='qty'||f.kind==='money')" :key="f.key" :value="f.key">{{f.label}}</option></select><small>数量按单位分开汇总，不同单位不相加；缺失不计入并单列。</small></label>
<label>图表<select v-model="editing.chart"><option value="bar">柱状图</option><option value="line">折线图</option><option value="table">只要表格</option></select></label>
<label>生成周期<select v-model="editing.period"><option value="weekly">每周一</option><option value="daily">每天</option><option value="monthly">每月 1 日</option><option value="none">不定期</option></select><small>没有后台定时任务：到期在列表标「到期待生成」，点「生成本期」留档。</small></label>
<fieldset class="cr-fields"><legend>明细列（可选，最多 20 列）</legend><label v-for="f in src?.fields" :key="f.key" class="cr-check"><input type="checkbox" :value="f.key" v-model="editing.fields">{{f.label}}</label></fieldset>
<fieldset class="cr-filters"><legend>筛选</legend><div v-for="(flt,i) in editing.filters" :key="i" class="cr-filter"><select v-model="flt.field" aria-label="筛选字段"><option v-for="f in src?.fields" :key="f.key" :value="f.key">{{f.label}}</option></select><select v-model="flt.op" aria-label="条件"><option value="eq">等于</option><option value="contains">包含</option><option value="gte">不小于</option><option value="lte">不大于</option></select><input v-model="flt.value" aria-label="筛选值" placeholder="值（日期用 2026-11-01）"><UButton size="xs" color="neutral" variant="ghost" @click="editing.filters.splice(i,1)">移除</UButton></div><UButton size="xs" color="neutral" variant="outline" @click="editing.filters.push({field:src?.fields[0]?.key||'',op:'eq',value:''})">添加筛选</UButton></fieldset>
<div class="actions"><UButton size="sm" color="neutral" variant="outline" @click="doPreview()">预览</UButton><UButton size="sm" @click="save()">保存模板</UButton><UButton v-if="editing.id" size="sm" color="neutral" variant="outline" :loading="busy" @click="generate(editing)">生成本期</UButton></div>
</div>
<section v-if="preview" class="cr-result" aria-label="预览结果"><h2>预览 <small>{{preview.summary.groups}} 组 · {{preview.summary.records}} 条记录<template v-if="preview.summary.missing"> · 缺失 {{preview.summary.missing}} 条未计入</template><template v-if="preview.summary.unitSplit"> · 不同单位已分开</template> · 未保存</small></h2>
<div v-if="editing.chart!=='table'" class="cr-chart"><div v-for="c in previewChart" :key="c.unit" class="cr-chart-block"><small v-if="c.unit">单位：{{c.unit}}</small><svg v-if="editing.chart==='bar'" :viewBox="'0 0 600 '+(c.items.length*22+4)" role="img" :aria-label="'柱状图 '+c.unit"><g v-for="(it,i) in c.items" :key="i"><text x="0" :y="i*22+15" font-size="11">{{it.label.slice(0,14)}}</text><rect x="160" :y="i*22+4" :width="Math.max(1,Math.abs(it.v)/c.max*380)" height="14" rx="3" class="cr-bar"/><text :x="166+Math.abs(it.v)/c.max*380" :y="i*22+15" font-size="11">{{fmtNumber(it.v)}}</text></g></svg><svg v-else viewBox="0 0 600 180" role="img" :aria-label="'折线图 '+c.unit"><polyline :points="c.items.map((it,i)=>(20+i*(560/Math.max(1,c.items.length-1)))+','+(160-it.v/c.max*140)).join(' ')" class="cr-line"/><text v-for="(it,i) in c.items" :key="i" :x="20+i*(560/Math.max(1,c.items.length-1))" y="176" font-size="10" text-anchor="middle">{{it.label.slice(0,6)}}</text></svg></div></div>
<table class="cr-table"><thead><tr><th v-for="c in preview.columns" :key="c">{{c}}</th></tr></thead><tbody><tr v-for="(r,i) in preview.table" :key="i"><td v-for="(v,j) in r" :key="j" :class="{n:typeof v==='number'||v===null}">{{cell(v,j,preview)}}</td></tr><tr v-if="!preview.table.length"><td :colspan="preview.columns.length" class="muted">当前筛选没有记录。</td></tr></tbody></table>
<details v-if="preview.detail.length"><summary>明细（前 200 条）</summary><table class="cr-table"><thead><tr><th v-for="c in preview.detailColumns" :key="c">{{c}}</th></tr></thead><tbody><tr v-for="(r,i) in preview.detail" :key="i"><td v-for="(v,j) in r" :key="j">{{v}}</td></tr></tbody></table></details>
</section>
</template>
<section v-else-if="viewRun" class="cr-result" aria-label="快照"><h2>{{viewRun.templateName}} <small>生成于 {{fmtTime(viewRun.generatedAt)}} · {{viewRun.sourceLabel}} · 快照不随来源变化</small></h2>
<div v-if="viewRun.chart!=='table'" class="cr-chart"><div v-for="c in runChart" :key="c.unit" class="cr-chart-block"><small v-if="c.unit">单位：{{c.unit}}</small><svg :viewBox="'0 0 600 '+(c.items.length*22+4)" role="img" aria-label="快照图表"><g v-for="(it,i) in c.items" :key="i"><text x="0" :y="i*22+15" font-size="11">{{it.label.slice(0,14)}}</text><rect x="160" :y="i*22+4" :width="Math.max(1,Math.abs(it.v)/c.max*380)" height="14" rx="3" class="cr-bar"/><text :x="166+Math.abs(it.v)/c.max*380" :y="i*22+15" font-size="11">{{fmtNumber(it.v)}}</text></g></svg></div></div>
<table class="cr-table"><thead><tr><th v-for="c in viewRun.columns" :key="c">{{c}}</th></tr></thead><tbody><tr v-for="(r,i) in viewRun.rows" :key="i"><td v-for="(v,j) in r" :key="j" :class="{n:typeof v==='number'||v===null}">{{v===null?'—（无有效值）':typeof v==='number'?fmtNumber(v,{digits:j===(viewRun.columns.indexOf('单位')>=0?2:1)?2:0}):v}}</td></tr></tbody></table>
<p class="muted" v-if="viewRun.summary">{{viewRun.summary.groups}} 组 · {{viewRun.summary.records}} 条记录<template v-if="viewRun.summary.missing"> · 缺失 {{viewRun.summary.missing}} 条未计入</template></p>
</section>
<p v-else class="muted cr-empty">选择左侧模板编辑或生成，或点「新建报表」。快照生成后可在左侧回看。</p>
</main>
</div>
</section>
</template>
<style scoped>
.cr-page{display:grid;gap:12px}
.cr-head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.cr-head p{margin:0;font-size:12px;color:var(--cs-muted)}.cr-head h1{margin:2px 0;font-size:1.375rem}.cr-head small{color:var(--cs-muted);font-size:.75rem}
.cr-body{display:grid;grid-template-columns:300px minmax(0,1fr);gap:12px;align-items:start}
.cr-list,.cr-main{background:var(--cs-surface);border:1px solid var(--cs-border);border-radius:10px;padding:12px}
.cr-list-head{display:flex;justify-content:space-between;align-items:center;font-size:12px;color:var(--cs-muted);margin:4px 0 8px}
.cr-item{border:1px solid var(--cs-border);border-radius:8px;padding:8px;margin-bottom:8px;display:grid;gap:6px}.cr-item.on{border-color:var(--cs-primary)}
.cr-item-main{border:0;background:none;text-align:left;cursor:pointer;display:grid;gap:2px;padding:0}.cr-item-main strong{font-size:.875rem}.cr-item-main small{font-size:.75rem;color:var(--cs-muted)}
.cr-item-actions{display:flex;gap:6px}
.cr-run{display:flex;justify-content:space-between;width:100%;border:0;background:none;padding:6px 4px;font-size:.8125rem;cursor:pointer;border-radius:6px}.cr-run:hover,.cr-run.on{background:var(--cs-primary-soft)}.cr-run small{color:var(--cs-muted)}
.cr-form{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:10px 16px}
.cr-form label{display:grid;gap:4px;font-size:.8125rem}.cr-form small{font-size:.75rem;color:var(--cs-muted)}
.cr-form input,.cr-form select{height:32px;border:1px solid var(--cs-border);border-radius:6px;padding:0 8px;font-size:.875rem;background:var(--cs-surface)}
.cr-fields,.cr-filters{grid-column:1/-1;border:1px solid var(--cs-border);border-radius:8px;padding:8px 12px;display:flex;flex-wrap:wrap;gap:6px 14px}
.cr-fields legend,.cr-filters legend{font-size:12px;color:var(--cs-muted);padding:0 4px}
.cr-check{display:flex!important;align-items:center;gap:4px;grid-template-columns:none}
.cr-filter{display:flex;gap:6px;align-items:center;width:100%}
.cr-form .actions{grid-column:1/-1;display:flex;gap:8px;justify-content:flex-end}
.cr-result{margin-top:14px}.cr-result h2{font-size:1rem;margin:0 0 8px}.cr-result h2 small{font-weight:400;font-size:.75rem;color:var(--cs-muted);margin-left:6px}
.cr-chart{display:grid;gap:10px;margin-bottom:10px}.cr-chart svg{width:100%;max-width:760px;height:auto}
.cr-bar{fill:var(--cs-primary,#1b65a8)}.cr-line{fill:none;stroke:var(--cs-primary,#1b65a8);stroke-width:2}
.cr-table{width:100%;border-collapse:collapse;font-size:.875rem;font-variant-numeric:tabular-nums}.cr-table th,.cr-table td{padding:6px 8px;border-bottom:1px solid var(--cs-border);text-align:left;line-height:20px}.cr-table th{font-weight:600;color:var(--cs-muted);font-size:.8125rem}.cr-table td.n{text-align:right}
.cr-empty{padding:24px 0}
.muted{color:var(--cs-muted);font-size:.8125rem}
@media(max-width:900px){.cr-body{grid-template-columns:1fr}}
</style>
