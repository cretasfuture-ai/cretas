import { describe, it, expect } from 'vitest';
import {
  periodSelectionToRequest,
  chartGapReason,
  chartNotes,
  createDataVersion,
  buildChartNav,
} from '../financialDashboardState';
import type { ChartStateLike as ChartLike } from '../financialDashboardState';
import type { PeriodSelection } from '@/components/smartbi/PeriodSelector.vue';

const sel = (s: Partial<PeriodSelection>): PeriodSelection =>
  ({ type: 'year', year: 2026, value: '2026', ...s }) as PeriodSelection;

describe('periodSelectionToRequest (期间选择 → 后端参数)', () => {
  it('全部历史 → period_type=all, 不按年过滤', () => {
    expect(periodSelectionToRequest(sel({ allHistory: true }))).toEqual({
      periodType: 'all', year: 2026, startMonth: 1, endMonth: 12,
    });
  });

  it('单年 → period_type=year, 只要那一年', () => {
    expect(periodSelectionToRequest(sel({ type: 'year', year: 2025, value: '2025' }))).toEqual({
      periodType: 'year', year: 2025, startMonth: 1, endMonth: 12,
    });
  });

  it('单月 "2026-03" → 只要 3 月 (修复前会落到 1-12 月)', () => {
    expect(periodSelectionToRequest(sel({ type: 'month', value: '2026-03' }))).toEqual({
      periodType: 'month_range', year: 2026, startMonth: 3, endMonth: 3,
    });
  });

  it('单季 "2026-Q3" → 7-9 月', () => {
    expect(periodSelectionToRequest(sel({ type: 'quarter', value: '2026-Q3' }))).toEqual({
      periodType: 'month_range', year: 2026, startMonth: 7, endMonth: 9,
    });
  });

  it('月份区间 1-7 月', () => {
    expect(periodSelectionToRequest(sel({ type: 'month_range', value: ['2026-01', '2026-07'] }))).toEqual({
      periodType: 'month_range', year: 2026, startMonth: 1, endMonth: 7,
    });
  });

  it('季度区间 Q1-Q2 → 1-6 月 (修复前把 "Q1" 当数字解析成 NaN)', () => {
    expect(periodSelectionToRequest(sel({ type: 'quarter_range', value: ['2026-Q1', '2026-Q2'] }))).toEqual({
      periodType: 'month_range', year: 2026, startMonth: 1, endMonth: 6,
    });
  });

  it('同年自定义区间按月份区间处理, 年份取区间本身', () => {
    expect(periodSelectionToRequest(sel({ type: 'custom', year: 2026, value: ['2025-02', '2025-08'] }))).toEqual({
      periodType: 'month_range', year: 2025, startMonth: 2, endMonth: 8,
    });
  });

  it('跨年自定义区间 → 明确报错, 不静默截断', () => {
    const r = periodSelectionToRequest(sel({ type: 'custom', value: ['2025-11', '2026-02'] }));
    expect('error' in r && r.error).toContain('跨年');
  });
});

describe('chartGapReason / chartNotes (数据不足与口径说明要显示给用户)', () => {
  it('没有图的图表返回后端给的原因', () => {
    const chart = {
      chartType: 'channel_analysis', echartsOption: {}, kpis: [],
      metadata: { dataQuality: 'insufficient', reason: '上传的数据里没有「渠道」列' },
      analysisContext: '上传的数据里没有「渠道」列',
    };
    expect(chartGapReason(chart)).toContain('渠道');
  });

  it('有图的图表不算缺数据', () => {
    const chart = { chartType: 'x', echartsOption: { series: [] }, kpis: [], metadata: { dataQuality: 'good' } };
    expect(chartGapReason(chart)).toBeNull();
  });

  it('对账差异与口径说明都要列出', () => {
    const chart = {
      chartType: 'pnl_waterfall', echartsOption: { series: [] }, kpis: [],
      metadata: { reconciliation: ['净利润：表内 3,300，按明细计算 3,308，差额 -8'], note: '合计为各月简单平均' },
    };
    expect(chartNotes(chart)).toEqual(['净利润：表内 3,300，按明细计算 3,308，差额 -8', '合计为各月简单平均']);
  });
});

describe('buildChartNav (图表切换条只放能出图的图表)', () => {
  const entries = [
    { key: 'budget_achievement', label: '预算达成分析', group: '核心财务' },
    { key: 'yoy_mom_comparison', label: '同环比分析', group: '核心财务' },
    { key: 'channel_analysis', label: '渠道分析', group: '品类与渠道' },
    { key: 'bullet_chart', label: '目标达成进度', group: '运营专项' },
  ];
  const charts: Record<string, ChartLike> = {
    budget_achievement: { echartsOption: { series: [] }, metadata: { dataQuality: 'good' } },
    channel_analysis: { echartsOption: {}, metadata: { dataQuality: 'insufficient', reason: '上传的数据里没有「渠道」列' } },
    bullet_chart: { echartsOption: { series: [] }, metadata: { dataQuality: 'good' } },
  };
  const nav = buildChartNav(entries, k => charts[k]);

  it('能出图的按分组列出, 保持原顺序, 空分组不出现', () => {
    expect(nav.groups.map(g => [g.name, g.items.map(i => i.key)])).toEqual([
      ['核心财务', ['budget_achievement']],
      ['运营专项', ['bullet_chart']],
    ]);
  });

  it('出不了的图收进缺数据, 带原因 (后端没返回的也算)', () => {
    expect(nav.missing.map(m => [m.key, m.reason])).toEqual([
      ['yoy_mom_comparison', '这份数据缺少这张图需要的列'],
      ['channel_analysis', '上传的数据里没有「渠道」列'],
    ]);
  });
});

describe('createDataVersion (换期间/换数据后丢弃旧的分析结果)', () => {
  it('旧版本发出的请求在新版本下不再算数', () => {
    const v = createDataVersion();
    const first = v.current();
    v.bump();
    expect(v.isCurrent(first)).toBe(false);
    expect(v.isCurrent(v.current())).toBe(true);
  });
});
