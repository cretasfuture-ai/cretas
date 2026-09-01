/**
 * 库存查询 hub tab 解析 —— LIUSHANMEN 仓储「库存盘点」菜单收拢 (owner 拍板, 2026-09-01)。
 *
 * owner 原话:「这个分仓库存查询跟总库存查询还有库存批次查询我觉得可以放在一起，
 * 然后半成品库存也可以放在一起。」
 *
 * 把此前 4 个各自独立的库存查询页合并到一个入口 (/warehouse/inventory-query)，
 * 用 el-tabs 承载。?tab= query 同步 (保书签 + 旧路径 redirect 落点)，与
 * businessAnalysisTab.ts (WS4 经营分析 hub) 同模式 —— 纯函数，方便单测。
 *
 * 旧路径仍可直接访问，只重定向不删除 (存量深链/书签; LIUSHANMEN 仓储主管天天用)。
 * 重定向逻辑见 router/warehouseHubRedirect.ts + router/index.ts 的 warehouse/
 * inventory children：
 *   '/inventory/by-warehouse'    → tab=by-warehouse (默认)
 *   '/warehouse/inventory-total' → tab=total
 *   '/warehouse/inventory'       → tab=batch
 *   '/warehouse/semi-finished'   → tab=semi-finished
 */
export type InventoryQueryTab = 'by-warehouse' | 'total' | 'batch' | 'semi-finished';

export const INVENTORY_QUERY_TABS: InventoryQueryTab[] = ['by-warehouse', 'total', 'batch', 'semi-finished'];

/** 默认 tab = 分仓库存查询 (PR#309 B2 原始入口，合并前最常用)。 */
export const DEFAULT_INVENTORY_QUERY_TAB: InventoryQueryTab = 'by-warehouse';

/**
 * 从 ?tab= query 解析激活 tab。非法/缺省 → 默认 by-warehouse。
 */
export function resolveInventoryQueryTab(query: Record<string, unknown> | null | undefined): InventoryQueryTab {
  const t = query?.tab;
  return INVENTORY_QUERY_TABS.includes(t as InventoryQueryTab)
    ? (t as InventoryQueryTab)
    : DEFAULT_INVENTORY_QUERY_TAB;
}
