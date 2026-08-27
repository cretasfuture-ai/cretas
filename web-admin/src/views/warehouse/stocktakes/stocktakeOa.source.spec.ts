import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const stocktakeSource = readFileSync(resolve(process.cwd(), 'src/views/warehouse/stocktakes/index.vue'), 'utf8');
const approvalSource = readFileSync(resolve(process.cwd(), 'src/views/workflow/pending.vue'), 'utf8');
const moduleLabelsSource = readFileSync(resolve(process.cwd(), 'src/views/workflow/moduleLabels.ts'), 'utf8');

describe('stocktake OA-only approval contract', () => {
  it('submits into OA and removes direct approval actions from the stocktake row', () => {
    expect(stocktakeSource).toContain('workflowInstanceId');
    expect(stocktakeSource).toContain("path: '/workflow/pending'");
    expect(stocktakeSource).toContain('前往 OA 审批中心');
    expect(stocktakeSource).not.toContain('@click="openApproveDialog(row)"');
    expect(stocktakeSource).not.toContain('@click="openRejectDialog(row)"');
  });

  it('keeps the OA center actionable for inventory-adjustment tasks', () => {
    expect(approvalSource).toContain("'INVENTORY_ADJUSTMENT'");
    // ⚠️ 2026-08-28 抬高: 原本断言中文名「库存盘点」的字面量**长在 pending.vue 里**。
    //    当天把六个 OA 页面各写各的中文表(三种不同内容)收敛到共享的
    //    views/workflow/moduleLabels.ts 之后这条红了 —— **性质更成立了(从 1 个页面
    //    扩到 6 个), 而断言在拦它**。⇒ 抬到「中文名在那份唯一的表里」+
    //    「pending.vue 用的就是那份表」。⛔ 不是把断言删掉。
    expect(moduleLabelsSource, '库存盘点的中文名应在共享表里').toContain('库存盘点');
    expect(approvalSource, 'pending.vue 应引用共享表')
      .toMatch(/import\s*\{\s*MODULE_LABELS\s*\}\s*from\s*'\.\/moduleLabels'/);
  });

  it('labels an empty approval preview as no inventory impact', () => {
    expect(stocktakeSource).toContain('无实际差异，无库存影响');
  });
});
