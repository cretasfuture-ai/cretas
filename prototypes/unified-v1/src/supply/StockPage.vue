<script setup lang="ts">
// 六膳门 · 库存查询（规格 24）＋ 未返回余额与补充（32）＋ 进销存汇总（31 N6）＋ 补充记录。
import {computed,ref,watch} from 'vue'
import {registerSupplyAi,selectStock} from './ai-supply'
import UModal from '@nuxt/ui/components/Modal.vue'
import UButton from '@nuxt/ui/components/Button.vue'
import type {Row} from '../frame/types'
import {T,activeFor,activeRecords,post,readSupply,KIND_LABEL,supplySession} from './supply-store'
import type {SupplyState} from './supply-store'
import {fmtQty,toNum,stockState,STATE_LABEL,negativeDirections,unreturnedCombos,periodSummary,flatten,localDate,CATEGORIES,downloadCsv,fmtTime} from './supply-facts'
import type {StockState,SummaryRow} from './supply-facts'
import {usePager,usePrefs} from './pager'
import {openSupplement} from './supplement'
import {S,navigate} from '../frame/store'
import TracePanel from './TracePanel.vue'
// 第四批② BL20：产品行「追溯」打开产品追溯（C28 追溯视图的反向入口）
const traceCode=ref('')
const props=defineProps<{products:Row[];initialTab?:string}>()
type Tab='stock'|'missing'|'summary'|'records'
const prefs=usePrefs('unified-lsm-supply-stock-v2',{tab:(props.initialTab||'stock') as string,q:'',wh:'',state:'',onlyDocs:false,from:'',to:''})
if(props.initialTab)prefs.value.tab=props.initialTab
const tab=computed({get:()=>prefs.value.tab as Tab,set:v=>{prefs.value.tab=v}})
const inv=computed(()=>T.inv!)
const warehouses=computed(()=>inv.value.warehouses.map(w=>({id:String(w.id),name:String(w.name)})))
const match=(s:string)=>!prefs.value.q||s.toLowerCase().includes(prefs.value.q.toLowerCase())
const toRecords=()=>{prefs.value.tab='records'}
// AI：当前库存筛选作为悬浮窗对象（只提供系统计算的依据）
registerSupplyAi()
watch(()=>[prefs.value.tab,prefs.value.wh,prefs.value.state,prefs.value.q],()=>{if(prefs.value.tab==='stock')selectStock(prefs.value.wh,prefs.value.state,prefs.value.q)},{immediate:true})
// 深链：#/warehouse/inventory-query?product=<编码>&wh=<仓库ID>
if(S.query.get('product')||S.query.get('wh')){prefs.value.tab='stock';prefs.value.q=S.query.get('product')||'';prefs.value.wh=S.query.get('wh')||'';prefs.value.state=''}

// ---- 黑湖返回的库存行
const stockRows=computed(()=>inv.value.stock.map(s=>{const st=stockState(s.qtyInWarehouse),key=String(s.productId)+'@'+String(s.warehouseId);return {row:s,state:st,key,superseded:activeFor('stock',key)}}))
const counts=computed(()=>{const c:Record<StockState,number>={positive:0,zero:0,negative:0,invalid:0};for(const r of stockRows.value)c[r.state]++;return c})
const stockFiltered=computed(()=>stockRows.value.filter(r=>match(String(r.row.productCode)+String(r.row.productName))&&(!prefs.value.wh||String(r.row.warehouseId)===prefs.value.wh)&&(!prefs.value.state||r.state===prefs.value.state)))
const stockPager=usePager(stockFiltered)
const detail=ref<{row:Row;state:StockState;dirs:string[]}>()
function openDetail(r:{row:Row;state:StockState}){detail.value={row:r.row,state:r.state,dirs:r.state==='negative'?negativeDirections(r.row,inv.value):[]}}

// ---- 未返回余额（产品档案 × 仓库档案 − 快照返回）
const combos=computed(()=>unreturnedCombos(inv.value,props.products))
const missingFiltered=computed(()=>combos.value.filter(c=>match(c.productCode+c.productName)&&(!prefs.value.wh||c.warehouseId===prefs.value.wh)&&(!prefs.value.onlyDocs||c.usedInDocs)))
const missingPager=usePager(missingFiltered)
const docCombos=computed(()=>combos.value.filter(c=>c.usedInDocs).length)
const supplementedCount=computed(()=>activeRecords('stock').filter(r=>!inv.value.stock.some(s=>String(s.productId)+'@'+String(s.warehouseId)===r.key)).length)

// ---- 进销存汇总
const lines=computed(()=>flatten(inv.value))
const dates=computed(()=>{const d=lines.value.map(l=>localDate(l.bizAt)).filter(Boolean).sort();return {min:d[0]||'',max:d[d.length-1]||''}})
const snapshotDate=computed(()=>localDate(inv.value.materialAt))
const from=computed({get:()=>prefs.value.from||dates.value.min,set:v=>{prefs.value.from=v}})
const to=computed({get:()=>prefs.value.to||snapshotDate.value,set:v=>{prefs.value.to=v}})
const summary=computed(()=>periodSummary(inv.value,from.value,to.value,lines.value))
const summaryRows=computed(()=>summary.value.rows.filter(r=>match(r.productCode+r.productName)&&(!prefs.value.wh||r.warehouseName===warehouses.value.find(w=>w.id===prefs.value.wh)?.name)))
const summaryPager=usePager(summaryRows)
// BL 14 进销存成本金额（第二批⑥）：估值口径由 B 线提供（/__lsm/product-cost/valuation），C 只显示。
// 金额 = 数量 × 单价，仅单位相同才乘，不换算；缺价为空，不补 0；合计只算已估值部分。
interface Val{productCode:string;unit:string;unitPrice:number|null;basis:string;source:'blacklake'|'cretas'|'missing';at:string|null;reason:string|null}
const vals=ref<Map<string,Val>>(new Map()),valRule=ref(''),valErr=ref(''),valAsOf=ref('')
async function readValuation(){valErr.value='';try{const s=supplySession();const r=await fetch('/__lsm/product-cost/valuation?asOf='+encodeURIComponent(to.value),{headers:s?{'x-acceptance-session':s}:{}});const b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'估值读取失败');valRule.value=b.data.rule;valAsOf.value=b.data.asOf;const m=new Map<string,Val>();for(const v of b.data.rows as Val[])m.set(v.productCode,v);vals.value=m}catch(e){valErr.value=(e instanceof Error?e.message:'估值读取失败')+'；金额列暂不显示';vals.value=new Map()}}
watch(()=>[prefs.value.tab,to.value],()=>{if(prefs.value.tab==='summary')readValuation()},{immediate:true})
const SRC_LABEL:Record<string,string>={blacklake:'黑湖',cretas:'Cretas 补充',missing:'未估值'}
function valOf(r:SummaryRow):{price:number|null;src:string;note:string;tone:string}{const v=vals.value.get(r.productCode);if(!v)return {price:null,src:'未估值',note:'估值结果里没有这个编码',tone:'muted'};if(v.source==='missing'||v.unitPrice==null)return {price:null,src:'未估值',note:v.reason||'未估值',tone:'muted'};if(String(v.unit).trim()!==r.unit)return {price:null,src:SRC_LABEL[v.source],note:'估值单位 '+v.unit+' 与本行单位 '+r.unit+' 不同，不换算，金额为空',tone:'muted'};return {price:v.unitPrice,src:SRC_LABEL[v.source],note:v.basis,tone:v.source==='cretas'?'supply':''}}
const amt=(q:number|undefined,p:number|null)=>q===undefined||p===null?null:Math.round(q*p*100)/100
const valTotals=computed(()=>{let inA=0,outA=0,closeA=0,valued=0;for(const r of summaryRows.value){const v=valOf(r);if(v.price===null)continue;valued++;inA+=amt(r.inTotal,v.price)||0;outA+=amt(r.outTotal,v.price)||0;closeA+=amt(r.closing,v.price)||0}return {inA,outA,closeA,valued,all:summaryRows.value.length}})
const money=(v:number|null)=>v===null?'—':v.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})
const catLabel=(id:string)=>CATEGORIES.find(c=>c.id===id)?.label||id
const breakdown=(m:Record<string,number>)=>Object.entries(m).map(([k,v])=>catLabel(k)+' '+fmtQty(v)).join('；')||'无'
function openingOf(r:SummaryRow){const pid=r.key.split('@')[0],rest=r.key.split('@')[1],wid=rest.split('|')[0];return activeFor('opening',pid+'@'+wid+'|'+r.unit+'|'+from.value)}
function supplementOpening(r:SummaryRow){const [pid,rest]=r.key.split('@'),wid=rest.split('|')[0];openSupplement({kind:'opening',productId:pid,warehouseId:wid,title:r.productName+' · '+r.warehouseName+' · 期初 '+from.value,unit:r.unit,asOf:from.value,sourceValue:'无期初快照'},toRecords)}
function exportSummary(){downloadCsv('六膳门进销存汇总-'+from.value+'至'+to.value+'.csv',['产品编码','产品','仓库','单位','期初','期初来源','入库合计','入库构成','出库合计','出库构成','期末','期末说明','单价','单价来源','单价依据','入库金额','出库金额','期末金额','快照时点'],summaryRows.value.map(r=>{const o=openingOf(r);return [r.productCode,r.productName,r.warehouseName,r.unit,o?o.value.qty:'无期初快照',o?'Cretas 补充':'—',r.inTotal,breakdown(r.inByCat),r.outTotal,breakdown(r.outByCat),r.closing??'',r.closingNote||'黑湖快照',valOf(r).price??'',valOf(r).src,valOf(r).note,amt(r.inTotal,valOf(r).price)??'',amt(r.outTotal,valOf(r).price)??'',amt(r.closing,valOf(r).price)??'',fmtTime(inv.value.materialAt)]}))}

// ---- 补充记录
const records=computed(()=>[...(T.supply?.records||[])].reverse())
const recordsPager=usePager(records)
const revoking=ref<string>(),revokeReason=ref(''),revokeErr=ref('')
async function revoke(){if(!T.supply||!revoking.value)return;revokeErr.value='';if(!revokeReason.value.trim()){revokeErr.value='撤销必须填写原因';return}try{await post<SupplyState>('/revoke',{id:revoking.value,reason:revokeReason.value.trim(),expectedVersion:T.supply.version});await readSupply();T.notice='已撤销补充并留痕（已重新读取保存区）。';revoking.value=undefined;revokeReason.value=''}catch(e){revokeErr.value=e instanceof Error?e.message:'撤销失败'}}
const recordTitle=(r:{kind:string;productId?:string;warehouseId?:string;lineId?:string;key:string})=>{if(r.lineId)return '明细行 '+r.lineId;const p=props.products.find(x=>String(x.id)===r.productId),w=warehouses.value.find(x=>x.id===r.warehouseId);return (p?String(p.productName):'产品 '+r.productId)+' @ '+(w?w.name:'仓库 '+r.warehouseId)}
const recordValue=(v:Record<string,unknown>)=>v.qty!==undefined?fmtQty(v.qty)+' '+v.unit+' · 时点 '+v.asOf:v.batchNo!==undefined?'批次 '+v.batchNo:fmtQty(v.amount)+' '+v.currency
const recordState=(r:{kind:string;key:string;revokedAt?:string;replacedAt?:string})=>r.revokedAt?'已撤销':r.replacedAt?'已被新补充替换':r.kind==='stock'&&inv.value.stock.some(s=>String(s.productId)+'@'+String(s.warehouseId)===r.key)?'已被来源值取代':'生效中'

function exportStock(){downloadCsv('六膳门库存查询.csv',['产品编码','产品','仓库','在库','单位','标记','来源','快照时点'],stockFiltered.value.map(r=>[r.row.productCode,r.row.productName,r.row.warehouseName,toNum(r.row.qtyInWarehouse)??r.row.qtyInWarehouse,r.row.unitName,STATE_LABEL[r.state],'黑湖',fmtTime(inv.value.materialAt)]))}
defineExpose({exportCurrent:()=>tab.value==='summary'?exportSummary():exportStock(),showRecords:toRecords})
const canSupply=computed(()=>Boolean(T.supply))
</script>
<template><div>
<div class="cs-tabs lsm-tabs supply-tabs" role="tablist" aria-label="库存视图">
<button role="tab" :aria-selected="tab==='stock'" @click="tab='stock'">黑湖返回余额 <small>{{inv.stock.length}}</small></button>
<button role="tab" :aria-selected="tab==='missing'" @click="tab='missing'">未返回余额 <small>{{combos.length}}</small></button>
<button role="tab" :aria-selected="tab==='summary'" @click="tab='summary'">进销存汇总</button>
<button role="tab" :aria-selected="tab==='records'" @click="tab='records'">补充记录 <small>{{T.supply?.records.length??'—'}}</small></button>
</div>
<section class="card">
<form class="filters" @submit.prevent>
<input v-model="prefs.q" aria-label="搜索产品" placeholder="产品编码或名称">
<select v-if="tab!=='records'" v-model="prefs.wh" aria-label="筛选仓库"><option value="">全部仓库（{{warehouses.length}}）</option><option v-for="w in warehouses" :key="w.id" :value="w.id">{{w.name}}</option></select>
<select v-if="tab==='stock'" v-model="prefs.state" aria-label="筛选余额状态"><option value="">全部状态</option><option value="positive">有余额 {{counts.positive}}</option><option value="zero">零库存 {{counts.zero}}</option><option value="negative">负库存 {{counts.negative}}</option><option v-if="counts.invalid" value="invalid">格式无效 {{counts.invalid}}</option></select>
<label v-if="tab==='missing'" class="check"><input v-model="prefs.onlyDocs" type="checkbox"> 只看单据里出现过的组合（{{docCombos}}）</label>
<template v-if="tab==='summary'"><label>期间 <input v-model="from" type="date" aria-label="期间开始"></label><label>至 <input v-model="to" type="date" aria-label="期间结束"></label></template>
<button type="button" class="ui-button" @click="prefs.q='';prefs.wh='';prefs.state=''">重置</button>
</form>

<template v-if="tab==='stock'">
<div class="section-head"><h2>黑湖返回余额 · {{stockFiltered.length}} 条</h2><small>零库存 {{counts.zero}} · 负库存 {{counts.negative}}；在库不等于可用，未返回占用、待检、冻结量</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>产品编码</th><th>产品</th><th>仓库</th><th class="num">在库</th><th>单位</th><th>标记</th><th>来源</th><th>操作</th></tr></thead><tbody>
<tr v-for="r in stockPager.rows.value" :key="r.key" :data-state="r.state"><td>{{r.row.productCode||'未编码'}}</td><td>{{r.row.productName}}</td><td>{{r.row.warehouseName}}</td><td class="num">{{fmtQty(r.row.qtyInWarehouse)}}</td><td>{{r.row.unitName||'（无单位）'}}</td><td><span v-if="r.state!=='positive'" class="supply-badge" :data-tone="r.state">{{STATE_LABEL[r.state]}}</span></td><td><span class="src-tag">黑湖</span><span v-if="r.superseded" class="src-tag" data-tone="muted" title="曾有 Cretas 补充值，黑湖已返回，以黑湖为准">补充已被取代</span></td><td><button class="op" @click="openDetail(r)">{{r.state==='negative'?'核对':'详情'}}</button><button v-if="r.row.productCode" class="op" title="按产品和时间范围倒查工单、领料与销售去向" @click="traceCode=String(r.row.productCode)">追溯</button><button v-if="r.row.productCode" class="op" title="看这个产品的 Cretas 入库批次、到期日和先到期先出建议" @click="navigate('/lsm/inbound-batches',{product:String(r.row.productCode)})">按批次看</button></td></tr>
<tr v-if="!stockPager.rows.value.length"><td colspan="8" class="empty">当前筛选没有黑湖返回的余额记录，不以模拟数据补齐。</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{stockPager.page.value}} / {{stockPager.pages.value}} 页</span><button class="ui-button" :disabled="stockPager.page.value<=1" @click="stockPager.page.value--">上一页</button><button class="ui-button" :disabled="stockPager.page.value>=stockPager.pages.value" @click="stockPager.page.value++">下一页</button></div>
</template>

<template v-else-if="tab==='missing'">
<div class="section-head"><h2>未返回余额 · {{missingFiltered.length}} 个组合</h2><small>黑湖产品档案 {{products.length}} × 仓库档案 {{warehouses.length}}，快照返回 {{inv.stock.length}} 条；已补充 {{supplementedCount}} 个</small></div>
<p class="supply-note">“未返回”不等于 0：可能是该仓从未存放，也可能是接口没有返回。责任人：黑湖接口对接人确认是否补采；现场确认过的数量可以在这里补充（只存本机原型，不回写黑湖）。</p>
<p v-if="T.supplyError" class="notice">{{T.supplyError}}</p>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>产品编码</th><th>产品</th><th>仓库</th><th>单据中出现</th><th class="num">在库</th><th>来源</th><th>操作</th></tr></thead><tbody>
<tr v-for="c in missingPager.rows.value" :key="c.productId+'@'+c.warehouseId"><td>{{c.productCode||'未编码'}}</td><td>{{c.productName}}</td><td>{{c.warehouseName}}</td><td>{{c.usedInDocs?'是':'否'}}</td>
<template v-if="activeFor('stock',c.productId+'@'+c.warehouseId)"><td class="num">{{fmtQty(activeFor('stock',c.productId+'@'+c.warehouseId)!.value.qty)}} {{activeFor('stock',c.productId+'@'+c.warehouseId)!.value.unit}}</td><td><span class="src-tag" data-tone="supply" :title="'时点 '+activeFor('stock',c.productId+'@'+c.warehouseId)!.value.asOf">Cretas 补充</span></td></template>
<template v-else><td class="num muted">黑湖未返回</td><td>—</td></template>
<td><button class="op" :disabled="!canSupply" @click="openSupplement({kind:'stock',productId:c.productId,warehouseId:c.warehouseId,title:c.productName+' · '+c.warehouseName,unit:c.unit},toRecords)">补充</button></td></tr>
<tr v-if="!missingPager.rows.value.length"><td colspan="7" class="empty">当前筛选没有未返回组合。</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{missingPager.page.value}} / {{missingPager.pages.value}} 页</span><button class="ui-button" :disabled="missingPager.page.value<=1" @click="missingPager.page.value--">上一页</button><button class="ui-button" :disabled="missingPager.page.value>=missingPager.pages.value" @click="missingPager.page.value++">下一页</button></div>
</template>

<template v-else-if="tab==='summary'">
<div class="section-head"><h2>进销存汇总 · {{from}} 至 {{to}} · {{summaryRows.length}} 行</h2><small>入/出取期间内黑湖出入库单据；期末只在期末日等于快照日（{{snapshotDate}}）时取快照在库</small></div>
<p v-if="valRule" class="supply-note">成本金额口径（成本分析提供，估值日 {{valAsOf}}）：{{valRule}}</p><p v-if="valErr" class="notice">{{valErr}}</p>
<p class="supply-note">期初没有历史快照，显示“无期初快照”；系统不会用“期末 − 入 + 出”倒推。现场确认过的期初可点“补期初”，结果标“含 Cretas 补充”。不同单位分行，不合计。</p>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>产品</th><th>仓库</th><th>单位</th><th class="num">期初</th><th class="num">入库</th><th class="num">出库</th><th class="num">期末</th><th class="num">单价（来源）</th><th class="num">入库金额</th><th class="num">出库金额</th><th class="num">期末金额</th><th>操作</th></tr></thead><tbody>
<tr v-if="summaryRows.length&&!valErr" class="total-row"><td colspan="7"><b>合计（仅已估值部分）</b> · 已估值 {{valTotals.valued}} / 共 {{valTotals.all}} 行；不同产品金额相加只为参考，数量不跨单位合计</td><td></td><td class="num">{{money(valTotals.inA)}}</td><td class="num">{{money(valTotals.outA)}}</td><td class="num">{{money(valTotals.closeA)}}</td><td></td></tr>
<tr v-for="r in summaryPager.rows.value" :key="r.key"><td>{{r.productName}}<small>{{r.productCode}}</small></td><td>{{r.warehouseName}}</td><td>{{r.unit}}</td>
<td class="num"><template v-if="openingOf(r)">{{fmtQty(openingOf(r)!.value.qty)}} <span class="src-tag" data-tone="supply">含 Cretas 补充</span></template><span v-else class="muted">无期初快照</span></td>
<td class="num" :title="breakdown(r.inByCat)">{{fmtQty(r.inTotal)}}</td><td class="num" :title="breakdown(r.outByCat)">{{fmtQty(r.outTotal)}}</td>
<td class="num"><template v-if="r.closing!==undefined">{{fmtQty(r.closing)}} <span class="src-tag">黑湖快照</span></template><span v-else class="muted">{{r.closingNote}}</span></td>
<td class="num" :title="valOf(r).note"><template v-if="valOf(r).price!==null">{{valOf(r).price!.toLocaleString('zh-CN',{maximumFractionDigits:4})}} </template><span class="src-tag" :data-tone="valOf(r).tone">{{valOf(r).src}}</span></td><td class="num">{{money(amt(r.inTotal,valOf(r).price))}}</td><td class="num">{{money(amt(r.outTotal,valOf(r).price))}}</td><td class="num">{{r.closing===undefined?'—':money(amt(r.closing,valOf(r).price))}}</td>
<td><button class="op" :disabled="!canSupply" @click="supplementOpening(r)">补期初</button></td></tr>
<tr v-if="!summaryPager.rows.value.length"><td colspan="12" class="empty">期间内没有出入库单据。</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{summaryPager.page.value}} / {{summaryPager.pages.value}} 页</span><button class="ui-button" :disabled="summaryPager.page.value<=1" @click="summaryPager.page.value--">上一页</button><button class="ui-button" :disabled="summaryPager.page.value>=summaryPager.pages.value" @click="summaryPager.page.value++">下一页</button></div>
</template>

<template v-else>
<div class="section-head"><h2>补充记录 · {{records.length}} 条</h2><small>谁、何时、补了什么、原因；撤销同样留痕</small></div>
<p v-if="T.supplyError" class="notice">{{T.supplyError}}</p>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>时间</th><th>类型</th><th>对象</th><th>补充值</th><th>原因</th><th>状态</th><th>操作</th></tr></thead><tbody>
<tr v-for="r in recordsPager.rows.value" :key="r.id"><td>{{fmtTime(r.at)}}</td><td>{{KIND_LABEL[r.kind]}}</td><td>{{recordTitle(r)}}</td><td>{{recordValue(r.value)}}</td><td>{{r.reason}}<small v-if="r.revokeReason">撤销：{{r.revokeReason}}</small></td><td>{{recordState(r)}}</td><td><button v-if="!r.revokedAt&&!r.replacedAt" class="op" @click="revoking=r.id;revokeReason='';revokeErr=''">撤销</button></td></tr>
<tr v-if="!records.length"><td colspan="7" class="empty">还没有补充记录。</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{recordsPager.page.value}} / {{recordsPager.pages.value}} 页</span><button class="ui-button" :disabled="recordsPager.page.value<=1" @click="recordsPager.page.value--">上一页</button><button class="ui-button" :disabled="recordsPager.page.value>=recordsPager.pages.value" @click="recordsPager.page.value++">下一页</button></div>
</template>
</section>
<TracePanel v-if="traceCode" :product-code="traceCode" @close="traceCode=''"/>

<UModal :open="Boolean(detail)" :title="String(detail?.row.productName||'库存详情')" @update:open="v=>{if(!v)detail=undefined}"><template #body><div v-if="detail" class="operation-detail">
<p>六膳门 · 黑湖只读来源 · 快照 {{fmtTime(inv.materialAt)}}</p>
<dl><dt>产品</dt><dd>{{detail.row.productName}} · {{detail.row.productCode||'未编码'}}</dd><dt>仓库</dt><dd>{{detail.row.warehouseName}} · {{detail.row.warehouseCode}}</dd><dt>在库</dt><dd>{{fmtQty(detail.row.qtyInWarehouse)}} {{detail.row.unitName}}（{{STATE_LABEL[detail.state]}}）</dd><dt>可用 / 占用 / 待检 / 冻结</dt><dd>黑湖未返回，不推算</dd></dl>
<template v-if="detail.dirs.length"><h3>负库存核对方向（规则给出，不判缺料）</h3><ol><li v-for="d in detail.dirs" :key="d">{{d}}</li></ol><p>找谁：线边仓管理员核对领料与调拨签收；仍不一致时请黑湖对接人查历史流水。</p></template>
</div></template></UModal>
<UModal :open="Boolean(revoking)" title="撤销补充" @update:open="v=>{if(!v)revoking=undefined}"><template #body><div class="supply-form"><p class="supply-note">撤销后该补充不再参与计算，记录保留并标“已撤销”。</p><label>撤销原因<textarea v-model="revokeReason" rows="2" maxlength="200" aria-label="撤销原因"/></label><p v-if="revokeErr" role="alert" class="error">{{revokeErr}}</p><div class="supply-actions"><UButton color="neutral" variant="outline" size="sm" @click="revoking=undefined">取消</UButton><UButton color="error" size="sm" @click="revoke">确认撤销</UButton></div></div></template></UModal>
</div></template>
