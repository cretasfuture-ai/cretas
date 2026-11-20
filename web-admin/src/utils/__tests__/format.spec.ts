import { describe, expect, it } from 'vitest';
import { fmtMoney, fmtNumber, fmtPct, fmtQty, safeRatio } from '@/utils/format';

/**
 * 期望值 = 2026-11-13 把统一原型 design-system.js 的 fmt* 原样跑一遍的输出（P1 移植对照）。
 * 唯一有意的差异：-0.001 两位小数原型显示「-0.00」，这里显示「0.00」（负零没有业务含义）。
 */
const V = (fmtN: string, money: string, qtyKg: string, qtyNoUnit: string, pct: string) => ({ fmtN, money, qtyKg, qtyNoUnit, pct });
const cases: Array<[unknown, ReturnType<typeof V>]> = [
  [null, V('—', '—', '—', '—', '—')],
  [undefined, V('—', '—', '—', '—', '—')],
  ['', V('—', '—', '—', '—', '—')],
  ['  ', V('—', '—', '—', '—', '—')],
  [0, V('0.00', '0.00 元', '0.00 kg', '0.00（单位未填）', '0.00%')],
  ['0', V('0.00', '0.00 元', '0.00 kg', '0.00（单位未填）', '0.00%')],
  [1234.5, V('1,234.50', '1,234.50 元', '1,234.50 kg', '1,234.50（单位未填）', '1,234.50%')],
  [-1234.567, V('-1,234.57', '-1,234.57 元', '-1,234.57 kg', '-1,234.57（单位未填）', '-1,234.57%')],
  ['1,234.5', V('1,234.50', '1,234.50 元', '1,234.50 kg', '1,234.50（单位未填）', '1,234.50%')],
  ['abc', V('格式无效', '格式无效', '格式无效', '格式无效', '格式无效')],
  [Number.NaN, V('格式无效', '格式无效', '格式无效', '格式无效', '格式无效')],
  [Number.POSITIVE_INFINITY, V('格式无效', '格式无效', '格式无效', '格式无效', '格式无效')],
  [true, V('格式无效', '格式无效', '格式无效', '格式无效', '格式无效')],
  [0.005, V('0.01', '0.01 元', '0.01 kg', '0.01（单位未填）', '0.01%')],
  [1234567.891, V('1,234,567.89', '1,234,567.89 元', '1,234,567.89 kg', '1,234,567.89（单位未填）', '1,234,567.89%')],
  ['-0.001', V('0.00', '0.00 元', '0.00 kg', '0.00（单位未填）', '0.00%')],
  [12, V('12.00', '12.00 元', '12.00 kg', '12.00（单位未填）', '12.00%')],
];

describe('fmtNumber / fmtMoney / fmtQty / fmtPct（原型 T5 口径）', () => {
  it.each(cases)('%j', (input, want) => {
    expect(fmtNumber(input)).toBe(want.fmtN);
    expect(fmtMoney(input)).toBe(want.money);
    expect(fmtQty(input, 'kg')).toBe(want.qtyKg);
    expect(fmtQty(input, '')).toBe(want.qtyNoUnit);
    expect(fmtPct(input)).toBe(want.pct);
  });

  it('缺失不是 0：null 与 0 显示不同', () => {
    expect(fmtMoney(null)).not.toBe(fmtMoney(0));
  });

  it('选项：币种、位数、缺失占位', () => {
    expect(fmtMoney(1, { currency: '（币种待确认）' })).toBe('1.00 （币种待确认）');
    expect(fmtMoney(1, { currency: '' })).toBe('1.00');
    expect(fmtQty(3, '件', { digits: 0 })).toBe('3 件');
    expect(fmtQty(null, '件', { missing: '未填' })).toBe('未填');
    expect(fmtNumber(1234, { digits: 0 })).toBe('1,234');
  });
});

describe('safeRatio', () => {
  it('零分母不做除法、缺失与格式无效分开说', () => {
    expect(safeRatio(1, 0)).toEqual({ value: null, reason: '分母为 0' });
    expect(safeRatio(null, 2)).toEqual({ value: null, reason: '缺少数据' });
    expect(safeRatio('x', 2)).toEqual({ value: null, reason: '格式无效' });
    expect(safeRatio(3, 4)).toEqual({ value: 0.75, reason: '' });
  });
});
