import { describe, it, expect } from 'vitest';
import {
  resolveStocktakeQueryTab,
  STOCKTAKE_QUERY_TABS,
  DEFAULT_STOCKTAKE_QUERY_TAB,
} from '../stocktakeQueryTab';

describe('stocktakeQueryTab — 盘点 hub tab 解析 (LIUSHANMEN IA 收拢, 2026-09-01)', () => {
  it('2 个 tab: main(盘点与期初库存)/semi-finished(半成品盘点)', () => {
    expect(STOCKTAKE_QUERY_TABS).toEqual(['main', 'semi-finished']);
  });

  it('默认 tab = main (finance_manager 个人 OA 深链落点)', () => {
    expect(DEFAULT_STOCKTAKE_QUERY_TAB).toBe('main');
    expect(resolveStocktakeQueryTab({})).toBe('main');
    expect(resolveStocktakeQueryTab(null)).toBe('main');
    expect(resolveStocktakeQueryTab(undefined)).toBe('main');
  });

  it.each(['main', 'semi-finished'] as const)('?tab=%s → %s', (tab) => {
    expect(resolveStocktakeQueryTab({ tab })).toBe(tab);
  });

  it('非法 tab → 默认 main', () => {
    expect(resolveStocktakeQueryTab({ tab: 'whatever' })).toBe('main');
    expect(resolveStocktakeQueryTab({ tab: '' })).toBe('main');
    expect(resolveStocktakeQueryTab({ tab: ['semi-finished'] })).toBe('main');
  });
});
