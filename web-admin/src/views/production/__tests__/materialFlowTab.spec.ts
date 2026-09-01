import { describe, it, expect } from 'vitest';
import {
  resolveMaterialFlowTab,
  MATERIAL_FLOW_TABS,
  DEFAULT_MATERIAL_FLOW_TAB,
} from '../materialFlowTab';

describe('materialFlowTab — 领料 hub tab 解析 (LIUSHANMEN IA 收拢, 2026-09-02)', () => {
  it('2 个 tab: 领料单/退料记录', () => {
    expect(MATERIAL_FLOW_TABS).toEqual(['requisitions', 'returns']);
  });

  it('默认 tab = requisitions (领料单)', () => {
    expect(DEFAULT_MATERIAL_FLOW_TAB).toBe('requisitions');
    expect(resolveMaterialFlowTab({})).toBe('requisitions');
    expect(resolveMaterialFlowTab(null)).toBe('requisitions');
    expect(resolveMaterialFlowTab(undefined)).toBe('requisitions');
  });

  it.each(['requisitions', 'returns'] as const)('?tab=%s → %s', (tab) => {
    expect(resolveMaterialFlowTab({ tab })).toBe(tab);
  });

  it('非法 tab → 默认 requisitions', () => {
    expect(resolveMaterialFlowTab({ tab: 'whatever' })).toBe('requisitions');
    expect(resolveMaterialFlowTab({ tab: '' })).toBe('requisitions');
    expect(resolveMaterialFlowTab({ tab: ['returns'] })).toBe('requisitions');
  });
});
