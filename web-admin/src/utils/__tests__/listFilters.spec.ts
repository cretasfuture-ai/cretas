import { describe, it, expect } from 'vitest';
import { parseBatchNos, relativeRange, MAX_BATCH_NOS } from '../listFilters';

describe('parseBatchNos', () => {
  it('换行/逗号/顿号/空白分隔, 保序去重, 不改大小写', () => {
    const r = parseBatchNos('PB-1\nPB-2，PB-1、pb-2; PB-3  PB-4');
    expect(r.nos).toEqual(['PB-1', 'PB-2', 'pb-2', 'PB-3', 'PB-4']);
    expect(r.overLimit).toBe(false);
  });
  it('空输入没有单号', () => {
    expect(parseBatchNos('  \n ,, ').nos).toEqual([]);
  });
  it('超过 200 个不静默截断: 标记 overLimit', () => {
    const text = Array.from({ length: MAX_BATCH_NOS + 5 }, (_, i) => `N${i}`).join('\n');
    const r = parseBatchNos(text);
    expect(r.nos).toHaveLength(MAX_BATCH_NOS);
    expect(r.overLimit).toBe(true);
  });
  it('恰好 200 个不算超限', () => {
    const text = Array.from({ length: MAX_BATCH_NOS }, (_, i) => `N${i}`).join(',');
    expect(parseBatchNos(text).overLimit).toBe(false);
  });
});

describe('relativeRange (北京时间, R18)', () => {
  // 2026-11-14 是周六; 取北京时间中午
  const sat = new Date('2026-11-14T04:00:00Z');
  it('今天 / 近 7 天 / 近 30 天截止今天', () => {
    expect(relativeRange('today', sat)).toMatchObject({ from: '2026-11-14', to: '2026-11-14' });
    expect(relativeRange('last7', sat)).toMatchObject({ from: '2026-11-08', to: '2026-11-14' });
    expect(relativeRange('last30', sat)).toMatchObject({ from: '2026-10-16', to: '2026-11-14' });
  });
  it('本周 = 周一至周日整周, 本月 = 1 日至月末', () => {
    expect(relativeRange('week', sat)).toMatchObject({ from: '2026-11-09', to: '2026-11-15' });
    expect(relativeRange('month', sat)).toMatchObject({ from: '2026-11-01', to: '2026-11-30' });
  });
  it('周日仍属于本周 (周一起算)', () => {
    const sun = new Date('2026-11-15T04:00:00Z');
    expect(relativeRange('week', sun)).toMatchObject({ from: '2026-11-09', to: '2026-11-15' });
  });
  it('北京时间已过零点: UTC 还是前一天, 按北京算', () => {
    const lateUtc = new Date('2026-11-14T17:30:00Z'); // 北京 11-15 01:30
    expect(relativeRange('today', lateUtc).from).toBe('2026-11-15');
  });
  it('月末跨月 (2 月)', () => {
    expect(relativeRange('month', new Date('2027-02-10T04:00:00Z'))).toMatchObject({ from: '2027-02-01', to: '2027-02-28' });
  });
});
