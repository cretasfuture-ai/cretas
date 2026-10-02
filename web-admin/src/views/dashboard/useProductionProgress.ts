import { computed, onBeforeUnmount, onMounted, ref, watch, type ComputedRef } from 'vue'
import { get } from '@/api/request'

interface ProcessRow {
  processName: string
  plannedQty: number
  reportedQty: number
  pct: number
  status: 'DONE' | 'IN_PROGRESS' | 'PENDING'
}
interface PlanRow {
  planId: string
  planNumber: string
  productName: string
  customerName: string
  plannedQuantity: number
  reportedQuantity: number
  progressPct: number
  status: 'DONE' | 'IN_PROGRESS' | 'PENDING'
  processes: ProcessRow[]
}
interface Summary {
  totalPlans: number
  totalWorkOrders: number
  completedWorkOrders: number
  inProgressWorkOrders: number
  pendingWorkOrders: number
  overallProgressPct: number
}
interface ProgressResp {
  date: string
  summary: Summary
  plans: PlanRow[]
}

interface DeliveryWarn {
  warnLevel: 'OVERDUE' | 'URGENT' | 'WARN' | 'NORMAL'
}

function localDate(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function timeText(d: Date): string {
  return d.toLocaleTimeString('zh-CN', { hour12: false })
}

/** Progress and delivery warnings retain separate success/failure states. */
export function useProductionProgress(factoryId: ComputedRef<string | undefined>) {
  const loading = ref(false)
  const data = ref<ProgressResp | null>(null)
  const now = ref(new Date())
  const today = computed(() => localDate(now.value))
  const nowText = computed(() => timeText(now.value))
  const errorMsg = ref('')
  const lastSuccess = ref<Date | null>(null)
  const online = ref(navigator.onLine)
  const stale = computed(() => data.value !== null && (!online.value || !!errorMsg.value))
  const lastSuccessText = computed(() => lastSuccess.value
    ? `${localDate(lastSuccess.value)} ${timeText(lastSuccess.value)}` : '尚未成功取数')
  const overdueCount = ref<number | null>(null)
  const overdueFailed = ref(false)
  const overdueText = computed(() => overdueFailed.value || overdueCount.value === null
    ? '—' : String(overdueCount.value))

  let generation = 0
  let stopped = false
  // Each stream admits one request per context. Old-context finalizers cannot unlock a new one.
  let progressFlight: symbol | null = null
  let warningFlight: symbol | null = null
  let timer: ReturnType<typeof setInterval> | undefined
  let clockTimer: ReturnType<typeof setInterval> | undefined

  async function fetchData() {
    if (stopped || progressFlight || !online.value) return
    const factory = factoryId.value
    if (!factory) { errorMsg.value = '未获取到工厂ID'; return }
    const date = today.value
    const epoch = generation
    const flight = Symbol()
    progressFlight = flight
    loading.value = true
    try {
      const res = await get<ProgressResp>(`/${factory}/dashboard/production-progress`, { params: { date } })
      if (stopped || epoch !== generation) return
      if (!res.data || !res.data.summary || !Array.isArray(res.data.plans)) {
        throw new Error('生产进度响应缺少有效数据')
      }
      data.value = res.data
      lastSuccess.value = new Date()
      errorMsg.value = ''
    } catch (e: unknown) {
      if (!stopped && epoch === generation) {
        errorMsg.value = e instanceof Error ? e.message : '加载生产进度失败'
      }
    } finally {
      if (progressFlight === flight) { progressFlight = null; loading.value = false }
    }
  }

  async function fetchOverdue() {
    if (stopped || warningFlight || !online.value || !factoryId.value) return
    const factory = factoryId.value
    const epoch = generation
    const flight = Symbol()
    warningFlight = flight
    try {
      const res = await get<DeliveryWarn[]>(`/${factory}/production-plans/delivery-warnings`, {
        params: { windowDays: 7 },
      })
      if (stopped || epoch !== generation) return
      // An unreadable warning is unknown, never a synthetic zero.
      if (!Array.isArray(res.data)) throw new Error('交货预警响应不是数组')
      overdueCount.value = res.data.filter(w => w?.warnLevel === 'OVERDUE').length
      overdueFailed.value = false
    } catch {
      if (!stopped && epoch === generation) {
        overdueFailed.value = true
        overdueCount.value = null
      }
    } finally {
      if (warningFlight === flight) warningFlight = null
    }
  }

  function refreshAll() {
    // Read the current wall date even after background timer throttling.
    now.value = new Date()
    void fetchData()
    void fetchOverdue()
  }
  function invalidate() {
    generation += 1
    progressFlight = warningFlight = null
    loading.value = false
  }
  watch([factoryId, today], () => {
    invalidate()
    data.value = null
    lastSuccess.value = null
    errorMsg.value = ''
    overdueCount.value = null
    overdueFailed.value = false
    refreshAll()
  }, { flush: 'sync' })

  function offline() {
    online.value = false
    invalidate()
    errorMsg.value = '网络已断开，等待恢复'
    overdueCount.value = null
    overdueFailed.value = true
  }
  function connected() {
    online.value = true
    refreshAll()
  }
  onMounted(() => {
    if (!online.value) offline()
    refreshAll()
    timer = setInterval(refreshAll, 30000)
    clockTimer = setInterval(() => { now.value = new Date() }, 1000)
    window.addEventListener('offline', offline)
    window.addEventListener('online', connected)
  })
  onBeforeUnmount(() => {
    stopped = true
    invalidate()
    clearInterval(timer)
    clearInterval(clockTimer)
    window.removeEventListener('offline', offline)
    window.removeEventListener('online', connected)
  })
  return { loading, data, today, nowText, errorMsg, stale, lastSuccessText,
    overdueCount, overdueFailed, overdueText, refreshAll }
}
