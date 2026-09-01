import { describe, it, expect } from 'vitest';
import {
  resolveProcessConfigTab,
  PROCESS_CONFIG_TABS,
  DEFAULT_PROCESS_CONFIG_TAB,
} from '../processConfigTab';

describe('processConfigTab — 工序 hub tab 解析 (LIUSHANMEN IA 收拢, 2026-09-02)', () => {
  it('2 个 tab: 工序管理/产品-工序配置', () => {
    expect(PROCESS_CONFIG_TABS).toEqual(['work-processes', 'product-processes']);
  });

  it('默认 tab = work-processes (工序管理)', () => {
    expect(DEFAULT_PROCESS_CONFIG_TAB).toBe('work-processes');
    expect(resolveProcessConfigTab({})).toBe('work-processes');
    expect(resolveProcessConfigTab(null)).toBe('work-processes');
    expect(resolveProcessConfigTab(undefined)).toBe('work-processes');
  });

  it.each(['work-processes', 'product-processes'] as const)('?tab=%s → %s', (tab) => {
    expect(resolveProcessConfigTab({ tab })).toBe(tab);
  });

  it('非法 tab → 默认 work-processes', () => {
    expect(resolveProcessConfigTab({ tab: 'whatever' })).toBe('work-processes');
    expect(resolveProcessConfigTab({ tab: '' })).toBe('work-processes');
    expect(resolveProcessConfigTab({ tab: ['product-processes'] })).toBe('work-processes');
  });
});
