import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const evaluateProposalComparison = vi.hoisted(() => vi.fn())
const authState = vi.hoisted(() => ({ factoryId: 'F006', currentRole: 'factory_super_admin' }))
const authStoreRef = vi.hoisted(() => ({
  value: undefined as { factoryId: string; currentRole: string } | undefined,
}))
vi.mock('@/api/proposalComparison', async () => {
  const actual = await vi.importActual<typeof import('@/api/proposalComparison')>('@/api/proposalComparison')
  return { ...actual, evaluateProposalComparison }
})
vi.mock('@/store/modules/auth', async () => {
  const { reactive } = await import('vue')
  authStoreRef.value = reactive(authState)
  return {
    useAuthStore: () => authStoreRef.value!,
  }
})

import ProposalComparisonPage from '../index.vue'

const comparable = {
  status: 'SUPPLIED_FACTS_COMPARABLE', comparisonBasisId: 'basis-1', reason: 'PARETO_FRONTIER_OF_SUPPLIED_FACTS',
  candidates: [
    { id: 'A', status: 'ELIGIBLE_BY_SUPPLIED_FACTS', reasons: [], dominatedByCandidateIds: [] },
    { id: 'B', status: 'ELIGIBLE_BY_SUPPLIED_FACTS', reasons: [], dominatedByCandidateIds: ['A'] },
  ],
  nonDominatedCandidateIds: ['A'], factsVerified: false, businessExecutionPerformed: false, resourcesReserved: false,
}

function fillValidForm(wrapper: ReturnType<typeof mount>) {
  const inputs = wrapper.findAll('input')
  return (async () => {
    for (const [index, value] of [
      [0, 'basis-1'], [1, 'CNY'], [2, 'CNY_PER_BATCH'],
      [3, '2026-11-06T01:00'], [4, '2026-11-06T09:00'], [6, '100'],
      [7, 'A'], [8, 'basis-1'], [9, 'CNY'], [10, 'CNY_PER_BATCH'], [11, '10'],
      [12, '2026-11-06T05:00'], [13, '2'],
      [14, 'B'], [15, 'basis-1'], [16, 'CNY'], [17, 'CNY_PER_BATCH'], [18, '20'],
      [19, '2026-11-06T06:00'], [20, '3'],
    ] as const) await inputs[index].setValue(value)
    const selects = wrapper.findAll('select')
    await selects[0].setValue('PERSON_HOUR')
    await selects[1].setValue('PERSON_HOUR')
    await selects[2].setValue('PERSON_HOUR')
  })()
}

describe('proposal comparison page', () => {
  beforeEach(() => {
    evaluateProposalComparison.mockReset()
    authStoreRef.value!.factoryId = 'F006'
    authStoreRef.value!.currentRole = 'factory_super_admin'
  })

  it.each([
    ['', 'factory_super_admin', '当前账号没有可用工厂范围。'],
    ['F006', 'warehouse_worker', '仅工厂超级管理员或权限管理员可查看此页面。'],
    ['F006', '', '仅工厂超级管理员或权限管理员可查看此页面。'],
  ])('denies missing factory or unsupported role without an API call (%s/%s)', async (factoryId, role, message) => {
    authStoreRef.value!.factoryId = factoryId
    authStoreRef.value!.currentRole = role
    const wrapper = mount(ProposalComparisonPage)
    await flushPromises()
    expect(wrapper.text()).toContain('暂时无法使用方案比较')
    expect(wrapper.text()).toContain(message)
    expect(evaluateProposalComparison).not.toHaveBeenCalled()
  })

  it('mounts, submits exact current-factory facts, and renders non-dominated plus exclusion reasons', async () => {
    evaluateProposalComparison.mockResolvedValue({ success: true, data: comparable })
    const wrapper = mount(ProposalComparisonPage)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(evaluateProposalComparison).toHaveBeenCalledWith('F006', expect.objectContaining({
      comparisonBasisId: 'basis-1', currency: 'CNY', candidates: expect.arrayContaining([
        expect.objectContaining({ id: 'A', cost: 10, requiredResource: 2 }),
      ]),
    }))
    expect(wrapper.text()).toContain('非支配候选')
    expect(wrapper.text()).toContain('A')
    expect(wrapper.text()).toContain('被以下候选支配：A')
    expect(wrapper.text()).toContain('输入事实未核实')
    expect(wrapper.text()).toContain('本次未执行方案')
    expect(wrapper.text()).toContain('本次未预留资源')
  })

  it('renders server errors without inventing an empty success state', async () => {
    evaluateProposalComparison.mockResolvedValue({ success: false, message: '当前账号无权访问' })
    const wrapper = mount(ProposalComparisonPage)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('当前账号无权访问')
    expect(wrapper.text()).toContain('未展示旧结果')
    expect(wrapper.text()).not.toContain('非支配候选')
  })

  it('clears an earlier result for both a real 403 and a network error', async () => {
    evaluateProposalComparison
      .mockResolvedValueOnce({ success: true, data: comparable })
      .mockRejectedValueOnce({ response: { status: 403, data: { message: '当前账号无权访问' } } })
      .mockRejectedValueOnce(new Error('network unavailable'))
    const wrapper = mount(ProposalComparisonPage)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('非支配候选')

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('当前账号无权访问')
    expect(wrapper.text()).not.toContain('非支配候选')

    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('network unavailable')
    expect(wrapper.text()).not.toContain('非支配候选')
  })

  it('rejects malformed results and never presents unsafe execution claims', async () => {
    evaluateProposalComparison.mockResolvedValue({
      success: true,
      data: { ...comparable, factsVerified: true, candidates: [null] },
    })
    const wrapper = mount(ProposalComparisonPage)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('返回结果无法核实')
    expect(wrapper.text()).not.toContain('输入事实已标记为已核实')
    expect(wrapper.text()).not.toContain('非支配候选')
  })

  it.each([
    ['0x10', '必须是非负数字'], ['1e2', '必须是非负数字'], ['-1', '必须是非负数字'],
    ['1.1234567', '必须是非负数字'], ['123456789012.123456', '超出可精确传输的数字范围'],
  ])('rejects unsafe numeric spelling %s before the API call', async (value, message) => {
    evaluateProposalComparison.mockResolvedValue({ success: true, data: comparable })
    const wrapper = mount(ProposalComparisonPage)
    await fillValidForm(wrapper)
    const inputs = wrapper.findAll('input')
    await inputs[11].setValue(value)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(evaluateProposalComparison).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(message)
  })

  it('keeps a missing cost as null and renders an explicit not-comparable result', async () => {
    const notComparable = {
      ...comparable,
      status: 'NOT_COMPARABLE',
      reason: 'MISSING_OR_INCOMPATIBLE_FACTS',
      candidates: [
        { id: 'A', status: 'NOT_COMPARABLE', reasons: ['MISSING_COST'], dominatedByCandidateIds: [] },
        { id: 'B', status: 'ELIGIBLE_BY_SUPPLIED_FACTS', reasons: [], dominatedByCandidateIds: [] },
      ],
      nonDominatedCandidateIds: [],
    }
    evaluateProposalComparison.mockResolvedValue({ success: true, data: notComparable })
    const wrapper = mount(ProposalComparisonPage)
    await fillValidForm(wrapper)
    await wrapper.findAll('input')[11].setValue('')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(evaluateProposalComparison).toHaveBeenCalledWith('F006', expect.objectContaining({
      candidates: expect.arrayContaining([expect.objectContaining({ id: 'A', cost: null })]),
    }))
    expect(wrapper.text()).toContain('暂不可比较')
    expect(wrapper.text()).toContain('缺少成本')
    expect(wrapper.text()).not.toContain('非支配候选')
  })

  it('shows mixed currency and resource-unit reasons without converting the facts', async () => {
    evaluateProposalComparison.mockResolvedValue({
      success: true,
      data: {
        ...comparable,
        status: 'NOT_COMPARABLE',
        reason: 'MISSING_OR_INCOMPATIBLE_FACTS',
        candidates: [
          { id: 'A', status: 'NOT_COMPARABLE', reasons: ['CURRENCY_MISMATCH'], dominatedByCandidateIds: [] },
          { id: 'B', status: 'NOT_COMPARABLE', reasons: ['RESOURCE_UNIT_MISMATCH'], dominatedByCandidateIds: [] },
        ],
        nonDominatedCandidateIds: [],
      },
    })
    const wrapper = mount(ProposalComparisonPage)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('币种不同')
    expect(wrapper.text()).toContain('资源单位不同')
    expect(wrapper.text()).toContain('暂不可比较')
    expect(wrapper.text()).not.toContain('已完成换算')
  })

  it('explains an excluded candidate while refusing to form a frontier with fewer than two eligible candidates', async () => {
    evaluateProposalComparison.mockResolvedValue({success: true, data: {
      ...comparable, status: 'NOT_COMPARABLE', reason: 'INSUFFICIENT_ELIGIBLE_PROPOSALS',
      nonDominatedCandidateIds: [], candidates: [comparable.candidates[0],
        {id: 'B', status: 'EXCLUDED_BY_SUPPLIED_FACTS', reasons: ['RESOURCE_SHORTAGE'], dominatedByCandidateIds: []}],
    }})
    const wrapper = mount(ProposalComparisonPage)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('暂不可比较')
    expect(wrapper.text()).toContain('资源不足')
    expect(wrapper.text()).not.toContain('非支配候选')
  })

  it.each([
    ['comparable with an unknown candidate', {...comparable, candidates: [comparable.candidates[0],
      {id: 'B', status: 'NOT_COMPARABLE', reasons: ['MISSING_COST'], dominatedByCandidateIds: []}]}],
    ['empty comparable frontier', {...comparable, nonDominatedCandidateIds: []}],
    ['omitted undominated candidate', {...comparable, candidates: comparable.candidates.map(item => ({...item, dominatedByCandidateIds: []}))}],
    ['self dominator', {...comparable, candidates: [comparable.candidates[0], {...comparable.candidates[1], dominatedByCandidateIds: ['B']}]}],
    ['duplicate dominator', {...comparable, candidates: [comparable.candidates[0], {...comparable.candidates[1], dominatedByCandidateIds: ['A', 'A']}]}],
    ['not-comparable missing reason without unknown facts', {...comparable, status: 'NOT_COMPARABLE',
      reason: 'MISSING_OR_INCOMPATIBLE_FACTS', nonDominatedCandidateIds: []}],
  ])('rejects semantically inconsistent server evidence: %s', async (_name, data) => {
    evaluateProposalComparison.mockResolvedValue({success: true, data})
    const wrapper = mount(ProposalComparisonPage)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('返回结果无法核实')
    expect(wrapper.text()).not.toContain('同口径事实可比较')
    expect(wrapper.text()).not.toContain('非支配候选')
  })

  it('drops a late response after the input changes while the request is pending', async () => {
    let resolve!: (value: unknown) => void
    evaluateProposalComparison.mockReturnValue(new Promise((nextResolve) => { resolve = nextResolve }))
    const wrapper = mount(ProposalComparisonPage)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await wrapper.findAll('input')[0].setValue('changed-basis')
    resolve({ success: true, data: comparable })
    await flushPromises()
    expect(wrapper.text()).not.toContain('非支配候选')
    expect(wrapper.text()).toContain('本页不会执行方案')
  })

  it('submits a pending request once and drops it across factory and role changes', async () => {
    let resolve!: (value: unknown) => void
    evaluateProposalComparison.mockReturnValue(new Promise((nextResolve) => { resolve = nextResolve }))
    const wrapper = mount(ProposalComparisonPage)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(evaluateProposalComparison).toHaveBeenCalledTimes(1)

    authStoreRef.value!.factoryId = 'F007'
    authStoreRef.value!.currentRole = 'warehouse_worker'
    await wrapper.vm.$nextTick()
    resolve({ success: true, data: comparable })
    await flushPromises()
    expect(wrapper.text()).not.toContain('非支配候选')
    expect(wrapper.text()).not.toContain('A')
    authStoreRef.value!.factoryId = 'F006'
    authStoreRef.value!.currentRole = 'permission_admin'
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).not.toContain('非支配候选')
  })

  it.each([
    ['factsVerified', { factsVerified: true }],
    ['businessExecutionPerformed', { businessExecutionPerformed: true }],
    ['resourcesReserved', { resourcesReserved: true }],
    ['unknown status', { status: 'SUCCEEDED' }],
    ['unknown reason', { reason: 'WINNER_SELECTED' }],
    ['foreign candidate id', { candidates: [{ id: 'FOREIGN', status: 'ELIGIBLE_BY_SUPPLIED_FACTS', reasons: [], dominatedByCandidateIds: [] }, comparable.candidates[1]] }],
  ])('rejects unsafe declaration or malformed candidate data: %s', async (_label, patch) => {
    evaluateProposalComparison.mockResolvedValue({ success: true, data: { ...comparable, ...patch } })
    const wrapper = mount(ProposalComparisonPage)
    await fillValidForm(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('返回结果无法核实')
    expect(wrapper.text()).not.toContain('非支配候选')
  })
})
