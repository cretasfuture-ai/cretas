// 六膳门采购销售库存：确定性口径（规格 C-supply.md 第 24–27、31 节）。
// 只读黑湖快照；所有数量、分类、零/负/未返回、期初期末都在这里由代码算，界面和 AI 不改这些事实。
import type {Row} from '../frame/types'

export interface Line extends Row {id:unknown;productId:unknown;productCode:unknown;productName:unknown;qty:unknown;unitName:unknown;realCostAmount:unknown;associatedBizOrderCode?:unknown;detailWarehouseId?:unknown}
export interface Order extends Row {orderNo:unknown;bizTypeDisplay:unknown;bizAt:unknown;orderStatusDisplay:unknown;lines:Line[]}
export interface Inventory {factoryId:string;checkedAt:string;materialAt:string;revision:string;stock:Row[];orders:Order[];warehouses:Row[]}

// ---------- 数值 ----------
// 统一两位小数（DECISIONS T5）。共享 fmtQty 由 D 放进设计系统后改为引用。
export function toNum(v:unknown):number|undefined{
  if(typeof v==='number')return Number.isFinite(v)?v:undefined
  if(typeof v==='string'&&/^\s*-?\d+(?:\.\d+)?\s*$/.test(v))return Number(v)
  return undefined
}
export function fmtQty(v:unknown):string{
  // 优先用 D 线共享格式（T5）；无单位单元格用 fmtNumber，单位单独一列
  const ui=(globalThis as unknown as {CretasUI?:{fmtNumber:(v:unknown,o?:{digits?:number})=>string}}).CretasUI
  if(ui)return ui.fmtNumber(v,{digits:2})
  if(v===null||v===undefined||v==='')return '—'
  const n=toNum(v);if(n===undefined)return '格式无效'
  return n.toLocaleString('zh-CN',{maximumFractionDigits:2})
}
// 十进制累加，避免浮点误差（数量最多 6 位小数）
export function add(a:number,b:number){return Math.round((a+b)*1e6)/1e6}
export const unitKey=(u:unknown)=>u==null||u===''?'（无单位）':String(u).trim()
export function fmtTime(v:unknown):string{
  if(!v)return '未记录'
  const s=String(v)
  if(/T.*(?:Z|[+-]\d\d:?\d\d)$/.test(s))return new Date(s).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})
  return s.replace('T',' ').slice(0,19)
}
export function localDate(v:unknown):string{
  if(!v)return ''
  const s=String(v)
  if(/T.*(?:Z|[+-]\d\d:?\d\d)$/.test(s)){const d=new Date(new Date(s).getTime()+8*3600e3);return d.toISOString().slice(0,10)}
  return s.slice(0,10)
}

// ---------- N4 入库 / 出库场景分类（白名单，不猜） ----------
export interface Category {id:string;dir:'in'|'out';label:string;desc:string;types:string[]}
export const CATEGORIES:Category[]=[
  {id:'in-purchase',dir:'in',label:'采购入库',desc:'供应商来货收下入库，关联采购单号（黑湖采购单本身未接入，只显示关联号）。',types:['采购入库']},
  {id:'in-production',dir:'in',label:'生产入库',desc:'工单产出的成品或半成品入库；数量是入库数，不等于报工良品。',types:['成品入库']},
  {id:'in-return',dir:'in',label:'退料入库',desc:'生产或委外领出后退回仓库的物料，抵减领用。',types:['生产退料入库','委外退料入库','工单退货入库']},
  {id:'in-transfer',dir:'in',label:'调拨入库',desc:'从其他仓库调入；与对应调拨出库成对，在途不计入本仓。',types:['调拨入库']},
  {id:'in-other',dir:'in',label:'其他入库',desc:'普通入库、销售退货入库等不属于以上场景的入库，按来源原类型显示。',types:['普通入库','其它入库','销售退货入库']},
  {id:'out-sales',dir:'out',label:'销售出库',desc:'发给客户的成品出库。',types:['销售出库']},
  {id:'out-picking',dir:'out',label:'领料出库',desc:'生产工单领用原辅料出库，是“已发”数量，不等于实耗。',types:['生产领料出库']},
  {id:'out-transfer',dir:'out',label:'调拨出库',desc:'调往其他仓库；对方签收前属于在途。',types:['调拨出库']},
  {id:'out-other',dir:'out',label:'其他出库',desc:'采购退货出库、其它出库等，按来源原类型显示。',types:['采购退货出库','其它出库']}
]
export function categoryOf(type:unknown):Category|undefined{const t=String(type??'');return CATEGORIES.find(c=>c.types.includes(t))}
export function directionOf(type:unknown):'in'|'out'|undefined{
  const c=categoryOf(type);if(c)return c.dir
  const t=String(type??'');return t.endsWith('入库')?'in':t.endsWith('出库')?'out':undefined
}

// ---------- 库存状态：正 / 零 / 负 ----------
export type StockState='positive'|'zero'|'negative'|'invalid'
export function stockState(q:unknown):StockState{const n=toNum(q);if(n===undefined)return 'invalid';return n>0?'positive':n<0?'negative':'zero'}
export const STATE_LABEL:Record<StockState,string>={positive:'有余额',zero:'零库存',negative:'负库存',invalid:'格式无效'}

// 负库存核对方向：规则给出，不判缺料（规格 24 §5）
export function negativeDirections(row:Row,inv:Inventory):string[]{
  const pid=String(row.productId),wid=String(row.warehouseId),out:string[]=[]
  let picked=0,inbound=0
  for(const o of inv.orders){const dir=directionOf(o.bizTypeDisplay);for(const l of o.lines){if(String(l.productId)!==pid||String(l.detailWarehouseId)!==wid)continue;if(dir==='out'&&String(o.bizTypeDisplay)==='生产领料出库')picked++;if(dir==='in')inbound++}}
  if(picked>0&&inbound===0)out.push('本仓有 '+picked+' 行领料出库、没有任何入库：可能漏入库或调拨入库未过账')
  else if(picked>0)out.push('本仓领料出库 '+picked+' 行、入库 '+inbound+' 行：核对领料是否早于入库或调拨签收')
  const elsewhere=inv.stock.filter(s=>String(s.productId)===pid&&String(s.warehouseId)!==wid&&(toNum(s.qtyInWarehouse)??0)>0)
  if(elsewhere.length)out.push('同产品在 '+elsewhere.map(s=>String(s.warehouseName)).join('、')+' 有正余额：可能错仓（先领后调）')
  const units=new Set(inv.stock.filter(s=>String(s.productId)===pid).map(s=>unitKey(s.unitName)))
  for(const o of inv.orders)for(const l of o.lines)if(String(l.productId)===pid)units.add(unitKey(l.unitName))
  if(units.size>1)out.push('同产品出现多个单位（'+[...units].join('、')+'）：核对单位换算')
  if(!out.length)out.push('快照期间内没有可对照的出入库行：需在黑湖查该产品在本仓的历史流水')
  return out
}

// 黑湖产品档案 × 仓库档案 中，库存快照没有返回余额的组合（代码按当前快照计算，不写死数字）。
// 未返回 ≠ 0：可能是该仓从未存放，也可能是接口未返回，需黑湖对接人确认或在 Cretas 补充。
export interface Combo{productId:string;productCode:string;productName:string;unit:string;warehouseId:string;warehouseName:string;usedInDocs:boolean}
export function unreturnedCombos(inv:Inventory,products:Row[]):Combo[]{
  const have=new Set(inv.stock.map(s=>String(s.productId)+'@'+String(s.warehouseId)))
  const used=new Set<string>()
  for(const o of inv.orders)for(const l of o.lines)if(l.detailWarehouseId!=null)used.add(String(l.productId)+'@'+String(l.detailWarehouseId))
  const out:Combo[]=[]
  for(const p of products)for(const w of inv.warehouses){
    const k=String(p.id)+'@'+String(w.id)
    if(!have.has(k))out.push({productId:String(p.id),productCode:String(p.productCode??''),productName:String(p.productName??''),unit:String(p.unit??''),warehouseId:String(w.id),warehouseName:String(w.name??''),usedInDocs:used.has(k)})
  }
  return out.sort((a,b)=>Number(b.usedInDocs)-Number(a.usedInDocs))
}

// ---------- 明细行展开 ----------
export interface FlatLine {lineId:string;orderNo:string;bizType:string;category?:Category;dir?:'in'|'out';bizAt:string;productId:string;productCode:string;productName:string;qty:number|undefined;rawQty:unknown;unit:string;warehouseId:string;warehouseName:string;related:string;cost:unknown;order:Order}
export function flatten(inv:Inventory):FlatLine[]{
  const wh=new Map(inv.warehouses.map(w=>[String(w.id),String(w.name)]))
  const out:FlatLine[]=[]
  for(const o of inv.orders)for(const l of o.lines){
    const wid=l.detailWarehouseId==null?'':String(l.detailWarehouseId)
    out.push({lineId:String(l.id),orderNo:String(o.orderNo),bizType:String(o.bizTypeDisplay??''),category:categoryOf(o.bizTypeDisplay),dir:directionOf(o.bizTypeDisplay),bizAt:String(o.bizAt??''),productId:String(l.productId),productCode:String(l.productCode??''),productName:String(l.productName??''),qty:toNum(l.qty),rawQty:l.qty,unit:unitKey(l.unitName),warehouseId:wid,warehouseName:wid?wh.get(wid)||'仓库 '+wid:'未记录',related:l.associatedBizOrderCode==null||l.associatedBizOrderCode===''?'':String(l.associatedBizOrderCode),cost:l.realCostAmount,order:o})
  }
  return out
}

// ---------- 生产领退料（规格 27）：已发 / 退回 / 净领用；需求、实耗来源未提供 ----------
export interface FlowSum {productCode:string;productName:string;unit:string;issued:number;returned:number;net:number;lines:number;noRelated:number}
export const PICK_TYPES=['生产领料出库'],RETURN_TYPES=['生产退料入库']
export function flowSummary(lines:FlatLine[]):FlowSum[]{
  const m=new Map<string,FlowSum>()
  for(const l of lines){
    const isPick=PICK_TYPES.includes(l.bizType),isRet=RETURN_TYPES.includes(l.bizType)
    if(!isPick&&!isRet)continue
    const k=l.productId+'|'+l.unit
    const s=m.get(k)||{productCode:l.productCode,productName:l.productName,unit:l.unit,issued:0,returned:0,net:0,lines:0,noRelated:0}
    const q=l.qty??0
    if(isPick)s.issued=add(s.issued,q);else s.returned=add(s.returned,q)
    s.net=add(s.issued,-s.returned);s.lines++;if(!l.related)s.noRelated++
    m.set(k,s)
  }
  return [...m.values()].sort((a,b)=>b.issued-a.issued)
}

// ---------- N6 进销存汇总：期初只取快照，绝不倒推 ----------
export interface SummaryRow {key:string;productCode:string;productName:string;warehouseName:string;unit:string;opening:string;inByCat:Record<string,number>;outByCat:Record<string,number>;inTotal:number;outTotal:number;closing:number|undefined;closingNote:string}
export function periodSummary(inv:Inventory,from:string,to:string,lines:FlatLine[]):{rows:SummaryRow[];snapshotDate:string;closingAvailable:boolean}{
  const snapshotDate=localDate(inv.materialAt)
  const closingAvailable=to===snapshotDate
  const m=new Map<string,SummaryRow>()
  const keyOf=(pid:string,wid:string,unit:string)=>pid+'@'+wid+'|'+unit
  for(const l of lines){
    const d=localDate(l.bizAt);if(!d||d<from||d>to||!l.category)continue
    const k=keyOf(l.productId,l.warehouseId,l.unit)
    const r=m.get(k)||{key:k,productCode:l.productCode,productName:l.productName,warehouseName:l.warehouseName,unit:l.unit,opening:'无期初快照',inByCat:{},outByCat:{},inTotal:0,outTotal:0,closing:undefined,closingNote:''}
    const q=l.qty??0,c=l.category
    if(c.dir==='in'){r.inByCat[c.id]=add(r.inByCat[c.id]||0,q);r.inTotal=add(r.inTotal,q)}else{r.outByCat[c.id]=add(r.outByCat[c.id]||0,q);r.outTotal=add(r.outTotal,q)}
    m.set(k,r)
  }
  // 期末：只有期末日等于快照日时，才用快照在库；单位必须一致
  const wh=new Map(inv.warehouses.map(w=>[String(w.id),String(w.name)]))
  for(const s of inv.stock){
    const k=keyOf(String(s.productId),String(s.warehouseId),unitKey(s.unitName))
    let r=m.get(k)
    if(!r&&closingAvailable){r={key:k,productCode:String(s.productCode??''),productName:String(s.productName??''),warehouseName:String(s.warehouseName??wh.get(String(s.warehouseId))??''),unit:unitKey(s.unitName),opening:'无期初快照',inByCat:{},outByCat:{},inTotal:0,outTotal:0,closing:undefined,closingNote:''};m.set(k,r)}
    if(r&&closingAvailable)r.closing=toNum(s.qtyInWarehouse)
  }
  for(const r of m.values())r.closingNote=closingAvailable?(r.closing===undefined?'快照未返回该组合余额':''):'无期末快照'
  return {rows:[...m.values()].sort((a,b)=>a.productName.localeCompare(b.productName,'zh')||a.warehouseName.localeCompare(b.warehouseName,'zh')),snapshotDate,closingAvailable}
}

// CSV：防公式注入
export function csvCell(v:unknown){let t=v==null?'':String(v);if(/^[=+@-]/.test(t)&&!/^-?\d+(?:\.\d+)?$/.test(t))t="'"+t;return '"'+t.replaceAll('"','""')+'"'}
export function downloadCsv(name:string,head:string[],rows:unknown[][]){
  const content=[head.map(csvCell).join(','),...rows.map(r=>r.map(csvCell).join(','))].join('\n')
  const url=URL.createObjectURL(new Blob(['﻿'+content],{type:'text/csv;charset=utf-8'})),a=document.createElement('a')
  a.href=url;a.download=name;a.click();URL.revokeObjectURL(url)
}
