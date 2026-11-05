/**
 * 「前端模块裁剪」里能选的模块。必须与后端 InterviewModuleConfigCatalog 的 moduleId 逐字一致
 * （后端不认的 id 写进去只会 400）；Java 侧 InterviewModuleCatalogParityContractTest 钉住两份一致。
 */
export const INTERVIEW_MODULE_OPTIONS: ReadonlyArray<{ moduleId: string; label: string }> = [
  { moduleId: 'production', label: '生产' },
  { moduleId: 'warehouse', label: '仓储' },
  { moduleId: 'quality', label: '质检' },
  { moduleId: 'scheduling', label: '调度' },
  { moduleId: 'hr', label: '人事' },
  { moduleId: 'equipment', label: '设备' },
  { moduleId: 'finance', label: '财务' },
  { moduleId: 'smartbi', label: '智能BI' },
  { moduleId: 'procurement', label: '采购' },
  { moduleId: 'sales', label: '销售' },
  { moduleId: 'logistics', label: '物流' },
  { moduleId: 'trace', label: '溯源' },
  { moduleId: 'system', label: '系统' },
]

export function isKnownInterviewModule(moduleId: string | undefined): boolean {
  const key = (moduleId ?? '').trim().toLowerCase()
  return INTERVIEW_MODULE_OPTIONS.some((option) => option.moduleId === key)
}

export function interviewModuleLabel(moduleId: string | undefined): string {
  const key = (moduleId ?? '').trim().toLowerCase()
  return INTERVIEW_MODULE_OPTIONS.find((option) => option.moduleId === key)?.label ?? ''
}

/** 保存前在页面上就能发现的模块行问题（服务端对空 id 会回 400，页面上却看不见）。 */
export function moduleConfigProblems(rows: ReadonlyArray<{ moduleId?: string | null }> | null | undefined): string[] {
  return (rows ?? []).flatMap((row, index) => {
    if (!(row.moduleId ?? '').trim()) return [`前端模块裁剪第 ${index + 1} 行还没有选模块，请选一个模块或删除这一行`]
    if (!isKnownInterviewModule(row.moduleId ?? '')) return [`前端模块裁剪第 ${index + 1} 行的模块「${row.moduleId}」不可用，请从下拉里选`]
    return []
  })
}
