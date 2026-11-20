// 第四批② BL20 产品追溯「一键倒查」（R17）：产品＋时间范围 → 工单 → 报工 / 成品入库 / 领料物料；同期销售出库 → 销售订单 → 客户。
// 只用黑湖返回的直接关联字段（单据行 associatedBizOrderCode、出库行产品、销售订单客户）；任何一环接不上就写「缺：原因」，
// 不按产品名、日期相近或单号前缀推断补链。数据时点逐个写明。
import type {Row} from '../frame/types'

export interface TraceInput{productCode:string;from:string;to:string
  projects:Row[];outputs:Row[];products:Row[];orders:Row[];warehouses:Row[];saleOrders:Row[];customers:Row[]
  at:{source?:string;inventory?:string;extras?:string}
  /** 工单状态码转中文（由页面传入共享的 stateLabel）；不传时写「状态码 N」 */
  statusLabel?:(v:unknown)=>string
  /** 第六批：Cretas 入库批次，键＝黑湖入库单号#行 ID；有批次的成品入库行接上批次（R17：接不上仍写缺） */
  batches?:Map<string,{batchNo:string;expiryDate:string;productionDate:string}[]>}
export interface TraceLine{step:string;no:string;date:string;item:string;qty:number|null;unit:string;link:string;gap:string}
export interface TraceWork{project:Row;start:string;reports:{count:number;processes:number};inbound:TraceLine[];picks:TraceLine[];returns:TraceLine[];saleGap:string}
export interface TraceResult{product:{code:string;name:string;unit:string}|null;from:string;to:string;at:TraceInput['at'];works:TraceWork[];shipments:TraceLine[];gaps:{step:string;count:number;reason:string}[];rows:TraceLine[]}

const day=(x:unknown)=>x?String(x).slice(0,10):''
const n=(x:unknown)=>{const v=typeof x==='number'?x:typeof x==='string'&&/^\s*-?\d+(\.\d+)?\s*$/.test(x)?Number(x):NaN;return Number.isFinite(v)?v:null}
const inR=(d:string,from:string,to:string)=>!!d&&(!from||d>=from)&&(!to||d<=to)
export const GAP={pickBatch:'缺：领料无批次（黑湖出入库单行没有批次号），不能定到哪一次采购入库',reportBatch:'缺：报工无批次号，不能定到具体成品批次',
  shipBatch:'缺：出库无批次，不能定到是哪张工单生产的',workSale:'缺：黑湖工单未返回来源销售单字段',noWork:'缺：时间范围内没有该产品的工单',noShip:'时间范围内没有该产品的销售出库'}

export function traceProduct(i:TraceInput):TraceResult{
  const code=i.productCode.trim()
  const p=i.products.find(x=>String(x.productCode)===code)
  const wh=new Map(i.warehouses.map(w=>[String(w.id??w.warehouseId),String(w.name??w.warehouseName??'')]))
  const so=new Map(i.saleOrders.map(o=>[String(o.orderNo),o]))
  const cust=new Map(i.customers.map(c=>[String(c.id),c]))
  const lineOf=(step:string,o:Row,l:Row,link:string,gap:string):TraceLine=>({step,no:String(o.orderNo),date:day(o.bizAt),item:String(l.productCode??'')+' '+String(l.productName??''),qty:n(l.qty),unit:String(l.unitName??''),link:link+(l.detailWarehouseId!=null?' · '+(wh.get(String(l.detailWarehouseId))||'仓库 '+l.detailWarehouseId):''),gap})
  const works:TraceWork[]=i.projects.filter(x=>String(x.productCode)===code&&inR(day(x.actualStartTime??x.planStartTime),i.from,i.to)).sort((a,b)=>day(a.actualStartTime??a.planStartTime).localeCompare(day(b.actualStartTime??b.planStartTime))).map(pr=>{
    const pc=String(pr.projectCode),pid=String(pr.id)
    const reps=i.outputs.filter(o=>String(o.projectId)===pid)
    const lines=(type:string)=>i.orders.filter(o=>String(o.bizTypeDisplay)===type).flatMap(o=>((o.lines as Row[])||[]).filter(l=>String(l.associatedBizOrderCode??'')===pc).map(l=>({o,l})))
    // 工单号形如「S20260007-001-001」时只作提示，不作为关联依据（R17）
    const hint=/^S\d{8}-/.test(pc)&&so.has(pc.split('-')[0])?'（工单号含销售单号 '+pc.split('-')[0]+'，未作为关联依据）':''
    return {project:pr,start:day(pr.actualStartTime??pr.planStartTime),
      // 各工序分别报工，良品数不跨工序相加（黑湖快照口径：不叠加工单、任务和报工数量）
      reports:{count:reps.length,processes:new Set(reps.map(r=>String(r.processId))).size},
      inbound:lines('成品入库').map(({o,l})=>{const bs=i.batches?.get(String(o.orderNo)+'#'+String(l.id));const x=lineOf('成品入库',o,l,'工单 '+pc,bs?.length?'':GAP.reportBatch);if(bs?.length)x.link+=' · Cretas 批次 '+bs.map(b=>b.batchNo+'（生产 '+b.productionDate+'，到期 '+b.expiryDate+'）').join('、');return x}),
      picks:lines('生产领料出库').map(({o,l})=>lineOf('领料物料',o,l,'工单 '+pc,GAP.pickBatch)),
      returns:lines('生产退料入库').map(({o,l})=>lineOf('退料',o,l,'工单 '+pc,GAP.pickBatch)),
      saleGap:GAP.workSale+hint}})
  const shipments:TraceLine[]=i.orders.filter(o=>String(o.bizTypeDisplay)==='销售出库'&&inR(day(o.bizAt),i.from,i.to)).flatMap(o=>((o.lines as Row[])||[]).filter(l=>String(l.productCode)===code).map(l=>{
    const sno=String(l.associatedBizOrderCode??''),s=so.get(sno),c=s?cust.get(String(s.customerId)):undefined
    const link=sno?(s?'销售订单 '+sno+' → 客户 '+String(s.customerName??c?.name??'未返回'):'关联号 '+sno+'（黑湖新接口里没有这张销售单）'):'缺：出库行没有关联销售单'
    return lineOf('销售出库',o,l,link,GAP.shipBatch)}))
  const rows:TraceLine[]=[]
  if(!works.length)rows.push({step:'工单',no:'',date:'',item:code,qty:null,unit:'',link:'',gap:GAP.noWork})
  for(const w of works){const pr=w.project
    rows.push({step:'工单',no:String(pr.projectCode),date:w.start,item:String(pr.productName??''),qty:n(pr.actualAmount),unit:String(pr.projectUnit??''),link:'计划 '+String(n(pr.planAmount)??'未返回')+' · '+(i.statusLabel?i.statusLabel(pr.status):'状态码 '+String(pr.status??'未返回')),gap:w.saleGap})
    rows.push({step:'报工',no:String(pr.projectCode),date:'',item:String(pr.productName??''),qty:null,unit:'',link:w.reports.count+' 条报工，涉及 '+w.reports.processes+' 道工序（各工序良品不相加）',gap:w.reports.count?GAP.reportBatch:'缺：黑湖没有该工单的报工'})
    rows.push(...(w.inbound.length?w.inbound:[{step:'成品入库',no:'',date:'',item:'',qty:null,unit:'',link:'工单 '+pr.projectCode,gap:'缺：没有关联到该工单的成品入库单'}]))
    rows.push(...(w.picks.length?w.picks:[{step:'领料物料',no:'',date:'',item:'',qty:null,unit:'',link:'工单 '+pr.projectCode,gap:'缺：没有关联到该工单的领料出库单'}]),...w.returns)}
  rows.push(...(shipments.length?shipments:[{step:'销售出库',no:'',date:'',item:code,qty:null,unit:'',link:'',gap:GAP.noShip}]))
  const gaps:TraceResult['gaps']=[]
  const add=(step:string,count:number,reason:string)=>{if(count)gaps.push({step,count,reason})}
  add('领料物料',works.reduce((s,w)=>s+w.picks.length,0),GAP.pickBatch)
  add('成品入库',works.reduce((s,w)=>s+w.inbound.filter(x=>x.gap).length,0),GAP.reportBatch+'（建了 Cretas 批次的入库行已接上）')
  add('工单 → 销售订单',works.length,GAP.workSale)
  add('销售出库 → 工单',shipments.length,GAP.shipBatch)
  add('销售出库 → 销售订单',shipments.filter(x=>!x.link.startsWith('销售订单')).length,'缺：出库行没有关联销售单，或关联号不在黑湖新接口的销售单里')
  return {product:p?{code,name:String(p.productName??''),unit:String(p.unit??'')}:null,from:i.from,to:i.to,at:i.at,works,shipments,gaps,rows}
}

/** 审计退回 2：时点一律北京时间（UTC+8），精确到秒 */
export function bjTime(x:unknown):string{if(!x)return '未返回';const t=Date.parse(String(x));if(!Number.isFinite(t))return String(x);return new Date(t+8*3600e3).toISOString().slice(0,19).replace('T',' ')+'（北京时间）'}
export function traceCsv(r:TraceResult):string[][]{
  const head=[['产品追溯报告（一键倒查）'],['产品',r.product?r.product.code+' '+r.product.name:'未找到该产品编码'],['时间范围',(r.from||'不限')+' 至 '+(r.to||'不限')],
    ['数据时点','黑湖主快照 '+bjTime(r.at.source)+'；出入库单据快照 '+bjTime(r.at.inventory)+'；黑湖新接口 '+bjTime(r.at.extras)],
    ['口径','只用黑湖直接关联字段；接不上的环节写「缺：原因」，不按产品名、日期或单号前缀补链'],[],['环节','单号','日期','物料 / 产品','数量','单位','关联','缺失环节']]
  return [...head,...r.rows.map(x=>[x.step,x.no,x.date,x.item,x.qty==null?'':String(x.qty),x.unit,x.link,x.gap])]
}
