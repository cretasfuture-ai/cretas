import { describe, expect, it } from 'vitest';
import {
  costCoverageText,
  fmtMoney,
  fmtPercent,
  fmtQty,
  productChartLabels,
  quantityParts,
  fillCalendarDays,
  summaryYield,
  summaryYieldMissing,
  unresolvedProductLabel,
} from '../costAnalysisFormat';

describe('成本分析: 数字格式', () => {
  it('金额千分位，null/NaN 显示「—」而不是 0', () => {
    expect(fmtMoney(212037.09)).toBe('212,037');
    expect(fmtMoney(1234.5, 2)).toBe('1,234.50');
    expect(fmtMoney(null)).toBe('—');
    expect(fmtMoney(Number.NaN)).toBe('—');
    expect(fmtMoney(0)).toBe('0');
  });

  it('产量千分位，百分比保留一位', () => {
    expect(fmtQty(6002.5)).toBe('6,002.5');
    expect(fmtQty(1343.36, 2)).toBe('1,343.36');
    expect(fmtQty(undefined)).toBe('—');
    expect(fmtPercent(85.5)).toBe('85.5%');
    expect(fmtPercent(null)).toBe('—');
  });
});

describe('成本分析: 多单位产出不相加', () => {
  it('按产出量降序列出，每个单位各一项', () => {
    expect(quantityParts({ box: 20, kg: 1343.36, 个: 6002.95 })).toEqual([
      { unit: '个', quantity: 6002.95 },
      { unit: 'kg', quantity: 1343.36 },
      { unit: 'box', quantity: 20 },
    ]);
  });

  it('没有数据返回空数组', () => {
    expect(quantityParts(undefined)).toEqual([]);
    expect(quantityParts({})).toEqual([]);
  });
});

describe('成本分析: 汇总出成率只信后端加权值', () => {
  it('后端给了值就用，null/NaN/缺失都是 null，前端不自己算', () => {
    expect(summaryYield({ avgYieldRate: 82 })).toBe(82);
    expect(summaryYield({ avgYieldRate: null })).toBeNull();
    expect(summaryYield({ avgYieldRate: Number.NaN })).toBeNull();
    expect(summaryYield({})).toBeNull();
    expect(summaryYield(undefined)).toBeNull();
  });

  it('区分「字段缺失（后端未更新）」和「后端明确说没有数据」', () => {
    expect(summaryYieldMissing({})).toBe(true);
    expect(summaryYieldMissing({ avgYieldRate: null })).toBe(false);
    expect(summaryYieldMissing({ avgYieldRate: 80 })).toBe(false);
  });
});

describe('成本分析: 无数据日断开不连线', () => {
  it('补成连续日历日，缺的日子 point 为 null', () => {
    const filled = fillCalendarDays([
      { statDate: '2026-10-29', v: 1 },
      { statDate: '2026-10-31', v: 2 },
      { statDate: '2026-11-02', v: 3 },
    ]);
    expect(filled.map((d) => d.date)).toEqual(['2026-10-29', '2026-10-30', '2026-10-31', '2026-11-01', '2026-11-02']);
    expect(filled.map((d) => d.point?.v ?? null)).toEqual([1, null, 2, null, 3]);
  });

  it('日期键取本地日历日，东八区凌晨不退一天（含夏令时切换日不重复/不丢）', () => {
    const originalTz = process.env.TZ;
    for (const tz of ['Asia/Shanghai', 'America/Toronto', 'UTC']) {
      process.env.TZ = tz;
      const days = fillCalendarDays([{ statDate: '2026-03-07' }, { statDate: '2026-03-10' }]).map((d) => d.date);
      expect(days).toEqual(['2026-03-07', '2026-03-08', '2026-03-09', '2026-03-10']);
    }
    process.env.TZ = originalTz;
  });

  it('空数组返回空数组；跨月不丢日', () => {
    expect(fillCalendarDays([])).toEqual([]);
    expect(fillCalendarDays([{ statDate: '2026-02-27' }, { statDate: '2026-03-02' }]).length).toBe(4);
  });
});

describe('成本分析: 产品标签', () => {
  const name = (id: string) => ({ a: '卤牛肉', b: '酱鸭' }[id] ?? unresolvedProductLabel(id));

  it('同一产品出现在多个单位时标签带单位，避免两根同名柱子', () => {
    expect(
      productChartLabels(
        [
          { productTypeId: 'a', unit: 'kg' },
          { productTypeId: 'a', unit: '袋' },
          { productTypeId: 'b', unit: 'kg' },
        ],
        name,
      ),
    ).toEqual(['卤牛肉（kg）', '卤牛肉（袋）', '酱鸭']);
  });

  it('未解析的产品给可读兜底，不显示整串 UUID', () => {
    expect(unresolvedProductLabel('3f1da5a8-bad2-48c4-b7d5-e4ee741cc67a')).toBe('未命名产品 · 3f1da5a8');
  });
});

describe('成本分析: 单位成本覆盖提示', () => {
  it('部分批次没有成本时必须提示', () => {
    expect(costCoverageText(3, 5)).toBe('3/5 批有成本');
  });
  it('全部有成本或后端没给就不提示', () => {
    expect(costCoverageText(5, 5)).toBeNull();
    expect(costCoverageText(undefined, 5)).toBeNull();
  });
});
