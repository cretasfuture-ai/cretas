import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const EDITOR = readFileSync(
  resolve(__dirname, '../ProductProcessWorkflowEditor.vue'), 'utf8');

describe('回料产出节点', () => {
  it('复用已有的 addOutputToProcess 入口, 不新造函数', () => {
    // 与副产同一条纪律: 「复用画布已有的『＋ 产出 Cell（分流）』入口, 不要新造入口」
    expect(EDITOR).not.toMatch(/function addReworkToProcess/);
    // 🔴 2026-09-01 同 byproductOutputNode.spec.ts: 原来钉的是调用点的字面文本,
    //    加第三类时它红了而行为没坏。改成钉结构 —— 建产出 Cell 的函数只有一个,
    //    统一入口把回料这一类也从它穿过去。
    expect(EDITOR.match(/function addOutputToProcess\(/g) ?? []).toHaveLength(1);
    const chooser = EDITOR.slice(
      EDITOR.indexOf('function chooseSpecialOutput'),
      EDITOR.indexOf('function chooseSpecialOutput') + 900);
    expect(chooser).toContain('addOutputToProcess(processId');
    expect(chooser).toContain('rework: true');
    // 选中「回料」之后必须**立刻进入真实配置**, 不是只生成一个空壳 Cell(§7)。
    expect(EDITOR).toMatch(/if \(kind === 'rework'\) void openReworkDrawer\(created\.id\)/);
  });

  it('回料标记与副产标记是两个独立的键 —— 合并会让回料进不了报工屏', () => {
    // 副产端口会被 getOutputOptions 过滤掉; 回料端口必须留在选项里。
    expect(EDITOR).toMatch(/isRework/);
    expect(EDITOR).not.toMatch(/isRework\s*[:=]\s*options\.byproduct/);
  });
});
