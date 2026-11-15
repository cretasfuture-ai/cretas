<script setup lang="ts">
// 第八批 R40：AI 经营报告（周报 / 月报）。
// 事实表完全由规则生成：每个数字都带来源说明和「依据 N 条」，点开能回到对应记录（六膳门可再点开记录详情）。
// 模型解读只在用户点击后请求；模型不可用时只显示事实表。留档只写统一入口 private/，不写业务数据。
import {computed,onMounted,reactive,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {S,rs,text,inspect,stateLabel} from '../frame/store'
import {fmtNumber,fmtMoney,fmtQty,fmtPct,cretasUI} from '../frame/ai-protocol'
type Kind='count'|'qty'|'money'|'pct'
interface Rec{kind:string;row?:Record<string,unknown>;line:string}
interface Metric{key:string;group:string;label:string;kind:Kind;unit:string;value:number|null;records:Rec[];source:string;note:string}
interface Saved{id:string;kind:'week'|'month';from:string;to:string;label:string;generatedAt:string;sourceRevision:string;sourceLabel:string;rows:{group:string;label:string;value:string|null;prev:string|null;source:string;count:number;note:string}[]}
const ws=computed<'lsm'|'ydwm'>(()=>S.query.get('ws')==='ydwm'?'ydwm':'lsm')
const kind=ref<'week'|'month'>('week'),offset=ref(0)
const session=computed(()=>{const ok=(v:string|null)=>v&&/^test-[a-z0-9-]{8,64}$/.test(v)?v:'';let v=ok(S.query.get('acceptanceSession'));if(!v)try{v=ok(new URLSearchParams(window.top!.location.search).get('acceptanceSession'))}catch{/* 跨域时只认 frame 地址 */}return v})
const sess=():Record<string,string>=>session.value?{'x-prototype-test-session':session.value}:{}
// 今天：六膳门 = 快照日（R28）；味美 = 北京时间今天
const bjDay=(d:Date)=>new Date(d.getTime()+8*3600e3).toISOString().slice(0,10)
const ydwmAt=ref(''),ydwmDay=ref(''),ydwmPlans=ref<Record<string,unknown>[]>([]),loadErr=ref('')
const today=computed(()=>ws.value==='lsm'?(S.source?.checkedAt?bjDay(new Date(S.source.checkedAt)):bjDay(new Date())):(ydwmDay.value||bjDay(new Date())))
const addDays=(d:string,n:number)=>{const x=new Date(d+'T00:00:00Z');x.setUTCDate(x.getUTCDate()+n);return x.toISOString().slice(0,10)}
function periodOf(k:'week'|'month',off:number){
  const t=today.value
  if(k==='week'){const dow=(new Date(t+'T00:00:00Z').getUTCDay()+6)%7,from=addDays(t,-dow+off*7);return {from,to:addDays(from,6)}}
  const d=new Date(t+'T00:00:00Z'),y=d.getUTCFullYear(),m=d.getUTCMonth()+off,a=new Date(Date.UTC(y,m,1)),b=new Date(Date.UTC(y,m+1,0))
  return {from:a.toISOString().slice(0,10),to:b.toISOString().slice(0,10)}
}
const cur=computed(()=>periodOf(kind.value,offset.value)),prev=computed(()=>periodOf(kind.value,offset.value-1))
const inP=(v:unknown,p:{from:string;to:string})=>{const d=v==null||v===''?'':String(v).slice(0,10);return !!d&&d>=p.from&&d<=p.to}
const n=(v:unknown)=>{if(v==null||v==='')return null;const x=Number(String(v).replace(/,/g,''));return Number.isFinite(x)?x:null}
const isNowP=(p:{from:string;to:string})=>today.value>=p.from&&today.value<=p.to
const PLABEL=(p:{from:string;to:string})=>p.from.slice(5)+' 至 '+p.to.slice(5)
// —— 规则：由来源记录生成指标（不调用模型）——
function lsmMetrics(p:{from:string;to:string}):Metric[]{
  const out:Metric[]=[],P=rs('project'),O=rs('output'),costs=S.source?.costs||[]
  const pr=(r:Record<string,unknown>)=>text(r.projectCode)+' · '+text(r.productName)+' · '+stateLabel(r.status)
  const started=P.filter(r=>inP(r.planStartTime,p)),ended=P.filter(r=>String(r.status)==='20'&&inP(r.actualEndTime,p))
  out.push({key:'p-start',group:'工单',label:'计划开始的工单',kind:'count',unit:'',value:started.length,records:started.map(r=>({kind:'project',row:r,line:pr(r)})),source:'黑湖 · 生产工单（计划开始日落在本期）',note:''})
  out.push({key:'p-end',group:'工单',label:'实际结束的工单',kind:'count',unit:'',value:ended.length,records:ended.map(r=>({kind:'project',row:r,line:pr(r)})),source:'黑湖 · 生产工单（状态=已结束，实际结束日落在本期）',note:''})
  const running=P.filter(r=>String(r.status)==='10'),now=isNowP(p)
  out.push({key:'p-run',group:'工单',label:'仍在执行中的工单',kind:'count',unit:'',value:now?running.length:null,records:now?running.map(r=>({kind:'project',row:r,line:pr(r)})):[],source:'黑湖 · 生产工单（状态=执行中，取快照日当时）',note:now?'':'黑湖快照只有当前状态，往期不回算'})
  const outs=O.filter(r=>inP(r.starttimeofReport,p)),units=[...new Set(outs.map(r=>String(r.stockUnit||'单位未填')))].sort()
  const orec=(rows:Record<string,unknown>[])=>rows.map(r=>({kind:'output',row:r,line:text(r.productName)+' · '+String(r.starttimeofReport||'').slice(0,16)+' · 良 '+text(r.numberofgoodProducts)+' / 不良 '+text(r.numberofDefectives)}))
  out.push({key:'o-n',group:'报工',label:'报工笔数',kind:'count',unit:'',value:outs.length,records:orec(outs),source:'黑湖 · 生产报工（报工开始日落在本期）',note:''})
  for(const u of units){
    const rows=outs.filter(r=>String(r.stockUnit||'单位未填')===u);let good=0,bad=0,miss=0
    for(const r of rows){const g=n(r.numberofgoodProducts),b=n(r.numberofDefectives);if(g===null)miss++;else good+=g;if(b!==null)bad+=b}
    out.push({key:'o-good-'+u,group:'报工',label:'良品量',kind:'qty',unit:u,value:rows.length?good:null,records:orec(rows),source:'黑湖 · 生产报工（同单位才相加）',note:miss?'有 '+miss+' 笔良品数缺失，未计入':''})
    out.push({key:'o-bad-'+u,group:'报工',label:'不良量',kind:'qty',unit:u,value:rows.length?bad:null,records:orec(rows),source:'黑湖 · 生产报工（同单位才相加）',note:''})
    out.push({key:'o-rate-'+u,group:'报工',label:'不良率',kind:'pct',unit:u,value:good+bad>0?bad/(good+bad):null,records:orec(rows),source:'不良量 ÷（良品量 + 不良量），同单位',note:good+bad>0?'':'本期没有产出，不算比率'})
  }
  const endedIds=new Set(ended.map(r=>String(r.id))),cs=costs.filter(c=>endedIds.has(String(c.projectId)))
  const sumC=(k:string)=>{let s=0,m=0,c=0;for(const x of cs){const v=n((x as Record<string,unknown>)[k]);if(v===null)m++;else{s+=v;c++}}return {s,m,c}}
  for(const [k,label] of [['materialCost','已记录材料费'],['laborCost','已记录人工费']] as const){const r=sumC(k)
    out.push({key:'c-'+k,group:'成本',label:'本期结束工单 · '+label,kind:'money',unit:'',value:r.c?r.s:null,records:cs.map(c=>{const pj=P.find(x=>String(x.id)===String(c.projectId));return {kind:'project',row:pj,line:text(c.projectCode)+' · '+text(c.product)+' · '+label+' '+text((c as Record<string,unknown>)[k])}}),source:'黑湖 · 已记录成本（币种待确认，不含未记录项）',note:r.m?'有 '+r.m+' 张工单该项缺失，未计入':''})}
  return out
}
function ydwmMetrics(p:{from:string;to:string}):Metric[]{
  const rows=ydwmPlans.value.filter(r=>inP(r.due,p)),pr=(r:Record<string,unknown>)=>text(r.number)+' · '+text(r.product)+' · '+text(r.statusLabel)+' · 交期 '+text(r.due)
  const rec=(a:Record<string,unknown>[])=>a.map(r=>({kind:'plan',row:r,line:pr(r)})),late=rows.filter(r=>r.overdue)
  const out:Metric[]=[{key:'y-due',group:'生产计划',label:'交期落在本期的计划',kind:'count',unit:'',value:rows.length,records:rec(rows),source:'味美 · 生产计划（首页只读投影，交期落在本期）',note:''},
   {key:'y-late',group:'生产计划',label:'其中已超期',kind:'count',unit:'',value:isNowP(p)||p.to<today.value?late.length:null,records:rec(late),source:'味美 · 生产计划（超期标记取投影生成时）',note:isNowP(p)||p.to<today.value?'':'未到期的周期不算超期'}]
  const units=[...new Set(rows.map(r=>String(r.unit||'单位未填')))].sort()
  for(const u of units){const a=rows.filter(r=>String(r.unit||'单位未填')===u);let s=0,m=0;for(const r of a){const v=n(r.quantity);if(v===null)m++;else s+=v}
    out.push({key:'y-q-'+u,group:'生产计划',label:'计划量',kind:'qty',unit:u,value:a.length?s:null,records:rec(a),source:'味美 · 生产计划（同单位才相加）',note:m?'有 '+m+' 条计划量缺失，未计入':''})}
  return out
}
const build=(p:{from:string;to:string})=>ws.value==='lsm'?lsmMetrics(p):ydwmMetrics(p)
const fmtV=(m:{kind:Kind;unit:string},v:number|null)=>v===null?'—':m.kind==='count'?fmtNumber(v,{digits:0}):m.kind==='qty'?fmtQty(v,m.unit):m.kind==='money'?fmtMoney(v,{currency:'（币种待确认）'}):fmtPct(v*100)
const rows=computed(()=>{const a=build(cur.value),b=new Map(build(prev.value).map(m=>[m.key,m]));return a.map(m=>{const pv=b.get(m.key)?.value??null;return {...m,prevValue:pv,cur:fmtV(m,m.value),prev:fmtV(m,pv),delta:m.value!==null&&pv!==null?(m.kind==='pct'?(m.value-pv)*100:m.value-pv):null}})})
const groups=computed(()=>[...new Set(rows.value.map(r=>r.group))])
const deltaText=(r:{kind:Kind;delta:number|null;unit:string})=>r.delta===null?'—':(r.delta>0?'+':r.delta<0?'−':'')+(r.kind==='pct'?Math.abs(r.delta).toFixed(1)+' 个百分点':r.kind==='count'?fmtNumber(Math.abs(r.delta),{digits:0}):r.kind==='qty'?fmtQty(Math.abs(r.delta),r.unit):fmtMoney(Math.abs(r.delta),{currency:'（币种待确认）'}))
const open=ref<string>('')
function openRec(r:Rec){if(r.row&&(r.kind==='project'||r.kind==='output'))inspect(r.kind,r.row)}
const sourceLabel=computed(()=>ws.value==='lsm'?'黑湖快照 '+String(S.source?.revision||'').slice(0,12):'味美投影 '+(ydwmAt.value||'').slice(0,19))
const ready=computed(()=>ws.value==='lsm'?!!S.source:!!ydwmAt.value||!!loadErr.value)
// —— 留档 ——
const saved=reactive<{version:number;reports:Saved[]}>({version:0,reports:[]}),viewSaved=ref<Saved|null>(null),err=ref(''),busy=ref(false)
async function call(body?:unknown){const r=await fetch('/__unified/d/report-archive?ws='+ws.value,{method:body?'POST':'GET',headers:body?{'Content-Type':'application/json','x-prototype-action':'explicit',...sess()}:sess(),body:body?JSON.stringify({...body as object,expectedVersion:saved.version}):undefined});const b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'留档失败（'+r.status+'）');saved.version=b.data.version;saved.reports=b.data.reports}
const periodLabel=(k:string,p:{from:string;to:string})=>(k==='week'?'周报 ':'月报 ')+p.from+' 至 '+p.to
async function archive(){busy.value=true;err.value='';try{const p=cur.value;await call({op:'save',kind:kind.value,from:p.from,to:p.to,label:periodLabel(kind.value,p),sourceRevision:ws.value==='lsm'?String(S.source?.revision||''):ydwmAt.value,sourceLabel:sourceLabel.value,rows:rows.value.map(r=>({group:r.group,label:r.label+(r.unit?'（'+r.unit+'）':''),value:r.cur,prev:r.prev,source:r.source,count:r.records.length,note:r.note}))});const hit=saved.reports.find(r=>r.kind===kind.value&&r.from===p.from);if(!hit)throw Error('留档后未读回');cretasUI().toast({message:'已留档「'+hit.label+'」· 回读一致'})}catch(e){err.value=e instanceof Error?e.message:'留档失败'}finally{busy.value=false}}
const hasSaved=computed(()=>saved.reports.find(r=>r.kind===kind.value&&r.from===cur.value.from))
// 已结束的上一周期还没留档 → 提示（没有后台定时任务，到期靠这里提醒）
const lastDone=computed(()=>periodOf(kind.value,-1)),missedLast=computed(()=>offset.value===0&&ready.value&&!saved.reports.some(r=>r.kind===kind.value&&r.from===lastDone.value.from))
function gotoLast(){offset.value=-1}
// —— 模型解读：只在点击后请求；数字必须来自事实表 ——
const ex=reactive<{state:''|'busy'|'answered'|'unavailable'|'rejected'|'error';html:string;note:string}>({state:'',html:'',note:''})
const facts=computed(()=>rows.value.filter(r=>r.value!==null).map(r=>({label:r.group+' '+r.label+(r.unit?'（'+r.unit+'）':'')+' 本期',value:String(Math.round((r.kind==='pct'?r.value!*100:r.value!)*100)/100),unit:r.kind==='pct'?'%':r.kind==='count'?'':r.unit})).concat(rows.value.filter(r=>r.prevValue!==null).map(r=>({label:r.group+' '+r.label+(r.unit?'（'+r.unit+'）':'')+' 上期',value:String(Math.round((r.kind==='pct'?r.prevValue!*100:r.prevValue!)*100)/100),unit:r.kind==='pct'?'%':r.kind==='count'?'':r.unit}))))
async function explain(){if(ex.state==='busy')return;ex.state='busy';ex.html='';ex.note=''
  try{const r=await fetch('/__unified/d/report-explain',{method:'POST',headers:{'Content-Type':'application/json','x-unified-explicit-ai':'1'},body:JSON.stringify({label:periodLabel(kind.value,cur.value),facts:facts.value.slice(0,40)})});const b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'解读失败（'+r.status+'）')
   const d=b.data;if(d.state==='answered'){const C=cretasUI(),chk=C.ai.checkParagraphs(d.paragraphs.join('\n\n'),facts.value);ex.html=C.ai.renderChecked(chk);ex.note=chk.rejected?chk.rejected+' 段含事实表里没有的数字，已折叠为「未核实内容」。':'';ex.state='answered'}
   else{ex.state=d.state==='rejected'?'rejected':'unavailable';ex.note=d.note||cretasUI().ai.TEXT.unavailable}}
  catch(e){ex.state='error';ex.note=e instanceof Error?e.message:'解读失败'}}
async function loadAll(){err.value='';loadErr.value='';ex.state='';
  try{await call()}catch(e){err.value=e instanceof Error?e.message:'读取留档失败'}
  if(ws.value==='ydwm'){try{const r=await fetch('/__account/home',{headers:sess()}),b=await r.json();if(!r.ok||!Array.isArray(b.plans))throw Error(b.message||cretasUI().ai.sourceErrorText(r.status,'味美首页投影'));ydwmPlans.value=b.plans;ydwmAt.value=b.updatedAt||'';ydwmDay.value=/^d{4}-d{2}-d{2}$/.test(String(b.today||''))?String(b.today):''}catch(e){loadErr.value=e instanceof Error?e.message:'味美数据读取失败'}}}
watch([ws,kind,offset],()=>{open.value='';viewSaved.value=null;ex.state=''})
watch(ws,()=>{saved.version=0;saved.reports=[];loadAll()})
watch(session,()=>{saved.version=0;saved.reports=[];viewSaved.value=null;loadAll()})
onMounted(loadAll)
const fmtTime=(v:string)=>v?new Date(v).toLocaleString('zh-CN',{hour12:false,timeZone:'Asia/Shanghai'}):'—'
</script>
<template>
<section class="ar-page">
<header class="ar-head"><div><p>数据与分析 · {{ws==='lsm'?'六膳门':'味美'}}工作区</p><h1>AI 经营报告</h1><small>按周、按月由规则生成事实表，每个数字都能点开看依据记录。模型解读只在你点击后才请求；模型不可用时，事实表照常可用。<template v-if="session"> · 演示会话 {{session}}</template></small></div></header>
<p v-if="err" role="alert" class="error">{{err}}</p><p v-if="loadErr" role="alert" class="error">{{loadErr}}</p>
<div class="ar-body">
<aside class="ar-side" aria-label="留档报告"><div class="ar-side-head"><b>已留档 {{saved.reports.length}}</b></div>
<p v-if="!saved.reports.length" class="muted">还没有留档。生成本期事实表后，点「留档本期」保存一份不随来源变化的快照。</p>
<button v-for="r in saved.reports" :key="r.id" type="button" class="ar-saved" :class="{on:viewSaved?.id===r.id}" @click="viewSaved=r"><span>{{r.label}}</span><small>{{fmtTime(r.generatedAt)}}</small></button></aside>
<main class="ar-main">
<template v-if="viewSaved">
<h2>{{viewSaved.label}} <small>留档于 {{fmtTime(viewSaved.generatedAt)}} · {{viewSaved.sourceLabel}} · 快照不随来源变化</small></h2>
<div class="actions"><UButton size="xs" color="neutral" variant="outline" @click="viewSaved=null">回到本期</UButton></div>
<table class="ar-table"><thead><tr><th>分组</th><th>指标</th><th class="n">本期</th><th class="n">上期</th><th>来源</th><th class="n">依据</th></tr></thead><tbody><tr v-for="(r,i) in viewSaved.rows" :key="i"><td>{{r.group}}</td><td>{{r.label}}<small v-if="r.note" class="muted"> · {{r.note}}</small></td><td class="n">{{r.value??'—'}}</td><td class="n">{{r.prev??'—'}}</td><td class="muted">{{r.source}}</td><td class="n">{{r.count}} 条</td></tr></tbody></table>
</template>
<template v-else>
<div class="ar-bar"><div class="ar-seg" role="tablist" aria-label="周期"><button type="button" role="tab" :aria-selected="kind==='week'" :class="{on:kind==='week'}" @click="kind='week';offset=0">周报</button><button type="button" role="tab" :aria-selected="kind==='month'" :class="{on:kind==='month'}" @click="kind='month';offset=0">月报</button></div>
<div class="ar-nav"><UButton size="xs" color="neutral" variant="outline" @click="offset--">上一{{kind==='week'?'周':'月'}}</UButton><b>{{cur.from}} 至 {{cur.to}}</b><UButton size="xs" color="neutral" variant="outline" :disabled="offset>=0" @click="offset++">下一{{kind==='week'?'周':'月'}}</UButton></div>
<div class="ar-act"><UButton size="sm" color="neutral" variant="outline" :loading="ex.state==='busy'" :disabled="!ready||!facts.length" @click="explain()">解读本期</UButton><UButton size="sm" :loading="busy" :disabled="!ready" @click="archive()">{{hasSaved?'更新留档':'留档本期'}}</UButton></div></div>
<p class="muted ar-meta">{{ws==='lsm'?'「今天」按快照日 '+today+'；':'今天 '+today+'；'}}对比期 {{PLABEL(prev)}} · 来源 {{sourceLabel}}<template v-if="isNowP(cur)"> · 本期还没结束，数字只算到 {{today}}，上期是完整周期，直接相比会偏低</template></p>
<p v-if="missedLast" class="ar-warn" role="status">上{{kind==='week'?'周':'月'}}（{{lastDone.from}} 至 {{lastDone.to}}）的{{kind==='week'?'周':'月'}}报还没留档。 <button type="button" class="link" @click="gotoLast()">去生成并留档</button>（没有后台定时任务，到期靠这里提醒）</p>
<p v-if="!ready" class="muted">正在读取来源…</p>
<p v-else-if="ws==='ydwm'&&!ydwmPlans.length" class="ar-warn" role="status">味美当前工作区的生产计划投影是 0 条，下面的数字都是 0，不代表业务没有发生；有计划数据后这里自动出数。</p>
<template v-else>
<table class="ar-table"><thead><tr><th>指标（来源与口径）</th><th class="n">本期</th><th class="n">上期</th><th class="n">变化</th><th class="n">依据</th></tr></thead>
<template v-for="g in groups" :key="g"><tbody><tr class="ar-group"><th colspan="5">{{g}}</th></tr>
<template v-for="r in rows.filter(x=>x.group===g)" :key="r.key"><tr><td>{{r.label}}<small v-if="r.unit" class="muted">（{{r.unit}}）</small><small class="muted ar-src">{{r.source}}<template v-if="r.note"> · {{r.note}}</template></small></td><td class="n"><b>{{r.cur}}</b></td><td class="n">{{r.prev}}</td><td class="n">{{deltaText(r)}}</td><td class="n"><button v-if="r.records.length" type="button" class="link" :aria-expanded="open===r.key" @click="open=open===r.key?'':r.key">{{r.records.length}} 条{{open===r.key?' ▴':' ▾'}}</button><span v-else class="muted">0 条</span></td></tr>
<tr v-if="open===r.key" class="ar-rec"><td colspan="5"><ul><li v-for="(x,i) in r.records.slice(0,60)" :key="i"><button v-if="x.row&&(x.kind==='project'||x.kind==='output')" type="button" class="link" @click="openRec(x)">{{x.line}}</button><span v-else>{{x.line}}</span></li></ul><p v-if="r.records.length>60" class="muted">只显示前 60 条，共 {{r.records.length}} 条。</p></td></tr></template></tbody></template></table>
<section v-if="ex.state" class="ar-ex" aria-label="模型解读"><h2>解读 <small>模型只写文字，数字必须来自上面的事实表；不通过的段落已折叠</small></h2>
<p v-if="ex.state==='busy'" class="muted">正在请求解读…（可能排队）</p>
<div v-else-if="ex.state==='answered'" class="cs-ai-answer" v-html="ex.html"/>
<p v-if="ex.note" class="ar-warn" :role="ex.state==='answered'?'status':'alert'">{{ex.note}}</p></section>
</template>
</template>
</main>
</div>
</section>
</template>
<style scoped>
.ar-page{display:grid;gap:12px}
.ar-head p{margin:0;font-size:12px;color:var(--cs-muted)}.ar-head h1{margin:2px 0;font-size:1.375rem}.ar-head small{color:var(--cs-muted);font-size:.75rem}
.ar-body{display:grid;grid-template-columns:260px minmax(0,1fr);gap:12px;align-items:start}
.ar-side,.ar-main{background:var(--cs-surface);border:1px solid var(--cs-border);border-radius:10px;padding:12px}
.ar-side-head{font-size:12px;color:var(--cs-muted);margin:2px 0 8px}
.ar-saved{display:flex;flex-direction:column;align-items:flex-start;width:100%;border:0;background:none;padding:6px 4px;font-size:.8125rem;cursor:pointer;border-radius:6px;text-align:left}.ar-saved:hover,.ar-saved.on{background:var(--cs-primary-soft)}.ar-saved small{color:var(--cs-muted)}
.ar-bar{display:flex;flex-wrap:wrap;gap:10px 16px;align-items:center;justify-content:space-between}
.ar-seg{display:inline-flex;border:1px solid var(--cs-border);border-radius:8px;overflow:hidden}.ar-seg button{border:0;background:none;padding:5px 14px;font-size:.8125rem;cursor:pointer}.ar-seg button.on{background:var(--cs-primary-soft);color:var(--cs-primary);font-weight:600}
.ar-nav{display:flex;gap:8px;align-items:center;font-size:.875rem}.ar-act{display:flex;gap:8px}
.ar-meta{margin:8px 0}.ar-warn{margin:8px 0;padding:8px 10px;border-radius:8px;background:var(--cs-warning-soft,#fff6e0);font-size:.8125rem}
.ar-table{width:100%;border-collapse:collapse;font-size:.875rem;font-variant-numeric:tabular-nums;table-layout:auto}
.ar-table th,.ar-table td{padding:6px 8px;border-bottom:1px solid var(--cs-border);text-align:left;line-height:20px;max-width:none;white-space:normal;overflow:visible;text-overflow:clip;overflow-wrap:break-word}
.ar-table td:first-child,.ar-table th:first-child{min-width:10em}.ar-src{display:block}
.ar-table th{font-weight:600;color:var(--cs-muted);font-size:.8125rem}.ar-table .n{text-align:right;white-space:normal}.ar-table td.n b{white-space:nowrap}
.ar-group th{background:var(--cs-surface-2,#f4f7fa);color:var(--cs-text,inherit);font-size:.8125rem}
.ar-rec td{background:var(--cs-surface-2,#f8fafc);font-size:.8125rem}.ar-rec ul{margin:0;padding-left:18px;display:grid;gap:3px}
.link{border:0;background:none;color:var(--cs-primary);cursor:pointer;padding:0;font:inherit;text-align:left}.link:hover{text-decoration:underline}
.ar-ex{margin-top:14px}.ar-ex h2,.ar-main h2{font-size:1rem;margin:0 0 8px}.ar-ex h2 small,.ar-main h2 small{font-weight:400;font-size:.75rem;color:var(--cs-muted);margin-left:6px}
.muted{color:var(--cs-muted);font-size:.8125rem}.actions{margin:6px 0 10px}
@media(max-width:1180px){.ar-body{grid-template-columns:1fr}.ar-side{display:flex;flex-wrap:wrap;gap:4px 12px;align-items:center}.ar-side .muted{margin:0}.ar-saved{width:auto}.ar-main{overflow-x:auto}}
</style>
