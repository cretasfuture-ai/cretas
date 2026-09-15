import { describe, expect, it } from 'vitest';
import { formatQueryTime, formatTimeRange } from '../traceDisplay';

/**
 * 时间格式化 —— 投屏机浏览器语言是英文, `toLocaleString()` 出来是 `9/15/2026, 6:46:38 PM`。
 * 这里钉住: 后端 LocalDateTime → `YYYY-MM-DD HH:mm`; 解析不了 → 空串 (不抛、不是 `Invalid Date`)。
 */
describe('traceDisplay — formatQueryTime', () => {
  it('LocalDateTime → YYYY-MM-DD HH:mm (秒丢掉)', () => {
    expect(formatQueryTime('2026-09-15T18:46:38')).toBe('2026-09-15 18:46');
  });

  it('没有毫秒 / 有毫秒都一样', () => {
    expect(formatQueryTime('2026-09-15T10:00:00.123')).toBe('2026-09-15 10:00');
  });

  it.each([
    ['null', null],
    ['undefined', undefined],
    ['空串', ''],
    ['只有空格', '   '],
    ['乱串', 'not-a-date'],
  ])('对照: %s ⇒ 空串, 不抛', (_label, input) => {
    expect(() => formatQueryTime(input as string | null | undefined)).not.toThrow();
    expect(formatQueryTime(input as string | null | undefined)).toBe('');
  });

  it('⛔ undefined 不是「现在」: dayjs(undefined) 会给当前时间, 这里必须挡住', () => {
    expect(formatQueryTime(undefined)).toBe('');
  });
});

describe('traceDisplay — formatTimeRange', () => {
  it('同一天: 日期只念一次', () => {
    expect(formatTimeRange('2026-09-10T08:00:00', '2026-09-10T08:40:00')).toBe('2026-09-10 08:00 ~ 08:40');
  });

  it('跨天: 两头都带日期', () => {
    expect(formatTimeRange('2026-09-10T23:30:00', '2026-09-11T00:20:00')).toBe(
      '2026-09-10 23:30 ~ 2026-09-11 00:20'
    );
  });

  it('只有起点 ⇒ 只显示起点', () => {
    expect(formatTimeRange('2026-09-10T08:00:00', null)).toBe('2026-09-10 08:00');
  });

  it('对照: 起点解析不了 ⇒ 整段空串 (止点再好也不单独显示)', () => {
    expect(formatTimeRange('garbage', '2026-09-10T08:40:00')).toBe('');
    expect(formatTimeRange(null, '2026-09-10T08:40:00')).toBe('');
  });
});
