import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const listDecisionHistory = vi.hoisted(() => vi.fn())
const compareDecisionHistory = vi.hoisted(() => vi.fn())
vi.mock('@/api/decisionHistory', async () => {
  const actual = await vi.importActual<typeof import('@/api/decisionHistory')>('@/api/decisionHistory')
  return { ...actual, listDecisionHistory, compareDecisionHistory }
})

import DecisionHistoryPanel from '../DecisionHistoryPanel.vue'

const records = [
  { id: 'decision-current', decisionType: 'RULE_EXECUTION', decisionCode: null, entityType: 'PurchaseOrder', entityId: 'order-1', ruleConfigId: 'rule-7', ruleConfigVersion: 3, createdAt: '2026-11-07T02:00:00Z' },
  { id: 'decision-baseline', decisionType: 'RULE_EXECUTION', decisionCode: 'RULE_1', entityType: 'PurchaseOrder', entityId: 'order-1', ruleConfigId: 'rule-6', ruleConfigVersion: 2, createdAt: '2026-11-07T01:00:00Z' },
] as const
const page = { records, page: 0, pageSize: 20, totalElements: 2, totalPages: 1 }
const sameResult = {
  decisionId: 'decision-current', baselineId: 'decision-baseline', status: 'SAME_RESULT', reason: 'RECORDED_OUTPUT_EQUAL',
  canonicalRecordedOutputHash: 'a'.repeat(64), canonicalBaselineOutputHash: 'a'.repeat(64), currentRuleConfigId: 'rule-7', currentRuleConfigVersion: 3,
  baselineRuleConfigId: 'rule-6', baselineRuleConfigVersion: 2, businessExecutionPerformed: false, integrityVerified: true,
  recordedOutputRelation: 'SAME', comparisonBasis: 'CHECKSUM_AND_COLUMNS',
}

describe('DecisionHistoryPanel', () => {
  beforeEach(() => { listDecisionHistory.mockReset(); compareDecisionHistory.mockReset() })

  it('loads same-factory records, selects a distinct pair, and renders a verified comparison', async () => {
    listDecisionHistory.mockResolvedValue({ success: true, data: page })
    compareDecisionHistory.mockResolvedValue({ success: true, data: sameResult })
    const wrapper = mount(DecisionHistoryPanel, { props: { factoryId: 'F006', role: 'factory_super_admin' } })
    await wrapper.get('[data-testid="history-load"]').trigger('click')
    await flushPromises()
    expect(listDecisionHistory).toHaveBeenCalledWith('F006', 0, 20)
    expect(wrapper.get('[data-testid="history-record-decision-current"]')).toBeTruthy()
    await wrapper.get('[data-testid="history-current-decision-current"]').setValue(true)
    await wrapper.get('[data-testid="history-baseline-decision-baseline"]').setValue(true)
    await wrapper.get('[data-testid="history-compare"]').trigger('click')
    await flushPromises()
    expect(compareDecisionHistory).toHaveBeenCalledWith('F006', 'decision-current', 'decision-baseline')
    expect(wrapper.get('[data-testid="history-result"]').text()).toContain('记录结果一致')
    expect(wrapper.text()).toContain('校验和已核验')
    expect(wrapper.text()).toContain('不会重新执行规则')
    expect(wrapper.get('[data-testid="history-integrity-limit"]').text()).toContain('内部一致')
    expect(wrapper.get('[data-testid="history-integrity-limit"]').text()).toContain('不是防篡改签名')
  })

  it('keeps a semantic-only result explicitly not comparable', async () => {
    listDecisionHistory.mockResolvedValue({ success: true, data: page })
    compareDecisionHistory.mockResolvedValue({ success: true, data: {
      ...sameResult, status: 'NOT_COMPARABLE', reason: 'CHECKSUM_MISMATCH_NO_INTEGRITY_PROOF',
      integrityVerified: false, comparisonBasis: 'PERSISTED_COLUMNS_SEMANTIC_ONLY',
    } })
    const wrapper = mount(DecisionHistoryPanel, { props: { factoryId: 'F006', role: 'permission_admin' } })
    await wrapper.get('[data-testid="history-load"]').trigger('click'); await flushPromises()
    await wrapper.get('[data-testid="history-current-decision-current"]').setValue(true)
    await wrapper.get('[data-testid="history-baseline-decision-baseline"]').setValue(true)
    await wrapper.get('[data-testid="history-compare"]').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="history-result"]').text()).toContain('不可比较')
    expect(wrapper.text()).toContain('校验和未核验')
    expect(wrapper.text()).not.toContain('记录结果一致（已核验）')
  })

  it('fails closed for empty/forbidden list responses and drops stale context responses', async () => {
    let resolveList!: (value: unknown) => void
    listDecisionHistory.mockReturnValue(new Promise(resolve => { resolveList = resolve }))
    const wrapper = mount(DecisionHistoryPanel, { props: { factoryId: 'F006', role: 'factory_super_admin' } })
    await wrapper.get('[data-testid="history-load"]').trigger('click')
    await wrapper.setProps({ factoryId: 'F007' })
    resolveList({ success: true, data: page }); await flushPromises()
    expect(wrapper.text()).not.toContain('decision-current')

    listDecisionHistory.mockResolvedValueOnce({ success: false, message: '当前账号无权访问' })
    await wrapper.get('[data-testid="history-load"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('当前账号无权访问')
  })

  it('clears an old comparison when refreshing and ignores its late response after list failure', async () => {
    let resolveCompare!: (value: unknown) => void
    listDecisionHistory
      .mockResolvedValueOnce({ success: true, data: page })
      .mockResolvedValueOnce({ success: false, message: '刷新失败' })
    compareDecisionHistory.mockReturnValue(new Promise(resolve => { resolveCompare = resolve }))
    const wrapper = mount(DecisionHistoryPanel, { props: { factoryId: 'F006', role: 'factory_super_admin' } })
    await wrapper.get('[data-testid="history-load"]').trigger('click'); await flushPromises()
    await wrapper.get('[data-testid="history-current-decision-current"]').setValue(true)
    await wrapper.get('[data-testid="history-baseline-decision-baseline"]').setValue(true)
    await wrapper.get('[data-testid="history-compare"]').trigger('click')
    await wrapper.get('[data-testid="history-load"]').trigger('click'); await flushPromises()
    resolveCompare(sameResult); await flushPromises()
    expect(wrapper.text()).toContain('刷新失败')
    expect(wrapper.find('[data-testid="history-result"]').exists()).toBe(false)
    expect(compareDecisionHistory).toHaveBeenCalledTimes(1)
  })

  it('does not show a late comparison error after the role context changes', async () => {
    let rejectCompare!: (reason: unknown) => void
    listDecisionHistory.mockResolvedValue({ success: true, data: page })
    compareDecisionHistory.mockReturnValue(new Promise((_, reject) => { rejectCompare = reject }))
    const wrapper = mount(DecisionHistoryPanel, { props: { factoryId: 'F006', role: 'factory_super_admin' } })
    await wrapper.get('[data-testid="history-load"]').trigger('click'); await flushPromises()
    await wrapper.get('[data-testid="history-current-decision-current"]').setValue(true)
    await wrapper.get('[data-testid="history-baseline-decision-baseline"]').setValue(true)
    await wrapper.get('[data-testid="history-compare"]').trigger('click')
    await wrapper.setProps({ role: 'permission_admin' })
    rejectCompare(new Error('旧上下文错误')); await flushPromises()
    expect(wrapper.text()).not.toContain('旧上下文错误')
    expect(wrapper.text()).not.toContain('对照未完成')
  })
})
