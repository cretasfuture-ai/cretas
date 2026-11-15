// F 线 · 写操作草案：把对话或主动汇报里算出的事实，整理成「等我确认」的预览（每一行都显示，不藏）+ 确认后要调用的现有接口。
// 现有接口只有 E 线协同任务（/__unified/e/api/tasks/save）能在测试会话里真写；黑湖开单、工艺保存等写不了的，在 notDone 里写清。
import {pct,qty,extraUse,type YieldSeries,type Src} from './crew-facts'
import type {Preview,InboxAction} from './crew-store'
import {realToday} from './crew-store'
import type {CrewId} from './crew-config'
export interface Draft{employee:CrewId;kind:string;title:string;module:string;sourceKey:string;context?:string;preview:Preview;action:InboxAction}
const addDays=(d:string,n:number)=>new Date(Date.parse(d)+n*864e5).toISOString().slice(0,10)

/* eslint-disable @typescript-eslint/no-explicit-any */
export function rushDraft(d:{input:{productCode:string;productName:string;qty:number;date:string};result:any},context=''):Draft{
  const {input:i,result:r}=d,qtyText=qty(i.qty,'kg')
  const desc=['数字计划员按急单核对生成，负责人确认后交车间：',
    '产品 '+i.productName+'（'+i.productCode+'），加 '+qtyText+'，'+i.date+' 生产。',
    '物料：'+r.material.state+'。'+(r.material.text||''),
    '产出参考：近 14 天单日最多 '+(r.history.maxDay?qty(r.history.maxDay.qty,'kg')+'（'+r.history.maxDay.date+'）':'无记录')+'；六膳门没有配置日产能（正式系统有产能配置和急单插单分析，原型还没接）。',
    '原有安排：'+i.date+' 黑湖已开工单 '+r.sameDay.length+' 张。',
    '要人办的：① 仓库核实原料实际库存；② 在黑湖开工单（Cretas 不开单）；③ 开好后在协同任务里回复工单号。'].join('\n')
  return {employee:'planner',kind:'rush-plan',module:'production',title:('急单安排 · '+i.productName+' '+qtyText+' · '+i.date).slice(0,80),sourceKey:'rush:'+i.productCode+':'+i.date+':'+i.qty,context,
    preview:{rows:[{label:'产品',value:i.productName+'（'+i.productCode+'）'},{label:'加单数量',value:qtyText},{label:'生产日期',value:i.date},
      {label:'物料核对',value:r.material.state+(r.material.text?'：'+r.material.text:'')},
      {label:'BOM 投入',value:r.lines.map((l:any)=>l.name+' 需 '+qty(l.need,'kg')+'，库存 '+(l.stock==null?'未返回':qty(l.stock,l.stockUnit||'kg'))).join('；')||'无'},
      {label:'产出参考',value:'近 14 天 '+r.history.days.length+' 天生产，单日最多 '+(r.history.maxDay?qty(r.history.maxDay.qty,'kg'):'无记录')},
      {label:'产能',value:r.capacity.text},{label:'原有安排',value:i.date+' 黑湖已开工单 '+r.sameDay.length+' 张'},
      {label:'确认后执行',value:'建一条协同任务交「生产主管」，截止 '+i.date+'，优先级 高；记「已交办」，生产主管在黑湖开好工单、任务完成后才算已办成'}],
      impact:['原有工单不改动（Cretas 不改黑湖工单）','车间当天多一张 '+qtyText+' 的工单，需要仓库先核实原料'],
      basis:['黑湖快照 '+r.snapshot.source,'库存快照 '+r.snapshot.inventory,'BOM '+r.lines.length+' 行（'+(r.alt?'疑似替代料':'按用量相加')+'）'],
      notDone:['不在黑湖开工单：工单在黑湖开，Cretas 只读','不判断产能：六膳门没有配置日产能']},
    // F-16：用 E 线新增的任务类型「急单」（rush）。工单还没开，订单 / 工单号如实写「待在黑湖开单」
    action:{type:'e-task',payload:{type:'rush',fields:{order:'待在黑湖开单',product:i.productName.slice(0,60),qty:i.qty,unit:'kg',needBy:i.date},title:('急单 · '+i.productName+' '+qtyText+' · '+i.date.slice(5)).slice(0,60),description:desc,assignee:{kind:'role',role:'生产主管'},due:i.date,priority:'高',related:[{type:'product',code:i.productCode}],origin:{kind:'draft',ref:'数字计划员 · 急单',draftBy:'数字计划员'}}}}
}
export function yieldDraft(src:Src,s:YieldSeries,context=''):Draft{
  const dr=s.drop!,x=extraUse(s),due=addDays(realToday(),2)
  const desc=['数字成本会计按报工良品对比生成：',
    s.productName+' 「'+s.fromProc+' → '+s.toProc+'」出成 '+dr.from.code+' '+pct(dr.from.ratio)+' → '+dr.to.code+' '+pct(dr.to.ratio)+'（降 '+Math.abs(dr.deltaPts).toFixed(2)+' 个百分点）。',
    x?x.formula+'，多用 '+x.extra.toFixed(2)+' kg。':'',
    '请核对：① '+dr.to.code+' 的'+s.fromProc+'、'+s.toProc+'报工是否按实称重（黑湖报工常为录入值）；② '+s.toProc+'当班原料规格、时长是否有变化；③ 核完在本任务回复结论。'].filter(Boolean).join('\n')
  return {employee:'accountant',kind:'yield-check',module:'analytics',title:('费料核对 · '+s.productName+' '+s.fromProc+'→'+s.toProc+' '+pct(dr.from.ratio)+'→'+pct(dr.to.ratio)).slice(0,80),sourceKey:'yield:'+s.productCode+':'+s.fromProc+':'+s.toProc+':'+dr.to.code,context,
    preview:{rows:[{label:'产品',value:s.productName+'（'+s.productCode+'）'},{label:'对比环节',value:s.fromProc+' → '+s.toProc+'（后道良品 ÷ 前道良品）'},
      {label:'下降前',value:dr.from.code+' · '+dr.from.date+' · '+s.fromProc+' '+qty(dr.from.from,'kg')+' → '+s.toProc+' '+qty(dr.from.to,'kg')+' · '+pct(dr.from.ratio)},
      {label:'下降后',value:dr.to.code+' · '+dr.to.date+' · '+s.fromProc+' '+qty(dr.to.from,'kg')+' → '+s.toProc+' '+qty(dr.to.to,'kg')+' · '+pct(dr.to.ratio)},
      {label:'多用物料',value:x?x.extra.toFixed(2)+' kg（'+x.formula+'）':'无法计算'},
      {label:'可算工单',value:s.points.filter(p=>p.ratio!=null).length+' / '+s.points.length+' 张'},
      {label:'确认后执行',value:'建一条协同任务交「生产主管」核对，截止 '+due+'，关联 '+dr.from.code+'、'+dr.to.code+'；记「已交办」，核对完才算已办成'}],
      impact:['只生成核对待办，不改报工、不改成本'],basis:['黑湖报工良品（快照 '+new Date(Date.parse(src.checkedAt)+8*3600e3).toISOString().slice(0,16).replace('T',' ')+'）'],
      notDone:['不算费料金额：材料单价按工单汇总，拆不到这一道工序','黑湖「出成率 1.00 / 计算初成」不作依据']},
    // F-16：用 E 线新增的任务类型「费料核对」（materialCheck）：物料 = 前道工序的产出，标准用量 = 按上一单出成应用量，实际用量 = 实际报工
    action:{type:'e-task',payload:{type:'materialCheck',fields:{material:(s.fromProc+' · '+s.productName.replace('叮咚好食光','')).slice(0,60),order:dr.to.code,standardQty:x?x.expected:null,actualQty:x?x.actual:null,unit:'kg'},title:('费料核对 · '+s.productName.replace('叮咚好食光','')+' '+s.toProc).slice(0,60),description:desc,assignee:{kind:'role',role:'生产主管'},due,priority:'中',related:[{type:'project',code:dr.from.code},{type:'project',code:dr.to.code}],origin:{kind:'draft',ref:'数字成本会计 · 费料',draftBy:'数字成本会计'}}}}
}
