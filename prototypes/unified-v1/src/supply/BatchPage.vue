<script setup lang="ts">
// 第六批（R23）：六膳门入库批次 · Cretas 主来源。给一线仓管用：一屏完成，一个主按钮「建批次」。
// 左边是黑湖入库单里还没建批次的行（只读选择），右边填批次；到期日由生产日期＋保质期自动算。不回写黑湖。
// 批次库存：按到期日排，剩余天数、临期 / 过期标色；黑湖出库没有批次，所以批次余量按「先到期先出」从黑湖当前在库倒推，标「估算」。
// 对账：Cretas 批次数量 ↔ 黑湖入库单行数量（匹配键：黑湖入库单号＋行），分一致 / 差额 / 只在 Cretas / 只在黑湖。
import {computed,onMounted,ref,watch} from 'vue'
import type {Row} from '../frame/types'
import {rs,S} from '../frame/store'
import {T,readInventory,supplySession,supplyUrl} from './supply-store'
import {fmtQty,fmtTime} from './supply-facts'
import {usePrefs} from './pager'
import {reconcileView,confirmReason} from '../frame/ai-protocol'
import UButton from '@nuxt/ui/components/Button.vue'
interface Batch{id:string;batchNo:string;kind:'production'|'purchase';kindLabel:string;productCode:string;productName:string;unit:string;qty:number;productionDate:string;shelfDays:number;expiryDate:string;storage:string;blOrderNo:string|null;blLineId:string|null;blQty:number|null;blUnit:string|null;projectCode:string|null;weightSource:string;expiredDays?:number;expiredReason?:string;weighingRef?:{verified?:boolean;verifyNote?:string;processName?:string;projectCode?:string;goodNetKg?:number}|null;status:string;at:string;by:string;revokedAt?:string}
interface State{snapshotDay?:string;version:number;batches:Batch[];productConfig:Record<string,{shelfDays:number;storage:string;at:string}>;storageOptions:string[];inventoryAt?:string;inventoryCheckedAt?:string;session:string|null}
const B=ref<State>(),err=ref(''),busy=ref(false)
// 审计退回 C-5：待建列表和批次库存各用各的搜索词
const prefs=usePrefs('unified-lsm-batches-v2',{tab:'create',q:'',qs:''})
const H=(write=false):Record<string,string>=>({...(supplySession()?{'x-acceptance-session':supplySession()}:{}),...(write?{'Content-Type':'application/json','x-prototype-user-action':'local-edit'}:{})})
async function load(){busy.value=true;err.value='';try{if(!T.inv)await readInventory();const r=await fetch(supplyUrl('/batches'),{headers:H()}),b=await r.json();if(!b.success)throw Error(b.message||'读取失败');B.value=b.data;if(!picked.value&&!f.value.productCode)f.value.productionDate=today()}catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
// E 的检验待处置（只读）：HOLD 待处置、BLOCKED 不可用；这两类不进先到期先出建议，批次上标 label 和检验单号
interface Hold{batchNo:string;status:'HOLD'|'BLOCKED';label:string;inspectionNo:string;reason?:string}
const holds=ref<Map<string,Hold>>(new Map()),holdErr=ref('')
async function loadHolds(){try{const s=supplySession();const r=await fetch('/__unified/e/api/batch-holds'+(s?'?acceptanceSession='+encodeURIComponent(s):''),{headers:s?{'x-acceptance-session':s}:{}});const b=await r.json();const list:Hold[]=b?.data?.holds||b?.holds||[];holds.value=new Map(list.map(h=>[h.batchNo,h]));holdErr.value=''}catch{holdErr.value='检验待处置读取失败，先到期先出建议未排除待处置批次'}}
// 审计退回 C-6：库存查询「按批次看」带 ?product= 进来，直接打开批次库存并按产品筛选
onMounted(()=>{prefs.value.q='';prefs.value.qs='';const p=S.query.get('product');if(p){prefs.value.tab='stock';prefs.value.qs=p}void load();void loadHolds()})
const active=computed(()=>(B.value?.batches||[]).filter(b=>!b.revokedAt))
// R28：临期、先到期先出、「近 30 天」里的「今天」按快照日（黑湖采集时点换算北京时间的日期），页面写明；不随电脑时钟漂移
const today=()=>B.value?.snapshotDay||new Date(Date.now()+8*3600e3).toISOString().slice(0,10)
const d10=(x:unknown)=>x?String(x).slice(0,10):''
const addDays=(d:string,n:number)=>new Date(Date.parse(d+'T00:00:00Z')+n*864e5).toISOString().slice(0,10)
const daysLeft=(b:Batch)=>Math.round((Date.parse(b.expiryDate+'T00:00:00Z')-Date.parse(today()+'T00:00:00Z'))/864e5)
// 临期：剩余天数 ≤ 保质期的 20%（至少 3 天）；过期：剩余天数 < 0
const nearLimit=(b:Batch)=>Math.max(3,Math.ceil(b.shelfDays*0.2))
const life=(b:Batch)=>{const d=daysLeft(b);return d<0?{t:'已过期 '+(-d)+' 天',tone:'negative'}:d<=nearLimit(b)?{t:'临期，剩 '+d+' 天',tone:'zero'}:{t:'剩 '+d+' 天',tone:'ok'}}
const unitEq=(a:unknown,b:unknown)=>String(a??'').trim().toLowerCase()===String(b??'').trim().toLowerCase()
// 审计退回 C-5：同一产品单位大小写混写（kg / KG）时统一按产品档案的单位显示
const unitOf=(code:string,u:string)=>{const p=rs('product').find(x=>String(x.productCode)===code);const pu=String(p?.unit??'');return pu&&unitEq(pu,u)?pu:u}

// ---- 黑湖入库单行（成品入库、采购入库），只读选择 ----
interface InLine{key:string;orderNo:string;date:string;kind:'production'|'purchase';type:string;line:Row}
const inLines=computed<InLine[]>(()=>((T.inv?.orders||[]) as unknown as Row[]).filter(o=>o.bizTypeDisplay==='成品入库'||o.bizTypeDisplay==='采购入库').flatMap(o=>((o.lines as Row[])||[]).map(l=>({key:String(o.orderNo)+'#'+String(l.id),orderNo:String(o.orderNo),date:d10(o.bizAt),kind:(o.bizTypeDisplay==='成品入库'?'production':'purchase') as 'production'|'purchase',type:String(o.bizTypeDisplay),line:l}))).sort((a,b)=>b.date.localeCompare(a.date)))
const linked=computed(()=>new Set(active.value.filter(b=>b.blOrderNo).map(b=>b.blOrderNo+'#'+b.blLineId)))
const todo=computed(()=>inLines.value.filter(x=>!linked.value.has(x.key)&&x.date<=today()&&(!prefs.value.q||(x.orderNo+String(x.line.productCode)+String(x.line.productName)).toLowerCase().includes(prefs.value.q.toLowerCase()))))
const products=computed(()=>rs('product'))

// ---- 建批次表单 ----
const blank=()=>({kind:'production' as 'production'|'purchase',productCode:'',productName:'',unit:'',qty:'' as string|number,batchNo:'',productionDate:today(),shelfDays:'' as string|number,storage:'',blOrderNo:'',blLineId:'',projectCode:'',saveDefault:false,weightSource:'手填'})
const f=ref(blank()),fieldErr=ref<{field:string|null;message:string}|null>(null),saved=ref<Batch>(),picked=ref('')
const cfg=computed(()=>f.value.productCode?B.value?.productConfig[f.value.productCode]:undefined)
const expiry=computed(()=>{const n=Number(f.value.shelfDays);return f.value.productionDate&&Number.isInteger(n)&&n>=0&&String(f.value.shelfDays)!==''?addDays(f.value.productionDate,n):''})
async function suggest(){if(!f.value.productCode||!f.value.productionDate)return;try{const r=await fetch(supplyUrl('/batch-no?code='+encodeURIComponent(f.value.productCode)+'&date='+f.value.productionDate),{headers:H()}),b=await r.json();if(b.success)f.value.batchNo=b.data.batchNo}catch{/* 取不到默认批次号时由服务端生成 */}}
function applyCfg(){const c=cfg.value;if(c){f.value.shelfDays=c.shelfDays;f.value.storage=c.storage}}
// 审计退回 C-3：窄屏选单后收起列表、表单置顶
const narrow=ref(false);const listOpen=ref(true)
onMounted(()=>{const mq=window.matchMedia('(max-width:720px)');narrow.value=mq.matches;mq.addEventListener?.('change',e=>{narrow.value=e.matches;if(!e.matches)listOpen.value=true})})
const formEl=ref<HTMLElement>()
function focusForm(){if(narrow.value){listOpen.value=false;setTimeout(()=>formEl.value?.scrollIntoView({block:'start'}),0)}}
function pick(x:InLine){focusForm();picked.value=x.key;saved.value=undefined;fieldErr.value=null;const l=x.line
  f.value={...blank(),kind:x.kind,productCode:String(l.productCode??''),productName:String(l.productName??''),unit:String(l.unitName??''),qty:Number(l.qty)||'',productionDate:x.date||today(),blOrderNo:x.orderNo,blLineId:String(l.id),projectCode:x.kind==='production'?String(l.associatedBizOrderCode??''):''}
  applyCfg();void suggest()}
function manual(){focusForm();picked.value='';saved.value=undefined;fieldErr.value=null;f.value=blank()}
watch(()=>f.value.productCode,c=>{if(picked.value)return;const p=products.value.find(x=>String(x.productCode)===c);if(p){f.value.productName=String(p.productName??'');f.value.unit=f.value.unit||String(p.unit??'')}applyCfg();void suggest()})
watch(()=>f.value.productionDate,()=>void suggest())
async function save(){busy.value=true;fieldErr.value=null;try{const num=(x:unknown)=>String(x??'').trim()===''?null:Number(x)
  const body={...f.value,weighingRef:f.value.weightSource==='称重'?weighRef.value:null,qty:num(f.value.qty),shelfDays:num(f.value.shelfDays),blOrderNo:f.value.blOrderNo||null,blLineId:f.value.blLineId||null,projectCode:f.value.projectCode||null}
  const od=expiry.value?Math.round((Date.parse(today()+'T00:00:00Z')-Date.parse(expiry.value+'T00:00:00Z'))/864e5):0
  if(od>0){const why=await confirmReason({title:'建已过期的批次',object:(f.value.batchNo||'新批次')+' · '+f.value.productName+' '+fmtQty(f.value.qty)+' '+f.value.unit,message:'该批次按快照日 '+today()+' 已过期 '+od+' 天，仍要建？请写明原因，会记进批次。',okLabel:'仍要建',danger:true});if(why===null)return;(body as Record<string,unknown>).expiredReason=why}
  const r=await fetch(supplyUrl('/batch'),{method:'POST',headers:H(true),body:JSON.stringify(body)}),b=await r.json()
  if(!b.success){fieldErr.value={field:b.field||null,message:b.message||'保存失败'};return}
  await load();saved.value=active.value.find(x=>x.id===b.data.batch.id);picked.value='';f.value=blank();weighRef.value=null;fieldErr.value=null}
  catch(e){fieldErr.value={field:null,message:e instanceof Error?e.message:'保存失败'}}finally{busy.value=false}}
// 用户改了内容就收起旧的错误提示
watch(f,()=>{if(fieldErr.value&&!busy.value)fieldErr.value=null},{deep:true})
// ---- A 的称重报工（只读）：生产入库带出良品净重 ----
interface Wrow{projectId:string;projectCode:string;productCode:string;taskId:string;processName:string;isLastProcess:boolean|null;goodNetKg:number;entries:number;lastAt:string}
const weigh=ref<{asOf?:string;rows:Wrow[]}>(),weighErr=ref('')
async function loadWeigh(){weigh.value=undefined;weighErr.value='';if(f.value.kind!=='production'||!f.value.productCode)return
  try{const q=new URLSearchParams({ws:'lsm',product:f.value.productCode});const s=supplySession();if(s)q.set('acceptanceSession',s);const r=await fetch('/__unified/a/api/weighins/inbound?'+q,{headers:s?{'x-acceptance-session':s}:{}});const b=await r.json().catch(()=>null);const d=b?.data??b;if(!r.ok||!d||!Array.isArray(d.rows))throw Error(b?.message||'称重读取失败');weigh.value={asOf:d.asOf,rows:d.rows}}
  catch(e){weighErr.value=e instanceof Error?e.message:'称重读取失败'}}
watch(()=>[f.value.kind,f.value.productCode,f.value.projectCode],()=>void loadWeigh())
// 只取本工单（有工单号时）、本产品；优先最后一道工序；路线缺失（isLastProcess 为空）时列出各工序让人选
const weighOptions=computed(()=>{const rows=(weigh.value?.rows||[]).filter(r=>r.productCode===f.value.productCode&&(!f.value.projectCode||r.projectCode===f.value.projectCode)&&r.goodNetKg>0);const last=rows.filter(r=>r.isLastProcess===true);return last.length?last:rows.filter(r=>r.isLastProcess===null)})
// 与 A / B 口径一致：入库量只取路线最后一道工序的称重；路线已知但最后工序没称重时写「缺」，不拿别的工序顶替。只有路线缺失（isLastProcess 为空）时才列出各工序让人确认
const lastMissing=computed(()=>!weighOptions.value.length&&(weigh.value?.rows||[]).some(r=>r.productCode===f.value.productCode&&(!f.value.projectCode||r.projectCode===f.value.projectCode)&&r.isLastProcess===false))
function useWeigh(r:Wrow){f.value.qty=Number(r.goodNetKg.toFixed(2));f.value.unit='kg';f.value.weightSource='称重';if(!f.value.projectCode)f.value.projectCode=r.projectCode;weighRef.value={projectCode:r.projectCode,taskId:r.taskId,processName:r.processName,isLastProcess:r.isLastProcess,goodNetKg:r.goodNetKg,asOf:weigh.value?.asOf||null}}
const weighRef=ref<Record<string,unknown>|null>(null)
const errFor=(k:string)=>fieldErr.value?.field===k?fieldErr.value.message:''

// ---- 批次库存（先到期先出，余量估算） ----
const stockOf=(code:string,unit:string)=>{let s=0,known=false;for(const x of (T.inv?.stock||[]) as unknown as Row[]){if(String(x.productCode)!==code||!unitEq(x.unitName,unit))continue;const n=Number(x.qtyInWarehouse);if(Number.isFinite(n)){s+=n;known=true}}return known?s:null}
interface Est{b:Batch;remain:number|null}
const groups=computed(()=>{const m=new Map<string,Batch[]>();for(const b of active.value){const k=b.productCode+'|'+b.unit.toLowerCase();m.set(k,[...(m.get(k)||[]),b])}
  const q=prefs.value.qs.trim().toLowerCase()
  return [...m.values()].map(list=>{const sorted=[...list].sort((a,b)=>a.expiryDate.localeCompare(b.expiryDate));const total=stockOf(list[0].productCode,list[0].unit)
    // 黑湖当前在库按「后到期的批次先留着」分配：先出的批次应先被消耗，剩下的就是后到期批次的余量
    let left=total===null?null:Math.max(0,total);const est=new Map<string,number|null>()
    for(const b of [...sorted].reverse()){if(left===null){est.set(b.id,null);continue}const take=Math.min(left,b.qty);est.set(b.id,take);left-=take}
    const rows:Est[]=sorted.map(b=>({b,remain:est.get(b.id)??null}))
    // 建议先出：最早到期、未过期、未被检验标「待处置」的批次（不看估算余量：估算本身就假设了先到期先出，不能反过来用它跳过临期批次）
    const firstRow=rows.find(r=>daysLeft(r.b)>=0&&r.b.status!=='hold'&&!holds.value.has(r.b.batchNo))
    return {code:list[0].productCode,name:list[0].productName,unit:unitOf(list[0].productCode,list[0].unit),total,rows,first:firstRow?.b,firstZero:firstRow?.remain===0}}).filter(g=>!q||(g.code+g.name).toLowerCase().includes(q))})
// 审计退回 C-2：撤销批次（共享填原因弹层），撤销后对应的黑湖入库单行回到待建
function expTip(b:Batch){return b.expiredDays?'建时已过期 '+b.expiredDays+' 天 · 原因：'+(b.expiredReason||'未写'):''}
function srcTip(b:Batch){const x=expTip(b);const t=srcTip0(b);return x?t+' ｜ '+x:t}
function srcTip0(b:Batch){const w=b.weighingRef;if(b.weightSource==='称重')return '数量取自 A 的称重良品'+(w?.processName?'（'+(w.projectCode||'')+' · '+w.processName+(w.goodNetKg!=null?' '+w.goodNetKg+' kg':'')+'）':'')+(w?.verifyNote?'；'+w.verifyNote:'');return w?.verifyNote||'数量为手工填写，没有关联 A 的称重'}
async function revokeBatch(b:Batch){const reason=await confirmReason({title:'撤销批次',object:b.batchNo+' · '+b.productName+' '+fmtQty(b.qty)+' '+unitOf(b.productCode,b.unit),message:'撤销后这个批次不再参与先到期先出和对账'+(b.blOrderNo?'，黑湖入库单 '+b.blOrderNo+' 这一行回到「待建批次」':'')+'。不回写黑湖；产品默认值不受影响。',okLabel:'确认撤销',danger:true});if(reason===null)return
  busy.value=true;try{const r=await fetch(supplyUrl('/batch-revoke'),{method:'POST',headers:H(true),body:JSON.stringify({id:b.id,reason})}),j=await r.json();if(!j.success)throw Error(j.message||'撤销失败');await load()}catch(e){err.value=e instanceof Error?e.message:'撤销失败'}finally{busy.value=false}}

// ---- 对账（黑湖入库单 ↔ Cretas 批次）：数据取服务端 /batch-reconcile（与 D 的对账汇总页同一份），用 D 的共享组件显示 ----
// 键为「黑湖入库单号 · 第 N 行」（不露内部 ID）；两边单位不同的配对服务端单列 mismatch，不交给组件
interface RRow{key:string;label:string;owner:string;cretas:{qty:number;unit:string;batchNo?:string}|null;blacklake:{qty:number;unit:string}|null}
const R=ref<{asOf:string|null;snapshotDay:string;rows:RRow[];mismatch:RRow[]}>(),rErr=ref('')
async function loadRecon(){rErr.value='';try{const r=await fetch(supplyUrl('/batch-reconcile'),{headers:H()}),b=await r.json();if(!b.success)throw Error(b.message||'对账读取失败');R.value=b.data}catch(e){rErr.value=e instanceof Error?e.message:'对账读取失败'}}
watch(()=>[prefs.value.tab,B.value?.version],()=>{if(prefs.value.tab==='recon')void loadRecon()},{immediate:true})
const reconHost=ref<HTMLElement>();let rv:ReturnType<typeof reconcileView>|undefined
type Rec={__k:string;__l:string;__o:string;qty:number;unit:string}
const reconOpts=()=>{const rows=R.value?.rows||[];const pack=(x:RRow,side:'cretas'|'blacklake'):Rec=>({__k:x.key,__l:x.label,__o:x.owner,qty:x[side]!.qty,unit:x[side]!.unit})
  return {cretas:rows.filter(x=>x.cretas).map(x=>pack(x,'cretas')),blacklake:rows.filter(x=>x.blacklake).map(x=>pack(x,'blacklake')),key:(r:Rec)=>r.__k,
  fields:[{key:'qty',label:'入库数量',digits:2,cretas:(r:Rec)=>r.qty,blacklake:(r:Rec)=>r.qty}],
  // 审计退回 C-5：一致的行不写找谁
  owner:(row:{key:string;state:string})=>row.state==='match'?'':row.state==='cretasOnly'?'仓库（核对是否漏开黑湖入库单）':(rows.find(x=>x.key===row.key)?.owner||'仓库'),
  label:(r:Rec)=>r.__l+'（'+r.unit+'）'}}
watch(()=>[prefs.value.tab,reconHost.value,R.value],()=>{if(prefs.value.tab!=='recon'||!reconHost.value||!R.value)return
  const extra={title:'入库批次 ↔ 黑湖入库单',asOf:R.value.asOf,keyLabel:'黑湖入库单 · 行',fileName:'六膳门入库批次对账'}
  if(rv&&reconHost.value.contains(rv.element))rv.refresh({...reconOpts(),...extra} as never);else{rv?.element.remove();rv=reconcileView(reconHost.value,{...reconOpts(),...extra} as never)}},{flush:'post'})
</script>
<template><div class="batch-page">
<p class="supply-note">入库批次以 Cretas 为主来源：批次号、生产日期、保质期和到期日、储存温度、入库数量都以这里录入为准；黑湖入库单只读，用来关联和对账，不回写黑湖。黑湖单据快照时点 {{fmtTime(B?.inventoryAt)}}（每天 08:00 采集）；临期、剩余天数和先到期先出<b>按快照日 {{B?.snapshotDay||'未知'}}</b> 计算。<template v-if="B?.session"> · 验收会话 {{B.session}}</template></p>
<p v-if="err" role="alert" class="error">{{err}}</p>
<div class="cs-tabs lsm-tabs supply-tabs" role="tablist" aria-label="入库批次"><button role="tab" :aria-selected="prefs.tab==='create'" @click="prefs.tab='create'">建批次 <small>{{todo.length}} 待建</small></button><button role="tab" :aria-selected="prefs.tab==='stock'" @click="prefs.tab='stock'">批次库存 <small>{{active.length}}</small></button><button role="tab" :aria-selected="prefs.tab==='recon'" @click="prefs.tab='recon'">对账（黑湖入库单）</button></div>

<section v-if="prefs.tab==='create'" class="card batch-create">
<button v-if="narrow&&!listOpen" type="button" class="op reopen" @click="listOpen=true">← 重新选黑湖入库单</button>
<div v-show="!narrow||listOpen" class="batch-left"><div class="section-head"><h2>待建批次的黑湖入库单</h2></div><button type="button" class="op" @click="manual">不关联黑湖单，手动建批次</button>
<input v-model="prefs.q" placeholder="入库单号、产品" aria-label="搜索入库单">
<ul class="batch-todo"><li v-for="x in todo.slice(0,40)" :key="x.key" :aria-selected="picked===x.key" @click="pick(x)"><b>{{x.orderNo}}</b> <span class="src-tag">黑湖</span> {{x.type}} · {{x.date}}<br><span>{{x.line.productName}}</span> <span class="muted">{{fmtQty(x.line.qty)}} {{x.line.unitName}}</span></li><li v-if="!todo.length" class="muted">没有待建批次的入库单。</li></ul>
<p v-if="todo.length>40" class="muted">只列最近 40 行，用上面的搜索找其他单。</p></div>
<form ref="formEl" class="batch-form" @submit.prevent="save">
<div class="section-head"><h2>{{picked?'按黑湖入库单建批次':'手动建批次'}}</h2></div>
<div class="row"><label>入库类型<select v-model="f.kind" :disabled="!!picked"><option value="production">生产入库</option><option value="purchase">采购入库</option></select></label>
<label>关联黑湖入库单<input :value="f.blOrderNo||'未关联黑湖单'" readonly><small v-if="errFor('blOrderNo')" class="field-err">{{errFor('blOrderNo')}}</small></label></div>
<div class="row"><label>产品<select v-model="f.productCode" :disabled="!!picked"><option value="">请选择</option><option v-for="p in products" :key="String(p.productCode)" :value="String(p.productCode)">{{p.productCode}} {{p.productName}}</option></select><small v-if="errFor('productCode')" class="field-err">{{errFor('productCode')}}</small></label>
<label>数量<span class="with-unit"><input v-model="f.qty" type="number" min="0" step="any"><input v-model="f.unit" class="unit" aria-label="单位"></span><small v-if="errFor('qty')||errFor('unit')" class="field-err">{{errFor('qty')||errFor('unit')}}</small><template v-else-if="f.kind==='production'"><span v-for="w in weighOptions" :key="w.taskId" class="weigh-pick"><UButton type="button" size="xs" color="primary" variant="soft" @click="useWeigh(w)">带出称重良品 {{fmtQty(w.goodNetKg)}} kg</UButton><small class="muted">{{w.projectCode}} · {{w.processName}}{{w.isLastProcess===null?'（路线缺失，请确认是入库工序）':''}} · {{w.entries}} 笔</small></span><small v-if="!weighOptions.length" class="muted">{{weighErr?'称重读取失败：'+weighErr:lastMissing?'缺：最后一道工序还没有称重，不拿其他工序顶替；数量按黑湖入库单带出，可改':weigh?'这张工单还没有称重报工，数量按黑湖入库单带出，可改':'正在读取称重…'}}</small><small v-if="f.weightSource==='称重'" class="muted">数量来源：Cretas 称重</small></template></label></div>
<div class="row"><label>批次号<input v-model="f.batchNo" placeholder="自动生成，可改"><small v-if="errFor('batchNo')" class="field-err">{{errFor('batchNo')}}</small><small v-else class="muted">默认：产品码-生产日期-流水号</small></label>
<label>生产日期<input v-model="f.productionDate" type="date"><small v-if="errFor('productionDate')" class="field-err">{{errFor('productionDate')}}</small></label></div>
<div class="row"><label>保质期（天）<input v-model="f.shelfDays" type="number" min="0" step="1" :placeholder="cfg?'':'该产品还没有保质期，请填写'"><small v-if="errFor('shelfDays')" class="field-err">{{errFor('shelfDays')}}</small><small v-else-if="cfg" class="muted">按产品配置带出（Cretas）</small></label>
<label>到期日<input :value="expiry||'填保质期后自动算'" readonly></label></div>
<div class="row"><label>储存温度<select v-model="f.storage"><option value="">请选择</option><option v-for="s in B?.storageOptions||[]" :key="s" :value="s">{{s}}</option></select><small v-if="errFor('storage')" class="field-err">{{errFor('storage')}}</small></label>
<label class="check-col"><span class="check"><input v-model="f.saveDefault" type="checkbox"> 把保质期和储存温度记为这个产品的默认值</span><small class="muted">勾选后会影响这个产品以后建的批次（带出用）；撤销批次不会撤销默认值，再建一次并勾选就能改</small></label></div>
<p v-if="fieldErr&&!fieldErr.field" role="alert" class="error">{{fieldErr.message}}</p>
<div class="batch-actions"><UButton type="submit" color="primary" size="md" :loading="busy">建批次</UButton></div>
<div v-if="saved" class="batch-saved" role="status">已建批次 <b>{{saved.batchNo}}</b>：{{saved.productName}} {{fmtQty(saved.qty)}} {{saved.unit}}，生产 {{saved.productionDate}}，到期 {{saved.expiryDate}}（{{life(saved).t}}），{{saved.storage}}，{{saved.blOrderNo?'关联黑湖 '+saved.blOrderNo:'未关联黑湖单'}}；数量来源：{{saved.weightSource==='称重'?'Cretas 称重':'手填'}}。<span class="src-tag">Cretas</span></div>
</form>
</section>

<section v-else-if="prefs.tab==='stock'" class="card">
<div class="section-head"><h2>批次库存 · {{groups.length}} 个产品</h2><small>先到期先出（跳过已过期和检验待处置 / 不可用的批次）；余量为估算（黑湖出库没有批次，按先到期先出从黑湖当前在库倒推）</small></div><p v-if="holdErr" role="alert" class="error">{{holdErr}}</p>
<input v-model="prefs.qs" placeholder="产品编码、名称" aria-label="搜索产品">
<div v-for="g in groups" :key="g.code+g.unit" class="batch-group"><h3>{{g.code}} {{g.name}} <small class="muted">黑湖在库 {{g.total===null?'未返回':fmtQty(g.total)+' '+g.unit}}</small> <template v-if="g.first"><span class="supply-badge" data-tone="info">建议先出：{{g.first.batchNo}}（到期 {{g.first.expiryDate}}）</span><small v-if="g.firstZero" class="muted">估算余量为 0，出库前请先确认实物还在</small></template><span v-else class="supply-badge" data-tone="zero">没有可建议的批次（都已过期或待处置）</span></h3>
<table class="supply-table"><thead><tr><th>批次号</th><th>类型</th><th>生产日期</th><th>到期日</th><th>剩余天数</th><th class="num">入库数量</th><th class="num">余量（估算）</th><th>储存</th><th>黑湖入库单</th><th>来源</th><th>操作</th></tr></thead><tbody>
<tr v-for="r in g.rows" :key="r.b.id" :data-life="life(r.b).tone"><td><b>{{r.b.batchNo}}</b><span v-if="r.b.expiredDays" class="supply-badge" data-tone="negative" :title="expTip(r.b)">建时已过期</span><span v-if="holds.get(r.b.batchNo)" class="supply-badge" data-tone="negative" :title="holds.get(r.b.batchNo)?.reason||''">{{holds.get(r.b.batchNo)?.label}}（{{holds.get(r.b.batchNo)?.inspectionNo}}）</span></td><td>{{r.b.kindLabel}}</td><td>{{r.b.productionDate}}</td><td>{{r.b.expiryDate}}</td><td><span class="supply-badge" :data-tone="life(r.b).tone" :title="expTip(r.b)||undefined">{{life(r.b).t}}</span></td><td class="num">{{fmtQty(r.b.qty)}} {{unitOf(r.b.productCode,r.b.unit)}}</td><td class="num">{{r.remain===null?'未返回':fmtQty(r.remain)}}</td><td>{{r.b.storage}}</td><td>{{r.b.blOrderNo||'未关联黑湖单'}}</td><td><span class="src-tag" :title="srcTip(r.b)">Cretas · {{r.b.weightSource==='称重'?'称重':'手填'}}</span></td><td><button type="button" class="op" :disabled="busy" @click="revokeBatch(r.b)">撤销</button></td></tr></tbody></table></div>
<p v-if="!groups.length" class="muted">{{active.length&&prefs.qs?'没有匹配「'+prefs.qs+'」的批次。':'还没有批次。在「建批次」里按黑湖入库单建。'}}</p>
</section>

<section v-else class="card">
<div class="section-head"><h2>对账 · Cretas 批次 ↔ 黑湖入库单</h2><small>Cretas 录入为准；差异由人决定是否回黑湖修。「只在黑湖」只列最近 30 天还没建批次的入库单行</small></div>
<p v-if="rErr" role="alert" class="error">{{rErr}}</p><p class="muted">「只在黑湖」按快照日 {{R?.snapshotDay||'未知'}} 往前 30 天。数据与「数据与分析 › 对账」汇总页同一来源（服务端 /batch-reconcile）。</p>
<div ref="reconHost"></div>
<template v-if="R?.mismatch.length"><h3 class="mismatch-h">单位不同，不比较数量（{{R.mismatch.length}}）</h3>
<table class="supply-table"><thead><tr><th>黑湖入库单 · 行</th><th>产品 · 批次</th><th class="num">Cretas</th><th class="num">黑湖</th><th>找谁</th></tr></thead><tbody><tr v-for="m in R.mismatch" :key="m.key"><td>{{m.key}}</td><td>{{m.label}}</td><td class="num">{{fmtQty(m.cretas?.qty)}} {{m.cretas?.unit}}</td><td class="num">{{fmtQty(m.blacklake?.qty)}} {{m.blacklake?.unit}}</td><td>仓库：核对单位（如按重量称、按盒开单）</td></tr></tbody></table></template>
</section>
</div></template>
<style scoped>
.batch-create{display:grid;grid-template-columns:minmax(240px,1fr) 2fr;gap:16px}
.batch-create>*{min-width:0}.batch-form input:not([type=checkbox]),.batch-form select{width:100%;min-width:0;box-sizing:border-box}.with-unit .unit{width:70px}
.batch-left input{width:100%;margin:6px 0}
.batch-todo{list-style:none;margin:0;padding:0;max-height:420px;overflow:auto;font-size:13px}
.batch-todo li{padding:6px 8px;border-bottom:1px solid var(--cs-border);cursor:pointer}
.batch-todo li[aria-selected=true]{background:var(--cs-primary-soft)}
.batch-form .row{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:10px}
.batch-form label{display:flex;flex-direction:column;gap:4px;font-size:13px}
.batch-form .check{display:flex;flex-direction:row;align-items:center;gap:6px}.check-col{display:flex;flex-direction:column;gap:2px}
.reopen{margin-bottom:8px}
.with-unit{display:flex;gap:6px}.with-unit input{flex:1}.with-unit .unit{flex:0 0 70px}
.weigh-pick{display:flex;flex-wrap:wrap;gap:4px 8px;align-items:center;margin-top:4px}
.field-err{color:var(--cs-danger)}
.batch-actions{margin-top:6px}
.batch-saved{margin-top:10px;padding:8px 10px;border:1px solid var(--cs-border);border-radius:6px;font-size:13px}
.batch-group{margin:12px 0}.batch-group h3{font-size:14px;margin:6px 0;display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.batch-group td:first-child{white-space:normal;min-width:190px}.batch-group td:first-child .supply-badge{display:inline-block;margin-top:2px}
.mismatch-h{font-size:14px;margin:14px 0 6px}
@media (max-width:720px){.batch-create{grid-template-columns:1fr}.batch-form .row{grid-template-columns:1fr}
  /* 审计退回 C-3：主按钮固定在底部 */
  .batch-form{padding-bottom:64px}.batch-actions{position:fixed;left:0;right:0;bottom:0;z-index:20;background:var(--cs-surface);padding:8px 15px;border-top:1px solid var(--cs-border)}
  /* 右下角是全局「解读」悬浮按钮，主按钮放在左侧、不被它挡住 */
  .batch-actions :deep(button){width:calc(100% - 190px);min-width:140px;justify-content:center}}
</style>
