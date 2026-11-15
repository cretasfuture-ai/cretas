// 工序任务与产品当前路线的对应分类（A 线规格：区分三类，不当作历史版本已确认）。
// 只用来源已返回的字段做确定性判断，不按名称或时间猜配。
import type {Row,Source} from '../frame/types'
export type Mapping='matched'|'matched-cretas'|'no-route'|'not-in-route'|'no-product'
export function mappingOf(source:Source,task:Row):Mapping{
  const r=source.resources,project=(r.project||[]).find(p=>String(p.id)===String(task.projectId))
  const products=(r.product||[]).filter(p=>p.productCode===project?.productCode)
  if(products.length!==1)return 'no-product'
  const route=(r.routing||[]).find(x=>x.processRoutingCode===products[0].prcessRoutingCode)
  const steps=Array.isArray(route?.processRoutingSteps)?route!.processRoutingSteps as Row[]:[]
  if(!route||!steps.length)return 'no-route'
  const code=(r.process||[]).find(p=>String(p.id)===String(task.processId))?.code
  // 第五批 ①：路线来自 Cretas 补充（effectiveSource 叠加）时单独标出，不和黑湖路线混为一谈
  return code&&steps.some(s=>s.code===code)?(route._source==='cretas'?'matched-cretas':'matched'):'not-in-route'
}
export const mappingLabel=(m:Mapping)=>({matched:'当前路线可对应','matched-cretas':'补充路线可对应','no-route':'产品无当前路线','not-in-route':'当前路线不含此工序','no-product':'产品编码不唯一'})[m]
export function orderMapping(source:Source,projectId:string){
  const tasks=(source.resources.task||[]).filter(t=>String(t.projectId)===projectId)
  if(!tasks.length)return '来源未返回任务'
  const ms=new Set(tasks.map(t=>mappingOf(source,t)))
  return ms.size===1?mappingLabel([...ms][0]):'混合：'+[...ms].map(mappingLabel).join(' / ')
}
