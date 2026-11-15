/**
 * 生产看板 hub tab 解析 —— LIUSHANMEN 生产管理 IA 收拢 (owner 拍板, 2026-09-02)。
 *
 * 把「未完成计划队列」+「备货看板」+「交货预警」合并到一个入口 (/production/kanban)。
 * ?tab= query 同步，与 inventoryQueryTab.ts 同模式 —— 纯函数，方便单测。
 *
 * 旧路径仍可直接访问，只重定向不删除。三个原页面内容原样未改动 —— 见 hub 组件头部说明。
 */
export type ProductionKanbanTab = 'pending-queue' | 'restock-board' | 'delivery-warnings';

export const PRODUCTION_KANBAN_TABS: ProductionKanbanTab[] = [
  'pending-queue', 'restock-board', 'delivery-warnings',
];

/** 默认 tab = 未完成计划队列 (原 /production/pending-queue，合并前最常用)。 */
export const DEFAULT_PRODUCTION_KANBAN_TAB: ProductionKanbanTab = 'pending-queue';

/**
 * 从 ?tab= query 解析激活 tab。非法/缺省 → 默认 pending-queue。
 */
export function resolveProductionKanbanTab(query: Record<string, unknown> | null | undefined): ProductionKanbanTab {
  const t = query?.tab;
  return PRODUCTION_KANBAN_TABS.includes(t as ProductionKanbanTab)
    ? (t as ProductionKanbanTab)
    : DEFAULT_PRODUCTION_KANBAN_TAB;
}
