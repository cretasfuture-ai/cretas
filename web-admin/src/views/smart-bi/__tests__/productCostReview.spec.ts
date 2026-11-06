import { expect, it } from 'vitest';
import type { CostProject } from '@/api/smartbi/blacklake';
import { blacklakeLaborInput,buildProductCostReviews } from '../productCostReview';
import {analyzeLaborCosts} from '@/utils/laborCostAnalysis';

function row(id:number, changes:Partial<CostProject>={}):CostProject {
  return {projectId:id,projectCode:`ORDER-${id}`,product:'合成成品',productCode:'SYN',unit:'包',status:'20',planned:10,actual:10,finishedGood:10,materialCost:20,laborCost:5,identifiedDirectCost:25,unitIdentifiedDirectCost:2.5,materialLines:1,materialCostKnownLines:1,sourceZeroCostLines:0,taskCount:1,laborCostKnownTasks:1,issues:[],unitCostAvailable:true,
    materialLinesDetail:[{material:'合成半成品',materialCode:'M',unit:'kg',netConsumption:2,recognizedCost:20,unitPrice:10,orderCode:'OUT-'+id,date:'2026-11-03',bizType:'32'}],
    processes:[{process:'包装',status:'20',good:10,hours:1,laborCost:5}],...changes};
}

it('wires actual Blacklake wages without re-labelling reported duration as person-hours',()=>{
  const input=blacklakeLaborInput([row(1),row(2)]);
  expect(input).toMatchObject({total:10,output:20,unit:'包'});
  expect(input.rows).toHaveLength(1);expect(input.rows[0]).toMatchObject({amount:10,reportedHours:2,personHours:null});
  expect(input.rows[0]!.workerIds).toBeUndefined();
});
it('deduplicates registered staff while keeping actual roster and person-hours unconfirmed',()=>{
  const orders=[row(1),row(2)];
  for(const order of orders)order.processes[0]!.laborEvidence={registeredWorkerKeys:['opaque-worker'],registeredCoverageComplete:true,registeredReports:1,sourceEffectiveHours:1,zeroTimeReports:0,overlapReports:1,issues:[],source:'blacklake_output_productionStaffId',basis:'registered_staff_not_confirmed_shift_roster'};
  const input=blacklakeLaborInput(orders),result=analyzeLaborCosts(input);
  expect(result.registeredParticipants).toBe(1);expect(result.registeredPerPerson).toBe(10);
  expect(result.sourceEffectiveHours).toBe(2);expect(result.perPerson).toBeNull();expect(result.personHours).toBeNull();expect(result.canAllocate).toBe(false);
  expect(input.requests?.map(r=>r.orderCode)).toEqual(['ORDER-1','ORDER-2']);
  expect(input.requests?.[0]?.missing).toContain('重叠报工');
  expect(input.requests?.[0]?.owner).toContain('班组长');
  expect(input.requests?.[0]?.benefit).toContain('时长×人数');
});

it('weights product costs by actual output, retains member orders and sums components once',()=>{
  const large=row(2,{finishedGood:90,materialCost:450,laborCost:90});
  large.materialLinesDetail[0]!.recognizedCost=450;large.materialLinesDetail[0]!.netConsumption=45;
  large.processes[0]!.hours=9;large.processes[0]!.laborCost=90;
  const result=buildProductCostReviews([row(1),large])[0]!;
  expect(result.sampleCount).toBe(2);expect(result.orders.map(r=>r.projectId)).toEqual([1,2]);
  expect(result.finishedGood).toBe(100);expect(result.materialCost).toBe(470);expect(result.laborCost).toBe(95);
  expect(result.weightedUnitCost).toBe(5.65);expect(result.weightedMaterialUnitCost).toBe(4.7);expect(result.weightedLaborUnitCost).toBe(.95);
  expect(result.minimumUnitCost).toBe(2.5);expect(result.maximumUnitCost).toBe(6);
  expect(result.materials[0]).toMatchObject({quantity:47,amount:470});
  expect(result.processes[0]).toEqual({process:'包装',hours:10,amount:95});
});

it('keeps finished goods, semi-finished goods, different units and codes separate',()=>{
  const results=buildProductCostReviews([row(1),row(2,{product:'合成半成品'}),row(3,{unit:'kg'}),row(4,{productCode:'OTHER'})]);
  expect(results).toHaveLength(4);
  expect(results.find(r=>r.orders[0]?.projectId===1)?.identityConflict).toBe(true);
  expect(results.every(r=>r.sampleCount===1)).toBe(true);
});

it('does not merge missing product identities or treat missing material/process values as zero',()=>{
  const first=row(1,{productCode:null});const second=row(2,{productCode:null});
  first.materialLinesDetail[0]!.recognizedCost=null;first.processes[0]!.hours=null;
  const results=buildProductCostReviews([first,second]);
  expect(results).toHaveLength(2);
  const selected=results.find(r=>r.orders[0]?.projectId===1)!;
  expect(selected.materials[0]!.amount).toBeNull();expect(selected.processes[0]!.hours).toBeNull();
});
