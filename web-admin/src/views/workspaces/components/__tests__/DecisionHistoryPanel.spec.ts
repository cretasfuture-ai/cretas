import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const listDecisionHistory = vi.hoisted(() => vi.fn())
const compareDecisionHistory = vi.hoisted(() => vi.fn())
const replayDecisionHistory = vi.hoisted(() => vi.fn())
const permissionState = vi.hoisted(() => ({ isDbLoaded: true, canAccess: vi.fn(() => true) }))
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => permissionState }))
vi.mock('@/api/decisionHistory', async () => {
  const actual = await vi.importActual<typeof import('@/api/decisionHistory')>('@/api/decisionHistory')
  return { ...actual, listDecisionHistory, compareDecisionHistory, replayDecisionHistory }
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
const replayMatch = {
  decisionId: 'decision-current', status: 'REPLAY_MATCH', reason: 'RECORDED_RECOMMENDATION_MATCHES_REPLAY',
  evaluationScope: 'QUALITY_RECOMMENDATION_ONLY', evaluatorId: 'QUALITY_RECOMMENDATION_V1', evaluatorVersion: '1',
  ruleBundleHash: 'b'.repeat(64), snapshotHash: 'c'.repeat(64), recordedRecommendation: 'RELEASE', replayedRecommendation: 'RELEASE',
  recordedConfidence: '0.95', replayedConfidence: '0.95',
  snapshotConsistencyVerified: true, ruleEvaluationPerformed: true, businessExecutionPerformed: false, approvalEvaluated: false,
}

describe('DecisionHistoryPanel', () => {
  beforeEach(() => {
    listDecisionHistory.mockReset(); compareDecisionHistory.mockReset(); replayDecisionHistory.mockReset()
    permissionState.canAccess.mockReset(); permissionState.canAccess.mockReturnValue(true)
  })

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

  it('replays only the selected current record and exposes readonly evaluator facts', async () => {
    listDecisionHistory.mockResolvedValue({ success: true, data: page })
    replayDecisionHistory.mockResolvedValue({ success: true, data: replayMatch })
    const wrapper = mount(DecisionHistoryPanel, { props: { factoryId: 'F006', role: 'factory_super_admin' } })
    await wrapper.get('[data-testid="history-load"]').trigger('click'); await flushPromises()
    await wrapper.get('[data-testid="history-current-decision-current"]').setValue(true)
    await wrapper.get('[data-testid="history-replay"]').trigger('click'); await flushPromises()
    expect(replayDecisionHistory).toHaveBeenCalledWith('F006', 'decision-current')
    expect(wrapper.get('[data-testid="history-replay-result"]').text()).toContain('建议复算一致')
    expect(wrapper.text()).toContain('QUALITY_RECOMMENDATION_V1')
    expect(wrapper.text()).toContain('0.95 / 0.95')
    expect(wrapper.text()).toContain('动作与置信度均为纯输出对比')
    expect(wrapper.text()).toContain('不会审批、释放、执行、保存或预留资源')
    expect(wrapper.text()).toContain('不是防篡改签名')
  })

  it('keeps legacy or missing snapshots visibly unavailable and rejects corrupt replay data', async () => {
    listDecisionHistory.mockResolvedValue({ success: true, data: page })
    replayDecisionHistory.mockResolvedValueOnce({ success: true, data: {
      decisionId: 'decision-current', status: 'UNAVAILABLE', reason: 'SNAPSHOT_MISSING_OR_INVALID',
      evaluationScope: 'QUALITY_RECOMMENDATION_ONLY', evaluatorId: null, evaluatorVersion: null, ruleBundleHash: null, snapshotHash: null,
      recordedRecommendation: null, replayedRecommendation: null, snapshotConsistencyVerified: false, ruleEvaluationPerformed: false,
      recordedConfidence: null, replayedConfidence: null,
      businessExecutionPerformed: false, approvalEvaluated: false,
    } })
    const wrapper = mount(DecisionHistoryPanel, { props: { factoryId: 'F006', role: 'factory_super_admin' } })
    await wrapper.get('[data-testid="history-load"]').trigger('click'); await flushPromises()
    await wrapper.get('[data-testid="history-current-decision-current"]').setValue(true)
    await wrapper.get('[data-testid="history-replay"]').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="history-replay-result"]').text()).toContain('不可复算')
    expect(wrapper.get('[data-testid="history-replay-result"]').text()).toContain('未提供')

    replayDecisionHistory.mockResolvedValueOnce({ success: true, data: {
      ...replayMatch, status: 'REPLAY_DIFFERENT', reason: 'RECORDED_CONFIDENCE_DIFFERS_FROM_REPLAY', replayedConfidence: '0.90',
    } })
    await wrapper.get('[data-testid="history-replay"]').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-testid="history-replay-result"]').text()).toContain('建议复算不同')
    expect(wrapper.get('[data-testid="history-replay-result"]').text()).toContain('0.95 / 0.90')

    replayDecisionHistory.mockResolvedValueOnce({ success: true, data: { ...replayMatch, snapshotHash: 'bad' } })
    await wrapper.get('[data-testid="history-replay"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('复算结果无法核验')
    expect(wrapper.find('[data-testid="history-replay-result"]').exists()).toBe(false)

    replayDecisionHistory.mockResolvedValueOnce({ success: true, data: {
      ...replayMatch, status: 'REPLAY_DIFFERENT', reason: 'RECORDED_RECOMMENDATION_DIFFERS_FROM_REPLAY', replayedConfidence: '0.90',
    } })
    await wrapper.get('[data-testid="history-replay"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('复算结果无法核验')
    expect(wrapper.find('[data-testid="history-replay-result"]').exists()).toBe(false)
  })

  it('does not double-submit or render a late replay response after context changes', async () => {
    listDecisionHistory.mockResolvedValue({ success: true, data: page })
    let resolveReplay!: (value: unknown) => void
    replayDecisionHistory.mockReturnValue(new Promise(resolve => { resolveReplay = resolve }))
    const wrapper = mount(DecisionHistoryPanel, { props: { factoryId: 'F006', role: 'factory_super_admin' } })
    await wrapper.get('[data-testid="history-load"]').trigger('click'); await flushPromises()
    await wrapper.get('[data-testid="history-current-decision-current"]').setValue(true)
    await wrapper.get('[data-testid="history-replay"]').trigger('click')
    await wrapper.get('[data-testid="history-replay"]').trigger('click')
    expect(replayDecisionHistory).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ factoryId: 'F007', role: 'warehouse_worker' })
    resolveReplay({ success: true, data: replayMatch }); await flushPromises()
    expect(wrapper.find('[data-testid="history-replay-result"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂时无法读取决策历史')
  })

  it('keeps the replay button disabled when quality read is denied', async () => {
    permissionState.canAccess.mockImplementation((module: string) => module !== 'quality')
    listDecisionHistory.mockResolvedValue({ success: true, data: page })
    const wrapper = mount(DecisionHistoryPanel, { props: { factoryId: 'F006', role: 'factory_super_admin' } })
    await wrapper.get('[data-testid="history-load"]').trigger('click'); await flushPromises()
    await wrapper.get('[data-testid="history-current-decision-current"]').setValue(true)
    expect((wrapper.get('[data-testid="history-replay"]').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.text()).toContain('缺少系统读取或质量读取权限')
    expect(replayDecisionHistory).not.toHaveBeenCalled()
  })
})
