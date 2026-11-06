import { ref } from 'vue'
import axios, { type AxiosAdapter, type AxiosInstance } from 'axios'

// Ephemeral: a reload, actor change or ended sandbox never resumes playback.
export const presentationEnabled = ref(false)
export const PRESENTATION_HOLD_SECONDS = [10, 20, 30] as const

export function createPresentationCountdown(options: {
  canTick: () => boolean
  onTick: (remaining: number) => void
  onElapsed: () => void | Promise<void>
  onError: (error: unknown) => void
}) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let generation = 0
  function cancel() { generation++; if (timer) clearTimeout(timer); timer = undefined }
  function start(seconds: number) {
    cancel()
    if (!PRESENTATION_HOLD_SECONDS.some(value => value === seconds)) throw new Error('请选择10、20或30秒讲解停留')
    let remaining = seconds
    const owner = generation
    options.onTick(remaining)
    const tick = () => {
      if (owner !== generation) return
      if (options.canTick()) { remaining--; options.onTick(remaining) }
      if (remaining > 0) timer = setTimeout(tick, 1000)
      else { timer = undefined; Promise.resolve().then(() => { if (owner === generation) return options.onElapsed() }).catch(options.onError) }
    }
    timer = setTimeout(tick, 1000)
  }
  return { start, cancel }
}

export function presentationRoute(step: string, entities: Record<string, Record<string, unknown>>): string {
  const encoded = (entity: string, field = 'id') => entities[entity]?.[field] == null ? '' : encodeURIComponent(String(entities[entity]![field]))
  if (step.startsWith('purchase-')) return step.includes('receive') ? entities.purchaseReceipt?.status === 'CONFIRMED' ? '/warehouse/materials?view=batches' : '/warehouse/materials?view=receiving' : encoded('purchaseOrder') ? `/procurement/orders/${encoded('purchaseOrder')}` : '/procurement/orders'
  if (step.startsWith('sales-order') || ['sales-delivery-create', 'sales-shipment-create', 'sales-batch-allocate'].includes(step)) return encoded('salesOrder') ? `/sales/orders/${encoded('salesOrder')}` : '/sales/orders'
  if (step === 'production-plan-create' || step === 'production-settle') return '/production/plans'
  if (step === 'material-requisition') return '/production/material-flow?tab=requisitions'
  if (step === 'finished-goods-receive') return '/sales/finished-goods'
  if (step === 'sales-warehouse-confirm') return ['SHIPPED', 'DELIVERED'].includes(String(entities.salesShipment?.status)) ? '/sales/shipments' : '/warehouse/shipments'
  if (step === 'traceability-read') return `/production/traceability${encoded('processReport6', 'batchNumber') ? `?batchNumber=${encoded('processReport6', 'batchNumber')}` : ''}`
  const report = step.startsWith('process-report-') ? `processReport${step.split('-').at(-1)}` : ''
  const batchId = report ? encoded(report, 'batchId') : encoded('productionBatch')
  return batchId ? `/production/batches/${batchId}` : '/production/batches'
}

const originalAdapters = new WeakMap<AxiosAdapter, AxiosAdapter>()
// Track raw transport completion: the shared response interceptor unwraps response.config away.
export function installPresentationReadTracking(client: Pick<AxiosInstance, 'interceptors' | 'defaults'>, options: { isActive: () => boolean; onStart: () => void; onFinish: (failed: boolean) => void; onFailure?: () => void }) {
  const interceptor = client.interceptors.request.use(config => {
    const url = config.url || ''
    if (!options.isActive() || (config.method || 'get').toLowerCase() !== 'get' || !/\/F006\//.test(url) || (url.includes('/demo-sandbox/') && !url.includes('/proxy/'))) return config
    const configured = axios.getAdapter(config.adapter || client.defaults.adapter)
    const adapter = originalAdapters.get(configured) || configured
    const tracked: AxiosAdapter = async requestConfig => {
      let failed = true
      try {
        options.onStart()
        const response = await adapter(requestConfig)
        failed = response.status >= 400 || response.data?.success === false
        return response
      } finally { options.onFinish(failed) }
    }
    originalAdapters.set(tracked, adapter)
    config.adapter = tracked
    return config
  })
  const failures = client.interceptors.response.use(response => response, error => { if (options.isActive()) options.onFailure?.(); return Promise.reject(error) })
  return () => { client.interceptors.request.eject(interceptor); client.interceptors.response.eject(failures) }
}
