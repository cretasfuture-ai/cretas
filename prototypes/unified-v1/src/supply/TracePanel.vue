<script setup lang="ts">
// 第四批② BL20：C28 追溯视图的反向入口（不做独立自定义页面）。从库存查询的产品行打开：产品＋时间范围 → 追溯链，导出 CSV。
// 只读；接不上的环节逐行写「缺：原因」（R17），不补链。
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import type {Row} from '../frame/types'
import {S,rs,stateLabel} from '../frame/store'
import {T,readInventory} from './supply-store'
import {downloadCsv,fmtQty} from './supply-facts'
import {traceProduct,traceCsv,GAP,bjTime} from './trace'
import type {TraceResult} from './trace'
import {relativeDate,relativeRange} from '../frame/ai-protocol'
import {readBatches,batchTraceIndex} from './batch-store'
import type {CBatch} from './batch-store'
const props=defineProps<{productCode:string}>()
const emit=defineEmits<{close:[]}>()
const code=ref(props.productCode),from=ref(''),to=ref(''),dk=ref('last30')
const cb=ref<CBatch[]>([])
const X=ref<{checkedAt?:string;resources:Record<string,Row[]|null>}>(),err=ref(''),busy=ref(false),result=ref<TraceResult>()
const products=computed(()=>rs('product'))
const dateHost=ref<HTMLElement>();let rel:{element:HTMLElement}|undefined
function applyKey(k:string){const r=k?relativeRange(k):null;if(r){from.value=r.from;to.value=r.to}}
onMounted(async()=>{applyKey(dk.value);rel=relativeDate(dateHost.value||null,{value:dk.value,onChange:r=>{dk.value=r?.key||'';if(r){from.value=r.from;to.value=r.to}}})
  busy.value=true
  try{if(!T.inv)await readInventory();const r=await fetch('/__lsm/supply/extras'),b=await r.json();if(!b.success)throw Error(b.message||'黑湖新接口读取失败');X.value=b.data;try{cb.value=await readBatches()}catch{/* 批次读不到时按无批次追溯 */}run()}
  catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}})
onBeforeUnmount(()=>{rel?.element.remove()})
watch(()=>props.productCode,c=>{code.value=c;run()})
function run(){if(!T.inv||!X.value||!S.source)return;err.value=''
  result.value=traceProduct({productCode:code.value,from:from.value,to:to.value,projects:rs('project'),outputs:rs('output'),products:products.value,orders:(T.inv.orders||[]) as unknown as Row[],warehouses:((T.inv as unknown as {warehouses?:Row[]}).warehouses)||[],
    saleOrders:X.value.resources.sale_order||[],customers:X.value.resources.customer||[],// 出入库时点取单据快照时间（materialAt，与页面顶部「库存／单据快照」一致），不是采集核验时间
    at:{source:S.source.checkedAt as string|undefined,inventory:(T.inv as unknown as {materialAt?:string;checkedAt?:string}).materialAt??(T.inv as unknown as {checkedAt?:string}).checkedAt,extras:X.value.checkedAt},statusLabel:v=>stateLabel(v),batches:batchTraceIndex(cb.value)})}
function exportCsv(){if(!result.value)return;const rows=traceCsv(result.value);downloadCsv('产品追溯-'+code.value.replace(/\s+/g,'')+'-'+(from.value||'不限')+'至'+(to.value||'不限')+'.csv',rows[0],rows.slice(1))}
const q=(x:number|null)=>x==null?'—':fmtQty(x)
</script>
<template><section class="card detail-card trace-panel" aria-label="产品追溯">
<div class="section-head"><h2>产品追溯 · 一键倒查</h2><button class="ui-button" @click="emit('close')">关闭</button></div>
<p class="supply-note">反向入口：从产品和时间范围倒查工单、报工、成品入库、领料物料和同期销售出库。只用黑湖单据上的直接关联字段；接不上的环节写「缺：原因」，不按产品名、日期或单号前缀补链。只读。</p>
<form class="filters" @submit.prevent="run">
<input v-model="code" list="trace-products" aria-label="产品编码" placeholder="产品编码"><datalist id="trace-products"><option v-for="p in products" :key="String(p.productCode)" :value="String(p.productCode)">{{p.productName}}</option></datalist>
<span ref="dateHost" class="ui-date-host" title="按工单开工日期与出库日期筛选"></span>
<input v-model="from" type="date" aria-label="开始日期" @change="dk=''"><span class="muted">至</span><input v-model="to" type="date" aria-label="结束日期" @change="dk=''">
<button type="submit" class="ui-button primary" :disabled="busy">开始一键倒查</button><button type="button" class="ui-button" :disabled="!result" @click="exportCsv">导出追溯报告（CSV）</button>
</form>
<p v-if="err" role="alert" class="error">{{err}}</p>
<p v-if="busy" class="muted">正在读取…</p>
<template v-if="result">
<p class="muted">{{result.product?result.product.code+' '+result.product.name:'未找到该产品编码'}} · {{result.from||'不限'}} 至 {{result.to||'不限'}} · 数据时点：黑湖主快照 {{bjTime(result.at.source)}}，出入库单据快照 {{bjTime(result.at.inventory)}}，黑湖新接口 {{bjTime(result.at.extras)}}</p>
<div v-if="result.gaps.length" class="trace-gaps"><h3>缺失环节</h3><ul><li v-for="g in result.gaps" :key="g.step"><b>{{g.step}}</b>（{{g.count}} 处）：{{g.reason}}</li></ul></div>
<h3>工单 {{result.works.length}} 张</h3>
<p v-if="!result.works.length" class="muted">{{GAP.noWork}}</p>
<details v-for="w in result.works" :key="String(w.project.projectCode)" class="trace-work" open><summary><b>{{w.project.projectCode}}</b> · 开工 {{w.start||'未返回'}} · 实际 {{q(Number(w.project.actualAmount))}} {{w.project.projectUnit}} · 报工 {{w.reports.count}} 条（{{w.reports.processes}} 道工序）</summary>
<p class="trace-gap">{{w.saleGap}}</p>
<table class="supply-table"><thead><tr><th>环节</th><th>单号</th><th>日期</th><th>物料 / 产品</th><th class="num">数量</th><th>单位</th><th>关联</th><th>缺失环节</th></tr></thead><tbody>
<tr v-for="(x,i) in [...w.inbound,...w.picks,...w.returns]" :key="i"><td>{{x.step}}</td><td>{{x.no}}</td><td>{{x.date}}</td><td>{{x.item}}</td><td class="num">{{q(x.qty)}}</td><td>{{x.unit}}</td><td>{{x.link}}</td><td class="trace-gap">{{x.gap}}</td></tr>
<tr v-if="!w.inbound.length&&!w.picks.length"><td colspan="8" class="empty">没有关联到该工单的成品入库或领料单。</td></tr></tbody></table></details>
<h3>同期销售出库 {{result.shipments.length}} 行</h3>
<p v-if="!result.shipments.length" class="muted">{{GAP.noShip}}</p>
<table v-else class="supply-table"><thead><tr><th>出库单</th><th>日期</th><th class="num">数量</th><th>单位</th><th>销售订单 → 客户</th><th>缺失环节</th></tr></thead><tbody>
<tr v-for="(x,i) in result.shipments" :key="i"><td>{{x.no}}</td><td>{{x.date}}</td><td class="num">{{q(x.qty)}}</td><td>{{x.unit}}</td><td>{{x.link}}</td><td class="trace-gap">{{x.gap}}</td></tr></tbody></table>
</template>
</section></template>
<style scoped>
.trace-gaps{border:1px solid var(--cs-border);border-radius:6px;padding:8px 12px;margin:8px 0}
.trace-gaps ul{margin:4px 0 0 18px}
.trace-gap{color:var(--cs-warning-text)}
.trace-work{margin:8px 0}
.trace-panel :deep(.supply-table td){white-space:normal;overflow:visible;text-overflow:clip}
.trace-work summary{cursor:pointer;padding:4px 0}
h3{font-size:14px;margin:12px 0 6px}
</style>
