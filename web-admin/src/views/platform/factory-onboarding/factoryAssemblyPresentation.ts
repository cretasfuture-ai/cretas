import type {
  FactoryBuildProgress,
  PendingItem,
  PlannedChange,
  SuggestedData,
} from '@/api/factoryOnboardingApi'

export interface ReconciliationIssue {
  id: string
  severity: 'blocking' | 'warning'
  title: string
  detail: string
  targetKey?: string
  targetLabel?: string
}

export interface CoverageBar {
  group: string
  count: number
  create: number
  overwrite: number
  skip: number
  percent: number
}

export interface WorkflowLane {
  productCode: string
  productName: string
  processNames: string[]
}

const text = (value: unknown) => String(value ?? '').trim()

function numberFrom(value: unknown): number | null {
  const match = text(value).match(/\d+/)
  return match ? Number(match[0]) : null
}

function isProductName(label: string): boolean {
  return /(成品|产品).*(名称|名字)/.test(label) && !/(个数|数量|规格)/.test(label)
}

function isProcessName(label: string): boolean {
  return /工序.*(名称|名字)/.test(label) && !/(道数|数量|类别)/.test(label)
}

function duplicateIssues(
  progress: FactoryBuildProgress,
  predicate: (label: string) => boolean,
  noun: string,
): ReconciliationIssue[] {
  const groups = new Map<string, Array<{ key: string; label: string; value: string }>>()
  for (const [key, value] of Object.entries(progress.collected ?? {})) {
    const label = progress.answeredLabels?.[key] ?? key
    if (!predicate(label)) continue
    const normalized = text(value).replace(/[\s，,、/]+/g, '').toLocaleLowerCase()
    if (!normalized) continue
    groups.set(normalized, [...(groups.get(normalized) ?? []), { key, label, value: text(value) }])
  }
  return [...groups.entries()].flatMap(([normalized, rows]) => {
    if (rows.length < 2) return []
    const target = rows[rows.length - 1]
    return [{
      id: `duplicate-${noun}-${normalized}`,
      severity: 'blocking' as const,
      title: `${noun}名称重复，我先不让它直接写`,
      detail: `${rows.map((row) => row.label).join('、')} 都是“${target.value}”。如果确实是同一个，请减少数量；如果不是，请把其中一个名称改准确。`,
      targetKey: target.key,
      targetLabel: target.label,
    }]
  })
}

function countIssue(
  progress: FactoryBuildProgress,
  countPattern: RegExp,
  namePredicate: (label: string) => boolean,
  noun: string,
): ReconciliationIssue[] {
  const countEntry = Object.entries(progress.collected ?? {}).find(([key]) =>
    countPattern.test(progress.answeredLabels?.[key] ?? key))
  if (!countEntry) return []
  const expected = numberFrom(countEntry[1])
  if (expected === null) return []
  const names = Object.entries(progress.collected ?? {}).filter(([key, value]) =>
    namePredicate(progress.answeredLabels?.[key] ?? key) && text(value).length > 0)
  if (names.length === 0 || names.length === expected) return []
  return [{
    id: `count-${noun}`,
    severity: 'blocking',
    title: `${noun}数量对不上，我先拦一下`,
    detail: `前面说的是 ${expected} 个，目前明确收到 ${names.length} 个名称。请修正数量，或者把缺少的名称补全。`,
    targetKey: countEntry[0],
    targetLabel: progress.answeredLabels?.[countEntry[0]] ?? countEntry[0],
  }]
}

export function deriveReconciliationIssues(progress: FactoryBuildProgress | null): ReconciliationIssue[] {
  if (!progress) return []
  const skipped = (progress.skippedItems ?? []).map((item) => ({
    id: `skipped-${item.name}`,
    severity: 'warning' as const,
    title: `${item.label}还没有拿到确切答案`,
    detail: `${item.why || '已经追问过，仍无法确认'}。可以现在补一句；不补也会在预览和复核里继续标出来。`,
    targetKey: item.name,
    targetLabel: item.label,
  }))
  return [
    ...duplicateIssues(progress, isProductName, '产品'),
    ...duplicateIssues(progress, isProcessName, '工序'),
    ...countIssue(progress, /(成品|产品).*(个数|数量)/, isProductName, '产品'),
    ...countIssue(progress, /工序.*(道数|数量)/, isProcessName, '工序'),
    ...skipped,
  ]
}

export function questionnaireItems(progress: FactoryBuildProgress | null): PendingItem[] {
  if (!progress) return []
  return (progress.pendingItems ?? [])
    .filter((item) => (item.options?.length ?? 0) > 0 && !item.repeatIndex)
    .slice(0, 6)
}

export function coverageBars(changes: PlannedChange[]): CoverageBar[] {
  const grouped = new Map<string, PlannedChange[]>()
  for (const change of changes) {
    grouped.set(change.group, [...(grouped.get(change.group) ?? []), change])
  }
  const max = Math.max(1, ...[...grouped.values()].map((rows) => rows.length))
  return [...grouped.entries()]
    .map(([group, rows]) => ({
      group,
      count: rows.length,
      create: rows.filter((row) => row.action === '新建').length,
      overwrite: rows.filter((row) => row.action === '覆盖').length,
      skip: rows.filter((row) => row.action === '跳过').length,
      percent: Math.max(8, Math.round((rows.length / max) * 100)),
    }))
    .sort((left, right) => right.count - left.count || left.group.localeCompare(right.group, 'zh-CN'))
}

export function workflowLanes(data: SuggestedData | undefined): WorkflowLane[] {
  if (!data) return []
  const names = new Map((data.productTypes ?? []).map((item) => [item.code, item.name]))
  return (data.productProcesses ?? []).map((item) => ({
    productCode: item.productCode,
    productName: names.get(item.productCode) ?? item.productCode,
    processNames: [...(item.processNames ?? [])],
  }))
}
