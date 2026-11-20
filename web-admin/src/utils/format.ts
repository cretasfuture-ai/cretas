/**
 * 共用数值格式（统一原型 DECISIONS T5；MIGRATION-PLAN P1）。
 *
 * 规则与原型 design-system.js 的 fmtNumber / fmtMoney / fmtQty / fmtPct / safeRatio 一致：
 * - 缺失（null / undefined / 空串）≠ 0：显示「—」，绝不显示 0；
 * - 非数字（布尔、NaN、Infinity、无法解析的字符串）显示「格式无效」，不猜；
 * - 默认两位小数、千分位；金额带币种（默认「元」），数量带单位，单位缺失写「（单位未填）」；
 * - 零分母不做除法，返回 { value: null, reason }，调用方显示「无法计算：原因」。
 *
 * 与 utils/format-number.ts 的区别：那边是图表/KPI 的「万/亿」缩写，这里是表格与明细里的精确值。
 * 千分位手写（不走 toLocaleString），与 format-number.ts 一样避免跨运行环境差异。
 */

export const MISSING = '—'
export const INVALID = '格式无效'

type ValueKind = 'missing' | 'invalid' | 'zero' | 'value'

const NUMERIC_TEXT = /^[-+]?\d+(?:\.\d+)?(?:e[-+]?\d+)?$/i

function valueKind(v: unknown): ValueKind {
  if (v === null || v === undefined) return 'missing'
  let n: unknown = v
  if (typeof v === 'string') {
    const t = v.trim()
    if (!t) return 'missing'
    const plain = t.replace(/,/g, '')
    if (!NUMERIC_TEXT.test(plain)) return 'invalid'
    n = Number(plain)
  }
  if (typeof n !== 'number' || !Number.isFinite(n)) return 'invalid'
  return n === 0 ? 'zero' : 'value'
}

function toNumber(v: unknown): number {
  return typeof v === 'string' ? Number(v.trim().replace(/,/g, '')) : (v as number)
}

function groupThousands(n: number, digits: number): string {
  const fixed = Math.abs(n).toFixed(digits)
  const [intPart, decPart] = fixed.split('.')
  const grouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  // -0.001 按两位小数是 0.00：不显示「-0.00」
  const isNegative = n < 0 && Number(fixed) !== 0
  return (isNegative ? '-' : '') + grouped + (decPart !== undefined ? '.' + decPart : '')
}

export interface FmtNumberOptions {
  digits?: number
  missing?: string
  invalid?: string
}

export function fmtNumber(v: unknown, { digits = 2, missing = MISSING, invalid = INVALID }: FmtNumberOptions = {}): string {
  const k = valueKind(v)
  if (k === 'missing') return missing
  if (k === 'invalid') return invalid
  return groupThousands(toNumber(v), digits)
}

export interface FmtMoneyOptions {
  currency?: string
  digits?: number
  missing?: string
}

export function fmtMoney(v: unknown, { currency = '元', digits = 2, missing = MISSING }: FmtMoneyOptions = {}): string {
  const k = valueKind(v)
  if (k === 'missing' || k === 'invalid') return fmtNumber(v, { digits, missing })
  return fmtNumber(v, { digits }) + (currency ? ' ' + currency : '')
}

export interface FmtQtyOptions {
  digits?: number
  missing?: string
}

export function fmtQty(v: unknown, unit?: string | null, { digits = 2, missing = MISSING }: FmtQtyOptions = {}): string {
  const k = valueKind(v)
  if (k === 'missing' || k === 'invalid') return fmtNumber(v, { digits, missing })
  const u = String(unit ?? '').trim()
  return fmtNumber(v, { digits }) + (u ? ' ' + u : '（单位未填）')
}

export function fmtPct(v: unknown, { digits = 2, missing = MISSING }: FmtQtyOptions = {}): string {
  const k = valueKind(v)
  if (k === 'missing' || k === 'invalid') return fmtNumber(v, { digits, missing })
  return fmtNumber(v, { digits }) + '%'
}

export interface SafeRatio {
  value: number | null
  reason: string
}

/** 零分母不做除法：返回 { value: null, reason }；调用方显示「无法计算：原因」 */
export function safeRatio(num: unknown, den: unknown, reason = '分母为 0'): SafeRatio {
  const kn = valueKind(num)
  const kd = valueKind(den)
  if (kn === 'missing' || kd === 'missing') return { value: null, reason: '缺少数据' }
  if (kn === 'invalid' || kd === 'invalid') return { value: null, reason: INVALID }
  if (kd === 'zero') return { value: null, reason }
  return { value: toNumber(num) / toNumber(den), reason: '' }
}
