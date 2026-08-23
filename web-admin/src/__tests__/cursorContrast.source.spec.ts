import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const rawStyle = readFileSync(resolve(__dirname, '..', 'style.css'), 'utf8');
const rawEditor = readFileSync(
  resolve(__dirname, '..', 'views', 'system', 'product-processes', 'workflow',
    'ProductProcessWorkflowEditor.vue'), 'utf8');

/**
 * ⚠️ 一律扫**剥掉注释之后**的源码。
 * 本文件说明里现在写着 `.canvas-shell.is-connecting[data-v-…]`、`cursor: crosshair`
 * 这些字样, 不剥注释的话闸会命中自己的说明, 于是「规则还在不在」永远是真。
 */
function stripCssComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '');
}

/** 只取 SFC 的 <style> 块, 免得扫到脚本里的字符串。 */
function sfcStyleBlocks(source: string): string {
  const blocks = source.match(/<style[^>]*>[\s\S]*?<\/style>/g) || [];
  return stripCssComments(blocks.join('\n'));
}

const style = stripCssComments(rawStyle);
const editorStyle = sfcStyleBlocks(rawEditor);

/**
 * 客户 2026-07-20 (Sheet 第 13 行):「背景为白色、鼠标指针为白色, 对比度非常低, 找不到指针。
 * 具体场景为: 标签栏宽度调整时、WorkFlow 编辑时。」
 *
 * 🔴 2026-08-19 重写。这个文件原来钉住的两句话**都是没量就写下的, 而且都是错的**:
 *
 *  ① 原断言的注释写「编辑器里那两条裸 crosshair 仍在(作用域样式), 全局这条优先级更高」。
 *     实测相反 —— Vue scoped 会补 [data-v-xxxx], 权重 0-3-0 压过全局的 0-2-0。
 *     真实 Chromium 里量 computed cursor:
 *        改之前  .canvas-shell.is-connecting        → "crosshair"        ← 描边光标没生效
 *        改之前  .vue-flow__pane(批量选择)          → "crosshair"
 *        改之后  两者                                → "url(\"data:image/svg+xml…\") 12 12, crosshair"
 *     ⇒ 2026-08-05 上线的那半, 从上线起就是**摆设**。现在把 cursor 写进组件的 scoped 块。
 *
 *  ② 原断言钉住注释「网页覆盖不了系统配色」, 用意是「别让后来人再去试」。
 *     前提错了: EP 设的是**行内样式**(document.body.style.cursor = "col-resize",
 *     table-header/event-helper.mjs:125), 而作者样式表的 !important 压得过行内样式。
 *     真实 Chromium + 真 el-table, 鼠标停在表头右边界上量到:
 *        body 行内样式 = "cursor: col-resize;"     ← 阳性对照, EP 确实设上了
 *        computed body cursor = "url(\"data:image/svg+xml…"   ← 我们的描边光标赢了
 *        没停在边界上时  computed = "auto", 行内 = ""          ← 阴性对照
 *     ⇒ 这一半同样能修, 已经修了。那条旧断言随之删除 —— 它守的是历史, 不是需求。
 */
describe('白底上找不到指针 (Sheet 第 13 行)', () => {
  it('两个描边光标变量都在, 且都是「白描边 + 深主体 + 关键字回退」三件套', () => {
    for (const [name, fallback] of [
      ['--cretas-cursor-crosshair', 'crosshair'],
      ['--cretas-cursor-col-resize', 'col-resize'],
    ] as const) {
      // ⚠️ 不能用 `${name}:[\s\S]*?;` —— data URI 里就有 `;`(image/svg+xml;utf8),
      // 非贪婪会在那儿截断, 于是「缺白色描边层」是仪器造的假读数(第一版实测踩到)。
      // 声明的真正终点是末尾那个关键字回退。
      const decl = style.match(new RegExp(`${name}:[\\s\\S]*?\\b${fallback};`));
      expect(decl, `${name} 没有定义`).not.toBeNull();
      const text = decl![0];
      // 描边(白) + 主体(深) 两层都要在, 少一层就还是单色光标
      expect(text, `${name} 缺白色描边层`).toContain('stroke="%23ffffff"');
      expect(text, `${name} 缺深色主体层`).toContain('stroke="%23111111"');
      // data URI 不被支持时必须回落到标准光标, 不能变成 auto
      expect(text, `${name} 缺关键字回退`).toContain(fallback);
    }
  });

  it('画布光标写在【组件的 scoped 块】里 —— 写在全局会被 scoped 权重压掉', () => {
    const uses = editorStyle.match(/cursor:\s*var\(--cretas-cursor-crosshair\)/g) || [];
    expect(uses.length, '编辑器 scoped 块里应有两处引用(连线态 / 框选态)').toBe(2);
    expect(editorStyle).toContain('.canvas-shell.is-connecting');
    expect(editorStyle).toContain('.workflow-canvas.is-batch-selecting');
    // 阴性对照: 裸 crosshair 不许回来 —— 那正是 2026-08-05~2026-08-19 之间的真实状态
    expect(editorStyle.match(/cursor:\s*crosshair/g) || []).toEqual([]);
  });

  it('阴性对照: style.css 里不许再留一条注定输给 scoped 的画布规则', () => {
    // 只允许出现在变量定义里; 画布选择器本身不该在全局带 cursor 声明
    expect(style).not.toMatch(/\.canvas-shell\.is-connecting[^{]*\{[^}]*cursor/);
    expect(style).not.toMatch(/\.workflow-canvas\.is-batch-selecting[^{]*\{[^}]*cursor/);
  });

  it('列宽光标: 打在 EP 真正设行内样式的那三处, 且必须 !important 才压得过行内', () => {
    const rule = style.match(/body\[style\*="col-resize"\][\s\S]*?\}/);
    expect(rule, '没有覆盖 EP 行内 col-resize 的规则').not.toBeNull();
    const text = rule![0];
    expect(text).toContain('var(--cretas-cursor-col-resize)');
    // 去掉 !important 就压不过 document.body.style.cursor, 整条规则等于没写
    expect(text, '缺 !important —— 作者样式表不加 important 压不过行内样式').toContain('!important');
    // EP 在可排序表头 th 和它的 caret 上也各设一次行内样式
    expect(text).toContain('.el-table th.el-table__cell[style*="col-resize"]');
    expect(text).toContain('.caret-wrapper');
  });

  it('列宽落点指示线保持高对比 —— 指针之外的第二重线索, 不因为光标修好了就撤掉', () => {
    expect(style).toContain('.el-table__column-resize-proxy');
    expect(style).toMatch(/\.el-table__column-resize-proxy\s*\{[^}]*border-left: 2px/);
    expect(style).toMatch(/\.el-table__column-resize-proxy\s*\{[^}]*box-shadow/);
  });

  it('阳性对照: 剥注释这一步真的在干活 (否则上面所有阴性对照都可能是命中注释)', () => {
    expect(rawStyle).toContain('网页覆盖不了系统配色'); // 说明段里仍写着这句(讲它为什么是错的)
    expect(style).not.toContain('网页覆盖不了系统配色'); // 剥掉注释后就不该再有
  });
});
