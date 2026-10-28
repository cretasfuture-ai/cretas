import { describe, expect, it } from 'vitest'
import {
  schedulerRunStatusLabel,
  schedulerRunStatusType,
  schedulerRunTokenText,
} from '../schedulerRunDisplay'

describe('schedulerRunDisplay', () => {
  it('未裁定的 UNKNOWN 显示「结果未知」, 不是成功', () => {
    expect(schedulerRunStatusLabel({ status: 'UNKNOWN', resolution: null })).toBe('结果未知')
    expect(schedulerRunStatusType('UNKNOWN')).toBe('warning')
    expect(schedulerRunStatusLabel({ status: 'UNKNOWN', resolution: null })).not.toContain('成功')
  })

  it('人工裁定后显示裁定值并注明是人工裁定', () => {
    expect(schedulerRunStatusLabel({ status: 'UNKNOWN', resolution: 'SUCCESS' })).toBe('成功（人工裁定）')
    expect(schedulerRunStatusLabel({ status: 'UNKNOWN', resolution: 'FAILED' })).toBe('失败（人工裁定）')
  })

  it('明确的成功 / 失败', () => {
    expect(schedulerRunStatusLabel({ status: 'SUCCESS', resolution: null })).toBe('成功')
    expect(schedulerRunStatusLabel({ status: 'FAILED', resolution: null })).toBe('失败')
    expect(schedulerRunStatusType('SUCCESS')).toBe('success')
    expect(schedulerRunStatusType('FAILED')).toBe('danger')
  })

  it('Token: 没有用量数据显示未知, 没调用 LLM 显示无调用, 都不显示 0', () => {
    const unknown = schedulerRunTokenText({
      tokenUsageState: 'UNKNOWN', totalTokens: null, promptTokens: null, completionTokens: null, llmCallCount: 2,
    })
    expect(unknown).toContain('未知')
    expect(unknown).not.toMatch(/^0/)
    expect(schedulerRunTokenText({
      tokenUsageState: 'NONE', totalTokens: null, promptTokens: null, completionTokens: null, llmCallCount: 0,
    })).toBe('无 LLM 调用')
    expect(schedulerRunTokenText({
      tokenUsageState: 'KNOWN', totalTokens: 135, promptTokens: 110, completionTokens: 25, llmCallCount: 2,
    })).toContain('135')
  })
})
