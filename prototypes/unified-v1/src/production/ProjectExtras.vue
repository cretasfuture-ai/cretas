<script setup lang="ts">
// 六膳门工单详情 · 第三批：「委外」区与「操作记录」（黑湖新接口导出，只读；后端 a-api.cjs project-extras）。
// 委外：outsource_task 按工单 id 关联，发料/退料、收货/退货按委外任务号接上；没有工单号的整单委外如实列出「未关联任何工单」，不按产品猜配。
// 操作记录：用 D 的 B6 共享组件（changeLogHTML / changeLogDrawer），来源标「黑湖」；黑湖只给操作类型和字段类别，没有字段级前后值，不补造。
import {computed,onMounted,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import {fmtNumber,fmtMoney,sourceTagHTML,changeLogHTML,changeLogDrawer} from '../frame/ai-protocol'
import {acceptanceSession} from './session'
interface Move{id:number;orderCode:string;outsourceTaskCode:string;postTypeName:string;amount:number|null;pricingUnitName:string|null;bizAt:string|null;productName:string|null;shortName:string|null}
interface Task{id:number;taskCode:string;orderCode:string;processName:string|null;productName:string|null;shortName:string|null;outsourcePlanAmount:number|null;issuedAmount:number|null;receivedGoodAmount:number|null;receivedDefectiveAmount:number|null;pendingIssueAmount:number|null;pendingReceiveAmount:number|null;productUnit:string|null;pricingUnitName:string|null;price:number|null;amount:number|null;expectEndTime:string|null}
interface Log{at:string|null;object:string;instanceId:string;instanceCode:string|null;action:string|null;fieldType:string|null;source:string;deletedObject?:boolean}
interface Data{revision:string;checkedAt:string;outsource:{tasks:Task[];delivers:Move[];receives:Move[];unlinked:{orderCode:string;tasks:number;products:string[];reason:string}[];note:string|null};operationLog:{entries:Log[];counts:Record<string,number>;total:number;note:string;deletedReports?:string[]}}
const props=defineProps<{projectId:string;projectCode:string}>()
const data=ref<Data>(),err=ref(''),busy=ref(false)
const session=acceptanceSession()
async function load(){busy.value=true;err.value='';try{const p=new URLSearchParams({ws:'lsm',project:props.projectId});if(session)p.set('acceptanceSession',session);const b=await (await fetch('/__unified/a/api/project-extras?'+p)).json();if(!b.success)throw Error(b.message==='未找到'?'委外与操作记录服务尚未接入统一入口':b.message);data.value=b.data}catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
// 黑湖操作日志 → B6 共享组件的条目格式；人员字段采集时已剔除，前后值黑湖未提供
const entries=computed(()=>(data.value?.operationLog.entries||[]).map(e=>({at:e.at||'时间未返回',by:'黑湖（人员已剔除）',action:(e.action||'操作类型未返回')+' · '+e.object+(/^报工/.test(e.object)?' '+e.instanceId:'')+(e.deletedObject?'（快照里已没有这条报工）':''),reason:e.fieldType?'字段类别：'+e.fieldType+'；字段级前后值黑湖未提供':'字段级前后值黑湖未提供',source:'blacklake' as const,changes:[]})))
const logHTML=computed(()=>changeLogHTML(entries.value.slice(0,8),{empty:'黑湖操作日志里没有与这张工单相关的记录'}))
function openAll(){changeLogDrawer({title:'操作记录 · 工单 '+props.projectCode,object:'黑湖操作日志（'+entries.value.length+' 条，只读）',load:()=>entries.value})}
const q=(v:number|null,u:string|null)=>v===null||v===undefined?'未返回':fmtNumber(v)+(u?' '+u:'')
onMounted(load);watch(()=>props.projectId,load)
</script>
<template><section class="detail-section project-extras">
<h3>委外 <span v-html="sourceTagHTML('blacklake')"/></h3>
<p v-if="err" class="pe-err" role="alert">{{err}}</p>
<template v-if="data">
<p v-if="data.outsource.note" class="muted">{{data.outsource.note}}。</p>
<table v-if="data.outsource.tasks.length"><thead><tr><th>委外任务</th><th>工序</th><th>委外方</th><th>计划</th><th>已发料</th><th>已收良品</th><th>不良</th><th>待收</th><th>金额</th></tr></thead><tbody>
<tr v-for="t in data.outsource.tasks" :key="t.id"><td>{{t.taskCode}}<small>{{t.orderCode}}</small></td><td>{{t.processName||'未返回'}}</td><td>{{t.shortName||'未返回'}}</td><td>{{q(t.outsourcePlanAmount,t.productUnit)}}</td><td>{{q(t.issuedAmount,t.productUnit)}}</td><td>{{q(t.receivedGoodAmount,t.productUnit)}}</td><td>{{q(t.receivedDefectiveAmount,t.productUnit)}}</td><td>{{q(t.pendingReceiveAmount,t.productUnit)}}</td><td>{{t.amount===null?'未返回':fmtMoney(t.amount,{currency:'（币种待确认）'})}}</td></tr></tbody></table>
<table v-if="data.outsource.delivers.length||data.outsource.receives.length"><thead><tr><th>收发</th><th>委外任务</th><th>数量</th><th>业务时间</th></tr></thead><tbody>
<tr v-for="m in [...data.outsource.delivers,...data.outsource.receives]" :key="m.id"><td>{{m.postTypeName}}</td><td>{{m.outsourceTaskCode}}</td><td>{{q(m.amount,m.pricingUnitName)}}</td><td>{{m.bizAt||'未返回'}}</td></tr></tbody></table>
<p v-else-if="data.outsource.tasks.length" class="muted">黑湖没有这些委外任务的发料、退料、收货、退货记录。</p>
<details v-if="data.outsource.unlinked.length" class="pe-unlinked"><summary>另有 {{data.outsource.unlinked.length}} 张黑湖委外单没有关联任何工单</summary><p v-for="u in data.outsource.unlinked" :key="u.orderCode">{{u.orderCode}} · {{u.tasks}} 个任务 · {{u.products.join('、')}} · {{u.reason}}；不按产品或日期归到工单。</p></details>
</template>
<p v-else-if="busy" class="muted">正在读取黑湖委外…</p>
<h3>操作记录 <span v-html="sourceTagHTML('blacklake')"/></h3>
<template v-if="data"><p class="muted">{{data.operationLog.note}}。与本工单相关 {{entries.length}} 条（{{Object.entries(data.operationLog.counts).map(([k,v])=>k+' '+v).join('、')||'无'}}），黑湖日志共 {{data.operationLog.total}} 条。<template v-if="data.operationLog.deletedReports?.length">其中 {{data.operationLog.deletedReports.length}} 条报工（{{data.operationLog.deletedReports.join('、')}}）在当前快照里已不存在，日志按工单号保留。</template>快照 {{data.revision.slice(0,12)}}。</p>
<div class="pe-log" v-html="logHTML"/><UButton v-if="entries.length>8" size="xs" color="neutral" variant="outline" @click="openAll">查看全部 {{entries.length}} 条</UButton></template>
</section></template>
<style scoped>
.project-extras table{width:100%;font-size:12px;border-collapse:collapse;margin:6px 0}.project-extras td,.project-extras th{height:32px;padding:4px;border-bottom:1px solid var(--cs-border,#e6ebf0);text-align:left;white-space:nowrap;font-variant-numeric:tabular-nums}.project-extras small{display:block;color:#8a96a6;font-size:11px}
.muted{color:#748297;font-size:12px}.pe-err{color:var(--cs-danger,#c53b3b);font-size:12px}.pe-unlinked{font-size:12px;color:#5e6b7d;margin:6px 0}.pe-log{font-size:12px}
</style>
