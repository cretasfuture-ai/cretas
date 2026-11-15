<script setup lang="ts">
// 第六批（R23）六膳门「称重报工」：Cretas 为主来源，逐笔称重，一屏完成，主按钮只有「保存称重」。
// 写入只到 a-api（按 acceptanceSession 隔离），不回写黑湖；黑湖报工只作对照，差异进对账。
import {computed,nextTick,onMounted,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import type {Row} from '../frame/types'
import {S,rs} from '../frame/store'
import {fmtQty,fmtPct,sourceTagHTML,confirmReason,reconcileView} from '../frame/ai-protocol'
import {acceptanceSession} from './session'
import {bjTime,bjDay} from './time'

interface Defect{defectId:string;code:string;name:string;kg:number}
interface By{productCode:string;name:string;kg:number}
interface Entry{id:string;taskId:string;inputKg:number;goodGrossKg:number;tareKg:number;goodKg:number;defects:Defect[];defectKg:number;byproducts:By[];byproductKg:number;lossKg:number;start:string;end:string;at:string;suspects?:string[]}
interface Sum{records:number;inputKg:number;goodKg:number;defectKg:number;byproductKg:number;yieldRate:number|null;standard:{rate:number;tolerance:number;id:string}|null;deviationPts:number|null;yieldStatus:string;suspectCount?:number;suspectReasons?:string[]}
interface Rec{suspects?:string[];label?:string;key:string;cretas:{goodKg:number;defectKg:number;count:number}|null;blacklake:{goodKg:number|null;defectKg:number|null;count:number;unit:string|null;note:string|null;effectiveHours:number|null}|null}

const aq=(path:string,extra:Record<string,string>={})=>{const p=new URLSearchParams({ws:'lsm',...extra});const s=acceptanceSession();if(s)p.set('acceptanceSession',s);return '/__unified/a/api/'+path+'?'+p}
async function post<T>(path:string,body:Record<string,unknown>):Promise<T>{const r=await fetch(aq(path),{method:'POST',headers:{'content-type':'application/json','x-prototype-action':'explicit'},body:JSON.stringify({ws:'lsm',...body})});const b=await r.json().catch(()=>({success:false,message:'服务返回格式不正确'}));if(!r.ok||!b.success)throw Error(b.message||'保存失败');return b.data as T}

// ---- 任务选择：默认显示执行中的任务，可按工单号 / 产品 / 工序搜索 ----
const q=ref(''),onlyRunning=ref(true),taskId=ref('')
// 第六批审计退回 A-2：窄屏（≤ 600px）选中任务后收起任务列表，表单顶到上面；主按钮固定在底部左侧，右边给「解读」浮钮留位
const narrow=()=>typeof window!=='undefined'&&window.matchMedia('(max-width:600px)').matches
const listOpen=ref(true)
// R28：「今天」= 快照日（checkedAt 的北京日期）；排序 = 快照日计划的工单在前，其余按计划日从新到旧
const snapDay=computed(()=>{const c=S.source?.checkedAt;return c?bjDay(c):''})
const procById=computed(()=>new Map(rs('process').map(p=>[String(p.id),p])))
const projById=computed(()=>new Map(rs('project').map(p=>[String(p.id),p])))
const tasks=computed(()=>rs('task').filter(t=>!onlyRunning.value||Number(t.processStatus)===10).map(t=>{const pj=projById.value.get(String(t.projectId));return {planDay:String(pj?.planStartTime??'').slice(0,10),id:String(t.id),projectCode:String(pj?.projectCode??t.projectId),product:String(pj?.productName??t.productCode??''),process:String(t.processName??procById.value.get(String(t.processId))?.name??''),processCode:String(procById.value.get(String(t.processId))?.code??'')}}).filter(t=>{const k=q.value.trim().toLowerCase();return !k||[t.projectCode,t.product,t.process].some(x=>x.toLowerCase().includes(k))}).sort((a,b)=>{const x=a.planDay===snapDay.value?0:1,y=b.planDay===snapDay.value?0:1;if(x!==y)return x-y;return a.planDay!==b.planDay?(a.planDay<b.planDay?1:-1):a.projectCode.localeCompare(b.projectCode)}))
const PAGE=50,shown=ref(PAGE);watch([q,onlyRunning],()=>{shown.value=PAGE})
const todayCount=computed(()=>tasks.value.filter(t=>t.planDay===snapDay.value).length)
const task=computed(()=>tasks.value.find(t=>t.id===taskId.value)||(()=>{const t=rs('task').find(x=>String(x.id)===taskId.value);if(!t)return null;const pj=projById.value.get(String(t.projectId));return {id:String(t.id),projectCode:String(pj?.projectCode??t.projectId),product:String(pj?.productName??''),process:String(t.processName??''),processCode:String(procById.value.get(String(t.processId))?.code??'')}})())

// ---- 表单 ----
const bjNow=()=>new Date(Date.now()+8*3600e3).toISOString().slice(0,16)
const bjLimit=()=>new Date(Date.now()+8*3600e3+5*60e3).toISOString().slice(0,16)  // 晚于现在超过 5 分钟算「时间晚于现在」
const f=ref({inputKg:'',goodGrossKg:'',tareKg:'0',start:bjNow(),end:bjNow()})
const defects=ref<{defectId:string;kg:string}[]>([]),byproducts=ref<{productCode:string;kg:string}[]>([])
const errs=ref<Record<string,string>>({}),busy=ref(false),notice=ref(''),err=ref('')
const n=(v:string)=>v===''?NaN:Number(v)
const netKg=computed(()=>{const g=n(f.value.goodGrossKg),t=n(f.value.tareKg||'0');return Number.isFinite(g)&&Number.isFinite(t)?Math.round((g-t)*100)/100:null})
const future=(v:string)=>!!v&&v>bjLimit()
const futureMsg=computed(()=>future(f.value.start)&&future(f.value.end)?'开始和结束时间都晚于现在':future(f.value.start)?'开始时间晚于现在':future(f.value.end)?'结束时间晚于现在':'')
const sameTime=computed(()=>f.value.start&&f.value.start===f.value.end),timeTouched=ref(false)  // 默认值没改过时不提前报错，改过或点保存才提示
// 第六批审计退回 A-3：超过两位小数不静默舍入，字段旁提示
const tooFine=(v:string)=>/\.\d{3,}$/.test(String(v).trim())
const TWO='只保留两位小数（kg），请改成两位'
function validate(){const e:Record<string,string>={};const i=n(f.value.inputKg),g=n(f.value.goodGrossKg),t=n(f.value.tareKg||'0')
  if(!Number.isFinite(i)||i<=0)e.inputKg='填投入重量（kg，大于 0）'
  if(!Number.isFinite(g)||g<0)e.goodGrossKg='填良品毛重（kg）'
  if(!Number.isFinite(t)||t<0)e.tareKg='皮重不能是负数；没有容器填 0'
  else if(Number.isFinite(g)&&t>g)e.tareKg='皮重比良品毛重还大，请核对'
  if(!e.inputKg&&tooFine(f.value.inputKg))e.inputKg=TWO
  if(!e.goodGrossKg&&tooFine(f.value.goodGrossKg))e.goodGrossKg=TWO
  if(!e.tareKg&&tooFine(f.value.tareKg))e.tareKg=TWO
  defects.value.forEach((d,k)=>{if(!d.defectId)e['d'+k]='选不良项';else if(!(n(d.kg)>0))e['d'+k]='填不良重量';else if(tooFine(d.kg))e['d'+k]=TWO})
  byproducts.value.forEach((d,k)=>{if(!d.productCode)e['b'+k]='选副产品';else if(!(n(d.kg)>0))e['b'+k]='填副产品重量';else if(tooFine(d.kg))e['b'+k]=TWO})
  const out=(netKg.value||0)+defects.value.reduce((s,d)=>s+(n(d.kg)||0),0)+byproducts.value.reduce((s,d)=>s+(n(d.kg)||0),0)
  if(Number.isFinite(i)&&out>i+0.005)e.out='良品净重＋不良＋副产品 = '+out.toFixed(2)+' kg，大于投入 '+i.toFixed(2)+' kg'
  if(!f.value.start||!f.value.end)e.time='填开始和结束时间'
  else if(sameTime.value)e.time='开始和结束时间相同，请改结束时间'
  else if(f.value.end<f.value.start)e.time='结束时间早于开始时间'
  else if(futureMsg.value)e.time=futureMsg.value+'（超过现在 5 分钟），请改成已经发生的时间'
  errs.value=e;return !Object.keys(e).length}

// ---- 数据：本任务记录、汇总、对账、不良项字典、标准出成率 ----
const entries=ref<Entry[]>([]),sum=ref<Sum|null>(null),rec=ref<Rec|null>(null),asOf=ref(''),version=ref(0),dict=ref<{id:string;name:string;code?:string;processes?:string[];active?:boolean}[]>([])
const dictForTask=computed(()=>dict.value.filter(d=>d.active!==false&&(!d.processes?.length||!task.value?.processCode||d.processes.includes(task.value.processCode))))
const products=computed(()=>rs('product').filter(p=>p.productCode).map(p=>({code:String(p.productCode),name:String(p.productName??p.productCode)})))
async function loadDict(){try{const s=acceptanceSession();const b=await (await fetch('/__unified/e/api/state?ws=lsm'+(s?'&acceptanceSession='+encodeURIComponent(s):''))).json();dict.value=b?.data?.defects||[]}catch{dict.value=[]}}
async function load(){if(!taskId.value)return;err.value='';try{const t=taskId.value,pj=rs('task').find(x=>String(x.id)===t)?.projectId
  const [w,r]=await Promise.all([fetch(aq('weighins',{task:t})).then(x=>x.json()),fetch(aq('weighin-reconcile',{project:String(pj??'')})).then(x=>x.json())])
  if(!w.success)throw Error(w.message==='未找到'?'称重服务尚未接入统一入口':w.message)
  entries.value=w.data.entries;sum.value=w.data.tasks[t]||null;version.value=w.data.version;asOf.value=w.data.asOf||''
  rec.value=(r.data?.rows||[]).find((x:Rec)=>x.key===t)||null
  recRows.value=r.data?.rows||[];mountRecon()
  try{const o=await (await fetch(aq('weighing-summary'))).json();order.value=o.data?.projects?.[String(pj)]||null}catch{order.value=null}
  prefillInput()
  const last=entries.value[0];if(last){f.value.tareKg=String(last.tareKg);const le=bjTime(last.end).replace(' ','T');if(le<f.value.start&&le.slice(0,10)===bjNow().slice(0,10))f.value.start=le}  // 默认开始 = 本任务今天上一笔的结束
}catch(e){err.value=e instanceof Error?e.message:'读取失败'}}
// 第六批审计退回 A-1：已经显示过错误时，任何字段一改就重新判断（过了就收起，不用再点保存）
watch([f,defects,byproducts],()=>{if(Object.keys(errs.value).length)validate()},{deep:true})
// 窄屏选中任务后把表单滚到最上面（页头之下），主按钮在底部固定
watch(taskId,v=>{if(v&&narrow())nextTick(()=>(document.querySelector('.wi-switch')||document.querySelector('.wi-main'))?.scrollIntoView({block:'start'}))})
watch(taskId,()=>{timeTouched.value=false;listOpen.value=!narrow();inputHint.value='';entries.value=[];sum.value=null;rec.value=null;notice.value='';errs.value={};f.value={inputKg:'',goodGrossKg:'',tareKg:'0',start:bjNow(),end:bjNow()};defects.value=[];byproducts.value=[];load()})

async function save(){notice.value='';err.value='';if(!task.value){err.value='先在左边选一个任务';return}if(!validate())return;busy.value=true
  try{const d=await post<{capture:Entry}>('weighins/save',{taskId:task.value.id,inputKg:n(f.value.inputKg),goodGrossKg:n(f.value.goodGrossKg),tareKg:n(f.value.tareKg||'0'),defects:defects.value.map(x=>({defectId:x.defectId,kg:n(x.kg)})),byproducts:byproducts.value.map(x=>({productCode:x.productCode,kg:n(x.kg)})),start:f.value.start,end:f.value.end,expectedVersion:version.value})
    const id=d.capture.id;const endWas=f.value.end;await load()
    const back=entries.value.find(x=>x.id===id)
    notice.value=back?'已保存并回读：良品净重 '+fmtQty(back.goodKg,'kg')+'，投入 '+fmtQty(back.inputKg,'kg')+'，'+bjTime(back.start)+' – '+bjTime(back.end).slice(11):'已保存，但回读没找到，请刷新核对'
    f.value={inputKg:'',goodGrossKg:'',tareKg:f.value.tareKg,start:endWas,end:bjNow()};timeTouched.value=false;defects.value=[];byproducts.value=[];errs.value={}
  }catch(e){err.value=e instanceof Error?e.message:'保存失败'}finally{busy.value=false}}
async function revoke(e:Entry){const why=await confirmReason({title:'撤销这笔称重',object:bjTime(e.start)+' · 良品 '+fmtQty(e.goodKg,'kg'),message:'撤销后不计入合计与出成率，记录保留留痕；不回写黑湖。',reasonLabel:'撤销原因',okLabel:'确认撤销',danger:true,required:true});if(why===null)return
  busy.value=true;try{await post('weighins/revoke',{id:e.id,reason:why,expectedVersion:version.value});await load();notice.value='已撤销这笔称重，合计已重算'}catch(x){err.value=x instanceof Error?x.message:'撤销失败'}finally{busy.value=false}}

// ---- 标准出成率（Cretas 补充，次要操作） ----
const stdEdit=ref(false),stdRate=ref(''),stdTol=ref('5'),stdReason=ref('')
async function saveStd(){if(!task.value)return;const r=n(stdRate.value);if(!(r>0&&r<=150)){err.value='标准出成率填 0 ~ 150 之间的百分数';return}busy.value=true
  try{const v=await (await fetch(aq('yield-standards'))).json();await post('yield-standards/save',{processCode:task.value.processCode,rate:r/100,tolerance:n(stdTol.value),reason:stdReason.value,expectedVersion:v.data.version});stdEdit.value=false;await load();notice.value='已保存 '+task.value.process+' 的标准出成率 '+r+'%'}catch(x){err.value=x instanceof Error?x.message:'保存失败'}finally{busy.value=false}}

// ③ 本工单对账：用 D 的共享 reconcileView（1.8.0）。黑湖单位不是 kg 时重量不比，名称里写明，不当成「未记录」。
const recRows=ref<Rec[]>([]),reconHost=ref<HTMLElement>()
// 第六批 · 口径：整单出成率 = 路线最后一道工序良品 ÷ 第一道工序投入（与 B 成本、C 生产入库同口径；缺哪道就写缺）
interface OrderSum{inputKg:number|null;goodKg:number|null;yieldRate:number|null;missing:string|null;firstProcess:{name:string|null}|null;finalProcess:{name:string|null}|null;processes:{name:string;code:string;isFirst:boolean;isLast:boolean}[]}
const order=ref<OrderSum|null>(null)
// 第六批审计退回 A-4：同一工单下一道工序的投入默认 = 上一道良品合计 − 本道已投入（可改）；没有上一道称重就留空
const inputHint=ref('')
function prefillInput(){inputHint.value='';if(f.value.inputKg!==''||!order.value||!task.value)return
  const ps=order.value.processes as unknown as {code:string;name:string;routeIndex:number|null;goodKg:number}[]
  const myIdx=ps.find(x=>x.code===task.value!.processCode)?.routeIndex??routeIdxOf(task.value.processCode);if(myIdx==null||myIdx<=0)return
  const prev=ps.filter(x=>x.routeIndex!=null&&x.routeIndex<myIdx).sort((a,b)=>(b.routeIndex as number)-(a.routeIndex as number))[0];if(!prev)return
  const used=sum.value?.inputKg||0,left=Math.round((prev.goodKg-used)*100)/100;if(left<=0)return
  f.value.inputKg=left.toFixed(2);inputHint.value='默认取上一道「'+prev.name+'」良品 '+fmtQty(prev.goodKg,'kg')+(used?' − 本道已投入 '+fmtQty(used,'kg'):'')+'，可改'}
function routeIdxOf(code:string){const t=rs('task').find(x=>String(x.id)===taskId.value);const pj=t&&rs('project').find(p=>String(p.id)===String(t.projectId));const pr=pj&&rs('product').find(p=>p.productCode===pj.productCode);const rt=pr&&rs('routing').find(r=>r.processRoutingCode===pr.prcessRoutingCode);const st=Array.isArray(rt?.processRoutingSteps)?rt!.processRoutingSteps as Row[]:[];const i=st.findIndex(x=>x.code===code);return i<0?null:i}
const isLastHere=computed(()=>!!order.value?.processes.find(x=>x.code===task.value?.processCode)?.isLast)
let recon:{element:HTMLElement;refresh:(o:Record<string,unknown>)=>void}|null=null
function reconOpts(){const rows=recRows.value;return {title:'本工单 称重报工 ↔ 黑湖报工',keyLabel:'工序任务',fileName:'称重对账-'+(task.value?.projectCode||''),asOf:asOf.value||null,
  cretas:rows.filter(r=>r.cretas).map(r=>({key:r.key,label:r.label||r.key,...r.cretas})),blacklake:rows.filter(r=>r.blacklake).map(r=>({key:r.key,label:(r.label||r.key)+(r.blacklake!.note?'（'+r.blacklake!.note+'）':''),...r.blacklake})),
  key:(r:{key:string})=>r.key,fields:[{key:'good',label:'良品',unit:'kg',cretas:(r:{goodKg:number})=>r.goodKg,blacklake:(r:{goodKg:number|null})=>r.goodKg},{key:'defect',label:'不良',unit:'kg',cretas:(r:{defectKg:number})=>r.defectKg,blacklake:(r:{defectKg:number|null})=>r.defectKg},{key:'count',label:'笔数',digits:0,cretas:(r:{count:number})=>r.count,blacklake:(r:{count:number})=>r.count}],
  owner:(row:{state:string;key:string})=>{const sp=recRows.value.find(x=>x.key===row.key)?.suspects;return sp?.length?'车间（数据疑点 · 先核称重录入：'+sp.join('；')+'）':row.state==='blacklakeOnly'?'车间（补称重）':'车间'},label:(r:{label:string})=>r.label}}
function mountRecon(){const h=reconHost.value;if(!h)return;if(recon&&h.contains(recon.element)){recon.refresh(reconOpts());return}recon?.element.remove();recon=reconcileView(h,reconOpts() as unknown as Parameters<typeof reconcileView>[1]) as unknown as typeof recon}
const diff=(a:number|null|undefined,b:number|null|undefined)=>a==null||b==null?null:Math.round((a-b)*100)/100
onMounted(()=>{loadDict();const t=S.query.get('task');if(t)taskId.value=t})
</script>

<template>
<section class="card wi">
  <header class="wi-head"><div><h2>称重报工 <span v-html="sourceTagHTML('cretasMain')"/></h2><p>六膳门 · 逐笔称重，Cretas 为准；黑湖报工只作对照（黑湖最近采集 {{asOf?bjTime(asOf):'—'}}，北京时间）。单位 kg，两位小数，不回写黑湖。</p></div></header>
  <div class="wi-grid" :class="{'wi-picked':!!task&&!listOpen}">
    <button v-if="task&&!listOpen" type="button" class="wi-switch" @click="listOpen=true">换任务（当前：{{task.projectCode}} · {{task.process}}）</button>
    <aside v-show="listOpen||!task" class="wi-tasks" aria-label="选任务">
      <input v-model="q" aria-label="搜索任务" placeholder="工单号、产品或工序">
      <label class="wi-check"><input v-model="onlyRunning" type="checkbox"> 只看执行中</label>
      <p class="wi-muted wi-sort">排序：先列计划日 = 快照日 {{snapDay||'今天'}} 的任务（共 {{todayCount}} 个），其余按计划日期从新到旧；同一天按工单号。</p>
      <ul><li v-for="t in tasks.slice(0,shown)" :key="t.id"><button type="button" :aria-pressed="t.id===taskId" :aria-label="t.projectCode+' '+t.process+' '+t.product" @click="taskId=t.id"><b>{{t.projectCode}}</b><span>{{t.process}}</span><small>{{t.product}}<template v-if="t.planDay===snapDay"> · 快照日计划</template></small></button></li>
        <li v-if="!tasks.length" class="wi-muted">没有符合的任务。</li><li v-else class="wi-muted">已列 {{Math.min(shown,tasks.length)}} / 共 {{tasks.length}} 个任务<button v-if="tasks.length>shown" type="button" class="wi-link" @click="shown+=PAGE">　再显示 {{Math.min(PAGE,tasks.length-shown)}} 个</button></li></ul>
    </aside>
    <main class="wi-main">
      <p v-if="!task" class="wi-empty">先在左边选一个任务，再称重。</p>
      <template v-else>
        <h3>{{task.projectCode}} · {{task.process}} <small>{{task.product}}</small></h3>
        <div class="wi-form">
          <label>投入重量 <span class="wi-in"><input v-model="f.inputKg" inputmode="decimal" aria-label="投入重量" placeholder="0.00" @input="inputHint=''"> kg</span><em v-if="errs.inputKg">{{errs.inputKg}}</em><small v-else-if="inputHint" class="wi-hint">{{inputHint}}</small></label>
          <label>良品毛重 <span class="wi-in"><input v-model="f.goodGrossKg" inputmode="decimal" aria-label="良品毛重" placeholder="0.00"> kg</span><em v-if="errs.goodGrossKg">{{errs.goodGrossKg}}</em></label>
          <label>皮重 <span class="wi-in"><input v-model="f.tareKg" inputmode="decimal" aria-label="皮重" placeholder="0.00"> kg</span><em v-if="errs.tareKg">{{errs.tareKg}}</em></label>
          <div class="wi-net">良品净重<b>{{netKg===null?'—':fmtQty(netKg,'kg')}}</b><small>= 毛重 − 皮重</small></div>
          <label class="wi-time">开始 <input v-model="f.start" type="datetime-local" aria-label="开始时间" @input="timeTouched=true"></label>
          <label class="wi-time">结束 <input v-model="f.end" type="datetime-local" aria-label="结束时间" @input="timeTouched=true"><em v-if="errs.time||(timeTouched&&sameTime)||futureMsg">{{errs.time||futureMsg||'开始和结束时间相同，请改结束时间'}}</em></label>
        </div>
        <div class="wi-rows">
          <div><b>不良</b><span v-for="(d,k) in defects" :key="'d'+k" class="wi-row"><select v-model="d.defectId" aria-label="不良项"><option value="">选不良项</option><option v-for="x in dictForTask" :key="x.id" :value="x.id">{{x.name}}</option></select><input v-model="d.kg" inputmode="decimal" aria-label="不良重量" placeholder="0.00"> kg <button type="button" class="wi-x" aria-label="删除这行" @click="defects.splice(k,1)">×</button><em v-if="errs['d'+k]">{{errs['d'+k]}}</em></span>
            <button type="button" class="wi-add" :disabled="!dictForTask.length" :title="dictForTask.length?'':'这个工序还没有不良项，请质量人员在「不良分析」维护'" @click="defects.push({defectId:'',kg:''})">＋ 不良</button><small v-if="!dictForTask.length" class="wi-muted">本工序没有可选的不良项</small></div>
          <div><b>副产品</b><span v-for="(d,k) in byproducts" :key="'b'+k" class="wi-row"><input v-model="d.productCode" list="wi-products" aria-label="副产品" placeholder="产品编码或名称"><input v-model="d.kg" inputmode="decimal" aria-label="副产品重量" placeholder="0.00"> kg <button type="button" class="wi-x" aria-label="删除这行" @click="byproducts.splice(k,1)">×</button><em v-if="errs['b'+k]">{{errs['b'+k]}}</em></span>
            <button type="button" class="wi-add" @click="byproducts.push({productCode:'',kg:''})">＋ 副产品</button></div>
          <datalist id="wi-products"><option v-for="p in products" :key="p.code" :value="p.code">{{p.name}}</option></datalist>
        </div>
        <p v-if="errs.out" class="wi-err">{{errs.out}}</p>
        <div class="wi-act"><UButton size="lg" class="wi-save" :loading="busy" @click="save">保存称重</UButton><span v-if="notice" role="status" class="wi-ok">{{notice}}</span><span v-if="err" role="alert" class="wi-err">{{err}}</span></div>

        <div class="wi-sum">
          <div><small>本任务合计（{{sum?.records||0}} 笔）</small><b>投入 {{fmtQty(sum?.inputKg??0,'kg')}} · 良品 {{fmtQty(sum?.goodKg??0,'kg')}} · 不良 {{fmtQty(sum?.defectKg??0,'kg')}} · 副产品 {{fmtQty(sum?.byproductKg??0,'kg')}}</b></div>
          <div><small>本工序出成率 = 良品净重 ÷ 投入<template v-if="isLastHere"> · 最后一道工序（入库取这道）</template></small><b :class="{'wi-warn':!sum?.suspectCount&&(sum?.yieldStatus==='低于标准'||sum?.yieldStatus==='高于标准')}">{{sum?.yieldRate==null?'—':fmtPct(sum.yieldRate*100)}}</b><span v-if="sum?.suspectCount" class="wi-sus" title="R34：录入明显有问题，原值不改，不当作业务异常">数据疑点 · 找车间核称重录入（{{sum.suspectCount}} 笔：{{sum.suspectReasons?.join('；')}}）</span><span v-else>{{sum?sum.yieldStatus:'还没有称重'}}<template v-if="sum?.standard">（标准 {{fmtPct(sum.standard.rate*100)}} ± {{sum.standard.tolerance}} 个百分点，偏离 {{sum.deviationPts}}）</template></span>
            <button type="button" class="wi-link" @click="stdEdit=!stdEdit;stdRate=sum?.standard?String(Math.round(sum.standard.rate*10000)/100):'';stdTol=String(sum?.standard?.tolerance??5)">{{sum?.standard?'改标准出成率':'设标准出成率'}}</button></div>
          <div><small>黑湖报工（对照，不作依据）</small><b v-if="rec?.blacklake">{{rec.blacklake.count}} 笔 · 良品 {{rec.blacklake.goodKg==null?'—':fmtQty(rec.blacklake.goodKg,'kg')}}</b><b v-else>黑湖没有这项任务的报工</b>
            <span v-if="rec?.blacklake?.note">{{rec.blacklake.note}}</span><span v-else-if="rec?.blacklake&&rec?.cretas">差额（Cretas − 黑湖）良品 {{fmtQty(diff(rec.cretas.goodKg,rec.blacklake.goodKg),'kg')}}；差异由车间核对，是否回黑湖修由人决定</span></div>
        </div>
        <p class="wi-order"><b>整单出成率</b>（路线最后一道「{{order?.finalProcess?.name||'—'}}」良品 ÷ 第一道「{{order?.firstProcess?.name||'—'}}」投入）：<b>{{order?.yieldRate==null?'—':fmtPct(order.yieldRate*100)}}</b><template v-if="order&&order.goodKg!=null&&order.inputKg!=null">　{{fmtQty(order.goodKg,'kg')}} ÷ {{fmtQty(order.inputKg,'kg')}}</template><span v-if="order?.missing" class="wi-muted">　不能算：{{order.missing}}</span><span v-else-if="!order" class="wi-muted">　本工单还没有称重</span></p>
        <div v-if="stdEdit" class="wi-std"><label>标准出成率 <input v-model="stdRate" inputmode="decimal" aria-label="标准出成率"> %</label><label>允许偏离 <input v-model="stdTol" inputmode="decimal" aria-label="允许偏离"> 个百分点</label><label class="grow">原因 <input v-model="stdReason" aria-label="标准出成率原因" placeholder="例如：按工艺卡"></label><UButton size="sm" color="neutral" variant="outline" :loading="busy" :disabled="!stdReason.trim()" @click="saveStd">保存标准</UButton><small v-html="sourceTagHTML('cretas')"/></div>

        <table class="wi-table"><thead><tr><th>时间</th><th>投入</th><th>良品净重</th><th>不良</th><th>副产品</th><th>损耗</th><th>出成率</th><th></th></tr></thead>
          <tbody><tr v-for="e in entries" :key="e.id"><td>{{bjTime(e.start)}} – {{bjTime(e.end).slice(11)}}</td><td>{{fmtQty(e.inputKg,'kg')}}</td><td :title="'毛重 '+e.goodGrossKg+' − 皮重 '+e.tareKg">{{fmtQty(e.goodKg,'kg')}}</td><td :title="e.defects.map(d=>d.name+' '+d.kg).join('、')">{{fmtQty(e.defectKg,'kg')}}</td><td :title="e.byproducts.map(d=>d.name+' '+d.kg).join('、')">{{e.byproducts.length?e.byproducts.map(d=>d.name+' '+fmtQty(d.kg,'kg')).join('、'):'—'}}</td><td>{{fmtQty(e.lossKg,'kg')}}</td><td>{{fmtPct(e.goodKg/e.inputKg*100)}}<span v-if="e.suspects?.length" class="wi-sus" :title="e.suspects.join('；')+'。原值不改，请车间核对录入。'">　数据疑点 · 找车间核</span></td><td><button type="button" class="wi-link" @click="revoke(e)">撤销</button></td></tr>
          <tr v-if="!entries.length"><td colspan="8" class="wi-muted">这项任务还没有称重记录。</td></tr></tbody></table>
        <div ref="reconHost" class="wi-recon"/>
      </template>
    </main>
  </div>
</section>
</template>

<style scoped>
.wi-head h2{margin:0;font-size:18px;display:flex;gap:8px;align-items:center}.wi-head p{margin:4px 0 10px;font-size:12px;color:#748297}
.wi-grid{display:grid;grid-template-columns:260px 1fr;gap:14px;align-items:start}
.wi-tasks{display:flex;flex-direction:column;gap:6px;max-height:calc(100vh - 220px);overflow:auto}.wi-tasks input[type=text],.wi-tasks>input{border:1px solid var(--cs-border,#d6dee7);border-radius:6px;padding:6px 8px}
.wi-tasks ul{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:4px}.wi-tasks li button{width:100%;text-align:left;border:1px solid var(--cs-border,#e3e8ef);border-radius:6px;background:#fff;padding:6px 8px;display:grid;font-size:12px;cursor:pointer}
.wi-tasks li button[aria-pressed=true]{border-color:var(--cs-primary,#1b65a8);background:var(--cs-primary-soft,#eef5fc)}.wi-tasks small,.wi-muted{color:#748297;font-size:12px}.wi-check{font-size:12px}
.wi-main h3{margin:0 0 8px;font-size:16px}.wi-main h3 small{color:#748297;font-weight:400;font-size:12px}.wi-empty{color:#748297;padding:40px 0}
.wi-form{display:grid;grid-template-columns:repeat(4,minmax(150px,1fr));gap:10px;align-items:start}.wi-form label{display:grid;gap:4px;font-size:13px}
.wi-form input{border:1px solid var(--cs-border,#d6dee7);border-radius:6px;padding:8px;font-size:16px;font-variant-numeric:tabular-nums}.wi-in{display:flex;align-items:center;gap:4px}.wi-in input{width:100%}
.wi-net{display:grid;font-size:13px}.wi-net b{font-size:20px}.wi-net small{color:#748297}
em{color:var(--cs-danger,#c53b3b);font-style:normal;font-size:12px}
.wi-rows{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:10px 0}.wi-rows>div{display:flex;flex-wrap:wrap;gap:6px;align-items:center;font-size:13px}.wi-row{display:flex;gap:4px;align-items:center;flex-basis:100%;flex-wrap:wrap}
.wi-row select,.wi-row input{border:1px solid var(--cs-border,#d6dee7);border-radius:6px;padding:6px}.wi-row input[inputmode]{width:90px}
.wi-add,.wi-x,.wi-link{border:0;background:none;color:var(--cs-primary,#1b65a8);cursor:pointer;font-size:13px}.wi-add:disabled{color:#aab4c0;cursor:not-allowed}
.wi-act{display:flex;gap:12px;align-items:center;margin:8px 0 12px}.wi-ok{color:var(--cs-success,#2f7d4a);font-size:13px}.wi-err{color:var(--cs-danger,#c53b3b);font-size:13px}
.wi-sum{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;border-top:1px solid var(--cs-border,#e3e8ef);padding-top:10px}.wi-sum>div{display:grid;gap:2px;font-size:12px}.wi-sum b{font-size:14px}.wi-sum small{color:#748297}.wi-warn{color:var(--cs-danger,#c53b3b)}
.wi-std{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:8px 0;font-size:13px}.wi-std input{border:1px solid var(--cs-border,#d6dee7);border-radius:6px;padding:4px 6px;width:80px}.wi-std .grow input{width:220px}
.wi-recon{margin-top:14px}.wi-order{margin:8px 0 0;font-size:13px}.wi-table{width:100%;margin-top:10px;font-size:13px;border-collapse:collapse}.wi-table th,.wi-table td{padding:6px;border-bottom:1px solid var(--cs-border,#eef1f5);text-align:left;white-space:nowrap;font-variant-numeric:tabular-nums}
.wi-main{min-width:0}.wi-recon{overflow-x:auto}
@media (max-width:900px){.wi-grid{grid-template-columns:minmax(0,1fr)}.wi-form{grid-template-columns:1fr 1fr}.wi-rows,.wi-sum{grid-template-columns:1fr}}
.wi-sus{color:#a15c00;background:#fff4e0;border-radius:4px;padding:0 6px;font-size:12px;white-space:normal}.wi-hint{color:#748297;font-size:12px}.wi-switch{display:none}
@media (max-width:600px){.wi-switch{display:block;width:100%;text-align:left;border:1px solid var(--cs-border,#e3e8ef);border-radius:6px;background:var(--cs-primary-soft,#eef5fc);padding:8px;font-size:13px;margin-bottom:8px}
  .wi-tasks{max-height:none}.wi-form{grid-template-columns:minmax(0,1fr) minmax(0,1fr)}.wi-form label,.wi-in{min-width:0}.wi-form input{min-width:0;width:100%;box-sizing:border-box}.wi-form .wi-time{grid-column:1/-1}.wi-row input[inputmode]{width:80px}.wi-row select,.wi-row input:not([inputmode]){flex:1 1 140px;min-width:0}.wi-picked .wi-main{padding-bottom:80px}.wi-form{gap:8px}.wi-form input{font-size:16px;padding:6px}
  .wi-picked .wi-act{position:fixed;left:0;right:0;bottom:0;z-index:20;margin:0;padding:10px 128px 10px 12px;background:var(--cs-surface,#fff);box-shadow:0 -1px 0 var(--cs-border,#e3e8ef);flex-wrap:wrap}
  .wi-picked .wi-act .wi-ok,.wi-picked .wi-act .wi-err{flex-basis:100%;font-size:12px}.wi-table{display:block;overflow-x:auto}}
</style>
