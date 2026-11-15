<script setup lang="ts">
// 味美 · 采购订单精简视图（规格第 3 节；N3 采购交货日期作为正式列；R6：只读，写操作留在原页面）。
// 订购·已收·待收：执行中的单取收货任务的 orderedQuantity / receivedQuantity / remainingReceivableQuantity；
// 已收货/已完成的单收货任务已关闭，明细未返回时如实写“已收齐（明细未返回）”；取消单不计待收。
import {computed,onMounted,ref,watch} from 'vue'
import {viewTabs} from '../frame/ai-protocol'
import type {Row} from '../frame/types'
import {yget,num,dateOnly,days,today,YD_SESSION_LABEL,withSession,DELIVERY_KEYS} from './ydwm'
import {fmtQty,add,downloadCsv} from './supply-facts'
import {usePager,usePrefs} from './pager'
import YdwmDraft from './YdwmDraft.vue'
// 第四批① BL13：新建草稿（带价目表参考价）
const draftOpen=ref(false)
function onDraftSaved(id:string){void read();prefs.value.q='';setTimeout(()=>{const x=orders.value.find(o=>String(o.id)===id);if(x)open.value=x},600)}
const orders=ref<Row[]>([]),tasks=ref<Row[]>([]),busy=ref(false),err=ref(''),open=ref<Row>()
const prefs=usePrefs('unified-ydwm-purchase-v1',{q:'',st:''})
const STATUS:Record<string,{t:string;g:'未完成'|'执行中'|'已完成'|'已取消'}>={DRAFT:{t:'草稿',g:'未完成'},PENDING_APPROVAL:{t:'待审批',g:'未完成'},SUBMITTED:{t:'已提交',g:'未完成'},REJECTED:{t:'已驳回',g:'未完成'},APPROVED:{t:'已审核',g:'执行中'},FINANCE_APPROVED:{t:'财务已审',g:'执行中'},PARTIALLY_RECEIVED:{t:'部分收货',g:'执行中'},RECEIVED:{t:'已收货',g:'已完成'},COMPLETED:{t:'已完成',g:'已完成'},CLOSED:{t:'已关闭',g:'已完成'},CANCELLED:{t:'已取消',g:'已取消'}}
const st=(o:Row)=>STATUS[String(o.status)]||{t:String(o.status),g:'未完成' as const}
async function read(){busy.value=true;err.value='';try{const [o,t]=await Promise.all([yget<Row[]>('purchase/orders?page=1&size=500'),yget<Row[]>('warehouse/receiving/tasks').catch(()=>[] as Row[])]);orders.value=o;tasks.value=Array.isArray(t)?t:[]}catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
onMounted(read)
// R11③：D 的已存视图与状态计数（CretasUI.viewTabs）。内置视图 + 用户另存（按账号，最多 5 个）；计数按四个状态分组
const tabsHost=ref<HTMLElement>();let tabs:ReturnType<typeof viewTabs>|undefined
const countList=()=>['未完成','执行中','已完成','已取消'].map(k=>({key:k,label:k,count:orders.value.length?orders.value.filter(o=>st(o).g===k).length:null}))
onMounted(()=>{tabs=viewTabs(tabsHost.value||null,{page:'ydwm-purchase-list',builtins:[{id:'all',name:'全部',state:{q:'',st:''}},{id:'open',name:'执行中待收',state:{q:'',st:'执行中'}},{id:'draft',name:'未完成',state:{q:'',st:'未完成'}}],getState:()=>({q:prefs.value.q,st:prefs.value.st}),apply:s=>{prefs.value.q=String(s.q??'');prefs.value.st=String(s.st??'')},counts:countList(),countKey:prefs.value.st,onCount:k=>{prefs.value.st=prefs.value.st===k?'':k;tabs?.refresh(countList(),prefs.value.st)}})})
watch(()=>[orders.value.length,prefs.value.st],()=>tabs?.refresh(countList(),prefs.value.st))
const detailTab=ref<'info'|'log'|'lines'>('info')
watch(open,()=>{detailTab.value='info'})
const due=(o:Row)=>{for(const k of DELIVERY_KEYS)if(o[k])return dateOnly(o[k]);return ''}
const placed=(o:Row)=>dateOnly(o.orderDate??o.submittedAt)
interface Q{unit:string;ordered:number;received:number|undefined;pending:number|undefined}
function qty(o:Row):{rows:Q[];note:string}{
  const g=st(o).g,task=tasks.value.find(t=>String(t.purchaseOrderId??t.sourceId)===String(o.id))
  const m=new Map<string,Q>()
  for(const i of (o.items as Row[])||[]){const u=String(i.unit??'（无单位）'),q=num(i.quantity)??0,r=m.get(u)||{unit:u,ordered:0,received:0,pending:0};r.ordered=add(r.ordered,q);m.set(u,r)}
  if(task){for(const r of m.values()){r.received=0;r.pending=0}for(const i of (task.items as Row[])||[]){const r=m.get(String(i.unit??'（无单位）'));if(!r)continue;const rc=num(i.receivedQuantity),pd=num(i.remainingReceivableQuantity);r.received=rc===undefined||r.received===undefined?undefined:add(r.received,rc);r.pending=pd===undefined||r.pending===undefined?undefined:add(r.pending,pd)}return {rows:[...m.values()],note:'来自收货任务'}}
  if(g==='已取消')return {rows:[...m.values()].map(r=>({...r,received:undefined,pending:0})),note:'已取消，不计待收'}
  if(g==='已完成')return {rows:[...m.values()].map(r=>({...r,received:undefined,pending:0})),note:'已收齐或已关闭，收货明细未返回'}
  return {rows:[...m.values()].map(r=>({...r,received:undefined,pending:undefined})),note:g==='未完成'?'未下达，尚无收货任务':'收货任务未返回'}
}
const qtyText=(o:Row)=>qty(o).rows.map(r=>fmtQty(r.ordered)+'·'+(r.received===undefined?'—':fmtQty(r.received))+'·'+(r.pending===undefined?'—':fmtQty(r.pending))+' '+r.unit).join('；')||'—'
// BL 12：这张单引起的收货。味美接口只返回执行中单据的收货任务（订购、已收、待收），收货单和入库批次没有返回与采购单的关联，所以只列任务汇总并如实说明
function recvLog(o:Row){const t=tasks.value.find(x=>String(x.purchaseOrderId??x.sourceId)===String(o.id));if(!t)return st(o).g==='已完成'?'收货任务已结束，收货单与入库批次未返回与本单的关联，不能逐笔列出。':st(o).g==='已取消'?'已取消，没有收货。':'还没有收货任务（未下达或未审核）。';return '收货任务 '+String(t.statusLabel??t.status)+'：'+((t.items as Row[])||[]).map(i=>String(i.materialName)+' 订购 '+fmtQty(i.orderedQuantity)+' / 已收 '+fmtQty(i.receivedQuantity)+' / 待收 '+fmtQty(i.remainingReceivableQuantity)+' '+String(i.unit??'')).join('；')+'。逐笔收货单未返回与本单的关联。'}
const lateDays=(o:Row)=>{const d=due(o);if(!d||['已完成','已取消'].includes(st(o).g))return 0;return Math.max(0,days(d,today())||0)}
const filtered=computed(()=>orders.value.filter(o=>(!prefs.value.st||st(o).g===prefs.value.st)&&(!prefs.value.q||(String(o.orderNumber)+String(o.supplierName)).toLowerCase().includes(prefs.value.q.toLowerCase()))).sort((a,b)=>String(b.createdAt).localeCompare(String(a.createdAt))))
const pager=usePager(filtered)
// 第二批②：后端对无价格权限的角色把金额置空；整页都没有金额时隐藏金额列（不按角色名判断）
const moneyVisible=computed(()=>!orders.value.length||orders.value.some(o=>num(o.totalAmount)!==undefined))
const counts=computed(()=>{const c:Record<string,number>={};for(const o of orders.value)c[st(o).g]=(c[st(o).g]||0)+1;return c})
const pendingByUnit=computed(()=>{const m=new Map<string,number>();let unknown=0;for(const o of filtered.value){if(st(o).g!=='执行中')continue;for(const r of qty(o).rows){if(r.pending===undefined){unknown++;continue}m.set(r.unit,add(m.get(r.unit)||0,r.pending))}}return {text:[...m.entries()].map(([u,v])=>fmtQty(v)+' '+u).join('；')||'0',unknown}})
const materials=(o:Row)=>{const n=[...new Set(((o.items as Row[])||[]).map(i=>String(i.materialName??'未提供')))];return n.length>1?n[0]+' 等 '+n.length+' 种':n[0]||'—'}
const TONE:Record<string,string>={未完成:'zero',执行中:'info',已完成:'ok',已取消:'muted'}
function top(u:string){try{window.top!.location.href=u}catch{location.href=u}}
const qs=(q:URLSearchParams)=>{const s=withSession(q).toString();return s?'?'+s:''}
const openNative=(o:Row)=>top('/procurement/orders/'+encodeURIComponent(String(o.id))+qs(new URLSearchParams()))
const toReceive=(o:Row)=>top('/warehouse/materials'+qs(new URLSearchParams({no:String(o.orderNumber)})))
function exportCsv(){downloadCsv('味美采购订单.csv',['单号','供应商','物料','下单','采购交货','订购·已收·待收','金额','状态','分组'],filtered.value.map(o=>[o.orderNumber,o.supplierName,materials(o),placed(o)||'未记录',due(o)||'未填写',qtyText(o),num(o.totalAmount)??'',st(o).t,st(o).g]))}
defineExpose({exportCurrent:exportCsv})
</script>
<template><div>
<p class="supply-note">精简视图（只读）· 味美工作区 · {{YD_SESSION_LABEL()}}。新建、提交、编辑、关闭在原采购订单页面完成（点“处理”）；收货在入库任务页完成（点“收货”）。采购交货日期味美接口暂无字段时显示“未填写”。<template v-if="!moneyVisible"> 金额未返回（可能是无价格权限或未录入），金额列已隐藏。</template></p>
<p v-if="err" role="alert" class="error">{{err}}</p>
<div class="draft-bar"><button type="button" class="ui-button primary" @click="draftOpen=!draftOpen">{{draftOpen?'收起新建草稿':'新建草稿（带参考价）'}}</button></div>
<YdwmDraft v-if="draftOpen" @close="draftOpen=false" @saved="onDraftSaved"/>
<section class="card">
<div ref="tabsHost" class="view-tabs-host"></div>
<form class="filters" @submit.prevent>
<input v-model="prefs.q" aria-label="搜索单号或供应商" placeholder="单号、供应商">
<select v-model="prefs.st" aria-label="按状态筛选"><option value="">全部状态（{{orders.length}}）</option><option v-for="s in ['未完成','执行中','已完成','已取消']" :key="s" :value="s">{{s}}（{{counts[s]||0}}）</option></select>
<button type="button" class="ui-button" @click="prefs.q='';prefs.st=''">重置</button><button type="button" class="ui-button" :disabled="busy" @click="read">{{busy?'正在读取…':'刷新'}}</button>
</form>
<div class="section-head"><h2>采购订单 · {{filtered.length}} 张</h2><small>执行中待收合计：{{pendingByUnit.text}}<template v-if="pendingByUnit.unknown">（另有 {{pendingByUnit.unknown}} 行收货明细未返回，未计入）</template>；取消单不计</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>单号</th><th>供应商</th><th>物料</th><th>采购交货</th><th class="num">订购·已收·待收</th><th v-if="moneyVisible" class="num">金额</th><th>状态</th><th>操作</th></tr></thead><tbody>
<tr v-for="o in pager.rows.value" :key="String(o.id)"><td>{{o.orderNumber}}</td><td class="clip" :title="String(o.supplierName||'')">{{o.supplierName||'未记录'}}</td><td class="clip" :title="materials(o)">{{materials(o)}}</td>
<td><span :class="due(o)?'':'muted'">{{due(o)||'未填写'}}</span><span v-if="lateDays(o)" class="supply-badge" data-tone="zero">已逾期 {{lateDays(o)}} 天</span></td>
<td class="num" :title="qty(o).note">{{qtyText(o)}}</td><td v-if="moneyVisible" class="num">{{num(o.totalAmount)===undefined?'—':fmtQty(o.totalAmount)+' 元'}}</td>
<td><span class="supply-badge" :data-tone="TONE[st(o).g]" :title="'分组：'+st(o).g">{{st(o).t}}</span></td>
<td class="ops"><button class="op" @click="open=o">详情</button><button v-if="st(o).g==='执行中'" class="op" @click="toReceive(o)">收货</button><button class="op" @click="openNative(o)">处理</button></td></tr>
<tr v-if="!pager.rows.value.length"><td colspan="8" class="empty">{{busy?'正在读取':'当前筛选没有采购订单。'}}</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{pager.page.value}} / {{pager.pages.value}} 页</span><button class="ui-button" :disabled="pager.page.value<=1" @click="pager.page.value--">上一页</button><button class="ui-button" :disabled="pager.page.value>=pager.pages.value" @click="pager.page.value++">下一页</button></div>
</section>
<section v-if="open" class="card detail-card" aria-label="采购订单详情"><div class="section-head"><h2>{{open.orderNumber}} · {{open.supplierName}}</h2><button class="ui-button" @click="open=undefined">关闭</button></div>
<div class="cs-tabs lsm-tabs supply-tabs" role="tablist" aria-label="采购订单详情"><button role="tab" :aria-selected="detailTab==='info'" @click="detailTab='info'">概况</button><button role="tab" :aria-selected="detailTab==='log'" @click="detailTab='log'">收发记录</button><button role="tab" :aria-selected="detailTab==='lines'" @click="detailTab='lines'">明细</button></div>
<template v-if="detailTab==='info'"><dl class="kv"><dt>状态</dt><dd>{{st(open).t}}（{{st(open).g}}）</dd><dt>下单</dt><dd>{{placed(open)||'未记录'}}</dd><dt>采购交货</dt><dd>{{due(open)||'未填写'}}</dd><dt>收货口径</dt><dd>{{qty(open).note}}</dd><dt>备注</dt><dd>{{open.notes||'—'}}</dd></dl></template>
<template v-else-if="detailTab==='lines'"><table class="supply-table"><thead><tr><th>物料</th><th class="num">订购</th><th>单位</th><th v-if="moneyVisible" class="num">未税单价</th><th class="num">税率</th></tr></thead><tbody><tr v-for="(i,k) in (open.items as Row[])" :key="k"><td>{{i.materialName||'未提供'}}</td><td class="num">{{fmtQty(i.quantity)}}</td><td>{{i.unit}}</td><td v-if="moneyVisible" class="num">{{num(i.unitPrice)===undefined?'未填写':fmtQty(i.unitPrice)}}</td><td class="num">{{num(i.taxRate)===undefined?'未填写':fmtQty(num(i.taxRate)!*100)+'%'}}</td></tr></tbody></table>
</template>
<template v-else-if="detailTab==='log'"><p class="muted detail-pad">{{recvLog(open)}}</p><h3>按单位</h3><table class="supply-table"><thead><tr><th>单位</th><th class="num">订购</th><th class="num">已收</th><th class="num">待收</th></tr></thead><tbody><tr v-for="r in qty(open).rows" :key="r.unit"><td>{{r.unit}}</td><td class="num">{{fmtQty(r.ordered)}}</td><td class="num">{{r.received===undefined?'未返回':fmtQty(r.received)}}</td><td class="num">{{r.pending===undefined?'未返回':fmtQty(r.pending)}}</td></tr></tbody></table></template>
</section>
</div></template>
