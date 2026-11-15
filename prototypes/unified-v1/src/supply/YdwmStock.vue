<script setup lang="ts">
// 味美 · 进销存汇总（N6）／库龄与呆滞（B8）／补货建议（B2）。只读；数量全部由代码按接口字段计算，缺字段显示“未返回”，不按 0 参与计算。
import {computed,onMounted,ref} from 'vue'
import type {Row} from '../frame/types'
import {valueMaterials,valueFinished,VALUATION_RULE} from '../cost/cost-review'
import type {ValuationRow,YdwmBatchLike,CostProduct} from '../cost/cost-review'
import {ydSession} from './ydwm'
import {yget,num,dateOnly,today,days,YD_SESSION_LABEL,withSession} from './ydwm'
import {fmtQty,add,downloadCsv} from './supply-facts'
import {usePager,usePrefs} from './pager'
import {openFormula} from '../frame/ai-protocol'
const props=defineProps<{initialTab?:string}>()
const prefs=usePrefs('unified-ydwm-stock-v1',{tab:props.initialTab||'summary',q:'',from:'',to:'',age:''})
const batches=ref<Row[]>([]),goods=ref<Row[]>([]),types=ref<Row[]>([]),purchases=ref<Row[]>([]),reqs=ref<Row[]>([]),sales=ref<Row[]>([]),deliveries=ref<Row[]>([]),busy=ref(false),err=ref('')
async function read(){busy.value=true;err.value='';try{
  const r=await Promise.all([yget<Row[]>('material-batches?page=1&size=500'),yget<Row[]>('sales/finished-goods?page=1&size=500'),yget<Row[]>('raw-material-types/active'),yget<Row[]>('purchase/orders?page=1&size=500').catch(()=>[] as Row[]),yget<Row[]>('material-requisitions?page=1&size=200').catch(()=>[] as Row[]),yget<Row[]>('sales/orders?page=1&size=500').catch(()=>[] as Row[]),yget<Row[]>('sales/deliveries?page=1&size=500').catch(()=>[] as Row[])])
  ;[batches.value,goods.value,types.value,purchases.value,reqs.value,sales.value,deliveries.value]=r
}catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
onMounted(read)
const readAt=ref(new Date().toLocaleString('zh-CN',{hour12:false}))
// 统一的“批次”视图：原料批次 + 成品批次
interface B{kind:'原料'|'成品';id:string;mid:string;name:string;batchNo:string;unit:string;inQty:number|undefined;remaining:number|undefined;inDate:string;inDateField:string;expire:string;shelfDays:number|undefined}
const shelfOf=(name:string)=>{const t=types.value.find(x=>x.name===name);return num(t?.shelfLifeDays??t?.shelfLife)}
const all=computed<B[]>(()=>[
  ...batches.value.map(b=>{const f=b.inboundDate?'inboundDate':b.receiptDate?'receiptDate':'createdAt';return {kind:'原料' as const,id:String(b.id),mid:String(b.materialTypeId??''),name:String(b.materialName??'未提供'),batchNo:String(b.batchNumber??'—'),unit:String(b.unit??'（无单位）'),inQty:num(b.receiptQuantity??b.quantity),remaining:num(b.remainingQuantity??b.currentQuantity),inDate:dateOnly(b[f]),inDateField:f==='createdAt'?'按记录创建时间（入库日未返回）':'入库日',expire:dateOnly(b.expireDate??b.expiryDate),shelfDays:shelfOf(String(b.materialName))}}),
  ...goods.value.map(g=>({kind:'成品' as const,id:String(g.id),mid:String(g.productTypeId??''),name:String(g.productName??'未提供'),batchNo:String(g.batchNumber??'—'),unit:String(g.unit??'（无单位）'),inQty:num(g.producedQuantity),remaining:(()=>{const p=num(g.producedQuantity),s=num(g.shippedQuantity);return p===undefined||s===undefined?undefined:add(p,-s)})(),inDate:dateOnly(g.createdAt),inDateField:'按记录创建时间（入库日未返回）',expire:dateOnly(g.expireDate),shelfDays:undefined}))
])
const q=(s:string)=>!prefs.value.q||s.toLowerCase().includes(prefs.value.q.toLowerCase())

// ---- N6 进销存汇总
const minDate=computed(()=>all.value.map(b=>b.inDate).filter(Boolean).sort()[0]||today())
const from=computed({get:()=>prefs.value.from||minDate.value,set:v=>{prefs.value.from=v}}),to=computed({get:()=>prefs.value.to||today(),set:v=>{prefs.value.to=v}})
// 审计退回第 10 条：按物料档案 ID 分行（同名不同 ID 不合并，名称后加 ID 尾号），与齐套按 ID 取数一致
const dupNames=computed(()=>{const m=new Map<string,Set<string>>();for(const b of all.value){const s=m.get(b.kind+b.name)||new Set();s.add(b.mid);m.set(b.kind+b.name,s)}return new Set([...m.entries()].filter(([,s])=>s.size>1).map(([k])=>k))})
const nameOf=(b:B)=>dupNames.value.has(b.kind+b.name)?b.name+' · '+(b.mid.slice(-4)||'无ID'):b.name
const summary=computed(()=>{const m=new Map<string,{kind:string;mid:string;name:string;unit:string;inPeriod:number;inMissing:number;outTotal:number|undefined;closing:number|undefined}>()
  for(const b of all.value){const k=b.kind+'|'+b.mid+'|'+b.unit;const r=m.get(k)||{kind:b.kind,mid:b.mid,name:nameOf(b),unit:b.unit,inPeriod:0,inMissing:0,outTotal:0,closing:0}
    if(b.inDate&&b.inDate>=from.value&&b.inDate<=to.value){if(b.inQty===undefined)r.inMissing++;else r.inPeriod=add(r.inPeriod,b.inQty)}
    if(b.inQty===undefined||b.remaining===undefined)r.outTotal=undefined;else if(r.outTotal!==undefined)r.outTotal=add(r.outTotal,b.inQty-b.remaining)
    if(b.remaining===undefined)r.closing=undefined;else if(r.closing!==undefined)r.closing=add(r.closing,b.remaining)
    m.set(k,r)}
  return [...m.values()].filter(r=>q(r.name)).sort((a,b)=>a.kind.localeCompare(b.kind)||a.name.localeCompare(b.name,'zh'))})
const closingAvailable=computed(()=>to.value===today())
// 第四批③ BL14：期末金额用 B 的估值口径（cost-review.ts 的 valueMaterials / VALUATION_RULE）。只读显示，不另算。
// 原料：同物料同单位批次加权；单价缺或为 0 记「无单价」，不补 0。成品单价在成本分析，本页未接入，标「无单价」。
// 价格权限（第二批口径）：后端对无价格权限的角色把单价置空；所有批次都没有单价时整列隐藏并说明，不按角色名判断。
const valuation=computed(()=>new Map(valueMaterials(batches.value as unknown as YdwmBatchLike[]).map(v=>[v.key,v])))
const priceVisible=computed(()=>batches.value.some(b=>num(b.unitPrice)!==undefined))
// 成品：B 的 valueFinished（入参是 /__account/product-cost 的 products），按产品类型 ID＋单位对应；期末金额＝期末余量×单位直接成本，同单位才乘
const costProducts=ref<CostProduct[]>([]),costErr=ref('')
onMounted(async()=>{try{const r=await fetch(location.origin+'/__account/product-cost',{headers:ydSession()?{'x-prototype-test-session':ydSession()}:{}}),b=await r.json();if(!b.success)throw Error(b.message||'成本分析读取失败');costProducts.value=b.data?.products||[]}catch(e){costErr.value=e instanceof Error?e.message:'成本分析读取失败'}})
const finished=computed(()=>{const v=valueFinished(costProducts.value),m=new Map<string,ValuationRow>();costProducts.value.forEach((p,i)=>m.set(String(p.productTypeId)+'|'+String(p.unit??''),v[i]));return m})
function valOf(r:{kind:string;mid:string;unit:string;closing?:number}):ValuationRow|null{const u=r.unit==='（无单位）'?'':r.unit
  if(r.kind==='原料')return valuation.value.get(r.mid+'|'+u)||null
  const f=finished.value.get(r.mid+'|'+u);if(!f)return null
  return {...f,qty:r.closing??null,amount:f.unitPrice!==null&&r.closing!==undefined?Math.round(r.closing*f.unitPrice*100)/100:null,basis:f.basis+(f.unitPrice!==null?'；期末金额＝期末余量×单位直接成本':'')}}
const sumPager=usePager(summary)

// ---- B8 库龄与呆滞：按批次入库日与保质期分档
type Tier='正常'|'临期'|'已过期'|'长期未动'|'未录保质期'
function tier(b:B):{tier:Tier;note:string}{
  const age=days(b.inDate)
  const exp=b.expire||(b.shelfDays!==undefined&&b.inDate?new Date(Date.parse(b.inDate)+b.shelfDays*864e5).toISOString().slice(0,10):'')
  if(exp){const left=days(today(),exp)!;const warn=Math.max(3,Math.round((b.shelfDays??30)*0.2));if(left<0)return {tier:'已过期',note:'已过期 '+(-left)+' 天'};if(left<=warn)return {tier:'临期',note:'剩余 '+left+' 天（阈值 '+warn+' 天）'}}
  if(age!==undefined&&age>90&&b.inQty!==undefined&&b.remaining!==undefined&&b.remaining>=b.inQty)return {tier:'长期未动',note:'入库 '+age+' 天未出库'}
  if(!exp)return {tier:'未录保质期',note:'批次和物料都没有保质期，无法判断临期'}
  return {tier:'正常',note:'剩余 '+days(today(),exp)+' 天'}
}
const aging=computed(()=>all.value.filter(b=>(b.remaining??1)>0&&q(b.name+b.batchNo)).map(b=>({b,age:days(b.inDate),...tier(b)})).filter(x=>!prefs.value.age||x.tier===prefs.value.age).sort((a,b)=>(b.age??-1)-(a.age??-1)))
const tierCount=computed(()=>{const c:Record<string,number>={};for(const b of all.value)if((b.remaining??1)>0){const t=tier(b).tier;c[t]=(c[t]||0)+1}return c})
const agePager=usePager(aging)

// ---- B2 补货建议：库存线 + 在途 + 在制 − 待发 − 待领 − 未清需求（逐项列出，缺项不按 0 计）
// 原料资料里同名同单位的重复档案只算一次（重复档案本身由第 22 节物料资料处理）
const uniqTypes=computed(()=>{const seen=new Set<string>();return types.value.filter(t=>{const k=String(t.name)+'|'+String(t.unit??'');if(seen.has(k))return false;seen.add(k);return true})})
const replenish=computed(()=>uniqTypes.value.filter(t=>q(String(t.name))).map(t=>{
  const name=String(t.name),unit=String(t.unit??'')
  const own=batches.value.filter(b=>b.materialName===name&&String(b.unit??'')===unit)
  const onHand=own.length?own.reduce((s,b)=>add(s,num(b.availableQuantity??b.remainingQuantity)??0),0):0
  let transit=0;for(const p of purchases.value){if(['CANCELLED','CLOSED','COMPLETED','RECEIVED','DRAFT','REJECTED'].includes(String(p.status)))continue;for(const i of (p.items as Row[])||[])if(i.materialName===name&&String(i.unit??'')===unit)transit=add(transit,num(i.quantity)??0)}
  let toIssue=0;for(const r of reqs.value){if(['CANCELLED','CLOSED','COMPLETED'].includes(String(r.status)))continue;for(const i of (r.items as Row[])||[])if(i.materialName===name&&String(i.unit??'')===unit)toIssue=add(toIssue,Math.max(0,(num(i.requiredQty)??0)-(num(i.issuedQty)??0)))}
  const line=num(t.safetyStock??t.minStock)
  const projected=add(add(onHand,transit),-toIssue)
  return {name,unit,line,onHand,transit,toIssue,projected,suggest:line===undefined?undefined:Math.max(0,add(line,-projected))}
}))
const repPager=usePager(replenish)
// B3 口径弹层：加项、减项、一行公式；缺项写“未返回”，不按 0 计
function formula(e:Event,r:{name:string;unit:string;line:number|undefined;onHand:number;transit:number;toIssue:number;projected:number;suggest:number|undefined}){openFormula(e.currentTarget as HTMLElement,{title:r.name+' · 补货建议口径',result:{value:r.suggest??null,unit:r.unit},plus:[{label:'库存线（安全库存）',value:r.line??null,unit:r.unit},{label:'待领',value:r.toIssue,unit:r.unit,source:'ydwm'},{label:'待发（原料不适用）',value:0,unit:r.unit},{label:'未清需求（未返回）',value:null,unit:r.unit}],minus:[{label:'在库可用',value:r.onHand,unit:r.unit,source:'ydwm'},{label:'采购在途',value:r.transit,unit:r.unit,source:'ydwm'},{label:'在制（未返回）',value:null,unit:r.unit}],formula:'建议补货 = 库存线 −（在库可用 + 采购在途 + 在制 − 待领 − 待发 − 未清需求）',note:r.line===undefined?'味美物料没有库存线字段，无法计算建议量；在制、未清需求未返回，不按 0 计。':'在制、未清需求未返回，不按 0 计。'})}
function exportCurrent(){
  if(prefs.value.tab==='aging')downloadCsv('味美库龄与呆滞.csv',['类型','物料','批次','剩余','单位','入库日','依据','库龄天数','分档','说明'],aging.value.map(x=>[x.b.kind,x.b.name,x.b.batchNo,x.b.remaining??'',x.b.unit,x.b.inDate,x.b.inDateField,x.age??'',x.tier,x.note]))
  else if(prefs.value.tab==='replenish')downloadCsv('味美补货建议.csv',['物料','单位','库存线','在库可用','采购在途','在制','待领','待发','未清需求','预计可用','建议补货'],replenish.value.map(r=>[r.name,r.unit,r.line??'未设置',r.onHand,r.transit,'未返回',r.toIssue,'不适用（原料）','未返回',r.projected,r.suggest??'无法计算：未设库存线']))
  else downloadCsv('味美进销存汇总-'+from.value+'至'+to.value+'.csv',['类型','物料','单位','期初','期间入库','累计出库（不分期）','期末'],summary.value.map(r=>['原料'===r.kind?'原料':'成品',r.name,r.unit,'无期初快照',r.inPeriod,r.outTotal??'未返回',closingAvailable.value?r.closing??'未返回':'无期末快照']))
}
defineExpose({exportCurrent})
</script>
<template><div>
<p class="supply-note">味美工作区 · {{YD_SESSION_LABEL()}} · 读取于 {{readAt}}。只读：数量由代码按接口字段计算；缺字段显示“未返回”，不按 0 参与计算。</p>
<p v-if="err" role="alert" class="error">{{err}}</p>
<div class="cs-tabs lsm-tabs supply-tabs" role="tablist" aria-label="库存分析视图">
<button role="tab" :aria-selected="prefs.tab==='summary'" @click="prefs.tab='summary'">进销存汇总</button>
<button role="tab" :aria-selected="prefs.tab==='aging'" @click="prefs.tab='aging'">库龄与呆滞</button>
<button role="tab" :aria-selected="prefs.tab==='replenish'" @click="prefs.tab='replenish'">补货建议</button>
</div>
<section class="card">
<form class="filters" @submit.prevent>
<input v-model="prefs.q" aria-label="搜索物料" placeholder="物料或批次">
<template v-if="prefs.tab==='summary'"><label>期间 <input v-model="from" type="date" aria-label="期间开始"></label><label>至 <input v-model="to" type="date" aria-label="期间结束"></label></template>
<select v-if="prefs.tab==='aging'" v-model="prefs.age" aria-label="库龄分档"><option value="">全部分档</option><option v-for="t in ['正常','临期','已过期','长期未动','未录保质期']" :key="t" :value="t">{{t}}（{{tierCount[t]||0}}）</option></select>
<button type="button" class="ui-button" @click="prefs.q='';prefs.age=''">重置</button><button type="button" class="ui-button" :disabled="busy" @click="read">{{busy?'正在读取…':'刷新'}}</button>
</form>
<template v-if="prefs.tab==='summary'">
<div class="section-head"><h2>进销存汇总 · {{from}} 至 {{to}} · {{summary.length}} 行</h2><small>期初无快照；期间入库按批次入库日；出库味美没有按日期的流水，只能给累计；期末只在期末日为今天时取当前余量</small></div>
<p class="supply-note">口径：期末 = 该物料档案（按 ID）在全部仓库所有批次的当前余量合计，含未放行和已预留的部分；销售订单齐套里的“可用”= 同一 ID 各批次的可用量合计，只计可用。两者对同一物料可以不同，可用不大于期末。同名但档案 ID 不同的物料分行显示，名称后带 ID 尾号。<template v-if="priceVisible"> 金额口径（B）：{{VALUATION_RULE}}</template><template v-else> 批次单价未返回（可能是无价格权限或未录入），不显示金额。</template></p>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>类型</th><th>物料</th><th>单位</th><th class="num">期初</th><th class="num">期间入库</th><th class="num">累计出库（不分期）</th><th class="num">期末</th><template v-if="priceVisible"><th class="num" :title="VALUATION_RULE">单价（来源）</th><th class="num" :title="VALUATION_RULE">期末金额</th></template></tr></thead><tbody>
<tr v-for="r in sumPager.rows.value" :key="r.kind+r.name+r.unit"><td>{{r.kind}}</td><td>{{r.name}}</td><td>{{r.unit}}</td><td class="num muted">无期初快照</td><td class="num">{{fmtQty(r.inPeriod)}}<small v-if="r.inMissing">{{r.inMissing}} 批数量未返回</small></td><td class="num">{{r.outTotal===undefined?'未返回':fmtQty(r.outTotal)}}</td><td class="num">{{!closingAvailable?'无期末快照':r.closing===undefined?'未返回':fmtQty(r.closing)}}</td><template v-if="priceVisible"><td class="num" :title="valOf(r)?.basis||(r.kind==='原料'?'无单价（批次未记录入库单价）':'无单价（成本分析里没有该产品'+(costErr?'，'+costErr:'')+'）')">{{!valOf(r)||valOf(r)!.unitPrice===null?'无单价':fmtQty(valOf(r)!.unitPrice)}}<small v-if="valOf(r)?.partial">部分批次无单价</small></td><td class="num">{{!closingAvailable?'无期末快照':valOf(r)?.amount==null?'—':fmtQty(valOf(r)!.amount)}}</td></template></tr>
<tr v-if="!summary.length"><td :colspan="priceVisible?9:7" class="empty">{{busy?'正在读取':'没有批次记录。'}}</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{sumPager.page.value}} / {{sumPager.pages.value}} 页</span><button class="ui-button" :disabled="sumPager.page.value<=1" @click="sumPager.page.value--">上一页</button><button class="ui-button" :disabled="sumPager.page.value>=sumPager.pages.value" @click="sumPager.page.value++">下一页</button></div>
</template>
<template v-else-if="prefs.tab==='aging'">
<div class="section-head"><h2>库龄与呆滞 · {{aging.length}} 批</h2><small>按批次入库日与保质期分档；临期阈值 = 保质期的 20%（至少 3 天）；入库 90 天以上且未出库为“长期未动”</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>类型</th><th>物料</th><th>批次</th><th class="num">剩余</th><th>单位</th><th>入库日</th><th class="num">库龄（天）</th><th>分档</th><th>说明</th></tr></thead><tbody>
<tr v-for="x in agePager.rows.value" :key="x.b.id"><td>{{x.b.kind}}</td><td>{{x.b.name}}</td><td>{{x.b.batchNo}}</td><td class="num">{{x.b.remaining===undefined?'未返回':fmtQty(x.b.remaining)}}</td><td>{{x.b.unit}}</td><td :title="x.b.inDateField">{{x.b.inDate||'未返回'}}</td><td class="num">{{x.age??'—'}}</td><td><span class="supply-badge" :data-tone="x.tier==='已过期'?'negative':x.tier==='临期'||x.tier==='长期未动'?'zero':x.tier==='正常'?'ok':'muted'">{{x.tier}}</span></td><td class="muted">{{x.note}}</td></tr>
<tr v-if="!aging.length"><td colspan="9" class="empty">{{busy?'正在读取':'没有在库批次。'}}</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{agePager.page.value}} / {{agePager.pages.value}} 页</span><button class="ui-button" :disabled="agePager.page.value<=1" @click="agePager.page.value--">上一页</button><button class="ui-button" :disabled="agePager.page.value>=agePager.pages.value" @click="agePager.page.value++">下一页</button></div>
</template>
<template v-else>
<div class="section-head"><h2>补货建议 · {{replenish.length}} 种原料</h2><small>建议补货 = 库存线 −（在库可用 + 采购在途 + 在制 − 待领 − 待发 − 未清需求）；公式固定，不可改</small></div>
<p class="supply-note">味美物料资料没有安全库存（库存线）字段，所以“建议补货”显示“无法计算”；在制、未清需求接口未返回，不按 0 计入。下面逐项列出已有的加项和减项，供人工判断。食品原料建议只补到安全库存，避免补了用不完。</p>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>物料</th><th>单位</th><th class="num">库存线</th><th class="num">在库可用</th><th class="num">+ 采购在途</th><th class="num">+ 在制</th><th class="num">− 待领</th><th class="num">− 待发</th><th class="num">− 未清需求</th><th class="num">建议补货</th></tr></thead><tbody>
<tr v-for="r in repPager.rows.value" :key="r.name+r.unit"><td>{{r.name}}</td><td>{{r.unit}}</td><td class="num">{{r.line===undefined?'未设置':fmtQty(r.line)}}</td><td class="num">{{fmtQty(r.onHand)}}</td><td class="num">{{fmtQty(r.transit)}}</td><td class="num muted">未返回</td><td class="num">{{fmtQty(r.toIssue)}}</td><td class="num muted">不适用</td><td class="num muted">未返回</td><td class="num">{{r.suggest===undefined?'无法计算：未设库存线':fmtQty(r.suggest)}} <button class="op" title="查看补货建议的加项、减项和公式" @click="formula($event,r)">口径</button></td></tr>
<tr v-if="!replenish.length"><td colspan="10" class="empty">{{busy?'正在读取':'没有原料资料。'}}</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{repPager.page.value}} / {{repPager.pages.value}} 页</span><button class="ui-button" :disabled="repPager.page.value<=1" @click="repPager.page.value--">上一页</button><button class="ui-button" :disabled="repPager.page.value>=repPager.pages.value" @click="repPager.page.value++">下一页</button></div>
</template>
</section>
</div></template>
