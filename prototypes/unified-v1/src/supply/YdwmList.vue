<script setup lang="ts">
// 味美 · 资料与单据精简视图（第二批①，R6：只读，原页面默认，写操作回原页面）。按配置渲染：
// 请购（1/2）、供应商（5）、价格表（6）、客户（8）、退货（13）、调拨（17）、盘点（19）；第五批：收货任务（15）、物料字典（22）。
// 会话跟随当前会话（ydwm.ts）；金额列在后端按权限返回为空时整列隐藏（第二批②，不按角色写死）。
import {computed,onMounted,ref,watch} from 'vue'
import type {Row} from '../frame/types'
import {yget,num,dateOnly,YD_SESSION_LABEL,withSession} from './ydwm'
import {fmtQty,downloadCsv} from './supply-facts'
import {usePager,usePrefs} from './pager'
import {registerAiObject,setAiObject} from '../frame/ai-objects'
import type {AiFact} from '../frame/ai-protocol'
interface Col{key:string;label:string;get:(r:Row)=>unknown;num?:boolean;money?:boolean}
interface Conf{title:string;api:string;native:string;noun:string;search:(r:Row)=>string;status?:(r:Row)=>{t:string;tone:string};cols:Col[];dupKey?:(r:Row)=>string;note:string;detail?:(r:Row)=>Row[]|undefined;detailCols?:Col[]}
const props=defineProps<{page:string}>()
const yes=(v:unknown)=>v===true||v==='ACTIVE'||v===1
const active=(r:Row)=>yes(r.isActive??r.status)?{t:'启用',tone:'ok'}:r.isActive===false||r.status==='INACTIVE'?{t:'停用',tone:'muted'}:{t:String(r.status??'未记录'),tone:'muted'}
const S_RQ:Record<string,string>={DRAFT:'草稿',SUBMITTED:'已提交',PENDING_APPROVAL:'待审批',APPROVED:'已批准',REJECTED:'已驳回',CONVERTED:'已转采购',CANCELLED:'已取消',WITHDRAWN:'已撤回'}
const S_TR:Record<string,string>={DRAFT:'草稿',SHIPPED:'已调出在途',IN_TRANSIT:'在途',RECEIVED:'已签收',COMPLETED:'已完成',CANCELLED:'已取消'}
const S_ST:Record<string,string>={INITIATED:'已发起',COUNTING:'盘点中',SUBMITTED:'待复核',APPROVED:'已批准',APPROVED_APPLIED:'已审批并生效',APPLIED:'已生效',REJECTED:'已驳回',CANCELLED:'已取消'}
// 第五批：收货任务状态（味美接口同时返回 statusLabel，优先用接口自带的中文）
const S_RV:Record<string,string>={WAITING_RECEIVE:'待收货',RECEIVING:'收货中',PARTIAL_RECEIVED:'部分入库',PARTIALLY_RECEIVED:'部分入库',RECEIVED:'已入库',COMPLETED:'已完成',CANCELLED:'已取消',CLOSED:'已关闭'}
const rvItems=(r:Row)=>items(r,'items')
const rvSum=(r:Row,k:string)=>{const m=new Map<string,number>();let miss=false;for(const i of rvItems(r)){const v=num(i[k]);if(v===undefined){miss=true;continue}const u=String(i.unit??'（无单位）');m.set(u,(m.get(u)||0)+v)}return m.size?[...m].map(([u,v])=>fmtQty(v)+' '+u).join('；')+(miss?'（部分未返回）':''):'未返回'}
const S_RT:Record<string,string>={DRAFT:'草稿',PENDING:'待处理',PENDING_APPROVAL:'待审批',APPROVED:'已批准',RECEIVED:'已入库',COMPLETED:'已完成',REJECTED:'已驳回',CANCELLED:'已取消'}
const tone=(t:string)=>/取消|驳回|停用/.test(t)?'muted':/完成|生效|签收|入库|批准|转采购/.test(t)?'ok':/草稿|待/.test(t)?'zero':'info'
const st=(map:Record<string,string>)=>(r:Row)=>{const t=map[String(r.status)]||String(r.status??'未记录');return {t,tone:tone(t)}}
const pick=(r:Row,...k:string[])=>{for(const x of k)if(r[x]!=null&&r[x]!=='')return r[x];return undefined}
const items=(r:Row,key:string)=>((r[key] as Row[])||[])
const itemSum=(key:string)=>(r:Row)=>{const l=items(r,key);if(!l.length)return '—';const f=l[0];return String(f.materialName??f.productName??'未提供')+' '+fmtQty(f.quantity)+' '+String(f.unit??'')+(l.length>1?' 等 '+l.length+' 项':'')}
const CONF:Record<string,Conf>={
  requisitions:{title:'请购单',api:'purchase-requisitions?page=1&size=500',native:'/procurement/requisitions/my',noun:'这张请购单',search:r=>String(r.requisitionNumber)+String(r.reason),status:st(S_RQ),note:'本人与待审批请购同一列表；提交、审批、转采购在原页面办理。',
    cols:[{key:'no',label:'单号',get:r=>r.requisitionNumber},{key:'items',label:'物料 × 数量',get:itemSum('requestedItems')},{key:'reason',label:'原因',get:r=>r.reason||'未填写'},{key:'due',label:'期望交货',get:r=>dateOnly(pick(r,'expectedDeliveryDate','requiredDate'))||'未填写'},{key:'at',label:'创建',get:r=>dateOnly(r.createdAt)||'未记录'}],
    detail:r=>items(r,'requestedItems'),detailCols:[{key:'m',label:'物料',get:i=>i.materialName||'未提供'},{key:'q',label:'数量',get:i=>fmtQty(i.quantity),num:true},{key:'u',label:'单位',get:i=>i.unit||'（无单位）'}]},
  suppliers:{title:'供应商',api:'suppliers?page=1&size=500',native:'/procurement/suppliers',noun:'这个供应商',search:r=>String(r.name)+String(r.code??'')+String(r.phone??''),status:active,dupKey:r=>String(r.name)+'|'+String(r.phone??''),note:'同名同电话的档案标“疑似重复”，不自动合并；新增、编辑、导入在原页面办理。',
    cols:[{key:'code',label:'编号',get:r=>r.code||r.supplierCode||'未编号'},{key:'name',label:'名称',get:r=>r.name},{key:'c',label:'联系人',get:r=>r.contactPerson||'未填写'},{key:'p',label:'电话',get:r=>r.phone||'未填写'},{key:'a',label:'地址',get:r=>r.address||'未填写'}]},
  customers:{title:'客户',api:'customers?page=1&size=500',native:'/sales/customers',noun:'这个客户',search:r=>String(r.name)+String(r.code??'')+String(r.phone??''),status:active,dupKey:r=>String(r.name)+'|'+String(r.phone??''),note:'客户档案、沟通记录和报价在原页面的客户详情里；同名同电话的档案标“疑似重复”。',
    cols:[{key:'code',label:'编号',get:r=>r.code||r.customerCode||'未编号'},{key:'name',label:'名称',get:r=>r.name},{key:'c',label:'联系人',get:r=>r.contactPerson||'未填写'},{key:'p',label:'电话',get:r=>r.phone||'未填写'},{key:'a',label:'收货地址',get:r=>r.shippingAddress||'未填写'}]},
  pricelists:{title:'价格表',api:'price-lists?page=1&size=500',native:'/procurement/price-lists',noun:'这张价格表',search:r=>String(r.name),status:r=>{const t=yes(r.isActive)?'有效':'停用';return {t,tone:t==='有效'?'ok':'muted'}},note:'价格条目在原页面维护；即将到期（30 天内）单独标出。',
    cols:[{key:'name',label:'名称',get:r=>r.name},{key:'type',label:'类型',get:r=>r.priceType||'未填写'},{key:'f',label:'生效',get:r=>dateOnly(r.effectiveFrom)||'未填写'},{key:'t',label:'失效',get:r=>{const d=dateOnly(r.effectiveTo);if(!d)return '未填写';const left=Math.floor((Date.parse(d)-Date.now())/864e5);return d+(left>=0&&left<=30?'（'+left+' 天后到期）':left<0?'（已过期）':'')}}]},
  returns:{title:'销售退货',api:'return-orders?page=1&size=500',native:'/sales/returns',noun:'这张退货单',search:r=>String(pick(r,'returnNumber','returnOrderNumber'))+String(r.customerName??''),status:st(S_RT),note:'退货从销售订单详情“申请退货”发起；入库、退款在原页面和仓库页办理。',
    cols:[{key:'no',label:'退货单号',get:r=>pick(r,'returnNumber','returnOrderNumber')||'未记录'},{key:'c',label:'客户',get:r=>r.customerName||'未记录'},{key:'src',label:'原订单',get:r=>pick(r,'salesOrderNumber','orderNumber')||'未记录'},{key:'d',label:'退货日期',get:r=>dateOnly(r.returnDate)||'未填写'},{key:'amt',label:'金额',get:r=>r.totalAmount,num:true,money:true}]},
  transfers:{title:'调拨单',api:'transfers?page=1&size=500',native:'/transfer/list',noun:'这张调拨单',search:r=>String(r.transferNumber),status:st(S_TR),note:'调出未签收为“在途”，不计入调入仓库存；调出、签收在原页面办理。',
    cols:[{key:'no',label:'调拨编号',get:r=>r.transferNumber||'未记录'},{key:'type',label:'类型',get:r=>r.transferType||'未填写'},{key:'from',label:'调出',get:r=>pick(r,'sourceWarehouseName','sourceWarehouseId','sourceFactoryId')||'未记录'},{key:'to',label:'调入',get:r=>pick(r,'targetWarehouseName','targetWarehouseId','targetFactoryId')||'未记录'},{key:'d',label:'调拨日期',get:r=>dateOnly(r.transferDate)||'未填写'},{key:'amt',label:'金额',get:r=>r.totalAmount,num:true,money:true}]},
  // 第五批（规格第 15 节）：只列收货任务，批次在库存查询的批次明细里，不与任务混在同一组页签
  receiving:{title:'收货任务',api:'warehouse/receiving/tasks',native:'/warehouse/materials',noun:'这个收货任务',search:r=>String(pick(r,'orderNumber','purchaseOrderNumber'))+String(r.supplierName??''),status:r=>{const t=String(r.statusLabel||S_RV[String(r.status)]||r.status||'未记录');return {t,tone:tone(t)}},note:'只列收货任务（订购、已收、待收按单位分开）；入库批次在「库存查询」的批次明细里看。收货、上传凭证、确认入库在原页面办理。',
    cols:[{key:'no',label:'采购单号',get:r=>pick(r,'purchaseOrderNumber','orderNumber')||'未记录'},{key:'sup',label:'供应商',get:r=>r.supplierName||'未记录'},{key:'m',label:'物料',get:r=>{const n=[...new Set(rvItems(r).map(i=>String(i.materialName??'未提供')))];return n.length>1?n[0]+' 等 '+n.length+' 种':n[0]||'—'}},{key:'o',label:'订购',get:r=>rvSum(r,'orderedQuantity')},{key:'rc',label:'已收',get:r=>rvSum(r,'receivedQuantity')},{key:'rm',label:'待收',get:r=>rvSum(r,'remainingReceivableQuantity')},{key:'ov',label:'超收规则',get:r=>r.overReceiveLimitLabel||'未返回'}],
    detail:r=>rvItems(r),detailCols:[{key:'m',label:'物料',get:i=>i.materialName||'未提供'},{key:'o',label:'订购',get:i=>i.orderedQuantity,num:true},{key:'rc',label:'已收',get:i=>i.receivedQuantity,num:true},{key:'rm',label:'待收',get:i=>i.remainingReceivableQuantity,num:true},{key:'u',label:'单位',get:i=>i.unit||'（无单位）'}]},
  // 第五批（规格第 22 节）：原料类型字典只读；同名同编码标「疑似重复」，不自动合并；物料分类树在原页面（接口返回 0 条）
  materials:{title:'物料字典',api:'raw-material-types?page=1&size=500',native:'/warehouse/material-types',noun:'这个物料',search:r=>String(r.name)+String(r.code??'')+String(r.category??''),status:active,dupKey:r=>String(r.name)+'|'+String(r.code??''),note:'原料类型字典只读；同名同编码的档案标“疑似重复”，不自动合并。新建、编辑、停用和分类树在原页面办理。',
    cols:[{key:'code',label:'编码',get:r=>r.code||'未编号'},{key:'name',label:'名称',get:r=>r.name||'未命名'},{key:'u',label:'单位',get:r=>r.unit||'（无单位）'},{key:'c',label:'分类',get:r=>r.category||'未分类'},{key:'mc',label:'用途',get:r=>({AUXILIARY:'辅料',PACKAGING:'包材',RAW:'原料',MAIN:'主料'} as Record<string,string>)[String(r.materialCategory)]||r.materialCategory||'未填写'},{key:'sto',label:'储存',get:r=>({FROZEN:'冷冻',CHILLED:'冷藏',DRY:'常温干燥',AMBIENT:'常温'} as Record<string,string>)[String(r.storageType)]||r.storageType||'未填写'},{key:'p',label:'参考价',get:r=>r.materialReferencePrice,money:true}]},
  stocktakes:{title:'盘点任务',api:'stocktakes?page=1&size=500',native:'/warehouse/stocktake-query',noun:'这张盘点单',search:r=>String(pick(r,'stocktakeNumber','planNumber','id')),status:st(S_ST),note:'盘点数量暂存不生效，他人复核批准后才调整库存；发起、录入、复核在原页面办理。',
    cols:[{key:'no',label:'盘点单号',get:r=>pick(r,'stocktakeNumber','planNumber')||'未记录'},{key:'w',label:'仓库',get:r=>pick(r,'warehouseName','warehouseId')||'未记录'},{key:'base',label:'基准时间',get:r=>dateOnly(pick(r,'baselineAt','snapshotAt'))||'未记录'},{key:'by',label:'发起',get:r=>dateOnly(r.initiatedAt)||'未记录'},{key:'ap',label:'复核人',get:r=>pick(r,'approvedByName','approvedBy')||'—'}]}
}
const conf=computed(()=>CONF[props.page]||CONF.requisitions)
const rows=ref<Row[]>([]),busy=ref(false),err=ref(''),open=ref<Row>()
const prefs=usePrefs('unified-ydwm-list-'+props.page+'-v1',{q:'',st:''})
async function read(){busy.value=true;err.value='';try{const d=await yget<Row[]>(conf.value.api);rows.value=Array.isArray(d)?d:[]}catch(e){err.value=e instanceof Error?e.message:'读取失败';rows.value=[]}finally{busy.value=false}}
onMounted(read)
// 第二批②：金额列只在后端返回了金额时显示；全空时不断定原因（可能无价格权限，也可能未录入），不在前端按角色名判断
const moneyVisible=computed(()=>rows.value.some(r=>conf.value.cols.some(c=>c.money&&c.get(r)!=null&&c.get(r)!=='')))
const cols=computed(()=>conf.value.cols.filter(c=>!c.money||moneyVisible.value))
const hasMoney=computed(()=>conf.value.cols.some(c=>c.money))
const dups=computed(()=>{const k=conf.value.dupKey;if(!k)return new Set<string>();const m=new Map<string,number>();for(const r of rows.value)m.set(k(r),(m.get(k(r))||0)+1);return new Set([...m.entries()].filter(([,n])=>n>1).map(([x])=>x))})
const statuses=computed(()=>[...new Set(rows.value.map(r=>conf.value.status?.(r).t||''))].filter(Boolean))
const filtered=computed(()=>rows.value.filter(r=>(!prefs.value.st||conf.value.status?.(r).t===prefs.value.st)&&(!prefs.value.q||conf.value.search(r).toLowerCase().includes(prefs.value.q.toLowerCase()))))
const pager=usePager(filtered)
const cell=(c:Col,r:Row)=>{const v=c.get(r);if(c.money)return v==null||v===''?'—':fmtQty(v)+' 元';if(c.num&&num(v)!==undefined)return fmtQty(v);return v==null||v===''?'—':String(v)}
function goNative(r?:Row){const s=withSession(new URLSearchParams({view:'native'})).toString();const u=conf.value.native+'?'+s;void r;try{window.top!.location.href=u}catch{location.href=u}}
// 打开详情时把这条记录交给唯一悬浮窗（只提供系统计算的依据）
registerAiObject('ydwm-record',{noun:'这条记录',describe:id=>{const r=rows.value.find(x=>String(x.id)===id);return r?conf.value.title+' · '+String(cell(conf.value.cols[0],r)):undefined},
  facts:id=>{const r=rows.value.find(x=>String(x.id)===id);if(!r)return [];const f:AiFact[]=[{label:'来源',value:'味美 · '+YD_SESSION_LABEL(),kind:'source:ydwm'},{label:'对象',value:conf.value.title}];if(conf.value.status)f.push({label:'状态',value:conf.value.status(r).t});for(const c of cols.value)f.push({label:c.label,value:c.get(r)??null});if(conf.value.dupKey&&dups.value.has(conf.value.dupKey(r)))f.push({label:'疑似重复',value:'同名同电话的档案不止一份'});return f}})
watch(open,r=>{if(r)setAiObject({kind:'ydwm-record',id:String(r.id)},conf.value.title+'精简视图')})
function exportCsv(){downloadCsv('味美'+conf.value.title+'.csv',[...cols.value.map(c=>c.label),'状态'],filtered.value.map(r=>[...cols.value.map(c=>cell(c,r)),conf.value.status?.(r).t||'']))}
defineExpose({exportCurrent:exportCsv})
</script>
<template><div>
<p class="supply-note">精简视图（只读）· 味美工作区 · {{YD_SESSION_LABEL()}}。{{conf.note}}<template v-if="hasMoney&&!moneyVisible&&rows.length"> 金额未返回（可能是无价格权限或未录入），金额列已隐藏。</template></p>
<p v-if="err" role="alert" class="error">{{err}}</p>
<section class="card">
<form class="filters" @submit.prevent>
<input v-model="prefs.q" aria-label="搜索" placeholder="单号、名称">
<select v-if="statuses.length" v-model="prefs.st" aria-label="按状态筛选"><option value="">全部状态（{{rows.length}}）</option><option v-for="s in statuses" :key="s" :value="s">{{s}}</option></select>
<button type="button" class="ui-button" @click="prefs.q='';prefs.st=''">重置</button><button type="button" class="ui-button" :disabled="busy" @click="read">{{busy?'正在读取…':'刷新'}}</button>
</form>
<div class="section-head"><h2>{{conf.title}} · {{filtered.length}} 条</h2><small v-if="dups.size">疑似重复 {{dups.size}} 组</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th v-for="c in cols" :key="c.key" :class="c.num?'num':''">{{c.label}}</th><th v-if="conf.status">状态</th><th>操作</th></tr></thead><tbody>
<tr v-for="r in pager.rows.value" :key="String(r.id)"><td v-for="(c,i) in cols" :key="c.key" :class="c.num?'num':''">{{cell(c,r)}}<span v-if="i===1&&conf.dupKey&&dups.has(conf.dupKey(r))" class="supply-badge" data-tone="zero">疑似重复</span></td><td v-if="conf.status"><span class="supply-badge" :data-tone="conf.status(r).tone">{{conf.status(r).t}}</span></td><td class="ops"><button class="op" @click="open=r">详情</button><button class="op" @click="goNative(r)">处理</button></td></tr>
<tr v-if="!pager.rows.value.length"><td :colspan="cols.length+2" class="empty">{{busy?'正在读取':'当前会话没有'+conf.title+'记录。'}}</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{pager.page.value}} / {{pager.pages.value}} 页</span><button class="ui-button" :disabled="pager.page.value<=1" @click="pager.page.value--">上一页</button><button class="ui-button" :disabled="pager.page.value>=pager.pages.value" @click="pager.page.value++">下一页</button></div>
</section>
<section v-if="open" class="card detail-card" :aria-label="conf.title+'详情'"><div class="section-head"><h2>{{conf.title}} · {{cell(conf.cols[0],open)}}</h2><button class="ui-button" @click="open=undefined">关闭</button></div>
<dl class="kv"><template v-for="c in cols" :key="c.key"><dt>{{c.label}}</dt><dd>{{cell(c,open)}}</dd></template><template v-if="conf.status"><dt>状态</dt><dd>{{conf.status(open).t}}</dd></template><dt>备注</dt><dd>{{open.notes||'—'}}</dd></dl>
<template v-if="conf.detail&&conf.detailCols"><h3>明细</h3><table class="supply-table"><thead><tr><th v-for="c in conf.detailCols" :key="c.key" :class="c.num?'num':''">{{c.label}}</th></tr></thead><tbody><tr v-for="(i,k) in conf.detail(open)" :key="k"><td v-for="c in conf.detailCols" :key="c.key" :class="c.num?'num':''">{{cell(c,i)}}</td></tr></tbody></table></template>
</section>
</div></template>
