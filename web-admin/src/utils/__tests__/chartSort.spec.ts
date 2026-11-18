import { describe, expect, it } from 'vitest';
import { sortChartRows, tableSortBy } from '../chartSort';

type R = { name: string; v: number | null; unit: string };
const rows: R[] = [
  { name: '卤鸡爪', v: 12, unit: '盒' },
  { name: '散装牛肉', v: 80, unit: 'kg' },
  { name: '卤牛肉', v: 30, unit: '盒' },
  { name: '未填产品', v: null, unit: '盒' },
  { name: '卤豆干', v: 6, unit: '盒' },
];
const names = (list: R[]) => list.map(r => r.name);
const sort = (mode: Parameters<typeof sortChartRows>[1], group?: (r: R) => string) =>
  names(sortChartRows(rows, mode, r => r.v, r => r.name, group));

describe('sortChartRows', () => {
  it('数值降序 / 升序：空值都在最后，不当 0', () => {
    expect(sort('valueDesc')).toEqual(['散装牛肉', '卤牛肉', '卤鸡爪', '卤豆干', '未填产品']);
    expect(sort('valueAsc')).toEqual(['卤豆干', '卤鸡爪', '卤牛肉', '散装牛肉', '未填产品']);
  });

  it('按名称（中文拼音序）与原始顺序', () => {
    expect(sort('name')).toEqual(['卤豆干', '卤鸡爪', '卤牛肉', '散装牛肉', '未填产品']);
    expect(sort('original')).toEqual(names(rows));
  });

  it('分组时组按首次出现排列、组内排序（盒和 kg 不混比）', () => {
    expect(sort('valueDesc', r => r.unit)).toEqual(['卤牛肉', '卤鸡爪', '卤豆干', '未填产品', '散装牛肉']);
  });

  it('不改原数组', () => {
    const before = names(rows);
    sort('valueAsc');
    expect(names(rows)).toEqual(before);
  });
});

describe('tableSortBy', () => {
  it('数字按大小、文字按拼音、空值最小', () => {
    const byV = tableSortBy<R>(r => r.v);
    expect([...rows].sort(byV).map(r => r.name)).toEqual(['未填产品', '卤豆干', '卤鸡爪', '卤牛肉', '散装牛肉']);
    const byName = tableSortBy<R>(r => r.name);
    expect([...rows].sort(byName).map(r => r.name)[0]).toBe('卤豆干');
  });
});
