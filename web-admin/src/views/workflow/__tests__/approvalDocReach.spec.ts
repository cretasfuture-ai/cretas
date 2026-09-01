import { describe, expect, it } from 'vitest';
import type { Router } from 'vue-router';
import { isRouteReachable, PEEKABLE_BY_MODULE } from '../approvalDocReach';

/**
 * `isRouteReachable` 的**行为**测试 —— 与同目录那份源码扫描闸互补。
 *
 * ⚠️ 为什么两者都要: 源码闸只能证明「代码里接上了 openApprovalDoc / OrderPeekDrawer」,
 * 它对「判定本身写错了」是瞎的 —— 把 `isRouteReachable` 改成恒 `true`,
 * 那道闸**照样全绿**, 而财务又会被送回 /403。要紧的那一维是判定的返回值, 量它。
 *
 * 缺陷 30 现场 (LIUSHANMEN 2026-09-01): `lsm009`(finance_manager) 点开自己审过的
 * `PO-20260901-0003` → 整页 /403。
 */

/** 造一个只回答 `resolve` 的假 router —— 判定只用得到这一个方法。 */
function fakeRouter(routes: Record<string, { module?: string; modulesAny?: string[] }>): Router {
  return {
    resolve(path: string) {
      const hit = routes[path];
      // matched 为空 = 这条路径不存在, 判定应当返回 false。
      return hit ? { matched: [{}], meta: hit } : { matched: [], meta: {} };
    },
  } as unknown as Router;
}

const ROUTES = {
  '/procurement/orders/PO-1': { module: 'procurement' },
  '/sales/orders/SO-1': { module: 'sales' },
  '/warehouse/stocktakes': { module: 'warehouse' },
  '/warehouse/materials': { module: 'warehouse' },
  '/finance/ar-ap': { module: 'finance' },
  '/both': { module: 'procurement', modulesAny: ['procurement', 'finance'] },
};

/** finance_manager 实测下发的是 `["finance:*"]` —— 只有 finance 一个模块。 */
const financeOnly = (m: string) => m === 'finance';
/** 采购主管: 只有 procurement。 */
const procurementOnly = (m: string) => m === 'procurement';
/** 不在 ROLE_PATH_WHITELIST 里的角色不受路径闸约束, 用它把两道闸分开量。 */
const everything = () => true;

describe('审批人能不能点开自己在审的那张单', () => {
  const router = fakeRouter(ROUTES);

  it('🔴 缺陷 30 本身: 财务主管够不着采购订单详情 (路径闸挡的)', () => {
    expect(isRouteReachable(router, 'finance_manager', '/procurement/orders/PO-1', financeOnly))
      .toBe(false);
  });

  it('阳性对照: 同一个判定对采购主管是 true —— 否则上面那条可能只是恒 false', () => {
    expect(isRouteReachable(router, 'procurement_manager', '/procurement/orders/PO-1', procurementOnly))
      .toBe(true);
  });

  it('两道闸是串联的: 模块通、但路径不在白名单里 ⇒ 仍然够不着', () => {
    // finance_manager 的白名单有 /warehouse/stocktakes, **没有** /warehouse/materials。
    // 这里刻意把 warehouse 模块给足, 让唯一的差别是路径闸。
    const warehouseToo = (m: string) => m === 'finance' || m === 'warehouse';
    expect(isRouteReachable(router, 'finance_manager', '/warehouse/stocktakes', warehouseToo))
      .toBe(true);
    expect(
      isRouteReachable(router, 'finance_manager', '/warehouse/materials', warehouseToo),
      '只查模块闸就会把这条判成可达 —— 而它在生产上是 403',
    ).toBe(false);
  });

  it('反过来: 路径闸放行、模块闸不放行 ⇒ 也够不着', () => {
    // 不在白名单表里的角色不受路径闸约束, 于是只剩模块闸在起作用。
    expect(isRouteReachable(router, 'procurement_manager', '/finance/ar-ap', procurementOnly))
      .toBe(false);
    expect(isRouteReachable(router, 'procurement_manager', '/finance/ar-ap', everything))
      .toBe(true);
  });

  it('深链的 query 要剥掉再过路径闸', () => {
    const warehouseToo = (m: string) => m === 'finance' || m === 'warehouse';
    expect(
      isRouteReachable(router, 'finance_manager', '/warehouse/stocktakes?stocktakeId=abc', warehouseToo),
      '带 ? 的深链被判成够不着 —— 说明 query 没剥, 盘点审批会误退抽屉',
    ).toBe(true);
  });

  it('modulesAny 时按 any-of 判, 与守卫一致', () => {
    expect(isRouteReachable(router, 'procurement_manager', '/both', financeOnly)).toBe(true);
    expect(isRouteReachable(router, 'procurement_manager', '/both', (m) => m === 'sales')).toBe(false);
  });

  it('路径压根不存在时返回 false, ⛔ 不许乐观放行', () => {
    expect(isRouteReachable(router, 'procurement_manager', '/nope/1', everything)).toBe(false);
  });

  it('只有采购/销售订单登记了抽屉 —— 抽屉本身也只认这两个 kind', () => {
    expect(PEEKABLE_BY_MODULE.PURCHASE_ORDER).toBe('purchase');
    expect(PEEKABLE_BY_MODULE.SALES_ORDER).toBe('sales');
    expect(PEEKABLE_BY_MODULE.INVENTORY_ADJUSTMENT).toBeUndefined();
  });
});
