import type { CostDataAlert, CostProject } from '@/api/smartbi/blacklake';
import { groupEligibleCostProjects, type ProductUnitCostSummary } from './costOverview';

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
