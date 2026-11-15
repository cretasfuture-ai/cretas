<script setup lang="ts">
// 味美 · 库存查询精简视图（规格第 18 节；R6 只读）。一行视图切换（按物料汇总 / 批次明细），
// 标读取时点；在库、可用分列，零库存与负库存分开标记；不同单位不合计；成本估值不显示（等价格权限）。
import {computed,onMounted,ref} from 'vue'
import type {Row} from '../frame/types'
import {yget,num,dateOnly,YD_SESSION_LABEL,withSession} from './ydwm'
import {fmtQty,add,downloadCsv} from './supply-facts'
import {usePager,usePrefs} from './pager'
const mb=ref<Row[]>([]),fg=ref<Row[]>([]),wh=ref<Row[]>([]),busy=ref(false),err=ref(''),readAt=ref('')
const prefs=usePrefs('unified-ydwm-inventory-v1',{view:'sum',q:'',kind:'',state:'',w:''})
async function read(){busy.value=true;err.value='';try{const [a,b,c]=await Promise.all([yget<Row[]>('material-batches?page=1&size=500'),yget<Row[]>('sales/finished-goods?page=1&size=500'),yget<Row[]>('factory/warehouses').catch(()=>[] as Row[])]);mb.value=a;fg.value=b;wh.value=Array.isArray(c)?c:[];readAt.value=new Date().toLocaleString('zh-CN',{hour12:false})}catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
onMounted(read)
const whName=(id:unknown)=>{const w=wh.value.find(x=>String(x.id)===String(id));return w?String(w.name??w.warehouseName??id):id==null?'未记录':'仓库 '+String(id).slice(0,8)}
interface B{kind:'原料'|'成品';name:string;batch:string;warehouse:string;whId:string;unit:string;onHand:number|undefined;available:number|undefined;inDate:string;status:string}
const batches=computed<B[]>(()=>[
  ...mb.value.map(b=>({kind:'原料' as const,name:String(b.materialName??'未提供'),batch:String(b.batchNumber??'—'),warehouse:whName(b.warehouseId),whId:String(b.warehouseId??''),unit:String(b.unit??'（无单位）'),onHand:num(b.remainingQuantity??b.currentQuantity),available:num(b.availableQuantity),inDate:dateOnly(b.createdAt),status:String(b.qualityStatus??b.status??'')})),
  ...fg.value.map(g=>{const p=num(g.producedQuantity),s=num(g.shippedQuantity);return {kind:'成品' as const,name:String(g.productName??'未提供'),batch:String(g.batchNumber??'—'),warehouse:String(g.warehouseName??whName(g.warehouseId)),whId:String(g.warehouseId??''),unit:String(g.unit??'（无单位）'),onHand:p===undefined||s===undefined?undefined:add(p,-s),available:num(g.availableQuantity),inDate:dateOnly(g.createdAt),status:''}})
])
// 同名不同 ID 的仓库不合并（数据里存在重名仓库），显示时加 ID 尾号区分
const dupNames=computed(()=>{const m=new Map<string,Set<string>>();for(const x of batches.value){const s=m.get(x.warehouse)||new Set();s.add(x.whId);m.set(x.warehouse,s)}return new Set([...m.entries()].filter(([,s])=>s.size>1).map(([n])=>n))})
const whLabel=(name:string,id:string)=>dupNames.value.has(name)?name+' · '+(id.slice(-4)||'无ID'):name
const stateOf=(v:number|undefined)=>v===undefined?'未返回':v<0?'负库存':v===0?'零库存':'有余额'
const base=computed(()=>batches.value.filter(b=>(!prefs.value.kind||b.kind===prefs.value.kind)&&(!prefs.value.w||b.whId===prefs.value.w)&&(!prefs.value.q||(b.name+b.batch).toLowerCase().includes(prefs.value.q.toLowerCase()))))
const sums=computed(()=>{const m=new Map<string,{kind:string;name:string;warehouse:string;unit:string;onHand:number|undefined;available:number|undefined;n:number}>();for(const b of base.value){const k=b.kind+'|'+b.name+'|'+b.whId+'|'+b.unit;const r=m.get(k)||{kind:b.kind,name:b.name,warehouse:whLabel(b.warehouse,b.whId),unit:b.unit,onHand:0,available:0,n:0};r.n++;r.onHand=b.onHand===undefined||r.onHand===undefined?undefined:add(r.onHand,b.onHand);r.available=b.available===undefined||r.available===undefined?undefined:add(r.available,b.available);m.set(k,r)}return [...m.values()].filter(r=>!prefs.value.state||stateOf(r.onHand)===prefs.value.state).sort((a,b)=>a.kind.localeCompare(b.kind)||a.name.localeCompare(b.name,'zh'))})
const rows=computed(()=>base.value.filter(b=>!prefs.value.state||stateOf(b.onHand)===prefs.value.state))
const sumPager=usePager(sums),batchPager=usePager(rows)
const whs=computed(()=>[...new Map(batches.value.map(b=>[b.whId,whLabel(b.warehouse,b.whId)])).entries()])
const tone=(s:string)=>s==='负库存'?'negative':s==='零库存'?'zero':s==='未返回'?'muted':''
function exportCsv(){downloadCsv('味美库存查询-'+readAt.value.replace(/[/:\s]/g,'')+'.csv',['类型','物料','仓库','在库','可用','单位','批次数','标记','读取时点'],sums.value.map(r=>[r.kind,r.name,r.warehouse,r.onHand??'未返回',r.available??'未返回',r.unit,r.n,stateOf(r.onHand),readAt.value]))}
defineExpose({exportCurrent:exportCsv})
</script>
<template><div>
<p class="supply-note">精简视图（只读）· 味美工作区 · {{YD_SESSION_LABEL()}} · 读取于 {{readAt||'—'}}。在库与可用分列，可用不等于质量放行；不同单位不合计；盘点、调整、移位在原页面或“库存调整单”完成。</p>
<p v-if="err" role="alert" class="error">{{err}}</p>
<section class="card">
<form class="filters" @submit.prevent>
<input v-model="prefs.q" aria-label="搜索物料或批次" placeholder="物料、批次">
<select v-model="prefs.kind" aria-label="物料类型"><option value="">原料与成品</option><option value="原料">原料</option><option value="成品">成品</option></select>
<select v-model="prefs.w" aria-label="仓库"><option value="">全部仓库</option><option v-for="[id,n] in whs" :key="id" :value="id">{{n}}</option></select>
<select v-model="prefs.state" aria-label="余额状态"><option value="">全部状态</option><option v-for="s in ['有余额','零库存','负库存','未返回']" :key="s" :value="s">{{s}}</option></select>
<button type="button" class="ui-button" @click="prefs.q='';prefs.kind='';prefs.state='';prefs.w=''">重置</button><button type="button" class="ui-button" :disabled="busy" @click="read">{{busy?'正在读取…':'刷新'}}</button>
<span class="seg" role="group" aria-label="视图"><button type="button" :aria-pressed="prefs.view==='sum'" @click="prefs.view='sum'">按物料汇总</button><button type="button" :aria-pressed="prefs.view==='batch'" @click="prefs.view='batch'">批次明细</button></span>
</form>
<template v-if="prefs.view==='sum'">
<div class="section-head"><h2>按物料汇总 · {{sums.length}} 行</h2><small>同物料同仓库同单位才合计</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>类型</th><th>物料</th><th>仓库</th><th class="num">在库</th><th class="num">可用</th><th>单位</th><th class="num">批次数</th><th>标记</th></tr></thead><tbody>
<tr v-for="r in sumPager.rows.value" :key="r.kind+r.name+r.warehouse+r.unit"><td>{{r.kind}}</td><td>{{r.name}}</td><td>{{r.warehouse}}</td><td class="num">{{r.onHand===undefined?'未返回':fmtQty(r.onHand)}}</td><td class="num">{{r.available===undefined?'未返回':fmtQty(r.available)}}</td><td>{{r.unit}}</td><td class="num">{{r.n}}</td><td><span v-if="stateOf(r.onHand)!=='有余额'" class="supply-badge" :data-tone="tone(stateOf(r.onHand))">{{stateOf(r.onHand)}}</span></td></tr>
<tr v-if="!sums.length"><td colspan="8" class="empty">{{busy?'正在读取':'当前筛选没有库存记录。'}}</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{sumPager.page.value}} / {{sumPager.pages.value}} 页</span><button class="ui-button" :disabled="sumPager.page.value<=1" @click="sumPager.page.value--">上一页</button><button class="ui-button" :disabled="sumPager.page.value>=sumPager.pages.value" @click="sumPager.page.value++">下一页</button></div>
</template>
<template v-else>
<div class="section-head"><h2>批次明细 · {{rows.length}} 批</h2><small>入库日按记录创建时间（味美批次未返回入库日字段）</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th>类型</th><th>物料</th><th>批次</th><th>仓库</th><th class="num">在库</th><th class="num">可用</th><th>单位</th><th>入库日</th><th>质量</th><th>标记</th></tr></thead><tbody>
<tr v-for="(b,i) in batchPager.rows.value" :key="b.batch+'-'+i"><td>{{b.kind}}</td><td>{{b.name}}</td><td>{{b.batch}}</td><td>{{whLabel(b.warehouse,b.whId)}}</td><td class="num">{{b.onHand===undefined?'未返回':fmtQty(b.onHand)}}</td><td class="num">{{b.available===undefined?'未返回':fmtQty(b.available)}}</td><td>{{b.unit}}</td><td>{{b.inDate||'未返回'}}</td><td>{{b.status==='PASSED'?'已放行':b.status||'—'}}</td><td><span v-if="stateOf(b.onHand)!=='有余额'" class="supply-badge" :data-tone="tone(stateOf(b.onHand))">{{stateOf(b.onHand)}}</span></td></tr>
<tr v-if="!rows.length"><td colspan="10" class="empty">{{busy?'正在读取':'当前筛选没有批次。'}}</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{batchPager.page.value}} / {{batchPager.pages.value}} 页</span><button class="ui-button" :disabled="batchPager.page.value<=1" @click="batchPager.page.value--">上一页</button><button class="ui-button" :disabled="batchPager.page.value>=batchPager.pages.value" @click="batchPager.page.value++">下一页</button></div>
</template>
</section>
</div></template>
