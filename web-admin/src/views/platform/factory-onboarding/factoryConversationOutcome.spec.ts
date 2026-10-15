import { describe, expect, it } from 'vitest'
import type { ConversationResponse } from '@/api/factoryOnboardingApi'
import {
  conversationOutcomeIssue,
  generationFailureDetail,
} from './factoryConversationOutcome'

function response(
  extractionOutcome: ConversationResponse['extractionOutcome'],
): ConversationResponse {
  return {
    sessionId: 'session-1', currentRound: 2, maxRounds: 10,
    status: 'ACTIVE', completed: false, message: 'typed result', extractionOutcome,
  }
}

describe('conversationOutcomeIssue', () => {
  it('accepts only SUCCESS as a normal conversation advance', () => {
    expect(conversationOutcomeIssue(response('SUCCESS'))).toBeNull()
    expect(conversationOutcomeIssue(response(undefined))).toBeNull()
  })

  it('keeps the same idempotency key only for retryable provider failure', () => {
    expect(conversationOutcomeIssue(response('PROVIDER_UNAVAILABLE'))?.retrySameRequest).toBe(true)
    expect(conversationOutcomeIssue(response('INVALID_EXTRACTION'))?.retrySameRequest).toBe(false)
    expect(conversationOutcomeIssue(response('UNKNOWN'))?.retrySameRequest).toBe(false)
    expect(conversationOutcomeIssue(response('CONFLICT'))?.retrySameRequest).toBe(false)
  })

  it('states zero progress mutation for every fail-closed outcome', () => {
    for (const outcome of ['PROVIDER_UNAVAILABLE', 'INVALID_EXTRACTION', 'UNKNOWN', 'CONFLICT'] as const) {
      expect(conversationOutcomeIssue(response(outcome))?.sideEffect).toContain('轮次和进度均未更新')
    }
  })

  it('keeps provider internals out of the operator-facing generation retry', () => {
    const detail = generationFailureDetail(
      'AI配置生成失败: 处理失败: RuntimeError: [llm_router] All providers exhausted for chat: aistore/model: timeout',
    )

    expect(detail).toContain('全部回答已经保留')
    expect(detail).toContain('直接重试')
    expect(detail).not.toMatch(/RuntimeError|llm_router|provider|aistore|timeout/i)
  })

  it('preserves an already actionable server message without its transport prefix', () => {
    expect(generationFailureDetail(
      'AI配置生成失败: 这次没能把配置整理出来（已经试了 1 次）。请再点一次「生成配置」。',
    )).toBe('这次没能把配置整理出来（已经试了 1 次）。请再点一次「生成配置」。')
  })
})
