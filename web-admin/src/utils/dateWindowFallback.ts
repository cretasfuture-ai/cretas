/**
 * 默认日期窗口自动回退（LSMDEMO 演示：默认「今日 / 最近 7 天 / 本月」落在没数据的日期上，整页空白）。
 *
 * 做法：先查原窗口；有数据就用原窗口（不回退）。没数据就用同长度的更早窗口依次向前探测，
 * 取最近一个有数据的窗口，并给出可直接展示的说明。探测用页面已有的列表/汇总接口，不依赖新后端。
 *
 * 三种结果，⛔ 不许塌成一个：
 * - 有数据不回退：`fellBack=false, hasAnyData=true`
 * - 无数据回退：`fellBack=true, hasAnyData=true`，`notice` 明示实际显示的区间
 * - 全无数据：`fellBack=false, hasAnyData=false`，`notice` 如实说没有；窗口保持原窗口
 * 接口报错一律抛出（禁止降级：不能把「没查到」说成「没数据」）。
 */

export interface DateWindow {
  /** YYYY-MM-DD，含 */
  start: string;
  /** YYYY-MM-DD，含 */
  end: string;
}

export interface WindowFallbackOutcome<T> {
  /** 最终展示用的数据（全无数据时是原窗口那次的空结果）。 */
  data: T;
  /** 最终展示的窗口。 */
  window: DateWindow;
  /** 用户原本选的 / 默认的窗口。 */
  originalWindow: DateWindow;
  fellBack: boolean;
  hasAnyData: boolean;
  /** 页面上展示的说明；原窗口有数据时为空串。 */
  notice: string;
}

const DAY_MS = 86400000;

function parseDay(value: string): Date {
  const [y, m, d] = value.split('-').map(Number);
  return new Date(y, (m || 1) - 1, d || 1);
}

/** 本地日历日 YYYY-MM-DD（不走 toISOString，避免东八区凌晨给出昨天）。 */
export function formatDay(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function windowLengthDays(w: DateWindow): number {
  return Math.round((parseDay(w.end).getTime() - parseDay(w.start).getTime()) / DAY_MS) + 1;
}

/** 与 `original` 等长、首尾相接的更早窗口，由近到远，共 `count` 个。 */
export function previousWindows(original: DateWindow, count: number): DateWindow[] {
  const len = Math.max(1, windowLengthDays(original));
  const out: DateWindow[] = [];
  let end = parseDay(original.start);
  end.setDate(end.getDate() - 1);
  for (let i = 0; i < count; i += 1) {
    const start = new Date(end);
    start.setDate(start.getDate() - (len - 1));
    out.push({ start: formatDay(start), end: formatDay(end) });
    end = new Date(start);
    end.setDate(end.getDate() - 1);
  }
  return out;
}

function shortDay(value: string): string {
  return value.slice(5);
}

export function describeWindow(w: DateWindow): string {
  return w.start === w.end ? shortDay(w.start) : `${shortDay(w.start)} 至 ${shortDay(w.end)}`;
}

export function fallbackNotice(windowLabel: string, shown: DateWindow): string {
  return `${windowLabel}没有数据，已显示最近有数据的 ${describeWindow(shown)}`;
}

export function noDataNotice(windowLabel: string, lookbackDays: number): string {
  return `${windowLabel}没有数据，向前 ${lookbackDays} 天内也没有找到有数据的区间`;
}

export interface WindowFallbackOptions<T> {
  original: DateWindow;
  /** 窗口名，用于说明文字，如「最近 7 天」「本月」「今日」。 */
  windowLabel: string;
  fetchWindow: (w: DateWindow) => Promise<T>;
  hasData: (data: T) => boolean;
  /** 最多向前探测多少天（默认 90）。 */
  lookbackDays?: number;
  /** 并行探测的批大小（默认 6）。 */
  batchSize?: number;
}

export async function resolveWindowWithFallback<T>(opts: WindowFallbackOptions<T>): Promise<WindowFallbackOutcome<T>> {
  const lookbackDays = opts.lookbackDays ?? 90;
  const batchSize = Math.max(1, opts.batchSize ?? 6);
  const first = await opts.fetchWindow(opts.original);
  if (opts.hasData(first)) {
    return { data: first, window: opts.original, originalWindow: opts.original, fellBack: false, hasAnyData: true, notice: '' };
  }

  const steps = Math.ceil(lookbackDays / Math.max(1, windowLengthDays(opts.original)));
  const candidates = previousWindows(opts.original, steps);
  for (let i = 0; i < candidates.length; i += batchSize) {
    const batch = candidates.slice(i, i + batchSize);
    // 任一探测请求失败即整体抛出：不能把「探测失败」当成「那段没数据」。
    const results = await Promise.all(batch.map((w) => opts.fetchWindow(w)));
    for (let j = 0; j < batch.length; j += 1) {
      if (opts.hasData(results[j])) {
        return {
          data: results[j],
          window: batch[j],
          originalWindow: opts.original,
          fellBack: true,
          hasAnyData: true,
          notice: fallbackNotice(opts.windowLabel, batch[j]),
        };
      }
    }
  }
  return {
    data: first,
    window: opts.original,
    originalWindow: opts.original,
    fellBack: false,
    hasAnyData: false,
    notice: noDataNotice(opts.windowLabel, lookbackDays),
  };
}
