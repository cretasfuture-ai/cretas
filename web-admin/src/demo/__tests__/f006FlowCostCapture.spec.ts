import { describe, expect, it } from 'vitest'
import { DEMO_RUN_KEY, DEMO_STEPS, createDemoState, executeStep, prepareStep, type DemoState } from '../f006Flow'
import type { DemoRecord, DemoTransport } from '../f006DemoSupport'

const admin = { id: 1309, factoryId: 'F006', roleCode: 'factory_super_admin' }
const finishedStep = 'finished-goods-receive' as const
const plan = (runKey: string): DemoRecord => ({ id: 'plan-1', factoryId: 'F006', notes: runKey, status: 'COMPLETED' })
const batch: DemoRecord = { id: 'batch-1', factoryId: 'F006', productionPlanId: 'plan-1', batchNumber: 'PB-1' }
const settlement: DemoRecord = {
  postingStatus: 'POSTED',
  outputLines: [{ productTypeId: 'fg-1', finishedGoodsBatchId: 'fg-batch-1' }],
}

function state(): DemoState {
  const s = createDemoState(`${DEMO_RUN_KEY}-cost-test`)
  s.currentStep = DEMO_STEPS.findIndex(step => step.id === finishedStep)
  s.context = {
    supplier: { id: 'supplier-1' }, customer: { id: 'customer-1' }, material: { id: 'material-1', unit: 'kg' }, packaging: { id: 'pack-1', unit: '个' },
    product: { id: 'fg-1', unit: '盒' }, rawWarehouse: { id: 'raw-wh-1' }, workshopWarehouse: { id: 'workshop-wh-1' }, processes: [],
    rawQuantity: 1.25, finishedQuantity: 4,
  }
  s.entities.productionPlan = plan(s.runKey)
  s.entities.productionBatch = batch
  s.entities.processReport6 = { batchNumber: 'PB-1' }
  return s
}

function transportFor(s: DemoState, cost: DemoRecord, settlementValue: DemoRecord = settlement) {
  const reads: string[] = [], posts: string[] = [], puts: string[] = []
  const transport: DemoTransport = {
    get: async <T>(path: string) => {
      reads.push(path)
      if (path === '/auth/me') return admin as T
      if (path === `/F006/production-plans/${s.entities.productionPlan.id}`) return plan(s.runKey) as T
      if (path === '/F006/processing/batches/batch-1') return batch as T
      if (path.endsWith('/settlement')) return settlementValue as T
      if (path.endsWith('/cost-breakdown')) return cost as T
      throw new Error(`unexpected read: ${path}`)
    },
    post: async <T>(path: string) => {
      posts.push(path)
      return { id: 'receipt-1', factoryId: 'F006' } as T
    },
    put: async <T>(path: string) => {
      puts.push(path)
      return {} as T
    },
  }
  return { transport, reads, posts, puts }
}

describe('F006 demo supplemental cost capture', () => {
  it('prefills device and other costs and writes then reads back complete costs before advancing', async () => {
    const s = state()
    const prepared = prepareStep(s, finishedStep)
    expect(prepared.fields).toEqual([
      expect.objectContaining({ name: 'equipmentCost', value: 12.8 }),
      expect.objectContaining({ name: 'otherCost', value: 7.2 }),
    ])
    const { transport, puts } = transportFor(s, { calculationStatus: 'COMPLETE', costComplete: true, missingCostItemCount: 0, totalCost: 20 })

    await executeStep(s, finishedStep, { equipmentCost: 12.8, otherCost: 7.2 }, transport)

    expect(puts).toEqual(['/F006/production-plans/plan-1/costs?equipmentCost=12.8&otherCost=7.2'])
    expect(s.currentStep).toBe(DEMO_STEPS.findIndex(step => step.id === finishedStep) + 1)
    expect(s.entities.productionWarehouseReceipt).toMatchObject({ finishedGoodsBatchId: 'fg-batch-1', calculationStatus: 'COMPLETE' })
  })

  it('allows zero, but rejects empty, negative and non-finite costs without writes', async () => {
    for (const value of ['', null, undefined, -1, Number.NaN, Number.POSITIVE_INFINITY]) {
      const s = state()
      const { transport, posts, puts } = transportFor(s, { calculationStatus: 'COMPLETE', costComplete: true, missingCostItemCount: 0 })
      await expect(executeStep(s, finishedStep, { equipmentCost: value, otherCost: 0 }, transport)).rejects.toThrow('设备费用')
      expect(posts).toHaveLength(0)
      expect(puts).toHaveLength(0)
      expect(s.currentStep).toBe(DEMO_STEPS.findIndex(step => step.id === finishedStep))
    }
    const s = state()
    const { transport, puts } = transportFor(s, { calculationStatus: 'COMPLETE', costComplete: true, missingCostItemCount: 0 })
    await executeStep(s, finishedStep, { equipmentCost: 0, otherCost: 0 }, transport)
    expect(puts).toEqual(['/F006/production-plans/plan-1/costs?equipmentCost=0&otherCost=0'])
  })

  it('does not advance on partial cost readback', async () => {
    const s = state()
    const { transport, puts } = transportFor(s, { calculationStatus: 'PARTIAL', costComplete: false, missingCostItemCount: 1 })
    await expect(executeStep(s, finishedStep, { equipmentCost: 12.8, otherCost: 7.2 }, transport)).rejects.toThrow('成本尚未完整')
    expect(puts).toHaveLength(1)
    expect(s.currentStep).toBe(DEMO_STEPS.findIndex(step => step.id === finishedStep))
  })

  it('rejects missing visible fees and a mismatched final batch before any write', async () => {
    const s = state()
    const first = transportFor(s, {})
    await expect(executeStep(s, finishedStep, {}, first.transport)).rejects.toThrow('设备费用')
    expect(first.posts).toHaveLength(0)
    expect(first.puts).toHaveLength(0)
    const second = transportFor(s, {})
    const originalGet = second.transport.get
    second.transport.get = async <T>(path: string) => path.endsWith('/processing/batches/batch-1')
      ? { ...batch, batchNumber: 'ANOTHER-BATCH' } as T : originalGet<T>(path)
    await expect(executeStep(s, finishedStep, { equipmentCost: 12.8, otherCost: 7.2 }, second.transport)).rejects.toThrow('报工批次不一致')
    expect(second.posts).toHaveLength(0)
    expect(second.puts).toHaveLength(0)
  })

  it('keeps the current step when the ordinary cost API rejects the write', async () => {
    const s = state()
    const { transport, reads } = transportFor(s, {})
    transport.put = async () => { throw new Error('cost update rejected') }
    await expect(executeStep(s, finishedStep, { equipmentCost: 12.8, otherCost: 7.2 }, transport)).rejects.toThrow('cost update rejected')
    expect(reads.some(path => path.endsWith('/cost-breakdown'))).toBe(false)
    expect(s.currentStep).toBe(DEMO_STEPS.findIndex(step => step.id === finishedStep))
  })

  it('reuses a warehouse receipt after a non-POSTED settlement and never duplicates that write on retry', async () => {
    const s = state()
    const pending = { ...settlement, postingStatus: 'PENDING' }
    const { transport, posts, puts } = transportFor(s, { calculationStatus: 'COMPLETE', costComplete: true, missingCostItemCount: 0 }, pending)
    await expect(executeStep(s, finishedStep, { equipmentCost: 12.8, otherCost: 7.2 }, transport)).rejects.toThrow('成品尚未确认入库')
    await expect(executeStep(s, finishedStep, { equipmentCost: 12.8, otherCost: 7.2 }, transport)).rejects.toThrow('成品尚未确认入库')
    expect(posts).toEqual(['/F006/production-plans/plan-1/warehouse-receipt'])
    expect(puts).toHaveLength(0)
  })
})
