import { describe, it, expect } from 'vitest';
import { buildChartInsight, type ChartWithMeta } from '../../chartInsight';
import { chartSignalsFromOption, inferAiChartMeta } from '../aiChartMeta';
import { historyForSource, type HistoryMessage } from '../aiChatContext';

/** 和 AIQuery.vue 同一条路径: ECharts option -> 信号 -> meta -> Tier1 洞察文字 (页脚)。 */
function footerFor(
  chartType: 'bar' | 'line' | 'pie',
  title: string,
  option: Record<string, unknown>,
  content = '',
) {
  const labels = (option.xAxis as { data?: string[] } | undefined)?.data ?? [];
  const series = (option.series as Array<{ name?: string; data: unknown[] }>).map((s) => ({
    name: s.name, type: chartType, data: s.data,
  }));
  const meta = inferAiChartMeta({ chartType, title, content, ...chartSignalsFromOption(option) });
  const chart: ChartWithMeta = {
    chartType, title, meta,
    config: { xAxis: { data: labels }, series },
  };
  const insight = buildChartInsight(chart, { canViewFinance: false });
  return { meta, text: insight ? `${insight.finding} ${insight.implication ?? ''}` : '' };
}

describe('A6 页脚单位: 计数/kg 不能被写成 %、元', () => {
  it('kg 的图, 标题带「率」、回答里有「金额」: 不出 %、不出 ¥, 单位是 kg (实测 77893.6%)', () => {
    const { meta, text } = footerFor('bar', '各产品计划达成率', {
      xAxis: { data: ['26001', '26002', '26003'] },
      series: [{ name: '良品(kg)', data: [77893.6, 41200, 9800] }],
    }, '按金额看良品总量最高的是 26001');
    expect(meta.yMetric).not.toBe('pct');
    expect(meta.unit).toBe('kg');
    expect(text).not.toMatch(/77[,]?893(\.6)?%/);
    expect(text).not.toContain('¥');
    expect(text).toContain('kg');
  });

  it('架次计数 2,259 + 回答里有「出成率」: 不能写成 541.0% / 22.0%', () => {
    const { meta, text } = footerFor('line', '10月出成率趋势', {
      xAxis: { data: ['10/01', '10/02', '10/03', '10/04'] },
      series: [{ name: '总架次', data: [541, 600, 560, 558] }],
    }, '整体出成率未提供');
    expect(meta.yMetric).toBe('quantity');
    expect(meta.unit).toBe('架次');
    // 数值本身不带 % (「后半段比前半段低2.0%」是相对变化, 合法)
    expect(text).not.toMatch(/(2,259|600|541|558)(\.\d)?%/);
    expect(text).toContain('架次');
  });

  it('不认识的指标 (无单位线索): 只写数字, 不加单位也不加 %', () => {
    const { meta, text } = footerFor('line', '按周达成率', {
      xAxis: { data: ['W40', 'W41', 'W42'] },
      series: [{ name: '已产出', data: [22, 18, 20] }],
    });
    expect(meta.yMetric).toBe('quantity');
    expect(meta.unit).toBeUndefined();
    expect(text).not.toMatch(/(?<![降低高升比])(22|20|18|60)(\.\d)?%/);
    expect(text).not.toContain('¥');
  });

  it('阴性对照: 真正的率 (系列名是出成率, 数值在 0-100) 仍然写 %', () => {
    const { meta, text } = footerFor('line', '出成率趋势', {
      xAxis: { data: ['10/05', '10/06', '10/07', '10/08'] },
      series: [{ name: '出成率', data: [97.8, 98.1, 90.2, 97.5] }],
    });
    expect(meta.yMetric).toBe('pct');
    expect(text).toMatch(/\d(\.\d)?%/);
  });

  it('阴性对照: 系列名是销售额 (金额列) 时有成本权限才出现的 ¥ 路径不被误伤', () => {
    const meta = inferAiChartMeta({
      chartType: 'bar', title: '各客户销售额',
      ...chartSignalsFromOption({ series: [{ name: '销售额', data: [120000, 80000] }] }),
    });
    expect(meta.yMetric).toBe('revenue');
    expect(meta.unit).toBeUndefined();
  });

  it('率字样但数值是 kg 量级 (超出 0-100) 且没有单位名: 不当百分比', () => {
    const meta = inferAiChartMeta({
      chartType: 'bar', title: '达成率',
      ...chartSignalsFromOption({ series: [{ name: '达成率', data: [77893.6, 120] }] }),
    });
    expect(meta.yMetric).not.toBe('pct');
  });
});

describe('A7/A8 切换数据源后历史不串', () => {
  const msgs: HistoryMessage[] = [
    { id: 'welcome', role: 'assistant', content: '你好' },
    { id: 'u1', role: 'user', content: '哪一周计划品项达成率最低？', sourceUploadId: 5335 },
    { id: 'a1', role: 'assistant', content: 'W42，0.69', sourceUploadId: 5335 },
    { id: 'u2', role: 'user', content: '哪些产品没按计划完成？', sourceUploadId: 5323 },
    { id: 'a2', role: 'assistant', content: '', loading: true, sourceUploadId: 5323 },
  ];

  it('切到 5323 后, 带给后端的历史里没有 5335 的问答 (修前: 全部带上 -> 复读上一题)', () => {
    const h = historyForSource(msgs, 5323, ['welcome', 'a2', 'u2']);
    expect(h).toEqual([]);
    expect(JSON.stringify(h)).not.toContain('W42');
  });

  it('阴性对照: 同源追问仍带上同源的上一轮', () => {
    const h = historyForSource(msgs, 5335, ['welcome', 'a2', 'u2']);
    expect(h).toEqual([
      { role: 'user', content: '哪一周计划品项达成率最低？' },
      { role: 'assistant', content: 'W42，0.69' },
    ]);
  });

  it('未选数据源 (null) 的对话只和未选的消息互通', () => {
    const m: HistoryMessage[] = [
      { id: 'x1', role: 'user', content: 'q1', sourceUploadId: null },
      { id: 'x2', role: 'assistant', content: 'a1' }, // 旧消息没标记 = 未选
      { id: 'x3', role: 'user', content: 'q-on-5335', sourceUploadId: 5335 },
    ];
    expect(historyForSource(m, null, []).map((x) => x.content)).toEqual(['q1', 'a1']);
    expect(historyForSource(m, undefined, []).map((x) => x.content)).toEqual(['q1', 'a1']);
  });

  it('仍只取最近 40 条', () => {
    const many: HistoryMessage[] = Array.from({ length: 50 }, (_, i) => ({
      id: `m${i}`, role: i % 2 ? 'assistant' : 'user', content: `c${i}`, sourceUploadId: 1,
    }));
    const h = historyForSource(many, 1, []);
    expect(h).toHaveLength(40);
    expect(h[0].content).toBe('c10');
  });
});
