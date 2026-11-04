import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { balanceProcessLabel } from '../balanceProcessLabel';

describe('balanceProcessLabel 工序名兜底', () => {
  it('有名字用名字', () => {
    expect(balanceProcessLabel('卤煮', 2)).toBe('卤煮');
  });
  it('名字为空/空白用「第 N 道」', () => {
    expect(balanceProcessLabel('', 3)).toBe('第 3 道');
    expect(balanceProcessLabel('   ', 1)).toBe('第 1 道');
    expect(balanceProcessLabel(null, 4)).toBe('第 4 道');
  });
  it('名字和顺序都没有如实标未命名, 不留白', () => {
    expect(balanceProcessLabel(null, null)).toBe('未命名工序');
    expect(balanceProcessLabel('', 0)).toBe('未命名工序');
  });
  it('平衡表的工序列走这个兜底, 不再直接渲染 processName', () => {
    const src = readFileSync(resolve(__dirname, '../ProcessIOComparison.vue'), 'utf-8');
    expect(src).toContain('balanceProcessLabel(row.processName, row.processOrder)');
    expect(src).not.toContain('<el-table-column prop="processName" label="工序" min-width="120" />');
  });
});
