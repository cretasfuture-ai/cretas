/**
 * 缺口 03 回料比例上限: 前端只收集原因, 占比和是否超限都由后端 ReworkRatioCheck 裁决。
 */

/** 后端 ReworkRatioCheck.ERROR_CODE: 超限没原因 / 选了「其他」没写说明。 */
export const REWORK_RATIO_REASON_REQUIRED = 'REWORK_RATIO_REASON_REQUIRED';

/** 与后端 ReworkRatioCheck.STANDARD_REASONS 同一份, 改一边要改另一边。 */
export const REWORK_REASON_OTHER = '其他';
export const REWORK_REASONS = ['库存回料需尽快消化', '按主管口头调整', '主料不足', '品质允许', REWORK_REASON_OTHER] as const;

export interface ReworkRatioInput {
  reason: string;
  note?: string;
}

/** 选了原因才能继续; 「其他」必须写明。通过返回 null。 */
export function reworkReasonBlockReason(reason: string, note: string): string | null {
  if (!reason) return '请先选一个超限原因';
  if (reason === REWORK_REASON_OTHER && !note.trim()) return '选了「其他」，请写明具体原因';
  return null;
}

/** 从 submitRow 抛出的错误里取后端错误码。 */
export function errorCodeOf(e: unknown): string {
  return typeof e === 'object' && e != null && 'code' in e ? String((e as { code?: unknown }).code ?? '') : '';
}
