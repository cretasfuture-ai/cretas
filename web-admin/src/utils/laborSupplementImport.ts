import * as XLSX from 'xlsx';

export interface LaborSegmentEntry {start:string;end:string;workers:string[];breakMinutes:number;shutdownMinutes:number}
export interface LaborTaskEntry {projectId:number;taskId:number;segments:LaborSegmentEntry[];confirmed:boolean;expectedRevision:number;source:'manual'|'excel'|'cretas_mes';reference:string;zeroConfirmed:boolean}
export interface LaborTaskStatus {projectId:number;projectCode:string;product:string;taskId:number;process:string;laborCost:number|null;revision:number;status:string;rosterComplete?:boolean;participants:number|null;personHours:number|null;updatedAt?:string;source?:string;reference?:string}
export const LABOR_HEADERS=['工单号','任务ID','工序','开始时间','结束时间','参与人员代码','休息分钟','停机分钟','零人时确认','核对依据'];
export function laborTemplate(tasks:LaborTaskStatus[]):Uint8Array {
  const rows=tasks.map(t=>[t.projectCode,String(t.taskId),t.process,'','','',0,0,'否','']);
  const book=XLSX.utils.book_new();XLSX.utils.book_append_sheet(book,XLSX.utils.aoa_to_sheet([LABOR_HEADERS,...rows]),'人工补充');
  XLSX.utils.book_append_sheet(book,XLSX.utils.aoa_to_sheet([['填写说明'],['每行一段作业。相同任务可以分多行，人员变化请分段。'],['时间格式：2026-11-06 08:00；人员填员工代码，用逗号分开，同一人始终用同一代码。'],['休息、停机分钟从该段总时长扣除；同一人的时间不能交叉。'],['不计时任务可填零人时确认=是，时间和人员留空；零人时不能证明实际人员名单。'],['核对依据填报工单、班组记录或考勤凭证编号，不填工资和个人联系方式。'],['工单、任务ID与工序必须对应模板；仅导入本次产品范围内的任务。']]),'填写说明');
  return new Uint8Array(XLSX.write(book,{type:'array',bookType:'xlsx'}));
}
function time(value:unknown):string {
  if(typeof value==='number'){const d=XLSX.SSF.parse_date_code(value);if(!d)throw Error('时间无效');return `${d.y}-${String(d.m).padStart(2,'0')}-${String(d.d).padStart(2,'0')}T${String(d.H).padStart(2,'0')}:${String(d.M).padStart(2,'0')}:${String(d.S).padStart(2,'0')}+08:00`;}
  const s=String(value??'').trim().replace(' ','T');if(!/^\d{4}-\d\d-\d\dT\d\d:\d\d(:\d\d)?$/.test(s))throw Error('时间请填写年月日和时分');return `${s}+08:00`;
}
export function importLaborRows(buffer:ArrayBuffer,tasks:LaborTaskStatus[]):{entries:LaborTaskEntry[];errors:string[]} {
  if(buffer.byteLength>2*1024*1024)throw Error('文件请控制在2MB以内，按产品分批导入');
  const book=XLSX.read(buffer,{type:'array',cellFormula:true});const sheet=book.Sheets[book.SheetNames[0]||''];if(!sheet)throw Error('文件没有数据表');
  if(Object.values(sheet).some(cell=>typeof cell==='object'&&cell&&'f' in cell))throw Error('请导入数值和文字，不接受公式单元格');
  const rows=XLSX.utils.sheet_to_json<unknown[]>(sheet,{header:1,raw:true,defval:''});
  if(rows.length>1001)throw Error('每批最多1000个时段');
  const headers=rows[0]?.map(String)||[];if(LABOR_HEADERS.some(h=>!headers.includes(h)))throw Error('请使用下载的人工补充模板，表头不完整');
  const entries=new Map<number,LaborTaskEntry>(),errors:string[]=[];
  rows.slice(1).forEach((row,index)=>{if(row.every(v=>v===''))return;try{
    const get=(h:string)=>row[headers.indexOf(h)];const task=tasks.find(t=>String(t.taskId)===String(get('任务ID')).trim());
    if(!task||String(get('工单号')).trim()!==task.projectCode||String(get('工序')).trim()!==task.process)throw Error('工单、任务和工序未对应，请从模板选择');
    const reference=String(get('核对依据')).trim();if(!reference)throw Error('请补核对依据');
    const zero=String(get('零人时确认')).trim()==='是';const previous=entries.get(task.taskId);
    if(previous&&(previous.zeroConfirmed!==zero||previous.reference!==reference))throw Error('同一任务的零人时确认和依据应一致');
    const entry=previous||{projectId:task.projectId,taskId:task.taskId,segments:[],confirmed:true,expectedRevision:task.revision,source:'excel' as const,reference,zeroConfirmed:zero};
    if(zero){if(['开始时间','结束时间','参与人员代码'].some(h=>String(get(h)).trim()))throw Error('零人时确认任务请留空时间和人员');}
    else {const workers=String(get('参与人员代码')).split(/[,，;；\s]+/).filter(Boolean);if(!workers.length||new Set(workers).size!==workers.length)throw Error('人员代码不能为空或重复');
      const start=time(get('开始时间')),end=time(get('结束时间'));const rest=Number(get('休息分钟')),stop=Number(get('停机分钟')),minutes=(Date.parse(end)-Date.parse(start))/60000;
      if(!Number.isFinite(minutes)||minutes<=0||minutes>1440||![rest,stop].every(n=>Number.isFinite(n)&&n>=0)||rest+stop>minutes)throw Error('时段或休息、停机分钟不正确');
      entry.segments.push({start,end,workers,breakMinutes:rest,shutdownMinutes:stop});}
    entries.set(task.taskId,entry);
  }catch(e){errors.push(`第${index+2}行：${e instanceof Error?e.message:'内容无效'}`);}});
  return {entries:[...entries.values()],errors};
}
export function downloadLaborTemplate(tasks:LaborTaskStatus[]){const blob=new Blob([new Uint8Array(laborTemplate(tasks))],{type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='工单工序人工补充.xlsx';a.click();URL.revokeObjectURL(url);}
