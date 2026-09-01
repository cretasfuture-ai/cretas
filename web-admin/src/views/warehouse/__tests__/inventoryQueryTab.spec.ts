import { describe, it, expect } from 'vitest';
import {
  resolveInventoryQueryTab,
  INVENTORY_QUERY_TABS,
  DEFAULT_INVENTORY_QUERY_TAB,
} from '../inventoryQueryTab';

describe('inventoryQueryTab — 库存查询 hub tab 解析 (LIUSHANMEN IA 收拢, 2026-09-01)', () => {
  it('4 个 tab: 分仓/总量/批次/半成品', () => {
    expect(INVENTORY_QUERY_TABS).toEqual(['by-warehouse', 'total', 'batch', 'semi-finished']);
  });

  it('默认 tab = by-warehouse (分仓库存查询)', () => {
    expect(DEFAULT_INVENTORY_QUERY_TAB).toBe('by-warehouse');
    expect(resolveInventoryQueryTab({})).toBe('by-warehouse');
    expect(resolveInventoryQueryTab(null)).toBe('by-warehouse');
    expect(resolveInventoryQueryTab(undefined)).toBe('by-warehouse');
  });

  it.each(['by-warehouse', 'total', 'batch', 'semi-finished'] as const)('?tab=%s → %s', (tab) => {
    expect(resolveInventoryQueryTab({ tab })).toBe(tab);
  });

  it('非法 tab → 默认 by-warehouse', () => {
    expect(resolveInventoryQueryTab({ tab: 'whatever' })).toBe('by-warehouse');
    expect(resolveInventoryQueryTab({ tab: '' })).toBe('by-warehouse');
    expect(resolveInventoryQueryTab({ tab: ['total'] })).toBe('by-warehouse');
  });
});
