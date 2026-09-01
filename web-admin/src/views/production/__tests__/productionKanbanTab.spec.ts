import { describe, it, expect } from 'vitest';
import {
  resolveProductionKanbanTab,
  PRODUCTION_KANBAN_TABS,
  DEFAULT_PRODUCTION_KANBAN_TAB,
} from '../productionKanbanTab';

describe('productionKanbanTab — 生产看板 hub tab 解析 (LIUSHANMEN IA 收拢, 2026-09-02)', () => {
  it('3 个 tab: 未完成计划队列/备货看板/交货预警', () => {
    expect(PRODUCTION_KANBAN_TABS).toEqual(['pending-queue', 'restock-board', 'delivery-warnings']);
  });

  it('默认 tab = pending-queue (未完成计划队列)', () => {
    expect(DEFAULT_PRODUCTION_KANBAN_TAB).toBe('pending-queue');
    expect(resolveProductionKanbanTab({})).toBe('pending-queue');
    expect(resolveProductionKanbanTab(null)).toBe('pending-queue');
    expect(resolveProductionKanbanTab(undefined)).toBe('pending-queue');
  });

  it.each(['pending-queue', 'restock-board', 'delivery-warnings'] as const)('?tab=%s → %s', (tab) => {
    expect(resolveProductionKanbanTab({ tab })).toBe(tab);
  });

  it('非法 tab → 默认 pending-queue', () => {
    expect(resolveProductionKanbanTab({ tab: 'whatever' })).toBe('pending-queue');
    expect(resolveProductionKanbanTab({ tab: '' })).toBe('pending-queue');
    expect(resolveProductionKanbanTab({ tab: ['restock-board'] })).toBe('pending-queue');
  });
});
