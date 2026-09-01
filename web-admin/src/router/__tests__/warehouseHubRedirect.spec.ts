import { describe, it, expect } from 'vitest';
import type { LocationQuery } from 'vue-router';
import {
  buildInventoryQueryRedirect,
  buildStocktakeQueryRedirect,
  INVENTORY_QUERY_HUB_PATH,
  STOCKTAKE_QUERY_HUB_PATH,
} from '../warehouseHubRedirect';

const q = (o: Record<string, string>): LocationQuery => o as unknown as LocationQuery;

describe('warehouseHubRedirect — 旧页 → 库存查询/盘点 hub redirect (LIUSHANMEN IA 收拢)', () => {
  it('hub path 常量', () => {
    expect(INVENTORY_QUERY_HUB_PATH).toBe('/warehouse/inventory-query');
    expect(STOCKTAKE_QUERY_HUB_PATH).toBe('/warehouse/stocktake-query');
  });

  it('库存查询 4 个视角各 redirect 到对应 tab', () => {
    expect(buildInventoryQueryRedirect({ query: q({}) }, 'by-warehouse')).toEqual({
      path: INVENTORY_QUERY_HUB_PATH, query: { tab: 'by-warehouse' },
    });
    expect(buildInventoryQueryRedirect({ query: q({}) }, 'total')).toEqual({
      path: INVENTORY_QUERY_HUB_PATH, query: { tab: 'total' },
    });
    expect(buildInventoryQueryRedirect({ query: q({}) }, 'batch')).toEqual({
      path: INVENTORY_QUERY_HUB_PATH, query: { tab: 'batch' },
    });
    expect(buildInventoryQueryRedirect({ query: q({}) }, 'semi-finished')).toEqual({
      path: INVENTORY_QUERY_HUB_PATH, query: { tab: 'semi-finished' },
    });
  });

  it('盘点 2 个视角各 redirect 到对应 tab', () => {
    expect(buildStocktakeQueryRedirect({ query: q({}) }, 'main')).toEqual({
      path: STOCKTAKE_QUERY_HUB_PATH, query: { tab: 'main' },
    });
    expect(buildStocktakeQueryRedirect({ query: q({}) }, 'semi-finished')).toEqual({
      path: STOCKTAKE_QUERY_HUB_PATH, query: { tab: 'semi-finished' },
    });
  });

  it('保留 incoming query (e.g. 深链 ?stocktakeId=x), tab 叠加', () => {
    expect(buildStocktakeQueryRedirect({ query: q({ stocktakeId: 'SK-1' }) }, 'main')).toEqual({
      path: STOCKTAKE_QUERY_HUB_PATH, query: { stocktakeId: 'SK-1', tab: 'main' },
    });
    expect(buildInventoryQueryRedirect({ query: q({ warehouseId: 'WH-1' }) }, 'total')).toEqual({
      path: INVENTORY_QUERY_HUB_PATH, query: { warehouseId: 'WH-1', tab: 'total' },
    });
  });

  it('两个 hub 互不影响 (阴性对照: 库存查询的 redirect 不会写到盘点 hub 的路径上)', () => {
    const r = buildInventoryQueryRedirect({ query: q({}) }, 'batch');
    expect(r.path).not.toBe(STOCKTAKE_QUERY_HUB_PATH);
  });
});
