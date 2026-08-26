/**
 * Hermes-safe formatting utilities.
 *
 * Hermes (React Native JS engine) has incomplete Intl support.
 * toLocaleString / toLocaleDateString / toLocaleTimeString all crash
 * or return wrong results.  These helpers use manual string formatting.
 */

// ─── Numbers ────────────────────────────────────────────────────────

/** Thousands-separated number: 12345 → "12,345" */
export function formatNumberWithCommas(value: number | string | null | undefined): string {
  const n = typeof value === 'string' ? parseFloat(value) : value;
  if (n == null || isNaN(n as number)) return '0';
  const v = n as number;
  const parts = v.toFixed(v % 1 === 0 ? 0 : 2).split('.');
  const intPart = parts[0] ?? '0';
  const decPart = parts[1];
  const signed = intPart.startsWith('-');
  const digits = signed ? intPart.slice(1) : intPart;
  const withCommas = digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return (signed ? '-' : '') + withCommas + (decPart ? '.' + decPart : '');
}

/** Compact number: ≥1亿→"1.23亿", ≥1万→"1.2万", else thousands-separated */
export function formatCompactNumber(value: number | string | null | undefined): string {
  const n = typeof value === 'string' ? parseFloat(value) : value;
  if (n == null || isNaN(n as number)) return '0';
  const v = n as number;
  const abs = Math.abs(v);
  const sign = v < 0 ? '-' : '';
  if (abs >= 100000000) return `${sign}${(abs / 100000000).toFixed(2)}亿`;
  if (abs >= 10000) return `${sign}${(abs / 10000).toFixed(1)}万`;
  return formatNumberWithCommas(v);
}

/** Format currency with ¥ prefix */
export function formatCurrency(value: number | string | null | undefined): string {
  return `¥${formatNumberWithCommas(value)}`;
}

/** Format percentage: 0.856 → "85.6%"  or  85.6 → "85.6%" */
export function formatPercent(value: number | null | undefined, decimals = 1): string {
  if (value == null || isNaN(value)) return '0%';
  const v = Math.abs(value) < 1 && value !== 0 ? value * 100 : value;
  return `${v.toFixed(decimals)}%`;
}

// ─── Product name (P0-1 fool-proof display fallback) ────────────────

/**
 * Backend legacy placeholder for an un-bound batch product name.
 * P0-1 fix makes createBatch resolve the real name from productTypeId, but
 * historical rows (pre-backfill, or rows the backfill could not resolve)
 * may still carry this string. The UI must never surface it as a real name.
 */
const PRODUCT_NAME_PLACEHOLDER = '待设置产品名称';

/**
 * Display-safe product name: filters out the legacy "待设置产品名称" placeholder
 * (and blank/null) so the UI shows a clean fallback (e.g. t('...pending')).
 *
 * @param name    raw productName / productType from the API
 * @param fallback what to show when the name is missing or placeholder
 */
export function displayProductName(
  name: string | null | undefined,
  fallback: string,
): string {
  const trimmed = (name ?? '').trim();
  if (!trimmed || trimmed === PRODUCT_NAME_PLACEHOLDER) return fallback;
  return trimmed;
}

// ─── Date helpers ───────────────────────────────────────────────────

function toDate(input: Date | string | number | null | undefined): Date | null {
  if (input == null) return null;
  const d = input instanceof Date ? input : new Date(input);
  if (isNaN(d.getTime())) return null;
  return d;
}

function pad(n: number): string {
  return n < 10 ? '0' + n : String(n);
}

// ─── Dates ──────────────────────────────────────────────────────────

/** "2026-02-09" */
export function formatDate(input: Date | string | number | null | undefined): string {
  const d = toDate(input);
  if (!d) return '';
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * 把 "YYYY-MM-DD" 还原成 Date；解析不出来就落到今天。
 *
 * 用在日期滚轮/日历选择器打开时决定初始值：字段本来就默认今天，
 * 解析失败时给今天不是编数据，用户不确认也不会写回任何东西。
 *
 * ⚠️ 必须回读一遍：`new Date(2026, 1, 31)` 会被 Date 悄悄滚到 3 月 3 日，
 *    直接返回等于把一个用户没选过的日期塞给他。
 *
 * 🔴 2026-08-26: 这条规则一度在 6 个屏里各写了一份（配日期滚轮时复制粘贴的）。
 *    收敛到这里。同文件的 formatDate() 是它的反向操作，两者必须成对使用 ——
 *    ⛔ 不要再在屏幕文件里写私有副本（本仓 orderDate.ts 的注释就记着上一次同样的事）。
 */
export function parseYmdOrToday(value: string | null | undefined, now: Date = new Date()): Date {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value ?? '');
  if (m) {
    const year = Number(m[1]);
    const monthIndex = Number(m[2]) - 1;
    const day = Number(m[3]);
    const parsed = new Date(year, monthIndex, day);
    if (
      parsed.getFullYear() === year
      && parsed.getMonth() === monthIndex
      && parsed.getDate() === day
    ) {
      return parsed;
    }
  }
  return now;
}

/** "2026/02/09" */
export function formatDateSlash(input: Date | string | number | null | undefined): string {
  const d = toDate(input);
  if (!d) return '';
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())}`;
}

/** "02-09" (month-day only) */
export function formatMonthDay(input: Date | string | number | null | undefined): string {
  const d = toDate(input);
  if (!d) return '';
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

// ─── Times ──────────────────────────────────────────────────────────

/** "14:30" */
export function formatTime(input: Date | string | number | null | undefined): string {
  const d = toDate(input);
  if (!d) return '';
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** "14:30:05" */
export function formatTimeFull(input: Date | string | number | null | undefined): string {
  const d = toDate(input);
  if (!d) return '';
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

// ─── DateTime combos ────────────────────────────────────────────────

/** "2026-02-09 14:30" */
export function formatDateTime(input: Date | string | number | null | undefined): string {
  const d = toDate(input);
  if (!d) return '';
  return `${formatDate(d)} ${formatTime(d)}`;
}

/** "2026-02-09 14:30:05" */
export function formatDateTimeFull(input: Date | string | number | null | undefined): string {
  const d = toDate(input);
  if (!d) return '';
  return `${formatDate(d)} ${formatTimeFull(d)}`;
}

/** "02-09 14:30" (compact, no year) */
export function formatShortDateTime(input: Date | string | number | null | undefined): string {
  const d = toDate(input);
  if (!d) return '';
  return `${formatMonthDay(d)} ${formatTime(d)}`;
}
