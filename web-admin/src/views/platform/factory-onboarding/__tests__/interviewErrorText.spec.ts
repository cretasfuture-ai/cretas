import { describe, expect, it } from 'vitest'
import { interviewErrorText } from '../interviewErrorText'

describe('interviewErrorText', () => {
  it('turns the foreign-session error into a way forward', () => {
    const text = interviewErrorText('会话不存在或不属于当前用户: abc-123')
    expect(text).toContain('另一位操作人发起')
    expect(text).toContain('开始新的访谈版本')
    expect(text).not.toContain('abc-123')
  })
  it('leaves every other message alone', () => {
    expect(interviewErrorText('请求没有完成')).toBe('请求没有完成')
  })
})
