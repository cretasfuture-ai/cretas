/**
 * 后端拦下来、要操作员说一个原因才能继续的几种情况 (纪律排查 A12 / A10), 前端只负责在被 409 拦下后收原因再重提。
 * ⛔ 不在前端预判要不要原因 —— 开不开缺料拦截、超没超收容差都是后端按本厂设置算的。
 * 后端用 409 + errorCode; 这里按形状取, 不依赖 axios 类型 (同 reworkRatio.ts / settlementRequired.ts)。
 */

export const REASON_OTHER = '其他';

/** 后端 ShortageStartGate.CODE: 本厂开了「缺料时拦开工」且计划的缺料还没解决。 */
export const SHORTAGE_CASE_OPEN = 'SHORTAGE_CASE_OPEN';
/** 后端 YieldReportServiceImpl: 产出超过超收容差上限 (第一次拦, 让人确认)。 */
export const OVER_RECEIPT = 'OVER_RECEIPT';
/** 后端 OverReceiptForceReason.REASON_REQUIRED: 强制超收没带原因。 */
export const OVER_RECEIPT_REASON_REQUIRED = 'OVER_RECEIPT_REASON_REQUIRED';

/** 缺料仍要开工的常见原因 (最后一个是「其他」, 选它才要写一句)。 */
export const FORCE_START_REASONS = [
  '客户急单',
  '缺的料今天能到',
  '用替代料',
  '先做不缺料的部分',
  REASON_OTHER,
] as const;

/** 超收仍要提交的常见原因。 */
export const OVER_RECEIPT_REASONS = [
  '原料出成偏高',
  '已复称确认',
  '投入量少报了',
  '工艺调整',
  REASON_OTHER,
] as const;

/** 选了原因才能继续; 「其他」必须写明。通过返回 null。 */
export function reasonBlock(reason: string, note: string): string | null {
  if (!reason) return '请先选一个原因';
  if (reason === REASON_OTHER && !note.trim()) return '选了「其他」，请写明具体原因';
  return null;
}

/** 发给后端的一句话: 「其他」用写的那句, 其余用按钮文字 (写了补充就接在后面)。 */
export function composeReason(reason: string, note: string): string {
  const extra = note.trim();
  if (reason === REASON_OTHER) return extra;
  return extra ? `${reason}：${extra}` : reason;
}

/**
 * 这个报错是不是后端「要原因才能继续」的那种: 是则返回后端写好的那句话 (含缺什么 / 超多少), 否则 null。
 */
export function blockedMessage(error: unknown, ...codes: string[]): string | null {
  const resp = (error as {
    response?: { status?: number; data?: { errorCode?: string; message?: string; actionHint?: string } };
  } | null)?.response;
  if (!resp || resp.status !== 409 || !resp.data?.errorCode || !codes.includes(resp.data.errorCode)) return null;
  return resp.data.message || resp.data.actionHint || '需要填写原因才能继续';
}
