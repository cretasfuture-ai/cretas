/**
 * 「我的工资」展示逻辑的判据。
 *
 * <h2>🔴 这个文件真正在守的东西</h2>
 *
 * 只有一条: <b>「我还没查到」不许被显示成「0 元」</b>。
 * 在工资场景里这是最糟的错误 —— 工人会以为自己白干了一个月, 然后带着一个错的
 * 结论去找财务, 而这正是这个功能本来要消灭的扯皮。
 *
 * 所以每条格式化断言都配一条<b>阴性对照</b>: 拿到 `null` 时结果里不许出现字符 `0`
 * 组成的金额。光断言「返回 —」不够 —— `?? 0` 那种写法在某些实现下也可能返回
 * 一个含 `—` 的串。
 */

import type { MyWagePayroll } from '../../../services/api/myWageApiClient';
import {
  EMPTY,
  breakdownGap,
  currentMonth,
  explainUnavailable,
  formatCount,
  formatHours,
  formatMinutesAsHours,
  formatMoney,
  formatMonthLabel,
  formatRate,
  payrollBreakdown,
  payrollTone,
  shiftMonth,
  formatDayLabel,
} from '../../../utils/myWageFormat';

function payroll(overrides: Partial<MyWagePayroll> = {}): MyWagePayroll {
  return {
    id: 1,
    periodStart: '2026-08-01',
    periodEnd: '2026-08-31',
    periodType: 'MONTHLY',
    status: 'PAID',
    statusLabel: '已发放',
    paid: true,
    baseSalary: null,
    pieceRateWage: null,
    overtimeWage: null,
    overtimeHours: null,
    bonusAmount: null,
    deductionAmount: null,
    totalWage: null,
    totalPieceCount: null,
    totalWorkHours: null,
    approvedAt: null,
    paidAt: null,
    ...overrides,
  };
}

describe('⛔ 拿不到的数一律显示「—」, 绝不显示 0', () => {
  // 每个格式化函数逐个过一遍 null / undefined / NaN。
  // 参数化的三个输入必须各自独立走到, 否则「三次全绿」可能只是同一个样本跑了三遍。
  const absentInputs: Array<[string, number | null | undefined]> = [
    ['null', null],
    ['undefined', undefined],
    ['NaN', Number.NaN],
  ];

  const formatters: Array<[string, (v: number | null | undefined) => string]> = [
    ['formatMoney', formatMoney],
    ['formatCount', (v) => formatCount(v)],
    ['formatMinutesAsHours', formatMinutesAsHours],
    ['formatHours', formatHours],
    ['formatRate', formatRate],
  ];

  it.each(formatters)('%s 在没有值时返回「—」且不含任何数字', (name, fn) => {
    for (const [label, input] of absentInputs) {
      const out = fn(input);
      expect(`${name}(${label}) => ${out}`).toBe(`${name}(${label}) => ${EMPTY}`);
      // 🔴 阴性对照: 输出里不许出现任何数字。这条才是真正挡住 `?? 0` 的那一条 ——
      //    只断言 "=== '—'" 的话, 一个返回 "—0" 的实现也过不了, 但一个
      //    返回 "0 件" 的实现会在别的断言里漏网。
      expect(/\d/.test(out)).toBe(false);
    }
  });

  // 阳性对照: 证明上面那组断言不是因为「函数永远返回 —」而恒真。
  it('有值时确实会输出数字 (证明上面那组不是恒真式)', () => {
    expect(formatMoney(1234.5)).toBe('¥1,234.50');
    expect(formatCount(26)).toBe('26 件');
    expect(formatMinutesAsHours(480)).toBe('8.0 小时');
    expect(formatHours(7.25)).toBe('7.3 小时');
    expect(formatRate(0.8)).toBe('¥0.80/件');
  });

  it('真的是 0 时显示 0 —— 「没有这个数」和「这个数是 0」必须分得开', () => {
    expect(formatMoney(0)).toBe('¥0.00');
    expect(formatCount(0)).toBe('0 件');
    // 而 null 走的是另一条路
    expect(formatMoney(null)).toBe(EMPTY);
    expect(formatCount(null)).toBe(EMPTY);
  });

  it('负数 (扣款大于收入) 带负号, 不被吃掉', () => {
    expect(formatMoney(-50.5)).toBe('-¥50.50');
  });

  it('千分位分组正确', () => {
    expect(formatMoney(1000000)).toBe('¥1,000,000.00');
    expect(formatMoney(999)).toBe('¥999.00');
  });
});

describe('月份计算', () => {
  it('currentMonth 补零', () => {
    expect(currentMonth(new Date(2026, 0, 15))).toBe('2026-01');
    expect(currentMonth(new Date(2026, 11, 1))).toBe('2026-12');
  });

  it('shiftMonth 跨年正确 —— 1月的上个月是去年12月', () => {
    expect(shiftMonth('2026-01', -1)).toBe('2025-12');
    expect(shiftMonth('2026-08', -1)).toBe('2026-07');
    expect(shiftMonth('2026-12', 1)).toBe('2027-01');
  });

  it('formatMonthLabel / formatDayLabel 不产生 Invalid Date', () => {
    expect(formatMonthLabel('2026-08')).toBe('2026年8月');
    expect(formatDayLabel('2026-08-03')).toBe('8月3日');
    // 认不出来就原样返回, ⛔ 不抛、不显示 Invalid Date
    expect(formatDayLabel('whatever')).toBe('whatever');
    expect(formatDayLabel(null)).toBe(EMPTY);
  });
});

describe('⛔ 工资单分项: null 不补 0 行', () => {
  it('只有拿到值的项才成行 —— 一张全 null 的单产出 0 行, 不是 5 行 ¥0.00', () => {
    expect(payrollBreakdown(payroll())).toEqual([]);
  });

  it('拿到值的项成行, 扣款标为减项', () => {
    const rows = payrollBreakdown(payroll({
      baseSalary: 1000,
      pieceRateWage: 2000,
      deductionAmount: 50,
    }));
    expect(rows).toEqual([
      { label: '底薪', value: 1000, negative: false },
      { label: '计件', value: 2000, negative: false },
      { label: '扣款', value: 50, negative: true },
    ]);
  });

  it('值为 0 的项**要**成行 —— 「这个月奖金是 0」是真信息', () => {
    const rows = payrollBreakdown(payroll({ bonusAmount: 0 }));
    expect(rows).toEqual([{ label: '奖金', value: 0, negative: false }]);
  });
});

describe('🔴 分项对不上合计要能被发现 (工人拿去核对的那句话)', () => {
  it('对得上时差为 0', () => {
    expect(breakdownGap(payroll({
      baseSalary: 1000, pieceRateWage: 2000, deductionAmount: 50, totalWage: 2950,
    }))).toBe(0);
  });

  it('对不上时算出差额 —— ⛔ 不悄悄拿分项之和顶替合计', () => {
    const p = payroll({ baseSalary: 1000, pieceRateWage: 2000, totalWage: 2950 });
    expect(breakdownGap(p)).toBe(50);
    // 合计本身没被改动过
    expect(p.totalWage).toBe(2950);
  });

  it('合计缺失 / 没有分项时返回 null —— ⛔ 不返回 0 (那会被读成「对得上」)', () => {
    expect(breakdownGap(payroll({ baseSalary: 1000 }))).toBeNull();
    expect(breakdownGap(payroll({ totalWage: 100 }))).toBeNull();
  });
});

describe('状态分档', () => {
  it('三态各自成档, 未知状态不被归进任何一档', () => {
    expect(payrollTone('PAID')).toBe('paid');
    expect(payrollTone('APPROVED')).toBe('approved');
    expect(payrollTone('PENDING')).toBe('pending');
    // ⛔ 数据里出现三态之外的值时不假装认识它
    expect(payrollTone('DRAFT')).toBe('unknown');
    expect(payrollTone(null)).toBe('unknown');
  });
});

describe('「为什么没有这个数」说人话', () => {
  const reasons = [
    'NO_PAYROLL_FOR_PERIOD',
    'NOT_CALCULATED_YET',
    'NO_PIECE_RATE_RULE',
    'NO_HOURLY_RATE_RULE',
    'NOT_APPLICABLE_FOR_MODE',
  ] as const;

  it('每个原因码都有一句解释, 且不含英文码/表名/「暂无数据」', () => {
    for (const r of reasons) {
      const text = explainUnavailable(r);
      expect(text.length).toBeGreaterThan(8);
      // ⛔ 不许把原因码本身漏给工人
      expect(text).not.toContain(r);
      expect(text).not.toMatch(/[A-Z]{3,}/);
      // ⛔ 「暂无数据」什么都没解释, 工人只会更慌
      expect(text).not.toContain('暂无');
    }
  });

  // 阳性对照: 上面那组断言不是因为「函数永远返回空串」而恒真。
  it('未知原因返回空串 (屏上就只显示「—」)', () => {
    expect(explainUnavailable(null)).toBe('');
    expect(explainUnavailable(undefined)).toBe('');
  });
});
