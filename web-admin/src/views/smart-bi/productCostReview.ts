import type { CostDataAlert, CostProject } from '@/api/smartbi/blacklake';
import { groupEligibleCostProjects, type ProductUnitCostSummary } from './costOverview';
import type { LaborCostInput, LaborCostRow } from '@/utils/laborCostAnalysis';

/** Registered production identities remain distinct from a confirmed shift roster. */
export function blacklakeLaborInput(orders:readonly CostProject[],sourceRunId?:string):LaborCostInput {
  const known=(values:(number|null)[])=>values.length&&values.every(v=>typeof v==='number'&&Number.isFinite(v))?values.reduce<number>((a,b)=>a+(b as number),0):null;
  const groups=new Map<string,CostProject['processes']>();
  for(const order of orders)for(const row of order.processes)groups.set(row.process,[...(groups.get(row.process)||[]),row]);
  const units=new Set(orders.map(row=>row.unit));const unit=units.size===1?orders[0]?.unit??null:null;
  return {total:known(orders.map(r=>r.laborCost)),output:unit?known(orders.map(r=>r.finishedGood)):null,unit,
    blacklakeOrderIds:orders.map(r=>r.projectId),sourceRunId,
    rows:[...groups].map(([process,rows],index):LaborCostRow=>({id:`blacklake:${index}`,process,amount:known(rows.map(r=>r.laborCost)),reportedHours:known(rows.map(r=>r.hours)),personHours:null,
      ...(rows.some(r=>r.laborEvidence)?{
        registeredWorkerKeys:[...new Set(rows.flatMap(r=>r.laborEvidence?.registeredWorkerKeys||[]))],
        registeredCoverageComplete:rows.every(r=>r.laborEvidence?.registeredCoverageComplete===true),
        sourceEffectiveHours:known(rows.map(r=>r.laborEvidence?.sourceEffectiveHours??null)),
        registeredReports:rows.reduce((a,r)=>a+(r.laborEvidence?.registeredReports||0),0),
        zeroTimeReports:rows.reduce((a,r)=>a+(r.laborEvidence?.zeroTimeReports||0),0),
        overlapReports:rows.reduce((a,r)=>a+(r.laborEvidence?.overlapReports||0),0),
      }:{}),
    })),
    requests:orders.flatMap(order=>order.processes.flatMap(row=>{
      const e=row.laborEvidence;if(!e||(!e.registeredReports&&row.laborCost===0))return [];
      const missing=['实际班组人员名单和每段参与人数',...e.issues,
        ...(e.zeroTimeReports?['零时长报工需确认']:[]),...(e.overlapReports?['同一登记人员的重叠报工需确认']:[])];
      return [{id:`${order.projectId}:${row.taskId}`,orderCode:order.projectCode,process:row.process,missing:missing.join('；'),
        owner:e.issues.some(s=>s.includes('工资'))?'车间主管 / 财务':'车间主管 / 班组长',
        action:`已查到 ${e.registeredReports} 条报工、${e.registeredCount??e.registeredWorkerKeys?.length??'待核对'} 名登记人员。确认是否班组代报，补齐每段实际参与人员和人数；核对本工序的零时长、重叠及金额问题。`,
        benefit:'按实际人员去重计算人均；按每段时长×人数计算工序人时，再分摊同一笔人工费。'}];
    })),
  };
}

export interface ProductCostReview extends ProductUnitCostSummary {
  orders: CostProject[];
  alerts: CostDataAlert[];
  identityConflict: boolean;
  materials: { key: string; material: string; unit: string; quantity: number | null; amount: number | null }[];
  processes: { process: string; hours: number | null; amount: number | null }[];
}

/** Same SKU, name/stage and finished unit only. Never add upstream cost again. */
export function buildProductCostReviews(orders: readonly CostProject[], alerts: readonly CostDataAlert[] = []): ProductCostReview[] {
  const byKey = new Map<string, CostProject[]>();
  for (const row of orders) {
    const key = groupEligibleCostProjects([row])[0]?.key;
    if (!key) continue;
    byKey.set(key, [...(byKey.get(key) || []), row]);
  }
  const sum = (values: (number | null)[]) => values.length && values.every(v=>typeof v==='number'&&Number.isFinite(v))
    ? values.reduce<number>((total,v)=>total+(v as number),0) : null;
  return groupEligibleCostProjects(orders).map(group=>{
    const rows=byKey.get(group.key) || [];
    const ids=new Set(rows.map(row=>row.projectId));
    const materialGroups=new Map<string, CostProject['materialLinesDetail']>();
    const processGroups=new Map<string, CostProject['processes']>();
    for(const row of rows){
      for(const line of row.materialLinesDetail){
        // A missing material code does not establish identity across orders.
        const key=JSON.stringify([line.materialCode || `order:${row.projectId}:${line.orderCode}`,line.material,line.unit]);
        materialGroups.set(key,[...(materialGroups.get(key)||[]),line]);
      }
      for(const process of row.processes)processGroups.set(process.process,[...(processGroups.get(process.process)||[]),process]);
    }
    return {...group,orders:rows,alerts:alerts.filter(alert=>alert.entityRefs.some(ref=>ids.has(ref.projectId))),
      identityConflict:!!group.productCode && orders.some(row=>row.productCode===group.productCode&&row.product.trim()!==group.product),
      materials:[...materialGroups].map(([key,lines])=>({key,material:lines[0]!.material,unit:lines[0]!.unit,quantity:sum(lines.map(line=>line.netConsumption)),amount:sum(lines.map(line=>line.recognizedCost))})),
      processes:[...processGroups].map(([process,lines])=>({process,hours:sum(lines.map(line=>line.hours)),amount:sum(lines.map(line=>line.laborCost))})),
    };
  });
}
