import type {BlacklakeAnalysis} from '@/api/smartbi/blacklake';

export interface OperationsFinding {code:string;kind:'data'|'check'|'gap';title:string;summary:string;possibleCauses:string;owner:string;action:string;keys:string[]}
const finite=(v:unknown):v is number=>typeof v==='number'&&Number.isFinite(v);
const sumKnown=(rows:(number|null|undefined)[])=>rows.length&&rows.every(finite)?rows.reduce<number>((a,b)=>a+(b as number),0):null;
const same=(a:number|null|undefined,b:number|null|undefined)=>a==null&&b==null||finite(a)&&finite(b)&&Math.abs(a-b)<=1e-7*Math.max(1,Math.abs(a),Math.abs(b));
export const planIdentity=(row:BlacklakeAnalysis['production']['planActual'][number])=>JSON.stringify([row.productCode??null,row.product,row.unit]);
export const processIdentity=(row:BlacklakeAnalysis['production']['processes'][number])=>JSON.stringify([row.processId??null,row.process]);

export function reviewProduction(data:BlacklakeAnalysis){
  const projects=data.cost.projects,findings:OperationsFinding[]=[];
  const add=(code:string,kind:OperationsFinding['kind'],title:string,summary:string,possibleCauses:string,owner:string,action:string,keys:string[]=[])=>{findings.push({code,kind,title,summary,possibleCauses,owner,action,keys});};
  const statuses=projects.reduce<Record<string,number>>((a,p)=>{a[p.status]=(a[p.status]||0)+1;return a;},{});
  if(data.production.projectCount!==projects.length||Object.values(data.production.statuses).reduce((a,b)=>a+b,0)!==projects.length||Object.entries(statuses).some(([key,count])=>data.production.statuses[key]!==count))
    add('production_reconcile','data','工单汇总与明细对不上','先核对工单范围，不据此判断生产完成情况。','可能混用了不同日期或不同批次的资料。','生产主管 / 数据管理员','刷新同一日期的资料，逐张核对工单编号和状态。');
  const plans=data.production.planActual.map(row=>{
    const orders=projects.filter(p=>p.product===row.product&&p.unit===row.unit&&(row.productCode!==undefined?p.productCode===row.productCode:true));
    const ambiguous=row.productCode===undefined&&new Set(orders.map(p=>p.productCode)).size>1;
    const key=planIdentity(row),planned=sumKnown(orders.map(p=>p.planned)),actual=sumKnown(orders.map(p=>p.actual));
    return {...row,reviewKey:key,orderIds:ambiguous?[]:orders.map(p=>p.projectId),identityAmbiguous:ambiguous,
      finishedGood:ambiguous?null:sumKnown(orders.map(p=>p.finishedGood)),
      gap:row.unit&&finite(row.actual)&&finite(row.planned)?row.actual-row.planned:null,
      mismatch:ambiguous||orders.length!==row.projects||finite(row.planned)&&row.planned<0||finite(row.actual)&&row.actual<0||!same(planned,row.planned)||!same(actual,row.actual)};
  });
  const missing=plans.filter(p=>!p.unit||p.plannedMissing>0||p.actualMissing>0);
  if(missing.length)add('production_missing','gap',`${missing.length} 组产品的单位或数量不齐`,'数量或单位未提供的产品不计算计划差额。','可能尚未报工、数量未回填，或接口未提供该字段；需核对原单。','生产主管 / 报工负责人','定位产品，核对计量单位、计划量、实际产量、完工状态和报工审批。',missing.map(p=>p.reviewKey));
  const mismatch=plans.filter(p=>p.mismatch);
  if(mismatch.length)add('production_identity','data',`${mismatch.length} 组产品的汇总或编码需要核对`,'产品汇总必须对应同编码、同名称、同单位的工单。','同名产品可能对应不同编码，或汇总范围与明细不一致。','产品资料负责人 / 数据管理员','先核产品编码和工单归属，再比较数量。',mismatch.map(p=>p.reviewKey));
  const short=plans.filter(p=>p.gap!==null&&p.gap<0&&!p.mismatch);
  if(short.length)add('production_below_plan','check',`${short.length} 组产品实际产量低于计划`,'这是数量差额，还不能直接认定延期或损耗。','可能仍在生产、报工未审批、短产，或计划调整后未同步。','生产主管 / 计划负责人','逐张核对工单进度；已完工的再核最终良品和成品入库。',short.map(p=>p.reviewKey));
  const over=plans.filter(p=>p.gap!==null&&p.gap>0&&!p.mismatch);
  if(over.length)add('production_above_plan','check',`${over.length} 组产品实际产量超过计划`,'先核对数量和单位，再确认是否确实超产。','可能增产未改计划、重复报工，或包装单位换算不同。','生产主管 / 报工负责人','对照计划变更、报工审批和计量单位。',over.map(p=>p.reviewKey));
  const processes=data.production.processes.map(p=>({...p,reviewKey:processIdentity(p)}));
  const missingHours=processes.filter(p=>p.missingDurationTasks>0||!finite(p.hours));
  if(missingHours.length)add('process_hours_missing','gap',`${missingHours.length} 组工序的工时记录不齐`,'累计工时缺失时不排名、不按零工时判断。','任务可能未结束、未填报起止时间，或接口未提供时长。','车间主管 / 报工负责人','核对任务状态、报工起止时间；人均效率还需实际参与人数。',missingHours.map(p=>p.reviewKey));
  const invalidHours=processes.filter(p=>finite(p.hours)&&p.hours<0||p.inProgress>p.tasks);
  if(invalidHours.length)add('process_invalid','data','工序时长或任务数有矛盾','负时长或进行中任务多于总任务，需要先核原始记录。','可能时间填反、重复统计或状态不同步。','车间主管 / 数据管理员','核对任务编号、起止时间和任务状态。',invalidHours.map(p=>p.reviewKey));
  if(processes.reduce((a,p)=>a+p.tasks,0)!==data.production.taskCount)add('task_count_mismatch','data','工序任务合计对不上','工序任务数相加与总任务数不一致。','可能工序分组或关联记录缺失。','车间主管 / 数据管理员','核对工序与任务归属。');
  const quality=data.production.quality.map((p,i)=>{
    const valid=finite(p.good)&&finite(p.defective)&&p.good>=0&&p.defective>=0;
    const rate=valid&&p.unit&&p.good!+p.defective!>0?p.good!/(p.good!+p.defective!):null;
    const names=[...new Set(projects.filter(row=>p.productCode&&row.productCode===p.productCode).map(row=>row.product))];
    return {...p,product:p.product===p.productCode&&names.length===1?names[0]!:p.product,
      good:p.unit?p.good:null,defective:p.unit?p.defective:null,reportedDefect:p.defective!==null&&p.defective>0,
      reviewKey:String(i),reviewGoodRate:rate,invalid:!valid||!p.unit||!same(rate,p.goodRate)||p.missingRecords>0};
  });
  const approved=data.production.approvedOutputCount??quality.reduce((a,p)=>a+p.records,0);
  if(approved!==quality.reduce((a,p)=>a+p.records,0)||approved>data.production.outputCount)add('quality_count_mismatch','data','质量统计记录数对不上','已审批报工数与质量分组记录数不一致。','可能审批范围不同或分组遗漏。','质量负责人 / 数据管理员','对照报工编号与审批状态重新核对。');
  const excluded=data.production.outputCount-approved;
  if(excluded>0)add('quality_approval','gap',`${excluded} 条报工未纳入已审批质量统计`,'当前良品率只说明已审批报工，不代表所有报工。','其余记录可能待审批、已驳回或处于其他状态。','质量负责人 / 报工审批人','核对这些报工的审批状态；待审批部分补齐后再看完整质量表现。');
  const invalidQuality=quality.filter(p=>p.invalid);
  if(invalidQuality.length)add('quality_missing','data',`${invalidQuality.length} 组报工的单位、数量或比例待核对`,'单位未提供、数量缺失或互相矛盾时，不展示汇总数量和良品率。','可能当前采集档案未保留报工单位、原单漏填，或数量修正未同步。','质量负责人 / 车间主管','提供对应报工原单的良品、不良品及计量单位，再核数量和比例。',invalidQuality.map(p=>p.reviewKey));
  const unverifiedDefects=quality.filter(p=>p.invalid&&p.reportedDefect);
  if(unverifiedDefects.length)add('quality_defects_unverified','check',`${unverifiedDefects.length} 组报工提到了不良品，数量口径待核`,'原记录有不良品信号；先补单位与数量，暂不发布不良率。','可能确有质量问题，也可能报工数量或单位需要更正；原因尚未查明。','质量负责人 / 车间主管','按产品和工序回查原单、批次检验及当班记录。',unverifiedDefects.map(p=>p.reviewKey));
  const defective=quality.filter(p=>!p.invalid&&p.defective!>0);
  if(defective.length)add('quality_defects','check',`${defective.length} 组已审批报工记录有不良品`,'先按产品和工序定位，不跨单位相加。','可能与原料、操作、设备或检验判定有关，当前尚未确定原因。','质量负责人 / 车间主管','对照批次质检结果、不良原因和当班记录。',defective.map(p=>p.reviewKey));
  return {plans,processes,quality,approved,excluded,findings,statuses,
    completedOnly:data.periodMetadata?.basis==='completed',total:projects.length};
}

export function reviewInventory(data:BlacklakeAnalysis,unit:string){
  const all=data.inventory.records;
  const allRows=all.map((row,index)=>({...row,reviewKey:String(index),reviewStatus:!row.unit||!row.warehouse||!finite(row.quantity)?'资料待补':row.quantity<0?'负余额待核对':row.quantity===0?'零余额，需求待核':'已返回余额'}));
  const rows=allRows.filter(p=>!unit||unit==='__all__'||unit==='__missing__'&&!p.unit||p.unit===unit);
  const findings:OperationsFinding[]=[];
  const add=(code:string,kind:OperationsFinding['kind'],title:string,summary:string,possibleCauses:string,owner:string,action:string,keys:string[]=[])=>findings.push({code,kind,title,summary,possibleCauses,owner,action,keys});
  const missing=allRows.filter(p=>p.reviewStatus==='资料待补');
  if(missing.length)add('stock_missing','gap',`${missing.length} 行库存资料不齐`,'缺数量、单位或仓库的记录不按零库存处理。','可能接口未返回字段，或物料、仓库资料未维护完整。','仓库负责人 / 产品资料负责人','补对应物料、仓库、数量和计量单位。',missing.map(p=>p.reviewKey));
  const negative=allRows.filter(p=>finite(p.quantity)&&p.quantity<0);
  if(negative.length)add('stock_negative','check',`${negative.length} 行库存为负余额`,'需要核对账面，尚不能断定实物为负或已经丢失。','可能先出后入、入库未登记、错仓、批次或单位换算错误。','仓库负责人','核对最近入出库单和实物，确认时间、仓库、批次和单位。',negative.map(p=>p.reviewKey));
  const zero=allRows.filter(p=>p.quantity===0);
  if(zero.length)add('stock_zero','check',`${zero.length} 行为零余额，先核生产需求`,'零余额不自动等于缺料，也不直接生成采购数量。','可能正常用完、该物料暂不用，或收发记录还未补齐。','仓库负责人 / 生产计划负责人','对照近期生产需求、在途采购和安全库存，再决定补货。',zero.map(p=>p.reviewKey));
  if(!all.length)add('stock_no_snapshot','gap','所选范围没有可用库存余额','没有快照不代表库存为零。','库存未在这段时间采集，或当前账号未返回相关记录。','仓库负责人','提供对应日期的库存表、期初余额和已审核收发明细，才能核对收发存。');
  add('stock_ledger_boundary','gap','收发存还缺同一时间边界的资料','当前可以核对返回余额与仓库汇总，尚不能证明期间收发存平衡。','现有资料是采集时点余额，缺少同一时间边界的期初、期末和完整收发记录。','仓库 / 财务负责人','补期间起止库存、已审核出入库、调拨和盘盈盘亏明细，再核对期初＋入库－出库＋调整＝期末。');
  const mismatchKeys:string[]=[];let mismatchedGroups=0;
  for(const total of data.inventory.byWarehouseUnit){
    const records=all.filter(p=>p.unit===total.unit&&(total.warehouseKey?p.warehouseKey===total.warehouseKey:p.warehouse===total.warehouse));
    if(records.length!==total.records||records.filter(p=>!finite(p.quantity)).length!==total.missing||!same(sumKnown(records.map(p=>p.quantity)),total.quantity)){
      mismatchedGroups+=1;mismatchKeys.push(...records.map(p=>String(all.indexOf(p))));
    }
  }
  if(mismatchedGroups)add('stock_total_mismatch','data',`${mismatchedGroups} 组仓库汇总与明细对不上`,'暂不据此判断库存多少。','可能仓库同名、记录遗漏或汇总范围不一致。','仓库负责人 / 数据管理员','按仓库编码、物料和单位核对原库存表。',[...new Set(mismatchKeys)]);
  return {rows,findings,totalRecords:all.length,snapshotAt:all.length?(data.inventory.balanceAsOf??data.periodMetadata?.inventoryAsOf??data.snapshotAt):null,
    lastAvailableAt:data.inventory.lastAvailableBalanceAt??data.periodMetadata?.lastAvailableInventoryAsOf??null};
}

export function reviewPeriod(data:BlacklakeAnalysis){
  const rows=data.cost.projects,findings:OperationsFinding[]=[];
  const eligible=rows.filter(p=>p.unitCostAvailable);
  const add=(code:string,kind:OperationsFinding['kind'],title:string,summary:string,possibleCauses:string,owner:string,action:string,keys:string[]=[])=>findings.push({code,kind,title,summary,possibleCauses,owner,action,keys});
  if(rows.length!==data.production.projectCount||eligible.length!==data.cost.unitCostAvailableProjects||data.periodMetadata&&rows.length!==data.periodMetadata.selectedProjects)
    add('period_reconcile','data','本期工单和核算数量对不上','先核对日期范围和资料版本。','可能混用了旧分析、旧资料或不同工单范围。','生产 / 财务 / 数据管理员','刷新当前日期资料，逐张核对工单编号后再分析。');
  const inconsistent=eligible.filter(p=>!p.unit||!finite(p.finishedGood)||p.finishedGood<=0||!finite(p.materialCost)||!finite(p.laborCost)||p.materialCost<0||p.laborCost<0||p.issues.length>0||!same((p.materialCost!+p.laborCost!)/p.finishedGood!,p.unitIdentifiedDirectCost));
  if(inconsistent.length)add('period_cost_math','data',`${inconsistent.length} 张工单的单位成本对不上`,'单位成本应等于材料加人工除以最终良品量。','可能数量、单位或成本明细修正后尚未同步。','财务 / 生产负责人','核对该工单材料、人工及成品良品数。',inconsistent.map(p=>String(p.projectId)));
  const blocked=rows.filter(p=>!p.unitCostAvailable);
  const unfinished=blocked.filter(p=>p.status!=='20'),completedBlocked=blocked.filter(p=>p.status==='20');
  if(unfinished.length)add('period_unfinished','check',`${unfinished.length} 张工单尚未完工或未确认完工`,'先看生产状态，暂不作最终单位成本比较；这不等于漏数据。','可能仍在生产，或完工状态尚未同步。','生产主管','核对进度和实际完工情况，完工后再核最终良品、领退料与工资。',unfinished.map(p=>String(p.projectId)));
  if(completedBlocked.length)add('period_cost_missing','gap',`${completedBlocked.length} 张已完工工单的成本资料待核`,'这些工单暂不进入单位成本比较。','可能材料、工资、良品量或工单关联信息未齐，也可能有零价、符号矛盾。','生产 / 仓库 / 财务负责人','打开工单看具体缺项，再补原单；不把缺失金额算作零。',completedBlocked.map(p=>String(p.projectId)));
  const dateField=data.periodMetadata?.basis==='created'?'创建日期':'完工日期';
  const missingDates=data.periodMetadata?.basis==='created'?data.periodMetadata.unassignedProjects:data.periodMetadata?.unassignedCompletedProjects;
  const missingIds=data.periodMetadata?.basis==='created'?data.periodMetadata.unassignedProjectIds:data.periodMetadata?.unassignedCompletedProjectIds;
  if(missingDates)add('period_dates_missing','gap',`${missingDates} 张${dateField==='完工日期'?'已完工':''}工单缺少有效${dateField}`,`这些工单无法归入任何${dateField}，不能认定它们属于本期。`,'可能漏填日期，或来源日期格式无效。','生产负责人',`补真实${dateField}，再重新分析相应周期。`,(missingIds||[]).map(String));
  if(data.periodMetadata?.coverageStatus==='source_snapshot_before_period_end')add('period_not_complete','gap','所选周期尚未被资料完整覆盖','只分析目前已经采集到的记录，不能当作整月、整季或整年的最终结果。','资料采集时间早于所选周期结束。','生产 / 财务负责人','补齐周期结束后的生产和记账资料，再复核本期。');
  if(data.periodMetadata?.coverageStatus==='source_time_unknown')add('period_time_unknown','data','资料采集时间未知','不能确认这份资料覆盖到什么时候。','来源未提供有效采集时间。','数据管理员','补采集时间并核对日期范围。');
  return {findings,total:rows.length,eligible:eligible.length,blocked:blocked.length};
}
