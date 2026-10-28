import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { pickPrefilled, readReadinessPrefill } from '../readinessPrefill';

const strip = (src: string) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
const read = (rel: string) => strip(readFileSync(resolve(process.cwd(), rel), 'utf8'));

describe('就绪页 → 首笔业务: 带入数据', () => {
  it('只有 fromReadiness=1 才读取, 且只取指定键、去空白', () => {
    expect(readReadinessPrefill({ customerId: 'C1' }, ['customerId'])).toBeNull();
    expect(readReadinessPrefill({ fromReadiness: '1', customerId: ' C1 ', other: 'x' }, ['customerId']))
      .toEqual({ customerId: 'C1' });
    expect(readReadinessPrefill({ fromReadiness: '1' }, ['customerId'])).toBeNull();
  });

  it('按 id 在已加载列表里核对, 找不到返回 undefined', () => {
    const rows = [{ id: 7 }, { id: 'a' }];
    expect(pickPrefilled(rows, '7')).toBe(rows[0]);
    expect(pickPrefilled(rows, 'zzz')).toBeUndefined();
    expect(pickPrefilled(rows, undefined)).toBeUndefined();
  });

  it.each([
    ['src/views/sales/orders/new.vue', 'applyReadinessPrefill()'],
    ['src/views/procurement/orders/list.vue', 'applyReadinessPrefill()'],
    ['src/views/production/plans/list.vue', 'maybeOpenFromReadiness()'],
  ])('%s 真的调用了带入逻辑', (file, call) => {
    const src = read(file);
    expect(src).toContain('readReadinessPrefill(');
    // 定义 + 至少一次调用
    expect(src.split(call).length - 1).toBeGreaterThanOrEqual(2);
  });

  it('就绪页对已就绪业务渲染 firstAction 链接, 并带 query', () => {
    const src = read('src/views/platform/factory-review/components/FactoryReadiness.vue');
    expect(src).toContain('domain.firstAction');
    expect(src).toContain('query: domain.firstAction.query');
    expect(src).toContain("MATERIAL_PACKAGING: '/warehouse/material-types'");
    expect(src).toContain("USERS: '/system/users'");
  });
});
