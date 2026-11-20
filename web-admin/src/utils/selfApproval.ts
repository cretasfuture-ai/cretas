/**
 * 本人审批 (纪律排查 B1–B3 / B11 / B15, 后端 PR #1580)。
 *
 * 工厂在「工厂配置」里打开「允许本人审批」后, 提交人可以审批自己的单, 但每次都必须写原因,
 * 后端在审批记录上留痕。开关关着时后端照旧拒绝本人审批 (四眼)。
 *
 * 前端只负责: 认出「当前登录人就是提交人」, 在审批对话框里给出原因输入框并在提交前拦空。
 * 开关开没开以后端为准 —— 没开时后端返回原有的四眼错误, 由 errorToast 原样展示。
 */

export const SELF_APPROVAL_REASON_MAX = 500;

/** 后端「开关开了但没写原因」的错误码 (FactorySelfApprovalGate.REASON_REQUIRED)。 */
export const SELF_APPROVAL_REASON_REQUIRED = 'SELF_APPROVAL_REASON_REQUIRED';

/** 常见的本人审批原因 (防呆 Rule 3); 选「其他」必须补充文字。 */
export const SELF_APPROVAL_PRESET_REASONS = [
  '本厂只有我一个人负责这项审批',
  '其他审批人长期不在岗（休假 / 出差）',
  '紧急处理，事后由他人复核',
  '其他',
] as const;

/** 下拉 + 补充 → 最终原因。选「其他」且没写补充时返回空串 (校验会拦)。 */
export function composeSelfApprovalReason(selected: string, extra: string): string {
  const more = (extra ?? '').trim();
  if (selected === '其他') return more;
  return more ? `${selected}：${more}` : selected;
}

type Id = number | string | null | undefined;

/** 当前登录人是不是这张单的提交人。任一方缺失时视为不是 (不弹原因框, 后端仍会把关)。 */
export function isSelfApproval(submitterId: Id, currentUserId: Id): boolean {
  if (submitterId === null || submitterId === undefined || submitterId === '') return false;
  if (currentUserId === null || currentUserId === undefined || currentUserId === '') return false;
  return String(submitterId) === String(currentUserId);
}

/** 提交前校验本人审批原因; 返回错误文案, 合格返回 null。 */
export function validateSelfApprovalReason(reason: string | null | undefined): string | null {
  const trimmed = (reason ?? '').trim();
  if (!trimmed) return '这是你自己提交的单，本人审批必须填写原因';
  if (trimmed.length > SELF_APPROVAL_REASON_MAX) return `本人审批原因最多 ${SELF_APPROVAL_REASON_MAX} 字`;
  return null;
}

/** 只在本人审批时带上原因字段, 不是本人时不发 (后端忽略, 也不留痕)。 */
export function selfApprovalReasonPayload(self: boolean, reason: string | null | undefined): string | undefined {
  return self ? (reason ?? '').trim() : undefined;
}
