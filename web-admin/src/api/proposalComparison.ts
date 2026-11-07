import { post } from './request'
import type { ApiResponse } from '@/types/api'

export type ResourceBudgetUnit = 'PERSON_HOUR' | 'MACHINE_HOUR'

export interface ProposalComparisonCandidate {
  id: string
  comparisonBasisId: string
  currency: string
  costUnit: string
  resourceUnit: ResourceBudgetUnit
  cost: number | null
  completionTime: string | null
  requiredResource: number | null
}

export interface ProposalComparisonRequest {
  comparisonBasisId: string
  currency: string
  costUnit: string
  resourceUnit: ResourceBudgetUnit
  windowStart: string
  windowEnd: string
  requiredBy: string | null
  availableResource: number | null
  candidates: ProposalComparisonCandidate[]
}

export type ProposalComparisonStatus = 'SUPPLIED_FACTS_COMPARABLE' | 'NOT_COMPARABLE'
export type ProposalCandidateStatus = 'ELIGIBLE_BY_SUPPLIED_FACTS' | 'EXCLUDED_BY_SUPPLIED_FACTS' | 'NOT_COMPARABLE'

export type ProposalComparisonReason =
  | 'BASIS_MISMATCH'
  | 'CURRENCY_MISMATCH'
  | 'COST_UNIT_MISMATCH'
  | 'RESOURCE_UNIT_MISMATCH'
  | 'MISSING_COST'
  | 'MISSING_COMPLETION_TIME'
  | 'MISSING_RESOURCE_DEMAND'
  | 'MISSING_RESOURCE_CAPACITY'
  | 'COMPLETION_OUTSIDE_WINDOW'
  | 'RESOURCE_SHORTAGE'
  | 'DEADLINE_MISSED'

export interface ProposalCandidateEvaluation {
  id: string
  status: ProposalCandidateStatus
  reasons: ProposalComparisonReason[]
  dominatedByCandidateIds: string[]
}

export interface ProposalComparisonResult {
  status: ProposalComparisonStatus
  comparisonBasisId: string
  reason: string
  candidates: ProposalCandidateEvaluation[]
  nonDominatedCandidateIds: string[]
  factsVerified: boolean
  businessExecutionPerformed: boolean
  resourcesReserved: boolean
}

export function evaluateProposalComparison(
  factoryId: string,
  body: ProposalComparisonRequest,
): Promise<ApiResponse<ProposalComparisonResult>> {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(factoryId)) {
    throw new Error('工厂标识不合法，已阻止比较请求')
  }
  return post<ProposalComparisonResult>(`/${factoryId}/proposal-comparisons/evaluate`, body)
}
