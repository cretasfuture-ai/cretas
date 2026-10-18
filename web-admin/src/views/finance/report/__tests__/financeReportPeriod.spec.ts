import { describe, expect, it } from 'vitest';
import { readIncomeMonthRange, readMonthRange, samePeriodIncomeUrl } from '@/utils/financeReportPeriod';
describe('同期间利润表入口', () => {
  it('整月与跨年整月保留期间，不改成当前年度累计', () => {
    expect(readIncomeMonthRange({ periodStart:'2026-10-01',periodEnd:'2026-10-31' })).toEqual(['2026-10','2026-10']);
    expect(readIncomeMonthRange({ periodStart:'2025-12-01',periodEnd:'2026-02-28' })).toEqual(['2025-12','2026-02']);
    expect(readIncomeMonthRange({ periodStart:'2028-02-01',periodEnd:'2028-02-29' })).toEqual(['2028-02','2028-02']);
    const url = samePeriodIncomeUrl('F&A',['2026-10-01','2026-10-31']);
    expect(Object.fromEntries(new URL(url!,'http://localhost').searchParams)).toEqual({tab:'income-statement',factoryId:'F&A',periodStart:'2026-10-01',periodEnd:'2026-10-31'});
  });
  it.each([['2026-10-16','2026-10-31'],['2026-10-01','2026-10-16'],['2026-02-01','2026-02-30']])('部分月或非法日期没有同期间入口 %j', (start,end) => {
    expect(samePeriodIncomeUrl('F',[start,end])).toBeNull();
  });
  it.each([null,[],['2026-10'],['2026-13','2026-13'],['2026-10','2026-09'],['2026-1','2026-10']])('不接受无效月份 %j', value => { expect(readMonthRange(value)).toBeNull(); });
});
