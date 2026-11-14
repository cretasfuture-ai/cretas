import { describe, expect, it } from 'vitest';
import {
  costCoverageText,
  fmtMoney,
  fmtPercent,
  fmtQty,
  productChartLabels,
  quantityParts,
  summaryYield,
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

describe('成本分析: 汇总出成率按批次加权', () => {
  it('后端给了加权值就用后端的；明确为 null 就是没有', () => {
    expect(summaryYield({ avgYieldRate: 82 })).toBe(82);
    expect(summaryYield({ avgYieldRate: null }, [{ avgYieldRate: 90, batchCount: 1 }])).toBeNull();
    expect(summaryYield({ avgYieldRate: Number.NaN }, [])).toBeNull();
  });

  it('后端旧版没有该字段时用日行按批次加权现算，不是按天简单平均', () => {
    const rows = [
      { avgYieldRate: 100, batchCount: 1 },
      { avgYieldRate: 80, batchCount: 9 },
      { avgYieldRate: null, batchCount: 5 },
    ];
    expect(summaryYield({}, rows)).toBe(82); // 简单平均会是 90
    expect(summaryYield(undefined, [])).toBeNull();
    expect(summaryYield({}, [{ avgYieldRate: null, batchCount: 3 }])).toBeNull();
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
