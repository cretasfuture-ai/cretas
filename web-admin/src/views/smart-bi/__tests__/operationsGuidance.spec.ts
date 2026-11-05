import {describe,it,expect} from 'vitest';
import type {BlacklakeAnalysis,CostProject} from '@/api/smartbi/blacklake';
import {reviewProduction,reviewInventory,reviewPeriod} from '../operationsGuidance';

const order=(id=1,code='A'):CostProject=>({projectId:id,projectCode:`MO${id}`,product:'同名产品',productCode:code,unit:'kg',status:'20',planned:10,actual:8,finishedGood:8,materialCost:60,laborCost:20,identifiedDirectCost:80,unitIdentifiedDirectCost:10,materialLines:1,materialCostKnownLines:1,sourceZeroCostLines:0,taskCount:0,laborCostKnownTasks:0,materialLinesDetail:[],processes:[],issues:[],unitCostAvailable:true});
// Only the analysis branches exercised here are supplied; no network or fake business fallback.
function fixture():BlacklakeAnalysis{return {
  snapshotAt:'2026-11-04T10:00:00Z',runId:'current',
  production:{projectCount:1,taskCount:0,outputCount:0,approvedOutputCount:0,statuses:{'20':1},completionRate:null,planActual:[{productCode:'A',product:'同名产品',unit:'kg',planned:10,actual:8,projects:1,plannedMissing:0,actualMissing:0}],processes:[],quality:[]},
  inventory:{records:[],byWarehouseUnit:[],pairsNotReturned:null,movementOrders:0,balanceAsOf:null,lastAvailableBalanceAt:'2026-11-03T09:00:00Z'},
  cost:{projects:[order()],unitCostAvailableProjects:1},
} as BlacklakeAnalysis;}
describe('factory operations diagnostics',()=>{
  it('keeps same-name SKUs separate and flags ambiguous legacy groups',()=>{
    const d=fixture();d.cost.projects.push(order(2,'B'));d.production.projectCount=2;d.production.statuses={'20':2};
    expect(reviewProduction(d).plans[0]?.orderIds).toEqual([1]);
    delete d.production.planActual[0]!.productCode;
    expect(reviewProduction(d).plans[0]?.orderIds).toEqual([]);
    expect(reviewProduction(d).findings.some(p=>p.code==='production_identity')).toBe(true);
  });
  it('does not call a plan gap a confirmed delay or loss, and preserves missing units',()=>{
    const d=fixture();const r=reviewProduction(d);expect(r.findings.find(p=>p.code==='production_below_plan')?.kind).toBe('check');
    d.production.planActual[0]!.unit='';expect(reviewProduction(d).plans[0]?.gap).toBeNull();
    expect(reviewProduction(d).findings.some(p=>p.code==='production_missing')).toBe(true);
  });
  it('does not turn absent duration or a zero quality denominator into zero efficiency or 100% good',()=>{
    const d=fixture();d.production.taskCount=1;d.production.processes=[{processId:3,projectIds:[1],process:'包装',tasks:1,hours:null,inProgress:1,missingDurationTasks:1}];
    d.production.outputCount=2;d.production.approvedOutputCount=1;d.production.quality=[{product:'同名产品',process:'包装',unit:'kg',good:0,defective:0,goodRate:null,records:1,missingRecords:0}];
    const r=reviewProduction(d);expect(r.quality[0]?.reviewGoodRate).toBeNull();expect(r.excluded).toBe(1);
    expect(r.findings.map(p=>p.code)).toEqual(expect.arrayContaining(['process_hours_missing','quality_approval']));
    expect(r.processes[0]?.projectIds).toEqual([1]);
    d.production.quality[0]!.goodRate=1;expect(reviewProduction(d).findings.some(p=>p.code==='quality_missing')).toBe(true);
  });
  it('finds negatives outside the selected unit, keeps zero distinct from shortage, and exposes missing units',()=>{
    const d=fixture();d.inventory.records=[{product:'甲',warehouse:'A',unit:'箱',quantity:0},{product:'乙',warehouse:'A',unit:'kg',quantity:-2},{product:'丙',warehouse:'A',unit:'',quantity:null}];
    const r=reviewInventory(d,'箱');expect(r.rows).toHaveLength(1);expect(r.findings.find(p=>p.code==='stock_negative')?.keys).toEqual(['1']);
    expect(r.findings.find(p=>p.code==='stock_zero')?.summary).toContain('不自动等于缺料');
    expect(reviewInventory(d,'__missing__').rows[0]?.product).toBe('丙');expect(reviewInventory(d,'__all__').rows).toHaveLength(3);
    expect(r.findings.every(p=>p.owner&&p.action&&p.possibleCauses)).toBe(true);
  });
  it('does not publish rates or sum quantities when output units are absent, but retains a defect signal',()=>{
    const d=fixture();d.production.outputCount=1;d.production.approvedOutputCount=1;
    d.production.quality=[{productCode:'A',product:'A',process:'包装',unit:'',good:10,defective:1,goodRate:10/11,records:1,missingRecords:0}];
    const r=reviewProduction(d);expect(r.quality[0]?.reviewGoodRate).toBeNull();expect(r.quality[0]?.good).toBeNull();expect(r.quality[0]?.product).toBe('同名产品');
    expect(r.findings.map(p=>p.code)).toEqual(expect.arrayContaining(['quality_missing','quality_defects_unverified']));
  });
  it('reconciles same-name warehouses using IDs and never fabricates an absent period stock snapshot',()=>{
    const d=fixture();d.inventory.records=[{product:'甲',warehouse:'同名仓',warehouseKey:'A',unit:'kg',quantity:2},{product:'乙',warehouse:'同名仓',warehouseKey:'B',unit:'kg',quantity:3}];
    d.inventory.byWarehouseUnit=[{warehouse:'同名仓',warehouseKey:'A',unit:'kg',quantity:2,records:1,missing:0},{warehouse:'同名仓',warehouseKey:'B',unit:'kg',quantity:3,records:1,missing:0}];
    expect(reviewInventory(d,'kg').findings.some(p=>p.code==='stock_total_mismatch')).toBe(false);
    d.inventory.byWarehouseUnit[0]!.quantity=5;expect(reviewInventory(d,'kg').findings.some(p=>p.code==='stock_total_mismatch')).toBe(true);
    d.inventory.records=[];const r=reviewInventory(d,'__all__');expect(r.snapshotAt).toBeNull();expect(r.lastAvailableAt).toContain('11-03');expect(r.findings.some(p=>p.code==='stock_no_snapshot')).toBe(true);
  });
  it('separates unfinished orders from incomplete completed cost records and checks arithmetic',()=>{
    const d=fixture();d.cost.projects.push({...order(2),status:'10',unitCostAvailable:false},{...order(3),unitCostAvailable:false});d.production.projectCount=3;
    const r=reviewPeriod(d);expect(r.findings.find(p=>p.code==='period_unfinished')?.keys).toEqual(['2']);expect(r.findings.find(p=>p.code==='period_cost_missing')?.keys).toEqual(['3']);
    d.cost.projects[0]!.unitIdentifiedDirectCost=99;expect(reviewPeriod(d).findings.find(p=>p.code==='period_cost_math')?.keys).toEqual(['1']);
  });
  it('locates missing dates according to the selected date basis and flags incomplete periods',()=>{
    const d=fixture();d.periodMetadata={startDate:'2026-11-01',endDate:'2026-11-30',kind:'month',basis:'completed',timezone:'Asia/Shanghai',fingerprint:'x',scopeDescription:'',dataAsOf:d.snapshotAt,selectedProjects:1,unassignedProjects:2,unassignedCompletedProjects:1,unassignedProjectIds:[2,3],unassignedCompletedProjectIds:[2],coverageStatus:'source_snapshot_before_period_end',inventoryStatus:'historical_balance_unavailable'};
    expect(reviewPeriod(d).findings.find(p=>p.code==='period_dates_missing')?.keys).toEqual(['2']);
    d.periodMetadata.basis='created';expect(reviewPeriod(d).findings.find(p=>p.code==='period_dates_missing')?.keys).toEqual(['2','3']);
    expect(reviewPeriod(d).findings.some(p=>p.code==='period_not_complete')).toBe(true);
  });
});
