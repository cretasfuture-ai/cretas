import { get } from './request'
import type { ApiResponse } from '@/types/api'

export const DECISION_TYPES = [
  'RULE_EXECUTION', 'STATE_TRANSITION', 'FORCE_INSERT', 'APPROVAL', 'AI_ANALYSIS', 'MANUAL_OVERRIDE',
] as const
export type DecisionType = (typeof DECISION_TYPES)[number]

export interface DecisionHistoryRecord {
  id: string
  decisionType: DecisionType
  decisionCode: string | null
  entityType: string
  entityId: string
  ruleConfigId: string | null
  ruleConfigVersion: number | null
  createdAt: string | null
}

export interface DecisionHistoryPage {
  records: DecisionHistoryRecord[]
  page: number
  pageSize: number
  totalElements: number
  totalPages: number
}

export type DecisionSnapshotComparisonStatus = 'SAME_RESULT' | 'DIFFERENT_RESULT' | 'NOT_COMPARABLE'
export type RecordedOutputRelation = 'SAME' | 'DIFFERENT' | 'NOT_EVALUATED'
export type ComparisonBasis = 'CHECKSUM_AND_COLUMNS' | 'PERSISTED_COLUMNS_SEMANTIC_ONLY' | 'NOT_EVALUATED'

export interface DecisionSnapshotComparison {
  decisionId: string
  baselineId: string
  status: DecisionSnapshotComparisonStatus
  reason: string
  canonicalRecordedOutputHash: string | null
  canonicalBaselineOutputHash: string | null
  currentRuleConfigId: string | null
  currentRuleConfigVersion: number | null
  baselineRuleConfigId: string | null
  baselineRuleConfigVersion: number | null
  businessExecutionPerformed: false
  integrityVerified: boolean
  recordedOutputRelation: RecordedOutputRelation
  comparisonBasis: ComparisonBasis
}

export const QUALITY_RECOMMENDATION_ACTIONS = [
  'RELEASE', 'CONDITIONAL_RELEASE', 'REWORK', 'SCRAP', 'SPECIAL_APPROVAL', 'HOLD',
] as const
export type QualityRecommendationAction = (typeof QUALITY_RECOMMENDATION_ACTIONS)[number]
export type DecisionRecommendationReplayStatus = 'REPLAY_MATCH' | 'REPLAY_DIFFERENT' | 'UNAVAILABLE'

export interface DecisionRecommendationReplay {
  decisionId: string
  status: DecisionRecommendationReplayStatus
  reason: string
  evaluationScope: 'QUALITY_RECOMMENDATION_ONLY'
  evaluatorId: string | null
  evaluatorVersion: string | null
  ruleBundleHash: string | null
  snapshotHash: string | null
  recordedRecommendation: QualityRecommendationAction | null
  replayedRecommendation: QualityRecommendationAction | null
  recordedConfidence: string | null
  replayedConfidence: string | null
  snapshotConsistencyVerified: boolean
  ruleEvaluationPerformed: boolean
  businessExecutionPerformed: false
  approvalEvaluated: false
}

const FACTORY_ID = /^[A-Za-z0-9_-]{1,64}$/
const RECORD_ID = /^[A-Za-z0-9_-]{1,128}$/
const DECISION_REASONS = new Set([
  'INVALID_HISTORICAL_BASELINE', 'BUSINESS_SCOPE_MISMATCH', 'SNAPSHOT_MISSING_OR_INVALID',
  'INPUT_MISMATCH', 'RECORDED_OUTPUT_EQUAL', 'RECORDED_OUTPUT_DIFFERENT',
  'CHECKSUM_MISMATCH_NO_INTEGRITY_PROOF',
])
const RECORD_KEYS = new Set(['id', 'decisionType', 'decisionCode', 'entityType', 'entityId', 'ruleConfigId', 'ruleConfigVersion', 'createdAt'])
const PAGE_KEYS = new Set(['records', 'page', 'pageSize', 'totalElements', 'totalPages'])
const COMPARISON_KEYS = new Set([
  'decisionId', 'baselineId', 'status', 'reason', 'canonicalRecordedOutputHash', 'canonicalBaselineOutputHash',
  'currentRuleConfigId', 'currentRuleConfigVersion', 'baselineRuleConfigId', 'baselineRuleConfigVersion',
  'businessExecutionPerformed', 'integrityVerified', 'recordedOutputRelation', 'comparisonBasis',
])
const REPLAY_KEYS = new Set([
  'decisionId', 'status', 'reason', 'evaluationScope', 'evaluatorId', 'evaluatorVersion', 'ruleBundleHash',
  'snapshotHash', 'recordedRecommendation', 'replayedRecommendation', 'snapshotConsistencyVerified',
  'recordedConfidence', 'replayedConfidence',
  'ruleEvaluationPerformed', 'businessExecutionPerformed', 'approvalEvaluated',
])
const HASH = /^[0-9a-f]{64}$/i
const REPLAY_REASON = /^[A-Z][A-Z0-9_]{1,79}$/
const PLAIN_DECIMAL = /^(?:0|[1-9]\d*)(?:\.\d+)?$/

function assertFactoryId(factoryId: string): void {
  if (!FACTORY_ID.test(factoryId)) throw new Error('工厂标识不合法，已阻止历史记录请求')
}

function assertRecordId(id: string): void {
  if (!RECORD_ID.test(id)) throw new Error('决策记录标识不合法，已阻止历史记录请求')
}

function nonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

export function isDecisionHistoryRecord(value: unknown): value is DecisionHistoryRecord {
  if (!value || typeof value !== 'object') return false
  const record = value as Partial<DecisionHistoryRecord>
  return Object.keys(record).every(key => RECORD_KEYS.has(key))
    && typeof record.id === 'string' && RECORD_ID.test(record.id)
    && typeof record.decisionType === 'string' && DECISION_TYPES.includes(record.decisionType as DecisionType)
    && (record.decisionCode === null || typeof record.decisionCode === 'string')
    && typeof record.entityType === 'string' && record.entityType.length > 0
    && typeof record.entityId === 'string' && record.entityId.length > 0
    && (record.ruleConfigId === null || typeof record.ruleConfigId === 'string')
    && (record.ruleConfigVersion === null || nonNegativeInteger(record.ruleConfigVersion))
    && (record.createdAt === null || typeof record.createdAt === 'string')
}

export function isDecisionHistoryPage(value: unknown, expectedPage?: number, expectedPageSize?: number): value is DecisionHistoryPage {
  if (!value || typeof value !== 'object') return false
  const page = value as Partial<DecisionHistoryPage>
  if (!Object.keys(page).every(key => PAGE_KEYS.has(key))
    || !Array.isArray(page.records) || !page.records.every(isDecisionHistoryRecord)) return false
  const ids = page.records.map(record => record.id)
  if (new Set(ids).size !== ids.length) return false
  if (!nonNegativeInteger(page.page)
    || !nonNegativeInteger(page.pageSize) || page.pageSize < 1 || page.pageSize > 50
    || (expectedPage !== undefined && page.page !== expectedPage)
    || (expectedPageSize !== undefined && page.pageSize !== expectedPageSize)
    || !nonNegativeInteger(page.totalElements)
    || !nonNegativeInteger(page.totalPages)
    || page.records.length > page.pageSize
    || page.records.length > Math.max(0, page.totalElements - page.page * page.pageSize)) return false
  const expectedTotalPages = page.totalElements === 0 ? 0 : Math.ceil(page.totalElements / page.pageSize)
  return page.totalPages === expectedTotalPages
    && (page.totalPages === 0 || page.page < page.totalPages)
    && (page.totalElements === 0 ? page.records.length === 0 : page.records.length > 0)
}

function nullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function nullableVersion(value: unknown): value is number | null {
  return value === null || nonNegativeInteger(value)
}

export function isDecisionSnapshotComparison(
  value: unknown,
  decisionId: string,
  baselineId: string,
): value is DecisionSnapshotComparison {
  if (!value || typeof value !== 'object') return false
  const comparison = value as Partial<DecisionSnapshotComparison>
  if (!Object.keys(comparison).every(key => COMPARISON_KEYS.has(key))
    || comparison.decisionId !== decisionId || comparison.baselineId !== baselineId
    || !['SAME_RESULT', 'DIFFERENT_RESULT', 'NOT_COMPARABLE'].includes(comparison.status as string)
    || typeof comparison.reason !== 'string' || !DECISION_REASONS.has(comparison.reason)
    || !nullableString(comparison.canonicalRecordedOutputHash)
    || !nullableString(comparison.canonicalBaselineOutputHash)
    || !nullableString(comparison.currentRuleConfigId)
    || !nullableVersion(comparison.currentRuleConfigVersion)
    || !nullableString(comparison.baselineRuleConfigId)
    || !nullableVersion(comparison.baselineRuleConfigVersion)
    || comparison.businessExecutionPerformed !== false
    || typeof comparison.integrityVerified !== 'boolean'
    || !['SAME', 'DIFFERENT', 'NOT_EVALUATED'].includes(comparison.recordedOutputRelation as string)
    || !['CHECKSUM_AND_COLUMNS', 'PERSISTED_COLUMNS_SEMANTIC_ONLY', 'NOT_EVALUATED'].includes(comparison.comparisonBasis as string)) {
    return false
  }
  const hashesAreValid = typeof comparison.canonicalRecordedOutputHash === 'string'
    && HASH.test(comparison.canonicalRecordedOutputHash)
    && typeof comparison.canonicalBaselineOutputHash === 'string'
    && HASH.test(comparison.canonicalBaselineOutputHash)
  if (comparison.status === 'SAME_RESULT') {
    return comparison.reason === 'RECORDED_OUTPUT_EQUAL'
      && comparison.integrityVerified === true
      && comparison.recordedOutputRelation === 'SAME'
      && comparison.comparisonBasis === 'CHECKSUM_AND_COLUMNS'
      && hashesAreValid
      && comparison.canonicalRecordedOutputHash === comparison.canonicalBaselineOutputHash
  }
  if (comparison.status === 'DIFFERENT_RESULT') {
    return comparison.reason === 'RECORDED_OUTPUT_DIFFERENT'
      && comparison.integrityVerified === true
      && comparison.recordedOutputRelation === 'DIFFERENT'
      && comparison.comparisonBasis === 'CHECKSUM_AND_COLUMNS'
      && hashesAreValid
      && comparison.canonicalRecordedOutputHash !== comparison.canonicalBaselineOutputHash
  }
  if (comparison.integrityVerified !== false) return false
  if (comparison.reason === 'CHECKSUM_MISMATCH_NO_INTEGRITY_PROOF') {
    if (comparison.recordedOutputRelation === 'NOT_EVALUATED'
      || comparison.comparisonBasis !== 'PERSISTED_COLUMNS_SEMANTIC_ONLY'
      || !hashesAreValid) return false
    return comparison.recordedOutputRelation === 'SAME'
      ? comparison.canonicalRecordedOutputHash === comparison.canonicalBaselineOutputHash
      : comparison.canonicalRecordedOutputHash !== comparison.canonicalBaselineOutputHash
  }
  return ['INVALID_HISTORICAL_BASELINE', 'BUSINESS_SCOPE_MISMATCH', 'SNAPSHOT_MISSING_OR_INVALID', 'INPUT_MISMATCH'].includes(comparison.reason)
    && comparison.recordedOutputRelation === 'NOT_EVALUATED'
    && comparison.comparisonBasis === 'NOT_EVALUATED'
    && comparison.canonicalRecordedOutputHash === null
    && comparison.canonicalBaselineOutputHash === null
}

function nullableHash(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && HASH.test(value))
}

function nullableQualityAction(value: unknown): value is QualityRecommendationAction | null {
  return value === null || (typeof value === 'string' && QUALITY_RECOMMENDATION_ACTIONS.includes(value as QualityRecommendationAction))
}

function nullablePlainDecimal(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && PLAIN_DECIMAL.test(value))
}

export function isDecisionRecommendationReplay(value: unknown, decisionId: string): value is DecisionRecommendationReplay {
  if (!value || typeof value !== 'object') return false
  const replay = value as Partial<DecisionRecommendationReplay>
  if (!Object.keys(replay).every(key => REPLAY_KEYS.has(key))
    || replay.decisionId !== decisionId
    || !['REPLAY_MATCH', 'REPLAY_DIFFERENT', 'UNAVAILABLE'].includes(replay.status as string)
    || typeof replay.reason !== 'string' || !REPLAY_REASON.test(replay.reason)
    || replay.evaluationScope !== 'QUALITY_RECOMMENDATION_ONLY'
    || (replay.evaluatorId !== null && typeof replay.evaluatorId !== 'string')
    || (replay.evaluatorVersion !== null && typeof replay.evaluatorVersion !== 'string')
    || !nullableHash(replay.ruleBundleHash) || !nullableHash(replay.snapshotHash)
    || !nullableQualityAction(replay.recordedRecommendation) || !nullableQualityAction(replay.replayedRecommendation)
    || !nullablePlainDecimal(replay.recordedConfidence) || !nullablePlainDecimal(replay.replayedConfidence)
    || typeof replay.snapshotConsistencyVerified !== 'boolean'
    || typeof replay.ruleEvaluationPerformed !== 'boolean'
    || replay.businessExecutionPerformed !== false || replay.approvalEvaluated !== false) return false

  if (replay.status === 'REPLAY_MATCH' || replay.status === 'REPLAY_DIFFERENT') {
    const hashes = typeof replay.ruleBundleHash === 'string' && typeof replay.snapshotHash === 'string'
    const recommendations = replay.recordedRecommendation !== null && replay.replayedRecommendation !== null
    const confidence = replay.recordedConfidence !== null && replay.replayedConfidence !== null
    return hashes && recommendations && confidence && replay.evaluatorId !== null && replay.evaluatorVersion !== null
      && replay.snapshotConsistencyVerified === true && replay.ruleEvaluationPerformed === true
      && (replay.status === 'REPLAY_MATCH'
        ? replay.reason === 'RECORDED_RECOMMENDATION_MATCHES_REPLAY'
          && replay.recordedRecommendation === replay.replayedRecommendation
          && replay.recordedConfidence === replay.replayedConfidence
        : replay.recordedRecommendation !== replay.replayedRecommendation
          ? replay.reason === 'RECORDED_RECOMMENDATION_DIFFERS_FROM_REPLAY'
          : replay.reason === 'RECORDED_CONFIDENCE_DIFFERS_FROM_REPLAY'
            && replay.recordedConfidence !== replay.replayedConfidence)
  }
  return replay.recordedRecommendation === null && replay.replayedRecommendation === null
    && replay.recordedConfidence === null && replay.replayedConfidence === null
    && replay.snapshotConsistencyVerified === false && replay.ruleEvaluationPerformed === false
}

export function listDecisionHistory(
  factoryId: string,
  page = 0,
  size = 20,
): Promise<ApiResponse<DecisionHistoryPage>> {
  assertFactoryId(factoryId)
  if (!Number.isInteger(page) || page < 0 || page > 100000) throw new Error('历史记录页码不合法')
  if (!Number.isInteger(size) || size < 1 || size > 50) throw new Error('历史记录页大小不合法')
  return get<DecisionHistoryPage>(`/${factoryId}/decision-history`, { params: { page, size } })
}

export function compareDecisionHistory(
  factoryId: string,
  decisionId: string,
  baselineId: string,
): Promise<ApiResponse<DecisionSnapshotComparison>> {
  assertFactoryId(factoryId)
  assertRecordId(decisionId)
  assertRecordId(baselineId)
  const query = encodeURIComponent(baselineId)
  return get<DecisionSnapshotComparison>(
    `/${factoryId}/decision-history/${encodeURIComponent(decisionId)}/comparison?baselineId=${query}`,
  )
}

export function replayDecisionHistory(
  factoryId: string,
  decisionId: string,
): Promise<ApiResponse<DecisionRecommendationReplay>> {
  assertFactoryId(factoryId)
  assertRecordId(decisionId)
  return get<DecisionRecommendationReplay>(
    `/${factoryId}/decision-history/${encodeURIComponent(decisionId)}/replay-evaluation`,
  )
}
