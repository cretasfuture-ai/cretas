import { describe, expect, it, vi } from 'vitest'

const post = vi.hoisted(() => vi.fn())
vi.mock('../request', () => ({ post }))

import { evaluateProposalComparison, type ProposalComparisonRequest } from '../proposalComparison'

describe('proposal comparison API contract', () => {
  it('uses the current factory in the exact typed evaluate path and body', async () => {
    const body: ProposalComparisonRequest = {
      comparisonBasisId: 'basis-1', currency: 'CNY', costUnit: 'CNY_PER_BATCH',
      resourceUnit: 'PERSON_HOUR', windowStart: '2026-11-06T01:00:00.000Z',
      windowEnd: '2026-11-06T09:00:00.000Z', requiredBy: null, availableResource: null,
      candidates: [
        { id: 'A', comparisonBasisId: 'basis-1', currency: 'CNY', costUnit: 'CNY_PER_BATCH', resourceUnit: 'PERSON_HOUR', cost: 10, completionTime: null, requiredResource: null },
        { id: 'B', comparisonBasisId: 'basis-1', currency: 'CNY', costUnit: 'CNY_PER_BATCH', resourceUnit: 'PERSON_HOUR', cost: null, completionTime: null, requiredResource: null },
      ],
    }
    post.mockResolvedValue({ success: true, data: {} })
    await evaluateProposalComparison('F006', body)
    expect(post).toHaveBeenCalledWith('/F006/proposal-comparisons/evaluate', body)
  })

  it('rejects an unscoped factory path before issuing a request', () => {
    post.mockClear()
    expect(() => evaluateProposalComparison('F006/other', {} as ProposalComparisonRequest)).toThrow('工厂标识不合法')
    expect(post).not.toHaveBeenCalled()
  })
})
