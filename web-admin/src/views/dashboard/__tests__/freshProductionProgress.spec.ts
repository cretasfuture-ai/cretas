import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import Wall from '../production-progress.vue'

const h = vi.hoisted(() => ({
  auth: { factoryId: 'SIM24A' },
  get: vi.fn(),
}))
vi.mock('@/store/modules/auth', async () => {
  const { reactive } = await import('vue')
  h.auth = reactive(h.auth)
  return { useAuthStore: () => h.auth }
})
vi.mock('@/api/request', () => ({ get: h.get }))

const busy = {
  date: '2026-10-02',
  summary: { totalPlans: 1, totalWorkOrders: 2, completedWorkOrders: 1,
    inProgressWorkOrders: 1, pendingWorkOrders: 0, overallProgressPct: 50 },
  plans: [{ planId: 'A-1', planNumber: 'SYN-DASH-A', productName: '合成甲产品',
    customerName: '合成客户甲', plannedQuantity: 100, reportedQuantity: 50,
    progressPct: 50, status: 'IN_PROGRESS', processes: [] }],
}
const empty = { ...busy, summary: { ...busy.summary, totalPlans: 0 }, plans: [] }
const overdue = [ { warnLevel: 'OVERDUE' }, { warnLevel: 'URGENT' } ]
const wrappers: VueWrapper[] = []
const response = (data: unknown) => Promise.resolve({ success: true, data })
function defaults() {
  h.get.mockImplementation((url: string) => response(url.endsWith('delivery-warnings') ? overdue : busy))
}
async function wall() {
  const w = mount(Wall, { global: { plugins: [ElementPlus], stubs: { teleport: true } } })
  wrappers.push(w)
  await flushPromises()
  return w
}
async function refresh(w: VueWrapper) {
  await w.findAll('button').find(b => b.text() === '刷新')!.trigger('click')
  await flushPromises()
}
function deferred() {
  let resolve!: (value: unknown) => void
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date(2026, 9, 2, 10, 0, 0))
  h.auth.factoryId = 'SIM24A'
  h.get.mockReset()
  defaults()
})
afterEach(() => {
  wrappers.splice(0).forEach(w => w.unmount())
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('fresh B8 refresh at the rendered page entry', () => {
  it('shows successful fetch time separately from the ticking clock', async () => {
    const w = await wall()
    expect(w.text()).toContain('最近成功取数')
    expect(w.text()).toContain('10:00:00')
  })

  it('keeps old progress visibly stale after failure while warnings succeed independently', async () => {
    const w = await wall()
    h.get.mockImplementation((url: string) => url.endsWith('delivery-warnings')
      ? response([]) : Promise.reject(Error('合成主进度断网')))
    await refresh(w)
    expect(w.find('.plan-product').text()).toBe('合成甲产品')
    expect(w.text()).toContain('数据已过期')
    expect(w.find('.summary-card.overdue .summary-value').text()).toBe('0')
    expect(w.find('.summary-card.overdue').text()).not.toContain('读取失败')
  })

  it('does not pretend the first failed request is an empty production day', async () => {
    h.get.mockImplementation((url: string) => url.endsWith('delivery-warnings')
      ? response(overdue) : Promise.reject(Error('合成首次失败')))
    const w = await wall()
    expect(w.text()).toContain('尚未成功取数')
    expect(w.text()).not.toContain('当天暂无生产计划')
    expect(w.find('.summary-row').exists()).toBe(false)
  })

  it('does not overlap either stream and does not let a stalled warning block progress', async () => {
    const progress = deferred(), warning = deferred()
    h.get.mockImplementation((url: string) => url.endsWith('delivery-warnings') ? warning.promise : progress.promise)
    const w = await wall()
    await vi.advanceTimersByTimeAsync(60000)
    expect(h.get.mock.calls.filter(([url]) => String(url).endsWith('production-progress'))).toHaveLength(1)
    expect(h.get.mock.calls.filter(([url]) => String(url).endsWith('delivery-warnings'))).toHaveLength(1)
    progress.resolve({ success: true, data: busy })
    await flushPromises()
    expect(w.find('.plan-product').text()).toBe('合成甲产品')
    await vi.advanceTimersByTimeAsync(30000)
    expect(h.get.mock.calls.filter(([url]) => String(url).endsWith('production-progress'))).toHaveLength(2)
    expect(h.get.mock.calls.filter(([url]) => String(url).endsWith('delivery-warnings'))).toHaveLength(1)
    warning.resolve({ success: true, data: overdue })
    await flushPromises()
  })

  it('changes the request date at midnight and discards responses for the previous day', async () => {
    vi.setSystemTime(new Date(2026, 9, 2, 23, 59, 50))
    const old = deferred()
    h.get.mockImplementation((url: string, config: { params: { date?: string } }) => {
      if (url.endsWith('delivery-warnings')) return response(overdue)
      return config.params.date === '2026-10-02' ? old.promise : response({ ...busy, date: '2026-10-03' })
    })
    const w = await wall()
    await vi.advanceTimersByTimeAsync(11000)
    expect(w.find('.date-big').text()).toBe('2026-10-03')
    expect(h.get.mock.calls.some(([url, cfg]) => String(url).endsWith('production-progress') && cfg.params.date === '2026-10-03')).toBe(true)
    old.resolve({ success: true, data: { ...busy, plans: [{ ...busy.plans[0], productName: '旧日产品' }] } })
    await flushPromises()
    expect(w.text()).not.toContain('旧日产品')
  })

  it('clears both old tenant streams and ignores their late responses', async () => {
    const oldProgress = deferred(), oldWarning = deferred()
    h.get.mockImplementation((url: string) => url.startsWith('/SIM24A/')
      ? (url.endsWith('delivery-warnings') ? oldWarning.promise : oldProgress.promise)
      : response(url.endsWith('delivery-warnings') ? [] : { ...busy, plans: [{ ...busy.plans[0], productName: '合成乙产品' }] }))
    const w = await wall()
    h.auth.factoryId = 'SIM24B'
    await flushPromises()
    expect(w.text()).toContain('合成乙产品')
    oldProgress.resolve({ success: true, data: busy })
    oldWarning.resolve({ success: true, data: overdue })
    await flushPromises()
    expect(w.text()).not.toContain('合成甲产品')
    expect(w.find('.summary-card.overdue .summary-value').text()).toBe('0')
  })

  it('marks disconnect stale and recovers automatically on online without waiting 30 seconds', async () => {
    const w = await wall()
    window.dispatchEvent(new Event('offline'))
    await flushPromises()
    expect(w.text()).toContain('数据已过期')
    const calls = h.get.mock.calls.length
    defaults()
    window.dispatchEvent(new Event('online'))
    await flushPromises()
    expect(h.get.mock.calls.length).toBe(calls + 2)
    expect(w.text()).not.toContain('数据已过期')
    expect(w.text()).toContain('最近成功取数')
  })

  it('offers fullscreen, masks meaningful customer names there, and restores them on exit', async () => {
    const w = await wall()
    const screen = w.find('.dashboard-screen').element
    let fullscreen: Element | null = null
    Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => fullscreen })
    Object.defineProperty(screen, 'requestFullscreen', { configurable: true, value: async () => {
      fullscreen = screen; document.dispatchEvent(new Event('fullscreenchange'))
    } })
    Object.defineProperty(document, 'exitFullscreen', { configurable: true, value: async () => {
      fullscreen = null; document.dispatchEvent(new Event('fullscreenchange'))
    } })
    const enter = w.findAll('button').find(b => b.text() === '全屏展示')
    expect(enter).toBeDefined()
    await enter!.trigger('click'); await flushPromises()
    expect(w.text()).not.toContain('合成客户甲')
    await w.findAll('button').find(b => b.text() === '退出全屏')!.trigger('click'); await flushPromises()
    expect(w.text()).toContain('合成客户甲')
  })

  it('masks names before the fullscreen request and restores them if the browser rejects it', async () => {
    const w = await wall()
    const screen = w.find('.dashboard-screen').element
    Object.defineProperty(document, 'fullscreenElement', { configurable: true, get: () => null })
    let maskedWhenRequested = false
    Object.defineProperty(screen, 'requestFullscreen', { configurable: true, value: async () => {
      maskedWhenRequested = !w.text().includes('合成客户甲')
      throw Error('合成全屏请求拒绝')
    } })
    await w.findAll('button').find(b => b.text() === '全屏展示')!.trigger('click')
    await flushPromises()
    expect(maskedWhenRequested).toBe(true)
    expect(w.text()).toContain('合成客户甲')
    expect(w.findAll('button').some(b => b.text() === '全屏展示')).toBe(true)
  })

  it('removes refresh and network subscriptions on unmount', async () => {
    const w = await wall()
    w.unmount(); wrappers.splice(wrappers.indexOf(w), 1)
    const calls = h.get.mock.calls.length
    window.dispatchEvent(new Event('online'))
    await vi.advanceTimersByTimeAsync(60000)
    expect(h.get.mock.calls).toHaveLength(calls)
  })
})

describe('fresh contracts retained as negative controls', () => {
  it('uses totalPlans for KPI visibility even if plans is temporarily empty', async () => {
    h.get.mockImplementation((url: string) => response(url.endsWith('delivery-warnings') ? overdue : { ...busy, plans: [] }))
    const w = await wall()
    expect(w.find('.summary-row').exists()).toBe(true)
    expect(w.find('.summary-card.overall').text()).toContain('50%')
  })
  it('shows no zero KPI row on an empty day but keeps cross-day overdue information', async () => {
    h.get.mockImplementation((url: string) => response(url.endsWith('delivery-warnings') ? overdue : empty))
    const w = await wall()
    expect(w.find('.summary-row').exists()).toBe(false)
    expect(w.text()).toContain('当天暂无生产计划')
    expect(w.text()).toContain('另有 1 个生产计划已超期')
  })
  it('warning failure stays unknown independently of a successful progress fetch', async () => {
    h.get.mockImplementation((url: string) => url.endsWith('delivery-warnings') ? Promise.reject(Error('合成预警失败')) : response(busy))
    const w = await wall()
    expect(w.find('.plan-product').text()).toBe('合成甲产品')
    expect(w.find('.summary-card.overdue .summary-value').text()).toBe('—')
    expect(w.find('.summary-card.overdue').text()).toContain('读取失败')
  })
  it('malformed warning response stays unknown on an empty day', async () => {
    h.get.mockImplementation((url: string) => response(url.endsWith('delivery-warnings') ? { content: [] } : empty))
    const w = await wall()
    expect(w.text()).toContain('是否有超期计划本次未知')
    expect(w.text()).not.toContain('另有 0')
  })
})
