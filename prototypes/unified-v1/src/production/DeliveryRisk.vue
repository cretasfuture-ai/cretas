<script setup lang="ts">
// 交付风险（R39，G-gap-v2 第 6 节「交付风险」）：订单交期 × 计划进度 × 齐套，生产和销售两处菜单指向同一个路由，两个工作区都有。
// 味美：销售订单 + 生产计划（按销售单号关联）+ 配方与库存，经 19063 只读代理；六膳门：黑湖销售订单（交期、订单行库存）+ 黑湖工单。
// 六膳门的黑湖没有「订单 → 工单」关联（订单行 projectIds 全空），所以计划进度写「不能判断」，下方只列同产品工单供对照，不顶替。
// 判定在 delivery-risk.ts（代码判定，规则写在页面上）；齐套用 kitting.ts，进度用 progress.ts。本页只读，办理回各自原页面。
import {computed,onMounted,ref} from 'vue'
import UButton from '@nuxt/ui/components/Button.vue'
import type {Row} from '../frame/types'
import {S,rs,navigate} from '../frame/store'
import {fmtQty,sourceTagHTML} from '../frame/ai-protocol'
import {kit,type Bom,type Stock,type KitOrderStatus} from './kitting'
import {progress as plan,pct,type Schedule} from './progress'
import {assess,RULE,riskRank,type Risk} from './delivery-risk'
import {yget,num,nativeHref,YD_SESSION} from './ydwm-a'
import {bjDay,bjToday} from './time'
const ydwm=computed(()=>S.query.get('ws')==='ydwm')
interface Line{product:string;qty:number|null;unit:string;shipped:number|null;stock:number|null;kit:KitOrderStatus|null;kitNote:string}
interface Item{id:string;no:string;customer:string;due:string;state:string;lines:Line[];kit:KitOrderStatus|null;shortItems:number;schedule:Schedule|null;progressText:string;plans:string[];risk:Risk;reasons:string[];daysLeft:number|null;raw:Row}
const items=ref<Item[]>([]),busy=ref(false),err=ref(''),today=ref(''),asOf=ref(''),filter=ref<''|Risk>(''),q=ref(''),open=ref(''),notes=ref<string[]>([])
const rank=['高','中','低','不能判断'] as Risk[]
function finish(raw:Omit<Item,'risk'|'reasons'|'daysLeft'>):Item{const a=assess({due:raw.due,today:today.value,kit:raw.kit,schedule:raw.schedule});return {...raw,risk:a.risk,reasons:a.reasons,daysLeft:a.daysLeft}}
// kit 汇总：订单里每行各算一次，整单取最差（缺料 > 部分 > 不能判断 > 料齐待产 > 齐套）
const WORST:KitOrderStatus[]=['缺料','部分','不能判断','料齐待产','齐套']
const worst=(l:(KitOrderStatus|null)[])=>{const v=l.filter((x):x is KitOrderStatus=>!!x);return v.length?WORST.find(w=>v.includes(w))!:null}
// ---------- 味美 ----------
async function readYdwm(){
  today.value=bjToday();asOf.value='味美 · 隔离会话 '+YD_SESSION
  const [orders,plans,list,fg,mb]=await Promise.all([yget<Row[]>('sales/orders?page=1&size=500'),yget<Row[]>('production-plans?page=1&size=500').catch(()=>[] as Row[]),yget<Row[]>('bom/recipes?page=0&size=200').catch(()=>[] as Row[]),yget<Row[]>('sales/finished-goods?page=1&size=500').catch(()=>[] as Row[]),yget<Row[]>('material-batches?page=1&size=500').catch(()=>[] as Row[])])
  const active=list.filter(x=>x.status==='ACTIVE')
  const recipes=await Promise.all(active.map(x=>yget<Row>('bom/recipes/'+encodeURIComponent(String(x.id))).catch(()=>({} as Row))))
  const add=(a:number,b:number)=>Math.round((a+b)*1e6)/1e6
  const bomOf=(pid:string):Bom|null=>{const r=recipes.find(x=>String(x.productTypeId)===pid);if(!r)return null;const its=((r.items as Row[])||[]).filter(i=>!i.deleted);if(!its.length||its.some(i=>num(i.standardQuantity)===null))return null
    return {productId:pid,outputBase:num(r.outputQuantityPerUnit)??0,outputUnit:String(r.outputUnit??''),yieldRate:num(r.overallYieldRate)??null,yieldIncluded:true,version:r.version as number,lines:its.map(i=>({materialId:String(i.materialTypeId??i.subProductTypeId??''),name:String(i.materialName??'未提供'),qty:num(i.standardQuantity)??NaN,unit:String(i.unit??''),makeOrBuy:i.subProductTypeId?'make' as const:['RAW','PACKAGING','SEASONING'].includes(String(i.materialCategory))?'buy' as const:'unknown' as const}))}}
  const stockOf=(id:string):Stock|null=>{const g=fg.filter(x=>String(x.productTypeId)===id),m=mb.filter(x=>String(x.materialTypeId)===id),rows=g.length?g:m;if(!rows.length)return {onHand:0,allocated:0,unit:null};const us=new Set(rows.map(x=>String(x.unit??'')));if(us.size>1)return {onHand:null,allocated:null,unit:null};let s=0;for(const x of rows){const v=num(x.availableQuantity);if(v===null)return {onHand:null,allocated:null,unit:[...us][0]};s=add(s,v)}return {onHand:s,allocated:0,unit:[...us][0]||null}}
  const OPEN=new Set(['DRAFT','PENDING_APPROVAL','SUBMITTED','APPROVED','CONFIRMED','PROCESSING','PARTIAL_DELIVERED','SHIPPED'])
  const out:Item[]=[]
  for(const o of orders){
    if(!OPEN.has(String(o.status)))continue
    const no=String(o.orderNumber??'');const lines:Line[]=[];const kits:(KitOrderStatus|null)[]=[];let shortItems=0
    for(const i of ((o.items as Row[])||[])){const qty=num(i.quantity)??null;const k=qty===null||!recipes.length?null:kit({line:{productId:String(i.productTypeId),name:String(i.productName??''),qty,unit:String(i.unit??''),makeOrBuy:'make'},bomOf,stockOf,releasedOf:()=>null});kits.push(k?k.status:null);shortItems+=k?k.shortItems:0
      lines.push({product:String(i.productName??'未提供产品名称'),qty,unit:String(i.unit??''),shipped:null,stock:null,kit:k?k.status:null,kitNote:k?(k.status==='不能判断'?'缺库存、BOM 或单位不一致':k.shortItems?'缺 '+k.shortItems+' 项物料':''):'没有生效配方，不能算齐套'})}
    const mine=plans.filter(p=>[p.salesOrderNumber,p.sourceOrderNumber,p.orderNumber].some(v=>v!=null&&String(v)===no))
    let schedule:Schedule|null=null,progressText='没有关联的生产计划（按销售单号对）';const pl:string[]=[]
    if(mine.length){const sum=(k:string)=>mine.reduce((a,p)=>a+(num(p[k])??0),0);const starts=mine.map(p=>bjDay(p.plannedDate||p.startDate)).filter(Boolean).sort(),ends=mine.map(p=>bjDay(p.expectedCompletionDate||p.plannedDate)).filter(Boolean).sort()
      const g=plan({planned:sum('plannedQuantity'),done:sum('actualQuantity'),start:starts[0],end:ends[ends.length-1],finished:mine.every(p=>String(p.status).toUpperCase()==='COMPLETED'),cancelled:mine.every(p=>String(p.status).toUpperCase()==='CANCELLED'),now:Date.parse(today.value+'T12:00:00+08:00')})
      schedule=g.schedule;progressText=g.label+'：工单进度 '+pct(g.work,g.workReason)+' / 时间进度 '+pct(g.time,g.timeReason);for(const p of mine)pl.push(String(p.planNumber??p.code??p.id??'计划'))}
    out.push(finish({id:String(o.id),no,customer:String(o.customerName??'未提供'),due:bjDay(o.plannedDeliveryDate??o.deliveryDate??o.expectedDeliveryDate??o.requiredDate),state:String(o.status),lines,kit:worst(kits),shortItems,schedule,progressText,plans:pl,raw:o}))
  }
  items.value=out
  notes.value=['计划进度：按生产计划里的销售单号关联；没有关联计划的订单不算进度，只看交期和齐套。','齐套：用 A 线 kitting.ts，按订购量计算，未扣已发货量（和销售订单页一致）；只有生效配方才展开下层物料。']
}
// ---------- 六膳门 ----------
interface Extras{checkedAt:string;resources:Record<string,Row[]|null>}
async function readLsm(){
  const r=await fetch('/__lsm/supply/extras'),b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'黑湖销售订单读取失败')
  const x=b.data as Extras;today.value=bjDay(S.source?.checkedAt||x.checkedAt)||bjToday();asOf.value='黑湖快照 '+today.value+'（R28：演示「今天」按快照日）'
  const projects=rs('project'),out:Item[]=[]
  for(const o of (x.resources.sale_order||[])){
    if(!['进行中','待审批'].includes(String(o.statusDesc)))continue
    const lines:Line[]=[],kits:(KitOrderStatus|null)[]=[];let shortItems=0
    for(const l of ((o.saleManageOrderDetailRowApiVOList as Row[])||[])){
      const qty=num(l.qty)??null,shipped=num(l.productShipmentQty)??0,openQty=qty===null?null:num(l.productUnClearQty)??Math.max(qty-shipped,0),stock=num(l.productStockQty)??null
      const k=openQty===null||openQty<=0?null:kit({line:{productId:String(l.productId),name:String(l.productName??''),qty:openQty,unit:String(l.productUnitName??''),makeOrBuy:'make'},bomOf:()=>null,stockOf:()=>({onHand:stock,allocated:0,unit:null}),releasedOf:()=>null})
      kits.push(k?k.status:null);shortItems+=k&&(k.status==='缺料'||k.status==='部分')?1:0
      lines.push({product:String(l.productName??'未返回'),qty:openQty,unit:String(l.productUnitName??''),shipped,stock,kit:k?k.status:null,kitNote:openQty!==null&&openQty<=0?'已发完':stock===null?'订单行没有库存数':stock<=0?'成品库存 '+fmtQty(stock)+'，不够':'成品库存 '+fmtQty(stock)})
    }
    const codes=new Set(((o.saleManageOrderDetailRowApiVOList as Row[])||[]).map(l=>String(l.productCode)));const mine=projects.filter(p=>codes.has(String(p.productCode))&&['0','10'].includes(String(p.status)))
    out.push(finish({id:String(o.id),no:String(o.orderNo),customer:String(o.customerName??'未返回'),due:bjDay(o.arrivalPlanTime),state:String(o.statusDesc),lines,kit:worst(kits),shortItems,schedule:null,progressText:'不能判断：黑湖没有返回订单到工单的关联'+(mine.length?'（同产品未完成工单 '+mine.length+' 张，仅供对照）':'（同产品没有未完成工单）'),plans:mine.slice(0,5).map(p=>String(p.projectCode)),raw:o}))
  }
  items.value=out
  notes.value=['计划进度：黑湖订单行的 projectIds 全部为空，订单和工单对不上，所以写「不能判断」；同产品未完成工单只供对照，不当作这张订单的进度。','齐套：按订单行的未结清数量对订单行返回的成品库存（黑湖），没有 BOM 展开下层物料；库存为订单读取时的值。']
}
async function read(){busy.value=true;err.value='';try{if(ydwm.value)await readYdwm();else await readLsm()}catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
onMounted(read)
const counts=computed(()=>{const c:Record<string,number>={};for(const i of items.value)c[i.risk]=(c[i.risk]||0)+1;return c})
const shown=computed(()=>items.value.filter(i=>(!filter.value||i.risk===filter.value)&&(!q.value||(i.no+i.customer+i.lines.map(l=>l.product).join('')).toLowerCase().includes(q.value.toLowerCase()))).sort((a,b)=>riskRank(b.risk)-riskRank(a.risk)||(a.daysLeft??9e9)-(b.daysLeft??9e9)||a.no.localeCompare(b.no)))
const dueText=(i:Item)=>i.daysLeft===null?'未填写':i.daysLeft<0?'逾期 '+(-i.daysLeft)+' 天':i.daysLeft===0?'今天':'还有 '+i.daysLeft+' 天'
const tone=(r:Risk)=>r==='高'?'high':r==='中'?'mid':r==='低'?'low':'none'
function openOrder(i:Item){if(ydwm.value){const u=nativeHref('/sales/orders/'+encodeURIComponent(i.id));try{window.top!.location.href=u}catch{location.href=u}}else navigate('/sales/orders',{no:i.no})}
function openShortage(){if(ydwm.value){const u=nativeHref('/production/shortage-cases');try{window.top!.location.href=u}catch{location.href=u}}else navigate('/production/shortage-cases')}
</script>
<template><section class="card table-panel dr">
<div class="dr-head"><div><h2>交付风险</h2><p class="dr-note">订单交期 × 计划进度 × 齐套。生产和销售两处菜单是同一页。只读，办理回销售订单和缺料异常。</p></div><span v-html="sourceTagHTML(ydwm?'ydwm':'blacklake')"/></div>
<p class="dr-note">{{asOf}}　{{RULE}}</p>
<p v-if="err" role="alert" class="dr-err">{{err}}</p>
<div class="dr-sum" role="group" aria-label="按风险筛选"><button v-for="r in rank" :key="r" type="button" :class="['dr-chip','t-'+tone(r),{on:filter===r}]" :aria-pressed="filter===r" @click="filter=filter===r?'':r">{{r}} {{counts[r]||0}}</button><button v-if="filter" type="button" class="dr-chip" @click="filter=''">全部 {{items.length}}</button>
<input v-model="q" aria-label="搜索订单" placeholder="订单号、客户、产品"><UButton size="xs" color="neutral" variant="outline" :loading="busy" @click="read">重新读取</UButton></div>
<div class="table-scroll"><table><thead><tr><th>订单</th><th>客户</th><th>交期</th><th>齐套</th><th>计划进度</th><th>风险</th><th>原因</th><th class="op">操作</th></tr></thead><tbody>
<template v-for="i in shown" :key="i.id"><tr :class="'r-'+tone(i.risk)"><td><button type="button" class="dr-link" :aria-expanded="open===i.id" @click="open=open===i.id?'':i.id">{{i.no}}</button><small>{{i.state}}</small></td><td>{{i.customer}}</td><td>{{i.due||'未填写'}}<small :class="{late:(i.daysLeft??0)<0}">{{dueText(i)}}</small></td>
<td>{{i.kit||'不能判断'}}<small v-if="i.shortItems">缺 {{i.shortItems}} 项</small></td><td class="wrap">{{i.progressText}}</td><td><b :class="'badge-'+tone(i.risk)">{{i.risk}}</b></td><td class="wrap">{{i.reasons.join('；')}}</td>
<td class="op"><UButton size="xs" color="neutral" variant="outline" @click="openOrder(i)">看订单</UButton> <UButton v-if="i.kit==='缺料'||i.kit==='部分'" size="xs" color="neutral" variant="outline" @click="openShortage">缺料异常</UButton></td></tr>
<tr v-if="open===i.id" class="dr-detail"><td colspan="8"><table class="dr-lines"><thead><tr><th>产品</th><th>待交数量</th><th>齐套</th><th>说明</th></tr></thead><tbody><tr v-for="(l,n) in i.lines" :key="n"><td>{{l.product}}</td><td>{{l.qty===null?'未返回':fmtQty(l.qty)+' '+l.unit}}</td><td>{{l.kit||'—'}}</td><td>{{l.kitNote}}</td></tr><tr v-if="!i.lines.length"><td colspan="4" class="empty">来源没有返回订单行。</td></tr></tbody></table><p v-if="i.plans.length" class="dr-note">{{ydwm?'关联计划：':'同产品未完成工单（对照）：'}}{{i.plans.join('、')}}</p></td></tr></template>
<tr v-if="!shown.length"><td colspan="8" class="empty">{{busy?'读取中…':items.length?'当前筛选没有订单。':'来源里没有未完成的销售订单。'}}</td></tr></tbody></table></div>
<ul class="dr-notes"><li v-for="n in notes" :key="n">{{n}}</li></ul>
</section></template>
<style scoped>
.dr-head{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}.dr-head h2{margin:0 0 4px}.dr-note{font-size:12px;color:#667589;margin:4px 0}.dr-err{color:var(--cs-danger,#c53b3b);font-size:13px}
.dr-sum{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:8px 0}.dr-sum input{border:1px solid var(--cs-border,#d6dee7);border-radius:6px;padding:4px 8px;width:200px;margin-left:auto}
.dr-chip{border:1px solid var(--cs-border,#d6dee7);border-radius:14px;padding:2px 12px;background:var(--cs-surface,#fff);font-size:13px;cursor:pointer}.dr-chip.on{outline:2px solid var(--cs-primary,#2563eb)}
.t-high{background:#fdecec}.t-mid{background:#fff5e6}.t-low{background:#eaf6ee}.t-none{background:#f0f2f5}
.table-scroll td,.table-scroll th{height:32px;font-variant-numeric:tabular-nums;vertical-align:top}.table-scroll td:not(.wrap){white-space:nowrap}.wrap{min-width:220px;max-width:360px}.table-scroll small{display:block;color:#8a96a6;font-size:11px}small.late{color:var(--cs-danger,#c53b3b)}
.badge-high{color:var(--cs-danger,#c53b3b)}.badge-mid{color:#b86e00}.badge-low{color:var(--cs-success,#2f7d4a)}.badge-none{color:#748297}
.dr-link{border:0;background:none;color:var(--cs-primary,#2563eb);cursor:pointer;padding:0;font:inherit}.dr-detail td{background:#f8fafc}.dr-lines{width:100%}.dr-notes{font-size:12px;color:#667589;margin:8px 0 0;padding-left:18px}
</style>
