/**
 * 销售报表共用格式化。后端「不知道」给 null, 这里统一显示「—」, ⛔ 不当 0 显示。
 */
import { getErrorMessage } from '@/utils/errorToast';

export function money(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `¥ ${Number(value).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

/** 0.123 → "12.3%"; null → "—" */
export function pct(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${(value * 100).toFixed(1)}%`;
}

/** 增长率带正负号: 0.123 → "+12.3%" */
export function signedPct(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const s = (value * 100).toFixed(1);
  return value > 0 ? `+${s}%` : `${s}%`;
}

export function rateClass(value: number | null | undefined): string {
  if (value === null || value === undefined || value === 0) return '';
  return value > 0 ? 'rate-up' : 'rate-down';
}

/**
 * pythonFetch 失败时只带 "Python service error: <status> <text>", 拿不到后端 detail,
 * 这里按状态码给出可操作的说明 (页面上用常驻的 el-alert 展示, 不用一闪而过的 toast)。
 */
export function reportErrorText(e: unknown, forbiddenText?: string): string {
  const msg = getErrorMessage(e, '未知错误');
  if (/\b403\b/.test(msg)) return forbiddenText ?? '没有查看该报表的权限,请联系管理员开通';
  if (/\b400\b/.test(msg)) return '查询条件不合法,请检查日期范围后重试';
  if (/\b401\b/.test(msg)) return '登录已过期,请重新登录';
  if (/timed out/i.test(msg)) return '查询超时,请缩小日期范围后重试';
  return `加载失败:${msg}`;
}

/** 默认日期范围: 本月 1 日 ~ 今天 (按本地日历日, 不用 toISOString 截断) */
export function monthToDateRange(): [string, string] {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const y = now.getFullYear();
  const m = pad(now.getMonth() + 1);
  return [`${y}-${m}-01`, `${y}-${m}-${pad(now.getDate())}`];
}

export function currentYearMonth(): string {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}
