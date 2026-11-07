import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('@/api/request', () => ({ get: mocks.get }))

import PlanConcurrentReadiness from '../PlanConcurrentReadiness.vue'

const validPayload = () => ({
  planId: 'PLAN-1',
  planNumber: 'PP-001',
  planStatus: 'PLANNED',
  demandThroughDate: '2026-11-07',
  observedAt: '2026-11-07T10:00:00Z',
  cohortBasis: 'DUE_THROUGH_PLAN_DATE_AND_STARTED',
  consideredPlanCount: 3,
  unverifiedPlanCount: 0,
  notes: ['其他计划优先为下界，本计划优先为上界。'],
  material: {
    status: 'VERIFIED',
    reason: 'OK',
    lowerPercent: 50,
    upperPercent: 80,
    items: [{
      materialTypeId: 'MAT-1', materialName: '小麦粉', unit: 'kg', required: 10,
      consumed: 2, ownHeldReserved: 1, otherUnoccupied: 3, stockFree: 7,
      lowerPercent: 50, upperPercent: 80,
    }],
  },
  progress: {
    status: 'VERIFIED', basis: 'FINISHED_GOODS_RECEIVED', plannedQuantity: 10,
    receivedQuantity: 12, remainingQuantity: 0, completionPercent: 120,
    unit: 'kg', reason: '实际成品入库口径。',
  },
  advisoryOnly: true,
})

function response(data: unknown) {
  return { success: true, data, message: '' }
}

function mountComponent(factoryId = 'F006', planId = 'PLAN-1') {
  return shallowMount(PlanConcurrentReadiness, { props: { factoryId, planId } })
}

describe('PlanConcurrentReadiness', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.get.mockResolvedValue(response(validPayload()))
  })
  afterEach(() => vi.restoreAllMocks())

  it('renders the full database-sized Unicode material display name', async () => {
    const data = validPayload()
    data.material.items[0].materialName = '🧀'.repeat(255)
    mocks.get.mockResolvedValue(response(data))
    const wrapper = mountComponent()
    await wrapper.get('[data-testid="plan-concurrent-readiness-refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(data.material.items[0].materialName)
    expect(wrapper.find('[data-testid="readiness-error"]').exists()).toBe(false)
  })

  it('still rejects an oversized display name rather than accepting an unbounded payload', async () => {
    const data = validPayload()
    data.material.items[0].materialName = 'x'.repeat(513)
    mocks.get.mockResolvedValue(response(data))
    const wrapper = mountComponent()
    await wrapper.get('[data-testid="plan-concurrent-readiness-refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="readiness-error"]').exists()).toBe(true)
  })

  it('does not request until the operator starts a read-only calculation', async () => {
    const wrapper = mountComponent()
    expect(mocks.get).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="plan-concurrent-readiness-refresh"]').trigger('click')
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledWith('/F006/production-plans/PLAN-1/concurrent-readiness')
    expect(wrapper.text()).toContain('同期计划')
    expect(wrapper.text()).toContain('按已确认的成品入库量核算')
    expect(wrapper.text()).toContain('120%')
    expect(wrapper.text()).toContain('先满足其他计划时：50%')
    expect(wrapper.text()).not.toContain('FINISHED_GOODS_RECEIVED')
    expect(wrapper.text()).not.toContain('Advisory')
    expect(wrapper.text()).not.toContain('开工')
  })

  it('does not request without a complete factory/plan scope', async () => {
    const wrapper = mountComponent('', 'PLAN-1')
    expect(wrapper.get('[data-testid="readiness-no-scope"]').text()).toContain('暂不请求')
    expect(mocks.get).not.toHaveBeenCalled()
  })

  it('fails closed on missing unit, negative quantity, or invalid percentage', async () => {
    const malformed = validPayload()
    malformed.material.items[0].unit = ''
    malformed.material.items[0].required = -1
    malformed.material.lowerPercent = 101
    mocks.get.mockResolvedValue(response(malformed))
    const wrapper = mountComponent()
    await wrapper.get('[data-testid="plan-concurrent-readiness-refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="readiness-error"]').text()).toContain('响应字段无效')
    expect(wrapper.get('[data-testid="readiness-retry"]').exists()).toBe(true)
  })

  it('keeps unknown quantities explicit and accepts over-completion without inventing a cap', async () => {
    const unknown = validPayload()
    unknown.material.status = 'UNVERIFIED'
    unknown.material.reason = 'UNKNOWN_COVERAGE'
    unknown.material.lowerPercent = null
    unknown.material.upperPercent = null
    unknown.material.items[0].required = null
    unknown.material.items[0].stockFree = null
    unknown.progress.status = 'UNVERIFIED'
    unknown.progress.receivedQuantity = null
    unknown.progress.remainingQuantity = null
    unknown.progress.completionPercent = null
    mocks.get.mockResolvedValue(response(unknown))
    const wrapper = mountComponent()
    await wrapper.get('[data-testid="plan-concurrent-readiness-refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('未核验')
    expect(wrapper.text()).not.toContain('0%')
    expect(wrapper.text()).not.toContain('100%')
  })

  it('keeps retry available after an API error and clears the error on success', async () => {
    mocks.get.mockRejectedValueOnce(new Error('服务暂时不可用')).mockResolvedValueOnce(response(validPayload()))
    const wrapper = mountComponent()
    await wrapper.get('[data-testid="plan-concurrent-readiness-refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('服务暂时不可用')
    await wrapper.get('[data-testid="readiness-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="readiness-error"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('小麦粉')
    expect(mocks.get).toHaveBeenCalledTimes(2)
  })

  it('ignores a late response after the factory/plan scope changes', async () => {
    let resolveFirst: (value: unknown) => void = () => undefined
    const first = new Promise(resolve => { resolveFirst = resolve })
    mocks.get.mockReturnValueOnce(first).mockResolvedValueOnce(response({ ...validPayload(), planId: 'PLAN-2', planNumber: 'PP-002' }))
    const wrapper = mountComponent()
    await wrapper.get('[data-testid="plan-concurrent-readiness-refresh"]').trigger('click')
    await wrapper.setProps({ factoryId: 'F007', planId: 'PLAN-2' })
    await wrapper.get('[data-testid="plan-concurrent-readiness-refresh"]').trigger('click')
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('PP-002')
    resolveFirst(response(validPayload()))
    await flushPromises()
    expect(wrapper.text()).toContain('PP-002')
    expect(wrapper.text()).not.toContain('PP-001')
    expect(mocks.get).toHaveBeenNthCalledWith(2, '/F007/production-plans/PLAN-2/concurrent-readiness')
  })

  it('requires a successful response for the requested plan', async () => {
    mocks.get.mockResolvedValueOnce({ success: false, data: validPayload(), message: '未完成' })
    const wrapper = mountComponent()
    await wrapper.get('[data-testid="plan-concurrent-readiness-refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="readiness-error"]').text()).toContain('未确认')

    mocks.get.mockResolvedValueOnce(response({ ...validPayload(), planId: 'OTHER-PLAN' }))
    await wrapper.get('[data-testid="readiness-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="readiness-error"]').text()).toContain('其他生产计划')
    expect(wrapper.find('[data-testid="readiness-idle"]').exists()).toBe(false)
  })

  it.each([
    ['verified material has no bounds', (payload: ReturnType<typeof validPayload>) => { payload.material.lowerPercent = null }],
    ['verified material has a non-OK reason', (payload: ReturnType<typeof validPayload>) => { payload.material.reason = 'UNKNOWN_COVERAGE' }],
    ['verified material has reversed bounds', (payload: ReturnType<typeof validPayload>) => { payload.material.lowerPercent = 90; payload.material.upperPercent = 80 }],
    ['unverified material exposes a bound', (payload: ReturnType<typeof validPayload>) => { payload.material.status = 'UNVERIFIED'; payload.material.reason = 'UNKNOWN_COVERAGE'; payload.material.lowerPercent = 10 }],
    ['verified progress has no unit', (payload: ReturnType<typeof validPayload>) => { payload.progress.unit = null }],
    ['verified progress has no planned quantity', (payload: ReturnType<typeof validPayload>) => { payload.progress.plannedQuantity = 0 }],
    ['unverified progress exposes a received quantity', (payload: ReturnType<typeof validPayload>) => { payload.progress.status = 'UNVERIFIED'; payload.progress.receivedQuantity = 1 }],
  ])('fails closed when %s', async (_label, mutate) => {
    const malformed = validPayload()
    mutate(malformed)
    mocks.get.mockResolvedValue(response(malformed))
    const wrapper = mountComponent()
    await wrapper.get('[data-testid="plan-concurrent-readiness-refresh"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="readiness-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="readiness-idle"]').exists()).toBe(false)
  })
})
