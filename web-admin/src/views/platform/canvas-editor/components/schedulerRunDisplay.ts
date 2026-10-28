import type { SchedulerRun } from '@/types/canvas'

/** 一次定时运行在界面上怎么说: 未裁定的 UNKNOWN 就是「结果未知」, 永远不显示成成功。 */
export function schedulerRunStatusLabel(run: Pick<SchedulerRun, 'status' | 'resolution'>): string {
  if (run.resolution) return run.resolution === 'SUCCESS' ? '成功（人工裁定）' : '失败（人工裁定）'
  if (run.status === 'SUCCESS') return '成功'
  if (run.status === 'FAILED') return '失败'
  return '结果未知'
}

export function schedulerRunStatusType(
  status: string, _run?: Pick<SchedulerRun, 'status' | 'resolution'>,
): 'success' | 'danger' | 'warning' {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  return 'warning'
}

/**
 * 单次运行的 Token: provider 没给 usage 就写「未知」, 没调用 LLM 就写「无 LLM 调用」,
 * 绝不把没有读数显示成 0。
 */
export function schedulerRunTokenText(
  run: Pick<SchedulerRun, 'tokenUsageState' | 'totalTokens' | 'promptTokens' | 'completionTokens' | 'llmCallCount'>,
): string {
  if (run.tokenUsageState === 'NONE') return '无 LLM 调用'
  if (run.tokenUsageState === 'UNKNOWN' || run.totalTokens === null || run.totalTokens === undefined) {
    return `未知（${run.llmCallCount} 次调用中至少一次没有用量数据）`
  }
  return `${run.totalTokens}（输入 ${run.promptTokens ?? '—'} / 输出 ${run.completionTokens ?? '—'}，${run.llmCallCount} 次调用）`
}
