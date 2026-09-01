import { describe, it, expect } from 'vitest';
import router from '@/router';

// 预热: 静态 import 5 个 hub 组件, 让 vitest 在**文件加载阶段**(不计入单条测试的
// timeout)就转译完它们及其内嵌的真实大页面。router.push() 命中同一个模块时走缓存,
// 不再重新转译。见 warehouseInventoryStocktakeRedirects.spec.ts 头部同一条说明
// (2026-09-01 实测: 首次 push 触发的冷转译在并发套件下能超过 20000ms 单测超时)。
import '@/views/production/approval-hub/index.vue';
import '@/views/production/material-flow/index.vue';
import '@/views/production/kanban/index.vue';
import '@/views/production/analysis/index.vue';
import '@/views/production/process-hub/index.vue';

/**
 * 2026-09-02 LIUSHANMEN 生产管理菜单收拢 (owner 拍板) —— 硬约束 2:
 * 「Old URLs must have a defined behavior.」19 个旧路径合并进 5 个 hub 后，
 * 不删只重定向；本文件钉住每一条的落点与 ?tab=，并证明深链 query 在重定向之后
 * 原样保留。与 warehouseInventoryStocktakeRedirects.spec.ts 同模式。
 */
describe('生产管理 5 个 hub —— 旧路径 redirect (LIUSHANMEN IA 收拢)', () => {
  const cases: Array<{ from: string; toPath: string; tab: string }> = [
    // 报工审批 hub
    { from: '/production/reversals', toPath: '/production/approval', tab: 'reversal' },
    // 领料 hub
    { from: '/production/material-requisitions', toPath: '/production/material-flow', tab: 'requisitions' },
    { from: '/production/material-returns', toPath: '/production/material-flow', tab: 'returns' },
    // 生产看板 hub
    { from: '/production/pending-queue', toPath: '/production/kanban', tab: 'pending-queue' },
    { from: '/production/restock-board', toPath: '/production/kanban', tab: 'restock-board' },
    { from: '/production/delivery-warnings', toPath: '/production/kanban', tab: 'delivery-warnings' },
    // 生产分析 hub
    { from: '/analytics/production-report', toPath: '/production/analysis-query', tab: 'workshop-report' },
    { from: '/production-analytics/production', toPath: '/production/analysis-query', tab: 'data-analysis' },
    { from: '/production/bom-achievement', toPath: '/production/analysis-query', tab: 'bom-achievement' },
    { from: '/production/process-io', toPath: '/production/analysis-query', tab: 'process-io' },
    { from: '/production/labor-efficiency', toPath: '/production/analysis-query', tab: 'labor-efficiency' },
    { from: '/production-analytics/yield-cost', toPath: '/production/analysis-query', tab: 'yield-cost' },
    // 工序 hub
    { from: '/system/work-processes', toPath: '/production/process-hub', tab: 'work-processes' },
    { from: '/system/product-processes', toPath: '/production/process-hub', tab: 'product-processes' },
  ];

  // 20s: 首次 push 命中的 hub 路由要动态 import 内嵌的真实大页面, 冷启动转译比默认
  // 5s 超时慢 (见文件头部说明)。
  it.each(cases)('$from → $toPath?tab=$tab', async ({ from, toPath, tab }) => {
    await router.push(from);
    expect(router.currentRoute.value.path).toBe(toPath);
    expect(router.currentRoute.value.query.tab).toBe(tab);
    expect(router.currentRoute.value.redirectedFrom?.path).toBe(from);
  }, 20000);

  it('阳性对照: 没有 redirect 的普通页面 push 后路径不变 (证明上面的断言不是路由器的默认行为)', async () => {
    await router.push('/production/batches');
    expect(router.currentRoute.value.path).toBe('/production/batches');
    expect(router.currentRoute.value.redirectedFrom).toBeUndefined();
  });

  it('/production/approval 本身不重定向 (⛔ 路径复用, 不是新路径 —— 见 approvalQueryTab.ts)', async () => {
    await router.push('/production/approval');
    expect(router.currentRoute.value.path).toBe('/production/approval');
    expect(router.currentRoute.value.redirectedFrom).toBeUndefined();
  });

  it('深链 query 必须原样透传 —— 备货看板自己的 startDate/endDate', async () => {
    await router.push('/production/restock-board?startDate=2026-09-01&endDate=2026-09-05');
    expect(router.currentRoute.value.path).toBe('/production/kanban');
    expect(router.currentRoute.value.query.tab).toBe('restock-board');
    expect(router.currentRoute.value.query.startDate).toBe('2026-09-01');
    expect(router.currentRoute.value.query.endDate).toBe('2026-09-05');
  });

  it('多个 query 参数同时保留 (阳性对照: 不是只保留了第一个)', async () => {
    await router.push('/production/labor-efficiency?productTypeId=PT-1&foo=bar');
    expect(router.currentRoute.value.query).toEqual(
      expect.objectContaining({ tab: 'labor-efficiency', productTypeId: 'PT-1', foo: 'bar' }),
    );
  });

  it('生产分析 hub 的 6 个旧路径全部落在同一个 hub path 上 (阴性对照: 不是六个不同的路径)', async () => {
    const landed = new Set<string>();
    for (const from of [
      '/analytics/production-report', '/production-analytics/production',
      '/production/bom-achievement', '/production/process-io',
      '/production/labor-efficiency', '/production-analytics/yield-cost',
    ]) {
      await router.push(from);
      landed.add(router.currentRoute.value.path);
    }
    expect(landed.size).toBe(1);
    expect(Array.from(landed)).toEqual(['/production/analysis-query']);
  });

  it('/production-analytics/cost-summary 不受影响 (不在合并范围内, 仍指向自己的组件)', async () => {
    await router.push('/production-analytics/cost-summary');
    expect(router.currentRoute.value.path).toBe('/production-analytics/cost-summary');
    expect(router.currentRoute.value.redirectedFrom).toBeUndefined();
  });
});
