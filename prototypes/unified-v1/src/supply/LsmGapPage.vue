<script setup lang="ts">
// 第八批（R37 / R41）：六膳门「尚未迁入」占位迁入与缺口页。黑湖有数据的接上（只读，来源标「黑湖」）；
// 黑湖没有的写明「黑湖没有此类数据」，核心页（报损、源头个体、车辆字典）就地留 Cretas 补充入口。不造数据、不拿别的数据顶替。
import {computed,onMounted,ref,watch} from 'vue'
import type {Row} from '../frame/types'
import {T,readInventory,supplySession,supplyUrl} from './supply-store'
import {fmtQty,downloadCsv} from './supply-facts'
import {usePager,usePrefs} from './pager'
import {confirmReason} from '../frame/ai-protocol'
const props=defineProps<{page:string;products?:Row[]}>()
interface Col{key:string;label:string;get:(r:Row)=>unknown;num?:boolean;int?:boolean}
interface Sec{id:string;label:string;cols:Col[];rows:()=>Row[];empty:string}
interface Kind{label:string;fields:[string,string,number,string?][]}
interface Rec{id:string;kind:string;data:Record<string,string>;createdAt:string;createdBy:string;revokedAt?:string;revokeReason?:string}
const X=ref<{resources:Record<string,Row[]|null>;revision:string}>(),G=ref<{records:Rec[];kinds:Record<string,Kind>}>(),err=ref(''),msg=ref(''),ferr=ref<Record<string,string>>({})
const H=(w=false):Record<string,string>=>({...(supplySession()?{'x-acceptance-session':supplySession()}:{}),...(w?{'Content-Type':'application/json','x-prototype-user-action':'local-edit'}:{})})
async function load(){err.value='';try{if(!T.inv)await readInventory();const a=await fetch('/__lsm/supply/extras').then(r=>r.json());if(a.success)X.value=a.data;const g=await fetch(supplyUrl('/gap'),{headers:H()}).then(r=>r.json());if(!g.success)throw Error(g.message);G.value=g.data}catch(e){err.value=e instanceof Error?e.message:'读取失败'}}
onMounted(load)
const res=(k:string)=>X.value?.resources[k]||[]
const v=(x:unknown)=>x===null||x===undefined||x===''?'未返回':String(x)
const d=(x:unknown)=>x?String(x).slice(0,10):'未返回'
const docs=(types:string[])=>(T.inv?.orders||[]).filter(o=>types.includes(String(o.bizTypeDisplay))).sort((a,b)=>String(b.bizAt).localeCompare(String(a.bizAt)))
const uniq=(a:unknown[])=>[...new Set(a)]
const ln=(o:Row)=>((o.lines||[]) as Row[])
const docCols=():Col[]=>[{key:'no',label:'单号',get:o=>o.orderNo},{key:'t',label:'类型',get:o=>o.bizTypeDisplay},{key:'d',label:'业务时间',get:o=>d(o.bizAt)},{key:'s',label:'状态',get:o=>o.orderStatusDisplay},{key:'n',label:'行数',get:o=>ln(o).length,num:true,int:true},{key:'p',label:'品项',get:o=>uniq(ln(o).map(l=>String(l.productName))).slice(0,3).join('、')+(ln(o).length>3?' 等':'')},{key:'a',label:'关联单号',get:o=>uniq(ln(o).map(l=>l.associatedBizOrderCode).filter(Boolean)).join('、')||'无'}]
const recCols=(kind:string):Col[]=>[...(G.value?.kinds[kind]?.fields||[]).map(([k,label,,t]):Col=>({key:k,label,get:r=>(r.data as Record<string,string>)[k]||'',num:t==='num'})),{key:'src',label:'来源',get:()=>'Cretas 补充'}]
const recRows=(kind:string)=>(G.value?.records||[]).filter(r=>r.kind===kind&&!r.revokedAt) as unknown as Row[]
// 物料类型：黑湖产品档案的来源类型（originType）计数；黑湖没有物料分类字段，所以分类字典不造
// 0=自制、1=外购由黑湖采购订单的 productOriginTypeDesc 核实；其它代码黑湖没给名称，不猜
const ORIGIN:Record<string,string>={'0':'自制','1':'外购'}
const originRows=computed<Row[]>(()=>{const m=new Map<string,number>();for(const p of (props.products||[]))m.set(String(p.originType),(m.get(String(p.originType))||0)+1);return [...m].map(([k,n])=>({k,n}) as Row)})
// 采购均价趋势：按采购订单明细的采购单价，按产品＋月份加权平均；只含有单价和数量的行
const trendRows=computed<Row[]>(()=>{const m=new Map<string,{name:string;code:string;unit:string;month:string;sum:number;qty:number;n:number}>();for(const o of res('purchase_order')){const mon=String(o.purchaseTime||'').slice(0,7);for(const l of ((o.purchaseSubOrderVOS as Row[])||[])){const pr=Number(l.purchasePrice),q=Number(l.purchaseNum);if(!mon||!(pr>0)||!(q>0))continue;const k=l.productCode+'|'+mon,x=m.get(k)||{name:String(l.productName),code:String(l.productCode),unit:String(l.unitName),month:mon,sum:0,qty:0,n:0};x.sum+=pr*q;x.qty+=q;x.n++;m.set(k,x)}}return [...m.values()].sort((a,b)=>a.code.localeCompare(b.code)||a.month.localeCompare(b.month)).map(x=>({...x,avg:x.sum/x.qty}) as Row)})
const pendingReq=computed(()=>res('purchase_requirement').filter(r=>Number(r.approveStatus)!==1))
const lackReq=computed(()=>res('purchase_requirement').filter(r=>Number(r.lackAmount)>0))
const reqCols:Col[]=[{key:'c',label:'需求单',get:r=>r.code},{key:'p',label:'产品',get:r=>r.productName},{key:'q',label:'需求数量',get:r=>r.reqAmount,num:true},{key:'u',label:'单位',get:r=>v(r.productUnitName)},{key:'l',label:'缺口',get:r=>r.lackAmount,num:true},{key:'t',label:'计划到货',get:r=>d(r.arrivalPlanTime)},{key:'a',label:'审批',get:r=>Number(r.approveStatus)===1?'已审批':'待审批'}]
interface Conf{title:string;note:string;gap?:string;secs:Sec[];rec?:string}
const none=(label:string):Sec[]=>[{id:'n',label,cols:[],rows:()=>[],empty:'黑湖没有此类数据'}]
const CONF=computed<Record<string,Conf>>(()=>({
  transfers:{title:'调拨单',note:'黑湖调拨出库、调拨入库单据，只读；开单与审批仍在黑湖。',secs:[{id:'t',label:'调拨单据',cols:docCols(),rows:()=>docs(['调拨出库','调拨入库']),empty:'黑湖没有返回调拨单据'}]},
  shipments:{title:'出货记录',note:'黑湖销售出库单，只读；销售出库不等于销售订单，按关联单号对照。',secs:[{id:'s',label:'销售出库单',cols:docCols(),rows:()=>docs(['销售出库']),empty:'黑湖没有返回销售出库单'}]},
  returns:{title:'销售退货',note:'黑湖销售退货入库单，只读。黑湖没有单独的退货申请单据，退货原因、审批流不在这里。',secs:[{id:'r',label:'销售退货入库单',cols:docCols(),rows:()=>docs(['销售退货入库']),empty:'黑湖没有返回销售退货单'}]},
  unordered:{title:'无订单入库申请',note:'黑湖没有「申请」这一层数据，只有已审批的普通入库、其它入库单；下面是只读对照，不是申请。',gap:'黑湖没有无订单入库申请数据；申请与审批在黑湖办理，Cretas 不补写申请。',secs:[{id:'u',label:'已审批的普通 / 其它入库单（对照）',cols:docCols(),rows:()=>docs(['普通入库','其它入库']),empty:'黑湖没有返回此类入库单'}]},
  stocktake:{title:'盘点',note:'',gap:'黑湖没有盘点任务和盘点结果数据，盘点在黑湖办理；库存调整页另有说明。',secs:none('盘点')},
  mtypes:{title:'原料类型字典',note:'黑湖产品档案「来源类型」字段的计数，只读；黑湖没有单独维护的原料类型字典。',secs:[{id:'m',label:'来源类型分布',cols:[{key:'k',label:'来源类型',get:r=>ORIGIN[String(r.k)]||('类型 '+r.k+'（黑湖未给名称）')},{key:'c',label:'黑湖代码',get:r=>r.k},{key:'n',label:'产品数',get:r=>r.n,num:true,int:true}],rows:()=>originRows.value,empty:'黑湖没有返回产品档案'}]},
  msegments:{title:'物料分类字典',note:'',gap:'黑湖产品档案没有物料分类（大类 / 中类）字段，不用来源类型顶替。',secs:none('物料分类')},
  pricetrend:{title:'物料均价趋势',note:'由黑湖采购订单明细按「产品＋月份」加权平均采购单价算出，只含单价大于 0 且数量大于 0 的行（单价为 0 或空的行不参与平均，也不计入单据行）；黑湖未返回币种。不混算库存成本均价。',secs:[{id:'p',label:'采购均价（按月）',cols:[{key:'c',label:'编码',get:r=>r.code},{key:'n',label:'产品',get:r=>r.name},{key:'m',label:'月份',get:r=>r.month},{key:'a',label:'均价',get:r=>r.avg,num:true},{key:'q',label:'采购数量',get:r=>r.qty,num:true},{key:'u',label:'单位',get:r=>r.unit},{key:'o',label:'单据行',get:r=>r.n,num:true,int:true}],rows:()=>trendRows.value,empty:'黑湖采购订单没有可算的单价'}]},
  pending:{title:'待审批请购',note:'黑湖采购需求中审批状态不是「已审批」的，只读；审批在黑湖办理。',secs:[{id:'a',label:'待审批的采购需求',cols:reqCols,rows:()=>pendingReq.value,empty:'黑湖当前没有待审批的采购需求'}]},
  aipurchase:{title:'AI受控采购',note:'只列黑湖采购需求里「缺口大于 0」的项，只读。不自动下单，不推算安全库存；黑湖没有补货规则，不给建议数量。',secs:[{id:'l',label:'有缺口的采购需求',cols:reqCols,rows:()=>lackReq.value,empty:'黑湖采购需求当前没有缺口'}]},
  pricelists:{title:'价格表',note:'',gap:'黑湖没有供应商价目表数据（0 条）；客户价目表在「客户价目表」页。',secs:none('供应商价目表')},
  wastage:{title:'报损管理',note:'黑湖没有报损数据。这里是 Cretas 补充入口：记录报损事实，不扣黑湖库存、不回写黑湖；报损后的质量处置、财务报废审批不在本页。',rec:'wastage',secs:[{id:'w',label:'报损记录',cols:recCols('wastage'),rows:()=>recRows('wastage'),empty:'还没有补充的报损记录'}]},
  source:{title:'源头个体档案',note:'黑湖没有活体 / 胴体来源数据。这里是 Cretas 补充入口：记录个体编号、来源和关联入库批次，供追溯源头；不拉人员字段。',rec:'source',secs:[{id:'s',label:'源头个体',cols:recCols('source'),rows:()=>recRows('source'),empty:'还没有补充的源头个体'}]},
  vehicles:{title:'车辆字典',note:'黑湖没有车辆数据。这里是 Cretas 补充的车辆字典（主数据），车牌号不能重复；「司机」只能人手填写，不从黑湖拉取，也不关联人员档案。',rec:'vehicle',secs:[{id:'v',label:'车辆',cols:recCols('vehicle'),rows:()=>recRows('vehicle'),empty:'还没有补充的车辆'}]},
  payreq:{title:'销售付款申请',note:'',gap:'黑湖没有销售付款申请数据；财务模块在本原型中关闭，这里不提供申请入口，味美侧由编译页直接打开。',secs:none('销售付款申请')}
}))
const conf=computed(()=>CONF.value[props.page]||CONF.value.transfers)
const prefs=usePrefs('unified-lsm-gap-v1',{q:''})
const form=ref<Record<string,string>>({})
watch(()=>props.page,()=>{prefs.value.q='';form.value={};msg.value=''})
onMounted(()=>{prefs.value.q=''})
const cur=computed(()=>conf.value.secs[0])
const filtered=computed(()=>{const q=prefs.value.q.trim().toLowerCase();return cur.value.rows().filter(r=>!q||cur.value.cols.some(c=>String(c.get(r)??'').toLowerCase().includes(q)))})
const pg=usePager(filtered,20)
const own=computed(()=>!!conf.value.rec)
const cell=(c:Col,r:Row)=>{const x=c.get(r);return x===null||x===undefined||x===''?(own.value?'':'未返回'):c.int&&Number.isFinite(Number(x))?String(Math.round(Number(x))):c.num&&Number.isFinite(Number(x))?fmtQty(x):String(x)}
const kind=computed(()=>conf.value.rec?G.value?.kinds[conf.value.rec]:undefined)
async function save(){if(!conf.value.rec)return;err.value='';msg.value='';ferr.value={};const tk=new Date(),td=tk.getFullYear()+'-'+String(tk.getMonth()+1).padStart(2,'0')+'-'+String(tk.getDate()).padStart(2,'0');for(const f of (G.value?.kinds[conf.value.rec]?.fields||[]))if(f[3]==='date'&&form.value[f[0]]&&String(form.value[f[0]])>td){ferr.value={[f[0]]:f[1]+'晚于今天（'+td+'），不能保存'};return}try{const r=await fetch(supplyUrl('/gap-record'),{method:'POST',headers:H(true),body:JSON.stringify({...form.value,kind:conf.value.rec,by:'本机原型'})}).then(x=>x.json());if(!r.success){if(r.field)ferr.value={[r.field]:r.message};else throw Error(r.message);return}form.value={};msg.value='已保存（来源：Cretas 补充，不回写黑湖）';await load()}catch(e){err.value=e instanceof Error?e.message:'保存失败'}}
async function revoke(r:Rec){const reason=await confirmReason({title:'撤销补充记录',object:Object.values(r.data).filter(Boolean).slice(0,3).join(' · '),message:'撤销后这条记录不再显示，撤销原因会保留在保存区。',okLabel:'确认撤销',danger:true});if(reason===null)return;err.value='';try{const x=await fetch(supplyUrl('/gap-revoke'),{method:'POST',headers:H(true),body:JSON.stringify({id:r.id,reason,by:'本机原型'})}).then(y=>y.json());if(!x.success)throw Error(x.message);msg.value='已撤销，原因已记录';await load()}catch(e){err.value=e instanceof Error?e.message:'撤销失败'}}
function exportCurrent(){downloadCsv('六膳门'+conf.value.title+'.csv',cur.value.cols.map(c=>c.label),filtered.value.map(r=>cur.value.cols.map(c=>c.get(r)??'')))}
defineExpose({exportCurrent})
</script>
<template><div class="gap-page" :data-page="page">
<section class="card"><div class="review-content">
<p v-if="conf.gap" class="gap-empty" role="status"><b>黑湖没有此类数据。</b>{{conf.gap}}</p>
<p v-if="conf.note" class="supply-note">{{conf.note}}</p>
<p v-if="err" role="alert" class="error">{{err}}</p><p v-if="msg" role="status" class="notice">{{msg}}</p>
<form v-if="kind" class="gap-form" @submit.prevent="save"><label v-for="f in kind.fields" :key="f[0]">{{f[1]}}<span v-if="f[2]"> *</span><input v-model="form[f[0]]" :type="f[3]==='date'?'date':'text'" :inputmode="f[3]==='num'?'decimal':undefined" :aria-label="f[1]" :aria-invalid="ferr[f[0]]?'true':undefined"/><small v-if="ferr[f[0]]" role="alert" class="error">{{ferr[f[0]]}}</small></label><button class="ui-button" data-variant="primary" type="submit">保存补充记录</button></form>
</div></section>
<section v-if="cur.cols.length" class="card"><div class="filters"><input v-model="prefs.q" placeholder="搜索当前列表" aria-label="搜索"/><span class="muted">{{filtered.length}} 条 · 来源：{{own?'Cretas 补充':'黑湖只读'}}</span></div>
<table class="supply-table"><thead><tr><th v-for="c in cur.cols" :key="c.key" :class="{num:c.num}">{{c.label}}</th><th v-if="own">操作</th></tr></thead>
<tbody><tr v-for="(r,i) in pg.rows.value" :key="i"><td v-for="c in cur.cols" :key="c.key" :class="{num:c.num}">{{cell(c,r)}}</td><td v-if="own"><button class="ui-button" @click="revoke(r as unknown as Rec)">撤销</button></td></tr>
<tr v-if="!pg.rows.value.length"><td :colspan="cur.cols.length+(own?1:0)" class="empty">{{cur.empty}}</td></tr></tbody></table>
<div v-if="pg.pages.value>1" class="pager"><button class="ui-button" :disabled="pg.page.value<=1" @click="pg.page.value--">上一页</button> {{pg.page.value}} / {{pg.pages.value}} <button class="ui-button" :disabled="pg.page.value>=pg.pages.value" @click="pg.page.value++">下一页</button></div></section>
</div></template>
<style>
.gap-page .gap-empty{padding:8px 12px;border-radius:6px;background:var(--cs-warning-soft,#fff8e9);color:#80652f;margin:0 0 10px}
.gap-page .gap-form{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:8px;align-items:end;margin-top:10px}
.gap-page .gap-form label{display:grid;gap:4px;font-size:12px}
.gap-page .gap-form input{border:1px solid var(--cs-border);border-radius:6px;padding:6px 8px;font:inherit}
.gap-page .filters{display:flex;gap:10px;align-items:center;padding:10px 14px}
.gap-page .supply-table td,.gap-page .supply-table th{height:32px;padding:4px 8px;white-space:nowrap}
.gap-page .supply-table .num{text-align:right;font-variant-numeric:tabular-nums}
.gap-page .supply-table .empty{text-align:center;color:var(--cs-muted)}
.gap-page .pager{padding:8px 14px;font-size:12px}
.gap-page .supply-note{padding:8px 12px;font-size:12px;color:var(--cs-muted);background:var(--cs-surface-subtle);border-radius:6px;margin:0 0 8px}
</style>
