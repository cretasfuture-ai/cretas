/**
 * 源码闸：所有带 :min 的 UInputNumber 不得「吸附」出错误值。
 * Reka NumberField 把值吸附到 min + k × step。min=0.0001、step 缺省为 1 时，
 * 输入 12.5 失焦变 12.0001，保存下去就是静默存错价（2026-11-15 在 F006 真跑发现）。
 * 通过条件（任一）：step-snapping=false；或显式 step 且 min 是 step 的整数倍（网格对齐，不会偏移）。
 * vitest 里 UInputNumber 没装插件测不到吸附，所以守源码。
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

const SRC = join(__dirname, '..', '..');

function walk(dir: string, out: string[] = []): string[] {
  for (const n of readdirSync(dir)) {
    if (n === '__tests__' || n === 'node_modules') continue;
    const p = join(dir, n);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (n.endsWith('.vue')) out.push(p);
  }
  return out;
}

const num = (tag: string, attr: string) => {
  const m = tag.match(new RegExp(':' + attr + '="(-?[0-9.]+)"'));
  return m ? Number(m[1]) : undefined;
};

export function badTags(tags: string[]): string[] {
  const bad: string[] = [];
  for (const tag of tags) {
    const min = num(tag, 'min');
    if (min === undefined) continue;
    if (/:?step-snapping="false"/.test(tag)) continue;
    const step = num(tag, 'step');
    const aligned = step !== undefined && step > 0 && Math.abs(min / step - Math.round(min / step)) < 1e-9;
    if (!aligned) bad.push(tag.slice(0, 120));
  }
  return bad;
}

describe('UInputNumber 带 min 时不吸附', () => {
  const files = walk(SRC);
  const all = files.flatMap((f) =>
    (readFileSync(f, 'utf-8').match(/<UInputNumber[^>]*>/g) ?? []).map((t) => ({ f: relative(SRC, f).split(sep).join('/'), t })),
  );

  it('仪器自检：扫到了源码，且守得住价格输入框', () => {
    expect(files.length).toBeGreaterThan(500);
    expect(all.some((x) => x.t.includes('data-testid="price-input"'))).toBe(true);
  });

  it('判据本身：缺 step 的会被判坏，step=0.0001 的通过', () => {
    expect(badTags(['<UInputNumber :min="0.0001" />'])).toHaveLength(1);
    expect(badTags(['<UInputNumber :min="0.0001" :step="0.0001" />'])).toHaveLength(0);
    expect(badTags(['<UInputNumber :min="0.0001" :step-snapping="false" />'])).toHaveLength(0);
    expect(badTags(['<UInputNumber :min="0.5" :step="1" />'])).toHaveLength(1);
  });

  it('仓库里每个带 min 的 UInputNumber 都通过', () => {
    const offenders = all.filter((x) => badTags([x.t]).length).map((x) => x.f + ': ' + x.t.slice(0, 100));
    expect(offenders).toEqual([]);
  });
});
