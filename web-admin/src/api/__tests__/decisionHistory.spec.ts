import { describe, expect, it, vi } from 'vitest'

const get = vi.hoisted(() => vi.fn())
vi.mock('../request', () => ({ get }))

import {
  compareDecisionHistory,
  isDecisionHistoryPage,
  isDecisionSnapshotComparison,
  listDecisionHistory,
  type DecisionHistoryPage,
} from '../decisionHistory'

const record = {
  id: 'decision-current', decisionType: 'RULE_EXECUTION', decisionCode: null,
  entityType: 'PurchaseOrder', entityId: 'order-1', ruleConfigId: 'rule-7', ruleConfigVersion: 3,
  createdAt: '2026-11-07T01:00:00Z',
} as const

describe('decision history API contract', () => {
  it('uses the exact same-factory metadata path and bounded query', async () => {
    get.mockResolvedValue({ success: true, data: {} })
    await listDecisionHistory('F006', 2, 20)
    expect(get).toHaveBeenCalledWith('/F006/decision-history', { params: { page: 2, size: 20 } })
  })

  it('uses the exact comparison path and rejects unsafe paths or bounds', async () => {
    get.mockResolvedValue({ success: true, data: {} })
    await compareDecisionHistory('F006', 'decision-current', 'decision-baseline')
    expect(get).toHaveBeenCalledWith('/F006/decision-history/decision-current/comparison?baselineId=decision-baseline')
    expect(() => listDecisionHistory('F006/other', 0, 20)).toThrow('工厂标识不合法')
    expect(() => listDecisionHistory('F006', -1, 20)).toThrow('页码不合法')
    expect(() => listDecisionHistory('F006', 0, 51)).toThrow('页大小不合法')
    expect(() => compareDecisionHistory('F006', 'bad/id', 'decision-baseline')).toThrow('决策记录标识不合法')
  })

  it('validates privacy-safe metadata and strict comparison identity/status semantics', () => {
    const page: DecisionHistoryPage = { records: [record], page: 0, pageSize: 20, totalElements: 1, totalPages: 1 }
    expect(isDecisionHistoryPage(page, 0, 20)).toBe(true)
    expect(isDecisionHistoryPage({ ...page, records: [{ ...record, rawInput: 'must-not-be-consumed' }] }, 0, 20)).toBe(false)
    expect(isDecisionHistoryPage({ ...page, records: [record, { ...record }] }, 0, 20)).toBe(false)
    expect(isDecisionHistoryPage({ ...page, records: [{ ...record, decisionType: 'UNKNOWN' }] }, 0, 20)).toBe(false)
    expect(isDecisionHistoryPage({ ...page, totalElements: 21, totalPages: 1 }, 0, 20)).toBe(false)
    expect(isDecisionHistoryPage({ ...page, totalElements: 0, totalPages: 0 }, 0, 20)).toBe(false)
    expect(isDecisionHistoryPage({ ...page, records: [] }, 0, 20)).toBe(false)
    expect(isDecisionHistoryPage({ ...page, records: [], totalElements: 0, totalPages: 0 }, 0, 20)).toBe(true)
    expect(isDecisionHistoryPage({ ...page, records: [{ ...record, ruleConfigVersion: -1 }] }, 0, 20)).toBe(false)

    const comparable = {
      decisionId: 'decision-current', baselineId: 'decision-baseline', status: 'SAME_RESULT',
      reason: 'RECORDED_OUTPUT_EQUAL', canonicalRecordedOutputHash: 'a'.repeat(64), canonicalBaselineOutputHash: 'b'.repeat(64),
      currentRuleConfigId: 'rule-7', currentRuleConfigVersion: 3, baselineRuleConfigId: 'rule-6', baselineRuleConfigVersion: 2,
      businessExecutionPerformed: false, integrityVerified: true, recordedOutputRelation: 'SAME', comparisonBasis: 'CHECKSUM_AND_COLUMNS',
    }
    expect(isDecisionSnapshotComparison(comparable, 'decision-current', 'decision-baseline')).toBe(false)
    expect(isDecisionSnapshotComparison({ ...comparable, canonicalBaselineOutputHash: 'a'.repeat(64) }, 'decision-current', 'decision-baseline')).toBe(true)
    expect(isDecisionSnapshotComparison({ ...comparable, canonicalRecordedOutputHash: null, canonicalBaselineOutputHash: null }, 'decision-current', 'decision-baseline')).toBe(false)
    expect(isDecisionSnapshotComparison({ ...comparable, canonicalRecordedOutputHash: 'not-a-hash' }, 'decision-current', 'decision-baseline')).toBe(false)
    expect(isDecisionSnapshotComparison({ ...comparable, integrityVerified: false }, 'decision-current', 'decision-baseline')).toBe(false)
    expect(isDecisionSnapshotComparison({ ...comparable, decisionId: 'other' }, 'decision-current', 'decision-baseline')).toBe(false)
    expect(isDecisionSnapshotComparison({ ...comparable, canonicalBaselineOutputHash: 'a'.repeat(64), status: 'NOT_COMPARABLE', reason: 'CHECKSUM_MISMATCH_NO_INTEGRITY_PROOF', integrityVerified: false, recordedOutputRelation: 'SAME', comparisonBasis: 'PERSISTED_COLUMNS_SEMANTIC_ONLY' }, 'decision-current', 'decision-baseline')).toBe(true)
    expect(isDecisionSnapshotComparison({ ...comparable, status: 'DIFFERENT_RESULT', reason: 'RECORDED_OUTPUT_DIFFERENT', recordedOutputRelation: 'DIFFERENT' }, 'decision-current', 'decision-baseline')).toBe(true)
    expect(isDecisionSnapshotComparison({ ...comparable, status: 'DIFFERENT_RESULT', reason: 'RECORDED_OUTPUT_DIFFERENT', recordedOutputRelation: 'DIFFERENT', canonicalBaselineOutputHash: 'a'.repeat(64) }, 'decision-current', 'decision-baseline')).toBe(false)
    expect(isDecisionSnapshotComparison({ ...comparable, status: 'NOT_COMPARABLE', reason: 'INPUT_MISMATCH', integrityVerified: false, canonicalRecordedOutputHash: null, canonicalBaselineOutputHash: null, recordedOutputRelation: 'NOT_EVALUATED', comparisonBasis: 'NOT_EVALUATED', rawOutput: 'secret' }, 'decision-current', 'decision-baseline')).toBe(false)
  })
})
