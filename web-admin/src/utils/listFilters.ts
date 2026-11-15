// 列表共享筛选件的纯函数 (原型 BL21 批量单号 / BL23 相对日期, 口径见原型 SHARED.md 与 DECISIONS R18)。
// 只做输入整理与日期区间换算; 匹配与过滤一律在后端完成。

export const MAX_BATCH_NOS = 200;

export interface ParsedBatchNos {
  nos: string[];
  /** 去重前有效单号个数超出上限的部分 (不静默截断, 由调用方提示) */
  overLimit: boolean;
}

// 换行、逗号 (中英文)、顿号、分号、空白都算分隔; 保序去重; 不改大小写 (单号精确匹配)
export function parseBatchNos(text: string): ParsedBatchNos {
  const seen = new Set<string>();
  const nos: string[] = [];
  for (const part of text.split(/[\s,，、;；]+/)) {
    const no = part.trim();
    if (!no || seen.has(no)) continue;
    seen.add(no);
    nos.push(no);
  }
  return { nos: nos.slice(0, MAX_BATCH_NOS), overLimit: nos.length > MAX_BATCH_NOS };
}

export type RelativeDateKey = 'today' | 'week' | 'month' | 'last7' | 'last30';

export interface RelativeRange {
  key: RelativeDateKey;
  label: string;
  /** yyyy-mm-dd, 含 */
  from: string;
  /** yyyy-mm-dd, 含 */
  to: string;
}

export const RELATIVE_DATE_OPTIONS: ReadonlyArray<{ key: RelativeDateKey; label: string }> = [
  { key: 'today', label: '今天' },
  { key: 'week', label: '本周' },
  { key: 'month', label: '本月' },
  { key: 'last7', label: '近 7 天' },
  { key: 'last30', label: '近 30 天' },
];

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

function fmt(d: Date): string {
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
}

// 北京时间的今天 (按日历日, 与浏览器所在时区无关), 用 UTC 日期对象承载纯日期运算
function beijingToday(now: Date): Date {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(now).split('-').map(Number);
  return new Date(Date.UTC(parts[0], parts[1] - 1, parts[2]));
}

function addDays(d: Date, days: number): Date {
  return new Date(d.getTime() + days * 86400000);
}

// R18: 本周 = 本周一至本周日, 本月 = 1 日至月末 (不截止到今天); 今天 / 近 N 天截止今天
export function relativeRange(key: RelativeDateKey, now: Date = new Date()): RelativeRange {
  const today = beijingToday(now);
  const label = RELATIVE_DATE_OPTIONS.find((o) => o.key === key)?.label ?? key;
  switch (key) {
    case 'today':
      return { key, label, from: fmt(today), to: fmt(today) };
    case 'week': {
      const offset = (today.getUTCDay() + 6) % 7; // 周一 = 0
      const monday = addDays(today, -offset);
      return { key, label, from: fmt(monday), to: fmt(addDays(monday, 6)) };
    }
    case 'month': {
      const first = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), 1));
      const last = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth() + 1, 0));
      return { key, label, from: fmt(first), to: fmt(last) };
    }
    case 'last7':
      return { key, label, from: fmt(addDays(today, -6)), to: fmt(today) };
    case 'last30':
      return { key, label, from: fmt(addDays(today, -29)), to: fmt(today) };
  }
}
