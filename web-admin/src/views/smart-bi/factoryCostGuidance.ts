import type { CostProject } from '@/api/smartbi/blacklake';
import type { ProductCostReview } from './productCostReview';

/** Mutually exclusive next actions. A data issue takes priority over a cost signal. */
export function productReviewPriority(group:ProductCostReview):'data_quality'|'business_signal'|'readiness'|'none' {
  if(group.identityConflict||group.alerts.some(a=>a.family==='data_quality'))return 'data_quality';
  if(group.alerts.some(a=>a.family==='business_signal'))return 'business_signal';
  return group.alerts.some(a=>a.family==='readiness')?'readiness':'none';
}

export function orderUnitCosts(order:CostProject){
  if(!order.unit||typeof order.finishedGood!=='number'||!Number.isFinite(order.finishedGood)||order.finishedGood<=0
    ||typeof order.materialCost!=='number'||!Number.isFinite(order.materialCost)
    ||typeof order.laborCost!=='number'||!Number.isFinite(order.laborCost))return null;
  const material=order.materialCost/order.finishedGood,labor=order.laborCost/order.finishedGood;
  return {material,labor,total:material+labor};
}

/** Observed accounting difference, never an inferred causal or efficiency claim. */
export function productCostDifference(group:ProductCostReview){
  if(group.identityConflict||group.orders.length<2)return null;
  const rows=group.orders.map(order=>({order,cost:orderUnitCosts(order)}));
  if(rows.some(row=>!row.cost||row.order.unit!==group.unit))return null;
  rows.sort((a,b)=>a.cost!.total-b.cost!.total);
  const lower=rows[0]!,higher=rows.at(-1)!;
  const delta=higher.cost!.total-lower.cost!.total;
  const materialDelta=higher.cost!.material-lower.cost!.material;
  const laborDelta=higher.cost!.labor-lower.cost!.labor;
  const focus=materialDelta>Math.max(laborDelta,0)?'material':laborDelta>Math.max(materialDelta,0)?'labor':'both';
  return {lower:lower.order,higher:higher.order,lowerCost:lower.cost!.total,higherCost:higher.cost!.total,
    delta,materialDelta,laborDelta,percent:lower.cost!.total>0?delta/lower.cost!.total*100:null,focus,
    needsDataReview:productReviewPriority(group)==='data_quality',
    action:focus==='material'?`请仓库和财务对照这两张工单，核对材料单价、每${group.unit}成品的材料用量和退料记录。`
      :focus==='labor'?'请生产主管对照这两张工单，核对各工序工资、计薪方式和成品数量。'
      :'请仓库、生产和财务对照这两张工单，分别核对材料用量、单价和工序工资。'};
}
