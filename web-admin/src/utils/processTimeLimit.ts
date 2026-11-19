/**
 * 工序时限的显示口径 (食品 DAG 缺口 14)。能不能领由后端判定, 这里只算给人看的文字。
 */
import type { ProcessTimeLimitState, ProcessTimeLimitStatus } from '@/api/processTimeLimit';

/**
 * 后端时间是不带时区的东八区本地时间 (LocalDateTime), ⛔ 不用 `new Date(str)`:
 * 那会按设备时区解读, 设备不在东八区时倒计时差出几个小时。
 */
export function parseBackendTime(value: string | null | undefined): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/.exec(value ?? '');
  if (!match) return null;
  const [, y, mo, d, h, mi, s] = match;
  return Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h) - 8, Number(mi), Number(s ?? 0));
}

/** 只给人看: `2026-11-04T14:20:05.123` → `2026-11-04 14:20`。 */
export function formatBackendTime(value: string | null | undefined): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '—';
}

/** 还剩多少分钟; 已超时为负; 还没起算为 null。 */
export function remainingMinutesAt(
  status: Pick<ProcessTimeLimitStatus, 'deadlineAt' | 'remainingMinutes' | 'paused'>,
  nowMs: number,
): number | null {
  // 暂停中后端把截止时间推到「读取那一刻」, 时钟是停的: 按它倒数会把暂停的料数到超时。
  if (status.paused) return status.remainingMinutes;
  const deadline = parseBackendTime(status.deadlineAt);
  if (deadline == null) return status.remainingMinutes;
  const diff = deadline - nowMs;
  return diff >= 0 ? Math.floor(diff / 60000) : -Math.ceil(-diff / 60000);
}

/** `80` → `1 小时 20 分`; `35` → `35 分钟`; `120` → `2 小时`。 */
export function formatDuration(minutes: number): string {
  const total = Math.abs(minutes);
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  if (hours === 0) return `${rest} 分钟`;
  return rest === 0 ? `${hours} 小时` : `${hours} 小时 ${rest} 分`;
}

export function remainingText(minutes: number | null): string | null {
  if (minutes == null) return null;
  if (minutes < 0) return `已超时 ${formatDuration(minutes)}`;
  return minutes === 0 ? '还剩不到 1 分钟' : `还剩 ${formatDuration(minutes)}`;
}

export const TIME_LIMIT_STATE_LABELS: Record<ProcessTimeLimitState, string> = {
  NOT_STARTED: '还没开始计时',
  START_MISSING: '要先登记解冻',
  OK: '时限内',
  WARNING: '快到时限',
  EXPIRED: '已超时',
  RELEASED: '已复检放行',
};

export const TIME_LIMIT_STATE_TAGS: Record<ProcessTimeLimitState, 'info' | 'success' | 'warning' | 'danger'> = {
  NOT_STARTED: 'info',
  START_MISSING: 'danger',
  OK: 'success',
  WARNING: 'warning',
  EXPIRED: 'danger',
  RELEASED: 'success',
};

/** 上次读到还在时限内, 本地倒数已过截止时间: 该重新问后端了。 */
export function crossedDeadline(status: ProcessTimeLimitStatus, nowMs: number): boolean {
  if (status.state !== 'OK' && status.state !== 'WARNING') return false;
  const minutes = remainingMinutesAt(status, nowMs);
  return minutes != null && minutes < 0;
}

/** 本厂超时是否拦领用 (PROTO-Q41)。旧后端不带字段 = 原行为 (拦)。 */
export function timeLimitBlocks(status: Pick<ProcessTimeLimitStatus, 'blockOnExceed'>): boolean {
  return status.blockOnExceed !== false;
}

/** 后端成功响应里带回的投料时限警告 (errorCode = TIME_LIMIT_WARNING); 没有返回 null。 */
export function timeLimitWarningOf(body: unknown): string | null {
  const data = (body ?? {}) as { success?: unknown; errorCode?: unknown; actionHint?: unknown };
  if (data.success !== true || data.errorCode !== 'TIME_LIMIT_WARNING') return null;
  return typeof data.actionHint === 'string' && data.actionHint.trim() ? data.actionHint : null;
}

/** 按笔算时只有一部分超时: 要把能领的和超时的分开写。 */
export function hasPartialExpiry(status: ProcessTimeLimitStatus): boolean {
  return status.perLot && Number(status.expiredQuantity ?? 0) > 0 && Number(status.drawableQuantity ?? 0) > 0;
}

/** 只警告档的复盘记录 (PROTO-Q41): 判不合格只记结论、不动库存。 */
export function isReviewRecord(item: { recordKind?: string | null }): boolean {
  return item.recordKind === 'WARN_EXCEEDED' || item.recordKind === 'WARN_START_MISSING';
}

/** 记录类型给人看的一句; 被拦的原有记录返回 null (不用多说)。 */
export function recordKindText(item: { recordKind?: string | null }): string | null {
  if (item.recordKind === 'WARN_EXCEEDED') return '只警告档的复盘记录：超时后照常投料了';
  if (item.recordKind === 'WARN_START_MISSING') return '只警告档的复盘记录：没登记开始解冻就投料了';
  return null;
}

export const REVIEW_REJECT_TIP = '这是复盘记录，判不合格只记结论，不会自动报废库存；剩余的料要报损请到「报损管理」（App 为「报损单」）提交';
export const START_MISSING_RELEASE_TIP = '放行只记复盘结论，这批料仍按时限计时；请补登开始解冻';
