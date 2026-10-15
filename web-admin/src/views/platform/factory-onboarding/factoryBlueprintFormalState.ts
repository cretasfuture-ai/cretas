import type { FormalCapability, FormalExecution } from '@/api/factoryBlueprintActivationApi'

export function formalRuntimeCounts(results: Partial<Record<FormalCapability, FormalExecution>>) {
  const targets = { modules: new Set<string>(), workflow: new Set<string>() }
  for (const result of Object.values(results)) {
    if (!result) continue
    for (const runtime of result.verifiedRuntime) {
      if (runtime.runtimeState !== 'ACTIVE' || !runtime.targetRuntimeId) continue
      if (runtime.capabilityId !== 'modules' && runtime.capabilityId !== 'workflow') continue
      if (!result.modules.some(module => module.capabilityId === runtime.capabilityId && module.status === 'ACTIVATED')) continue
      targets[runtime.capabilityId].add(runtime.targetRuntimeId)
    }
  }
  return { modules: targets.modules.size, workflow: targets.workflow.size }
}

export function formalAcceptanceLabel(state: string, results: Partial<Record<FormalCapability, FormalExecution>>) {
  const labels: Record<string, string> = { NOT_READY: '尚不可验收', PENDING: '等待验收', PARTIAL: '部分已验收',
    ACCEPTED: '全部已通过', REJECTED: '存在需调整项' }
  const counts = formalRuntimeCounts(results)
  if (state !== 'ACCEPTED' && state !== 'REJECTED' && counts.modules + counts.workflow > 0) {
    return `已有 ${counts.modules + counts.workflow} 项正式运行态回读，待人工核对`
  }
  return labels[state] ?? '验收状态未返回'
}

export function formalConfigurationLabel(state: string, results: Partial<Record<FormalCapability, FormalExecution>>) {
  if (formalRuntimeCounts(results).modules > 0) return '配置已应用（已回读）'
  return state === 'APPLIED' ? '配置已应用' : '配置未应用'
}

export function formalWorkflowLabel(state: string, results: Partial<Record<FormalCapability, FormalExecution>>) {
  const count = formalRuntimeCounts(results).workflow
  if (count > 0) return `Workflow 已发布（已回读 ${count} 条）`
  return state === 'NOT_PUBLISHED' ? 'Workflow 未发布' : `Workflow ${state}`
}
