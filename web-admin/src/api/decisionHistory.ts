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
const HASH = /^[0-9a-f]{64}$/i

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
