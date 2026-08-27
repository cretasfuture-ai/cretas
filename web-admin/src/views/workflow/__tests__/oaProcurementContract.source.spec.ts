import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('procurement unified OA contract', () => {
  const detail = source('src/views/procurement/orders/detail.vue');
  const pending = source('src/views/workflow/pending.vue');
  const router = source('src/router/index.ts');

  it('keeps purchase details read-only after submission and links to truthful progress', () => {
    expect(detail).toContain('/approval-progress');
    expect(detail).toContain("path: '/workflow/my-created'");
    expect(detail).not.toContain("path: '/workflow/my-participated'");
    expect(detail).not.toContain("finance-approve");
    expect(detail).not.toContain("finance-reject");
    expect(detail).not.toContain("submit-for-finance-review");
  });

  it('provides one pending-work entry and guards each action with the visible node', () => {
    expect(router).toContain("path: 'pending'");
    expect(router).toContain("name: 'WorkflowPending'");
    expect(pending).toContain('/workflow/instances/pending');
    expect(pending).toContain('/workflow/instances/${row.instanceId}/actions');
    expect(pending).toContain('expectedNodeId: row.currentNodeId');
    expect(pending).toContain('idempotencyKey: `oa-${action.toLowerCase()}-${row.instanceId}-${row.currentNodeId}`');
    expect(pending).toContain("operatingId.value = row.instanceId");
    // 可操作域 = 后端 executeDomainAction 里有分支的域。
    // ⚠️ 这里刻意不断言整个 new Set([...]) 字面量: 那种写法在加盘点那次就漏同步红过一次
    //   (见本行原注释), 加 BUDGET 时又红了一次 —— 它锁的是源码格式而不是意图。
    //   改为逐个断言成员, 新增域只加一行, 且不会被换行/顺序变化假红。
    const actionable = pending.match(
      /ACTIONABLE_MODULE_CODES\s*=\s*new Set\(\[([\s\S]*?)\]\)/,
    )?.[1] ?? '';
    for (const code of [
      'PURCHASE_ORDER', 'SALES_ORDER', 'INVENTORY_TRANSFER', 'INVENTORY_ADJUSTMENT',
    ]) {
      expect(actionable, `${code} 应在可操作白名单里 (后端已有对应 adapter 分支)`)
        .toContain(code);
    }
    // 反向断言: BUDGET 通过 = 执行月度关账(影响所有工厂), 拆到独立 PR 等终审。
    // 在后端 executeDomainAction 接上 BUDGET 分支之前放进白名单, 只会让用户点了撞 422。
    expect(actionable, 'BUDGET 关账尚未接入, 放进白名单会让待办点了就撞 422')
      .not.toContain('BUDGET');
    // 筛选下拉改为 v-for MODULE_LABELS: 下拉与表格列共用同一份中文名。
    //
    // ⚠️ 2026-08-28 这条断言原本写的是 `expect(pending).toContain("SALES_ORDER: '销售订单'")`
    //    —— 它锁的是**那张表长在 pending.vue 里**这个实现位置, 而注释说的性质是
    //    「下拉与表格列共用同一份」。当天把这张表收敛到 `./moduleLabels.ts`
    //    (六个 OA 页面共用, 此前它们各写各的、三种不同内容)之后, 这条断言红了 ——
    //    **性质更成立了, 而断言在拦它**。⇒ 把它从「字面位置」抬到「性质」:
    //      ① pending.vue 用的是那份共享表(不是自己又写一份)
    //      ② 下拉确实 v-for 那份表(下拉与表格同源)
    //      ③ 那份表里确实有这个中文名(原断言真正关心的东西没丢)
    // ⛔ 不是把断言删掉。
    expect(pending, 'pending.vue 应引用共享的 MODULE_LABELS, 而不是自己再写一份')
      .toMatch(/import\s*\{\s*MODULE_LABELS\s*\}\s*from\s*'\.\/moduleLabels'/);
    expect(pending, '筛选下拉与表格列必须同源 —— 下拉 v-for 的就是那份表')
      .toMatch(/v-for="\(label, code\) in MODULE_LABELS"/);
    expect(source('src/views/workflow/moduleLabels.ts'), '共享表里应有销售订单的中文名')
      .toContain("SALES_ORDER: '销售订单'");
    expect(pending).toContain('v-for="(label, code) in MODULE_LABELS"');
  });
});
