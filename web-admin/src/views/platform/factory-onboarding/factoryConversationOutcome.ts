import type { ConversationResponse } from '@/api/factoryOnboardingApi'

export interface ConversationOutcomeIssue {
  title: string
  detail: string
  sideEffect: string
  retryLabel: string
  retrySameRequest: boolean
}

const TECHNICAL_GENERATION_FAILURE = /(?:RuntimeError|llm_router|All providers exhausted|\bprovider\b|\btimeout\b)/i

/**
 * 建厂生成跨过 Java/Python/provider 三层，原始错误可能带实现类、路由名和模型名。
 * 这些信息留给 network/log 证据；操作员只需要知道输入是否保留、现在能做什么。
 */
export function generationFailureDetail(rawMessage: string): string {
  const detail = rawMessage.replace(/^AI配置生成失败[：:]\s*/, '').trim()
  if (!detail || TECHNICAL_GENERATION_FAILURE.test(detail) || detail.startsWith('处理失败')) {
    return 'AI 服务暂时没有完成这次整理。你的全部回答已经保留，可以直接重试；如果连续失败，把最长的一段拆短后再试。'
  }
  return detail
}

export function conversationOutcomeIssue(
  response: ConversationResponse,
): ConversationOutcomeIssue | null {
  switch (response.extractionOutcome) {
    case undefined:
    case 'SUCCESS':
    case 'PARTIAL_SUCCESS':
      return null
    case 'PROVIDER_UNAVAILABLE':
      return {
        title: 'AI 提取服务暂时不可用，输入已经放回编辑框',
        detail: response.message,
        sideEffect: '本轮答案、轮次和进度均未更新，也没有生成或写入工厂配置。',
        retryLabel: '重试发送这句话',
        retrySameRequest: true,
      }
    case 'UNKNOWN':
      return {
        title: '这项信息仍保持未回答',
        detail: response.message,
        sideEffect: '系统没有猜测答案，轮次和进度均未更新。',
        retryLabel: '重新填写',
        retrySameRequest: false,
      }
    case 'INVALID_EXTRACTION':
      return {
        title: '这句话没有通过安全校验',
        detail: response.message,
        sideEffect: '无效字段没有落入访谈答案，轮次和进度均未更新。',
        retryLabel: '换一种说法',
        retrySameRequest: false,
      }
    case 'CONFLICT':
      return {
        title: '回答与当前会话状态有冲突',
        detail: response.message,
        sideEffect: '冲突内容没有覆盖旧答案，轮次和进度均未更新。',
        retryLabel: '刷新后重新填写',
        retrySameRequest: false,
      }
  }
}

export function createConversationRequestId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID()
  }
  return `reply-${Date.now()}-${Math.random().toString(16).slice(2)}`
}
