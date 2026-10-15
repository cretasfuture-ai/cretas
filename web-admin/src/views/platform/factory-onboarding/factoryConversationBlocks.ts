import type {
  ConversationHistoryMessage,
  ConversationResponse,
  FactoryBuildProgress,
  PendingItem,
} from '@/api/factoryOnboardingApi'

export interface ProcessSkeletonRow {
  sequence: number
  name: string
  inputMaterial: string
  output: string
}

export interface ProductFlowRow {
  sequence: number
  name: string
  steps: string
  routeShape: string[]
  routeDetails: string
}

export type RouteTopologyEdgeType = 'FORWARD' | 'BRANCH' | 'MERGE' | 'REWORK'

export interface RouteTopologyRow {
  id: string
  from: string
  to: string
  type: RouteTopologyEdgeType
  condition: string
  reworkExit: string
}

export interface RouteTopologyDraft {
  productIndex: number
  productName: string
  routeShapes: string[]
  routeDetails: string
  nodeLabels: string[]
  entry: string
  terminal: string
  rows: RouteTopologyRow[]
}

export interface ConversationChoiceReceipt {
  kind: 'choice-card' | 'field-card'
  title: string
  entries: Array<{
    label: string
    value: string
    state?: 'accepted' | 'rejected' | 'unknown' | 'conflict'
  }>
}

export interface ConversationProcessReceipt {
  kind: 'process-table'
  title: string
  rows: ProcessSkeletonRow[]
}

export interface ConversationProductReceipt {
  kind: 'product-table'
  title: string
  rows: ProductFlowRow[]
  packaging?: string
}

export type ConversationReceipt = ConversationChoiceReceipt | ConversationProcessReceipt | ConversationProductReceipt

export interface ProcessDetailGroup {
  sequence: number
  processName: string
  items: PendingItem[]
}

export type FactoryDomainCardKind = 'access' | 'quality'

export interface FactoryDomainGroup {
  kind: FactoryDomainCardKind
  kicker: string
  title: string
  description: string
  caution: string
  submitLabel: string
  items: PendingItem[]
}

const PROCESS_CORE_FIELDS = new Set(['name', 'inputMaterial', 'output'])
const SPECIALIZED_DOMAIN_FIELDS = new Set(['moduleOptOut', 'createAccounts', 'haccpPoints', 'haccpLimits'])

function normalizedChoice(value: string): string {
  return value.trim()
    .replaceAll('（', '(')
    .replaceAll('）', ')')
    .replace(/\s+/g, '')
    .toLowerCase()
}

function suggestedValue(progress: FactoryBuildProgress, name: string): string {
  if (Object.prototype.hasOwnProperty.call(progress.collected ?? {}, name)) {
    return progress.collected?.[name]?.trim() ?? ''
  }
  return progress.suggestions?.[name]?.trim() ?? ''
}

const DOMAIN_GROUPS: Array<Omit<FactoryDomainGroup, 'items'> & { names: Set<string> }> = [
  {
    kind: 'access',
    kicker: 'Agent 系统范围确认',
    title: '哪些功能要开放，是否先建初始账号',
    description: '先用业务名称做减法，不需要理解模块编码。关闭功能的真实影响会在最终差异预览里再次逐项确认。',
    caution: '这张卡只记录意图，不会立即隐藏页面、拦截接口或创建账号。',
    submitLabel: '把系统范围交给 Agent',
    names: new Set(['moduleOptOut', 'createAccounts']),
  },
  {
    kind: 'quality',
    kicker: 'Agent 食品安全确认',
    title: '关键控制点与临界限值',
    description: '可以选择多个控制点，再用一两句话写清每个点的限值；不知道的明确选“没有”，系统不会代编。',
    caution: '没有明确限值的控制点不会进入最终写入计划。',
    submitLabel: '把食品安全规则交给 Agent',
    names: new Set(['haccpPoints', 'haccpLimits']),
  },
]

function repeatField(name: string): string | null {
  return name.match(/^step\d+\.(.+)$/)?.[1] ?? null
}

export function isSpecializedDomainField(name: string): boolean {
  return SPECIALIZED_DOMAIN_FIELDS.has(name)
}

/**
 * Turn server-owned case suggestions into editable form values.
 *
 * Suggestions are never submitted here. Choice suggestions must still match the
 * server-provided options exactly after harmless punctuation/space normalization;
 * an unverified suggestion stays blank instead of becoming a client-side default.
 */
export function suggestedAnswersForItems(
  items: PendingItem[],
  suggestions: Record<string, string> = {},
): Record<string, string | string[]> {
  const answers: Record<string, string | string[]> = {}
  for (const item of items) {
    const suggestion = suggestions[item.name]?.trim()
    if (!suggestion) continue
    const options = item.options ?? []
    if (options.length === 0) {
      answers[item.name] = suggestion
      continue
    }
    if (!item.multiSelect) {
      const match = options.find(option => normalizedChoice(option) === normalizedChoice(suggestion))
      if (match) answers[item.name] = match
      continue
    }
    const selected = suggestion.split(/[、,，;；]/)
      .map(token => token.trim())
      .filter(Boolean)
      .map(token => options.find(option => normalizedChoice(option) === normalizedChoice(token)))
    if (selected.length > 0 && selected.every((option): option is string => Boolean(option))) {
      answers[item.name] = [...new Set(selected)]
    }
  }
  return answers
}

/** Preserve user edits (including an intentional clear) while seeding untouched fields. */
export function mergeSuggestedAnswers(
  current: Record<string, string | string[]>,
  suggested: Record<string, string | string[]>,
): Record<string, string | string[]> {
  const merged = { ...current }
  for (const [name, value] of Object.entries(suggested)) {
    if (!Object.prototype.hasOwnProperty.call(merged, name)) merged[name] = value
  }
  return merged
}

export function suggestedAnswerMatches(
  item: PendingItem,
  value: string | string[] | undefined,
  suggestions: Record<string, string> = {},
): boolean {
  const expected = suggestedAnswersForItems([item], suggestions)[item.name]
  if (expected === undefined || value === undefined) return false
  if (Array.isArray(expected)) {
    return Array.isArray(value)
      && value.length === expected.length
      && expected.every(option => value.includes(option))
  }
  return !Array.isArray(value) && value.trim() === expected
}

export function activeFactoryDomainGroup(
  progress: FactoryBuildProgress | null,
  enabled: boolean,
): FactoryDomainGroup | null {
  if (!progress || !enabled) return null
  const pendingItems = progress.pendingItems ?? []
  for (const definition of DOMAIN_GROUPS) {
    const items = pendingItems.filter((item) => definition.names.has(item.name))
    if (items.length > 0) {
      const { names: _names, ...group } = definition
      return { ...group, items }
    }
  }
  return null
}

export function activeProcessDetailGroup(
  progress: FactoryBuildProgress | null,
  enabled: boolean,
): ProcessDetailGroup | null {
  if (!progress || !enabled) return null
  const pendingItems = progress.pendingItems ?? []
  const hasPendingCore = pendingItems.some((item) => {
    const field = repeatField(item.name)
    return !!item.repeatIndex && !!field && PROCESS_CORE_FIELDS.has(field)
  })
  if (hasPendingCore) return null
  const detailItems = pendingItems.filter((item) => {
    const field = repeatField(item.name)
    return !!item.repeatIndex && !!field && !PROCESS_CORE_FIELDS.has(field)
  })
  const sequence = detailItems[0]?.repeatIndex
  if (!sequence) return null
  return {
    sequence,
    processName: progress.collected?.[`step${sequence}.name`] || `第 ${sequence} 道工序`,
    items: detailItems.filter((item) => item.repeatIndex === sequence),
  }
}

export interface FactoryConversationMessage {
  role: 'ai' | 'me'
  text?: string
  receipt?: ConversationReceipt
}

export function restoreConversationMessages(
  history: ConversationHistoryMessage[],
  items: PendingItem[] = [],
  answeredLabels: Record<string, string> = {},
): FactoryConversationMessage[] {
  return history.flatMap<FactoryConversationMessage>((message) => {
    const restored: FactoryConversationMessage[] = [{
      role: message.role === 'user' ? 'me' : 'ai',
      text: message.content,
    }]
    const entries = extractionReceiptEntries(
      items,
      answeredLabels,
      message.fieldReceipts ?? [],
    )
    if (entries.length > 0) {
      restored.push({
        role: 'ai',
        receipt: {
          kind: 'field-card',
          title: message.correctionApplied
            ? '已恢复这轮更正后的字段结果'
            : message.extractionOutcome === 'PARTIAL_SUCCESS'
              ? '已恢复逐字段处理结果'
              : '已恢复字段处理结果',
          entries,
        },
      })
    }
    return restored
  })
}

export function extractionReceiptEntries(
  items: PendingItem[],
  answeredLabels: Record<string, string>,
  receipts: NonNullable<ConversationResponse['fieldReceipts']>,
): ConversationChoiceReceipt['entries'] {
  const labels = new Map<string, string>(Object.entries(answeredLabels))
  items.forEach(item => labels.set(item.name, item.label))
  const copy = {
    ACCEPTED: { state: 'accepted' as const, fallback: '已保存' },
    REJECTED: { state: 'rejected' as const, fallback: '未保存，需要重新确认' },
    UNKNOWN: { state: 'unknown' as const, fallback: '保持未回答' },
    CONFLICT: { state: 'conflict' as const, fallback: '未保存，存在冲突' },
  }
  return receipts.map((receipt) => {
    const presentation = copy[receipt.resolution]
    const cleared = receipt.reasonCode === 'CLEARED'
    return {
      label: labels.get(receipt.field) ?? '无法识别的输入字段',
      value: receipt.resolution === 'ACCEPTED'
        ? (cleared ? '已清空，等待重新回答' : receipt.canonicalValue ?? presentation.fallback)
        : presentation.fallback,
      state: presentation.state,
    }
  })
}

function positiveInteger(value: unknown): number {
  const parsed = Number.parseInt(String(value ?? ''), 10)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0
}

export function buildProcessSkeletonRows(progress: FactoryBuildProgress | null): ProcessSkeletonRow[] {
  if (!progress) return []
  const stepCount = Math.min(30, positiveInteger(progress.collected?.stepCount))
  return Array.from({ length: stepCount }, (_, index) => {
    const sequence = index + 1
    return {
      sequence,
      name: suggestedValue(progress, `step${sequence}.name`),
      inputMaterial: suggestedValue(progress, `step${sequence}.inputMaterial`),
      output: suggestedValue(progress, `step${sequence}.output`),
    }
  })
}

export function buildProductFlowRows(progress: FactoryBuildProgress | null): ProductFlowRow[] {
  if (!progress) return []
  const productCount = Math.min(20, positiveInteger(progress.collected?.productCount))
  return Array.from({ length: productCount }, (_, index) => {
    const sequence = index + 1
    return {
      sequence,
      name: suggestedValue(progress, `product${sequence}.name`),
      steps: suggestedValue(progress, `product${sequence}.steps`),
      routeShape: suggestedValue(progress, `product${sequence}.routeShape`)
        .split(/[、,，;；]/).map(value => value.trim()).filter(Boolean),
      routeDetails: suggestedValue(progress, `product${sequence}.routeDetails`),
    }
  })
}

/** 「仅顺序」是排他事实；最后一次选择决定保留顺序或复杂形态。 */
export function normalizeProductRouteShape(values: string[]): string[] {
  const normalized = values.map(value => value.trim()).filter(Boolean)
  if (!normalized.includes('仅顺序') || normalized.length === 1) return normalized
  return normalized[normalized.length - 1] === '仅顺序'
    ? ['仅顺序']
    : normalized.filter(value => value !== '仅顺序')
}

export function productFlowAnswers(
  rows: ProductFlowRow[],
  packaging: string,
): Record<string, string | string[]> {
  const answers: Record<string, string | string[]> = {}
  const packagingValue = packaging.trim()
  if (packagingValue) answers.productSkus = packagingValue
  for (const row of rows) {
    const name = row.name.trim()
    const steps = row.steps.trim()
    const routeShape = row.routeShape.map(value => value.trim()).filter(Boolean)
    const routeDetails = row.routeDetails.trim()
    if (name) answers[`product${row.sequence}.name`] = name
    if (steps) answers[`product${row.sequence}.steps`] = steps
    if (routeShape.length) answers[`product${row.sequence}.routeShape`] = routeShape
    if (routeDetails) answers[`product${row.sequence}.routeDetails`] = routeDetails
    if (routeShape.length === 1 && routeShape[0] === '仅顺序') {
      const nodes = steps.split(/\s*(?:→|＞|->|>)\s*/).map(value => value.trim()).filter(Boolean)
      if (nodes.length > 0) {
        answers[`product${row.sequence}.routeEntry`] = nodes[0]
        answers[`product${row.sequence}.routeTerminal`] = nodes[nodes.length - 1]
        answers[`product${row.sequence}.routeTopology`] = '按顺序自动确定'
      }
    }
  }
  return answers
}

export function productFlowSignature(rows: ProductFlowRow[], packaging: string): string {
  return JSON.stringify({
    packaging: packaging.trim(),
    rows: rows.map((row) => ({
      sequence: row.sequence,
      name: row.name.trim(),
      steps: row.steps.trim(),
      routeShape: row.routeShape.map(value => value.trim()).filter(Boolean).sort(),
      routeDetails: row.routeDetails.trim(),
    })),
  })
}

export function productConfigurationPending(progress: FactoryBuildProgress | null): boolean {
  return !!progress?.pendingItems?.some((item) => {
    if (item.name === 'productSkus'
      || /^product\d+\.(name|steps|routeShape|routeDetails)$/.test(item.name)) return true
    const product = item.name.match(/^product(\d+)\.(routeEntry|routeTerminal|routeTopology)$/)?.[1]
    if (!product) return false
    // A legacy sequential session needs one explicit re-confirmation so the three new fields are
    // saved; complex routes deliberately leave them to the dedicated topology card.
    return progress.collected?.[`product${product}.routeShape`]?.trim() === '仅顺序'
  })
}

/**
 * 服务端已经持久化完整成品表时，刷新后恢复「已确认」状态。
 * 只看服务端 collected/pending，不读取浏览器本地标记。
 */
export function productFlowPersisted(progress: FactoryBuildProgress | null): boolean {
  if (!progress || productConfigurationPending(progress)) return false
  const productCount = Math.min(20, positiveInteger(progress.collected?.productCount))
  if (productCount === 0 || !progress.collected?.productSkus?.trim()) return false
  return Array.from({ length: productCount }, (_, index) => index + 1).every((sequence) =>
    ['name', 'steps', 'routeShape', 'routeDetails'].every((field) => {
      const name = `product${sequence}.${field}`
      return Object.prototype.hasOwnProperty.call(progress.collected, name)
        && Boolean(progress.collected?.[name]?.trim())
    }))
}

export function productPackagingItem(progress: FactoryBuildProgress | null): PendingItem | null {
  return progress?.pendingItems?.find((item) => item.name === 'productSkus') ?? null
}

function topologyRows(raw: string): RouteTopologyRow[] {
  return raw.split(/\r?\n/).map((line, index) => {
    const [from = '', to = '', rawType = 'FORWARD', condition = '', reworkExit = ''] = line.split('\t')
    const type: RouteTopologyEdgeType = ['FORWARD', 'BRANCH', 'MERGE', 'REWORK'].includes(rawType)
      ? rawType as RouteTopologyEdgeType
      : 'FORWARD'
    return { id: `saved-${index + 1}`, from, to, type, condition, reworkExit }
  }).filter(row => row.from || row.to)
}

/** Build the next complex-route card from server-owned pending fields and preview nodes. */
export function buildRouteTopologyDraft(
  progress: FactoryBuildProgress | null,
  current: RouteTopologyDraft | null = null,
): RouteTopologyDraft | null {
  if (!progress) return null
  const pending = progress.pendingItems?.find(item => /^product\d+\.routeTopology$/.test(item.name))
  const productIndex = Number(pending?.name.match(/^product(\d+)\./)?.[1] ?? 0)
  if (!productIndex) return null
  const preview = progress.routePreviews?.find(item => item.productIndex === productIndex)
  if (!preview || preview.routeShapes.length === 1 && preview.routeShapes[0] === '仅顺序') return null
  if (current?.productIndex === productIndex) return current
  const nodeLabels = preview.graph.nodes.map(node => node.label)
  const labels = new Map(preview.graph.nodes.map(node => [node.id, node.label]))
  const saved = progress.collected?.[`product${productIndex}.routeTopology`] ?? ''
  const rows = saved.trim() && saved !== '按顺序自动确定'
    ? topologyRows(saved)
    : preview.graph.edges.map((edge, index) => ({
      id: `skeleton-${index + 1}`,
      from: labels.get(edge.fromNodeId) ?? '',
      to: labels.get(edge.toNodeId) ?? '',
      type: 'FORWARD' as const,
      condition: '',
      reworkExit: '',
    }))
  return {
    productIndex,
    productName: preview.productName || `第 ${productIndex} 个成品`,
    routeShapes: [...preview.routeShapes],
    routeDetails: preview.routeDetails,
    nodeLabels,
    entry: progress.collected?.[`product${productIndex}.routeEntry`] ?? nodeLabels[0] ?? '',
    terminal: progress.collected?.[`product${productIndex}.routeTerminal`] ?? nodeLabels[nodeLabels.length - 1] ?? '',
    rows,
  }
}

function topologyCell(value: string): string {
  return value.trim().replace(/[\t\r\n]+/g, ' ')
}

export function routeTopologyAnswers(draft: RouteTopologyDraft): Record<string, string> {
  const prefix = `product${draft.productIndex}.`
  return {
    [`${prefix}routeEntry`]: topologyCell(draft.entry),
    [`${prefix}routeTerminal`]: topologyCell(draft.terminal),
    [`${prefix}routeTopology`]: draft.rows.map(row => [
      topologyCell(row.from),
      topologyCell(row.to),
      row.type,
      topologyCell(row.condition),
      topologyCell(row.reworkExit),
    ].join('\t')).join('\n'),
  }
}

export function processSkeletonAnswers(rows: ProcessSkeletonRow[]): Record<string, string> {
  const answers: Record<string, string> = {}
  for (const row of rows) {
    const fields = {
      name: row.name.trim(),
      inputMaterial: row.inputMaterial.trim(),
      output: row.output.trim(),
    }
    for (const [field, value] of Object.entries(fields)) {
      if (value) answers[`step${row.sequence}.${field}`] = value
    }
  }
  return answers
}

export function processSkeletonSignature(rows: ProcessSkeletonRow[]): string {
  return JSON.stringify(rows.map((row) => ({
    sequence: row.sequence,
    name: row.name.trim(),
    inputMaterial: row.inputMaterial.trim(),
    output: row.output.trim(),
  })))
}

/**
 * 服务端已经持久化完整流程骨架时，刷新后恢复「已确认」状态。
 * 半张表不能被误标为确认完成。
 */
export function processSkeletonPersisted(progress: FactoryBuildProgress | null): boolean {
  if (!progress) return false
  const stepCount = Math.min(30, positiveInteger(progress.collected?.stepCount))
  if (stepCount === 0) return false
  return Array.from({ length: stepCount }, (_, index) => index + 1).every((sequence) =>
    ['name', 'inputMaterial', 'output'].every((field) => {
      const name = `step${sequence}.${field}`
      return Object.prototype.hasOwnProperty.call(progress.collected, name)
        && Boolean(progress.collected?.[name]?.trim())
    }))
}

export function choiceReceiptEntries(
  items: PendingItem[],
  answers: Record<string, string | string[]>,
): Array<{ label: string; value: string }> {
  const labels = new Map(items.map((item) => [item.name, item.label]))
  return Object.entries(answers).map(([name, value]) => ({
    label: labels.get(name) ?? name,
    value: Array.isArray(value) ? value.join('、') : value,
  }))
}
