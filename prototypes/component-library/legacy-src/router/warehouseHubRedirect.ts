/**
 * LIUSHANMEN 仓储「库存盘点」菜单收拢 (2026-09-01, owner 拍板) — 库存查询/盘点
 * hub redirect 构造器 (保书签)。
 *
 * 6 个旧的散落页 (分仓库存查询/总库存查询/库存批次查询/半成品库存/盘点与期初库存/
 * 半成品盘点) 合并进 2 个 hub 后，旧路径用 function-form redirect 桥接，**必须
 * 保留 incoming query** (e.g. finance_manager 从个人 OA 深链
 * /warehouse/stocktakes?stocktakeId=x)。这层是纯函数，方便单测。
 *
 * 与 analysisHubRedirect.ts (WS4 经营分析 hub) 同模式。
 */
import type { LocationQuery, LocationQueryRaw } from 'vue-router';
import type { InventoryQueryTab } from '@/views/warehouse/inventoryQueryTab';
import type { StocktakeQueryTab } from '@/views/warehouse/stocktakeQueryTab';

export const INVENTORY_QUERY_HUB_PATH = '/warehouse/inventory-query';
export const STOCKTAKE_QUERY_HUB_PATH = '/warehouse/stocktake-query';

export interface RedirectTarget {
  path: string;
  query: LocationQueryRaw;
}

/** 构造一个保留 incoming query 并叠加 `tab` 的库存查询 hub redirect 目标。 */
export function buildInventoryQueryRedirect(
  to: { query: LocationQuery },
  tab: InventoryQueryTab,
): RedirectTarget {
  return { path: INVENTORY_QUERY_HUB_PATH, query: { ...to.query, tab } };
}

/** 构造一个保留 incoming query 并叠加 `tab` 的盘点 hub redirect 目标。 */
export function buildStocktakeQueryRedirect(
  to: { query: LocationQuery },
  tab: StocktakeQueryTab,
): RedirectTarget {
  return { path: STOCKTAKE_QUERY_HUB_PATH, query: { ...to.query, tab } };
}
