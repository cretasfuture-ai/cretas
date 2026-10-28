import { describe, expect, it } from 'vitest';
import { costSummaryTotal } from '../costSummaryTotal';
import { secretAwareCell } from '@/utils/processSecretField';

const money = (v?: number | null) => (v == null ? '—' : '¥' + Number(v).toFixed(2));
const secretRow = {
  totalCost: null,
  knownCostSubtotal: null,
  seasoningCost: null,
  processSecret: { visibility: 'TOTALS_ONLY', hiddenFields: ['totalCost', 'knownCostSubtotal', 'seasoningCost'] },
};

describe('成本汇总: 工艺保密隐藏的成本', () => {
  it('合计不把已保密的行当 0 悄悄加进去, 而是标出不含几单', () => {
    expect(costSummaryTotal([{ totalCost: 100 }, secretRow, { totalCost: 50 }], money))
      .toBe('¥150.00（不含 1 单已保密）');
  });

  it('待补和已保密同时出现时两个都标', () => {
    expect(costSummaryTotal([{ totalCost: null, knownCostSubtotal: 30 }, secretRow], money))
      .toBe('¥30.00（含待补，不含 1 单已保密）');
  });

  it('阴性对照: 没有保密行时合计照旧', () => {
    expect(costSummaryTotal([{ totalCost: 100 }, { totalCost: 50 }], money)).toBe('¥150.00');
  });

  it('单元格: 被隐藏的显示已保密, 没价格的仍是 —', () => {
    expect(secretAwareCell(secretRow, 'seasoningCost', (v) => money(v as number | null))).toBe('已保密');
    expect(secretAwareCell({ seasoningCost: null }, 'seasoningCost', (v) => money(v as number | null))).toBe('—');
    expect(secretAwareCell({ seasoningCost: 12.5 }, 'seasoningCost', (v) => money(v as number | null))).toBe('¥12.50');
  });
});
