import { describe, it, expect } from 'vitest';
import {
  buildSkuMarginInsight,
  skuTopRows,
  buildPriceTrendInsight,
  buildLaborProductInsight,
  buildLaborBatchInsight,
  buildLaborStepInsight,
  buildGanttInsight,
  ganttBarKind,
  type GanttItem,
} from '../chartPageInsights';

const text = (i: ReturnType<typeof buildSkuMarginInsight>) =>
  i ? [i.finding, i.implication, i.suggestion].filter(Boolean).join('\n') : '';

describe('SKU 毛利率', () => {
  const rows = [
    { name: 'A', rate: 42.34 },
    { name: 'B', rate: 12.0 },
    { name: 'C', rate: null },
    { name: 'D', rate: 31 },
    { name: 'E', rate: 8.5 },
  ];
  it('图上取毛利率前 N，null 不参与', () => {
    expect(skuTopRows(rows).map((r) => r.name)).toEqual(['A', 'D', 'B', 'E']);
  });
  it('首尾、差值、分档计数、缺毛利率都按数据现算', () => {
    const t = text(buildSkuMarginInsight(rows));
    expect(t).toContain('从 42.3%（A）到 8.5%（E），相差 33.8 个百分点');
    expect(t).toContain('2 个 ≥30%'); // A、D
    expect(t).toContain('2 个 <15%'); // B、E
    expect(t).toContain('另有 1 个算不出毛利率');
    expect(t).toContain('E 8.5%'); // 低毛利优先看最低的
    expect(t).not.toContain('¥');
  });
  it('换一组数结论跟着变（不是写死一句话）', () => {
    const a = text(buildSkuMarginInsight([{ name: 'X', rate: 50 }, { name: 'Y', rate: 40 }]));
    expect(a).toContain('从 50%（X）到 40%（Y）');
    expect(a).not.toContain('另有');
    expect(buildSkuMarginInsight([{ name: 'X', rate: 50 }, { name: 'Y', rate: 40 }])?.suggestion).toBeUndefined();
  });
  it('全部算不出 → 不给分析', () => {
    expect(buildSkuMarginInsight([{ name: 'C', rate: null }])).toBeNull();
    expect(buildSkuMarginInsight([])).toBeNull();
  });
});

describe('物料价格趋势', () => {
  const pts = [
    { date: '2026-10-01', price: 10 },
    { date: '2026-10-08', price: 12 },
    { date: '2026-10-15', price: 9 },
  ];
  it('首末变化、最高最低、单位', () => {
    const i = buildPriceTrendInsight('面粉', pts, { unit: 'kg' });
    expect(i?.finding).toContain('从 2026-10-01 的 ¥10.00/kg 下降到 2026-10-15 的 ¥9.00/kg（10%）');
    expect(i?.implication).toContain('最高 ¥12.00（2026-10-08），最低 ¥9.00（2026-10-15）');
    expect(i?.suggestion).toBeUndefined();
  });
  it('最近一批就是最高价时提醒核对；不是最高价就不提醒', () => {
    const up = buildPriceTrendInsight('面粉', [pts[0], { date: '2026-10-20', price: 15 }], { });
    expect(up?.suggestion).toContain('最近一批 2026-10-20 就是图中最高价');
    expect(buildPriceTrendInsight('面粉', pts, {})?.suggestion).toBeUndefined();
  });
  it('一个点不画趋势；没有点不出分析', () => {
    expect(buildPriceTrendInsight('面粉', [pts[0]], {})?.finding).toContain('画不出趋势');
    expect(buildPriceTrendInsight('面粉', [], {})).toBeNull();
  });
  it('价格持平如实说持平', () => {
    const i = buildPriceTrendInsight('盐', [pts[0], { date: '2026-10-09', price: 10 }], {});
    expect(i?.finding).toContain('持平');
  });
});

describe('人效看板', () => {
  it('产品图：预警计数、未配置计划工时', () => {
    const i = buildLaborProductInsight([
      { name: '甲', rate: 160, alert: 'ABOVE_ALERT' },
      { name: '乙', rate: 60, alert: 'BELOW_ALERT' },
      { name: '丙', rate: 100, alert: 'OK' },
      { name: '丁', rate: null, alert: null },
    ]);
    expect(i?.finding).toContain('最高 160%（甲），最低 60%（乙）');
    expect(i?.implication).toContain('1 个超过 150%（超时预警：甲）');
    expect(i?.implication).toContain('1 个低于 75%（低效预警：乙）');
    expect(i?.implication).toContain('1 个未配置计划工时');
  });
  it('产品图：全部未配置 → 说明缺什么，不编数', () => {
    const i = buildLaborProductInsight([{ name: '丁', rate: null, alert: null }]);
    expect(i?.finding).toContain('都没有配置计划工时');
    expect(i?.finding).not.toMatch(/\d+(\.\d+)?%/);
  });
  it('批次图：不断言「最新」，截断时说明', () => {
    const i = buildLaborBatchInsight(
      [{ name: 'B1', rate: 200, alert: 'ABOVE_ALERT' }, { name: 'B2', rate: 100, alert: 'OK' }],
      { shownCap: 30, totalBatches: 45, productName: '全部产品' },
    );
    expect(i?.finding).toContain('最高 200%（B1），最低 100%（B2）');
    expect(i?.implication).toContain('共 45 批，图只画前 30 批');
    expect(i?.suggestion).toContain('B1');
    expect(text(i)).not.toMatch(/最新|上升|下降/);
  });
  it('工序图：超计划最多的工序', () => {
    const i = buildLaborStepInsight([
      { name: '搅拌', actual: 30, planned: 20 },
      { name: '成型', actual: 10, planned: 10 },
      { name: '包装', actual: 5, planned: null },
    ]);
    expect(i?.finding).toContain('搅拌 实际 30 分钟，计划 20 分钟，超出 50%');
    expect(i?.implication).toContain('2 道可对比工序里 1 道超计划');
    expect(i?.implication).toContain('1 道缺实际或计划工时');
  });
  it('工序图：计划工时为 0 的工序不参与比较（不会算出无穷大）', () => {
    const i = buildLaborStepInsight([
      { name: '零计划', actual: 10, planned: 0 },
      { name: '搅拌', actual: 30, planned: 20 },
    ]);
    expect(i?.finding).toContain('搅拌');
    expect(i?.implication).toContain('1 道可对比工序');
    expect(i?.implication).toContain('1 道缺实际或计划工时');
    expect(text(i)).not.toContain('Infinity');
  });
  it('工序图：没超计划时不说超', () => {
    const i = buildLaborStepInsight([{ name: '搅拌', actual: 10, planned: 20 }]);
    expect(i?.finding).toContain('都没超计划');
  });
  it('没有数据不出分析', () => {
    expect(buildLaborProductInsight([])).toBeNull();
    expect(buildLaborStepInsight([])).toBeNull();
  });
});

describe('排产甘特', () => {
  const day = 86400000;
  const base = Date.UTC(2026, 10, 18);
  const mk = (over: Partial<GanttItem>): GanttItem => ({
    label: 'B', line: 'L1', status: 'pending', prob: 0.9, startMs: base, endMs: base + day, ...over,
  });
  it('颜色规则：概率缺失不标红，低概率/延期标红，完成/进行中优先', () => {
    expect(ganttBarKind({ status: 'pending', prob: null })).toBe('pending');
    expect(ganttBarKind({ status: 'pending', prob: 0.69 })).toBe('risk');
    expect(ganttBarKind({ status: 'pending', prob: 0.7 })).toBe('pending');
    expect(ganttBarKind({ status: 'delayed', prob: 0.99 })).toBe('risk');
    expect(ganttBarKind({ status: 'in_progress', prob: 0.1 })).toBe('in_progress');
    expect(ganttBarKind({ status: 'completed', prob: 0.1 })).toBe('completed');
  });
  it('状态计数、产线数、红条与最低概率', () => {
    const i = buildGanttInsight([
      mk({ label: 'B1', status: 'completed' }),
      mk({ label: 'B2', status: 'in_progress', line: 'L2' }),
      mk({ label: 'B3', prob: 0.5 }),
      mk({ label: 'B4', status: 'delayed', prob: null }),
      mk({ label: 'B5', prob: null }),
    ]);
    expect(i?.finding).toContain('5 个排程分布在 2 条产线');
    expect(i?.finding).toContain('已完成 1、进行中 1、待开始 1、延期/风险 2');
    expect(i?.implication).toContain('2 个标红（1 个已延期，1 个完成概率低于 70%）：B3、B4');
    expect(i?.implication).toContain('2 个未完成排程暂无完成概率'); // B4、B5
    expect(i?.suggestion).toContain('B3');
    expect(i?.suggestion).toContain('50%');
  });
  it('没有红条就说没有，且不给建议', () => {
    const i = buildGanttInsight([mk({}), mk({ label: 'B2' })]);
    expect(i?.implication).toContain('没有排程标红');
    expect(i?.suggestion).toBeUndefined();
  });
  it('空排程不出分析', () => {
    expect(buildGanttInsight([])).toBeNull();
  });
});
