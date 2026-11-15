<script setup lang="ts">
// 味美 · 生产链页面级新视图（DECISIONS A1 / R4）：只读列表，写操作（新建、录入、小结、拣货、签收、批准）回到味美原页面完成。
// 1) 生产计划：默认 9 列，「下一步」与工期前移，状态中文；2) 领料：搜索、版本核对、实耗「未报工」标记；3) 报工撤回审批：人名代替 ID。
// 数量与状态由代码判定（progress.ts 同一规则），缺失显示原因，不当 0。
import {computed,onMounted,ref,watch} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import type {Row} from '../frame/types'
import {S} from '../frame/store'
import {yget,num,day,nativeHref,PLAN_STATUS,REQ_STATUS,REV_STATUS,REV_SCOPE,YD_SESSION} from './ydwm-a'
import {progress,pct} from './progress'
import {fmtQty} from '../frame/ai-protocol'
import {registerAiObject,setAiObject} from '../frame/ai-objects'
const page=computed(()=>S.path==='/production/material-flow'?'flow':S.path==='/production/approval'?'reversal':'plans')
const plans=ref<Row[]>([]),reqs=ref<Row[]>([]),revs=ref<Row[]>([]),users=ref<Row[]>([]),busy=ref(false),err=ref(''),open=ref(''),q=ref(''),status=ref('open'),showAll=ref(false)
const KEY='unified-ydwm-production-a-v1'
function persist(){try{localStorage.setItem(KEY+':'+page.value,JSON.stringify({q:q.value,status:status.value}))}catch{/* 偏好未保存不影响业务 */}}
function restore(){q.value='';status.value=page.value==='plans'?'open':'';try{const s=JSON.parse(localStorage.getItem(KEY+':'+page.value)||'null');if(s){q.value=s.q||'';status.value=s.status??status.value}}catch{/* 默认 */}}
async function read(){busy.value=true;err.value='';try{
  const [p,r,u]=await Promise.all([yget<Row[]>('production-plans?page=1&size=500'),yget<Row[]>('material-requisitions?page=1&size=500').catch(()=>[] as Row[]),yget<Row[]>('users?page=1&size=200').catch(()=>[] as Row[])])
  plans.value=p;reqs.value=r;users.value=u
  // ① 报工撤回审批合并两类申请：批次整单撤回（/reversals）与小结撤回（interim-settle-reversal-requests）
  if(page.value==='reversal'){const [a,b]=await Promise.all([yget<Row[]>('reversals').catch(()=>[] as Row[]),yget<Row[]>('production-plans/interim-settle-reversal-requests').catch(()=>[] as Row[])]);revs.value=[...a.map(r=>({...r,_kind:'批次撤回'})),...b.map(r=>({...r,_kind:'小结撤回'}))]}
}catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
// ---- 生产计划 ----
const reqOf=(planId:unknown)=>reqs.value.filter(r=>String(r.productionPlanId)===String(planId))
// 与味美原生计划列表「下一步」同一规则（移植原生 list 页面的判定，不另造口径）；领料进度只放在详情里
function nextStep(p:Row){const e=String(p.status||'').toUpperCase(),ss=p.sourceType==='SAFETY_STOCK',skip=p.skipProcessReporting;if(e==='COMPLETED')return '已完成 · 查看入库状态';if(p.nextAction)return String(p.nextAction);if(e==='IN_PROGRESS')return ss?'继续逐道录入或生产小结':'继续逐道录入并核对结单';if(e==='PENDING')return ss?(skip===false?'逐道录入后生产小结':'按实际产出执行生产小结'):(skip===false?'逐道录入后核对销售订单数量':'核对销售订单数量后结单');if(e==='PLANNED'||e==='PREPARED')return '确认后进入未完成';if(e==='CANCELLED')return '已取消';return '查看详情'}
function reqProgress(p:Row){const rq=reqOf(p.id).filter(r=>String(r.status).toUpperCase()!=='CANCELLED');if(!rq.length)return '尚无领料单';return rq.some(r=>!['RECEIVED','CLOSED'].includes(String(r.status).toUpperCase()))?'领料中':'领料已签收'}
const planRows=computed(()=>plans.value.map(p=>{const g=progress({planned:p.plannedQuantity,done:p.actualQuantity,start:p.plannedDate||p.startDate,end:p.expectedCompletionDate||p.plannedDate,finished:String(p.status).toUpperCase()==='COMPLETED',cancelled:String(p.status).toUpperCase()==='CANCELLED'});return {p,g,next:nextStep(p),statusText:PLAN_STATUS[String(p.status||'').toUpperCase()]||('未识别状态 '+String(p.status||'—'))}}))
const planFiltered=computed(()=>planRows.value.filter(x=>(!q.value||[x.p.planNumber,x.p.productTypeName,x.p.productName].some(v=>String(v??'').includes(q.value)))&&(status.value===''||(status.value==='open'?!['COMPLETED','CANCELLED'].includes(String(x.p.status).toUpperCase()):x.statusText===status.value))))
// ---- 领料 ----
const planById=(id:unknown)=>plans.value.find(p=>String(p.id)===String(id))
const deepNo=computed(()=>S.query.get('no')||'')
const reqFiltered=computed(()=>reqs.value.filter(r=>(!deepNo.value||showAll.value||r.requisitionNo===deepNo.value)&&(!q.value||[r.requisitionNo,r.productionPlanNumber,r.productName].some(v=>String(v??'').includes(q.value)))&&(!status.value||(REQ_STATUS[String(r.status).toUpperCase()]||String(r.status))===status.value)))
const versionCheck=(r:Row)=>{const p=planById(r.productionPlanId);const a=num(r.sourceRecipeVersion),b=num(p?.selectedBomVersion);if(a===null)return {text:'生成版本未记录',warn:true};if(b===null)return {text:'用料 v'+a+' · 计划版本未取到',warn:true};return {text:'用料 v'+a+(a===b?' · 与计划一致':' · 计划已是 v'+b),warn:a!==b}}
function consumed(it:Row,r:Row){const v=num(it.consumedQty);if(v===null)return '未报工';const p=planById(r.productionPlanId);if(v===0&&num(p?.actualQuantity)===null)return '0.00 · 尚无报工';return fmtQty(v,String(it.unit||''))}
// ---- 报工撤回审批 ----
const userName=(id:unknown)=>{if(id===null||id===undefined||id==='')return '—';const u=users.value.find(x=>String(x.id)===String(id));return u?String(u.fullName||u.username):'用户 '+String(id)+'（名单未收录）'}
const revRows=computed(()=>revs.value.map(r=>({r,kind:String(r._kind),object:r._kind==='小结撤回'?String(r.planNumber||r.productionPlanNumber||'计划未返回')+(r.sessionSeq?' · 第 '+r.sessionSeq+' 次小结':''):String(r.batchId??'批次未返回'),scope:r._kind==='小结撤回'?'最近一次小结':(REV_SCOPE[String(r.reversalScope||'').toUpperCase()]||String(r.reversalScope||'—')),statusText:REV_STATUS[String(r.status||'').toUpperCase()]||('未识别状态 '+String(r.status||'—')),count:Array.isArray(r.revertedTxnIds)?r.revertedTxnIds.length+' 条':'—',applicant:userName(r.submittedBy??r.requestedBy),approver:userName(r.approvedBy??r.decidedBy??r.reviewedBy),applyAt:day(r.createdAt??r.requestedAt),decideAt:day(r.approvedAt??r.decidedAt??r.executedAt)})))
const revFiltered=computed(()=>revRows.value.filter(x=>!status.value||x.statusText===status.value))
// ③ BL 9 闭环状态（领 / 产 / 入）：代码判定，悬停看数量；发货属于 C，不放在计划上
const POSTING:Record<string,string>={POSTED:'已入库',POSTED_WITH_TOLERANCE:'已入库（容差）',PENDING_WAREHOUSE_RECEIPT:'待仓库确认',PENDING_CLEARING:'中转挂账'}
function loop(p:Row){const rq=reqOf(p.id).filter(r=>String(r.status).toUpperCase()!=='CANCELLED');const issued=rq.flatMap(r=>(r.items as Row[]||[])).length
  const ling=!rq.length?{s:'none',t:'未领料'}:rq.some(r=>!['RECEIVED','CLOSED'].includes(String(r.status).toUpperCase()))?{s:'run',t:'领料中（'+rq.length+' 张）'}:{s:'done',t:'已签收（'+rq.length+' 张，'+issued+' 行）'}
  const act=num(p.actualQuantity),chan=act===null?{s:'none',t:'未报工'}:{s:String(p.status).toUpperCase()==='COMPLETED'?'done':'run',t:'已报 '+fmtQty(act,String(p.plannedUnit||p.unit||''))}
  const st=(p.prototypeSettlement as Row|undefined)?.postingStatus as string|undefined
  const ru=st?{s:st.startsWith('POSTED')?'done':'run',t:POSTING[st]||('入库状态 '+st)}:p.settlementId?{s:'run',t:'已小结 · 入库状态未返回'}:{s:'none',t:'未小结'}
  return [{k:'领',...ling},{k:'产',...chan},{k:'入',...ru}]}
// ④ BL 11 来源需求：备货计划写明「无关联订单」；有来源订单但字段没返回时写「未返回」，不留空
function sourceDemand(p:Row){const t=String(p.sourceType||'');if(t==='SAFETY_STOCK'||t==='STOCK')return '备货计划，无关联订单';const no=p.salesOrderNumber??p.sourceOrderNumber??p.orderNumber,cust=p.customerName,due=p.deliveryDate??p.plannedDeliveryDate;if(!t)return '来源类型未填写';return '来源：'+(t==='SALES_ORDER'?'销售订单':t)+' · 订单 '+(no?String(no):'未返回')+' · 客户 '+(cust?String(cust):'未返回')+' · 交期 '+(due?day(due):'未返回')}
// ② 精简视图打开详情时把对象交给右下角悬浮窗（同一个 AiFloat；只给系统计算的依据，不调模型）
registerAiObject('ydwm-plan',{noun:'这条计划',describe:id=>{const p=planById(id);return p?'计划 '+p.planNumber+' · '+String(p.productTypeName||p.productName||''):undefined},facts:id=>{const p=planById(id);if(!p)return [];const x=planRows.value.find(y=>String(y.p.id)===id);return [{label:'状态',value:x?.statusText??null},{label:'下一步',value:x?.next??null},{label:'计划量',value:num(p.plannedQuantity),unit:String(p.plannedUnit||'')},{label:'实际数量',value:num(p.actualQuantity),unit:String(p.plannedUnit||'')},{label:'工期',value:x?.g.label??null},{label:'锁定生产版本',value:p.selectedWorkflowVersion?'v'+p.selectedWorkflowVersion:null},...loop(p).map(l=>({label:l.k+'（闭环）',value:l.t})),{label:'来源需求',value:sourceDemand(p)}]}})
registerAiObject('ydwm-requisition',{noun:'这张领料单',describe:id=>{const r=reqs.value.find(x=>x.requisitionNo===id);return r?'领料单 '+id+' · '+String(r.productionPlanNumber||''):undefined},facts:id=>{const r=reqs.value.find(x=>x.requisitionNo===id);if(!r)return [];return [{label:'状态',value:REQ_STATUS[String(r.status).toUpperCase()]||String(r.status)},{label:'生成版本',value:versionCheck(r).text},...(r.items as Row[]||[]).flatMap(it=>[{label:String(it.materialName)+' 已发',value:num(it.issuedQty),unit:String(it.unit||'')},{label:String(it.materialName)+' 实耗',value:num(it.consumedQty),unit:String(it.unit||'')},{label:String(it.materialName)+' 退回',value:num(it.returnedQty),unit:String(it.unit||'')}])]}})
registerAiObject('ydwm-reversal',{noun:'这条撤回申请',describe:id=>{const x=revRows.value.find(y=>String(y.r.id)===id);return x?x.kind+' · '+x.object:undefined},facts:id=>{const x=revRows.value.find(y=>String(y.r.id)===id);if(!x)return [];return [{label:'类型',value:x.kind},{label:'对象',value:x.object},{label:'状态',value:x.statusText},{label:'原因',value:String(x.r.reason||'')||null},{label:'申请人',value:x.applicant},{label:'审批人',value:x.approver},{label:'申请时间',value:x.applyAt||null}]}})
function toggle(kind:'ydwm-plan'|'ydwm-requisition'|'ydwm-reversal',id:string){open.value=open.value===id?'':id;if(open.value)setAiObject({kind,id},'味美 · '+(kind==='ydwm-plan'?'生产计划':kind==='ydwm-requisition'?'领料':'报工撤回审批')+'精简视图');else setAiObject(undefined)}
// R6：按账号记住视图选择（与 purpose-flow.js 同一个键）；进入精简视图即记为「精简」。
// R21 / BL22：「打开原页面办理」是一次性动作（链接带 view=native），不改按账号记住的选择。
// 要改回长期用原页面，点页头「默认用原页面」（明确的选择，才写偏好）；原页面页头的「精简视图」再切回来。
function preferNative(){try{localStorage.setItem(viewKey(),'native')}catch{/* 记不住时每次手动切换 */}try{window.top!.location.href=nativeHref(S.path)}catch{location.href=nativeHref(S.path)}}
const viewKey=()=>{let acc='ydwm_admin';try{acc=(window.top as unknown as {CretasWorkspace?:{account?:string}}).CretasWorkspace?.account||acc}catch{/* 跨域时用默认 */}return 'cretas-a-view-pref:ydwm:'+acc}
onMounted(()=>{try{localStorage.setItem(viewKey(),'list')}catch{/* 同上 */}})
watch(page,()=>{restore();open.value='';showAll.value=false;read()})
watch(()=>S.query.get('no'),()=>{showAll.value=false;const n=S.query.get('no');if(n)open.value=n})
onMounted(()=>{restore();const n=S.query.get('no');if(n)open.value=n;read()})
</script>
<template><section class="card table-panel ydwm-a">
<div class="ya-head"><div><h2>{{page==='plans'?'生产计划':page==='flow'?'领料':'报工撤回审批'}} <small>{{page==='plans'?planFiltered.length:page==='flow'?reqFiltered.length:revFiltered.length}} 条</small></h2><p>味美 · 统一框架只读视图（隔离会话 {{YD_SESSION}}）。{{page==='plans'?'新建、录入、小结、档案':page==='flow'?'生成领料、拣货、调拨、签收、关单':'批准、驳回'}}在原页面办理。</p></div>
<div class="actions"><UButton size="sm" color="neutral" variant="outline" :loading="busy" @click="read">刷新</UButton><a class="ya-link" :href="nativeHref(S.path)" target="_top" title="到原页面办理写操作（一次性，不改按账号记住的视图选择）">打开原页面办理</a><button type="button" class="ya-link ya-pref" title="以后打开这里默认进原页面（按账号记住）；想再用精简视图，在原页面页头点「精简视图」" @click="preferNative">默认用原页面</button></div></div>
<form class="filters" @submit.prevent="persist()"><input v-if="page!=='reversal'" v-model="q" :aria-label="page==='plans'?'搜索计划号或成品':'搜索单号、计划号或产品'" :placeholder="page==='plans'?'计划号、成品':'领料单号、计划号、产品'" @change="persist()">
<select v-model="status" aria-label="状态筛选" @change="persist()"><template v-if="page==='plans'"><option value="open">未完成</option><option value="">全部状态</option><option v-for="s in [...new Set(Object.values(PLAN_STATUS))]" :key="s">{{s}}</option></template><template v-else-if="page==='flow'"><option value="">全部状态</option><option v-for="s in [...new Set(Object.values(REQ_STATUS))]" :key="s">{{s}}</option></template><template v-else><option value="">全部状态</option><option v-for="s in Object.values(REV_STATUS)" :key="s">{{s}}</option></template></select>
<UButton type="submit" size="sm">搜索</UButton><UButton size="sm" color="neutral" variant="ghost" @click="q='';status=page==='plans'?'open':'';persist()">重置</UButton></form>
<p v-if="err" role="alert" class="ya-err">{{err}}</p>
<div v-if="page==='flow'&&deepNo" class="ya-deep" role="status">{{reqs.some(r=>r.requisitionNo===deepNo)?'只看领料单 '+deepNo:'当前列表没有领料单 '+deepNo+'，没有按相近单号猜配'}} <UButton size="xs" color="neutral" variant="ghost" @click="showAll=!showAll">{{showAll?'只看这张':'显示全部'}}</UButton></div>
<div class="table-scroll">
<table v-if="page==='plans'"><thead><tr><th>计划号</th><th>成品</th><th>计划量</th><th>计划日期</th><th>状态</th><th>下一步</th><th>工期</th><th>完成 / 时间</th><th>锁定生产版本</th><th>闭环</th><th class="op">操作</th></tr></thead><tbody>
<template v-for="x in planFiltered" :key="String(x.p.id)"><tr :class="{picked:open===String(x.p.id)}"><td>{{x.p.planNumber}}</td><td>{{x.p.productTypeName||x.p.productName||'未填写'}}</td><td>{{fmtQty(x.p.plannedQuantity,String(x.p.plannedUnit||x.p.unit||''))}}</td><td>{{day(x.p.plannedDate)||'未填写'}}</td><td>{{x.statusText}}</td><td><b>{{x.next}}</b></td><td><span class="state" :data-s="x.g.label">{{x.g.label}}</span></td><td :title="x.g.timeReason||x.g.workReason||''">{{pct(x.g.work,x.g.workReason?'未录入':undefined)}} / {{pct(x.g.time===null?null:Math.min(x.g.time,1),'无计划时间')}}</td><td>{{x.p.selectedWorkflowVersion?'v'+x.p.selectedWorkflowVersion:'未绑定'}}</td><td class="loop"><span v-for="l in loop(x.p)" :key="l.k" :class="'l-'+l.s" :title="l.k+'：'+l.t">{{l.k}}</span></td><td class="op"><UButton size="xs" color="neutral" variant="ghost" @click="toggle('ydwm-plan',String(x.p.id))">详情</UButton></td></tr>
<tr v-if="open===String(x.p.id)" class="ya-detail"><td colspan="11"><dl><dt>来源需求</dt><dd>{{sourceDemand(x.p)}}</dd><dt>闭环</dt><dd>{{loop(x.p).map(l=>l.k+'：'+l.t).join('；')}}</dd><dt>报工模式</dt><dd>{{x.p.skipProcessReporting===true?'免工序':x.p.skipProcessReporting===false?'逐道':'未填写'}}</dd><dt>用料投影</dt><dd>{{x.p.selectedBomVersion?'v'+x.p.selectedBomVersion:'未绑定'}}</dd><dt>领料进度</dt><dd>{{reqProgress(x.p)}}</dd><dt>领料单</dt><dd>{{reqOf(x.p.id).map(r=>r.requisitionNo+'（'+(REQ_STATUS[String(r.status).toUpperCase()]||r.status)+'）').join('、')||'无'}}</dd><dt>实际数量</dt><dd>{{num(x.p.actualQuantity)===null?'未录入':fmtQty(x.p.actualQuantity,String(x.p.plannedUnit||''))}}</dd><dt>备注</dt><dd>{{x.p.notes||'—'}}</dd></dl></td></tr></template>
<tr v-if="!planFiltered.length"><td colspan="11" class="empty">{{busy?'正在读取…':'当前筛选没有计划。'}}</td></tr></tbody></table>
<table v-else-if="page==='flow'"><thead><tr><th>领料单号</th><th>状态</th><th>生产计划</th><th>生成版本</th><th>物料行</th><th>需料日期</th><th>申请人</th><th class="op">操作</th></tr></thead><tbody>
<template v-for="r in reqFiltered" :key="String(r.id)"><tr :class="{picked:open===r.requisitionNo}"><td>{{r.requisitionNo}}</td><td>{{REQ_STATUS[String(r.status).toUpperCase()]||('未识别状态 '+r.status)}}</td><td>{{r.productionPlanNumber||'未关联'}}</td><td :class="{warn:versionCheck(r).warn}">{{versionCheck(r).text}}</td><td>{{(r.items as Row[]||[]).length}}</td><td>{{day(r.requiredDate)||'未填写'}}</td><td>{{r.requestedByName||'—'}}</td><td class="op"><UButton size="xs" color="neutral" variant="ghost" @click="toggle('ydwm-requisition',String(r.requisitionNo))">详情</UButton></td></tr>
<tr v-if="open===r.requisitionNo" class="ya-detail"><td colspan="8"><table><thead><tr><th>物料</th><th>类别</th><th>需求</th><th>已发</th><th>实耗</th><th>退回</th><th>单位</th></tr></thead><tbody><tr v-for="it in (r.items as Row[]||[])" :key="String(it.id)"><td>{{it.materialName}}</td><td>{{({RAW:'原料',SEASONING:'辅料',AUXILIARY:'辅料',PACKAGING:'包材'} as Record<string,string>)[String(it.materialCategory)]||'未分类'}}</td><td>{{num(it.requiredQty)===null?'按实际领用':fmtQty(it.requiredQty,String(it.unit||''))}}</td><td>{{fmtQty(it.issuedQty,String(it.unit||''))}}</td><td>{{consumed(it,r)}}</td><td>{{fmtQty(it.returnedQty,String(it.unit||''))}}</td><td>{{it.unit||'（单位未填）'}}</td></tr></tbody></table><p class="ya-note">需求、已发、实耗、退回各取各的字段，不互相代替；不同单位不合计。</p></td></tr></template>
<tr v-if="!reqFiltered.length"><td colspan="8" class="empty">{{busy?'正在读取…':'当前筛选没有领料单。'}}</td></tr></tbody></table>
<table v-else class="ya-rev"><thead><tr><th>类型</th><th>对象</th><th>撤回范围</th><th>状态</th><th>申请原因</th><th>回退流水</th><th>申请人</th><th>审批人</th><th>申请时间</th><th>审批时间</th><th class="op">操作</th></tr></thead><tbody>
<tr v-for="x in revFiltered" :key="x.kind+String(x.r.id)" :class="{picked:open===String(x.r.id)}"><td>{{x.kind}}</td><td class="ya-clip" :title="x.object">{{x.object}}</td><td>{{x.scope}}</td><td>{{x.statusText}}</td><td class="ya-clip" :title="String(x.r.reason||'')">{{x.r.reason||'—'}}</td><td>{{x.count}}</td><td>{{x.applicant}}</td><td>{{x.approver}}</td><td>{{x.applyAt||'—'}}</td><td>{{x.decideAt||'—'}}</td><td class="op"><UButton size="xs" color="neutral" variant="ghost" @click="toggle('ydwm-reversal',String(x.r.id))">{{open===String(x.r.id)?'收起':'选中'}}</UButton></td></tr>
<tr v-if="!revFiltered.length"><td colspan="11" class="empty">{{busy?'正在读取…':'暂无撤回申请。小结撤回从生产计划「更多 → 撤回小结」发起，批次整单撤回从批次详情发起；批准、驳回在原页面办理。'}}</td></tr></tbody></table>
</div></section></template>
<style scoped>
.ya-head{display:flex;justify-content:space-between;gap:12px;align-items:flex-start;margin-bottom:8px}.ya-head h2{margin:0;font-size:18px}.ya-head p{margin:4px 0 0;font-size:12px;color:#748297}.ya-head .actions{display:flex;gap:8px;align-items:center}
.ya-link{font-size:13px;color:var(--cs-primary,#1b65a8)}.ya-pref{border:0;background:none;padding:0;cursor:pointer;color:#748297}.ya-err{color:var(--cs-danger,#c53b3b);font-size:13px}.ya-deep{display:flex;gap:8px;align-items:center;padding:6px 10px;margin:0 0 8px;border-radius:6px;background:var(--cs-primary-soft,#eef5fc);font-size:13px}
.table-scroll td,.table-scroll th{white-space:nowrap;height:32px;font-variant-numeric:tabular-nums}.table-scroll td{max-width:260px;overflow:hidden;text-overflow:ellipsis}
/* 第四批审计退回 2：撤回审批精简表在 1440 宽下不再横向滚动看不到操作——长文本列收窄带悬停全文，操作列固定在右侧 */
.ya-rev .ya-clip{max-width:180px}.ya-rev th.op,.ya-rev td.op{position:sticky;right:0;z-index:1;background:var(--cs-surface,#fff);box-shadow:-1px 0 0 var(--cs-border,#e3e8ef)}
.ya-detail td{white-space:normal;background:#f8fafc}.ya-detail dl{display:grid;grid-template-columns:auto 1fr auto 1fr;gap:4px 16px;font-size:13px;margin:4px 0}.ya-detail dd{margin:0}.ya-note{font-size:12px;color:#748297;margin:4px 0}
.warn{color:var(--cs-warning,#b26a00)}.loop span{display:inline-grid;place-items:center;width:20px;height:20px;margin-right:3px;border-radius:4px;font-size:12px;background:#f0f2f5;color:#8a96a6}.loop .l-run{background:var(--cs-warning-soft,#fff5e6);color:var(--cs-warning,#b26a00)}.loop .l-done{background:var(--cs-success-soft,#eaf6ee);color:var(--cs-success,#2f7d4a)}.state[data-s=已超期]{background:var(--cs-danger-soft,#fdecec);color:var(--cs-danger,#c53b3b)}.state[data-s=正常],.state[data-s=已完成]{background:var(--cs-success-soft);color:var(--cs-success)}.state[data-s=不能判断],.state[data-s=未开始],.state[data-s=已取消]{background:#f0f2f5;color:#6b7889}
</style>
