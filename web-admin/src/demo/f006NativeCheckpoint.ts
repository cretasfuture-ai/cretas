import type { DemoEntity, DemoState } from './f006Flow'
import type { NativeDemoStepId } from './f006NativeBridge'

/** UI handlers often catch and return void. A resolved click is never a committed step. */
export function assertNativeCheckpoint(step: NativeDemoStepId, state: DemoState): void {
  const fail=(message:string):never=>{throw new Error(`原生页面提交尚未验证：${message}`)}
  const get=(key:string,identity=true):DemoEntity=>{
    const value=state.entities[key]
    if(!value||typeof value!=='object'||(identity&&(value.id===undefined||value.id===null||value.id==='')))return fail(`${key}缺少真实业务记录`)
    if(value.factoryId&&value.factoryId!=='F006')return fail(`${key}工厂不一致`)
    return value
  }
  const owned=(key:string)=>{const value=get(key);if(!String(value.remark??value.notes??'').includes(state.runKey))return fail(`${key}不是本轮单据`);return value}
  if(step.startsWith('purchase-')&&!step.includes('receive')){
    const order=owned('purchaseOrder')
    if(step==='purchase-link-sales'&&String(order.salesOrderId??order.relatedSalesOrderId)!==String(get('salesOrder').id))fail('采购没有关联本轮销售需求')
    if(step==='purchase-submit'&&!['SUBMITTED','WORKFLOW_RUNNING','APPROVED','PENDING_FINANCE_REVIEW','FINANCE_APPROVED','PARTIAL_RECEIVED','COMPLETED'].includes(String(order.status)))fail('采购未进入审批')
    if(step==='purchase-approve'&&!['FINANCE_APPROVED','PARTIAL_RECEIVED','COMPLETED'].includes(String(order.status)))fail('采购尚未获批')
    return
  }
  if(step.startsWith('sales-order')){
    const order=owned('salesOrder')
    if(step==='sales-order-submit'&&!['PENDING_FINANCE_REVIEW','WORKFLOW_RUNNING','FINANCE_APPROVED','PROCESSING','COMPLETED','PARTIAL_DELIVERED'].includes(String(order.status)))fail('销售未进入审批')
    if(step==='sales-order-approve'&&!['FINANCE_APPROVED','PROCESSING','COMPLETED','PARTIAL_DELIVERED'].includes(String(order.status)))fail('销售审批尚未完成')
    return
  }
  if(step.startsWith('purchase-receive')){
    const receipt=get('purchaseReceipt')
    if(String(receipt.purchaseOrderId)!==String(get('purchaseOrder').id))fail('收货单不属于本轮采购')
    if(step==='purchase-receive-confirm'){
      const items=receipt.items
      if(receipt.status!=='CONFIRMED'||!Array.isArray(items)||!items.length||items.some(item=>!item||typeof item!=='object'||!(item as Record<string,unknown>).materialBatchId))fail('原料和包材批次尚未入库')
    }
    return
  }
  if(step==='production-plan-create'){owned('productionPlan');return}
  if(step==='production-batch-create'){const batch=get('productionBatch');if(String(batch.productionPlanId)!==String(get('productionPlan').id))fail('生产批次不属于本轮计划');return}
  if(step==='material-requisition'){
    const requisition=get('materialRequisition')
    if(requisition.status!=='ISSUED')fail('本轮领料尚未签收')
    if(!Array.isArray(requisition.items)||!requisition.items.length)fail('本轮领料没有真实明细')
    for(const raw of requisition.items as unknown[]){const item=raw as Record<string,unknown>;if(!Array.isArray(item.batchNumbers)||!item.batchNumbers.length||item.batchNumbers.some(batch=>!batch||typeof batch!=='object'||!(batch as Record<string,unknown>).workshopBatchId))fail('领料未形成生产仓批次')}
    return
  }
  if(step.startsWith('process-report-')){
    const order=Number(step.split('-').at(-1)),row=get(`processReport${order}`,false)
    if(row.clientRowId!==`${state.runKey}:p${order}`||row.submissionStatus!=='SUBMITTED'||!row.batchNumber)fail(`工序${order}缺少本轮持久化报工行或真实产出批次`)
    return
  }
  if(step==='production-settle'){if(get('productionSettlement',false).postingStatus!=='PENDING_WAREHOUSE_RECEIPT')fail('结单尚未等待仓库入库');return}
  if(step==='finished-goods-receive'){if(!['POSTED','POSTED_WITH_TOLERANCE'].includes(String(get('productionWarehouseReceipt',false).postingStatus)))fail('成品尚未入库');return}
  if(step==='supplemental-costs'){const cost=get('supplementalCosts',false);if(cost.calculationStatus!=='COMPLETE'||cost.costComplete!==true||cost.missingCostItemCount!==0)fail('费用补录后成本仍不完整');return}
  if(step==='sales-delivery-create'||step==='sales-shipment-create'){
    const delivery=get(step==='sales-delivery-create'?'salesDelivery':'salesShipment')
    if(String(delivery.salesOrderId)!==String(get('salesOrder').id))fail('发运单不属于本轮销售')
    return
  }
  if(step==='sales-batch-allocate'){const allocation=get('salesBatchAllocation',false);if(!Array.isArray(allocation.allocations)||!allocation.allocations.length)fail('销售没有真实成品批次分配');return}
  if(step==='sales-warehouse-confirm'){if(!['SHIPPED','DELIVERED'].includes(String(get('salesShipment').status)))fail('仓库尚未确认出库')}
}

/** Bind every production proof to actual scoped readbacks, never to locally supplied IDs. */
export async function verifyNativeProductionScope(step: NativeDemoStepId, state: DemoState, read: (path: string) => Promise<unknown>): Promise<void> {
  if (!['production-plan-create','production-batch-create','material-requisition','production-settle','finished-goods-receive','supplemental-costs'].includes(step) && !step.startsWith('process-report-')) return
  const object=(value:unknown):DemoEntity=>value&&typeof value==='object'&&!Array.isArray(value)?value as DemoEntity:{}
  const id=(value:unknown)=>String(value??'')
  const requireSame=(actual:unknown,expected:unknown,label:string)=>{if(!id(actual)||!id(expected)||id(actual)!==id(expected))throw new Error(`真实来源核对失败：${label}`)}
  const planId=id(state.entities.productionPlan?.id)
  if(!planId)throw new Error('缺少本轮真实计划编号')
  const plan=object(await read(`/production-plans/${encodeURIComponent(planId)}`))
  requireSame(plan.id,planId,'计划编号')
  requireSame(plan.sourceOrderId??plan.salesOrderId,state.entities.salesOrder?.id,'生产计划对应的销售单')
  if(plan.factoryId&&plan.factoryId!=='F006')throw new Error('真实来源核对失败：计划工厂')
  state.readbacks.nativeProductionPlan=plan
  if(step==='material-requisition')requireSame(state.entities.materialRequisition?.productionPlanId,planId,'领料单对应的计划')
  if(state.entities.productionBatch?.id){
    const batch=object(await read(`/processing/batches/${encodeURIComponent(id(state.entities.productionBatch.id))}`))
    requireSame(batch.id,state.entities.productionBatch.id,'生产批次编号')
    requireSame(batch.productionPlanId,planId,'批次对应的计划')
    state.readbacks.nativeProductionBatch=batch
    if(['production-settle','finished-goods-receive','supplemental-costs'].includes(step))requireSame(batch.batchNumber,state.entities.processReport6?.batchNumber,'最终报工产出批次')
  }
  if(step.startsWith('process-report-')){
    const order=Number(step.split('-').at(-1)),process=state.context?.processes[order-1]
    if(!process||process.order!==order)throw new Error('真实来源核对失败：工序顺序')
    const response=await read(`/production-plans/${encodeURIComponent(planId)}/process-sheet/rows?process=${encodeURIComponent(process.code)}&processOrder=${order}`)
    const items=Array.isArray(response)?response:object(response).data
    if(!Array.isArray(items))throw new Error('本轮计划工序回读格式不正确')
    const matches=items.map(object).filter(row=>row.clientRowId===`${state.runKey}:p${order}`&&row.submissionStatus==='SUBMITTED')
    if(matches.length!==1)throw new Error('本轮计划没有唯一的已提交工序行')
    const row=matches[0]!,payload=object(row.payload??row)
    if(row.processOrder!==undefined)requireSame(row.processOrder,order,'已提交行的工序序号')
    if(row.productionPlanId!==undefined)requireSame(row.productionPlanId,planId,'已提交行的计划')
    requireSame(row.batchNumber??payload.batchNumber,state.entities[`processReport${order}`]?.batchNumber,'工序产出批号')
    state.readbacks[`nativeProcessScope${order}`]=row
  }
  if(['production-settle','finished-goods-receive'].includes(step)){
    const actual=object(await read(`/production-plans/${encodeURIComponent(planId)}/settlement`))
    const expected=state.entities[step==='production-settle'?'productionSettlement':'productionWarehouseReceipt']
    requireSame(actual.postingStatus,expected?.postingStatus,'计划入账状态')
    state.readbacks.nativeSettlement=actual
  }
  if(step==='supplemental-costs'){
    const actual=object(await read(`/production/batches/${encodeURIComponent(id(state.entities.processReport6?.batchNumber))}/cost-breakdown`))
    if(actual.calculationStatus!=='COMPLETE'||actual.costComplete!==true||actual.missingCostItemCount!==0)throw new Error('本轮实际成品成本尚未完整')
    state.entities.supplementalCosts=actual;state.readbacks.supplementalCosts=actual
  }
}
