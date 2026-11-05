import { expect, it } from 'vitest'
import { createDemoState, executeStep, type DemoTransport } from '../f006Flow'

it('resumes a confirmed pick with transfer and receipt without reserving the batches again', async () => {
  const state = createDemoState()
  state.currentStep = 10
  state.context = { supplier: {}, customer: {}, material: {}, packaging: {}, product: {}, rawWarehouse: {}, workshopWarehouse: {}, processes: [], rawQuantity: 1.25, finishedQuantity: 4 }
  state.entities.productionPlan = { id: 'plan', factoryId: 'F006' }
  let status = 'PICKING'
  const writes: string[] = []
  const requisition = () => ({ id: 'req', factoryId: 'F006', productionPlanId: 'plan', status, pickedAt: '2026-11-05T09:00:00', items: [] })
  const transport: DemoTransport = {
    get: async <T>(path: string) => {
      if (path === '/auth/me') return { id: 1309, factoryId: 'F006', roleCode: 'factory_super_admin' } as T
      if (path === '/F006/material-requisitions/by-plan/plan') return [requisition()] as T
      if (path === '/F006/material-requisitions/req') return requisition() as T
      throw new Error(`unexpected read: ${path}`)
    },
    post: async () => { throw new Error('a generated requisition must not be created again') },
    put: async <T>(path: string) => {
      writes.push(path)
      if (path.endsWith('/transfer')) status = 'TRANSFERRED'
      else if (path.endsWith('/receive')) status = 'ISSUED'
      else throw new Error('picking must not be confirmed twice')
      return requisition() as T
    },
  }
  const result = await executeStep(state, 'material-requisition', {}, transport)
  expect(writes).toEqual(['/F006/material-requisitions/req/transfer', '/F006/material-requisitions/req/receive'])
  expect(result.currentStep).toBe(11)
  expect(result.entities.materialRequisition.status).toBe('ISSUED')
})
