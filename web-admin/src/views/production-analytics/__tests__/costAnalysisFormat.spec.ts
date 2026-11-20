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
  productsByUnitCost,
  unitCostLabel,
  yieldBasisText,
  COST_CATEGORY_COLORS,
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

describe('成本分析: 产品对比按单位成本 (LSM-COSTBI-B 第 3 项)', () => {
  const p = (id: string, unit: string, costPerUnit: number | null, totalCost: number) =>
    ({ productTypeId: id, unit, costPerUnit, totalCost });
  it('比单位成本而不是总成本：产量大的便宜产品不排第一', () => {
    const sorted = productsByUnitCost([p('大产量便宜', '盒', 10, 30000), p('小产量贵', '盒', 25, 900)]);
    expect(sorted.map((x) => x.productTypeId)).toEqual(['小产量贵', '大产量便宜']);
  });
  it('不同单位分组，不把盒和袋的单价混排；算不出的放最后', () => {
    const sorted = productsByUnitCost([
      p('袋A', '袋', 50, 1), p('盒A', '盒', 10, 1), p('盒B', '盒', 20, 1), p('盒无成本', '盒', null, 0),
    ]);
    expect(sorted.map((x) => x.productTypeId)).toEqual(['盒B', '盒A', '袋A', '盒无成本']);
  });
  it('单位成本标签带单位，缺失写「未填」不是 ¥0', () => {
    expect(unitCostLabel(16.236, '盒')).toBe('¥16.24/盒');
    expect(unitCostLabel(null, '盒')).toBe('未填');
  });
});

describe('成本分析: 平均出成率分母 (LSM-COSTBI-B 第 4 项)', () => {
  it('部分批次没有出成率时说清楚 9/10，不说「按 10 批」', () => {
    expect(yieldBasisText(9, 10)).toBe('9/10 批有出成率，按这 9 批加权');
    expect(yieldBasisText(10, 10)).toBe('按 10 批加权');
    expect(yieldBasisText(0, 10)).toBe('暂无带出成率的批次');
  });
});

describe('成本分析: 两张图同一套成本颜色 (LSM-COSTBI-B 第 6 项)', () => {
  it('四类成本各有且只有一个颜色，互不相同', () => {
    const colors = Object.values(COST_CATEGORY_COLORS);
    expect(Object.keys(COST_CATEGORY_COLORS)).toEqual(['材料', '人工', '设备', '其他']);
    expect(new Set(colors).size).toBe(4);
  });
});
