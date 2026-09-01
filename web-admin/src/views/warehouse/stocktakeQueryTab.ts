/**
 * 盘点 hub tab 解析 —— LIUSHANMEN 仓储「库存盘点」菜单收拢 (owner 拍板, 2026-09-01)。
 *
 * owner 原话:「半成品盘点跟盘点与期初库存可以放在一起。」
 *
 * 把此前 2 个各自独立的盘点页合并到一个入口 (/warehouse/stocktake-query)，用
 * el-tabs 承载。?tab= query 同步 (保书签 + 旧路径 redirect 落点)，与
 * businessAnalysisTab.ts (WS4 经营分析 hub) 同模式 —— 纯函数，方便单测。
 *
 * 旧路径仍可直接访问，只重定向不删除。finance_manager 从个人 OA 待办
 * (workflow/acted.vue、workflow/pending.vue 的 INVENTORY_ADJUSTMENT 深链)
 * 打开的是旧路径 '/warehouse/stocktakes?stocktakeId=...' —— redirect 必须保留
 * incoming query，否则深链会打开 hub 默认 tab 而不是那张单据。重定向逻辑见
 * router/warehouseHubRedirect.ts + router/index.ts 的 warehouse children：
 *   '/warehouse/stocktakes'               → tab=main (默认)
 *   '/warehouse/semi-finished-stocktakes' → tab=semi-finished
 */
export type StocktakeQueryTab = 'main' | 'semi-finished';

export const STOCKTAKE_QUERY_TABS: StocktakeQueryTab[] = ['main', 'semi-finished'];

/** 默认 tab = 盘点与期初库存 (finance_manager 个人 OA 深链落点，见上)。 */
export const DEFAULT_STOCKTAKE_QUERY_TAB: StocktakeQueryTab = 'main';

/**
 * 从 ?tab= query 解析激活 tab。非法/缺省 → 默认 main。
 */
export function resolveStocktakeQueryTab(query: Record<string, unknown> | null | undefined): StocktakeQueryTab {
  const t = query?.tab;
  return STOCKTAKE_QUERY_TABS.includes(t as StocktakeQueryTab)
    ? (t as StocktakeQueryTab)
    : DEFAULT_STOCKTAKE_QUERY_TAB;
}
