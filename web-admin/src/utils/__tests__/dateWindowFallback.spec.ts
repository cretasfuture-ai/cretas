import { describe, expect, it, vi } from 'vitest';
import {
  previousWindows,
  resolveWindowWithFallback,
  windowLengthDays,
  type DateWindow,
} from '../dateWindowFallback';

const ORIGINAL: DateWindow = { start: '2026-11-12', end: '2026-11-18' }; // 7 天

/** 只有这些「窗口起点」有数据；其余窗口返回空数组。 */
function fetcherWithDataAt(starts: string[]) {
  return vi.fn(async (w: DateWindow) => (starts.includes(w.start) ? [{ qty: 5 }] : []));
}
const hasData = (rows: unknown[]) => rows.length > 0;

describe('previousWindows', () => {
  it('生成与原窗口等长、首尾相接、由近到远的更早窗口', () => {
    const w = previousWindows(ORIGINAL, 2);
    expect(w).toEqual([
      { start: '2026-11-05', end: '2026-11-11' },
      { start: '2026-10-29', end: '2026-11-04' },
    ]);
    expect(w.every((x) => windowLengthDays(x) === 7)).toBe(true);
  });
});

describe('resolveWindowWithFallback', () => {
  it('原窗口有数据：不回退，也不发探测请求', async () => {
    const fetchWindow = fetcherWithDataAt([ORIGINAL.start]);
    const out = await resolveWindowWithFallback({ original: ORIGINAL, windowLabel: '最近 7 天', fetchWindow, hasData });
    expect(out.fellBack).toBe(false);
    expect(out.hasAnyData).toBe(true);
    expect(out.window).toEqual(ORIGINAL);
    expect(out.notice).toBe('');
    expect(fetchWindow).toHaveBeenCalledTimes(1);
  });

  it('原窗口无数据：回退到最近有数据的窗口并明示区间', async () => {
    // 11-05~11-11 空，10-29~11-04 有数据（更早的也有，但要取最近的）
    const fetchWindow = fetcherWithDataAt(['2026-10-29', '2026-10-22']);
    const out = await resolveWindowWithFallback({ original: ORIGINAL, windowLabel: '最近 7 天', fetchWindow, hasData });
    expect(out.fellBack).toBe(true);
    expect(out.hasAnyData).toBe(true);
    expect(out.window).toEqual({ start: '2026-10-29', end: '2026-11-04' });
    expect(out.originalWindow).toEqual(ORIGINAL);
    expect(out.notice).toBe('最近 7 天没有数据，已显示最近有数据的 10-29 至 11-04');
    expect(out.data).toEqual([{ qty: 5 }]);
  });

  it('单日窗口回退：说明里只写一个日期', async () => {
    const today: DateWindow = { start: '2026-11-18', end: '2026-11-18' };
    const fetchWindow = fetcherWithDataAt(['2026-11-07']);
    const out = await resolveWindowWithFallback({ original: today, windowLabel: '今日', fetchWindow, hasData });
    expect(out.window).toEqual({ start: '2026-11-07', end: '2026-11-07' });
    expect(out.notice).toBe('今日没有数据，已显示最近有数据的 11-07');
  });

  it('全无数据：如实说没有，窗口保持原窗口，不回退', async () => {
    const fetchWindow = fetcherWithDataAt([]);
    const out = await resolveWindowWithFallback({ original: ORIGINAL, windowLabel: '最近 7 天', fetchWindow, hasData, lookbackDays: 28 });
    expect(out.fellBack).toBe(false);
    expect(out.hasAnyData).toBe(false);
    expect(out.window).toEqual(ORIGINAL);
    expect(out.notice).toBe('最近 7 天没有数据，向前 28 天内也没有找到有数据的区间');
    // 1 次原窗口 + 4 个 7 天探测窗口：确认真的探测过，而不是直接下结论
    expect(fetchWindow).toHaveBeenCalledTimes(5);
  });

  it('原窗口接口报错：抛出，不当成没数据', async () => {
    const fetchWindow = vi.fn(async () => { throw new Error('500 服务异常'); });
    await expect(resolveWindowWithFallback({ original: ORIGINAL, windowLabel: '最近 7 天', fetchWindow, hasData }))
      .rejects.toThrow('500 服务异常');
  });

  it('探测窗口接口报错：抛出，不把探测失败说成那段没数据', async () => {
    const fetchWindow = vi.fn(async (w: DateWindow) => {
      if (w.start === ORIGINAL.start) return [];
      throw new Error('探测超时');
    });
    await expect(resolveWindowWithFallback({ original: ORIGINAL, windowLabel: '最近 7 天', fetchWindow, hasData }))
      .rejects.toThrow('探测超时');
  });
});
