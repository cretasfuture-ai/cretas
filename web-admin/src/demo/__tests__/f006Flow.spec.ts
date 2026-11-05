import { describe, expect, it, vi } from 'vitest'
import { DEMO_RUN_KEY, DEMO_STEPS, createDemoState, executeStep, prepareStep, type DemoState } from '../f006Flow'
import { approveWorkflow, type DemoTransport } from '../f006DemoSupport'

const admin = { id: 1309, factoryId: 'F006', roleCode: 'factory_super_admin' }
const master = (id: string, extra: Record<string, unknown> = {}) => ({ id, factoryId: 'F006', ...extra })
function state(): DemoState {
  const s = createDemoState(`${DEMO_RUN_KEY}-test`)
  s.context = {
    supplier: master('sup'), customer: master('cus'),
    material: master('raw', { unit: 'kg', unitPrice: 46 }), packaging: master('pack', { unit: '个', unitPrice: .65 }),
    product: master('fg', { unit: '盒', unitPrice: 27, gramsPerUnit: 250 }),
    rawWarehouse: master('wh'), workshopWarehouse: master('wks'), rawQuantity: 1.25, finishedQuantity: 4,
    processes: Array.from({ length: 6 }, (_, i) => ({ name: `工序${i + 1}`, code: `p${i + 1}`, order: i + 1,
      input: { id: `in:p:${i + 1}`, materialNodeId: i ? `m:semi${i - 1}` : 'm:raw', skuId: i ? `semi${i - 1}` : 'raw', unit: i === 5 ? '盒' : 'kg' },
      output: { id: `out:p:${i + 1}`, materialNodeId: i === 5 ? 'm:fg' : `m:semi${i}`, skuId: i === 5 ? 'fg' : `semi${i}`, unit: i >= 4 ? '盒' : 'kg' },
    })),
  }
  return s
}
function at(s: DemoState, step: typeof DEMO_STEPS[number]['id']) {
  s.currentStep = DEMO_STEPS.findIndex(v => v.id === step)
  return s
}
const purchaseForm = { supplierId: 'sup', materialTypeId: 'raw', quantity: 1.25, unit: 'kg', unitPrice: 46 }
const form = (s: DemoState, step: typeof DEMO_STEPS[number]['id']) => Object.fromEntries(prepareStep(s, step).fields.map(f => [f.name, f.value]))

describe('F006 demo flow business boundaries', () => {
  it('confirms a sales draft before review and reads the resulting status before deciding to submit', async () => {
    for (const autoApprove of [true, false]) {
      const s = at(state(), 'sales-order-submit')
      let status = 'DRAFT'
      const order = () => master('so', { remark: s.runKey, status })
      s.entities.salesOrder = order()
      const t = {
        get: vi.fn(async (p: string) => p === '/auth/me' ? admin : order()),
        post: vi.fn(async (p: string) => { status = p.endsWith('/confirm') && !autoApprove ? 'CONFIRMED' : 'FINANCE_APPROVED'; return order() }),
      } as DemoTransport
      await executeStep(s, 'sales-order-submit', {}, t)
      expect(t.post).toHaveBeenNthCalledWith(1, '/F006/sales/orders/so/confirm', {}, expect.anything())
      expect(t.post).toHaveBeenCalledTimes(autoApprove ? 1 : 2)
      if (!autoApprove) expect(t.post).toHaveBeenNthCalledWith(2, '/F006/sales/orders/so/submit-for-review', {}, expect.anything())
      expect(s.entities.salesOrder.status).toBe('FINANCE_APPROVED')
    }
  })
  it('blocks other live tenants before a business mutation', async () => {
    const post = vi.fn()
    await expect(executeStep(state(), 'purchase-create', purchaseForm, { get: vi.fn().mockResolvedValue({ ...admin, factoryId: 'F005' }), post })).rejects.toThrow(/F006/)
    expect(post).not.toHaveBeenCalled()
  })
  it('creates raw and packaging together and resumes after a failed readback without another PO', async () => {
    const s = state(), po = master('po', { remark: s.runKey, status: 'DRAFT' })
    let failRead = true
    const get = vi.fn(async (p: string) => {
      if (p === '/auth/me') return admin
      if (p.includes('?page=')) return { content: [], totalPages: 1 }
      if (failRead) throw new Error('readback unavailable')
      return po
    })
    const post = vi.fn().mockResolvedValue(po), t = { get, post } as DemoTransport
    await expect(executeStep(s, 'purchase-create', purchaseForm, t)).rejects.toThrow(/readback/)
    expect(s.currentStep).toBe(0)
    failRead = false
    await executeStep(s, 'purchase-create', purchaseForm, t)
    expect(post).toHaveBeenCalledTimes(1)
    expect(post.mock.calls[0][1].items).toEqual([
      expect.objectContaining({ materialTypeId: 'raw', quantity: 1.25, unit: 'kg' }),
      expect.objectContaining({ materialTypeId: 'pack', quantity: 4, unit: '个' }),
    ])
    expect(s.currentStep).toBe(1)
  })
  it('rejects a cross-tenant readback without advancing', async () => {
    const s = state(); s.entities.purchaseOrder = master('po', { remark: s.runKey })
    const t = { get: vi.fn(async (p: string) => p === '/auth/me' ? admin : { ...s.entities.purchaseOrder, factoryId: 'F005' }), post: vi.fn() } as DemoTransport
    await expect(executeStep(s, 'purchase-create', purchaseForm, t)).rejects.toThrow(/F006/)
    expect(s.currentStep).toBe(0)
  })
  it('preserves kg to box units across all six processes', () => {
    const s = state()
    expect(form(s, 'sales-order-create')).toMatchObject({ quantity: 4, unit: '盒', unitPrice: 27 })
    expect(form(s, 'process-report-5')).toMatchObject({ inputQuantity: 1, inputUnit: 'kg', outputQuantity: 4, outputUnit: '盒' })
    expect(form(s, 'process-report-6')).toMatchObject({ inputQuantity: 4, inputUnit: '盒', outputQuantity: 4, outputUnit: '盒' })
    expect(DEMO_STEPS.filter(s => s.id.startsWith('process-report-'))).toHaveLength(6)
  })
  it('submits the pinned raw input slot and requires persisted submitted data', async () => {
    const s = at(state(), 'process-report-1')
    s.entities.productionPlan = master('plan', { notes: s.runKey })
    s.entities.materialRequisition = master('req', { items: [{ materialTypeId: 'raw', batchNumbers: [{ workshopBatchId: 'this-purchase-wks' }] }] })
    let submitted = false
    const get = vi.fn(async (p: string) => p === '/auth/me' ? admin : submitted ? [{ clientRowId: `${s.runKey}:p1`, submissionStatus: 'SUBMITTED', batchNumber: 'PB-NEW' }] : [])
    const post = vi.fn(async (_p: string, _b?: unknown) => { submitted = true; return { batchNumber: 'PB-NEW' } })
    await executeStep(s, 'process-report-1', form(s, 'process-report-1'), { get, post } as DemoTransport)
    expect(post.mock.calls[0][1]).toMatchObject({ workflowPortId: 'out:p:1', rawMaterialInputs: [{ materialBatchId: 'this-purchase-wks', workflowPortId: 'in:p:1', materialNodeId: 'm:raw', skuId: 'raw' }] })
    expect(s.entities.processReport1.batchNumber).toBe('PB-NEW')
    expect(get.mock.calls.some(([p]) => p.includes('process=p1&processOrder=1'))).toBe(true)
  })
  it('does not advance if the submitted row was not persisted', async () => {
    const s = at(state(), 'process-report-2'); s.entities.productionPlan = master('plan'); s.entities.processReport1 = { batchNumber: 'PB1' }
    const t = { get: vi.fn(async (p: string) => p === '/auth/me' ? admin : []), post: vi.fn().mockResolvedValue({ batchNumber: 'PB2' }) } as DemoTransport
    await expect(executeStep(s, 'process-report-2', form(s, 'process-report-2'), t)).rejects.toThrow(/持久化/)
    expect(s.completed).not.toContain('process-report-2')
  })
  it('uses the demo endpoint only for procurement', async () => {
    for (const module of ['PURCHASE_ORDER', 'SALES_ORDER']) {
      let pending = true
      const t = { get: vi.fn(async (p: string) => p === '/auth/me' ? admin : pending ? [{ instanceId: 'inst', businessEntityId: 'entity', currentNodeId: 'node', factoryId: 'F006' }] : []), post: vi.fn(async () => { pending = false; return {} }) } as DemoTransport
      await approveWorkflow('demo', module, 'entity', t)
      expect(t.post).toHaveBeenCalledWith(`/F006/workflow/instances/inst/${module === 'PURCHASE_ORDER' ? 'demo-actions' : 'actions'}`, expect.objectContaining({ action: 'APPROVE', expectedNodeId: 'node' }))
    }
  })
})
