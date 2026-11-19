import { describe, expect, it } from 'vitest';
import { formatLocalDateTimeMinute } from '@/utils/dateFormat';
import { formatStageLossRate } from '../traceDisplay';

describe('formatStageLossRate — 损耗率只由投入/产出现算', () => {
  it('正常损耗: 217.5kg → 206.625kg = 5.0%, 不是 10000.0%', () => {
    expect(formatStageLossRate(217.5, 206.625).text).toBe('5.0%');
  });
  it('增重: 206.625 → 221.85 显示「增重 7.4%」, 不出现负号', () => {
    const r = formatStageLossRate(206.625, 221.85);
    expect(r.text).toBe('增重 7.4%');
    expect(r.text).not.toContain('-');
  });
  it('投入为 0 / 缺失 / 单位不一致 → 「—」并给原因', () => {
    expect(formatStageLossRate(0, 10)).toEqual({ text: '—', reason: '投入为 0, 无法计算' });
    expect(formatStageLossRate(null, 10).text).toBe('—');
    expect(formatStageLossRate(10, undefined).reason).toContain('未记录');
    expect(formatStageLossRate(10, 9, false).reason).toContain('单位不一致');
  });
  it('产出等于投入 = 0.0%', () => {
    expect(formatStageLossRate(100, 100).text).toBe('0.0%');
  });
});

describe('formatLocalDateTimeMinute — 不做时区偏移', () => {
  it.each([
    ['2026-11-17T15:00:00', '2026-11-17 15:00'],
    ['2026-11-18T15:23:02.873513', '2026-11-18 15:23'],
    ['2026-11-18T06:30:00', '2026-11-18 06:30'],
    ['2026-11-17', '2026-11-17'],
    ['2026-11-17 08:05:00', '2026-11-17 08:05'],
  ])('%s → %s', (input, expected) => {
    expect(formatLocalDateTimeMinute(input)).toBe(expected);
  });
  it('空值返回空串, 乱串原样', () => {
    expect(formatLocalDateTimeMinute(null)).toBe('');
    expect(formatLocalDateTimeMinute('   ')).toBe('');
    expect(formatLocalDateTimeMinute('abc')).toBe('abc');
  });
});
