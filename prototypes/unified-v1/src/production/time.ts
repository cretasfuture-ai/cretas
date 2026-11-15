// R22：A 线页面的时间一律按北京时间显示。
// 带时区的 ISO（…Z / …+00:00 / …+08:00）先换成北京时间；没有时区的「YYYY-MM-DD HH:mm:ss」是黑湖 / 味美原样的本地时间，只截取不换算。
const BJ = 8 * 3600e3
const ZONED = /T.*(?:Z|[+-]\d\d:?\d\d)$/i
const iso = (t: number) => new Date(t + BJ).toISOString()
/** 北京时间「YYYY-MM-DD HH:mm」；缺失或无法解析返回 missing */
export function bjTime(v: unknown, missing = '—'): string {
  if (v === null || v === undefined || v === '') return missing
  const s = String(v)
  if (ZONED.test(s)) { const t = Date.parse(s); return Number.isFinite(t) ? iso(t).slice(0, 16).replace('T', ' ') : missing }
  return /^\d{4}-\d\d-\d\d[ T]\d\d:\d\d/.test(s) ? s.slice(0, 16).replace('T', ' ') : /^\d{4}-\d\d-\d\d$/.test(s) ? s : missing
}
/** 北京日期「YYYY-MM-DD」；缺失返回 ''（调用方自己写「未填写」） */
export function bjDay(v: unknown): string {
  if (v === null || v === undefined || v === '') return ''
  const s = String(v)
  if (ZONED.test(s)) { const t = Date.parse(s); return Number.isFinite(t) ? iso(t).slice(0, 10) : '' }
  return s.slice(0, 10)
}
/** 今天（北京日期） */
export const bjToday = (now: number = Date.now()) => iso(now).slice(0, 10)
