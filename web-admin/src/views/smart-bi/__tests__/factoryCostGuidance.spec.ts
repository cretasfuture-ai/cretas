import {expect,it} from 'vitest';
import type {CostDataAlert,CostProject} from '@/api/smartbi/blacklake';
import {buildProductCostReviews} from '../productCostReview';
import {orderUnitCosts,productCostDifference,productReviewPriority} from '../factoryCostGuidance';

function row(id:number,material:number,labor:number,output=10):CostProject{
  return {projectId:id,projectCode:`ORDER-${id}`,product:'合成成品',productCode:'SKU',unit:'包',status:'20',planned:output,actual:output,
    finishedGood:output,materialCost:material,laborCost:labor,identifiedDirectCost:material+labor,unitIdentifiedDirectCost:(material+labor)/output,
    materialLines:0,materialCostKnownLines:0,sourceZeroCostLines:0,taskCount:0,laborCostKnownTasks:0,
    materialLinesDetail:[],processes:[],issues:[],unitCostAvailable:true};
}
function alert(family:CostDataAlert['family']):CostDataAlert{return {id:family,type:'synthetic',family,severity:'warning',blocksUnitCost:false,
  title:'合成核对项',message:'合成测试',owner:'生产/财务',action:'核对原单',fields:[],entityRefs:[{projectId:1,projectCode:'ORDER-1',productCode:'SKU',unit:'包'}],
  evidence:{},detectedBy:'test',ruleVersion:'test',scope:'test',runId:'test',status:'open'};}

it('reconciles the per-unit gap when material rises while labor falls; batch size is normalized',()=>{
  const orders=[row(1,20,80),row(2,1000,500,100)];
  const comparison=productCostDifference(buildProductCostReviews(orders)[0]!)!;
  expect(comparison.lower.projectId).toBe(1);expect(comparison.higher.projectId).toBe(2);
  expect(comparison).toMatchObject({delta:5,materialDelta:8,laborDelta:-3,percent:50,focus:'material'});
  expect(comparison.materialDelta+comparison.laborDelta).toBeCloseTo(comparison.delta,10);
  expect(comparison.action).toContain('每包成品');expect(orders[0]!.projectId).toBe(1);
});

it('prioritizes data checks over cost signals and never counts one product twice',()=>{
  const group=buildProductCostReviews([row(1,20,5)],[alert('business_signal'),alert('data_quality'),alert('readiness')])[0]!;
  expect(productReviewPriority(group)).toBe('data_quality');
  group.alerts=group.alerts.filter(a=>a.family!=='data_quality');expect(productReviewPriority(group)).toBe('business_signal');
  group.alerts=[alert('readiness')];expect(productReviewPriority(group)).toBe('readiness');
  group.alerts=[];expect(productReviewPriority(group)).toBe('none');
  group.identityConflict=true;expect(productReviewPriority(group)).toBe('data_quality');
});

it('does not compare single samples, conflicting identities, mixed units or incomplete amounts',()=>{
  const group=buildProductCostReviews([row(1,20,5),row(2,30,5)])[0]!;
  expect(productCostDifference({...group,orders:[group.orders[0]!]})).toBeNull();
  expect(productCostDifference({...group,identityConflict:true})).toBeNull();
  expect(productCostDifference({...group,orders:[group.orders[0]!,{...group.orders[1]!,unit:'KG'}]})).toBeNull();
  expect(productCostDifference({...group,orders:[group.orders[0]!,{...group.orders[1]!,materialCost:null}]})).toBeNull();
  expect(orderUnitCosts({...group.orders[0]!,finishedGood:0})).toBeNull();
});

it('keeps zero baseline differences finite and permits equal observations without claiming an anomaly',()=>{
  const zero=buildProductCostReviews([row(1,0,0),row(2,10,0)])[0]!;
  expect(productCostDifference(zero)).toMatchObject({delta:1,percent:null});
  const equal=buildProductCostReviews([row(1,10,10),row(2,100,100,100)])[0]!;
  expect(productCostDifference(equal)?.delta).toBe(0);
  expect(productReviewPriority(equal)).toBe('none');
});

it('retains a data warning alongside a numerical gap instead of calling its cause confirmed',()=>{
  const group=buildProductCostReviews([row(1,10,10),row(2,10,30)],[alert('data_quality')])[0]!;
  expect(productCostDifference(group)).toMatchObject({focus:'labor',needsDataReview:true,materialDelta:0,laborDelta:2});
});
