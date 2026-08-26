import {
  displayProductName,
  formatCompactNumber,
  formatCurrency,
  formatDate,
  parseYmdOrToday,
  formatDateSlash,
  formatDateTime,
  formatDateTimeFull,
  formatMonthDay,
  formatNumberWithCommas,
  formatPercent,
  formatShortDateTime,
  formatTime,
  formatTimeFull,
} from '../../../utils/formatters';

describe('formatters', () => {
  const sampleDate = new Date(2026, 1, 9, 14, 30, 5);

  it('formats numbers without Intl dependencies', () => {
    expect(formatNumberWithCommas(12345)).toBe('12,345');
    expect(formatNumberWithCommas(-12345.67)).toBe('-12,345.67');
    expect(formatNumberWithCommas(null)).toBe('0');
    expect(formatCompactNumber(1234)).toBe('1,234');
    expect(formatCurrency(1234)).toContain('1,234');
    expect(formatPercent(0.856)).toBe('85.6%');
    expect(formatPercent(85.6)).toBe('85.6%');
    expect(formatPercent(null)).toBe('0%');
  });

  it('filters empty product names to a caller-provided fallback', () => {
    expect(displayProductName('  Product A  ', 'Pending')).toBe('Product A');
    expect(displayProductName('', 'Pending')).toBe('Pending');
    expect(displayProductName(null, 'Pending')).toBe('Pending');
  });

  it('formats dates and times manually', () => {
    expect(formatDate(sampleDate)).toBe('2026-02-09');
    expect(formatDateSlash(sampleDate)).toBe('2026/02/09');
    expect(formatMonthDay(sampleDate)).toBe('02-09');
    expect(formatTime(sampleDate)).toBe('14:30');
    expect(formatTimeFull(sampleDate)).toBe('14:30:05');
    expect(formatDateTime(sampleDate)).toBe('2026-02-09 14:30');
    expect(formatDateTimeFull(sampleDate)).toBe('2026-02-09 14:30:05');
    expect(formatShortDateTime(sampleDate)).toBe('02-09 14:30');
  });

  it('returns empty strings for invalid dates', () => {
    expect(formatDate('not-a-date')).toBe('');
    expect(formatTime(undefined)).toBe('');
  });
});

// ────────────────────────────────────────────────────────────────────────────
// 2026-08-26: 六个屏的私有 formatYmd/parseYmdOrToday 收敛到这里之后, 这两个函数
// 成了全 App 日期滚轮的唯一真源, 而 parseYmdOrToday 此前**零行为覆盖** ——
// 只有一条读源码的闸。源码闸只能说「函数体里有那几行字」, 说不了「它算得对」。
// ────────────────────────────────────────────────────────────────────────────
describe('formatDate: 本地日历日, 不许按 UTC 截断', () => {
  it('🔴 一天 24 个整点采样, 每个都等于本地 getter 拼出来的那天', () => {
    for (let h = 0; h < 24; h += 1) {
      const d = new Date(2026, 7, 26, h, 30, 0);
      const local =
        String(d.getFullYear()) +
        '-' +
        String(d.getMonth() + 1).padStart(2, '0') +
        '-' +
        String(d.getDate()).padStart(2, '0');
      expect(formatDate(d)).toBe(local);
    }
  });

  it('🔴 阳性对照: 本时区下确有整点让 toISOString 落到**前一天** —— 这条正是六个屏原来的缺陷', () => {
    const offsetMin = new Date(2026, 7, 26).getTimezoneOffset();
    if (offsetMin === 0) {
      // UTC 机器上这个对照天然不成立, 显式说明而不是静默跳过。
      expect(offsetMin).toBe(0);
      return;
    }
    const differing: string[] = [];
    for (let h = 0; h < 24; h += 1) {
      const d = new Date(2026, 7, 26, h, 30, 0);
      if (d.toISOString().slice(0, 10) !== formatDate(d)) {
        differing.push(String(h));
      }
    }
    // 东八区: 00:30~07:30 这 8 个整点 toISOString 会给出 2026-08-25。
    expect(differing.length).toBeGreaterThan(0);
  });
});

describe('parseYmdOrToday: 回读校验, 不许让 Date 悄悄滚月', () => {
  const now = new Date(2026, 7, 26, 12, 0, 0);

  it('🔴 2026-02-31 不存在 -> 退回 now, ⛔ 不许滚成 3 月 3 日', () => {
    // new Date(2026, 1, 31) 会静默滚到 2026-03-03。回读校验就是为了拦它。
    expect(parseYmdOrToday('2026-02-31', now)).toEqual(now);
    expect(parseYmdOrToday('2026-13-01', now)).toEqual(now);
    expect(parseYmdOrToday('2026-04-31', now)).toEqual(now);
  });

  it('合法日期原样解析成本地零点', () => {
    const d = parseYmdOrToday('2026-02-09', now);
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(1);
    expect(d.getDate()).toBe(9);
  });

  it('闰年 2028-02-29 是合法的, 不许被回读校验误杀', () => {
    const d = parseYmdOrToday('2028-02-29', now);
    expect(d.getDate()).toBe(29);
    expect(d.getMonth()).toBe(1);
  });

  it('空/格式不符/null 一律退回 now', () => {
    expect(parseYmdOrToday('', now)).toEqual(now);
    expect(parseYmdOrToday(null, now)).toEqual(now);
    expect(parseYmdOrToday(undefined, now)).toEqual(now);
    expect(parseYmdOrToday('2026/02/09', now)).toEqual(now);
    expect(parseYmdOrToday('2026-2-9', now)).toEqual(now);
  });

  it('🔴 与 formatDate 互为逆运算(往返不变形)', () => {
    for (const ymd of ['2026-01-01', '2026-08-26', '2026-12-31', '2028-02-29']) {
      expect(formatDate(parseYmdOrToday(ymd, now))).toBe(ymd);
    }
  });
});
