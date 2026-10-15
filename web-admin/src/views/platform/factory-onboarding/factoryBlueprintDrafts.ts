/**
 * 建厂草稿面板的纯函数: 按路径写回请求、待填项类型与校验、行状态推导、固定幂等键、谁能确认、
 * 错误定位到字段。
 *
 * 不碰组件, 全部可单测。组件只负责把这些结果画出来。
 */
import type {
  DraftEvidence,
  DraftInputType,
  DraftOption,
  DraftProposal,
  DraftProposalItem,
  DraftRequiredInput,
  DraftStructureKind,
  DraftWriteOrderRow,
  WrittenCapability,
} from '@/api/factoryBlueprintDraftApi'

// ==================== 路径 ====================

/** 与后端 BlueprintDraftProposalController.SEGMENT 同一个语法: `name` 或 `name[index]` */
const SEGMENT = /^([A-Za-z][A-Za-z0-9]*)(?:\[(\d+)])?$/

export interface PathSegment {
  key: string
  index: number | null
}

export function parseDraftPath(path: string): PathSegment[] {
  if (typeof path !== 'string' || path.length === 0) {
    throw new Error(`待填字段路径为空`)
  }
  const segments = path.split('.').map((raw) => {
    const m = SEGMENT.exec(raw)
    if (!m) throw new Error(`待填字段路径不合法：${path}`)
    return { key: m[1], index: m[2] === undefined ? null : Number(m[2]) }
  })
  // 后端同样拒绝「最后一段带下标」: 待填的是某个字段, 不是数组里的某一格
  if (segments[segments.length - 1].index !== null) {
    throw new Error(`待填字段路径不合法（末段不能带下标）：${path}`)
  }
  return segments
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * 找到 path 最后一段所在的对象。任何一层不存在都抛错 —— ⛔ 不静默新建:
 * 后端拒绝未知字段, 而前端「顺手建出来」的字段正是未知字段。
 */
function locateParent(root: Record<string, unknown>, path: string): { parent: Record<string, unknown>; key: string } {
  const segments = parseDraftPath(path)
  let node: unknown = root
  for (let i = 0; i < segments.length - 1; i++) {
    const { key, index } = segments[i]
    if (!isPlainObject(node) || !Object.prototype.hasOwnProperty.call(node, key)) {
      throw new Error(`待填字段 ${path} 在请求里不存在（缺 ${key}）`)
    }
    node = node[key]
    if (index !== null) {
      if (!Array.isArray(node) || index >= node.length) {
        throw new Error(`待填字段 ${path} 在请求里不存在（${key}[${index}] 越界）`)
      }
      node = node[index]
    }
  }
  const last = segments[segments.length - 1].key
  if (!isPlainObject(node) || !Object.prototype.hasOwnProperty.call(node, last)) {
    throw new Error(`待填字段 ${path} 在请求里不存在（缺 ${last}）`)
  }
  return { parent: node, key: last }
}

export function readAtDraftPath(request: Record<string, unknown>, path: string): unknown {
  const { parent, key } = locateParent(request, path)
  return parent[key]
}

/** 返回写好的新请求(深拷贝), 不改入参。路径不存在抛错。 */
export function writeAtDraftPath(
  request: Record<string, unknown>,
  path: string,
  value: unknown
): Record<string, unknown> {
  const copy = JSON.parse(JSON.stringify(request)) as Record<string, unknown>
  const { parent, key } = locateParent(copy, path)
  parent[key] = value
  return copy
}

// ==================== 待填项类型(只看后端给的 inputType, 不再解析 constraint 措辞) ====================

export const DRAFT_INPUT_TYPES: readonly DraftInputType[] = [
  'TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'ENUM', 'MULTI_ENUM', 'DATE', 'STRUCTURE',
]

export interface DraftInputSpec {
  type: DraftInputType
  options: DraftOption[]
  /** true = 用户可以明确留空(发 null) */
  nullable: boolean
  min: number | null
  max: number | null
  maxLength: number | null
  /** 只对 STRUCTURE: LIST = 若干行同形对象, OBJECT = 一个对象 */
  structureKind: DraftStructureKind | null
  /** 只对 STRUCTURE: 子字段(相对路径, 单段字段名), 已逐个校验过规格 */
  itemFields: DraftRequiredInput[]
  /** 只对 LIST */
  minItems: number | null
  maxItems: number | null
  itemLabel: string | null
}

// 两侧都声明 spec / error: 本仓 strictNullChecks 关闭, 布尔判别字段不收窄联合类型
export type DraftInputSpecResult =
  | { ok: true; spec: DraftInputSpec; error?: undefined }
  | { ok: false; spec?: undefined; error: string }

function finiteOrNull(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

/**
 * 后端待填项 → 输入规格。缺 inputType / 不认识的类型 / 选择题没有选项都是**契约错误**,
 * 原样报出来 —— ⛔ 不退化成文本框(那等于让用户往枚举字段里手打一个必被拒绝的值)。
 */
export function draftInputSpec(input: DraftRequiredInput): DraftInputSpecResult {
  const type = input.inputType
  if (!type) {
    return { ok: false, error: `待填项「${input.label}」（${input.path}）缺少 inputType，后端版本与页面不一致` }
  }
  if (!DRAFT_INPUT_TYPES.includes(type)) {
    return { ok: false, error: `待填项「${input.label}」（${input.path}）的 inputType「${type}」页面不认识` }
  }
  const options = Array.isArray(input.options) ? input.options : []
  if ((type === 'ENUM' || type === 'MULTI_ENUM') && options.length === 0) {
    return { ok: false, error: `待填项「${input.label}」（${input.path}）是选择题却没有给选项` }
  }
  let structureKind: DraftStructureKind | null = null
  let itemFields: DraftRequiredInput[] = []
  let minItems: number | null = null
  let maxItems: number | null = null
  let itemLabel: string | null = null
  if (type === 'STRUCTURE') {
    const where = `待填项「${input.label}」（${input.path}）`
    if (input.structureKind !== 'LIST' && input.structureKind !== 'OBJECT' && input.structureKind !== 'VALUES') {
      return { ok: false, error: `${where}是结构化输入却没有 structureKind，后端版本与页面不一致` }
    }
    structureKind = input.structureKind
    itemFields = Array.isArray(input.itemFields) ? input.itemFields : []
    if (itemFields.length === 0) {
      return { ok: false, error: `${where}是结构化输入却没有子字段定义 itemFields，后端版本与页面不一致` }
    }
    const seen = new Set<string>()
    for (const field of itemFields) {
      if (typeof field?.path !== 'string' || !RELATIVE_FIELD.test(field.path)) {
        return { ok: false, error: `${where}的子字段路径「${field?.path}」不合法（只能是一段字段名）` }
      }
      if (seen.has(field.path)) return { ok: false, error: `${where}的子字段「${field.path}」重复` }
      seen.add(field.path)
      const sub = draftInputSpec(field)
      if (!sub.ok) return { ok: false, error: `${where}的子字段有问题：${sub.error}` }
    }
    if (structureKind === 'VALUES') {
      // VALUES = 一组单值: itemFields 恰好一条, 描述「一个值」, 它的 path 就是这个列表自己的字段名(不是包装键)
      const own = lastFieldName(input.path)
      if (itemFields.length !== 1) {
        return { ok: false, error: `${where}是值列表，子字段应恰好 1 条，实际 ${itemFields.length} 条` }
      }
      if (itemFields[0].path !== own) {
        return { ok: false, error: `${where}是值列表，子字段路径应为「${own}」，实际「${itemFields[0].path}」` }
      }
      if (itemFields[0].inputType === 'STRUCTURE') {
        return { ok: false, error: `${where}是值列表，单个值不能再是结构` }
      }
    }
    if (structureKind === 'LIST' || structureKind === 'VALUES') {
      minItems = finiteOrNull(input.minItems)
      maxItems = finiteOrNull(input.maxItems)
      itemLabel = typeof input.itemLabel === 'string' && input.itemLabel.trim() ? input.itemLabel.trim() : null
      if (minItems !== null && maxItems !== null && minItems > maxItems) {
        return { ok: false, error: `${where}的行数下限 ${minItems} 大于上限 ${maxItems}` }
      }
    }
  }
  return {
    ok: true,
    spec: {
      type,
      options,
      nullable: input.nullable === true,
      min: finiteOrNull(input.min),
      max: finiteOrNull(input.max),
      maxLength: finiteOrNull(input.maxLength),
      structureKind,
      itemFields,
      minItems,
      maxItems,
      itemLabel,
    },
  }
}

/** STRUCTURE 子字段的相对路径: 只允许一段字段名(与后端契约一致) */
const RELATIVE_FIELD = /^[A-Za-z][A-Za-z0-9]*$/

/** 路径最后一段的字段名(去掉下标): forms[0].fields[1].optionCodes → optionCodes */
function lastFieldName(path: string): string {
  const last = String(path ?? '').split('.').pop() ?? ''
  return last.replace(/\[\d+\]$/, '')
}

/** LIST / VALUES 都是「若干行」, 共用加行 / 删行 / 行数边界 */
function isRowsKind(kind: unknown): kind is 'LIST' | 'VALUES' {
  return kind === 'LIST' || kind === 'VALUES'
}

/** 已校验过的规格(STRUCTURE 递归校验过子字段, 这里拿不到就是页面缺陷) */
function requireSpec(input: DraftRequiredInput): DraftInputSpec {
  const r = draftInputSpec(input)
  if (!r.ok) throw new Error(r.error)
  return r.spec
}

/** 输入框旁先显示的边界(范围 / 可选个数 / 字数 / 行数) */
export function draftBoundText(spec: DraftInputSpec): string {
  const { min, max } = spec
  // 原样的 JS 数字会把 0.00000001 / 1e12 写成「1e-8」「1e+12」, 给人看要写成平常的小数
  const plain = (n: number) => n.toLocaleString('en-US', { useGrouping: false, maximumFractionDigits: 12 })
  const range = (lo: number | null, hi: number | null, unit: string) => {
    if (lo !== null && hi !== null) return `${plain(lo)}–${plain(hi)}${unit}`
    if (lo !== null) return `不小于 ${plain(lo)}${unit}`
    if (hi !== null) return `不大于 ${plain(hi)}${unit}`
    return ''
  }
  if (spec.type === 'INTEGER' || spec.type === 'DECIMAL') {
    const r = range(min, max, '')
    return r ? `范围：${r}${spec.type === 'INTEGER' ? '（整数）' : ''}` : (spec.type === 'INTEGER' ? '整数' : '')
  }
  if (spec.type === 'MULTI_ENUM') {
    const r = range(min, max, ' 项')
    return r ? `可选：${r}` : ''
  }
  if (spec.type === 'TEXT' && spec.maxLength !== null) return `最多 ${spec.maxLength} 字`
  // STRUCTURE 的行数边界由结构编辑器在列表顶部显示一次(draftRowBoundText), 这里不再重复
  return ''
}

/** 「至少 X 行，至多 Y 行」—— 加行 / 删行之前就让人看到边界 */
export function draftRowBoundText(spec: DraftInputSpec): string {
  const lo = spec.minItems ?? 0
  const hi = spec.maxItems
  if (hi !== null) return lo > 0 ? `至少 ${lo} 行，至多 ${hi} 行` : `至多 ${hi} 行，可以不填`
  return lo > 0 ? `至少 ${lo} 行` : '行数不限，可以不填'
}

export function draftItemContractErrors(item: DraftProposalItem): string[] {
  return item.requiredInputs
    .map(draftInputSpec)
    .flatMap((r) => (r.error ? [r.error] : []))
}

// ==================== 回答 ====================

/** 用户对一个待填项的回答: 填了值, 或明确留空(只有 nullable 才认) */
export type DraftAnswer = { kind: 'value'; value: unknown } | { kind: 'empty' }

export type DraftAnswers = Record<string, DraftAnswer | undefined>

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/

function isIsoDate(v: unknown): boolean {
  if (typeof v !== 'string') return false
  const m = ISO_DATE.exec(v)
  if (!m) return false
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])))
  return d.getUTCFullYear() === Number(m[1]) && d.getUTCMonth() === Number(m[2]) - 1 && d.getUTCDate() === Number(m[3])
}

function rangeText(min: number | null, max: number | null, unit = ''): string {
  if (min !== null && max !== null) return `${min}–${max}${unit}`
  if (min !== null) return `不小于 ${min}${unit}`
  return `不大于 ${max}${unit}`
}

/**
 * 这个回答还差什么。null = 可以提交。没回答返回「未填写」; 填了但不合规格返回具体原因,
 * 组件把原因显示在输入框旁(边界前置显示, 不等后端拒了再说)。
 */
export function draftAnswerProblem(spec: DraftInputSpec, answer: DraftAnswer | undefined): string | null {
  if (!answer) return '未填写'
  if (answer.kind === 'empty') return spec.nullable ? null : '这一项不能留空'
  const v = answer.value
  if (v === null || v === undefined) return '未填写'
  const { min, max } = spec
  switch (spec.type) {
    case 'TEXT': {
      if (typeof v !== 'string' || v.trim().length === 0) return '未填写'
      if (spec.maxLength !== null && v.length > spec.maxLength) return `最多 ${spec.maxLength} 字，现在 ${v.length} 字`
      return null
    }
    case 'INTEGER':
    case 'DECIMAL': {
      if (typeof v !== 'number' || !Number.isFinite(v)) return '请填数字'
      if (spec.type === 'INTEGER' && !Number.isInteger(v)) return '只能填整数'
      if (min !== null && v < min) return `不能小于 ${min}`
      if (max !== null && v > max) return `不能大于 ${max}`
      return null
    }
    case 'BOOLEAN':
      return typeof v === 'boolean' ? null : '请选择'
    case 'ENUM':
      return typeof v === 'string' && spec.options.some((o) => o.value === v) ? null : '请从选项里选择'
    case 'MULTI_ENUM': {
      if (!Array.isArray(v) || v.length === 0) return '未选择'
      if (!v.every((x) => spec.options.some((o) => o.value === x))) return '请从选项里选择'
      if (new Set(v).size !== v.length) return '不能重复选择'
      const lo = min ?? 1
      if (v.length < lo || (max !== null && v.length > max)) return `应选 ${rangeText(lo, max, ' 项')}`
      return null
    }
    case 'DATE':
      return isIsoDate(v) ? null : '请选择日期'
    case 'STRUCTURE': {
      if (!isStructState(v) || v.kind !== spec.structureKind) return '未填写'
      const missing = draftStructMissing(spec, v, '', '', null)
      return missing.length ? `还有 ${missing.length} 处没填好` : null
    }
    default:
      return '页面不认识这一项的类型'
  }
}

export function missingDraftInputs(item: DraftProposalItem, answers: DraftAnswers): DraftRequiredInput[] {
  return item.requiredInputs.filter((input) => {
    const r = draftInputSpec(input)
    // 契约错误的项永远算「没填」: 这一条整体不能预览
    return !r.ok || draftAnswerProblem(r.spec, answers[input.path]) !== null
  })
}

export function allDraftInputsAnswered(item: DraftProposalItem, answers: DraftAnswers): boolean {
  return missingDraftInputs(item, answers).length === 0
}

/**
 * 把回答写回 item.request。只写 requiredInputs 列出的路径; 没填完或有契约错误就抛错
 * (调用方应先禁用按钮)。结果的字段集合与 item.request 完全相同 —— 不多一个字段。
 */
export function buildFilledDraftRequest(item: DraftProposalItem, answers: DraftAnswers): Record<string, unknown> {
  let request = JSON.parse(JSON.stringify(item.request)) as Record<string, unknown>
  for (const input of item.requiredInputs) {
    const r = draftInputSpec(input)
    if (!r.ok) throw new Error(r.error)
    const answer = answers[input.path]
    const problem = draftAnswerProblem(r.spec, answer)
    if (problem !== null) {
      throw new Error(`待填项「${input.label}」（${input.path}）${problem}`)
    }
    let value = answer!.kind === 'empty' ? null : (answer as { value: unknown }).value
    if (r.spec.type === 'STRUCTURE' && value !== null) value = draftSerializeStruct(r.spec, value as DraftStructState)
    request = writeAtDraftPath(request, input.path, value)
  }
  return request
}

// ==================== 结构化输入(STRUCTURE: LIST / OBJECT, 可嵌套) ====================

/** 一行(LIST)或一个对象(OBJECT)的回答。base = 预填时这一行原有的对象, 用来保留子字段以外的原值 */
export interface DraftStructRow {
  id: number
  answers: DraftAnswers
  base: Record<string, unknown>
}

export type DraftStructState =
  | { kind: 'LIST'; rows: DraftStructRow[] }
  | { kind: 'VALUES'; rows: DraftStructRow[] }
  | { kind: 'OBJECT'; row: DraftStructRow }

let structRowSeq = 0

function isStructState(v: unknown): v is DraftStructState {
  if (!isPlainObject(v)) return false
  return (isRowsKind(v.kind) && Array.isArray(v.rows)) || (v.kind === 'OBJECT' && isPlainObject(v.row))
}

/** 一个对象(预填行 / 空行)→ 子字段回答。嵌套 STRUCTURE 递归; 预填里的 null 算「还没填」 */
function rowFromObject(spec: DraftInputSpec, obj: unknown): DraftStructRow {
  const base = isPlainObject(obj) ? obj : {}
  const answers: DraftAnswers = {}
  for (const field of spec.itemFields) {
    const fieldSpec = requireSpec(field)
    const raw = base[field.path]
    if (fieldSpec.type === 'STRUCTURE') {
      answers[field.path] = { kind: 'value', value: draftStructFromValue(fieldSpec, raw ?? null) }
    } else if (raw !== null && raw !== undefined) {
      answers[field.path] = { kind: 'value', value: raw }
    }
  }
  return { id: ++structRowSeq, answers, base }
}

/**
 * 初始状态: 请求里已有值就用它(提案器部分预填); 为 null 时 LIST 给 minItems 行空行(minItems≥1 时),
 * 否则 0 行; OBJECT 给一个空对象。
 */
export function draftStructFromValue(spec: DraftInputSpec, value: unknown): DraftStructState {
  if (spec.structureKind === 'OBJECT') return { kind: 'OBJECT', row: rowFromObject(spec, value) }
  if (spec.structureKind === 'VALUES') {
    const initialValues: unknown[] = Array.isArray(value) ? value
      : Array.from({ length: (spec.minItems ?? 0) >= 1 ? (spec.minItems as number) : 0 }, (): unknown => null)
    return { kind: 'VALUES', rows: initialValues.map((v) => rowFromValue(spec, v)) }
  }
  if (Array.isArray(value)) return { kind: 'LIST', rows: value.map((row) => rowFromObject(spec, row)) }
  const initial = (spec.minItems ?? 0) >= 1 ? (spec.minItems as number) : 0
  return { kind: 'LIST', rows: Array.from({ length: initial }, () => rowFromObject(spec, null)) }
}

/** VALUES 的一行 = 一个值, 回答挂在那唯一子字段的 path 下 */
function rowFromValue(spec: DraftInputSpec, value: unknown): DraftStructRow {
  const key = spec.itemFields[0].path
  const answers: DraftAnswers = {}
  if (value !== null && value !== undefined) answers[key] = { kind: 'value', value }
  return { id: ++structRowSeq, answers, base: {} }
}

export function draftStructRowTitle(spec: DraftInputSpec, index: number): string {
  return spec.itemLabel ? `${spec.itemLabel} ${index + 1}` : `第 ${index + 1} 行`
}

export interface DraftRowPermission {
  ok: boolean
  reason: string
}

export function draftStructCanAddRow(spec: DraftInputSpec, state: DraftStructState): DraftRowPermission {
  if (state.kind === 'OBJECT') return { ok: false, reason: '' }
  if (spec.maxItems !== null && state.rows.length >= spec.maxItems) {
    return { ok: false, reason: `最多 ${spec.maxItems} 行，已经满了` }
  }
  return { ok: true, reason: '' }
}

export function draftStructCanRemoveRow(spec: DraftInputSpec, state: DraftStructState): DraftRowPermission {
  if (state.kind === 'OBJECT') return { ok: false, reason: '' }
  const lo = spec.minItems ?? 0
  if (state.rows.length <= lo) return { ok: false, reason: `至少要保留 ${lo} 行` }
  return { ok: true, reason: '' }
}

/** 加一行空行(不可加时原样返回) */
export function draftStructAddRow(spec: DraftInputSpec, state: DraftStructState): DraftStructState {
  if (state.kind === 'OBJECT' || !draftStructCanAddRow(spec, state).ok) return state
  const row = state.kind === 'VALUES' ? rowFromValue(spec, null) : rowFromObject(spec, null)
  return { kind: state.kind, rows: [...state.rows, row] }
}

/** 删一行(不可删时原样返回) */
export function draftStructRemoveRow(spec: DraftInputSpec, state: DraftStructState, rowId: number): DraftStructState {
  if (state.kind === 'OBJECT' || !draftStructCanRemoveRow(spec, state).ok) return state
  return { kind: state.kind, rows: state.rows.filter((row) => row.id !== rowId) }
}

/** 改某一行(LIST 按 rowId; OBJECT 忽略 rowId)的某个子字段回答, 返回新状态 */
export function draftStructSetAnswer(
  state: DraftStructState,
  rowId: number | null,
  fieldPath: string,
  answer: DraftAnswer | undefined
): DraftStructState {
  const patch = (row: DraftStructRow): DraftStructRow => ({ ...row, answers: { ...row.answers, [fieldPath]: answer } })
  if (state.kind === 'OBJECT') return { kind: 'OBJECT', row: patch(state.row) }
  return { kind: state.kind, rows: state.rows.map((row) => (row.id === rowId ? patch(row) : row)) }
}

/** 没填好的一处: 绝对路径(与后端报错同语法) + 给人看的位置名 + 原因 */
export interface DraftMissing {
  path: string
  label: string
  problem: string
}

function joinLabel(prefix: string | null, label: string): string {
  return prefix ? `${prefix} · ${label}` : label
}

function joinPath(base: string, tail: string): string {
  if (!base) return tail.startsWith('.') ? tail.slice(1) : tail
  return base + tail
}

/**
 * 一个结构里所有没填好的地方, 逐处命名(如「岗位 2 · 最少在岗人数」)。
 * @param absPath  这个结构在请求里的绝对路径(如 `positions`)
 * @param ownLabel 这个结构自己的名字(行数不对、OBJECT 子字段时用)
 * @param prefix   外层位置名(嵌套时是外层的行名)
 */
export function draftStructMissing(
  spec: DraftInputSpec,
  state: DraftStructState,
  absPath: string,
  ownLabel: string,
  prefix: string | null
): DraftMissing[] {
  const out: DraftMissing[] = []
  const rows = state.kind === 'OBJECT' ? [state.row] : state.rows
  if (state.kind !== 'OBJECT') {
    const lo = spec.minItems ?? 0
    if (rows.length < lo) {
      out.push({ path: absPath, label: joinLabel(prefix, ownLabel), problem: `至少 ${lo} 行，现在 ${rows.length} 行` })
    }
    if (spec.maxItems !== null && rows.length > spec.maxItems) {
      out.push({ path: absPath, label: joinLabel(prefix, ownLabel), problem: `至多 ${spec.maxItems} 行，现在 ${rows.length} 行` })
    }
  }
  rows.forEach((row, index) => {
    const rowLabel = state.kind === 'OBJECT' ? joinLabel(prefix, ownLabel) : joinLabel(prefix, draftStructRowTitle(spec, index))
    const rowPath = state.kind === 'OBJECT' ? absPath : joinPath(absPath, `[${index}]`)
    if (state.kind === 'VALUES') {
      // 一行就是一个值: 位置名是行名(如「选项 2」), 路径是 optionCodes[1]
      const valueField = spec.itemFields[0]
      const problem = draftAnswerProblem(requireSpec(valueField), row.answers[valueField.path])
      if (problem !== null) out.push({ path: rowPath, label: rowLabel, problem })
      return
    }
    for (const field of spec.itemFields) {
      const fieldSpec = requireSpec(field)
      const answer = row.answers[field.path]
      const fieldPath = joinPath(rowPath, `.${field.path}`)
      if (fieldSpec.type === 'STRUCTURE' && answer?.kind === 'value' && isStructState(answer.value)) {
        out.push(...draftStructMissing(fieldSpec, answer.value, fieldPath, field.label, rowLabel))
        continue
      }
      const problem = draftAnswerProblem(fieldSpec, answer)
      if (problem !== null) out.push({ path: fieldPath, label: joinLabel(rowLabel, field.label), problem })
    }
  })
  return out
}

/** LIST → 行对象数组; OBJECT → 对象。键只有子字段(外加预填行里原有、不在子字段里的原值) */
export function draftSerializeStruct(spec: DraftInputSpec, state: DraftStructState): unknown {
  if (state.kind === 'LIST') return state.rows.map((row) => serializeStructRow(spec, row))
  if (state.kind === 'VALUES') {
    // 纯值数组: ["A","B"], ⛔ 不包成 { optionCodes: "A" }
    const key = spec.itemFields[0].path
    return state.rows.map((row) => {
      const answer = row.answers[key]
      return !answer || answer.kind === 'empty' ? null : answer.value
    })
  }
  return serializeStructRow(spec, state.row)
}

function serializeStructRow(spec: DraftInputSpec, row: DraftStructRow): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  const own = new Set(spec.itemFields.map((f) => f.path))
  for (const [key, value] of Object.entries(row.base)) {
    if (!own.has(key)) out[key] = value
  }
  for (const field of spec.itemFields) {
    const fieldSpec = requireSpec(field)
    const answer = row.answers[field.path]
    if (!answer || answer.kind === 'empty') {
      out[field.path] = null
    } else if (fieldSpec.type === 'STRUCTURE') {
      out[field.path] = draftSerializeStruct(fieldSpec, answer.value as DraftStructState)
    } else {
      out[field.path] = answer.value
    }
  }
  return out
}

/**
 * 整条草稿还差哪些地方没填好(顶层 + 结构里每一处), 逐处命名。
 * 「还差 N 项」用它的长度。契约错误的项也算一处(这一条整体不能预览)。
 */
export function draftMissingDetails(item: DraftProposalItem, answers: DraftAnswers): DraftMissing[] {
  const out: DraftMissing[] = []
  for (const input of item.requiredInputs) {
    const r = draftInputSpec(input)
    if (!r.ok) {
      out.push({ path: input.path, label: input.label, problem: '格式与页面不一致' })
      continue
    }
    const answer = answers[input.path]
    if (r.spec.type === 'STRUCTURE' && answer?.kind === 'value' && isStructState(answer.value)) {
      out.push(...draftStructMissing(r.spec, answer.value, input.path, input.label, null))
      continue
    }
    const problem = draftAnswerProblem(r.spec, answer)
    if (problem !== null) out.push({ path: input.path, label: input.label, problem })
  }
  return out
}

/** 当前所有可被后端报错点名的字段路径(顶层 + 结构里每行每个子字段), 用来高亮 */
export function draftFieldPaths(item: DraftProposalItem, answers: DraftAnswers): string[] {
  const out: string[] = []
  const walk = (spec: DraftInputSpec, state: DraftStructState, absPath: string) => {
    const rows = state.kind === 'OBJECT' ? [state.row] : state.rows
    rows.forEach((row, index) => {
      const rowPath = state.kind === 'OBJECT' ? absPath : `${absPath}[${index}]`
      if (state.kind === 'VALUES') {
        out.push(rowPath)
        return
      }
      for (const field of spec.itemFields) {
        const fieldPath = `${rowPath}.${field.path}`
        out.push(fieldPath)
        const fieldSpec = requireSpec(field)
        const answer = row.answers[field.path]
        if (fieldSpec.type === 'STRUCTURE' && answer?.kind === 'value' && isStructState(answer.value)) {
          walk(fieldSpec, answer.value, fieldPath)
        }
      }
    })
  }
  for (const input of item.requiredInputs) {
    out.push(input.path)
    const r = draftInputSpec(input)
    const answer = answers[input.path]
    if (r.ok && r.spec.type === 'STRUCTURE' && answer?.kind === 'value' && isStructState(answer.value)) {
      walk(r.spec, answer.value, input.path)
    }
  }
  return out
}

/**
 * materialize 的 body = 预览时的请求 + 确认摘要 + 固定幂等键 + 预览给出的额外确认字段。
 * 额外确认字段(如 Workflow 的 confirmedOperationDigest)只填请求里本来就有的字段 ——
 * 后端拒绝未知字段, 这里也不许借这个口子新增字段。
 */
export function buildMaterializeBody(
  request: Record<string, unknown>,
  confirmedPreviewDigest: string,
  idempotencyKey: string,
  confirmation?: Record<string, string> | null
): Record<string, unknown> & { confirmedPreviewDigest: string; idempotencyKey: string } {
  const extra: Record<string, string> = {}
  for (const [key, value] of Object.entries(confirmation ?? {})) {
    if (Object.prototype.hasOwnProperty.call(request, key)) extra[key] = value
  }
  return { ...request, ...extra, confirmedPreviewDigest, idempotencyKey }
}

// ==================== 给人看的文字 ====================

const CODE_CHAR = 'A-Za-z0-9_'

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/**
 * 后端 constraint 里列的是选项原值(如「FREEZER、COLD_CHAIN_VEHICLE 之一」), 一线用户看不懂。
 * 有选项的字段: 把每个**整词**出现的 option.value 换成 option.label。
 * 一次正则扫描完成(换出来的中文不会被再换一次); 长的值排在前面, 且整词边界保证
 * COLD 不会把 COLD_CHAIN_VEHICLE 拆开, 也不会改动 COLDROOM 这类更长的词。
 */
export function draftConstraintText(constraint: string, options: readonly DraftOption[] | null | undefined): string {
  const text = constraint ?? ''
  const usable = (options ?? []).filter((o) => typeof o?.value === 'string' && o.value.length > 0 && typeof o.label === 'string')
  if (!text || usable.length === 0) return text
  const byValue = new Map(usable.map((o) => [o.value, o.label]))
  const values = [...byValue.keys()].sort((x, y) => y.length - x.length)
  const pattern = new RegExp(`(?<![${CODE_CHAR}])(?:${values.map(escapeRegExp).join('|')})(?![${CODE_CHAR}])`, 'g')
  return text.replace(pattern, (hit) => byValue.get(hit) ?? hit)
}

/**
 * 结构里的子字段: 原因和外层结构一样时不再逐个重复(外层在顶部说过一次); 不一样才显示。
 */
export function draftSubFieldReason(reason: string | null | undefined, parentReason: string | null | undefined): string {
  const own = (reason ?? '').trim()
  if (!own) return ''
  return parentReason && own === parentReason.trim() ? '' : own
}

export interface DraftFillSummary {
  /** 还挡着预览的顶层待填项(名字) */
  blocking: string[]
  /** 可选、现在是 0 行的列表, 如「冷链控制点（0 行）」—— 不算「需补充」 */
  optionalEmpty: string[]
}

function pathIsWithin(path: string, root: string): boolean {
  return path === root || path.startsWith(`${root}.`) || path.startsWith(`${root}[`)
}

/** 「还需你补充 N 项」的 N 只数还挡着预览的顶层待填项; 可选的空列表单独列出 */
export function draftFillSummary(item: DraftProposalItem, answers: DraftAnswers): DraftFillSummary {
  const missing = draftMissingDetails(item, answers)
  const blocking: string[] = []
  const optionalEmpty: string[] = []
  for (const input of item.requiredInputs) {
    if (missing.some((m) => pathIsWithin(m.path, input.path))) {
      blocking.push(input.label)
      continue
    }
    const r = draftInputSpec(input)
    const answer = answers[input.path]
    if (r.ok && r.spec.type === 'STRUCTURE' && answer?.kind === 'value' && isStructState(answer.value)
        && answer.value.kind !== 'OBJECT' && answer.value.rows.length === 0) {
      optionalEmpty.push(`${input.label}（0 行）`)
    }
  }
  return { blocking, optionalEmpty }
}

// ==================== 证据 ====================

export const NOT_CARRIED_PREFIX = '未带入：'

export function splitDraftEvidence(evidence: DraftEvidence[]): { carried: DraftEvidence[]; notCarried: DraftEvidence[] } {
  const carried: DraftEvidence[] = []
  const notCarried: DraftEvidence[] = []
  for (const e of evidence ?? []) {
    if (typeof e.value === 'string' && e.value.startsWith(NOT_CARRIED_PREFIX)) notCarried.push(e)
    else carried.push(e)
  }
  return { carried, notCarried }
}

// ==================== 幂等键 ====================

export interface DraftIdempotencyParts {
  actorType: 'FACTORY_USER' | 'PLATFORM_ADMIN'
  actorId: number
  factoryId: string
  blueprintId: string
  blueprintVersion: number | string
  sourceDigest: string
  capabilityId: string
  itemKey: string
}

/** 后端幂等键长度上限 */
export const IDEMPOTENCY_KEY_MAX_LENGTH = 128

/**
 * 被哈希的原文: 身份命名空间/编号与全部六段原样拼接(sourceDigest 不截断)。不含随机数或时间。
 * 缺任何一段都抛错, 不拼出一个看起来正常的键。
 */
export function draftIdempotencyMaterial(parts: DraftIdempotencyParts): string {
  if (!['FACTORY_USER', 'PLATFORM_ADMIN'].includes(parts.actorType)
      || !Number.isSafeInteger(parts.actorId) || parts.actorId <= 0) {
    throw new Error('无法生成幂等键：登录身份不完整，请重新登录')
  }
  const entries = Object.entries(parts) as Array<[keyof DraftIdempotencyParts, unknown]>
  for (const [name, value] of entries) {
    if (value === null || value === undefined || String(value).trim() === '') {
      throw new Error(`无法生成幂等键：缺少 ${name}`)
    }
  }
  return [
    'draft',
    parts.actorType,
    String(parts.actorId),
    parts.factoryId,
    parts.blueprintId,
    String(parts.blueprintVersion),
    parts.sourceDigest,
    parts.capabilityId,
    parts.itemKey,
  ].join(':')
}

/**
 * 每个(身份命名空间/编号 × 工厂 × 蓝图版本 × 血缘摘要 × 能力 × 条目)一个固定键:
 * `bd:{capabilityId}:{SHA-256(原文) 前 40 位十六进制}`。原文可能远超 128 字符(UUID + 很长的 itemKey),
 * 哈希后长度只取决于 capabilityId。刷新页面、重试都得到同一个键 ⇒ 不会写两份。
 *
 * 用 globalThis.crypto.subtle: 浏览器只在安全上下文(https / localhost)提供它。
 * 拿不到就抛错 —— ⛔ 不退回不哈希的原文(会超长被拒), 也不换一套算法(同一条目会得到两个键)。
 */
export async function draftIdempotencyKey(parts: DraftIdempotencyParts): Promise<string> {
  return hashedIdempotencyKey('bd', parts.capabilityId, draftIdempotencyMaterial(parts))
}

/**
 * 当前 READY 预览的幂等键原文: 旧草稿键再绑定这一次真实预览摘要。
 * 摘要是服务端预览的事实凭证，缺失时拒绝生成键，不能让确认写入复用一个未绑定预览的键。
 */
export function draftPreviewIdempotencyMaterial(parts: DraftIdempotencyParts, previewDigest: string): string {
  if (typeof previewDigest !== 'string' || previewDigest.trim() === '') {
    throw new Error('无法生成幂等键：缺少 previewDigest')
  }
  return `${draftIdempotencyMaterial(parts)}:preview:${previewDigest}`
}

/**
 * 当前 READY 预览专用幂等键。旧 draftIdempotencyKey 保持兼容，新的写入键必须绑定真实 previewDigest。
 */
export async function draftPreviewIdempotencyKey(
  parts: DraftIdempotencyParts,
  previewDigest: string
): Promise<string> {
  return hashedIdempotencyKey('bd', parts.capabilityId, draftPreviewIdempotencyMaterial(parts, previewDigest))
}

/**
 * `{prefix}:{capabilityId}:{SHA-256(material) 前 40 位十六进制}`。草稿写入用 bd, 转正用 bp。
 * 拿不到 crypto.subtle(非 https)时抛错 —— ⛔ 不退回原文(会超长被拒), 也不换算法(同一对象会得到两个键)。
 */
export async function hashedIdempotencyKey(prefix: string, capabilityId: string, material: string): Promise<string> {
  const subtle = globalThis.crypto?.subtle
  if (!subtle) {
    throw new Error('当前页面不是安全连接（https），浏览器不提供 SHA-256，无法生成写入幂等键；请用 https 地址打开本页')
  }
  const digest = await subtle.digest('SHA-256', new TextEncoder().encode(material))
  const hex = Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
  const key = `${prefix}:${capabilityId}:${hex.slice(0, 40)}`
  if (key.length > IDEMPOTENCY_KEY_MAX_LENGTH) {
    throw new Error(`幂等键超过 ${IDEMPOTENCY_KEY_MAX_LENGTH} 字符（能力编号过长：${capabilityId}）`)
  }
  return key
}

// ==================== 身份 ====================

export interface DraftAuthLike {
  isFactory: boolean
  isPlatform?: boolean
  currentRole: string
  factoryId: string
}

/** UI visibility follows the authenticated namespace; backend rechecks current authority. */
export function canConfirmBlueprintDrafts(auth: DraftAuthLike, factoryId: string): boolean {
  return !!factoryId
    && ((auth.isFactory === true && auth.isPlatform !== true
      && auth.currentRole === 'factory_super_admin' && auth.factoryId === factoryId)
      || (auth.isPlatform === true && auth.isFactory !== true
        && auth.currentRole === 'super_admin'))
}

// ==================== 行状态 ====================

export type DraftRowStateKind =
  | 'READ_ONLY'
  | 'ENTRY_CLOSED'
  | 'DEPENDENCY_CLOSED'
  | 'NO_PROPOSAL'
  | 'AWAITING_FACTORY'
  | 'LOADING'
  | 'PROPOSAL_ERROR'
  | 'BLOCKED'
  | 'NEEDS_INPUT'
  | 'READY_FOR_PREVIEW'

export type DraftTone = 'info' | 'warning' | 'danger' | 'success'

export interface DraftRowState {
  kind: DraftRowStateKind
  label: string
  tone: DraftTone
  detail: string
  /** 这一行能否展开填写 / 预览 / 写入 */
  actionable: boolean
}

export interface DraftRowContext {
  canConfirm: boolean
  proposal?: DraftProposal | null
  proposalError?: string | null
  /** 每个 itemKey 还差几项待填(组件实时上报); 没上报的按 proposal 原样计 */
  missingByItem?: Record<string, number>
  labelOf?: (capabilityId: string) => string
}

export function deriveDraftRowState(row: DraftWriteOrderRow, ctx: DraftRowContext): DraftRowState {
  const name = ctx.labelOf ?? ((id: string) => id)
  if (row.adapterMode !== 'MODULE_DRAFT') {
    return { kind: 'READ_ONLY', label: '只读能力', tone: 'info', detail: '这项能力不写草稿', actionable: false }
  }
  if (!row.entryOpen) {
    return { kind: 'ENTRY_CLOSED', label: '入口未开放', tone: 'info', detail: '这项能力的草稿写入入口还没有开放', actionable: false }
  }
  const closedDeps = row.dependenciesWithoutOpenEntry ?? []
  if (closedDeps.length > 0) {
    const names = closedDeps.map((id) => `「${name(id)}」`).join('、')
    return {
      kind: 'DEPENDENCY_CLOSED',
      label: `依赖${names}入口未开放`,
      tone: 'warning',
      detail: `要先写${names}的草稿，而它的入口还没有开放`,
      actionable: false,
    }
  }
  if (!row.proposalOpen) {
    return { kind: 'NO_PROPOSAL', label: '无预填', tone: 'info', detail: '需手工填写，暂未提供', actionable: false }
  }
  if (!ctx.canConfirm) {
    return {
      kind: 'AWAITING_FACTORY',
      label: '待本厂确认',
      tone: 'info',
      detail: '草稿需由本厂工厂超级管理员确认写入',
      actionable: false,
    }
  }
  if (ctx.proposalError) {
    return { kind: 'PROPOSAL_ERROR', label: '预填失败', tone: 'danger', detail: ctx.proposalError, actionable: false }
  }
  const proposal = ctx.proposal
  if (!proposal) {
    return { kind: 'LOADING', label: '读取预填…', tone: 'info', detail: '', actionable: false }
  }
  if (proposal.status === 'BLOCKED') {
    const detail = (proposal.blockers ?? [])
      .map((b) => (b.nextStep ? `${b.message}（下一步：${b.nextStep}）` : b.message))
      .join('；')
    return { kind: 'BLOCKED', label: '阻塞', tone: 'danger', detail, actionable: false }
  }
  const missing = (proposal.items ?? []).reduce((sum, item) => {
    const reported = ctx.missingByItem?.[item.itemKey]
    return sum + (typeof reported === 'number' ? reported : item.requiredInputs.length)
  }, 0)
  if (missing > 0) {
    return { kind: 'NEEDS_INPUT', label: `待填 ${missing} 项`, tone: 'warning', detail: '', actionable: true }
  }
  return { kind: 'READY_FOR_PREVIEW', label: '可预览', tone: 'success', detail: '', actionable: true }
}

/**
 * 已写入的标签。没有这一能力的记录(没权限 / 没读到)时返回 null —— ⛔ 不说「未写入」;
 * supported=false 是「后端列不出」, 同样 ⛔ 不能说成「未写入」。
 */
export function deriveWrittenLabel(written: WrittenCapability | undefined | null): { label: string; tone: DraftTone } | null {
  if (!written) return null
  if (!written.supported) return { label: '暂无法列出已写入', tone: 'info' }
  const n = (written.drafts ?? []).length
  if (n > 0) return { label: `已写入 ${n} 份`, tone: 'success' }
  return { label: '未写入', tone: 'info' }
}

// ==================== 错误 ====================

interface ErrorLike {
  message?: string
  code?: string
  backendErrorCode?: string | null
  backendMessage?: string | null
}

/** 403/404 时后端的码与原文在 backendErrorCode / backendMessage, 其它 4xx 在 code / message */
export function draftErrorCode(e: unknown): string | null {
  const err = (e ?? {}) as ErrorLike
  return err.backendErrorCode || err.code || null
}

export function draftErrorMessage(e: unknown): string {
  const err = (e ?? {}) as ErrorLike
  return err.backendMessage || err.message || '请求失败，未返回原因'
}

/** 这几个 400 的 message 带字段路径, 可以定位到具体输入 */
export const FIELD_ERROR_CODES: readonly string[] = [
  'BLUEPRINT_DRAFT_REQUIRED_INPUT_MISSING',
  'BLUEPRINT_DRAFT_INPUT_INVALID',
  'BLUEPRINT_DRAFT_UNKNOWN_FIELD',
]

const PATH_CHAR = /[A-Za-z0-9_.[\]]/

/**
 * 从带字段路径的错误里找出它指的是哪一个待填项。取最长的命中, 且命中的前后不能紧挨着路径字符
 * (避免 `positions` 命中 `positions[0].department`)。找不到返回 null(错误仍原样显示, 只是不高亮)。
 */
export function draftErrorInputPath(e: unknown, paths: readonly string[]): string | null {
  const code = draftErrorCode(e)
  if (!code || !FIELD_ERROR_CODES.includes(code)) return null
  const message = draftErrorMessage(e)
  let best: string | null = null
  for (const path of paths) {
    let from = 0
    for (;;) {
      const at = message.indexOf(path, from)
      if (at < 0) break
      const before = at > 0 ? message[at - 1] : ''
      const after = message[at + path.length] ?? ''
      // 句末的英文句点不算路径的延续; 「.」后面跟字母才是更长的路径
      const continues = after === '.'
        ? /[A-Za-z]/.test(message[at + path.length + 1] ?? '')
        : PATH_CHAR.test(after)
      if (!PATH_CHAR.test(before) && !continues) {
        if (!best || path.length > best.length) best = path
        break
      }
      from = at + 1
    }
  }
  return best
}
