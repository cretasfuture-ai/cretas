import { describe, it, expect } from 'vitest';
import { resolveAllHistoryRange, wideFallbackRange, isoDate } from '../analysisDefaults';

const NOW = new Date('2026-06-02T12:00:00Z');

describe('analysisDefaults — 全部历史默认窗 (WS4 Task 1 / #10/#11/#12)', () => {
  it('isoDate → 本地日历日 YYYY-MM-DD (⛔ 不是 UTC 日)', () => {
    // 2026-08-26: 这条原来断言 '2026-06-02' —— 守的是 `toISOString().slice(0,10)`
    // 那个**旧行为**(UTC 日), 而不是需求。isoDate 的产出是发给后端当
    // LocalDate 用的业务日历日, 后端全线 GMT+8 ⇒ 必须是**本地**日。
    // 23:59Z 在东八区已经是次日 07:59, 正是这条缺陷的边界。
    const utcLateEvening = new Date('2026-06-02T23:59:00Z');
    const p = (n: number) => String(n).padStart(2, '0');
    const localDay =
      utcLateEvening.getFullYear() + '-' +
      p(utcLateEvening.getMonth() + 1) + '-' +
      p(utcLateEvening.getDate());
    expect(isoDate(utcLateEvening)).toBe(localDay);

    // 阴性对照: 必须存在一个时刻, 让「本地日」与「UTC 截断」给出**不同**的答案 ——
    // 否则这条断言就是恒真式, 什么都没守住。
    //
    // 🔴 2026-08-27 订正: 原来写死用 23:59Z 且只用 `getTimezoneOffset() !== 0` 放行,
    //    在**东**半球(UTC+8)成立(23:59Z = 次日 07:59), 但在**西**半球(PDT, UTC-7)
    //    23:59Z 仍是同一天 -> 两者相同 -> 阴性对照必然失败。
    //    实测: 加拿大的自建 runner 上这条红了, 而东八区本机全绿。
    // ⇒ 不放宽守卫(那样西半球等于没对照), 而是**按时区挑一个真能跨日的时刻**。
    const offsetMin = new Date('2026-06-02T12:00:00Z').getTimezoneOffset();
    if (offsetMin !== 0) {
      // offset > 0 = 西半球(如 PDT 420): 取当日 00:30Z, 本地仍是前一天
      // offset < 0 = 东半球(如 CST -480): 取当日 23:30Z, 本地已是次日
      const crossing = offsetMin > 0
        ? new Date('2026-06-02T00:30:00Z')
        : new Date('2026-06-02T23:30:00Z');
      expect(isoDate(crossing)).not.toBe(crossing.toISOString().slice(0, 10));
    }
  });

  it('真实数据窗 → 原样返回 [minDate, maxDate] (全部历史)', () => {
    const r = resolveAllHistoryRange({ minDate: '2025-01-01', maxDate: '2025-12-31', dayCount: 365 }, 730, NOW);
    expect(r).toEqual(['2025-01-01', '2025-12-31']);
  });

  it('probe null → 宽回退窗 (NOT 近30天)', () => {
    const r = resolveAllHistoryRange(null, 730, NOW);
    // end = today, start = 730d ago → 远早于"近30天"
    expect(r[1]).toBe('2026-06-02');
    expect(r[0]).toBe(wideFallbackRange(730, NOW)[0]);
    // 关键回归断言: 起点不是"近30天" (#11)
    const thirtyDaysAgo = new Date(NOW); thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);
    expect(r[0] < isoDate(thirtyDaysAgo)).toBe(true);
  });

  it('probe 有 null 边界 → 宽回退窗', () => {
    const r = resolveAllHistoryRange({ minDate: null, maxDate: null }, 730, NOW);
    expect(r).toEqual(wideFallbackRange(730, NOW));
  });

  it('wideFallbackRange(730) 起点约 2 年前', () => {
    const [start, end] = wideFallbackRange(730, NOW);
    expect(end).toBe('2026-06-02');
    expect(start).toBe('2024-06-03'); // 729天前 (含端点 → 730天窗)
  });
});
