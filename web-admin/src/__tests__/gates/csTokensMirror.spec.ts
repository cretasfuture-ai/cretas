import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { CS_TOKENS, csVarName, type CsTokenName } from '@/styles/csTokens';

/**
 * P1 设计系统（MIGRATION-PLAN）的三条接线闸。
 *
 * 1. tokens.css 是 --cs-* 的唯一真值；ECharts 读不了 CSS 变量，用 TS 镜像 csTokens.ts。
 *    两份必然会漂（measurement-and-wiring 形态 D），这里逐项比对。
 * 2. Element Plus 映射必须真的盖得过 element-plus/dist/index.css：它在 main.ts bootstrap 里
 *    **动态** import、晚于我们的样式进入页面，自带 `:root{--el-color-primary:#409eff}`。
 *    同特异性后到者赢 —— 原来 style.css 的 `:root{--el-color-primary:#1B65A8}` 线上从未生效
 *    （2026-11-13 本地实测 computed = #409eff）。所以映射块必须是 `html:root`（0,1,1）。
 * 3. main.ts 的导入顺序：tokens.css 先于 style.css（后者引用 --cs-*），element-plus-theme.css 最后。
 */
const ROOT = resolve(__dirname, '../..');
const read = (p: string) => readFileSync(resolve(ROOT, p), 'utf8');

function parseCssVars(css: string, selectorPattern: RegExp): Map<string, string> {
  const m = css.replace(/\/\*[\s\S]*?\*\//g, '').match(selectorPattern);
  if (!m) throw new Error(`selector block not found: ${selectorPattern}`);
  const body = m[1];
  const vars = new Map<string, string>();
  for (const decl of body.split(';')) {
    const i = decl.indexOf(':');
    if (i < 0) continue;
    const name = decl.slice(0, i).trim();
    if (name.startsWith('--')) vars.set(name, decl.slice(i + 1).trim());
  }
  return vars;
}

describe('P1 设计系统接线', () => {
  const tokensCss = read('styles/tokens.css');
  const csVars = parseCssVars(tokensCss, /:root\s*\{([\s\S]*?)\}/);

  it('tokens.css 含原型的核心 --cs-* token（数量不为 0，防仪器空转）', () => {
    expect(csVars.size).toBeGreaterThanOrEqual(40);
    expect(csVars.get('--cs-primary')).toBe('#1b65a8');
  });

  it('csTokens.ts 每一项与 tokens.css 逐字相等', () => {
    const keys = Object.keys(CS_TOKENS) as CsTokenName[];
    expect(keys.length).toBeGreaterThan(10);
    const mismatches = keys
      .map((k) => ({ k, css: csVars.get(csVarName(k)), ts: CS_TOKENS[k] }))
      .filter(({ css, ts }) => css !== ts);
    expect(mismatches).toEqual([]);
  });

  it('Element Plus 映射块用 html:root（盖得过晚加载的 element-plus/dist/index.css）且主色指向 --cs-primary', () => {
    const elCss = read('styles/element-plus-theme.css');
    const light = parseCssVars(elCss, /(?:^|\n)html:root\s*\{([\s\S]*?)\}/);
    for (const [el, cs] of [
      ['--el-color-primary', 'var(--cs-primary)'],
      ['--el-color-success', 'var(--cs-success)'],
      ['--el-color-warning', 'var(--cs-warning)'],
      ['--el-color-danger', 'var(--cs-danger)'],
      ['--el-color-error', 'var(--cs-danger)'],
      ['--el-border-color', 'var(--cs-border)'],
      ['--el-text-color-primary', 'var(--cs-text)'],
      ['--el-border-radius-base', 'var(--cs-radius-sm)'],
      ['--el-font-family', 'var(--cs-font-family)'],
    ]) {
      expect(light.get(el), el).toBe(cs);
    }
    // 不允许再有同特异性的 :root 写 --el-color-primary（会被 Element Plus 默认值静默盖掉）
    for (const f of ['style.css', 'App.vue']) {
      const src = read(f);
      const plainRootBlocks = [...src.matchAll(/(?:^|\n)\s*:root\s*\{([\s\S]*?)\}/g)].map((x) => x[1]);
      expect(plainRootBlocks.some((b) => /--el-color-primary\s*:/.test(b)), f).toBe(false);
    }
  });

  it('main.ts 导入顺序：tokens.css → style.css → element-plus-theme.css', () => {
    const main = read('main.ts');
    const a = main.indexOf("import './styles/tokens.css'");
    const b = main.indexOf("import './style.css'");
    const c = main.indexOf("import './styles/element-plus-theme.css'");
    expect([a, b, c].every((i) => i >= 0)).toBe(true);
    expect(a < b && b < c).toBe(true);
  });
});
