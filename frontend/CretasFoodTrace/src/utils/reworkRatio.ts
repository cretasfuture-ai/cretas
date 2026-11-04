/**
 * 缺口 03「回料比例上限」(App 报工路径): 判定在后端 ReworkRatioCheck (占比 = 回料 / (本道投入 − 回料)),
 * 前端只负责在被后端 409 拦下后收集超限原因再重提 —— 不在前端预算占比。
 *
 * 与 web-admin 的 processSheet/reworkRatio.ts 同口径。
 */

/** 后端 ReworkRatioCheck.ERROR_CODE: 超限没原因 / 选了「其他」没写说明。 */
export const REWORK_RATIO_REASON_REQUIRED = 'REWORK_RATIO_REASON_REQUIRED';

export const REWORK_REASON_OTHER = '其他';
/** 与后端 ReworkRatioCheck.STANDARD_REASONS 同一份, 改一边要改另一边。 */
export const REWORK_REASONS = [
  '库存回料需尽快消化',
  '按主管口头调整',
  '主料不足',
  '品质允许',
  REWORK_REASON_OTHER,
] as const;

export interface ReworkRatioInput {
  reason: string;
  note?: string;
}

/** 选了原因才能继续; 「其他」必须写明。通过返回 null。 */
export function reworkReasonBlockReason(reason: string, note: string): string | null {
  if (!reason) return '请先选一个原因';
  if (reason === REWORK_REASON_OTHER && !note.trim()) return '选了「其他」，请写明具体原因';
  return null;
}

/**
 * 提交报错是不是「回料超限需要原因」: 是则返回后端写好的那句话 (含占比和上限), 否则 null。
 * 后端用 409 + errorCode; 这里按形状取, 不依赖 axios 类型。
 */
export function reworkReasonRequiredMessage(error: unknown): string | null {
  const resp = (error as { response?: { status?: number; data?: { errorCode?: string; message?: string } } } | null)
    ?.response;
  if (!resp || resp.status !== 409 || resp.data?.errorCode !== REWORK_RATIO_REASON_REQUIRED) return null;
  return resp.data.message || '回料占比超过该产品上限，必须填写超限原因';
}
