import { describe, expect, it } from 'vitest';
import {
  composeSelfApprovalReason,
  isSelfApproval,
  selfApprovalReasonPayload,
  validateSelfApprovalReason,
  SELF_APPROVAL_REASON_MAX,
} from '../selfApproval';

describe('本人审批 (纪律排查 B1–B3 / B11 / B15)', () => {
  it('认出当前登录人就是提交人; 数字与字符串 ID 等价; 任一缺失视为不是本人', () => {
    expect(isSelfApproval(7, 7)).toBe(true);
    expect(isSelfApproval('7', 7)).toBe(true);
    expect(isSelfApproval(7, 8)).toBe(false);
    expect(isSelfApproval(null, 7)).toBe(false);
    expect(isSelfApproval(7, undefined)).toBe(false);
    expect(isSelfApproval('', '')).toBe(false);
  });

  it('本人审批原因不能为空、不能超长', () => {
    expect(validateSelfApprovalReason('')).toContain('必须填写原因');
    expect(validateSelfApprovalReason('   ')).toContain('必须填写原因');
    expect(validateSelfApprovalReason(undefined)).toContain('必须填写原因');
    expect(validateSelfApprovalReason('x'.repeat(SELF_APPROVAL_REASON_MAX + 1))).toContain('最多');
    expect(validateSelfApprovalReason('本厂只有我一个人')).toBeNull();
  });

  it('下拉 + 补充拼原因; 选「其他」不写补充得空串 (校验会拦)', () => {
    expect(composeSelfApprovalReason('本厂只有我一个人负责这项审批', '')).toBe('本厂只有我一个人负责这项审批');
    expect(composeSelfApprovalReason('本厂只有我一个人负责这项审批', ' 厂长出差 ')).toBe('本厂只有我一个人负责这项审批：厂长出差');
    expect(composeSelfApprovalReason('其他', '  ')).toBe('');
    expect(validateSelfApprovalReason(composeSelfApprovalReason('其他', ''))).not.toBeNull();
    expect(composeSelfApprovalReason('其他', '临时代班')).toBe('临时代班');
  });

  it('不是本人时不发原因字段', () => {
    expect(selfApprovalReasonPayload(false, '原因')).toBeUndefined();
    expect(selfApprovalReasonPayload(true, ' 原因 ')).toBe('原因');
  });
});
