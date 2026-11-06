import {pythonFetch} from './common';
import type {LaborTaskEntry,LaborTaskStatus} from '@/utils/laborSupplementImport';
export interface LaborSupplementSummary {
  complete:boolean;participants:number|null;personHours:number|null;perPerson:number|null;perPersonHour:number|null;total:number|null;
  processes:{process:string;personHours:number|null;participants:number|null;allocated:number|null}[];
  orders:{projectId:number;complete:boolean;participants:number|null;personHours:number|null;perPerson:number|null;perPersonHour:number|null;total:number|null}[];
}
export interface LaborSupplementStatus {sourceRunId:string;revision:string;editable:boolean;tasks:LaborTaskStatus[];summary:LaborSupplementSummary}
export interface LaborSupplementReceipt {saved:true;savedRevision:string;savedCount:number;editable:boolean}
export type LaborSupplementSaveResult=(LaborSupplementStatus&LaborSupplementReceipt)|(LaborSupplementReceipt&{readbackPending:true});
async function call<T>(path:string,options:RequestInit={}):Promise<T>{
  const response=await pythonFetch<{success:boolean;data:T;message:string}>(path,{...options,preserveResponseKeys:true});
  if(!response.success)throw new Error(response.message||'人工资料暂不可用');
  return response.data;
}
export const getLaborSupplements=(ids:number[])=>call<LaborSupplementStatus>(`/api/smartbi/blacklake/labor-supplements?project_ids=${ids.join(',')}`);
export const saveLaborSupplements=(sourceRunId:string,entries:LaborTaskEntry[])=>call<LaborSupplementSaveResult>('/api/smartbi/blacklake/labor-supplements',{method:'POST',body:JSON.stringify({sourceRunId,entries})});
