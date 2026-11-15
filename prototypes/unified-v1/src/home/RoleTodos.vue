<script setup lang="ts">
// 第六批（R23）D③：六膳门首页按岗位的待办。只读，数据口径：
//  （第六批审计退回 D-3/D-4、R28：名称改「待称重任务（执行中）」；「今天」= 快照日；库存/单据时点用 materialAt，采集核验用 checkedAt）
//  车间 · 待称重任务（执行中）：黑湖工序任务「执行中」（processStatus=10，取黑湖最近一次采集）且本会话 Cretas 还没有称重记录的任务；
//  仓库 · 待建批次：近 7 天的黑湖「采购入库 / 成品入库」单里，还没有被任何 Cretas 批次关联的单；
//  仓库 · 临期批次：Cretas 批次（未撤销）到期日在今天起 7 天内；已过期单列。到期按北京时间日历日算。
import {computed,onMounted,ref} from 'vue'
import type {Source,Row} from '../frame/types'
const props=defineProps<{source:Source;orders:(Row&{lines?:Row[]})[]|undefined;inventoryAt?:string}>()
const role=ref<'shop'|'store'>('shop'),weighed=ref<Set<string>|null>(null),batches=ref<Row[]|null>(null),err=ref<Record<string,string>>({})
const session=()=>{const q=new URLSearchParams(location.hash.split('?')[1]||'');let s=q.get('acceptanceSession')||'';try{if(!s&&window.top)s=new URLSearchParams(window.top.location.search).get('acceptanceSession')||new URLSearchParams((window.top.location.hash.split('?')[1])||'').get('acceptanceSession')||''}catch{/* 读不到顶层地址 */}return /^test-[a-z0-9-]{8,64}$/.test(s)?s:''}
const headers=():Record<string,string>=>{const s=session();return s?{'x-acceptance-session':s}:{}}
// R28：六膳门「今天」= 快照日（采集核验 checkedAt 换算北京时间的日期），不随电脑时钟漂移
const bjDay=(t:number)=>new Date(t+8*3600e3).toISOString().slice(0,10)
const snapDay=computed(()=>{const t=Date.parse(String(props.source.checkedAt||''));return Number.isFinite(t)?bjDay(t):bjDay(Date.now())})
const bjToday=()=>snapDay.value
const fmtAt=(v?:string|null)=>{const t=Date.parse(String(v||''));return Number.isFinite(t)?new Date(t).toLocaleString('zh-CN',{hour12:false,timeZone:'Asia/Shanghai'}):'未知'}
const daysBetween=(a:string,b:string)=>Math.round((Date.parse(a+'T00:00:00Z')-Date.parse(b+'T00:00:00Z'))/864e5)
async function load(){
  try{const r=await fetch('/__unified/a/api/weighins?ws=lsm',{headers:headers()}),b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'称重记录读取失败');const d=b.data||{};weighed.value=new Set([...(d.entries||[]).filter((e:Row)=>!e.revoked&&!e.revokedAt).map((e:Row)=>String(e.taskId)),...Object.keys(d.tasks||{})])}catch(e){err.value.shop=e instanceof Error?e.message:'读取失败';weighed.value=null}
  try{const r=await fetch('/__lsm/supply/batches',{headers:headers()}),b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'批次读取失败');batches.value=(b.data.batches||[]).filter((x:Row)=>!x.revokedAt)}catch(e){err.value.store=e instanceof Error?e.message:'读取失败';batches.value=null}
}
onMounted(load)
const projects=computed(()=>new Map((props.source.resources.project||[]).map(p=>[String(p.id),p])))
const toWeigh=computed(()=>{if(!weighed.value)return null;return (props.source.resources.task||[]).filter(t=>String(t.processStatus)==='10'&&!weighed.value!.has(String(t.id))).map(t=>({id:String(t.id),code:String(projects.value.get(String(t.projectId))?.projectCode||t.projectId),process:String(t.processName||''),product:String(projects.value.get(String(t.projectId))?.productName||t.productCode||'')}))})
const toBatch=computed(()=>{if(!batches.value||!props.orders)return null;const linked=new Set(batches.value.map(b=>String(b.blOrderNo||'')).filter(Boolean)),today=bjToday();
  return props.orders.filter(o=>['采购入库','成品入库'].includes(String(o.bizTypeDisplay))&&!linked.has(String(o.orderNo))&&o.bizAt&&daysBetween(today,String(o.bizAt).slice(0,10))<=7&&daysBetween(today,String(o.bizAt).slice(0,10))>=0).map(o=>({no:String(o.orderNo),type:String(o.bizTypeDisplay),at:String(o.bizAt).slice(0,16),lines:(o.lines||[]).length}))})
const expiring=computed(()=>{if(!batches.value)return null;const today=bjToday();return batches.value.filter(b=>b.expiryDate).map(b=>({no:String(b.batchNo),product:String(b.productName||b.productCode||''),exp:String(b.expiryDate),left:daysBetween(String(b.expiryDate),today)})).filter(b=>b.left<=7).sort((a,b)=>a.left-b.left)})
const go=(h:string)=>{location.hash=h}
const top5=<T,>(l:T[]|null)=>(l||[]).slice(0,5)
</script>
<template><section class="card d-todos" aria-label="岗位待办">
  <div class="section-head"><h2>岗位待办</h2><div class="d-role" role="tablist"><button role="tab" :aria-selected="role==='shop'" @click="role='shop'">车间</button><button role="tab" :aria-selected="role==='store'" @click="role='store'">仓库</button></div><button class="operation-link" @click="load">刷新</button></div>
  <template v-if="role==='shop'">
    <div class="d-todo-block"><div class="d-todo-head"><b>待称重任务（执行中）</b><strong>{{toWeigh?toWeigh.length:'—'}}</strong><small>黑湖「执行中」且 Cretas 还没有称重的工序任务 · 采集核验 {{fmtAt(source.checkedAt)}}</small></div>
      <p v-if="err.shop" class="d-todo-err">称重记录读取失败（{{err.shop}}），不显示数量。</p>
      <ul v-else-if="toWeigh&&toWeigh.length"><li v-for="t in top5(toWeigh)" :key="t.id"><button class="operation-link" @click="go('#/production/weigh?task='+t.id)">{{t.code}} · {{t.process}}</button><small>{{t.product}}</small></li></ul>
      <p v-else-if="toWeigh" class="empty">没有待称重的执行中任务。</p>
      <button v-if="toWeigh&&toWeigh.length>5" class="operation-link" @click="go('#/production/weigh')">全部 {{toWeigh.length}} 个 → 称重报工</button></div>
  </template>
  <template v-else>
    <div class="d-todo-block"><div class="d-todo-head"><b>待建批次的入库单</b><strong>{{toBatch?toBatch.length:'—'}}</strong><small>近 7 天（按快照日 {{snapDay}}）黑湖采购入库、成品入库里还没关联 Cretas 批次的单 · 库存／单据快照 {{fmtAt(inventoryAt)}}</small></div>
      <p v-if="err.store" class="d-todo-err">批次读取失败（{{err.store}}），不显示数量。</p>
      <ul v-else-if="toBatch&&toBatch.length"><li v-for="o in top5(toBatch)" :key="o.no"><button class="operation-link" @click="go('#/lsm/inbound-batches?order='+o.no)">{{o.no}}</button><small>{{o.type}} · {{o.at}} · {{o.lines}} 行</small></li></ul>
      <p v-else-if="toBatch" class="empty">近 7 天的入库单都已建批次。</p>
      <button v-if="toBatch&&toBatch.length>5" class="operation-link" @click="go('#/lsm/inbound-batches')">全部 {{toBatch.length}} 张 → 入库批次</button></div>
    <div class="d-todo-block"><div class="d-todo-head"><b>临期批次</b><strong>{{expiring?expiring.length:'—'}}</strong><small>Cretas 批次 7 天内到期（含已过期），按快照日 {{snapDay}} 算</small></div>
      <ul v-if="expiring&&expiring.length"><li v-for="b in top5(expiring)" :key="b.no"><button class="operation-link" @click="go('#/lsm/inbound-batches?batch='+b.no)">{{b.no}}</button><small>{{b.product}} · 到期 {{b.exp}} · <span :class="b.left<0?'d-exp':'d-near'">{{b.left<0?'已过期 '+(-b.left)+' 天':b.left===0?'今天到期':'剩 '+b.left+' 天'}}</span></small></li></ul>
      <p v-else-if="expiring" class="empty">7 天内没有到期的批次。</p></div>
  </template>
</section></template>
<style scoped>
.d-todos{margin-bottom:18px}.d-todos .section-head{display:flex;align-items:center;gap:12px}.d-role{display:flex;gap:4px}.d-role button{border:1px solid #d9e2eb;background:#fff;border-radius:14px;padding:3px 12px;font-size:12px;cursor:pointer}.d-role button[aria-selected=true]{background:#1b65a8;border-color:#1b65a8;color:#fff}.d-todos .operation-link{background:none;border:0;color:#1b65a8;font-size:12px;cursor:pointer;padding:0}
.d-todos .section-head .operation-link{margin-left:auto}.d-todo-block{padding:12px 16px;border-top:1px solid #edf1f5}.d-todo-head{display:flex;align-items:baseline;gap:10px;flex-wrap:wrap}.d-todo-head b{font-size:13px;font-weight:600}.d-todo-head strong{font-size:22px;color:#263e56;font-variant-numeric:tabular-nums}.d-todo-head small{flex-basis:100%;font-size:11px;color:#7b8c9f}
.d-todo-block ul{list-style:none;margin:8px 0 4px;padding:0}.d-todo-block li{display:flex;gap:10px;align-items:baseline;padding:4px 0;border-bottom:1px dashed #edf1f5;font-size:12px}.d-todo-block li small{color:#7b8c9f}.d-todo-err{font-size:12px;color:#b3261e}.d-exp{color:#b3261e}.d-near{color:#885910}.empty{font-size:12px;color:#7b8c9f}
</style>
