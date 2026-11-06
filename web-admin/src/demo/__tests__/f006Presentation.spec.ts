import { afterEach, describe, expect, it, vi } from 'vitest'
import axios from 'axios'
import { createPresentationCountdown, presentationRoute, installPresentationReadTracking } from '../f006Presentation'

afterEach(() => vi.useRealTimers())
describe('presentation pace', () => {
  it('waits the full visible interval, excludes loading time and cancels safely', async () => {
    vi.useFakeTimers()
    let ready = false
    const elapsed = vi.fn(), tick = vi.fn()
    const clock = createPresentationCountdown({ canTick: () => ready, onTick: tick, onElapsed: elapsed, onError: error => { throw error } })
    clock.start(10)
    await vi.advanceTimersByTimeAsync(30000)
    expect(elapsed).not.toHaveBeenCalled()
    ready = true
    await vi.advanceTimersByTimeAsync(9999)
    expect(elapsed).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(elapsed).toHaveBeenCalledTimes(1)
    clock.start(20); await vi.advanceTimersByTimeAsync(10000); clock.cancel()
    await vi.advanceTimersByTimeAsync(30000)
    expect(elapsed).toHaveBeenCalledTimes(1)
    clock.start(20); await vi.advanceTimersByTimeAsync(19999)
    expect(elapsed).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(elapsed).toHaveBeenCalledTimes(2)
    expect(() => clock.start(1.4)).toThrow()
  })
  it('uses actual run identities and supported business deep links', () => {
    const entities = { purchaseOrder: { id: 'po-current' }, salesOrder: { id: 'so-current' }, productionBatch: { id: 1 }, processReport2: { batchId: 3 }, processReport6: { batchNumber: 'PB/current' } }
    expect(presentationRoute('purchase-submit', entities)).toBe('/procurement/orders/po-current')
    expect(presentationRoute('purchase-receive', entities)).toBe('/warehouse/materials?view=receiving')
    expect(presentationRoute('sales-order-approve', entities)).toBe('/sales/orders/so-current')
    expect(presentationRoute('material-requisition', entities)).toBe('/production/material-flow?tab=requisitions')
    expect(presentationRoute('process-report-2', entities)).toBe('/production/batches/3')
    expect(presentationRoute('finished-goods-receive', entities)).toBe('/sales/finished-goods')
    expect(presentationRoute('sales-warehouse-confirm', entities)).toBe('/warehouse/shipments')
    expect(presentationRoute('sales-warehouse-confirm', {...entities,salesShipment:{status:'SHIPPED'}})).toBe('/sales/shipments')
    expect(presentationRoute('purchase-receive-confirm', {...entities,purchaseReceipt:{status:'CONFIRMED'}})).toBe('/warehouse/materials?view=batches')
    expect(presentationRoute('traceability-read', entities)).toBe('/production/traceability?batchNumber=PB%2Fcurrent')
  })
})

it('tracks actual transport before envelope unwrapping and balances retry/disposal', async () => {
  let pending = 0
  const finish = vi.fn()
  const client = axios.create({adapter:async config => ({data:{success:true,data:['actual']},status:200,statusText:'OK',headers:{},config})})
  client.interceptors.response.use(response => response.data)
  const dispose = installPresentationReadTracking(client,{isActive:()=>true,onStart:()=>{pending++},onFinish:failed=>{pending--;finish(failed)}})
  await expect(client.get('/F006/production/batches')).resolves.toEqual({success:true,data:['actual']})
  expect(pending).toBe(0); expect(finish).toHaveBeenLastCalledWith(false)
  await client.get('/F006/production/batches')
  expect(pending).toBe(0); expect(finish).toHaveBeenCalledTimes(2)
  await client.get('/F007/production/batches')
  expect(finish).toHaveBeenCalledTimes(2)
  dispose(); await client.get('/F006/production/batches')
  expect(finish).toHaveBeenCalledTimes(2)
})

it('a pre-adapter guard rejection leaves no pending reads and can recover', async () => {
  let pending = 0, rejectGuard = true
  const failure = vi.fn()
  const client = axios.create({adapter:async config => ({data:{success:true},status:200,statusText:'OK',headers:{},config})})
  client.interceptors.request.use(config => { if(rejectGuard) throw new Error('scope guard'); return config })
  const dispose = installPresentationReadTracking(client,{isActive:()=>true,onStart:()=>{pending++},onFinish:()=>{pending--},onFailure:failure})
  await expect(client.get('/F006/production/batches')).rejects.toThrow('scope guard')
  expect(pending).toBe(0); expect(failure).toHaveBeenCalledTimes(1)
  rejectGuard = false
  await client.get('/F006/production/batches')
  expect(pending).toBe(0)
  dispose()
})
