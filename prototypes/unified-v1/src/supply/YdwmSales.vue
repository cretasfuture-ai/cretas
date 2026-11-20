<script setup lang="ts">
// 味美 · 销售订单精简视图（规格第 7 节，W6；N1 计划交货、N2 三态；B4 分段进度；B1 齐套位由 A 提供）。
// 只读列表：写操作（新建、提交审核、发货、编辑）回到原销售订单页面完成，原路由保留。
import {computed,onMounted,ref,watch} from 'vue'
import {registerAiObject,setAiObject} from '../frame/ai-objects'
import type {AiFact} from '../frame/ai-protocol'
import type {Row} from '../frame/types'
import {yget,num,SALES3,RAW_STATUS,RELEASED,deliveryDateOf,days,today,YD_SESSION_LABEL,withSession} from './ydwm'
import {fmtQty,add,downloadCsv} from './supply-facts'
import {usePager,usePrefs} from './pager'
import {kit} from '../production/kitting'
import type {Bom,Stock,KitResult} from '../production/kitting'
const recipes=ref<Row[]>([]),fg=ref<Row[]>([]),mb=ref<Row[]>([]),orders=ref<Row[]>([]),deliveries=ref<Row[]>([]),busy=ref(false),err=ref(''),open=ref<Row>()
const prefs=usePrefs('unified-ydwm-sales-v1',{q:'',three:'',view:'table'})
async function read(){busy.value=true;err.value='';try{const [o,d]=await Promise.all([yget<Row[]>('sales/orders?page=1&size=500'),yget<Row[]>('sales/deliveries?page=1&size=500').catch(()=>[] as Row[])]);orders.value=o;deliveries.value=d;readKitFacts()}catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
onMounted(read)
// ---- B1 齐套：计算由 A 线 kitting.ts 提供；C 只负责读取味美 BOM（当前生效配方）、批次可用量，并显示状态与缺几项
async function readKitFacts(){try{
  const list=await yget<Row[]>('bom/recipes?page=0&size=200'),active=list.filter(x=>x.status==='ACTIVE')
  recipes.value=await Promise.all(active.map(x=>yget<Row>('bom/recipes/'+encodeURIComponent(String(x.id)))))
  ;[fg.value,mb.value]=await Promise.all([yget<Row[]>('sales/finished-goods?page=1&size=500'),yget<Row[]>('material-batches?page=1&size=500')])
}catch{recipes.value=[]}}
function bomOf(pid:string):Bom|null{const r=recipes.value.find(x=>String(x.productTypeId)===pid);if(!r)return null;const items=((r.items as Row[])||[]).filter(i=>!i.deleted);if(!items.length)return null;
  // 有任一行标准用量未填写时不展开（不把缺失当 0），由 kitting 标“缺 BOM，不能展开下层”
  if(items.some(i=>num(i.standardQuantity)===undefined))return null;return {productId:pid,outputBase:num(r.outputQuantityPerUnit)??0,outputUnit:String(r.outputUnit??''),yieldRate:num(r.overallYieldRate)??null,yieldIncluded:true,version:r.version as number,lines:items.filter(i=>!i.deleted).map(i=>({materialId:String(i.materialTypeId??i.subProductTypeId??''),name:String(i.materialName??'未提供'),qty:num(i.standardQuantity)??NaN,unit:String(i.unit??''),makeOrBuy:i.subProductTypeId?'make' as const:String(i.materialCategory)==='RAW'||String(i.materialCategory)==='PACKAGING'||String(i.materialCategory)==='SEASONING'?'buy' as const:'unknown' as const}))}}
function stockOf(id:string):Stock|null{const goods=fg.value.filter(g=>String(g.productTypeId)===id),mats=mb.value.filter(b=>String(b.materialTypeId)===id);const rows=goods.length?goods:mats;if(!rows.length)return {onHand:0,allocated:0,unit:null};const units=new Set(rows.map(x=>String(x.unit??'')));if(units.size>1)return {onHand:null,allocated:null,unit:null};let s=0;for(const x of rows){const v=num(x.availableQuantity);if(v===undefined)return {onHand:null,allocated:null,unit:[...units][0]};s=add(s,v)}return {onHand:s,allocated:0,unit:[...units][0]||null}}
function kitOf(o:Row):{line:string;result:KitResult}[]{if(!recipes.value.length)return [];return ((o.items as Row[])||[]).filter(i=>num(i.quantity)!==undefined).map(i=>({line:String(i.productName??'未提供产品名称'),result:kit({line:{productId:String(i.productTypeId),name:String(i.productName??''),qty:num(i.quantity)!,unit:String(i.unit??''),makeOrBuy:'make'},bomOf,stockOf,releasedOf:()=>null})}))}
// A 线汇总状态（KitOrderStatus）：齐套 / 料齐待产 / 部分 / 缺料 / 不能判断
// 审计退回第 11 条：打开销售单详情时，把这张订单交给唯一悬浮窗（只提供系统计算的依据）
registerAiObject('ydwm-sales-order',{noun:'这张销售订单',placeholder:'例如：这张单卡在哪一步？还差多少没发？',
  describe:id=>{const o=orders.value.find(x=>String(x.id)===id);return o?'销售单 '+String(o.orderNumber)+' · '+String(o.customerName??''):undefined},
  facts:id=>{const o=orders.value.find(x=>String(x.id)===id);if(!o)return [];const p=progress(o),k=kitOf(o)
    const f:AiFact[]=[{label:'来源',value:'味美 · '+YD_SESSION_LABEL(),kind:'source:ydwm'},{label:'状态',value:three(o)+'（原状态：'+(RAW_STATUS[String(o.status)]||String(o.status))+'）'},{label:'计划交货',value:deliveryDateOf(o)||null},{label:'下达',value:p.released?'已下达':'未下达'}]
    for(const s of p.ship){f.push({label:'订购（'+s.unit+'）',value:s.ordered,unit:s.unit},{label:'已发（'+s.unit+'）',value:s.shipped??0,unit:s.unit},{label:'待发（'+s.unit+'）',value:Math.max(0,s.ordered-(s.shipped??0)),unit:s.unit})}
    for(const x of k)f.push({label:'齐套 · '+x.line,value:x.result.status+(x.result.shortItems?'，缺 '+x.result.shortItems+' 项':'')})
    f.push({label:'已下达数量',value:null},{label:'说明',value:'取消单不计；已下达数量味美接口未返回，齐套按全部需求计算'})
    return f}})
watch(open,o=>{if(o)setAiObject({kind:'ydwm-sales-order',id:String(o.id)},'销售订单详情')})
// BL 12：这张单引起的收发记录（销售侧 = 发货单：母单 + 子发运）
const SHIP_STATUS:Record<string,string>={DRAFT:'草稿',PENDING:'待确认',CONFIRMED:'已确认',SHIPPED:'已发货',DELIVERED:'已送达',CANCELLED:'已取消'}
function shipLog(o:Row){const out:{no:string;role:string;status:string;qty:unknown;unit:string;at:string}[]=[];for(const d of deliveries.value){if(String(d.orderNumber)!==String(o.orderNumber)&&String(d.salesOrderId)!==String(o.id))continue;for(const i of (d.items as Row[])||[])out.push({no:String(d.deliveryNumber??d.id),role:d.recordRole==='MASTER'?'母发货单':d.recordRole?'子发运':'发货单',status:SHIP_STATUS[String(d.status)]||String(d.status??''),qty:i.deliveredQuantity??i.actualQuantity,unit:String(i.unit??''),at:String(d.shippedAt??d.createdAt??'').replace('T',' ').slice(0,16)})}return out}
const detailTab=ref<'info'|'kit'|'log'|'lines'>('info')
watch(open,()=>{detailTab.value='info'})
const kitTone=(s:string)=>s==='齐套'?'ok':s==='料齐待产'?'info':s==='缺料'?'negative':s==='部分'?'zero':'muted'

type Item={productName:string;unit:string;quantity:number|undefined}
const items=(o:Row)=>((o.items as Row[])||[]).map(i=>({productName:String(i.productName??'未提供产品名称'),unit:String(i.unit??'（无单位）'),quantity:num(i.quantity)})) as Item[]
// 已发：取发货单（只取 MASTER，避免与子发运重复），按订单号 + 单位汇总
const shipped=computed(()=>{const m=new Map<string,Map<string,number>>();for(const d of deliveries.value){if(d.recordRole&&d.recordRole!=='MASTER')continue;if(String(d.status)==='CANCELLED')continue;const k=String(d.orderNumber);const u=m.get(k)||new Map();for(const i of (d.items as Row[])||[]){const q=num(i.deliveredQuantity);if(q!==undefined)u.set(String(i.unit??'（无单位）'),add(u.get(String(i.unit??'（无单位）'))||0,q))}m.set(k,u)}return m})
function qtyByUnit(o:Row){const m=new Map<string,number>();for(const i of items(o))if(i.quantity!==undefined)m.set(i.unit,add(m.get(i.unit)||0,i.quantity));return m}
function progress(o:Row){
  const st=String(o.status),ordered=qtyByUnit(o),sh=shipped.value.get(String(o.orderNumber))
  const units=[...ordered.keys()]
  const ship=units.map(u=>({unit:u,ordered:ordered.get(u)!,shipped:sh?.get(u)}))
  return {released:RELEASED.has(st),cancelled:st==='CANCELLED',ship}
}
const three=(o:Row)=>SALES3[String(o.status)]||'未识别'
const filtered=computed(()=>orders.value.filter(o=>(!prefs.value.three||three(o)===prefs.value.three)&&(!prefs.value.q||(String(o.orderNumber)+String(o.customerName)).toLowerCase().includes(prefs.value.q.toLowerCase()))).sort((a,b)=>String(b.createdAt).localeCompare(String(a.createdAt))))
const pager=usePager(filtered)
// 第二批②：后端对无价格权限的角色把金额置空；整页都没有金额时隐藏金额列（不按角色名判断）
const moneyVisible=computed(()=>!orders.value.length||orders.value.some(o=>num(o.totalAmount)!==undefined))
const counts=computed(()=>{const c:Record<string,number>={};for(const o of orders.value)c[three(o)]=(c[three(o)]||0)+1;return c})
const productSummary=(o:Row)=>{const n=[...new Set(items(o).map(i=>i.productName))];return n.length>1?n[0]+' 等 '+n.length+' 种':n[0]||'—'}
const qtyText=(o:Row)=>{const p=progress(o);return p.ship.map(s=>fmtQty(s.ordered)+'·'+(fmtQty(s.shipped??0))+'·'+fmtQty(Math.max(0,s.ordered-(s.shipped||0)))+' '+s.unit).join('；')||'—'}
const dueText=(o:Row)=>{const d=deliveryDateOf(o);if(!d)return {text:'未填写',late:0};const late=['已完成','已取消'].includes(three(o))?0:Math.max(0,days(d,today())||0);return {text:d,late}}
const TONE:Record<string,string>={未完成:'zero',进行中:'info',已完成:'ok',已取消:'muted',未识别:'muted'}
// D5：缺料处理在 A 的味美原生缺料事件页；该页暂不支持按单号筛选，不传参数
// A 线入口：料齐待产 → 原生新建计划（create=1，带销售单号、成品名、净需求；不自动填写或保存），沿用本视图的隔离会话
function planProduction(o:Row,root:{name:string;netDemand:number|null;unit:string}){const q=new URLSearchParams({create:'1',no:String(o.orderNumber),product:root.name,qty:String(root.netDemand??''),unit:root.unit});withSession(q);const u='/production/plans?'+q.toString();try{window.top!.location.href=u}catch{location.href=u}}
function openShortage(){const qs=withSession(new URLSearchParams()).toString(),u='/production/shortage-cases'+(qs?'?'+qs:'');try{window.top!.location.href=u}catch{location.href=u}}
function openNative(o:Row){const s=withSession(new URLSearchParams()).toString(),u='/sales/orders/'+encodeURIComponent(String(o.id))+(s?'?'+s:'');try{window.top!.location.href=u}catch{location.href=u}}
function exportCsv(){downloadCsv('味美销售订单.csv',['单号','客户','产品','计划交货','订购·已发·待发','金额','三态','原状态'],filtered.value.map(o=>[o.orderNumber,o.customerName,productSummary(o),dueText(o).text,qtyText(o),num(o.totalAmount)??'',three(o),RAW_STATUS[String(o.status)]||o.status]))}
defineExpose({exportCurrent:exportCsv})
</script>
<template><div>
<p class="supply-note">味美工作区 · {{YD_SESSION_LABEL()}}。<template v-if="!moneyVisible">金额未返回（可能是无价格权限或未录入），金额列已隐藏。</template>本视图只读，新建、提交审核、发货在原销售订单页面完成（点“处理”进入）；计划交货日期味美接口暂无字段时显示“未填写”。</p>
<p v-if="err" role="alert" class="error">{{err}}</p>
<section class="card">
<form class="filters" @submit.prevent>
<input v-model="prefs.q" aria-label="搜索单号或客户" placeholder="单号、客户">
<select v-model="prefs.three" aria-label="按状态筛选"><option value="">全部状态（{{orders.length}}）</option><option v-for="s in ['未完成','进行中','已完成','已取消']" :key="s" :value="s">{{s}}（{{counts[s]||0}}）</option></select>
<button type="button" class="ui-button" @click="prefs.q='';prefs.three=''">重置</button>
<button type="button" class="ui-button" :disabled="busy" @click="read">{{busy?'正在读取…':'刷新'}}</button>
<span class="seg" role="group" aria-label="视图"><button type="button" :aria-pressed="prefs.view==='table'" @click="prefs.view='table'">表格</button><button type="button" :aria-pressed="prefs.view==='board'" @click="prefs.view='board'">看板</button></span>
</form>
<template v-if="prefs.view==='table'">
<div class="section-head"><h2>销售订单 · {{filtered.length}} 张</h2><small>订购·已发·待发按单位分开；三态由单据状态按固定规则映射，取消单不进合计</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>单号</th><th>客户</th><th>产品</th><th>计划交货</th><th class="num">订购·已发·待发</th><th v-if="moneyVisible" class="num">金额</th><th>状态</th><th>进度</th><th>操作</th></tr></thead><tbody>
<tr v-for="o in pager.rows.value" :key="String(o.id)"><td>{{o.orderNumber}}</td><td>{{o.customerName||'未记录'}}</td><td>{{productSummary(o)}}</td>
<td><span :class="dueText(o).text==='未填写'?'muted':''">{{dueText(o).text}}</span><span v-if="dueText(o).late" class="supply-badge" data-tone="zero">已逾期 {{dueText(o).late}} 天</span></td>
<td class="num">{{qtyText(o)}}</td><td v-if="moneyVisible" class="num">{{num(o.totalAmount)===undefined?'—':fmtQty(o.totalAmount)+' 元'}}</td>
<td><span class="supply-badge" :data-tone="TONE[three(o)]" :title="'原状态：'+(RAW_STATUS[String(o.status)]||o.status)">{{three(o)}}</span></td>
<td><span class="seg-progress" :title="progress(o).released?'已下达':'未下达'"><i :data-on="progress(o).released||undefined">下达</i><i v-for="s in progress(o).ship" :key="s.unit" :data-on="(s.shipped||0)>=s.ordered||undefined" :data-part="(s.shipped||0)>0&&(s.shipped||0)<s.ordered||undefined" :title="'发货 '+(fmtQty(s.shipped??0))+' / '+fmtQty(s.ordered)+' '+s.unit">发货</i></span></td>
<td class="ops"><button class="op" @click="open=o">详情</button><button class="op" @click="openNative(o)">处理</button></td></tr>
<tr v-if="!pager.rows.value.length"><td colspan="9" class="empty">{{busy?'正在读取':'当前筛选没有销售订单。'}}</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{pager.page.value}} / {{pager.pages.value}} 页</span><button class="ui-button" :disabled="pager.page.value<=1" @click="pager.page.value--">上一页</button><button class="ui-button" :disabled="pager.page.value>=pager.pages.value" @click="pager.page.value++">下一页</button></div>
</template>
<div v-else class="board"><section v-for="s in ['未完成','进行中','已完成','已取消']" :key="s" class="board-col"><h3>{{s}} <small>{{filtered.filter(o=>three(o)===s).length}}</small></h3><button v-for="o in filtered.filter(o=>three(o)===s)" :key="String(o.id)" class="board-card" @click="open=o"><b>{{o.orderNumber}}</b><small>{{o.customerName}} · {{productSummary(o)}}</small><small>交货 {{dueText(o).text}}</small></button></section></div>
</section>
<section v-if="open" class="card detail-card" aria-label="订单详情"><div class="section-head"><h2>{{open.orderNumber}} · {{open.customerName}}</h2><button class="ui-button" @click="open=undefined">关闭</button></div>
<div class="cs-tabs lsm-tabs supply-tabs" role="tablist" aria-label="销售订单详情"><button role="tab" :aria-selected="detailTab==='info'" @click="detailTab='info'">概况</button><button role="tab" :aria-selected="detailTab==='kit'" @click="detailTab='kit'">齐套</button><button role="tab" :aria-selected="detailTab==='log'" @click="detailTab='log'">收发记录 <small>{{shipLog(open).length}}</small></button><button role="tab" :aria-selected="detailTab==='lines'" @click="detailTab='lines'">明细</button></div>
<template v-if="detailTab==='info'"><dl class="kv"><dt>状态</dt><dd>{{three(open)}}（原状态：{{RAW_STATUS[String(open.status)]||open.status}}）</dd><dt>计划交货</dt><dd>{{dueText(open).text}}</dd><dt>收货地址</dt><dd>{{open.deliveryAddress||'未记录'}}</dd></dl>
<h3>分段进度</h3>
<ol class="stages"><li :data-on="progress(open).released||undefined">下达：{{progress(open).cancelled?'已取消':progress(open).released?'已审核下达':'未下达'}}</li><li>生产 / 外购：味美订单未区分制造模式，不显示该段</li><li>成品入库：订单与成品批次未直接关联，未返回</li><li v-for="s in progress(open).ship" :key="s.unit" :data-on="(s.shipped||0)>=s.ordered||undefined">发货：{{fmtQty(s.shipped??0)}} / {{fmtQty(s.ordered)}} {{s.unit}}</li></ol>
</template>
<template v-else-if="detailTab==='kit'"><p class="muted detail-pad">计算由生产线提供（kitting）。</p>
<p v-if="!kitOf(open).length" class="muted">没有读到当前生效配方或订单明细，暂不能判断齐套。</p>
<template v-for="k in kitOf(open)" :key="k.line"><p class="kit-head"><b>{{k.line}}</b> <span class="supply-badge" :data-tone="kitTone(k.result.status)">{{k.result.status}}<template v-if="k.result.status==='料齐待产'&&k.result.rows[0]?.netDemand!=null"> · 需生产 {{fmtQty(k.result.rows[0].netDemand)}} {{k.result.rows[0].unit}}</template></span><span v-if="k.result.shortItems">缺 {{k.result.shortItems}} 项</span><span v-if="k.result.unknownItems" class="muted">{{k.result.unknownItems}} 项不能判断</span><button v-if="k.result.status==='料齐待产'&&k.result.rows[0]?.netDemand" class="op" title="打开新建生产计划（带齐套核对说明，成品和数量由人确认，不自动保存）" @click="planProduction(open!,k.result.rows[0])">安排生产</button><button v-if="k.result.status==='部分'||k.result.status==='缺料'" class="op" @click="openShortage">去缺料事件核对</button></p>
<table class="supply-table"><thead><tr><th>层级</th><th>物料</th><th class="num">需求</th><th class="num">已下达</th><th class="num">可用</th><th class="num">净需求</th><th>单位</th><th>状态</th><th>下一步</th><th>说明</th></tr></thead><tbody><tr v-for="row in k.result.rows" :key="row.level"><td>{{row.level}}</td><td>{{row.name}}</td><td class="num">{{row.required===null?'—':fmtQty(row.required)}}</td><td class="num">{{row.released===null?'未返回':fmtQty(row.released)}}</td><td class="num">{{row.available===null?'—':fmtQty(row.available)}}</td><td class="num">{{row.netDemand===null?'—':fmtQty(row.netDemand)}}</td><td>{{row.unit}}</td><td><span class="supply-badge" :data-tone="kitTone(row.status)">{{row.status}}</span></td><td>{{row.next||'—'}}</td><td class="muted">{{row.issue||''}}</td></tr></tbody></table>
<p class="muted">{{k.result.rule}}。已下达数量味美接口未返回，按全部需求计算；可用 = 该物料档案（按 ID）各批次可用量合计，不含预留和未放行，所以可能小于进销存汇总的期末余量。安排生产或采购请到生产分析的缺料事件核对（暂不能按单号定位）。</p></template>
</template>
<template v-else-if="detailTab==='log'">
<p v-if="!shipLog(open).length" class="muted">这张单还没有发货记录。</p>
<table v-else class="supply-table"><thead><tr><th>发货单号</th><th>记录类型</th><th>状态</th><th class="num">数量</th><th>单位</th><th>时间</th></tr></thead><tbody><tr v-for="(d,k) in shipLog(open)" :key="k"><td>{{d.no}}</td><td>{{d.role}}</td><td>{{d.status}}</td><td class="num">{{fmtQty(d.qty)}}</td><td>{{d.unit}}</td><td>{{d.at}}</td></tr></tbody></table>
<p class="muted">只列这张单引起的发货（母发货单和每次子发运）；出库扣减的批次在仓储出货页核对。合计只取母发货单，不和子发运重复相加。</p>
</template>
<template v-else><table class="supply-table"><thead><tr><th>产品</th><th class="num">数量</th><th>单位</th><th v-if="moneyVisible" class="num">单价</th></tr></thead><tbody><tr v-for="(i,k) in (open.items as Row[])" :key="k"><td>{{i.productName||'未提供产品名称'}}</td><td class="num">{{fmtQty(i.quantity)}}</td><td>{{i.unit}}</td><td v-if="moneyVisible" class="num">{{num(i.unitPrice)===undefined?'—':fmtQty(i.unitPrice)}}</td></tr></tbody></table></template>
</section>
</div></template>
