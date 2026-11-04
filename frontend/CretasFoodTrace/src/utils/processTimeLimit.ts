/**
 * 工序时限的显示口径 (食品 DAG 缺口 14)。能不能领由后端判定, 这里只算给操作员看的文字。
 * 与 web-admin/src/utils/processTimeLimit.ts 同一口径。
 */
import type { ProcessTimeLimitStatus } from '../services/api/processTimeLimitApi';

/**
 * 后端时间是不带时区的东八区本地时间 (LocalDateTime), ⛔ 不用 `new Date(str)`:
 * 那会按手机时区解读, 手机不在东八区时倒计时差出几个小时。
 */
export function parseBackendTime(value: string | null | undefined): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/.exec(value ?? '');
  if (!match) return null;
  return Date.UTC(
    Number(match[1]), Number(match[2]) - 1, Number(match[3]),
    Number(match[4]) - 8, Number(match[5]), Number(match[6] ?? 0),
  );
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

/** 这批料因时限现在一点也领不了的原因 (null = 能领, 能领多少看 timeLimitDrawable)。 */
export function timeLimitBlockReason(status: ProcessTimeLimitStatus | undefined, nowMs: number): string | null {
  if (!status) return null;
  if (status.state === 'START_MISSING') return status.reason ?? '还没有登记开始解冻，不能领用';
  if (status.state !== 'EXPIRED' || Number(status.drawableQuantity ?? 0) > 0) return null;
  const minutes = remainingMinutesAt(status, nowMs);
  return minutes != null && minutes < 0
    ? `已超时 ${formatDuration(minutes)}，请找质检员处置`
    : '已超时，请找质检员处置';
}

/** 时限允许领的量; null = 时限不限量 (没有超时的部分)。 */
export function timeLimitDrawable(status: ProcessTimeLimitStatus | undefined): number | null {
  if (!status || (status.state !== 'EXPIRED' && Number(status.expiredQuantity ?? 0) <= 0)) return null;
  return Math.max(0, Number(status.drawableQuantity ?? 0));
}

/** 按笔算时已超时的那部分; 0 = 没有。 */
export function timeLimitExpiredQuantity(status: ProcessTimeLimitStatus | undefined): number {
  return Math.max(0, Number(status?.expiredQuantity ?? 0));
}

/** 行下面那一句时间说明; null = 不用说。文字自带意思, 不靠颜色。 */
export function timeLimitLine(status: ProcessTimeLimitStatus | undefined, nowMs: number): string | null {
  if (!status) return null;
  if (status.state === 'NOT_STARTED' || status.state === 'RELEASED') return status.reason;
  const minutes = remainingMinutesAt(status, nowMs);
  if (minutes == null) return status.reason;
  if (minutes < 0) return status.state === 'EXPIRED' ? null : '时限已到，能不能领以提交结果为准';
  const left = minutes === 0 ? '还剩不到 1 分钟' : `还剩 ${formatDuration(minutes)}`;
  if (status.paused) return `${left}（冷藏暂停中，暂不计时）`;
  return status.state === 'WARNING' ? `${left}，快到时限` : left;
}

/** 上次读到还在时限内, 本地倒数已过截止时间: 该重新问后端了。 */
export function crossedDeadline(status: ProcessTimeLimitStatus, nowMs: number): boolean {
  if (status.state !== 'OK' && status.state !== 'WARNING') return false;
  const minutes = remainingMinutesAt(status, nowMs);
  return minutes != null && minutes < 0;
}

/** 后端的下一步提示: 统一响应里的字段叫 actionHint; hint 是旧写法。 */
export function backendActionHint(body: unknown): string {
  const data = (body ?? {}) as { actionHint?: string | null; hint?: string | null };
  return data.actionHint ?? data.hint ?? '';
}
