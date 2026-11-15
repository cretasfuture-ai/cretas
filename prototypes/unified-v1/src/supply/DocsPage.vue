<script setup lang="ts">
// 六膳门 · 出入库单据（规格 25）＋ 入库/出库场景分类与描述（31 N4）＋ 明细行补金额/补批次（32）。来源只读。
import {computed,ref,watch} from 'vue'
import {registerSupplyAi,selectDocument} from './ai-supply'
import UModal from '@nuxt/ui/components/Modal.vue'
import {T,activeFor,orderLink} from './supply-store'
import {flatten,CATEGORIES,categoryOf,directionOf,fmtQty,fmtTime,localDate,add,downloadCsv} from './supply-facts'
import type {Order} from './supply-facts'
import {usePager,usePrefs} from './pager'
import {openSupplement} from './supplement'
import {S,navigate} from '../frame/store'
const props=defineProps<{dir:'in'|'out';defaultCat?:string}>()
const prefs=usePrefs('unified-lsm-supply-docs-'+props.dir+'-v1',{cat:props.defaultCat||'',view:'orders',q:'',wh:'',from:'',to:''})
watch(()=>props.dir,()=>{/* 路由切换时组件重建，偏好按方向分开保存 */})
const inv=computed(()=>T.inv!)
const cats=computed(()=>CATEGORIES.filter(c=>c.dir===props.dir))
const orders=computed(()=>inv.value.orders.filter(o=>directionOf(o.bizTypeDisplay)===props.dir))
const unclassified=computed(()=>orders.value.filter(o=>!categoryOf(o.bizTypeDisplay)))
const catCount=(id:string)=>orders.value.filter(o=>categoryOf(o.bizTypeDisplay)?.id===id).length
const activeCat=computed(()=>cats.value.find(c=>c.id===prefs.value.cat))
const inRange=(v:unknown)=>{const d=localDate(v);return (!prefs.value.from||d>=prefs.value.from)&&(!prefs.value.to||d<=prefs.value.to)}
const catOk=(t:unknown)=>!prefs.value.cat?true:prefs.value.cat==='__none'?!categoryOf(t):categoryOf(t)?.id===prefs.value.cat
const q=(s:string)=>!prefs.value.q||s.toLowerCase().includes(prefs.value.q.toLowerCase())
const ordersFiltered=computed(()=>orders.value.filter(o=>catOk(o.bizTypeDisplay)&&inRange(o.bizAt)&&q(String(o.orderNo)+o.lines.map(l=>String(l.productName)+String(l.productCode)).join(' '))&&(!prefs.value.wh||o.lines.some(l=>String(l.detailWarehouseId)===prefs.value.wh))).sort((a,b)=>String(b.bizAt).localeCompare(String(a.bizAt))))
const allLines=computed(()=>flatten(inv.value).filter(l=>l.dir===props.dir))
const linesFiltered=computed(()=>allLines.value.filter(l=>catOk(l.bizType)&&inRange(l.bizAt)&&q(l.orderNo+l.productName+l.productCode)&&(!prefs.value.wh||l.warehouseId===prefs.value.wh)).sort((a,b)=>b.bizAt.localeCompare(a.bizAt)))
const orderPager=usePager(ordersFiltered),linePager=usePager(linesFiltered)
const totals=computed(()=>{const m=new Map<string,number>();for(const l of linesFiltered.value)if(l.qty!==undefined)m.set(l.unit,add(m.get(l.unit)||0,l.qty));return [...m.entries()].map(([u,v])=>fmtQty(v)+' '+u).join('；')||'—'})
const whName=(id:unknown)=>inv.value.warehouses.find(w=>String(w.id)===String(id))?.name as string|undefined
const productSummary=(o:Order)=>{const n=new Set(o.lines.map(l=>String(l.productName)));const first=[...n][0]||'—';return n.size>1?first+' 等 '+n.size+' 种':first}
const detail=ref<Order>()
registerSupplyAi()
watch(detail,o=>{if(o)selectDocument(String(o.orderNo))})
// 深链：#/warehouse/shipments?no=<单号>（入库同理）——填入搜索并切到全部分类
if(S.query.get('no')){prefs.value.q=S.query.get('no')||'';prefs.value.cat='';prefs.value.view='orders'}
// 审计退回第 9 条：深链单号精确命中一张单时，自动打开详情（详情打开即把单据交给悬浮窗）
const deepNo=S.query.get('no')||''
watch(()=>orders.value.length,()=>{if(!deepNo||detail.value)return;const hit=orders.value.find(o=>String(o.orderNo)===deepNo);if(hit)detail.value=hit},{immediate:true})
const batchOf=(lid:unknown)=>activeFor('lineBatch',String(lid)),costOf=(lid:unknown)=>activeFor('lineCost',String(lid))
function exportCurrent(){
  if(prefs.value.view==='lines')downloadCsv('六膳门'+(props.dir==='in'?'入库':'出库')+'明细.csv',['业务时间','单号','场景','原类型','产品编码','产品','数量','单位','仓库','关联单号','金额','金额来源','批次号','批次来源'],linesFiltered.value.map(l=>{const lid=l.lineId,bc=batchOf(lid),cc=costOf(lid);return [l.bizAt,l.orderNo,l.category?.label||'未分类',l.bizType,l.productCode,l.productName,l.qty??l.rawQty,l.unit,l.warehouseName,l.related,l.cost??cc?.value.amount??'',l.cost!=null&&l.cost!==''?'黑湖':cc?'Cretas 补充':'黑湖未返回',bc?.value.batchNo??'',bc?'Cretas 补充':'黑湖无批次字段']}))
  else downloadCsv('六膳门'+(props.dir==='in'?'入库':'出库')+'单据.csv',['单号','场景','原类型','业务时间','来源状态','产品','明细行数'],ordersFiltered.value.map(o=>[o.orderNo,categoryOf(o.bizTypeDisplay)?.label||'未分类',o.bizTypeDisplay,o.bizAt,o.orderStatusDisplay,productSummary(o),o.lines.length]))
}
defineExpose({exportCurrent})
</script>
<template><div>
<div class="cs-tabs lsm-tabs supply-tabs" role="tablist" :aria-label="(dir==='in'?'入库':'出库')+'场景'">
<button role="tab" :aria-selected="!prefs.cat" @click="prefs.cat=''">全部{{dir==='in'?'入库':'出库'}} <small>{{orders.length}}</small></button>
<button v-for="c in cats" :key="c.id" role="tab" :aria-selected="prefs.cat===c.id" @click="prefs.cat=c.id">{{c.label}} <small>{{catCount(c.id)}}</small></button>
<button v-if="unclassified.length" role="tab" :aria-selected="prefs.cat==='__none'" @click="prefs.cat='__none'">未分类 <small>{{unclassified.length}}</small></button>
</div>
<p class="supply-note">{{activeCat?activeCat.desc:prefs.cat==='__none'?'来源类型不在分类白名单内，按原类型显示，不猜归类。':(dir==='in'?'按场景分为采购、生产、退料、调拨和其他入库；每张都是黑湖已审批单据，Cretas 只读展示，开单与审批仍在黑湖。':'按场景分为销售、领料、调拨和其他出库；每张都是黑湖已审批单据，Cretas 只读展示，开单与审批仍在黑湖。')}}</p>
<section class="card">
<form class="filters" @submit.prevent>
<input v-model="prefs.q" aria-label="搜索单号或产品" placeholder="单号、产品">
<select v-model="prefs.wh" aria-label="筛选仓库"><option value="">全部仓库</option><option v-for="w in inv.warehouses" :key="String(w.id)" :value="String(w.id)">{{w.name}}</option></select>
<label>业务时间 <input v-model="prefs.from" type="date" aria-label="开始日期"></label><label>至 <input v-model="prefs.to" type="date" aria-label="结束日期"></label>
<button type="button" class="ui-button" @click="prefs.q='';prefs.wh='';prefs.from='';prefs.to=''">重置</button>
<span class="seg" role="group" aria-label="视图"><button type="button" :aria-pressed="prefs.view==='orders'" @click="prefs.view='orders'">按单据</button><button type="button" :aria-pressed="prefs.view==='lines'" @click="prefs.view='lines'">按明细行</button></span>
</form>
<template v-if="prefs.view==='orders'">
<div class="section-head"><h2>{{activeCat?.label||(dir==='in'?'入库单据':'出库单据')}} · {{ordersFiltered.length}} 张</h2><small>当前筛选数量合计（按单位）：{{totals}}</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>单号</th><th>场景</th><th>业务时间</th><th>产品</th><th class="num">明细行</th><th>来源状态</th><th>操作</th></tr></thead><tbody>
<tr v-for="o in orderPager.rows.value" :key="String(o.id)"><td>{{o.orderNo}}</td><td>{{categoryOf(o.bizTypeDisplay)?.label||'未分类'}}<small v-if="categoryOf(o.bizTypeDisplay)?.label!==o.bizTypeDisplay">{{o.bizTypeDisplay}}</small></td><td>{{fmtTime(o.bizAt)}}</td><td>{{productSummary(o)}}</td><td class="num">{{o.lines.length}}</td><td>{{o.orderStatusDisplay||'未记录'}}</td><td><button class="op" @click="detail=o">详情</button></td></tr>
<tr v-if="!orderPager.rows.value.length"><td colspan="7" class="empty">当前筛选没有来源单据，不以模拟数据补齐。</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{orderPager.page.value}} / {{orderPager.pages.value}} 页</span><button class="ui-button" :disabled="orderPager.page.value<=1" @click="orderPager.page.value--">上一页</button><button class="ui-button" :disabled="orderPager.page.value>=orderPager.pages.value" @click="orderPager.page.value++">下一页</button></div>
</template>
<template v-else>
<div class="section-head"><h2>明细行 · {{linesFiltered.length}} 行</h2><small>按单位合计：{{totals}}；不同单位不相加</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>业务时间</th><th>单号</th><th>场景</th><th>产品</th><th class="num">数量</th><th>单位</th><th>仓库</th><th>关联单号</th><th>批次号</th><th class="num">金额</th><th>操作</th></tr></thead><tbody>
<tr v-for="(l,i) in linePager.rows.value" :key="l.orderNo+'-'+i"><td>{{fmtTime(l.bizAt)}}</td><td>{{l.orderNo}}</td><td>{{l.category?.label||'未分类'}}</td><td>{{l.productName}}<small>{{l.productCode}}</small></td><td class="num">{{fmtQty(l.rawQty)}}</td><td>{{l.unit}}</td><td>{{l.warehouseName}}</td><td><button v-if="orderLink(l.related)" class="op" :title="'打开黑湖单据 '+l.related" @click="navigate(orderLink(l.related)!.path,{no:l.related})">{{l.related}}</button><template v-else>{{l.related||'无关联'}}</template></td>
<td><template v-if="batchOf(l.lineId)">{{batchOf(l.lineId)!.value.batchNo}} <span class="src-tag" data-tone="supply">Cretas 补充</span></template><span v-else class="muted">黑湖无批次字段</span></td>
<td class="num"><template v-if="l.cost!=null&&l.cost!==''">{{fmtQty(l.cost)}} <span class="src-tag">黑湖</span></template><template v-else-if="costOf(l.lineId)">{{fmtQty(costOf(l.lineId)!.value.amount)}} {{costOf(l.lineId)!.value.currency}} <span class="src-tag" data-tone="supply">Cretas 补充</span></template><span v-else class="muted">黑湖未返回</span></td>
<td class="ops"><button class="op" :disabled="!T.supply" @click="openSupplement({kind:'lineBatch',lineId:l.lineId,title:l.orderNo+' · '+l.productName+' · '+fmtQty(l.rawQty)+' '+l.unit})">补批次</button><button v-if="l.cost==null||l.cost===''" class="op" :disabled="!T.supply" @click="openSupplement({kind:'lineCost',lineId:l.lineId,title:l.orderNo+' · '+l.productName+' · '+fmtQty(l.rawQty)+' '+l.unit})">补金额</button></td></tr>
<tr v-if="!linePager.rows.value.length"><td colspan="11" class="empty">当前筛选没有明细行。</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{linePager.page.value}} / {{linePager.pages.value}} 页</span><button class="ui-button" :disabled="linePager.page.value<=1" @click="linePager.page.value--">上一页</button><button class="ui-button" :disabled="linePager.page.value>=linePager.pages.value" @click="linePager.page.value++">下一页</button></div>
</template>
</section>
<UModal :open="Boolean(detail)" :title="String(detail?.orderNo||'单据详情')" @update:open="v=>{if(!v)detail=undefined}"><template #body><div v-if="detail" class="operation-detail">
<p>六膳门 · 黑湖已审批单据 · 只读，不回写黑湖</p>
<dl><dt>场景</dt><dd>{{categoryOf(detail.bizTypeDisplay)?.label||'未分类'}}（原类型：{{detail.bizTypeDisplay}}）</dd><dt>业务时间</dt><dd>{{fmtTime(detail.bizAt)}}</dd><dt>审批时间</dt><dd>{{fmtTime(detail.approvedAt)}}</dd><dt>来源状态</dt><dd>{{detail.orderStatusDisplay||'未记录'}}</dd></dl>
<table><thead><tr><th>产品 / 编码</th><th class="num">数量</th><th>单位</th><th>仓库</th><th>关联单号</th><th>明细行ID</th><th class="num">金额</th></tr></thead><tbody><tr v-for="l in detail.lines" :key="String(l.id)"><td>{{l.productName}}<small>{{l.productCode}}</small></td><td class="num">{{fmtQty(l.qty)}}</td><td>{{l.unitName}}</td><td>{{whName(l.detailWarehouseId)||'未记录'}}</td><td>{{l.associatedBizOrderCode||'无关联'}}</td><td>{{l.id}}</td><td class="num">{{l.realCostAmount==null||l.realCostAmount===''?(costOf(l.id)?fmtQty(costOf(l.id)!.value.amount)+' '+costOf(l.id)!.value.currency+'（Cretas 补充）':'黑湖未返回'):fmtQty(l.realCostAmount)}}</td></tr></tbody></table>
<p>单据数量与库存快照时点不同，不直接相减推算可用量。</p>
</div></template></UModal>
</div></template>
