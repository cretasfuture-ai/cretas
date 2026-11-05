import {expect,it} from 'vitest';
import {queryCostTable,type CostTableColumn} from '../costTableQuery';
const rows=[{id:1,name:'牛肉',amount:9,unit:'KG',date:'2026-11-01'},{id:2,name:'牛腩',amount:100,unit:'包',date:'2026-11-03'},{id:3,name:'猪肉',amount:null,unit:'KG',date:null},{id:4,name:'牛肉',amount:0,unit:'KG',date:'2026-11-02'}];
const columns:CostTableColumn<(typeof rows)[number]>[]=[{key:'name',label:'产品'},{key:'amount',label:'金额',type:'number'},{key:'unit',label:'单位',type:'select'},{key:'date',label:'日期',type:'date'}];
it('sorts raw numbers across the whole list and leaves unknown values last in either direction',()=>{
  expect(queryCostTable(rows,columns,{}, {prop:'amount',order:'descending'}).map(r=>r.id)).toEqual([2,1,4,3]);
  expect(queryCostTable(rows,columns,{}, {prop:'amount',order:'ascending'}).map(r=>r.id)).toEqual([4,1,2,3]);
  expect(rows.map(r=>r.id)).toEqual([1,2,3,4]);
});
it('combines text, unit, numeric and inclusive date filters without turning null into zero',()=>{
  const result=queryCostTable(rows,columns,{name:{text:' 牛 '},unit:{selected:'KG'},amount:{min:'0',max:'9'},date:{min:'2026-11-02',max:'2026-11-02'}},{});
  expect(result.map(r=>r.id)).toEqual([4]);
  expect(queryCostTable(rows,columns,{amount:{min:0,max:0}},{}).map(r=>r.id)).toEqual([4]);
  expect(queryCostTable(rows,columns,{amount:{missingOnly:true}},{}).map(r=>r.id)).toEqual([3]);
});
it('scales percentages to their displayed value and sorts before a caller takes a page',()=>{
  const data=Array.from({length:1000},(_,i)=>({value:i/1000}));
  const cols:CostTableColumn<(typeof data)[number]>[]=[{key:'value',label:'良品率',type:'number',scale:100}];
  const result=queryCostTable(data,cols,{value:{min:'90'}},{prop:'value',order:'descending'});
  expect(result).toHaveLength(100);expect(result.slice(0,20)[0]!.value).toBe(.999);expect(result.slice(0,20)[19]!.value).toBe(.98);
});
it('preserves stable source order when sorting is cleared or values tie',()=>{
  expect(queryCostTable(rows,columns,{},{}).map(r=>r.id)).toEqual([1,2,3,4]);
  expect(queryCostTable(rows,columns,{}, {prop:'name',order:'ascending'}).filter(r=>r.name==='牛肉').map(r=>r.id)).toEqual([1,4]);
});
