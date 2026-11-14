/**
 * 迁移 P5 R35：建批次时到期日已早于今天（北京时间）——允许建，但要确认并写原因，原因记入批次。
 * 服务端 ExpiredBatchCreationGuard 同口径兜底（不写原因 409 EXPIRED_BATCH_REASON_REQUIRED）。
 */
export const EXPIRED_REASON_MIN = 2;
export const EXPIRED_REASON_MAX = 200;

/** 北京时间的日历日（工厂时区，不随浏览器时区变；不用 toISOString 截断）。 */
function beijingToday(now: Date): string {
  const bj = new Date(now.getTime() + 8 * 3600 * 1000);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${bj.getUTCFullYear()}-${p(bj.getUTCMonth() + 1)}-${p(bj.getUTCDate())}`;
}

/** 已过期天数；没过期 / 没填到期日 / 日期不合法 ⇒ 0。今天到期不算过期。 */
export function expiredDays(expireDate: string | null | undefined, now: Date): number {
  if (!expireDate || !/^\d{4}-\d{2}-\d{2}$/.test(expireDate)) return 0;
  const today = Date.parse(`${beijingToday(now)}T00:00:00Z`);
  const exp = Date.parse(`${expireDate}T00:00:00Z`);
  if (!Number.isFinite(exp)) return 0;
  const days = Math.round((today - exp) / 86400000);
  return days > 0 ? days : 0;
}

/** 字段旁的提示；没过期返回空串。 */
export function expiredHint(expireDate: string | null | undefined, now: Date): string {
  const d = expiredDays(expireDate, now);
  return d > 0 ? `该批次到期日已过 ${d} 天。仍要入库，请在下面写原因（会记在批次上）。` : '';
}

/** 原因校验：过期时必填 2~200 字；返回错误文字，通过返回空串。 */
export function expiredReasonError(expireDate: string | null | undefined, reason: string | null | undefined, now: Date): string {
  if (expiredDays(expireDate, now) === 0) return '';
  const r = (reason ?? '').trim();
  if (r.length < EXPIRED_REASON_MIN) return `该批次已过期，仍要入库请写原因（至少 ${EXPIRED_REASON_MIN} 个字）`;
  if (r.length > EXPIRED_REASON_MAX) return `原因最多 ${EXPIRED_REASON_MAX} 个字`;
  return '';
}
