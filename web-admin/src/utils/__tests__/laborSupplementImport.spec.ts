import {describe,it,expect} from 'vitest';
import * as XLSX from 'xlsx';
import {LABOR_HEADERS,laborTemplate,importLaborRows,type LaborTaskStatus} from '../laborSupplementImport';
const tasks:LaborTaskStatus[]=[{projectId:1,projectCode:'GD1',product:'样品',taskId:11,process:'包装',laborCost:100,revision:2,status:'missing',participants:null,personHours:null}];
const excel=(rows:unknown[][])=>{const book=XLSX.utils.book_new();XLSX.utils.book_append_sheet(book,XLSX.utils.aoa_to_sheet([LABOR_HEADERS,...rows]),'人工补充');return XLSX.write(book,{type:'array',bookType:'xlsx'}) as ArrayBuffer;};
describe('labor imports match exact work order and task before saving',()=>{
  it('exports a task-specific template and does not treat blank template as actual attendance',()=>{
    const result=importLaborRows(laborTemplate(tasks).buffer as ArrayBuffer,tasks);expect(result.entries).toEqual([]);expect(result.errors[0]).toContain('核对依据');
  });
  it('groups multiple intervals for the same task and retains revision for optimistic write',()=>{
    const result=importLaborRows(excel([['GD1','11','包装','2026-11-06 08:00','2026-11-06 10:00','E1,E2',10,5,'否','shift-1'],['GD1','11','包装','2026-11-06 11:00','2026-11-06 12:00','E1',0,0,'否','shift-1']]),tasks);
    expect(result.errors).toEqual([]);expect(result.entries).toHaveLength(1);expect(result.entries[0]?.expectedRevision).toBe(2);expect(result.entries[0]?.segments).toHaveLength(2);expect(result.entries[0]?.segments[0]?.start).toBe('2026-11-06T08:00+08:00');
  });
  it('blocks wrong project/process, invalid times and duplicate people without assigning unknown data',()=>{
    const result=importLaborRows(excel([['OTHER','11','包装','','','',0,0,'是','test'],['GD1','11','切配','','','',0,0,'是','test'],['GD1','11','包装','2026-11-06 08:00','2026-11-06 09:00','E1,E1',0,0,'否','test']]),tasks);expect(result.errors).toHaveLength(3);expect(result.entries).toEqual([]);
  });
  it('rejects cached values from formula cells and never guesses missing headers',()=>{
    const book=XLSX.utils.book_new(),sheet=XLSX.utils.aoa_to_sheet([LABOR_HEADERS]);sheet.A2={t:'s',f:'HYPERLINK("https://example.invalid")',v:'GD1'};sheet['!ref']='A1:J2';XLSX.utils.book_append_sheet(book,sheet,'data');
    expect(()=>importLaborRows(XLSX.write(book,{type:'array',bookType:'xlsx'}),tasks)).toThrow('公式');
    expect(()=>importLaborRows(excel([]),[])).not.toThrow();
  });
});
