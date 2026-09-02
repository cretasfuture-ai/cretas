import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const HERE = dirname(fileURLToPath(import.meta.url));
const LIST = resolve(HERE, '../list.vue');
const ENTRY = resolve(HERE, '../assignProcessOperatorEntry.ts');

/**
 * 缺陷 51 (owner 拍板 2026-09-02): 计划行「更多」里补一个「分配工序操作员」入口 ——
 * 「本来也不应该放在生产批次，应该放在生产计划的操作里面」。
 *
 * plans/list.vue 是一个 7000+ 行的巨型 SFC，本仓对它一律走源码字符串断言
 * (与 batches/__tests__/taskAssignEntry.spec.ts 同一套路), 不整份 mount ——
 * 纯逻辑(候选解析/目标路由)已抽到 assignProcessOperatorEntry.ts 并有独立单测。
 */
describe('生产计划列表「分配工序操作员」入口 —— 接线契约', () => {
  const view = readFileSync(LIST, 'utf8');
  const entry = readFileSync(ENTRY, 'utf8');

  it('菜单项接到 productionMoreActions 的唯一判据, 不另起一套 v-if', () => {
    expect(view).toContain("v-if=\"hasProductionMoreCommand(row, 'assign-process-operator')\"");
    expect(view).toContain('command="assign-process-operator"');
    expect(view).toContain('>分配工序操作员</el-dropdown-item>');
  });

  it('🔒 硬约束8: 全文件只有一处「更多」下拉 (table 行内), 没有 card/mobile 副本要同步登记', () => {
    // 判据: 「更多」的显隐按钮字面量只出现一次 —— 若未来加了移动端卡片视图,
    // 这条断言会先红, 提醒补第二处 v-if/command 而不是悄悄漏掉。
    const moreButtonOccurrences = (view.match(/更多<el-icon/g) || []).length;
    expect(moreButtonOccurrences).toBe(1);
    const commandOccurrences = (view.match(/command="assign-process-operator"/g) || []).length;
    expect(commandOccurrences).toBe(1);
  });

  it('点击命令路由到 handleAssignProcessOperator, 不是直接内联跳转', () => {
    expect(view).toContain("case 'assign-process-operator':");
    expect(view).toContain('handleAssignProcessOperator(row);');
    expect(view).toContain('function handleAssignProcessOperator(row: TableRow)');
  });

  it('下拉打开时惰性解析 (不是列表一加载就对每一行请求)', () => {
    expect(view).toContain(
      '@visible-change="(visible: boolean) => visible && ensureAssignOperatorCandidates(row)"',
    );
    expect(view).toContain('async function ensureAssignOperatorCandidates(row: TableRow)');
    expect(view).toContain('getProductionDocumentTrace(factoryId.value, key)');
  });

  it('无成品批次时灰显并写清原因, 不是点了才知道 (防呆 Rule 1, 与「编辑」同规矩)', () => {
    expect(view).toContain(
      ':disabled="actionLoading || assignOperatorDisabledReason(row) !== \'\'"',
    );
    expect(view).toContain(':title="assignOperatorDisabledReason(row)"');
    expect(view).toContain('function assignOperatorDisabledReason(row: TableRow): string');
  });

  it('多候选(联产/多产品)弹选择框, 不悄悄取第一个', () => {
    expect(view).toContain('v-model="assignOperatorPickerVisible"');
    expect(view).toContain('data-testid="assign-operator-picker"');
    expect(view).toContain('function goToAssignOperatorBatch(document: ProductionTraceDocument)');
    expect(view).toContain('该计划生成了多个成品批次，请选择要分配工序操作员的批次');
    // 唯一候选直接跳转、不弹选择框
    expect(view).toContain('if (candidates.length === 1) {');
  });

  it('批次解析走既有的单据追踪 API, 不是新猜一套按前缀过滤', () => {
    expect(view).toContain("from './assignProcessOperatorEntry'");
    expect(view).toContain('finishedGoodsBatchCandidates');
    expect(view).toContain('assignProcessOperatorTarget');
    expect(view).toContain('finishedGoodsBatchCandidates(response.data)');
    expect(entry).toContain("documentType === 'PRODUCTION_BATCH'");
    expect(entry).not.toMatch(/startsWith\(.PB-/);
  });

  it('阳性对照: 灰显文案与目标路由函数确实存在', () => {
    expect(view).toContain('PLAN_HAS_NO_FINISHED_BATCH_TOOLTIP');
    expect(view).toContain('assignProcessOperatorTarget(candidates[0].documentId)');
  });
});
