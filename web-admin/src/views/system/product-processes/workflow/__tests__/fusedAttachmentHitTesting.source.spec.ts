import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const EDITOR = readFileSync(
  resolve(__dirname, '../ProductProcessWorkflowEditor.vue'), 'utf8');

/**
 * 🔴 2026-09-01 用**真实鼠标**拖动时才撞出来的缺陷。
 *
 * 融合附属的 VueFlow 外壳留在 owner 的位置上，窄条只是被 `transform` 移走了 ——
 * 而 **transform 不改变布局盒**。于是那个不可见的外壳正好压在 owner 顶部（上方附属）
 * 或底部（下方附属）一条带子上，把「工序 Cell」标题、「编辑工序」按钮和整块可抓取区
 * 一起挡住：工序拖不动、标题点不到。
 *
 * ⚠️ 前几轮**一次都没发现**，因为我一直用合成事件（`dispatchEvent`）——
 * 合成事件按 target 直接派发，不走命中测试。Playwright 的真实鼠标一试就报：
 *   「<div data-id="bom-overlay:aux:process:cook"> intercepts pointer events」
 * ⇒ 形态：仪器够不着被测行为，读数看起来一切正常。
 *
 * 修法两段，缺一不可：
 *   ① 给附属外壳挂一个类，整体 `pointer-events: none`，只让窄条自己 `auto`；
 *   ② `!important` —— vue-flow 把 `pointer-events: all` 写成**行内样式**，
 *      行内压过任何样式表规则。第一版漏了 important：类挂上了、选择器也命中了，
 *      而 computed 值仍是 `all`，拖动照样被挡。
 */
describe('融合附属的外壳不许挡住 owner 的命中区', () => {
  it('附属节点挂上了 wf-fused-attachment 类', () => {
    expect(EDITOR).toMatch(/isFusedAttachmentNode\(node\.id\)\) classes\.push\('wf-fused-attachment'\)/);
  });

  it('外壳整体不接鼠标', () => {
    expect(EDITOR).toMatch(
      /\.vue-flow__node\.wf-fused-attachment\)\s*\{\s*pointer-events:\s*none\s*!important;\s*\}/);
  });

  it('🔴 必须带 !important —— vue-flow 用行内样式写 pointer-events: all', () => {
    // 没有它这条修复是**看起来生效**的：类在、选择器命中，computed 仍是 all。
    const rule = EDITOR.match(/\.vue-flow__node\.wf-fused-attachment\)[^}]*\}/)?.[0] ?? '';
    expect(rule).toContain('!important');
  });

  it('⛔ 窄条自己必须照常可点（展开/收起、配置入口都在上面）', () => {
    expect(EDITOR).toMatch(
      /\.vue-flow__node\.wf-fused-attachment \.fused-strip\)\s*\{\s*pointer-events:\s*auto;\s*\}/);
  });

  it('聚焦淡化的类与融合类可以并存，不能互相覆盖', () => {
    // applyFocusClasses 早先是直接赋一个字符串，加融合类时若还那样写就会把它冲掉。
    expect(EDITOR).toMatch(/const classes: string\[\] = \[\];/);
    expect(EDITOR).toMatch(/classes\.length > 0 \? classes\.join\(' '\) : undefined/);
    expect(EDITOR).toMatch(/classes\.push\('wf-focus-dim'\)/);
  });
});
