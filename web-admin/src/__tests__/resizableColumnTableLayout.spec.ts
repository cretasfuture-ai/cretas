/**
 * 客户 2026-07-31 + 复测 2026-08-11 (Sheet「综合」):
 * 「调节宽度 BUG 依然存在，压缩后表头和数据宽度不一致」。
 *
 * 根因不是 useTableColumnWidth 存错了宽度 —— 它存对了。根因在 CSS:
 * Element Plus 在 `table-layout="fixed"` 下把表头和表体渲染成**两个独立的
 * <table>**（`table.mjs` 的 `showHeader && tableLayout === 'fixed'` 分支），
 * 而 style.css 那条「语义化表格」规则 `table-layout: auto !important` 同时
 * 落在这两个 <table> 上。两张表于是各自按自己的内容排版 —— 一旦某列被拖到
 * **比表头文字的固有宽度还窄**，两边就不再一致。
 *
 * Chromium 实测（repro：真 Vue + 真 element-plus + 真 style.css）:
 *   「批次号」拖到 60px  →  header=95.55px / body=60px，右侧每一列跟着漂
 *   加上 .resizable-column-table 之后 → 四列 delta 全为 0.00
 *   不加的那一侧仍然 4 列错位（阴性对照，证明是这个 class 起的作用）
 *
 * ⚠️ 这道闸守的是**接线**，不是 CSS 文本本身：
 * 「规则写了」和「用它的表格挂上了」是两件事。本仓踩过多次
 * 「机制在、没接上」——所以下面第二条断言按 useTableColumnWidth 的
 * **实际引用者**反查，新增一个可拖拽表格却忘了加 class 会红。
 *
 * ⛔ 注意 jsdom 不做 layout，`table-layout` 在单测里没有任何可观测效果，
 * 所以这里只能守源码契约；真正的对齐读数来自上面的 Chromium 实测。
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const SRC = resolve(import.meta.dirname, '..');
const styleSource = readFileSync(resolve(SRC, 'style.css'), 'utf8');

/** The composable that turns a table into a drag-resizable one. */
const RESIZE_COMPOSABLE = 'useTableColumnWidth';
/** The class that pins header+body to one shared width contract. */
const OPT_IN_CLASS = 'resizable-column-table';

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = resolve(dir, entry);
    if (statSync(full).isDirectory()) {
      return entry === 'node_modules' ? [] : walk(full);
    }
    return full.endsWith('.vue') ? [full] : [];
  });
}

/** .vue files that actually wire up drag-resize (excludes the composable + its own tests). */
function resizableTableFiles(): string[] {
  return walk(SRC).filter((file) => readFileSync(file, 'utf8').includes(RESIZE_COMPOSABLE));
}

describe('拖拽调宽的表格：表头与数据共用同一份宽度契约', () => {
  it('style.css 定义了 .resizable-column-table 并把两个 <table> 都钉成 fixed', () => {
    // The rule must exist...
    expect(styleSource).toContain(`.${OPT_IN_CLASS} .el-table__header`);
    expect(styleSource).toContain(`.${OPT_IN_CLASS} .el-table__body`);

    // ...and it must actually override the semantic `auto !important` above it.
    // Without !important the earlier rule wins and the fix is a no-op.
    const block = styleSource.slice(
      styleSource.indexOf(`.${OPT_IN_CLASS} .el-table__header`),
      styleSource.indexOf(`.${OPT_IN_CLASS} .el-table__header`) + 400,
    );
    expect(block).toContain('table-layout: fixed !important');
  });

  it('阴性对照：非拖拽表格仍然保留语义化的 auto 排版（本次修复不许把它一起改掉）', () => {
    // 2026-07-20 的可读性决定仍然有效 —— 这条断言防止「修对齐」顺手把
    // 业务名称/地址/备注的自适应换行退回成单行省略号。
    expect(styleSource).toContain('table-layout: auto !important');
  });

  it('每个用 useTableColumnWidth 的表格都真的挂上了这个 class（接线闸）', () => {
    const files = resizableTableFiles();

    // 仪器自检：读不到文件就不是「全都挂上了」，是根本没量。
    expect(files.length, '没有扫到任何使用 useTableColumnWidth 的 .vue —— 闸没量到东西').toBeGreaterThan(0);

    const missing = files.filter((file) => !readFileSync(file, 'utf8').includes(OPT_IN_CLASS));
    expect(
      missing.map((f) => f.slice(SRC.length + 1)),
      `这些表格开启了拖拽调宽但没有 class="${OPT_IN_CLASS}"，`
        + '压缩列宽后表头和数据会错位（Chromium 实测 header=95.55 / body=60）。',
    ).toEqual([]);
  });
});
