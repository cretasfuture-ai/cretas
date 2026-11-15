<script setup lang="ts">
// 六膳门 · 黑湖新接口只读页（第三批①②）：销售订单、采购订单、采购需求、客户、供应商、客户价目表、委外收发、应收与收款。
// 数据只读 liushanmen-v1/private/extras-current.json（经 /__lsm/supply/extras），来源一律标「黑湖」；0 条显示空态，不补数据。
// 数值两位小数；金额黑湖未返回币种，按原值显示并注明“币种未返回”；缺字段写“未返回”，不补 0。
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import type {Row} from '../frame/types'
import {S,navigate} from '../frame/store'
import {fmtQty,downloadCsv,fmtTime} from './supply-facts'
import {usePager,usePrefs} from './pager'
import {registerAiObject,setAiObject} from '../frame/ai-objects'
import type {AiFact} from '../frame/ai-protocol'
import {batchNoSearch,matchNos,relativeDate,relativeRange,viewTabs} from '../frame/ai-protocol'
import {explainOf} from './ai-supply'
interface Extras{revision:string;checkedAt:string;at:Record<string,string>;counts:Record<string,number|null>;resources:Record<string,Row[]|null>}
interface Col{key:string;label:string;get:(r:Row)=>unknown;num?:boolean;money?:boolean;link?:(r:Row)=>{path:string;no:string}|undefined}
interface Tab{id:string;label:string;rows:(r:Row)=>Row[];cols:Col[];note?:string}
interface Conf{title:string;res:string;id:(r:Row)=>string;no:(r:Row)=>string;search:(r:Row)=>string;status?:(r:Row)=>{t:string;tone:string};cols:Col[];note:string;tabs?:Tab[];sections?:{id:string;label:string;res:string;cols:Col[];no:(r:Row)=>string}[]}
const props=defineProps<{page:string}>()
const X=ref<Extras>(),busy=ref(false),err=ref('')
async function read(){busy.value=true;err.value='';try{const r=await fetch('/__lsm/supply/extras'),b=await r.json();if(!r.ok||!b.success)throw Error(b.message||'黑湖新接口数据读取失败');X.value=b.data}catch(e){err.value=e instanceof Error?e.message:'读取失败'}finally{busy.value=false}}
onMounted(read)
const res=(k:string)=>X.value?.resources[k]||[]
const v=(x:unknown)=>x===null||x===undefined||x===''?'未返回':String(x)
const d=(x:unknown)=>x?String(x).slice(0,10):'未返回'
const q2=(x:unknown)=>x===null||x===undefined||x===''?'未返回':typeof x==='string'&&!/^\s*-?\d/.test(x)?x:fmtQty(x)
const tone=(t:string)=>/取消|作废|关闭/.test(t)?'muted':/完成|结清|已收|结束/.test(t)?'ok':/逾期|超期/.test(t)?'negative':/进行|执行|未/.test(t)?'info':'zero'
const st=(k:string)=>(r:Row)=>{const t=v(r[k]);return {t,tone:tone(t)}}
// 单号跳转（第三批③）：销售单号 S…、采购单号 PO… 能在黑湖新接口里找到时，跳到对应只读页并打开详情
const saleNos=computed(()=>new Set(res('sale_order').map(r=>String(r.orderNo))))
const poNos=computed(()=>new Set(res('purchase_order').map(r=>String(r.orderCode))))
const saleLink=(no:unknown)=>{const s=String(no??'');return saleNos.value.has(s)?{path:'/sales/orders',no:s}:undefined}
const poLink=(no:unknown)=>{const s=String(no??'');return poNos.value.has(s)?{path:'/procurement/orders',no:s}:undefined}
// 第三批审计 建议：已结束、已取消的销售订单不再挂「已逾期」（黑湖 isOverdue 在结束后仍为 true）
const saleLate=(r:Row)=>!!r.isOverdue&&!/结束|取消|关闭|完成/.test(String(r.statusDesc??''))
// 第三批审计 7：与 D（finance-lsm.cjs）一致——待收为负是「多收或冲减」，不标逾期；逾期只认 overdueStatus=1 且待收>0
const recvState=(r:Row):{t:string;tone:string}=>{const p=Number(r.pendingReceiveAmount);if(Number.isFinite(p)&&p<0)return {t:'多收或冲减',tone:'zero'};const o=Number(r.overdueDays)||0;if(Number(r.overdueStatus)===1&&p>0)return {t:'逾期 '+o+' 天',tone:'negative'};return {t:v(r.receivableTypeDesc),tone:'info'}}
// 第三批审计 9：委外任务详情带出本任务的发料 / 退料 / 收货 / 退货记录（按 outsourceTaskId 关联）
const flowCols:Col[]=[{key:'t',label:'动作',get:o=>v(o.postTypeName)},{key:'no',label:'委外单',get:o=>o.orderCode},{key:'a',label:'数量',get:o=>o.amount,num:true},{key:'u',label:'单位',get:o=>v(o.pricingUnitName)},{key:'d',label:'业务时间',get:o=>d(o.bizAt)},{key:'r',label:'备注',get:o=>v(o.remark)}]
const ofTask=(k:string)=>(t:Row)=>res(k).filter(o=>String(o.outsourceTaskId)===String(t.id))
const outTabs:Tab[]=[{id:'deliver',label:'发料 / 退料',rows:ofTask('outsource_deliver_return'),cols:flowCols},{id:'receive',label:'收货 / 退货',rows:ofTask('outsource_receive_return'),cols:flowCols}]
// 第四批① BL13 参考价与偏离（R16）：黑湖开单单价不改、不回写；客户价目表价只作参考。
// 只按「客户 ID＋产品 ID」精确匹配；黑湖客户价目表没有返回有效期字段，所以不能按有效期筛，页面写明，不按近似产品猜。
const priceKey=(c:unknown,p:unknown)=>String(c)+'|'+String(p)
// R19：同一客户＋产品有多条价目时不取其中任何一条，写「有 N 条价目，无法确定参考价」并列出各条
const priceMap=computed(()=>{const m=new Map<string,Row[]>();for(const p of res('customer_price')){const k=priceKey(p.customerId,p.productId);m.set(k,[...(m.get(k)||[]),p])}return m})
const saleLines=(o:Row)=>((o.saleManageOrderDetailRowApiVOList as Row[])||[]).map(l=>{const all=priceMap.value.get(priceKey(o.customerId,l.productId))||[];return {...l,_ref:all.length===1?all[0]:undefined,_refs:all} as Row})
const multi=(l:Row)=>((l._refs as Row[])||[]).length>1
const refDiff=(l:Row)=>{const r=l._ref as Row|undefined,u=Number(l.unitPrice),p=Number(r?.price);if(!r||!Number.isFinite(p)||!Number.isFinite(u))return undefined;return {d:u-p,pct:p?((u-p)/p*100):null}}
// R20：黑湖单价为 0 不算偏离，单独计数提示核对
const zeroPrice=(l:Row)=>Number(l.unitPrice)===0
const refFlag=(l:Row)=>{if(zeroPrice(l))return '黑湖单价为 0（可能为赠品或未定价），请核对';if(multi(l))return '有 '+(l._refs as Row[]).length+' 条价目，无法确定参考价（'+(l._refs as Row[]).map(p=>fmtQty(p.price)).join(' / ')+'）';if(!l._ref)return '无有效价目';const x=refDiff(l);if(!x)return '价目或单价未返回';if(Math.abs(x.d)<0.005)return '一致';return '偏离 '+(x.d>0?'+':'')+(x.pct==null?'—':x.pct.toFixed(2)+'%')}
const refSummary=(o:Row)=>{const ls=saleLines(o);const dev=ls.filter(l=>refFlag(l).startsWith('偏离')).length,zero=ls.filter(zeroPrice).length,none=ls.filter(l=>!zeroPrice(l)&&!l._ref&&!multi(l)).length,amb=ls.filter(l=>!zeroPrice(l)&&multi(l)).length;return !ls.length?'无明细':dev||none||amb||zero?[dev?'偏离 '+dev+' 行':'',zero?'单价为 0 '+zero+' 行':'',none?'无价目 '+none+' 行':'',amb?'多条价目 '+amb+' 行':''].filter(Boolean).join('，'):'全部一致'}
const saleTabs:Tab[]=[{id:'lines',label:'明细与参考价',rows:saleLines,note:'参考价来自黑湖客户价目表（只读），按客户＋产品精确匹配；黑湖价目表未返回有效期，无法按有效期筛选。单价以黑湖开单为准，不覆盖、不回写；差额＝黑湖单价－参考价。',
  cols:[{key:'p',label:'产品',get:l=>l.productName},{key:'c',label:'编码',get:l=>l.productCode},{key:'q',label:'数量',get:l=>l.qty,num:true},{key:'u',label:'单位',get:l=>v(l.productUnitName)},{key:'up',label:'黑湖单价',get:l=>l.unitPrice,num:true,money:true},{key:'ref',label:'参考价（黑湖价目表）',get:l=>multi(l)?'无法确定':(l._ref as Row|undefined)?.price??'无有效价目',num:true,money:true},{key:'d',label:'差额',get:l=>zeroPrice(l)?'—':refDiff(l)?.d??'—',num:true,money:true},{key:'f',label:'偏离',get:l=>refFlag(l)}]}]
const custTabs:Tab[]=[
  {id:'sales',label:'销售订单',rows:c=>res('sale_order').filter(o=>String(o.customerId)===String(c.id)),cols:[{key:'no',label:'单号',get:o=>o.orderNo,link:o=>saleLink(o.orderNo)},{key:'t',label:'下单',get:o=>d(o.orderTime)},{key:'q',label:'数量',get:o=>o.totalQty,num:true},{key:'a',label:'含税金额',get:o=>o.totalTaxInclusiveAmount,num:true,money:true},{key:'s',label:'状态',get:o=>v(o.statusDesc)}]},
  {id:'recv',label:'应收',rows:c=>res('receivable').filter(o=>String(o.customerId)===String(c.id)),cols:[{key:'no',label:'应收单',get:o=>o.orderNo},{key:'d',label:'日期',get:o=>d(o.receiptDate)},{key:'a',label:'应收',get:o=>o.realReceivableAmount??o.receiptAmount,num:true,money:true},{key:'p',label:'待收',get:o=>o.pendingReceiveAmount,num:true,money:true},{key:'o',label:'状态',get:o=>recvState(o).t}]},
  {id:'rcpt',label:'收款',rows:c=>res('receipt').filter(o=>String(o.customerId)===String(c.id)),cols:[{key:'no',label:'收款单',get:o=>o.code},{key:'d',label:'日期',get:o=>d(o.receiveDate)},{key:'m',label:'方式',get:o=>v(o.mode)},{key:'a',label:'实收',get:o=>o.realReceiveAmount,num:true,money:true}]},
  {id:'inv',label:'待开票',rows:c=>res('invoicing').filter(o=>{const m=o.master as Row;return String((m?.customerVO as Row)?.id??'')===String(c.id)}),cols:[{key:'no',label:'出入库单',get:o=>(o.master as Row).orderNo},{key:'t',label:'类型',get:o=>(o.master as Row).bizTypeDisplay},{key:'d',label:'业务时间',get:o=>d((o.master as Row).bizAt)},{key:'s',label:'关联销售单',get:o=>(o.saleInfo as Row)?.orderNo??'未返回',link:o=>saleLink((o.saleInfo as Row)?.orderNo)},{key:'p',label:'单价',get:o=>(o.saleInfo as Row)?.unitPrice,num:true,money:true}]},
  {id:'price',label:'价目表',rows:c=>res('customer_price').filter(o=>String(o.customerId)===String(c.id)),cols:[{key:'p',label:'产品',get:o=>o.productName},{key:'c',label:'编码',get:o=>o.productCode},{key:'pr',label:'价格',get:o=>o.price,num:true,money:true},{key:'l',label:'最近成交价',get:o=>o.lastSalePrice,num:true,money:true}]}
]
const vendTabs:Tab[]=[
  {id:'po',label:'采购订单',rows:c=>res('purchase_order').filter(o=>String(o.vendorId)===String(c.id)),cols:[{key:'no',label:'单号',get:o=>o.orderCode,link:o=>poLink(o.orderCode)},{key:'t',label:'采购日期',get:o=>d(o.purchaseTime)},{key:'a',label:'含税金额',get:o=>o.totalTaxInclusiveAmount,num:true,money:true},{key:'s',label:'状态',get:o=>v(o.orderStatusDesc)}]},
  {id:'inv',label:'待开票',rows:c=>res('invoicing').filter(o=>{const m=o.master as Row;return String((m?.vendorVO as Row)?.id??'')===String(c.id)}),cols:[{key:'no',label:'出入库单',get:o=>(o.master as Row).orderNo},{key:'t',label:'类型',get:o=>(o.master as Row).bizTypeDisplay},{key:'d',label:'业务时间',get:o=>d((o.master as Row).bizAt)},{key:'s',label:'关联采购单',get:o=>(o.purchaseInfo as Row)?.orderNo??'未返回',link:o=>poLink((o.purchaseInfo as Row)?.orderNo)},{key:'p',label:'单价',get:o=>(o.purchaseInfo as Row)?.unitPrice,num:true,money:true}]},
  {id:'price',label:'价目表',rows:c=>res('vendor_price').filter(o=>String(o.vendorId)===String(c.id)),cols:[{key:'p',label:'产品',get:o=>o.productName},{key:'pr',label:'价格',get:o=>o.price,num:true,money:true}]},
  {id:'out',label:'委外任务',rows:c=>res('outsource_task').filter(o=>String(o.vendorId)===String(c.id)),cols:[{key:'no',label:'任务',get:o=>o.taskCode},{key:'p',label:'产品',get:o=>o.productName},{key:'pl',label:'计划',get:o=>o.outsourcePlanAmount,num:true},{key:'r',label:'实收',get:o=>o.outsourceRealAmount,num:true},{key:'u',label:'单位',get:o=>v(o.productUnit)}]}
]
const ledgerOf=(kind:'customer'|'vendor',id:unknown)=>res(kind==='customer'?'customer_ledger':'vendor_ledger').find(l=>String(l.id)===String(id))
const CONF:Record<string,Conf>={
  sales:{title:'销售订单',res:'sale_order',id:r=>String(r.id),no:r=>String(r.orderNo),search:r=>String(r.orderNo)+String(r.customerName)+String(r.contractNo??''),status:st('statusDesc'),note:'黑湖销售订单（只读）。已发、退货、待审发货按黑湖返回值；开单与审批仍在黑湖。',
    cols:[{key:'no',label:'单号',get:r=>r.orderNo},{key:'c',label:'客户',get:r=>r.customerName},{key:'t',label:'下单',get:r=>d(r.orderTime)},{key:'due',label:'计划交货',get:r=>d(r.arrivalPlanTime)+(saleLate(r)?' · 已逾期':'')},{key:'q',label:'数量',get:r=>r.totalQty,num:true},{key:'s',label:'已发',get:r=>r.shipmentQty,num:true},{key:'a',label:'含税金额',get:r=>r.totalTaxInclusiveAmount,num:true,money:true},{key:'ref',label:'对照价目表',get:r=>refSummary(r)}],tabs:saleTabs},
  purchase:{title:'采购订单',res:'purchase_order',id:r=>String(r.id),no:r=>String(r.orderCode),search:r=>String(r.orderCode)+String(r.vendorName??r.shortName),status:st('orderStatusDesc'),note:'黑湖采购订单（只读）。收货、退货数量按黑湖返回值；关联销售单可跳转。',
    cols:[{key:'no',label:'单号',get:r=>r.orderCode},{key:'v',label:'供应商',get:r=>r.vendorName??r.shortName},{key:'t',label:'采购日期',get:r=>d(r.purchaseTime)},{key:'due',label:'计划到货',get:r=>d(r.planArrivalTime)},{key:'q',label:'采购数',get:r=>r.subOrderNumTotal,num:true},{key:'r',label:'已收',get:r=>r.receivedNum,num:true},{key:'a',label:'含税金额',get:r=>r.totalTaxInclusiveAmount,num:true,money:true}],
    tabs:[{id:'lines',label:'明细',rows:r=>(r.purchaseSubOrderVOS as Row[])||[],cols:[{key:'p',label:'物料',get:i=>i.productName},{key:'c',label:'编码',get:i=>i.productCode},{key:'n',label:'采购',get:i=>i.purchaseNum,num:true},{key:'r',label:'已收',get:i=>i.receiveNum,num:true},{key:'u',label:'未清',get:i=>i.unClearNum,num:true},{key:'un',label:'单位',get:i=>v(i.unitName)},{key:'pr',label:'单价',get:i=>i.purchasePrice,num:true,money:true}]},{id:'rel',label:'关联销售单',rows:r=>((r.relationSaleOrders as Row[])||[]).map(x=>typeof x==='object'?x:{orderNo:x} as Row),cols:[{key:'no',label:'销售单号',get:i=>i.orderNo??i.saleOrderNo??i.code,link:i=>saleLink(i.orderNo??i.saleOrderNo??i.code)}]}]},
  requirement:{title:'采购需求',res:'purchase_requirement',id:r=>String(r.materialRequirementsId),no:r=>String(r.code),search:r=>String(r.code)+String(r.productName)+String(r.productCode),status:st('completeStatus'),note:'黑湖物料需求（只读）。缺料数按黑湖返回值，系统不重算；下达与收货数分列。',
    cols:[{key:'no',label:'需求单',get:r=>r.code},{key:'p',label:'物料',get:r=>r.productName},{key:'q',label:'需求',get:r=>r.reqAmount,num:true},{key:'rel',label:'已下达',get:r=>r.alreadyReleaseAmount,num:true},{key:'rec',label:'已收',get:r=>r.alreadyReceiveAmount,num:true},{key:'lack',label:'缺料',get:r=>r.lackAmount,num:true},{key:'u',label:'单位',get:r=>v(r.productUnitName)},{key:'due',label:'计划到货',get:r=>d(r.arrivalPlanTime)},{key:'v',label:'建议供应商',get:r=>v(r.purchaseVendorName)}]},
  customers:{title:'客户',res:'customer',id:r=>String(r.id),no:r=>String(r.code),search:r=>String(r.code)+String(r.name)+String(r.fullName??''),note:'黑湖客户档案与客户账本（只读）。账本金额按黑湖返回；应收、收款、待开票、价目表在详情页签里。',
    cols:[{key:'code',label:'编码',get:r=>r.code},{key:'name',label:'名称',get:r=>r.name},{key:'days',label:'账期（天）',get:r=>r.receivableDays??'未返回'},{key:'ca',label:'合同额',get:r=>ledgerOf('customer',r.id)?.contractAmount,num:true,money:true},{key:'ps',label:'待发货额',get:r=>ledgerOf('customer',r.id)?.pendingShipmentAmount,num:true,money:true},{key:'pi',label:'待开票额',get:r=>ledgerOf('customer',r.id)?.pendingInvoiceAmount,num:true,money:true},{key:'pr',label:'待收款',get:r=>ledgerOf('customer',r.id)?.pendingReceiveAmount,num:true,money:true},{key:'rc',label:'已收款',get:r=>ledgerOf('customer',r.id)?.receivedAmount,num:true,money:true}],tabs:custTabs},
  vendors:{title:'供应商',res:'vendor',id:r=>String(r.id),no:r=>String(r.code),search:r=>String(r.code)+String(r.name??r.shortName),note:'黑湖供应商档案与供应商账本（只读）。黑湖应付、付款接口返回 0 条，按账本汇总显示；采购订单、待开票、委外任务在详情页签里。',
    cols:[{key:'code',label:'编码',get:r=>r.code},{key:'name',label:'名称',get:r=>r.name??r.shortName},{key:'days',label:'账期（天）',get:r=>r.payableDays??'未返回'},{key:'pa',label:'采购额',get:r=>ledgerOf('vendor',r.id)?.purchaseAmount,num:true,money:true},{key:'pr',label:'待收货额',get:r=>ledgerOf('vendor',r.id)?.pendingReceiveAmount,num:true,money:true},{key:'pi',label:'待开票额',get:r=>ledgerOf('vendor',r.id)?.pendingInvoiceAmount,num:true,money:true},{key:'pp',label:'待付款',get:r=>ledgerOf('vendor',r.id)?.pendingPaymentAmount,num:true,money:true},{key:'pd',label:'已付款',get:r=>ledgerOf('vendor',r.id)?.paidAmount,num:true,money:true}],tabs:vendTabs},
  prices:{title:'客户价目表',res:'customer_price',id:r=>String(r.id),no:r=>String(r.productCode),search:r=>String(r.customerName)+String(r.productName)+String(r.productCode),note:'黑湖客户价目表（只读）。黑湖供应商价目表接口返回 0 条，本页只有客户价。',
    cols:[{key:'c',label:'客户',get:r=>r.customerName},{key:'p',label:'产品',get:r=>r.productName},{key:'code',label:'编码',get:r=>r.productCode},{key:'spec',label:'规格',get:r=>v(r.productSpec)},{key:'price',label:'价格',get:r=>r.price,num:true,money:true},{key:'last',label:'最近成交',get:r=>r.lastSalePrice,num:true,money:true},{key:'avg',label:'平均',get:r=>r.avgSalePrice,num:true,money:true}]},
  outsource:{title:'委外收发',res:'outsource_task',id:r=>String(r.id),no:r=>String(r.taskCode),search:r=>String(r.taskCode)+String(r.productName)+String(r.vendorName??r.shortName),note:'黑湖委外任务与发料、收货记录（只读）。计划、已发、实收、退回分列，不互相代替。',
    cols:[{key:'no',label:'任务',get:r=>r.taskCode},{key:'v',label:'委外方',get:r=>r.vendorName??r.shortName},{key:'p',label:'产品',get:r=>r.productName},{key:'pl',label:'计划',get:r=>r.outsourcePlanAmount,num:true},{key:'is',label:'已发料',get:r=>r.issuedAmount,num:true},{key:'re',label:'实收',get:r=>r.outsourceRealAmount,num:true},{key:'rt',label:'退回',get:r=>r.outsourceReturnAmount,num:true},{key:'u',label:'单位',get:r=>v(r.productUnit)}],
    tabs:outTabs,
    sections:[{id:'deliver',label:'发料 / 退料',res:'outsource_deliver_return',no:r=>String(r.orderCode),cols:[{key:'no',label:'委外单',get:r=>r.orderCode},{key:'t',label:'动作',get:r=>v(r.postTypeName)},{key:'p',label:'产品',get:r=>v(r.productName)},{key:'a',label:'数量',get:r=>r.amount,num:true},{key:'u',label:'单位',get:r=>v(r.pricingUnitName)},{key:'d',label:'业务时间',get:r=>d(r.bizAt)}]},{id:'receive',label:'收货 / 退货',res:'outsource_receive_return',no:r=>String(r.orderCode),cols:[{key:'no',label:'委外单',get:r=>r.orderCode},{key:'t',label:'动作',get:r=>v(r.postTypeName)},{key:'p',label:'产品',get:r=>v(r.productName)},{key:'a',label:'数量',get:r=>r.amount,num:true},{key:'u',label:'单位',get:r=>v(r.pricingUnitName)},{key:'d',label:'业务时间',get:r=>d(r.bizAt)}]}]},
  finance:{title:'应收与收款',res:'receivable',id:r=>String(r.id),no:r=>String(r.orderNo),search:r=>String(r.orderNo)+String(r.customerName),status:recvState,note:'黑湖应收、收款、待开票（只读）。应付、付款接口返回 0 条。金额币种黑湖未返回。',
    cols:[{key:'no',label:'应收单',get:r=>r.orderNo},{key:'c',label:'客户',get:r=>r.customerName},{key:'d',label:'日期',get:r=>d(r.receiptDate)},{key:'a',label:'应收',get:r=>r.realReceivableAmount??r.receiptAmount,num:true,money:true},{key:'s',label:'已结算',get:r=>r.settlementAmount,num:true,money:true},{key:'p',label:'待收',get:r=>r.pendingReceiveAmount,num:true,money:true},{key:'e',label:'到期',get:r=>d(r.receiveEndDate)}],
    sections:[{id:'receipt',label:'收款',res:'receipt',no:r=>String(r.code),cols:[{key:'no',label:'收款单',get:r=>r.code},{key:'c',label:'客户',get:r=>r.customerName},{key:'d',label:'日期',get:r=>d(r.receiveDate)},{key:'m',label:'方式',get:r=>v(r.mode)},{key:'a',label:'实收',get:r=>r.realReceiveAmount,num:true,money:true}]},{id:'invoicing',label:'待开票',res:'invoicing',no:r=>String((r.master as Row).orderNo),cols:[{key:'no',label:'出入库单',get:r=>(r.master as Row).orderNo},{key:'t',label:'类型',get:r=>(r.master as Row).bizTypeDisplay},{key:'d',label:'业务时间',get:r=>d((r.master as Row).bizAt)},{key:'who',label:'客户 / 供应商',get:r=>((r.master as Row).customerVO as Row)?.name??((r.master as Row).vendorVO as Row)?.shortName??'未返回'},{key:'src',label:'关联单号',get:r=>(r.saleInfo as Row)?.orderNo??(r.purchaseInfo as Row)?.orderNo??'未返回',link:r=>saleLink((r.saleInfo as Row)?.orderNo)||poLink((r.purchaseInfo as Row)?.orderNo)},{key:'p',label:'单价',get:r=>(r.saleInfo as Row)?.unitPrice??(r.purchaseInfo as Row)?.unitPrice,num:true,money:true}]}]}
}
const conf=computed(()=>CONF[props.page]||CONF.sales)
const prefs=usePrefs('unified-lsm-extras-'+props.page+'-v1',{q:'',st:'',sec:'',dk:'',grp:'',due:'',late:false})
const section=computed(()=>conf.value.sections?.find(s=>s.id===prefs.value.sec))
const baseRows=computed(()=>res(section.value?section.value.res:conf.value.res))
const curCols=computed(()=>section.value?section.value.cols:conf.value.cols)
const noOf=(r:Row)=>section.value?section.value.no(r):conf.value.no(r)
const statuses=computed(()=>section.value||!conf.value.status?[]:[...new Set(baseRows.value.map(r=>conf.value.status!(r).t))])
// 深链单号只作本次筛选，不写进记住的搜索词（第三批菜单复测：跳转一次后从菜单进入仍只剩 1 条）
const linkQ=ref('')
const qText=computed({get:()=>linkQ.value||prefs.value.q,set:(x:string)=>{linkQ.value='';prefs.value.q=x}})
// 第四批⑤ 共享组件（SHARED 接入说明）：批量单号按全部记录精确匹配；相对日期只存 key，每次按北京时间日历日重算范围
const DATE:Record<string,{get:(r:Row)=>unknown;label:string}>={sales:{get:r=>r.orderTime,label:'下单日期'},purchase:{get:r=>r.purchaseTime,label:'采购日期'},requirement:{get:r=>r.arrivalPlanTime,label:'计划到货'},prices:{get:r=>r.updatedAt,label:'更新日期'},outsource:{get:r=>r.outsourceDate,label:'委外日期'},finance:{get:r=>r.receiptDate,label:'应收日期'},
  'outsource:deliver':{get:r=>r.bizAt,label:'业务时间'},'outsource:receive':{get:r=>r.bizAt,label:'业务时间'},'finance:receipt':{get:r=>r.receiveDate,label:'收款日期'},'finance:invoicing':{get:r=>(r.master as Row)?.bizAt,label:'业务时间'}}
const dateConf=computed(()=>DATE[section.value?props.page+':'+section.value.id:props.page])
const nos=ref<string[]>([]),hit=ref(new Set<string>())
const range=computed(()=>prefs.value.dk&&dateConf.value?relativeRange(prefs.value.dk):null)
const inRange=(r:Row)=>{const rg=range.value;if(!rg)return true;const x=String(dateConf.value!.get(r)??'').slice(0,10);return !!x&&x>=rg.from&&x<=rg.to}
// 第五批②：六膳门销售订单 N2 三态（与味美销售一致：未完成＝待审批，进行中，已完成＝已结束；已取消单列）＋已存视图
const N2=(r:Row):'未完成'|'进行中'|'已完成'|'已取消'=>{const t=String(r.statusDesc??'');return /取消|关闭/.test(t)?'已取消':/结束|完成/.test(t)?'已完成':/进行|执行/.test(t)?'进行中':'未完成'}
const isSales=props.page==='sales'
// 审计退回 2：「本周交货」排除已结束、已取消
const dueWeek=(r:Row)=>{const w=relativeRange('week');const d=String(r.arrivalPlanTime??'').slice(0,10);return !!w&&!!d&&d>=w.from&&d<=w.to&&N2(r)!=='已完成'&&N2(r)!=='已取消'}
// 审计退回 1（R21）：状态计数＝除状态（N2 分组、状态下拉）以外的当前全部筛选，所以 pass(r,true) 跳过状态条件
const salesExtra=(r:Row,noStatus=false)=>!isSales||section.value?true:(noStatus||!prefs.value.grp||N2(r)===prefs.value.grp)&&(!prefs.value.due||dueWeek(r))&&(!prefs.value.late||saleLate(r))
const pass=(r:Row,noStatus=false)=>salesExtra(r,noStatus)&&(!nos.value.length||hit.value.has(noOf(r)))&&inRange(r)&&(noStatus||!prefs.value.st||section.value||conf.value.status?.(r).t===prefs.value.st)&&(!qText.value||(section.value?noOf(r)+JSON.stringify(r).slice(0,400):conf.value.search(r)).toLowerCase().includes(qText.value.toLowerCase()))
const filtered=computed(()=>baseRows.value.filter(r=>pass(r)))
const countBase=computed(()=>baseRows.value.filter(r=>pass(r,true)))
const pager=usePager(filtered)
const batchHost=ref<HTMLElement>(),dateHost=ref<HTMLElement>();let batch:{element:HTMLElement;close():void}|undefined,rel:{element:HTMLElement}|undefined
onMounted(()=>{batch=batchNoSearch(batchHost.value||null,{label:'批量单号',onSearch:list=>{const m=matchNos(baseRows.value,list,r=>noOf(r));nos.value=list;hit.value=new Set(m.found.map(r=>noOf(r)));return {found:m.found.length,missing:m.missing}},onClear:()=>{nos.value=[];hit.value=new Set()}})
  if(DATE[props.page])rel=relativeDate(dateHost.value||null,{value:prefs.value.dk,onChange:r=>{prefs.value.dk=r?.key||''}})})
function resetAll(){qText.value='';prefs.value.st='';prefs.value.dk='';prefs.value.grp='';prefs.value.due='';prefs.value.late=false;vtabs?.refresh(n2Counts(),'');nos.value=[];hit.value=new Set();batch?.close();const r=rel as {set?(v:string):void}|undefined;r?.set?.('')}
const tabsHost=ref<HTMLElement>();let vtabs:ReturnType<typeof viewTabs>|undefined
const n2Counts=()=>['未完成','进行中','已完成','已取消'].map(k=>({key:k,label:k,count:X.value?countBase.value.filter(r=>N2(r)===k).length:null}))
onMounted(()=>{if(!isSales)return
  vtabs=viewTabs(tabsHost.value||null,{page:'lsm-sales-orders',builtins:[{id:'all',name:'全部',state:{grp:'',due:'',late:false,st:'',dk:''}},{id:'open',name:'未完成',state:{grp:'未完成',due:'',late:false,st:'',dk:''}},{id:'dueweek',name:'本周交货',state:{grp:'',due:'week',late:false,st:'',dk:''}},{id:'late',name:'已逾期',state:{grp:'',due:'',late:true,st:'',dk:''}}],
    getState:()=>({grp:prefs.value.grp,due:prefs.value.due,late:prefs.value.late,st:prefs.value.st,dk:prefs.value.dk,q:prefs.value.q}),
    apply:x=>{prefs.value.grp=String(x.grp??'');prefs.value.due=String(x.due??'');prefs.value.late=x.late===true;prefs.value.st=String(x.st??'');prefs.value.dk=String(x.dk??'');prefs.value.q=String(x.q??'');const r=rel as {set?(v:string):void}|undefined;r?.set?.(prefs.value.dk)},
    counts:n2Counts(),countKey:prefs.value.grp,onCount:k=>{prefs.value.grp=prefs.value.grp===k?'':k;vtabs?.refresh(n2Counts(),prefs.value.grp)}})})
watch(()=>[X.value?.revision,prefs.value.grp,countBase.value.length,prefs.value.due,prefs.value.late],()=>vtabs?.refresh(n2Counts(),prefs.value.grp))
onBeforeUnmount(()=>{vtabs?.element.remove();batch?.element.remove();rel?.element.remove()})
watch(()=>prefs.value.sec,()=>{nos.value=[];batch?.close()})
const cell=(c:Col,r:Row)=>{const x=c.get(r);if(c.num)return q2(x);return v(x)}
const open=ref<Row>(),tab=ref('info')
watch(open,()=>{tab.value='info'})
// 深链：?no=<单号> 精确命中时打开详情（与第一批约定相同）
// 同一路由从 ?no= 回到不带单号（菜单再点一次）时不会重建组件，所以跟着路由清掉本次筛选和详情
const deepNo=computed(()=>S.query.get('no')||'')
watch([deepNo,()=>baseRows.value.length],([no])=>{
  if(!no){if(linkQ.value){linkQ.value='';open.value=undefined}return}
  if(section.value||(open.value&&linkQ.value===no))return
  const hit=res(conf.value.res).find(r=>conf.value.no(r)===no);if(hit){linkQ.value=no;open.value=hit}},{immediate:true})
function go(l:{path:string;no:string}|undefined){if(l)navigate(l.path,{no:l.no})}
// 第四批⑥：严格模式解读（服务端 extraFacts 算事实与允许的核对代码，模型只选代码，正文由代码渲染）
registerAiObject('lsm-extra',{noun:'这条黑湖记录',placeholder:'例如：这张单有什么要核对的？',explain:(id,o)=>explainOf('extra',props.page+':'+id,o.question),isStale:(_id,a)=>Boolean(X.value&&a.objectRev&&a.objectRev!==X.value.revision),describe:id=>{const r=res(conf.value.res).find(x=>conf.value.id(x)===id);return r?conf.value.title+' · '+conf.value.no(r):undefined},
  facts:id=>{const r=res(conf.value.res).find(x=>conf.value.id(x)===id);if(!r)return [];const f:AiFact[]=[{label:'来源',value:'黑湖新接口（只读，采集于 '+fmtTime(X.value?.checkedAt)+'）',kind:'source:blacklake'},{label:'对象',value:conf.value.title}];if(conf.value.status)f.push({label:'状态',value:conf.value.status(r).t});// 审计退回 3：依据区数量、金额两位小数
  for(const c of conf.value.cols){const x=c.get(r);f.push({label:c.label+(c.money?'（币种未返回）':''),value:x===undefined||x===''?null:c.num?q2(x):x})}return f}})
watch(open,r=>{if(r)setAiObject({kind:'lsm-extra',id:conf.value.id(r)},conf.value.title)})
function exportCsv(){downloadCsv('六膳门'+(section.value?.label||conf.value.title)+'.csv',curCols.value.map(c=>c.label),filtered.value.map(r=>curCols.value.map(c=>{const x=c.get(r);return x==null?'':x})))}
defineExpose({exportCurrent:exportCsv})
</script>
<template><div>
<p class="supply-note">{{conf.note}} 来源：黑湖新接口，采集于 {{fmtTime(X?.checkedAt)}}；数值两位小数，金额币种未返回。</p>
<p v-if="err" role="alert" class="error">{{err}}</p>
<div v-if="conf.sections" class="cs-tabs lsm-tabs supply-tabs" role="tablist" :aria-label="conf.title"><button role="tab" :aria-selected="!prefs.sec" @click="prefs.sec=''">{{conf.title==='应收与收款'?'应收':conf.title==='委外收发'?'委外任务':conf.title}} <small>{{X?.counts[conf.res]??'—'}}</small></button><button v-for="s in conf.sections" :key="s.id" role="tab" :aria-selected="prefs.sec===s.id" @click="prefs.sec=s.id">{{s.label}} <small>{{X?.counts[s.res]??'—'}}</small></button></div>
<section class="card">
<div v-if="isSales&&!section" ref="tabsHost" class="view-tabs-host"></div>
<form class="filters" @submit.prevent>
<input v-model="qText" aria-label="搜索" placeholder="单号、名称"><span ref="batchHost" class="ui-batch-host"></span><span v-show="dateConf" ref="dateHost" class="ui-date-host" :title="dateConf?'按'+dateConf.label+'筛选':''"></span>
<select v-if="statuses.length" v-model="prefs.st" aria-label="按状态筛选"><option value="">全部状态（{{baseRows.length}}）</option><option v-for="s in statuses" :key="s" :value="s">{{s}}</option></select>
<button type="button" class="ui-button" @click="resetAll">重置</button><button type="button" class="ui-button" :disabled="busy" @click="read">{{busy?'正在读取…':'重新读取'}}</button>
</form>
<div class="section-head"><h2>{{section?.label||conf.title}} · {{filtered.length}} 条</h2><small v-if="range&&dateConf" class="muted">{{dateConf.label}} {{range.from}} 至 {{range.to}}</small><small v-if="nos.length" class="muted">批量单号 {{nos.length}} 个</small><small v-if="isSales&&(prefs.grp||prefs.due||prefs.late)" class="muted">{{[prefs.grp,prefs.due?'本周交货（计划交货 '+(relativeRange('week')?.from||'')+' 至 '+(relativeRange('week')?.to||'')+'）':'',prefs.late?'已逾期（未结束且计划交货已过）':''].filter(Boolean).join(' · ')}}</small><small><span class="src-tag">黑湖</span> 只读</small></div>
<div class="table-scroll"><table class="supply-table"><thead><tr><th v-for="c in curCols" :key="c.key" :class="c.num?'num':''">{{c.label}}</th><th v-if="conf.status&&!section">状态</th><th v-if="!section">操作</th></tr></thead><tbody>
<tr v-for="(r,i) in pager.rows.value" :key="noOf(r)+'-'+i"><td v-for="c in curCols" :key="c.key" :class="c.num?'num':''"><button v-if="c.link&&c.link(r)" class="op" :title="'打开 '+cell(c,r)" @click="go(c.link(r))">{{cell(c,r)}}</button><template v-else>{{cell(c,r)}}</template></td><td v-if="conf.status&&!section"><span class="supply-badge" :data-tone="conf.status(r).tone">{{conf.status(r).t}}</span></td><td v-if="!section"><button class="op" @click="open=r">详情</button></td></tr>
<tr v-if="!pager.rows.value.length"><td :colspan="curCols.length+2" class="empty">{{busy?'正在读取':(X&&X.counts[section?.res||conf.res]===0?'黑湖接口返回 0 条。':'当前筛选没有记录。')}}</td></tr></tbody></table></div>
<div class="pagination"><span>第 {{pager.page.value}} / {{pager.pages.value}} 页</span><button class="ui-button" :disabled="pager.page.value<=1" @click="pager.page.value--">上一页</button><button class="ui-button" :disabled="pager.page.value>=pager.pages.value" @click="pager.page.value++">下一页</button></div>
</section>
<section v-if="open" class="card detail-card" :aria-label="conf.title+'详情'"><div class="section-head"><h2>{{conf.title}} · {{conf.no(open)}}</h2><button class="ui-button" @click="open=undefined">关闭</button></div>
<div class="cs-tabs lsm-tabs supply-tabs" role="tablist" :aria-label="conf.title+'详情'"><button role="tab" :aria-selected="tab==='info'" @click="tab='info'">概况</button><button v-for="t in conf.tabs||[]" :key="t.id" role="tab" :aria-selected="tab===t.id" @click="tab=t.id">{{t.label}} <small>{{t.rows(open).length}}</small></button></div>
<template v-if="tab==='info'"><dl class="kv"><template v-for="c in conf.cols" :key="c.key"><dt>{{c.label}}</dt><dd>{{cell(c,open)}}</dd></template><template v-if="conf.status"><dt>状态</dt><dd>{{conf.status(open).t}}</dd></template><dt>备注</dt><dd>{{v(open.remark)}}</dd><dt>来源</dt><dd><span class="src-tag">黑湖</span> 更新于 {{v(open.updatedAt)}}</dd></dl></template>
<template v-for="t in conf.tabs||[]" :key="t.id"><template v-if="tab===t.id"><p v-if="t.note" class="supply-note">{{t.note}}</p><p v-if="!t.rows(open).length" class="muted detail-pad">{{t.id==='price'&&props.page==='vendors'?'黑湖供应商价目表接口返回 0 条。':'没有相关记录。'}}</p><table v-else class="supply-table"><thead><tr><th v-for="c in t.cols" :key="c.key" :class="c.num?'num':''">{{c.label}}</th></tr></thead><tbody><tr v-for="(x,i) in t.rows(open)" :key="i"><td v-for="c in t.cols" :key="c.key" :class="c.num?'num':''"><button v-if="c.link&&c.link(x)" class="op" @click="go(c.link(x))">{{cell(c,x)}}</button><template v-else>{{cell(c,x)}}</template></td></tr></tbody></table></template></template>
</section>
</div></template>
