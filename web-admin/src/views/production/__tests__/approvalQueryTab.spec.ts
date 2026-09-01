import { describe, it, expect } from 'vitest';
import {
  resolveApprovalQueryTab,
  canSeeApprovalTab,
  APPROVAL_QUERY_TABS,
  APPROVAL_TAB_ROLES,
  DEFAULT_APPROVAL_QUERY_TAB,
} from '../approvalQueryTab';

describe('approvalQueryTab — 报工审批 hub tab 解析 (LIUSHANMEN IA 收拢, 2026-09-02)', () => {
  it('2 个 tab: 报工审批/撤回审批', () => {
    expect(APPROVAL_QUERY_TABS).toEqual(['approval', 'reversal']);
  });

  it('默认 tab = approval (报工审批, 不带 role 参数时按向后兼容处理)', () => {
    expect(DEFAULT_APPROVAL_QUERY_TAB).toBe('approval');
    expect(resolveApprovalQueryTab({})).toBe('approval');
    expect(resolveApprovalQueryTab(null)).toBe('approval');
    expect(resolveApprovalQueryTab(undefined)).toBe('approval');
  });

  it.each(['approval', 'reversal'] as const)('?tab=%s → %s (不带 role 参数)', (tab) => {
    expect(resolveApprovalQueryTab({ tab })).toBe(tab);
  });

  it('非法 tab → 默认 approval (不带 role 参数)', () => {
    expect(resolveApprovalQueryTab({ tab: 'whatever' })).toBe('approval');
    expect(resolveApprovalQueryTab({ tab: '' })).toBe('approval');
    expect(resolveApprovalQueryTab({ tab: ['reversal'] })).toBe('approval');
  });

  describe('canSeeApprovalTab — 与后端 @RequireRole ∪ PLATFORM_ADMIN_ROLES 逐字节等价', () => {
    it.each(APPROVAL_TAB_ROLES)('%s 在白名单里 → true', (role) => {
      expect(canSeeApprovalTab(role)).toBe(true);
    });

    it('dispatcher (production:rw 但不在白名单里) → false', () => {
      expect(canSeeApprovalTab('dispatcher')).toBe(false);
    });

    it('quality_manager (2026-09-01 缺陷当事角色) → false', () => {
      expect(canSeeApprovalTab('quality_manager')).toBe(false);
    });

    it('null/undefined/空串 → false (fail-closed)', () => {
      expect(canSeeApprovalTab(null)).toBe(false);
      expect(canSeeApprovalTab(undefined)).toBe(false);
      expect(canSeeApprovalTab('')).toBe(false);
    });
  });

  describe('resolveApprovalQueryTab 带 role 参数 —— 回落到该角色能看到的 tab (⛔ 不落到隐藏 tab)', () => {
    it('白名单角色 + 无 tab query → 默认 approval', () => {
      expect(resolveApprovalQueryTab({}, 'factory_super_admin')).toBe('approval');
    });

    it('dispatcher + 无 tab query → 回落 reversal (approval 对它不可见)', () => {
      expect(resolveApprovalQueryTab({}, 'dispatcher')).toBe('reversal');
    });

    it('dispatcher + 显式 ?tab=approval → 仍回落 reversal (⛔ 不许显式请求绕过门禁)', () => {
      expect(resolveApprovalQueryTab({ tab: 'approval' }, 'dispatcher')).toBe('reversal');
    });

    it('dispatcher + 显式 ?tab=reversal → reversal (它自己能看到的 tab, 原样放行)', () => {
      expect(resolveApprovalQueryTab({ tab: 'reversal' }, 'dispatcher')).toBe('reversal');
    });

    it('quality_manager + 无 tab query → 回落 reversal', () => {
      expect(resolveApprovalQueryTab({}, 'quality_manager')).toBe('reversal');
    });

    it('workshop_supervisor (白名单里) + 无 tab query → 默认 approval', () => {
      expect(resolveApprovalQueryTab({}, 'workshop_supervisor')).toBe('approval');
    });

    it('role 传 null (已知但拿不到身份) → 按不可见处理, 回落 reversal', () => {
      expect(resolveApprovalQueryTab({}, null)).toBe('reversal');
    });

    it('非法 tab + dispatcher → 回落 reversal (与「非法」和「不可见」两条路径都要落到同一个安全值)', () => {
      expect(resolveApprovalQueryTab({ tab: 'whatever' }, 'dispatcher')).toBe('reversal');
    });
  });
});
