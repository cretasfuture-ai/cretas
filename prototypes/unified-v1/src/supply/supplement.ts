// 补充入口：复用 D 线共享件 CretasUI.supplementForm / supplementImport（SHARED.md 第 1 批），不另写表单。
// 保存、回读、撤销接口由 C 线 supply-lsm.cjs 实现（/__lsm/supply/*）。
import {cretasUI} from '../frame/ai-protocol'
import {T,post,readSupply,supplyUrl} from './supply-store'
import type {SupplyState} from './supply-store'
export interface Target{kind:'stock'|'opening'|'lineBatch'|'lineCost';productId?:string;warehouseId?:string;lineId?:string;title:string;unit?:string;asOf?:string;sourceValue?:string}
const today=()=>new Date(Date.now()+8*3600e3).toISOString().slice(0,10)
export function openSupplement(t:Target,onHistory?:()=>void){
  if(!T.supply){T.notice=T.supplyError||'补充保存区不可用';return}
  const isQty=t.kind==='stock'||t.kind==='opening'
  const fields=isQty?[
    {key:'qty',label:'数量',type:'number' as const,required:true,min:0,sourceValue:t.sourceValue??'黑湖未返回'},
    {key:'unit',label:'单位',required:true,value:t.unit||''},
    {key:'asOf',label:t.kind==='opening'?'期初日（YYYY-MM-DD）':'时点（YYYY-MM-DD）',required:true,value:t.asOf||today()}
  ]:t.kind==='lineBatch'?[{key:'batchNo',label:'批次号',required:true,sourceValue:'黑湖无批次字段'}]:[
    {key:'amount',label:'金额',type:'number' as const,required:true,min:0,sourceValue:t.sourceValue??'黑湖未返回'},
    {key:'currency',label:'币种',required:true,value:'CNY'}
  ]
  cretasUI().supplementForm({title:'补充'+({stock:'在库余额',opening:'期初库存',lineBatch:'批次号',lineCost:'单据金额'})[t.kind],object:t.title,fields,
    onSubmit:async({values,reason})=>{
      if(isQty&&!/^\d{4}-\d{2}-\d{2}$/.test(String(values.asOf||'')))throw Error('日期格式应为 YYYY-MM-DD')
      await post<SupplyState>('/supplement',{kind:t.kind,productId:t.productId,warehouseId:t.warehouseId,lineId:t.lineId,value:values,reason,expectedVersion:T.supply!.version})
      await readSupply()
      return onHistory?{viewHistory:onHistory}:undefined
    }})
}
export function openImport(onHistory?:()=>void){
  if(!T.supply){T.notice=T.supplyError||'补充保存区不可用';return}
  let token=''
  cretasUI().supplementImport({title:'批量补充（Excel）',templateHref:supplyUrl('/template'),templateLabel:'下载补充模板',
    note:'只补黑湖没有返回的数据；黑湖已有值的行会被忽略，不覆盖来源。只写本机原型，可在“补充记录”撤销。',
    onParse:async(file:File)=>{
      if(file.size>5_000_000)throw Error('文件超过 5MB')
      const buf=new Uint8Array(await file.arrayBuffer());let bin='';for(let i=0;i<buf.length;i+=0x8000)bin+=String.fromCharCode(...buf.subarray(i,i+0x8000))
      const d=await post<{token:string;rows:{row:number;label:string;target?:string;status:string;message:string}[]}>('/preflight',{fileBase64:btoa(bin)})
      token=d.token
      return {rows:d.rows.filter(r=>r.status==='ok'),errors:d.rows.filter(r=>r.status!=='ok').map(r=>'第 '+r.row+' 行（'+(r.target||r.label)+'）：'+r.message)}
    },
    onConfirm:async()=>{const d=await post<{written:number}>('/confirm',{token});await readSupply();return {count:d.written,viewHistory:onHistory}}
  })
}
