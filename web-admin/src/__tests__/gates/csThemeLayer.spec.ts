import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Element Plus 主题层（src/styles/cs-theme/*.css）接线闸：
 * 1. 每条规则都以 html:root 起头，才盖得过晚加载的 element-plus/dist/index.css（同 csTokensMirror 的原因）；
 * 2. 只取 --cs-* token，不写死颜色（允许 #fff / #000 作 color-mix 的混合端与按钮白字）；
 * 3. main.ts 在 element-plus-theme.css 之后导入这三个文件，否则顺序被打乱后覆盖失效。
 */
const ROOT = resolve(__dirname, '../..');
const DIR = resolve(ROOT, 'styles/cs-theme');
const files = readdirSync(DIR).filter((f) => f.endsWith('.css'));
const strip = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, '');

describe('cs-theme 主题层', () => {
  it('三个主题文件都在（防仪器空转）', () => {
    expect(files.sort()).toEqual(['controls.css', 'data-display.css', 'overlays.css']);
  });

  it.each(files)('%s：每条选择器以 html:root 起头', (f) => {
    const css = strip(readFileSync(resolve(DIR, f), 'utf8'));
    const bad: string[] = [];
    for (const m of css.matchAll(/(^|\})\s*([^{}@][^{}]*)\{/g)) {
      for (const sel of m[2].split(',').map((s) => s.trim()).filter(Boolean)) {
        if (!sel.startsWith('html:root')) bad.push(sel);
      }
    }
    expect(bad).toEqual([]);
  });

  it.each(files)('%s：不写死颜色（只允许 #fff/#000 作混合端）', (f) => {
    const css = strip(readFileSync(resolve(DIR, f), 'utf8'));
    const hex = [...css.matchAll(/#[0-9a-fA-F]{3,8}\b/g)].map((m) => m[0].toLowerCase()).filter((h) => h !== '#fff' && h !== '#000');
    expect(hex).toEqual([]);
  });

  it('main.ts 在 element-plus-theme.css 之后导入三个主题文件', () => {
    const main = readFileSync(resolve(ROOT, 'main.ts'), 'utf8');
    const base = main.indexOf("import './styles/element-plus-theme.css'");
    const idx = files.map((f) => main.indexOf(`import './styles/cs-theme/${f}'`));
    expect(base).toBeGreaterThanOrEqual(0);
    expect(idx.every((i) => i > base)).toBe(true);
  });
});
