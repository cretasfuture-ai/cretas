// 第六批：Cretas 入库批次的只读加载（追溯视图等页面共用）。按当前验收会话读取，不缓存跨会话。
import {supplySession,supplyUrl} from './supply-store'
export interface CBatch{id:string;batchNo:string;kind:'production'|'purchase';productCode:string;productName:string;unit:string;qty:number;productionDate:string;expiryDate:string;blOrderNo:string|null;blLineId:string|null;projectCode:string|null;revokedAt?:string}
export async function readBatches():Promise<CBatch[]>{
  const s=supplySession();const r=await fetch(supplyUrl('/batches'),{headers:s?{'x-acceptance-session':s}:{}}),b=await r.json()
  if(!b.success)throw Error(b.message||'入库批次读取失败')
  return ((b.data.batches||[]) as CBatch[]).filter(x=>!x.revokedAt)
}
/** 黑湖入库单行 → Cretas 批次（键：黑湖入库单号#行 ID） */
export function batchTraceIndex(list:CBatch[]){const m=new Map<string,CBatch[]>();for(const b of list){if(!b.blOrderNo)continue;const k=b.blOrderNo+'#'+b.blLineId;m.set(k,[...(m.get(k)||[]),b])}return m}
