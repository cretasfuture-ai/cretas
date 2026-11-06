import { expect,it } from 'vitest';
import { allocateLaborByPersonHours,analyzeLaborCosts,mesLaborRows,type LaborCostInput } from '../laborCostAnalysis';
const input=():LaborCostInput=>({total:100,output:20,unit:'包',rows:[
  {id:'a',process:'分切',amount:60,reportedHours:1,personHours:3,workerIds:['A','B'],rosterComplete:true},
  {id:'b',process:'包装',amount:40,reportedHours:1,personHours:2,workerIds:['B','C'],rosterComplete:true},
]});
it('deduplicates workers across processes and uses person-hours without multiplying workers twice',()=>{
  const result=analyzeLaborCosts(input());
  expect(result.participants).toBe(3);expect(result.perPerson).toBeCloseTo(100/3);
  expect(result.personHours).toBe(5);expect(result.perPersonHour).toBe(20);expect(result.perUnit).toBe(5);
  expect(result.rows[0]).toMatchObject({share:.6,perUnit:3,allocated:60});
});
it('never invents headcount or person-hours from Blacklake reported task duration',()=>{
  const data=input();data.rows=data.rows.map(row=>({...row,workerIds:undefined,rosterComplete:undefined,personHours:null}));
  const result=analyzeLaborCosts(data);expect(result.perPerson).toBeNull();expect(result.perPersonHour).toBeNull();expect(result.canAllocate).toBe(false);
  expect(result.perUnit).toBe(5);expect(result.needs.map(r=>r.key)).toEqual(['people','hours']);
});
it('does not divide total wages by a partial set of registered staff',()=>{
  const data=input();data.rows=data.rows.map((row,index)=>({...row,workerIds:undefined,rosterComplete:false,personHours:null,registeredWorkerKeys:['opaque'],registeredCoverageComplete:index===0,sourceEffectiveHours:1}));
  const result=analyzeLaborCosts(data);
  expect(result.registeredParticipants).toBe(1);expect(result.registeredPerPerson).toBeNull();expect(result.perPerson).toBeNull();expect(result.canAllocate).toBe(false);
});
it('refuses to divide a whole-batch wage by partial staff or partial hours',()=>{
  const data=input();data.rows[1]!.amount=null;
  const result=analyzeLaborCosts(data);expect(result.reconciled).toBe(false);expect(result.perPerson).toBeNull();expect(result.perPersonHour).toBeNull();expect(result.canAllocate).toBe(false);expect(result.perUnit).toBe(5);
  const mismatch=input();mismatch.total=100.01;expect(analyzeLaborCosts(mismatch).reconciled).toBe(false);
});
it('keeps missing totals and missing output units unknown',()=>{
  const data=input();data.total=null;data.unit=null;const result=analyzeLaborCosts(data);
  expect(result.perUnit).toBeNull();expect(result.perPerson).toBeNull();expect(result.canAllocate).toBe(false);
});
it('preserves money at cent precision, including tiny pools and zero-weight tail rows',()=>{
  expect(allocateLaborByPersonHours(10.01,[1,1,1])).toEqual([3.34,3.34,3.33]);
  const tiny=allocateLaborByPersonHours(.04,Array(8).fill(1));expect(tiny.every(n=>n!==null&&n>=0)).toBe(true);expect(tiny.reduce<number>((s,n)=>s+Math.round(n!*100),0)).toBe(4);
  expect(allocateLaborByPersonHours(10,[1,2,0])).toEqual([3.33,6.67,0]);
  expect(allocateLaborByPersonHours(10,[null,2])).toEqual([null,null]);
  expect(allocateLaborByPersonHours(10,[-1,2])).toEqual([null,null]);
  expect(allocateLaborByPersonHours(10,[0,0])).toEqual([null,null]);
});
it('maps native MES effective laborHours once and limits row-person averages to actual time segments',()=>{
  const rows=mesLaborRows([{processOrder:1,amount:120,durationMinutes:60,laborHours:6,workerCount:6,startTime:'08:00',endTime:'09:00'},{processOrder:2,amount:60,laborHours:3,workerCount:9}]);
  const result=analyzeLaborCosts({rows,total:180,output:10,unit:'盒'});
  expect(result.personHours).toBe(9);expect(result.perPersonHour).toBe(20);
  expect(result.rows[0]!.segmentPerPerson).toBe(20);expect(result.rows[1]!.segmentPerPerson).toBeNull();
  expect(result.participants).toBeNull();expect(result.perPerson).toBeNull();
});
