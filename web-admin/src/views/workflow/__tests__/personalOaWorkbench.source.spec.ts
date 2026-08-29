import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8');
}

describe('personal OA workbench contract', () => {
  const router = source('src/router/index.ts');
  const pending = source('src/views/workflow/pending.vue');
  const acted = source('src/views/workflow/acted.vue');
  const copied = source('src/views/workflow/copied.vue');

  it('routes every authenticated role through dashboard access to four personal queues', () => {
    expect(router).toContain("title: '个人 OA'");
    expect(router).toContain("path: 'pending'");
    expect(router).toContain("path: 'my-created'");
    expect(router).toContain("path: 'acted'");
    expect(router).toContain("path: 'copied'");
    expect(router).toContain("title: '待我审批', module: 'dashboard'");
    expect(router).toContain("title: '我发起的', module: 'dashboard'");
    expect(router).toContain("title: '已处理', module: 'dashboard'");
    expect(router).toContain("title: '抄送我的', module: 'dashboard'");
  });

  it('uses truthful endpoints and only enables domains backed by the unified action adapter', () => {
    expect(acted).toContain('/workflow/instances/acted');
    expect(copied).toContain('/workflow/instances/copied');
    // 可操作域 = 后端 executeDomainAction 里有分支的域。
    // ⚠️ 原本断言整个 new Set([...]) 字面量, 已因此红过三次(调拨、盘点、BUDGET 各一次) ——
    //   它锁的是源码格式而不是意图。改为逐个断言成员, 新增域只加一行。
    const actionable = pending.match(
      /ACTIONABLE_MODULE_CODES\s*=\s*new Set\(\[([\s\S]*?)\]\)/,
    )?.[1] ?? '';
    for (const code of [
      'PURCHASE_ORDER', 'SALES_ORDER', 'INVENTORY_TRANSFER', 'INVENTORY_ADJUSTMENT',
    ]) {
      expect(actionable, `${code} 应在可操作白名单里 (后端已有对应 adapter 分支)`)
        .toContain(code);
    }
    // 反向断言: BUDGET 关账拆到独立 PR 等终审, 后端没有分支前不许进白名单。
    expect(actionable, 'BUDGET 关账尚未接入, 放进白名单会让待办点了就撞 422')
      .not.toContain('BUDGET');
    expect(pending).toContain('v-if="canAct(row)"');
    expect(pending).toContain('该业务域正在接入统一 OA，当前仅可查看审批进度');
  });

  it('opens an acted inventory adjustment on the stocktake evidence page', () => {
    expect(acted).toContain("INVENTORY_ADJUSTMENT: `/warehouse/stocktakes?stocktakeId=${id}`");
    expect(acted).toMatch(/if \(target\) \{[\s\S]{0,120}router\.push\(target\)/);
    expect(acted).toContain('暂不支持跳转模块');
  });

  it('keeps user-facing Chinese readable instead of accepting mojibake', () => {
    expect(acted).toContain('已处理');
    expect(copied).toContain('抄送我的');
    expect(`${acted}${copied}`).not.toMatch(/[锛閿鍔寮]/u);
  });

  it('renders pending business identity, approver roles and local time without internal-code leakage', () => {
    const enumDisplay = source('src/utils/enumDisplay.ts');
    const nodeLabel = source('src/views/workflow/nodeLabel.ts');
    expect(enumDisplay).toContain("SALES_ORDER: '销售订单'");
    expect(enumDisplay).toContain("PURCHASE_ORDER: '采购订单'");
    expect(enumDisplay).toContain("finance_manager: '财务主管'");
    expect(pending).toContain("import { formatDateTime } from '@/utils/dateFormat'");
    expect(pending).toContain('{{ formatDateTime(row.initiatedAt) }}');

    /*
     * 🔴 2026-08-20 (M-12): 这里原来钉的是模板里必须出现 `row.approverRoles?.map` ——
     * 那是**守字面**。审批角色现在并进「当前节点」列(独立的「授权角色」列已删:
     * 「当前节点=财务审批」与「授权角色=财务主管」是同一件事说两遍), 写法从内联 .map
     * 变成了 approverHint()。断言因此变红, 但**行为并没有退化**。
     *
     * 形态 C‴: 断言好好地守着一个我们现在判定为冗余的行为。⇒ 抬到守**性质**:
     * 「审批角色仍然被读取, 且经过 enumLabel 翻成中文」。
     */
    expect(pending).toContain('currentNodeText(row)');
    expect(pending).toContain('approverHint(row)');
    expect(nodeLabel).toContain('row.approverRoles');
    expect(nodeLabel).toMatch(/approverRoles[\s\S]{0,160}enumLabel/);
  });

  /**
   * M-12 阴性对照 —— 防止「拆列」退化成「把内部码直接印出来」。
   * ⛔ 模板里不许出现裸的角色码 / 模块码。
   */
  it('M-12 拆列后仍不泄露内部代码, 且金额是结构化字段而非从摘要反解', () => {
    expect(pending).not.toMatch(/>\s*finance_manager\s*</);
    expect(pending).not.toMatch(/>\s*PURCHASE_ORDER\s*</);

    // 金额必须来自后端结构化字段, ⛔ 不许用正则从 businessSummary 里抠 ¥ 数字 ——
    // 那句摘要的格式一改就崩(本仓在「拿正则切拼接文本」上栽过)。
    expect(pending).toContain('row.businessAmount');
    expect(pending).not.toMatch(/businessSummary[\s\S]{0,80}(match|replace|split)\s*\(/);

    // 兜底不能丢: 未下发结构化字段的模块仍要显示原来那句话, ⛔ 不能变空白。
    expect(pending).toContain('row.businessSummary');
  });

  /**
   * M-13 (2026-08-20, owner: 加「详情」按钮 + 通过前必须看到单据内容, ⛔ 不要做
   * "跳转详情页再回来才能点通过"的状态跟踪 —— openDetail 会离开本页, "看过了"这个
   * 状态存不住)。
   */
  it('M-13 操作列独立于 canAct 提供详情按钮, 且只在有业务实体 id 时显示', () => {
    // 详情按钮复用既有 openDetail, ⛔ 不新写一套跳转; 且不依赖 canAct(row) ——
    // 只读域(如 BUDGET)的单据也该能点开看细节。
    const opColumn = pending.match(
      /<el-table-column label="操作"[\s\S]*?<\/el-table-column>/,
    )?.[0] ?? '';
    expect(opColumn, '操作列里应有一个独立于 canAct 分支的详情按钮').toMatch(
      /v-if="row\.businessEntityId"[^>]*>\s*<el-button[^>]*@click="openDetail\(row\)"[^>]*>\s*详情|<el-button v-if="row\.businessEntityId"[^>]*@click="openDetail\(row\)"[^>]*>\s*详情/,
    );
    // 三个按钮撑不下 170px 的旧宽度, 必须同步加宽。
    expect(pending).toMatch(/<el-table-column label="操作" width="(\d+)"/);
    const width = Number(pending.match(/<el-table-column label="操作" width="(\d+)"/)?.[1] ?? 0);
    expect(width, '操作列加了第三个按钮后仍是旧宽度, 会挤成两行').toBeGreaterThan(170);
  });

  it('M-13 「通过」前的确认框必须带金额等单据关键信息, 不能是空洞的"确定吗?"', () => {
    // 确认文案由 approveConfirmMessage 生成, 且用 HTML 渲染(要带 dangerouslyUseHTMLString)。
    expect(pending).toMatch(/ElMessageBox\.confirm\(\s*approveConfirmMessage\(row\)/);
    expect(pending).toMatch(/approveConfirmMessage\(row\)[\s\S]{0,200}dangerouslyUseHTMLString:\s*true/);

    // 正文必须列出这几项 —— 复用既有取值函数, ⛔ 不再拼一份新口径。
    const fnBody = pending.match(
      /function approveConfirmMessage\(row: PendingApproval\): string \{[\s\S]*?\n\}/,
    )?.[0] ?? '';
    expect(fnBody).toContain('业务类型');
    expect(fnBody).toContain('业务单号');
    expect(fnBody).toContain('往来单位');
    expect(fnBody).toContain('金额');
    expect(fnBody).toContain('当前节点');
    expect(fnBody).toContain('申请人');
    expect(fnBody).toContain('formatAmount(amountOf(row))');

    // XSS 阴性对照: 往来单位/单号等后端字段拼进 HTML 前必须转义, ⛔ 不能原样拼接。
    expect(fnBody).toMatch(/escapeHtml\(/);
  });
});
