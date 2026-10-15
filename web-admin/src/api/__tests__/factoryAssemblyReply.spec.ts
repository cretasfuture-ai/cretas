import { describe, expect, it, vi } from 'vitest'
const request = vi.hoisted(() => ({ post: vi.fn() }))
vi.mock('@/api/request', () => ({ default: request }))
import { replyFactoryBuildConversation } from '../factoryOnboardingApi'

describe('isolated assembly conversation reply', () => {
  it('uses the platform conversation entry rather than the ordinary factory conversation store', () => {
    replyFactoryBuildConversation('F1', 'S1', '面包', 'reply-62')
    expect(request.post).toHaveBeenCalledWith(
      '/api/platform/factory-assembly/factories/F1/ai-initialize/converse/S1/reply',
      { userReply: '面包', requestId: 'reply-62' }, { baseURL: '' },
    )
  })
})
