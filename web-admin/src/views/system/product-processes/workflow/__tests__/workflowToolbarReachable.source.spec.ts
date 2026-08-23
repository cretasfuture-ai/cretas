/**
 * 客户 2026-08-17 (Sheet):「[产品-工序配置] 显示 bug，+原料 cell 被挡住了」。
 *
 * 「被挡住」是字面意义上的。Chromium 实测（1180px 编辑器宽度，真 element-plus
 * + 从组件里逐字抄出来的 .toolbar-* 声明）:
 *
 *   按钮 box            left = 293.72
 *   .toolbar-actions box left = 572.56       ← 按钮整个在自己的滚动容器外面 278.84px
 *   scrollWidth/clientWidth = 596/596        ← maxScrollLeft = 0，**滚不过去**
 *   elementFromPoint(按钮中心) → SPAN.el-tag__content   ← 盖住它的就是「本图产出」标签
 *
 * 成因是 `justify-content: flex-end` + `flex-wrap: nowrap` 的**溢出丢失**:
 * 一行放不下时溢出发生在**行首**，而 LTR 下 scrollLeft 不能为负。
 * #2477 往 .toolbar-status 里加了「本图产出：…」标签之后必然触发。
 *
 * ⚠️ 这道闸只能守源码契约 —— jsdom 不做 layout，flex 溢出在单测里没有任何
 * 可观测效果。真正的可达性读数来自上面那次 Chromium 实测（阳性对照: 1900px
 * 宽度下同一探针读到 REACHABLE=true，证明探针不是恒假）。
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(import.meta.dirname, '../ProductProcessWorkflowEditor.vue'),
  'utf8',
);

/** Pull one CSS rule body out of the component's <style scoped> block. */
function ruleBody(selector: string): string {
  const at = source.indexOf(`\n${selector} {`);
  expect(at, `找不到 CSS 规则 ${selector} —— 闸没量到东西`).toBeGreaterThan(-1);
  const open = source.indexOf('{', at);
  const close = source.indexOf('}', open);
  return source.slice(open + 1, close);
}

describe('产品-工序配置 工具栏：「+ 原料 Cell」不能被挤出可达范围', () => {
  it('按钮存在且带 data-testid（否则 E2E 无法断言它可见）', () => {
    expect(source).toContain('data-testid="add-raw-cell"');
    expect(source).toContain('+ 原料 Cell');
  });

  it('.toolbar-actions 必须允许换行 —— 这是「按钮跑到容器外面」的直接成因', () => {
    const body = ruleBody('.toolbar-actions');
    // 被守的行为: 放不下的时候按钮换行, 而不是溢出到行首那个够不到的负偏移。
    expect(body, '.toolbar-actions 仍是 nowrap，溢出会发生在行首且滚不到').toContain('flex-wrap: wrap');
    expect(body).not.toContain('flex-wrap: nowrap');
  });

  it('⛔ 不许用 overflow-x: auto 假装能滚 —— flex-end 溢出在行首，scrollLeft 不能为负', () => {
    const body = ruleBody('.toolbar-actions');
    // 实测 scrollWidth === clientWidth (596/596) ⇒ 滚动条根本不存在，
    // `overflow-x: auto` 给的是一种「可以滚过去」的错觉。
    expect(body, 'overflow-x: auto 在 flex-end 溢出下到不了行首的内容').not.toContain('overflow-x: auto');
  });

  it('外层 .workflow-toolbar 也要能换行，否则 actions 那一行没有地方落', () => {
    expect(ruleBody('.workflow-toolbar')).toContain('flex-wrap: wrap');
  });

  it('阴性对照：状态区仍然收敛（不许为了腾地方把它改成无限撑开）', () => {
    const body = ruleBody('.toolbar-status');
    expect(body).toContain('overflow: hidden');
    // flex-basis 让状态区先让位再收缩，而不是把 actions 顶出去。
    expect(body).toContain('flex: 1 1');
  });
});
