import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 数字班组首页「和XX说」主按钮是蓝底蓝字（空白蓝块）的根因：
 * <UButton to=...> 渲染成 <a>，全局未分层的 `a { color }` 优先级高于 Tailwind utilities 层，
 * 把 Nuxt UI 的 text-inverted（白字）盖成蓝字。所以全局裸 `a` 规则必须在 legacy-reset 层里。
 */
function stripComments(s: string) { return s.replace(/\/\*[\s\S]*?\*\//g, ''); }

/** 返回顶层（不在任何 @layer / @media 块里）的规则选择器。 */
function topLevelSelectors(css: string): string[] {
  const out: string[] = [];
  let depth = 0; let buf = '';
  for (const ch of css) {
    if (ch === '{') { if (depth === 0) out.push(buf.trim()); depth++; buf = ''; }
    else if (ch === '}') { depth--; buf = ''; }
    else if (depth === 0) buf += ch;
  }
  return out;
}

export const unlayeredAnchorRules = (css: string) =>
  topLevelSelectors(stripComments(css)).filter((sel) => /(^|,)\s*a(:(hover|visited|active|link))?\s*(,|$)/.test(sel) && !sel.startsWith('@'));

describe('全局 a 链接色不许压过 Tailwind utilities', () => {
  const css = readFileSync(resolve(__dirname, '..', 'style.css'), 'utf8');
  it('style.css 没有未分层的裸 a / a:hover 规则', () => {
    expect(unlayeredAnchorRules(css)).toEqual([]);
  });
  it('阴性对照：未分层写法能被闸认出', () => {
    expect(unlayeredAnchorRules('a { color: red; }\n@layer x { a { color: blue } }')).toEqual(['a']);
  });
  it('链接色规则仍在（放进了 legacy-reset 层，没被误删）', () => {
    expect(stripComments(css)).toMatch(/@layer legacy-reset\s*\{\s*a\s*\{[^}]*color:\s*var\(--primary-color\)/);
  });
});
