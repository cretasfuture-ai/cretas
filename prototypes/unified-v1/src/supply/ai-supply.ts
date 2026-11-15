// C 线 AI 对象（DECISIONS A3）：向 D 的唯一悬浮窗 AiFloat 注册 stock（当前库存筛选）与 document（一张出入库单）。
// 本轮只提供系统计算的依据（facts），不提供 explain：悬浮窗写明「本对象只提供系统计算」。
// 数量、零/负/未返回、单位分组全部由代码算；不调用模型、不改任何数据。
import {registerAiObject,setAiObject} from '../frame/ai-objects'
import type {AiExplain} from '../frame/ai-objects'
import type {AiFact} from '../frame/ai-protocol'
import {T,activeRecords,post} from './supply-store'
import {stockState,toNum,add,categoryOf,directionOf,unitKey,fmtTime} from './supply-facts'

// stock 的 id：'wh=<仓库ID>|state=<状态>|q=<关键词>'，可解析回筛选
export const stockId=(wh:string,state:string,q:string)=>'wh='+wh+'|state='+state+'|q='+q
function parse(id:string){const o:Record<string,string>={};for(const p of id.split('|')){const i=p.indexOf('=');o[p.slice(0,i)]=p.slice(i+1)}return {wh:o.wh||'',state:o.state||'',q:o.q||''}}
const STATE:Record<string,string>={'':'全部状态',positive:'有余额',zero:'零库存',negative:'负库存',invalid:'格式无效'}
function stockRows(id:string){const f=parse(id);return (T.inv?.stock||[]).filter(s=>(!f.wh||String(s.warehouseId)===f.wh)&&(!f.state||stockState(s.qtyInWarehouse)===f.state)&&(!f.q||(String(s.productCode)+String(s.productName)).toLowerCase().includes(f.q.toLowerCase())))}
// 第二批③：严格模式解读（服务端 supply-ai.cjs：事实与允许的核对代码由代码算，模型只选代码，正文由代码渲染）
interface ExplainRes{model:string;modelNote?:string;conclusion:string;actions:{code:string;text:string;who:string}[];at:string;objectRev?:string;ms?:number}
export async function explainOf(kind:'stock'|'document'|'extra',id:string,question:string):Promise<AiExplain>{
  const r=await post<ExplainRes>('/explain',{kind,id,question})
  const src=r.model==='ok'?'model' as const:'system' as const
  return {state:r.model==='rejected'?'rejected':r.model==='unavailable'?'unavailable':'answered',conclusion:r.conclusion,items:r.actions.map(a=>({text:a.text,who:a.who,source:src})),rejected:[],note:r.modelNote||(r.model==='ok'?'核对项由解读服务从系统允许的范围内选出；数字和正文均由系统生成。':''),at:r.at,objectRev:r.objectRev,ms:r.ms}
}
const stale=(a:AiExplain)=>Boolean(T.inv&&a.objectRev&&a.objectRev!==T.inv.revision)
let registered=false
export function registerSupplyAi(){
  if(registered)return;registered=true
  registerAiObject('stock',{noun:'当前库存筛选',placeholder:'例如：这些负库存先找谁核对？',explain:(id,o)=>explainOf('stock',id,o.question),isStale:(_id,a)=>stale(a),
    describe:id=>{if(!T.inv)return undefined;const f=parse(id),w=T.inv.warehouses.find(x=>String(x.id)===f.wh);return '库存 · '+(w?String(w.name):'全部仓库')+' · '+STATE[f.state]+(f.q?' · “'+f.q+'”':'')},
    facts:id=>{
      const rows=stockRows(id),c={positive:0,zero:0,negative:0,invalid:0};const byUnit=new Map<string,number>()
      for(const r of rows){c[stockState(r.qtyInWarehouse)]++;const n=toNum(r.qtyInWarehouse);if(n!==undefined)byUnit.set(unitKey(r.unitName),add(byUnit.get(unitKey(r.unitName))||0,n))}
      const f=parse(id),w=T.inv?.warehouses.find(x=>String(x.id)===f.wh)
      const sup=activeRecords('stock').filter(r=>(!f.wh||r.warehouseId===f.wh)&&!(T.inv?.stock||[]).some(s=>String(s.productId)+'@'+String(s.warehouseId)===r.key))
      const facts:AiFact[]=[{label:'筛选范围',value:(w?String(w.name):'全部仓库')+' · '+STATE[f.state]+(f.q?' · 关键词“'+f.q+'”':''),kind:'scope'},{label:'来源',value:'黑湖只读快照（不回写黑湖）',kind:'source:blacklake'},{label:'快照时点',value:fmtTime(T.inv?.materialAt)},{label:'黑湖返回行数',value:rows.length},{label:'有余额',value:c.positive},{label:'零库存',value:c.zero,status:c.zero?'warning':undefined},{label:'负库存',value:c.negative,status:c.negative?'danger':undefined}]
      for(const [u,v] of byUnit)facts.push({label:'在库合计（'+u+'）',value:v,unit:u})
      facts.push({label:'范围内 Cretas 补充余额（黑湖未返回的组合）',value:sup.length,kind:'source:cretas'})
      for(const r of sup)facts.push({label:'Cretas 补充：'+r.key,value:Number(r.value.qty),unit:String(r.value.unit),kind:'source:cretas'})
      facts.push({label:'可用 / 占用 / 待检 / 冻结',value:null},{label:'说明',value:'不同单位分开合计；在库不等于可用；负库存只给核对方向，不判缺料'})
      return facts as AiFact[]
    }})
  registerAiObject('document',{noun:'这张出入库单',placeholder:'例如：这张单的数量和库存对得上吗？',explain:(id,o)=>explainOf('document',id,o.question),isStale:(_id,a)=>stale(a),
    describe:id=>{const o=T.inv?.orders.find(x=>String(x.orderNo)===id);return o?'单据 '+id+' · '+(categoryOf(o.bizTypeDisplay)?.label||String(o.bizTypeDisplay)):undefined},
    facts:id=>{
      const o=T.inv?.orders.find(x=>String(x.orderNo)===id);if(!o)return []
      const byUnit=new Map<string,number>();let noRel=0,noCost=0
      for(const l of o.lines){const n=toNum(l.qty);if(n!==undefined)byUnit.set(unitKey(l.unitName),add(byUnit.get(unitKey(l.unitName))||0,n));if(!l.associatedBizOrderCode)noRel++;if(l.realCostAmount==null||l.realCostAmount==='')noCost++}
      const lineIds=new Set(o.lines.map(l=>String(l.id))),supB=activeRecords('lineBatch').filter(r=>lineIds.has(r.key)).length,supC=activeRecords('lineCost').filter(r=>lineIds.has(r.key)).length
      const facts:AiFact[]=[{label:'来源',value:'黑湖已审批单据（只读）',kind:'source:blacklake'},{label:'场景',value:categoryOf(o.bizTypeDisplay)?.label||'未分类'},{label:'原类型',value:String(o.bizTypeDisplay)},{label:'方向',value:directionOf(o.bizTypeDisplay)==='in'?'入库':'出库'},{label:'业务时间',value:fmtTime(o.bizAt)},{label:'来源状态',value:String(o.orderStatusDisplay??'')||null},{label:'明细行',value:o.lines.length}]
      for(const [u,v] of byUnit)facts.push({label:'数量合计（'+u+'）',value:v,unit:u})
      facts.push({label:'无关联单号的行',value:noRel},{label:'黑湖未返回金额的行',value:noCost},{label:'Cretas 补充金额的行',value:supC,kind:'source:cretas'},{label:'Cretas 补充批次号的行',value:supB,kind:'source:cretas'},{label:'黑湖批次号',value:null})
      return facts as AiFact[]
    }})
}
export const selectStock=(wh:string,state:string,q:string)=>setAiObject({kind:'stock',id:stockId(wh,state,q)},'库存查询')
export const selectDocument=(orderNo:string)=>setAiObject({kind:'document',id:orderNo},'出入库单据')
