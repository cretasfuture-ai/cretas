/**
 * 「我的工资」屏的纯展示逻辑。
 *
 * <h2>⛔ 这个文件存在的唯一理由: 把「拿不到就显示 —」变成一条<b>可测</b>的规则</h2>
 *
 * 如果格式化散在 JSX 里, 就没人挡得住某天有人写下 `{data.totalWage ?? 0}` ——
 * 那一行会把「我还没查到」显示成「0 元」。在工资这件事上, 这是最糟的错误:
 * 工人会以为自己白干了一个月, 然后带着一个错的结论去找财务。
 *
 * ⇒ 所有金额/工时/数量都必须过这里的函数, 而这里的函数在拿到
 *   `null` / `undefined` / `NaN` 时<b>一律</b>返回 {@link EMPTY}, 永远不返回 0。
 *   配套测试 `src/__tests__/unit/utils/myWageFormat.test.ts` 钉住这条。
 *
 * @since 2026-08-25
 */

import type {
  MyWagePayroll,
  PayrollStatus,
  UnavailableReason,
} from '../services/api/myWageApiClient';

/** 「没有这个数」的统一显示。⛔ 不是 0, 不是空字符串 —— 空字符串会被读成「漏了」。 */
export const EMPTY = '—';

// ──────────────────────────────────────────────────────────────────────────────
// 金额 / 数量 / 工时
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 金额。`null` / `undefined` / `NaN` → {@link EMPTY}。
 *
 * ⛔ 绝不返回 `¥0.00` 兜底。真的是 0 元 (例如这个月扣款正好抵消) 才显示 `¥0.00`。
 */
export function formatMoney(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY;
  }
  const abs = Math.abs(value).toFixed(2);
  const [intPart = '0', decPart = '00'] = abs.split('.');
  const grouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${value < 0 ? '-' : ''}¥${grouped}.${decPart}`;
}

/** 件数。`null` → {@link EMPTY} (⛔ 「这条没记件数」不是「0 件」)。 */
export function formatCount(value: number | null | undefined, unit = '件'): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return EMPTY;
  }
  return `${value} ${unit}`;
}

/** 分钟 → 「8.0 小时」。`null` → {@link EMPTY}。 */
export function formatMinutesAsHours(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined || Number.isNaN(minutes)) {
    return EMPTY;
  }
  return `${(minutes / 60).toFixed(1)} 小时`;
}

/** 小时数直出。`null` → {@link EMPTY}。 */
export function formatHours(hours: number | null | undefined): string {
  if (hours === null || hours === undefined || Number.isNaN(hours)) {
    return EMPTY;
  }
  return `${hours.toFixed(1)} 小时`;
}

/** 单价 (元/件)。`null` → {@link EMPTY}。 */
export function formatRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) {
    return EMPTY;
  }
  return `¥${rate.toFixed(2)}/件`;
}

// ──────────────────────────────────────────────────────────────────────────────
// 日期 / 月份
// ──────────────────────────────────────────────────────────────────────────────

/** ISO date `"2026-08-03"` → `"8月3日"`。认不出来就原样返回, ⛔ 不抛异常、不显示 Invalid Date。 */
export function formatDayLabel(isoDate: string | null | undefined): string {
  if (!isoDate) {
    return EMPTY;
  }
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(isoDate);
  if (!m) {
    return isoDate;
  }
  return `${Number(m[2])}月${Number(m[3])}日`;
}

/** `"2026-08"` → `"2026年8月"`。 */
export function formatMonthLabel(month: string | null | undefined): string {
  if (!month) {
    return EMPTY;
  }
  const m = /^(\d{4})-(\d{2})$/.exec(month);
  if (!m) {
    return month;
  }
  return `${m[1]}年${Number(m[2])}月`;
}

/** 当前月份 `yyyy-MM` (本地时区)。 */
export function currentMonth(now: Date = new Date()): string {
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
}

/** `"2026-01"` → `"2025-12"`。跨年正确。 */
export function shiftMonth(month: string, delta: number): string {
  const m = /^(\d{4})-(\d{2})$/.exec(month);
  if (!m) {
    return month;
  }
  const year = Number(m[1]);
  const monthIndex = Number(m[2]) - 1 + delta;
  const d = new Date(year, monthIndex, 1);
  return currentMonth(d);
}

// ──────────────────────────────────────────────────────────────────────────────
// 「为什么没有这个数」—— 说人话
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 把原因码翻译成工人看得懂的一句话。
 *
 * ⚠️ 这一屏面向低技术素养用户, 所以措辞规则是:
 * ① 不出现表名/字段名/英文码; ② 说清「不是你的问题」还是「要找谁」;
 * ③ ⛔ 不说「暂无数据」——「暂无」什么都没解释, 工人只会更慌。
 */
export function explainUnavailable(reason: UnavailableReason | null | undefined): string {
  switch (reason) {
    case 'NO_PAYROLL_FOR_PERIOD':
      return '这个月的工资单还没做出来。工资单做出来之前, 这里不会有金额。';
    case 'NOT_CALCULATED_YET':
      return '系统还没算这个月。系统每月 1 号自动算上个月, 本月一般要下个月初才有。';
    case 'NO_PIECE_RATE_RULE':
      return '这道工序还没定过单价, 所以算不出钱。可以找班组长或厂里问一下。';
    case 'NO_HOURLY_RATE_RULE':
      return '还没给你定时薪, 所以按小时那部分算不出来。可以找班组长或厂里问一下。';
    case 'NOT_APPLICABLE_FOR_MODE':
      return '你是按件算工资的, 没有时薪这一项。';
    default:
      return '';
  }
}

// ──────────────────────────────────────────────────────────────────────────────
// 工资单状态
// ──────────────────────────────────────────────────────────────────────────────

export type PayrollTone = 'paid' | 'approved' | 'pending' | 'unknown';

/**
 * ⛔ 三态必须能一眼分清 —— 「已发放」和「待审核」混在一起看是这个功能失败的样子。
 * 后端已经给了 `statusLabel`, 这里只决定颜色分组, ⛔ 不重写文案 (那会变成两份措辞)。
 */
export function payrollTone(status: PayrollStatus | string | null | undefined): PayrollTone {
  switch (status) {
    case 'PAID':
      return 'paid';
    case 'APPROVED':
      return 'approved';
    case 'PENDING':
      return 'pending';
    default:
      return 'unknown';
  }
}

/** 工资单上「这个月怎么算出来的」的分项 —— 只列后端真给了值的项, ⛔ 不补 0 行。 */
export interface PayrollBreakdownRow {
  label: string;
  value: number;
  /** 扣款是减项, 屏上要带负号并变色。 */
  negative: boolean;
}

export function payrollBreakdown(payroll: MyWagePayroll): PayrollBreakdownRow[] {
  const rows: PayrollBreakdownRow[] = [];
  const push = (label: string, value: number | null, negative = false) => {
    // ⛔ 只有真的拿到数才成行。null 不补 0 —— 补了就等于宣称「这一项是 0 元」。
    if (value !== null && value !== undefined && !Number.isNaN(value)) {
      rows.push({ label, value, negative });
    }
  };
  push('底薪', payroll.baseSalary);
  push('计件', payroll.pieceRateWage);
  push('加班', payroll.overtimeWage);
  push('奖金', payroll.bonusAmount);
  push('扣款', payroll.deductionAmount, true);
  return rows;
}

/**
 * 分项加起来是多少。没有任何分项时返回 `null` (⛔ 不返回 0 —— 「没有分项」不是「加起来是 0」)。
 *
 * <p>⚠️ 这是 {@link breakdownGap} 和屏上那句「上面几项加起来是 X」的<b>同一个来源</b>。
 * 屏里另算一遍就会漂: 提示语里的 X 和差额对不上, 比不显示还糟。
 */
export function payrollBreakdownSum(payroll: MyWagePayroll): number | null {
  const rows = payrollBreakdown(payroll);
  if (rows.length === 0) {
    return null;
  }
  const sum = rows.reduce((acc, r) => acc + (r.negative ? -r.value : r.value), 0);
  return Number(sum.toFixed(2));
}

/**
 * 分项加起来对不对得上合计。
 *
 * <p>🔴 这条是给工人的<b>自查</b>用的, 也是这个功能的核心价值: 对不上就说出来,
 * ⛔ 不要悄悄把合计改成分项之和 (那会让屏上的数和财务发的数不一样, 更糟)。
 *
 * @returns `null` = 没法比 (合计或分项缺); 否则 = 分项之和减去合计的差
 */
export function breakdownGap(payroll: MyWagePayroll): number | null {
  if (payroll.totalWage === null || payroll.totalWage === undefined) {
    return null;
  }
  const sum = payrollBreakdownSum(payroll);
  if (sum === null) {
    return null;
  }
  return Number((sum - payroll.totalWage).toFixed(2));
}
