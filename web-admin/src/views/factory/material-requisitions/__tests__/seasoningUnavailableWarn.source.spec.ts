import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');

/**
 * 2026-11-10: 后端「按计划生成」调料表读取失败时单据照常生成, 但不追加调料行,
 * 返回 data.seasoningItemsStatus=UNAVAILABLE。网页原来写死弹「领料单已生成」,
 * 用户会以为配方本来没有调料 —— 漏调料。
 */
describe('按计划生成领料单: 调料读取失败要明说, 不能只报已生成', () => {
  const handleCreate = source.slice(source.indexOf('async function handleCreate'), source.indexOf('async function fetchDetail'));

  it('读 seasoningItemsStatus 并在 UNAVAILABLE 时给常驻警告', () => {
    expect(handleCreate).toContain('seasoningItemsStatus');
    expect(handleCreate).toMatch(/=== 'UNAVAILABLE'[\s\S]{0,200}type: 'warning'/);
    expect(handleCreate).toMatch(/调料行未列入[\s\S]{0,120}duration: 0/);
  });

  it('只有非 UNAVAILABLE 才报「领料单已生成」成功提示', () => {
    expect(handleCreate).toMatch(/else \{\s*ElMessage\.success\('领料单已生成'\)/);
  });
});
