import { describe, it, expect } from 'vitest';
import router from '@/router';

// 预热: 静态 import 两个 hub 组件, 让 vitest 在**文件加载阶段**(不计入单条测试的
// timeout)就转译完它们及其内嵌的 4/2 个真实大页面 (stocktakes/index.vue 2226 行
// 等)。router.push() 命中同一个模块时走缓存, 不再重新转译。
// ⚠️ 这不是可选的性能优化 —— 没有它, 全量套件并发跑（多 worker 抢 CPU）时,
// 首次 push 触发的冷转译实测能超过 20000ms 单测超时 (2026-09-01 实测: 单独跑
// 该文件 5477ms 通过, 混进全量套件后同一条超过 20000ms 判超时, 同一份代码,
// 差的只是并发负载)。
import '@/views/warehouse/inventory-query/index.vue';
import '@/views/warehouse/stocktake-query/index.vue';

/**
 * 2026-09-01 LIUSHANMEN 仓储「库存盘点」菜单收拢 (owner 拍板) —— 硬约束 2:
 * 「Old URLs must have a defined behavior.」六个旧路径不删，只重定向；本文件
 * 钉住每一条的落点与 ?tab=，并证明深链 query (finance_manager 从个人 OA 打开
 * 的 stocktakeId、盘点页的 openEntry) 在重定向之后原样保留。
 *
 * 用真实的 `@/router` 单例 (与 menuConfig.spec.ts 同一份), 不是自建的最小路由表
 * ——一份路由表, 不会漂 (形态 D)。它在 import 时不挂 beforeEach 守卫
 * (setupRouterGuards 只在 main.ts 里手动调用, 见 router/index.ts 尾部注释),
 * 所以这里的 push() 只测**路由匹配 + redirect 解析**这一层, 不含认证/RBAC ——
 * RBAC 那一层由 personalOaAccess.spec.ts / isPathAllowedForRole.spec.ts 单独钉。
 *
 * ⚠️ vue-router 4 的 redirect 在 `beforeEach` 之前就已经解析完 —— 已用 scratch
 * probe 验证 (createMemoryHistory 的最小路由表: beforeEach 只看得到重定向后的
 * 路径, 不会看到原始 /old)。这里同理: push('/old-path') 之后
 * router.currentRoute.value.path 就是重定向后的最终路径。
 */
describe('仓储库存查询/盘点 hub —— 旧路径 redirect (LIUSHANMEN IA 收拢)', () => {
  const cases: Array<{ from: string; toPath: string; tab: string }> = [
    { from: '/inventory/by-warehouse', toPath: '/warehouse/inventory-query', tab: 'by-warehouse' },
    { from: '/warehouse/inventory-total', toPath: '/warehouse/inventory-query', tab: 'total' },
    { from: '/warehouse/inventory', toPath: '/warehouse/inventory-query', tab: 'batch' },
    { from: '/warehouse/semi-finished', toPath: '/warehouse/inventory-query', tab: 'semi-finished' },
    { from: '/warehouse/stocktakes', toPath: '/warehouse/stocktake-query', tab: 'main' },
    { from: '/warehouse/semi-finished-stocktakes', toPath: '/warehouse/stocktake-query', tab: 'semi-finished' },
  ];

  // 20s: 首次 push 命中的 hub 路由要动态 import 一个内嵌 4 个/2 个真实大页面
  // (stocktakes/index.vue 2226 行等) 的容器组件, 冷启动转译比默认 5s 超时慢。
  it.each(cases)('$from → $toPath?tab=$tab', async ({ from, toPath, tab }) => {
    await router.push(from);
    expect(router.currentRoute.value.path).toBe(toPath);
    expect(router.currentRoute.value.query.tab).toBe(tab);
    expect(router.currentRoute.value.redirectedFrom?.path).toBe(from);
  }, 20000);

  it('/inventory (父路径, 不带 by-warehouse) 也落到库存查询 hub 的分仓 tab', async () => {
    await router.push('/inventory');
    expect(router.currentRoute.value.path).toBe('/warehouse/inventory-query');
    expect(router.currentRoute.value.query.tab).toBe('by-warehouse');
  });

  it('阳性对照: 没有 redirect 的普通页面 push 后路径不变 (证明上面的断言不是路由器的默认行为)', async () => {
    await router.push('/warehouse/materials');
    expect(router.currentRoute.value.path).toBe('/warehouse/materials');
    expect(router.currentRoute.value.redirectedFrom).toBeUndefined();
  });

  it('深链 query 必须原样透传 —— finance_manager 从个人 OA 打开的 stocktakeId', async () => {
    await router.push('/warehouse/stocktakes?stocktakeId=SK-9001');
    expect(router.currentRoute.value.path).toBe('/warehouse/stocktake-query');
    expect(router.currentRoute.value.query.tab).toBe('main');
    expect(router.currentRoute.value.query.stocktakeId).toBe('SK-9001');
  });

  it('深链 query 必须原样透传 —— materials/list.vue 的 openEntry=1 入口', async () => {
    await router.push('/warehouse/stocktakes?openEntry=1');
    expect(router.currentRoute.value.query.tab).toBe('main');
    expect(router.currentRoute.value.query.openEntry).toBe('1');
  });

  it('多个 query 参数同时保留 (阳性对照: 不是只保留了第一个)', async () => {
    await router.push('/warehouse/stocktakes?stocktakeId=SK-1&openEntry=1&foo=bar');
    expect(router.currentRoute.value.query).toEqual(
      expect.objectContaining({ tab: 'main', stocktakeId: 'SK-1', openEntry: '1', foo: 'bar' }),
    );
  });

  it('库存查询 hub 的 4 个旧路径全部落在同一个 hub path 上 (阴性对照: 不是四个不同的路径)', async () => {
    const landed = new Set<string>();
    for (const from of ['/inventory/by-warehouse', '/warehouse/inventory-total', '/warehouse/inventory', '/warehouse/semi-finished']) {
      await router.push(from);
      landed.add(router.currentRoute.value.path);
    }
    expect(landed.size).toBe(1);
    expect(Array.from(landed)).toEqual(['/warehouse/inventory-query']);
  });
});
