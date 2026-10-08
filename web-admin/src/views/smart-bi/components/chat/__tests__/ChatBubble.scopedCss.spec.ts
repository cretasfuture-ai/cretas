// @vitest-environment node
/**
 * ChatBubble 标准版式 (工厂租户, #966) 的 markdown 样式必须真的打得到 v-html 内容。
 *
 * 2026-10-08 本机实测: 嵌套写法 `.bubble-std .bubble-content-markdown { :deep(ol) {...} }` 被编译成
 * `.bubble-std .bubble-content-markdown [data-v-x] ol` —— 要求 v-html 里有带 data-v 的节点, 一个都没有,
 * 整块规则静默失效 (ol padding-left 0, 序号挤出正文列; 记录卡无边框)。挂载测试量不到 (jsdom 不算样式),
 * 所以这里走一遍和生产构建同样的两步: compiler-sfc 加 scoped 属性, 再用 esbuild 把 CSS 嵌套展开
 * (Vite 构建也是 esbuild 处理 CSS; 不展开的话, 旧的嵌套写法在这里根本拆不成规则, 「没有死选择器」会恒绿)。
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { compileStyle, parse } from '@vue/compiler-sfc';
import { transformSync } from 'esbuild';

// node 环境 (esbuild 在 jsdom 里过不了 TextEncoder 自检); 全局 setup 的 beforeEach 要 localStorage.clear(), 给个空壳
(globalThis as { localStorage?: Pick<Storage, 'clear'> }).localStorage ??= { clear: () => undefined };

const ID = 'data-v-test';
const file = resolve(__dirname, '..', 'ChatBubble.vue');
const { descriptor } = parse(readFileSync(file, 'utf-8'), { filename: file });
const scoped = descriptor.styles.find((s) => s.scoped);
const compiled = compileStyle({ source: scoped!.content, filename: file, id: ID, scoped: true });
// chrome100 不支持 CSS 嵌套 ⇒ esbuild 必须展开, 产物就是浏览器实际拿到的选择器
const lowered = transformSync(compiled.code, { loader: 'css', target: 'chrome100' }).code;

/** 编译后所有「选择器 → 声明」; 逗号分组拆开 */
function rules(css: string): Array<{ selector: string; body: string }> {
  const out: Array<{ selector: string; body: string }> = [];
  const re = /([^{}]+)\{([^{}]*)\}/g;
  css = css.replace(/\/\*[\s\S]*?\*\//g, '');
  let m: RegExpExecArray | null;
  while ((m = re.exec(css))) {
    for (const sel of m[1].split(',')) out.push({ selector: sel.trim(), body: m[2] });
  }
  return out;
}
const all = rules(lowered);
const std = all.filter((r) => r.selector.startsWith('.bubble-std .bubble-content-markdown'));

describe('标准版式 markdown 样式打得到 v-html 内容', () => {
  it('编译无错误; 标准块确实存在 (阳性: 不是因为一条都没有才不报错)', () => {
    expect(compiled.errors).toEqual([]);
    expect(std.length).toBeGreaterThanOrEqual(10);
  });

  it('🔴 标准块里没有一条是「[data-v] 前面带空格」的死选择器', () => {
    const dead = std.filter((r) => r.selector.includes(` [${ID}]`)).map((r) => r.selector);
    expect(dead).toEqual([]);
  });

  it('列表留在正文列里: ol / ul 编译成 `.bubble-content-markdown[data-v] ol` 且 padding-left 1.5em', () => {
    for (const tag of ['ol', 'ul']) {
      const rule = std.find((r) => r.selector === `.bubble-std .bubble-content-markdown[${ID}] ${tag}`);
      expect(rule, tag).toBeDefined();
      expect(rule!.body).toMatch(/padding-left:\s*1\.5em/);
    }
  });

  it('记录卡 (表格转成的卡片) 有边框和圆角', () => {
    const rule = std.find((r) => r.selector === `.bubble-std .bubble-content-markdown[${ID}] .bubble-record`);
    expect(rule).toBeDefined();
    expect(rule!.body).toMatch(/border:\s*1px solid/);
    expect(rule!.body).toMatch(/border-radius:\s*8px/);
  });
});
