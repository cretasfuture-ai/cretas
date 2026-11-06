/** Read-only interpretation of collected labor. Never writes payroll or replaces ERP settlement. */
export interface LaborCostRow {
  id: string;
  process: string;
  amount: number | null;
  reportedHours: number | null;
  personHours: number | null;
  segmentWorkers?: number | null;
  /** Only actual participant IDs; reporter/operator IDs are not a worker roster. */
  workerIds?: string[];
  rosterComplete?: boolean;
  registeredWorkerKeys?:string[];
  registeredCoverageComplete?:boolean;
  sourceEffectiveHours?:number|null;
  registeredReports?:number;
  zeroTimeReports?:number;
  overlapReports?:number;
}
export interface LaborDataRequest {id:string;orderCode:string;process:string;missing:string;owner:string;action:string;benefit:string}
export interface LaborCostInput {
  rows: LaborCostRow[];
  total: number | null;
  output: number | null;
  unit: string | null;
  requests?:LaborDataRequest[];
  blacklakeOrderIds?:number[];sourceRunId?:string;
}
const finite=(v:number|null|undefined):v is number=>typeof v==='number'&&Number.isFinite(v);
const sum=(values:(number|null|undefined)[])=>{if(!values.length||!values.every(finite))return null;const result=values.reduce<number>((a,b)=>a+(b as number),0);return Number.isFinite(result)?result:null;};
const divide=(a:number|null,b:number|null)=>finite(a)&&finite(b)&&b>0?a/b:null;

/** Money rounded once; final positive-weight process receives the cent remainder. */
export function allocateLaborByPersonHours(total:number|null,weights:(number|null)[]):(number|null)[]{
  const unavailable=():null[]=>weights.map(():null=>null);
  if(!finite(total)||total<0||!weights.length||weights.some(w=>!finite(w)||w<0))return unavailable();
  const totalWeight=sum(weights);if(totalWeight===null||totalWeight<=0)return unavailable();
  const cents=Math.round((total+Number.EPSILON)*100);
  if(!Number.isSafeInteger(cents))return unavailable();
  let last=-1;for(let index=weights.length-1;index>=0;index--){if(finite(weights[index])&&weights[index]!>0){last=index;break;}}let used=0;
  return weights.map((w,index)=>{
    const share=index===last?cents-used:Math.min(cents-used,Math.round(cents*(w as number)/totalWeight));
    used+=share;return share/100;
  });
}

export function analyzeLaborCosts(input:LaborCostInput){
  const rows=input.rows;
  const detailTotal=sum(rows.map(row=>row.amount));
  const total=finite(input.total)?input.total:null;
  const reconciled=total!==null&&total>=0&&detailTotal!==null&&rows.every(r=>finite(r.amount)&&r.amount>=0)&&Math.round((total+Number.EPSILON)*100)===Math.round((detailTotal+Number.EPSILON)*100);
  const personHours=sum(rows.map(row=>finite(row.personHours)&&row.personHours>=0?row.personHours:null));
  const rosterReady=rows.length>0&&rows.every(row=>row.rosterComplete===true&&!!row.workerIds?.length&&row.workerIds.every(id=>typeof id==='string'&&id.trim().length>0));
  const participants=rosterReady?new Set(rows.flatMap(row=>row.workerIds?.map(id=>id.trim())||[])).size:null;
  const preview=reconciled?allocateLaborByPersonHours(total,rows.map(row=>row.personHours)):rows.map(():null=>null);
  const sourceAvailable=rows.some(row=>row.registeredCoverageComplete!==undefined);
  const registeredKeys=new Set(rows.flatMap(row=>row.registeredWorkerKeys||[]));
  const registeredCoverage=sourceAvailable&&rows.every(row=>row.registeredCoverageComplete===true);
  const registeredParticipants=registeredKeys.size||null;
  return {
    total,detailTotal,reconciled,personHours,participants,
    sourceAvailable,registeredParticipants,registeredCoverage,
    registeredPerPerson:registeredCoverage&&reconciled?divide(total,registeredParticipants):null,
    sourceEffectiveHours:sum(rows.map(row=>row.sourceEffectiveHours)),
    registeredReports:rows.reduce((a,r)=>a+(r.registeredReports||0),0),
    zeroTimeReports:rows.reduce((a,r)=>a+(r.zeroTimeReports||0),0),
    overlapReports:rows.reduce((a,r)=>a+(r.overlapReports||0),0),
    perPerson:reconciled?divide(total,participants):null,
    perPersonHour:reconciled?divide(total,personHours):null,
    perUnit:input.unit?divide(total,input.output):null,
    canAllocate:preview.length>0&&preview.every(finite),
    rows:rows.map((row,index)=>({...row,
      perUnit:input.unit?divide(row.amount,input.output):null,
      share:reconciled?divide(row.amount,total):null,
      segmentPerPerson:Number.isInteger(row.segmentWorkers)&&row.segmentWorkers!>0?divide(row.amount,row.segmentWorkers!):null,
      allocated:preview[index]??null,
    })),
    needs:[
      ...(!rosterReady?[{key:'people',missing:sourceAvailable?'登记人员是否覆盖实际班组':'实际参与人员名单',owner:'生产主管 / 考勤负责人',action:sourceAvailable?'核对是否由班组长代报；补齐实际参与名单，同一人跨工序去重':'补齐所选工单的实际参与人员，并按人员去重',benefit:'计算真实人均人工费，避免把班组工资除以代报人数'}]:[]),
      ...(personHours===null?[{key:'hours',missing:'各工序的有效人时',owner:'车间主管',action:sourceAvailable?'确认每段报工实际有多少人参与，并核对零时长和重叠时段；有效时长不能直接当人时':'补齐每段的起止时间和人数；仅有报工时长不够',benefit:'计算每人时费用，并按人时测算工序分摊'}]:[]),
      ...(rows.some(row=>row.amount===0)?[{key:'zero',missing:'零工资工序的计薪方式',owner:'生产 / 人事',action:'确认这些工序是否合并计薪、工资另记或尚未补录',benefit:'避免把未记工资的工序当成不花钱'}]:[]),
      ...(!reconciled?[{key:'cost',missing:'工序人工明细与工单人工合计的核对结果',owner:'生产 / 财务',action:'检查漏记、重复工序或金额缺失',benefit:'让工序人工相加与本单人工费一致'}]:[]),
    ],
  };
}

export interface MesLaborDetail {
  processOrder?:number;processName?:string;amount?:number|null;
  durationMinutes?:number|null;laborHours?:number|null;workerCount?:number|null;
  startTime?:string;endTime?:string;
}
/** Native MES laborHours is already person-hours; never multiply it by workers again. */
export function mesLaborRows(rows:MesLaborDetail[]):LaborCostRow[]{
  return rows.map((row,index)=>({id:`mes:${row.processOrder??'unknown'}:${index}`,process:row.processName||`工序${row.processOrder??index+1}`,
    amount:finite(row.amount)?row.amount:null,personHours:finite(row.laborHours)?row.laborHours:null,
    reportedHours:finite(row.durationMinutes)?row.durationMinutes/60:null,
    segmentWorkers:row.startTime&&row.endTime&&finite(row.workerCount)?row.workerCount:null,
  }));
}
