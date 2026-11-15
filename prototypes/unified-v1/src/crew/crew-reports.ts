// F 线 · 主动汇报：数字员工扫描现有数据后主动推的事。每条带依据（可展开）、「转成待办」（生成预览进等我确认）和关闭（选结论再写一句）。
// R30：先把录入错误归为「数据疑点 · 找谁核」，不当业务异常、不上首页头条；只有通过合理性检查的变化才算「问题」。
// 本原型在打开页面时按快照现算（R28：按快照日），不是后台定时；定时与推送见缺口清单。
import {scanYieldDrops,rushCheck,workOrders,pct,qty,type Src,type Inv,type YieldSeries} from './crew-facts'
import {yieldDraft,type Draft} from './crew-drafts'
import {today} from './crew-store'
import type {CrewId} from './crew-config'
export type ReportLevel='问题'|'提醒'|'数据疑点'|'说明'
export interface Report{key:string;employee:CrewId;level:ReportLevel;title:string;summary:string;basis:string[];at:string;owner?:string;draft?:()=>Draft;series?:YieldSeries;link?:{label:string;route:string}}
// 首页卡片与「发现问题」计数只算问题与提醒；数据疑点单独计数，说明不计
export const COUNTED:ReportLevel[]=['问题','提醒']

export function buildReports(src:Src|undefined,inv:Inv|null):Report[]{
  if(!src)return []
  const at=new Date(Date.parse(src.checkedAt)+8*3600e3).toISOString().slice(0,16).replace('T',' '),out:Report[]=[],scan=scanYieldDrops(src)
  const line=(s:YieldSeries)=>s.points.map(p=>p.code+' · '+p.date+' · '+s.fromProc+' '+qty(p.from,p.fromUnit||'kg')+' → '+s.toProc+' '+qty(p.to,p.toUnit||'kg')+' · '+(p.ratio==null?p.reason:pct(p.ratio)+(p.suspect?'（数据疑点：'+p.suspect+'）':'')))
  // ① 出成下降（数字成本会计）：已排除数据疑点
  for(const s of scan.drops){const d=s.drop!
    out.push({key:'yield:'+s.productCode+':'+s.fromProc+':'+s.toProc+':'+d.to.code,employee:'accountant',level:'问题',at,series:s,owner:'生产主管',
      title:'出成下降 · '+s.productName+' '+s.fromProc+'→'+s.toProc,summary:pct(d.from.ratio)+' → '+pct(d.to.ratio)+'（'+d.from.code+' → '+d.to.code+'，降 '+Math.abs(d.deltaPts).toFixed(2)+' 个百分点；已排除数据疑点）',
      basis:line(s),draft:()=>yieldDraft(src,s,'发现的问题')})}
  // ② 数据疑点（数字成本会计发现，找车间核报工）
  for(const x of scan.suspects){const s=x.series
    out.push({key:'suspect:'+s.productCode+':'+s.fromProc+':'+s.toProc,employee:'accountant',level:'数据疑点',at,series:s,owner:'车间（核报工）',
      title:'数据疑点 · '+s.productName+' '+s.fromProc+'→'+s.toProc,summary:x.reasons.length+' 处报工不合理，找车间核：'+x.reasons.slice(0,2).join('；')+(x.reasons.length>2?'…':''),basis:[...x.reasons,'—— 全部工单 ——',...line(s)]})}
  if(scan.passThrough.length)out.push({key:'passthrough',employee:'accountant',level:'说明',at,owner:'车间',title:'报工前后数量一样：不能算出成',
    summary:scan.passThrough.length+' 个工序对每张工单的前后良品都一样（出成 100%），像没称重直接录入，不能用来算出成；称重报工上线后再看',
    basis:scan.passThrough.map(s=>s.productName+' '+s.fromProc+'→'+s.toProc+'（'+s.points.filter(p=>p.ratio!=null).length+' 张）')})
  // ③ 库存与生产对不上（数字仓管）：快照日前 7 天在生产、BOM 投入在库存快照里全是 0
  if(inv){const t=today(),since=new Date(Date.parse(t)-6*864e5).toISOString().slice(0,10),seen=new Set<string>()
    for(const w of workOrders(src).filter(w=>w.planDate>=since&&w.planDate<=t)){if(seen.has(w.productCode))continue;seen.add(w.productCode)
      const r=rushCheck(src,inv,{productCode:w.productCode,qty:0,date:t,today:t});if(!(r.lines.length&&r.lines.every(l=>l.stock===0)))continue
      out.push({key:'stock0:'+w.productCode,employee:'keeper',level:'提醒',at,owner:'仓库',title:'原料库存为 0 但在生产 · '+w.productName,summary:'BOM '+r.lines.length+' 项原料在黑湖库存快照里都是 0，近 14 天（按快照日）有 '+r.history.days.length+' 天生产',
        basis:r.lines.map(l=>l.name+'：'+l.warehouses),link:{label:'打开库存查询',route:'/warehouse/inventory-query'}})}}
  // ④ 工序超时：没有标准工时就不判断，只说明（不画假结论）
  out.push({key:'overtime:none',employee:'planner',level:'说明',at,title:'工序超时：暂不能判断',summary:'工序标准工时要在「在制品与效率」里补充，当前没有可用标准；黑湖报工的起止时间多为录入值。补上标准后计划员才汇报超时。',basis:['在制品与效率 · 标准工时页签'],link:{label:'打开在制品与效率',route:'/production/wip?tab=eff'}})
  return out
}
