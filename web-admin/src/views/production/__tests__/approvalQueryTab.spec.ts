import { describe, it, expect } from 'vitest';
import {
  resolveApprovalQueryTab,
  APPROVAL_QUERY_TABS,
  DEFAULT_APPROVAL_QUERY_TAB,
} from '../approvalQueryTab';

describe('approvalQueryTab — 报工审批 hub tab 解析 (LIUSHANMEN IA 收拢, 2026-09-02)', () => {
  it('2 个 tab: 报工审批/撤回审批', () => {
    expect(APPROVAL_QUERY_TABS).toEqual(['approval', 'reversal']);
  });

  it('默认 tab = approval (报工审批)', () => {
    expect(DEFAULT_APPROVAL_QUERY_TAB).toBe('approval');
    expect(resolveApprovalQueryTab({})).toBe('approval');
    expect(resolveApprovalQueryTab(null)).toBe('approval');
    expect(resolveApprovalQueryTab(undefined)).toBe('approval');
  });

  it.each(['approval', 'reversal'] as const)('?tab=%s → %s', (tab) => {
    expect(resolveApprovalQueryTab({ tab })).toBe(tab);
  });

  it('非法 tab → 默认 approval', () => {
    expect(resolveApprovalQueryTab({ tab: 'whatever' })).toBe('approval');
    expect(resolveApprovalQueryTab({ tab: '' })).toBe('approval');
    expect(resolveApprovalQueryTab({ tab: ['reversal'] })).toBe('approval');
  });
});
