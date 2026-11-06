import { describe, expect, it, vi } from 'vitest'
import type { DemoState } from '../f006Flow'
import { assertNativeCheckpoint, verifyNativeProductionScope } from '../f006NativeCheckpoint'
const state=():DemoState=>({schemaVersion:2,factoryId:'F006',runKey:'DEMO-F006-FLOW-20261105-unit',currentStep:10,entities:{salesOrder:{id:'so'},productionPlan:{id:'plan'},productionBatch:{id:'batch'},processReport6:{batchNumber:'final'},materialRequisition:{id:'req',productionPlanId:'plan'}},readbacks:{},completed:[]})
describe('native production proof is bound to actual business lineage',()=>{
 it('rejects a plan attached to a different sales order',async()=>{
  await expect(verifyNativeProductionScope('production-plan-create',state(),async()=>({id:'plan',sourceOrderId:'other'}))).rejects.toThrow('销售单')
 })
 it('rejects a production batch from a different plan',async()=>{
  const read=vi.fn().mockResolvedValueOnce({id:'plan',sourceOrderId:'so'}).mockResolvedValueOnce({id:'batch',productionPlanId:'other'})
  await expect(verifyNativeProductionScope('production-batch-create',state(),read)).rejects.toThrow('批次对应的计划')
 })
 it('rejects a requisition from another plan',async()=>{
  const value=state();value.entities.materialRequisition!.productionPlanId='other'
  await expect(verifyNativeProductionScope('material-requisition',value,async()=>({id:'plan',sourceOrderId:'so'}))).rejects.toThrow('领料单')
 })
 it('rejects the wrong finished batch even when the posting status looks complete',async()=>{
  const read=vi.fn().mockResolvedValueOnce({id:'plan',sourceOrderId:'so'}).mockResolvedValueOnce({id:'batch',productionPlanId:'plan',batchNumber:'other'})
  await expect(verifyNativeProductionScope('finished-goods-receive',state(),read)).rejects.toThrow('最终报工产出批次')
 })
})

it('recognizes actual financially approved purchase status and rejects unfinished approvals',()=>{
 const value=state();value.entities.purchaseOrder={id:'po',factoryId:'F006',remark:value.runKey,status:'FINANCE_APPROVED'}
 expect(()=>assertNativeCheckpoint('purchase-approve',value)).not.toThrow()
 for(const status of ['WORKFLOW_RUNNING','PENDING_FINANCE_REVIEW','APPROVED']){value.entities.purchaseOrder.status=status;expect(()=>assertNativeCheckpoint('purchase-approve',value)).toThrow('采购尚未获批')}
})
