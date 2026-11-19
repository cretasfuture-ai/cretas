/**
 * 统一日期格式化工具
 */

/** Date 对象 → "YYYY-MM-DD" */
export function toDateString(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

// el-date-picker 配 `value-format="YYYY-MM-DD"` 时 v-model 是 string 而非 Date,
// TS 类型 `[Date, Date]` 与运行时不符. 直接 `.toISOString()` 会抛
// "e.toISOString is not a function" (PR #468 §6 P1-A).
export function toApiDateString(value: Date | string | null | undefined): string {
  if (value == null) return '';
  if (typeof value === 'string') {
    return value.length >= 10 ? value.slice(0, 10) : '';
  }
  if (value instanceof Date && !isNaN(value.getTime())) {
    return toDateString(value);
  }
  return '';
}

/** ISO 字符串 → "YYYY-MM-DD HH:mm:ss" (去掉T和毫秒) */
export function formatDateTime(dateStr?: string | null): string {
  if (!dateStr) return '-';
  try {
    const date = new Date(dateStr);
    if (isNaN(date.getTime())) return dateStr;
    const y = date.getFullYear();
    const mo = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    const h = String(date.getHours()).padStart(2, '0');
    const mi = String(date.getMinutes()).padStart(2, '0');
    const s = String(date.getSeconds()).padStart(2, '0');
    return `${y}-${mo}-${d} ${h}:${mi}:${s}`;
  } catch {
    return dateStr || '-';
  }
}

/** ISO 字符串 / Date → "HH:mm" */
export function formatTime(dateStr?: string | Date | null): string {
  if (!dateStr) return '-';
  try {
    const date = typeof dateStr === 'string' ? new Date(dateStr) : dateStr;
    if (isNaN(date.getTime())) return typeof dateStr === 'string' ? dateStr : '-';
    const h = String(date.getHours()).padStart(2, '0');
    const mi = String(date.getMinutes()).padStart(2, '0');
    return `${h}:${mi}`;
  } catch {
    return typeof dateStr === 'string' ? dateStr : '-';
  }
}

/** ISO 字符串 → "YYYY-MM-DD" */
export function formatDate(dateStr?: string | null): string {
  if (!dateStr) return '-';
  try {
    const date = new Date(dateStr);
    if (isNaN(date.getTime())) return dateStr;
    return toDateString(date);
  } catch {
    return dateStr || '-';
  }
}

/**
 * 后端 LocalDateTime / LocalDate 字符串 -> 展示串, 纯字符串处理, 不经 Date/UTC 换算(后端给的是不带时区的本地时间)。
 * `2026-11-17T15:00:00` / `2026-11-18T15:23:02.873513` -> `2026-11-17 15:00`; `2026-11-17` -> `2026-11-17`。
 * 空值返回 `empty`(默认空串); 认不出的格式原样返回, 不吞数据。
 */
export function formatLocalDateTimeMinute(value?: string | null, empty = ''): string {
  if (value == null || `${value}`.trim() === '') return empty;
  const m = /^(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}):(\d{2}))?/.exec(`${value}`.trim());
  if (!m) return `${value}`;
  return m[2] ? `${m[1]} ${m[2]}:${m[3]}` : m[1];
}
