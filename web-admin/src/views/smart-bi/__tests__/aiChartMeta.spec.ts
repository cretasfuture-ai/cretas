import { describe, it, expect } from 'vitest';
import { inferAiChartMeta } from '../aiChartMeta';
import { buildProportionInsight, type ChartWithMeta } from '../components/chartInsight';

// LSM-COSTBI-F3：「各产品销售金额占比」饼图下的分析曾写成「（62031.8%）」
function pieChart(content: string): ChartWithMeta {
  const meta = inferAiChartMeta('pie', '各产品销售金额占比', content, '销售金额');
  return {
    chartType: 'pie',
    title: '各产品销售金额占比',
    meta,
    config: {
      xAxis: { data: ['DEMO产品C', 'DEMO产品A', 'DEMO产品B'] },
      series: [{
        name: '销售金额',
        type: 'pie',
        data: [
          { name: 'DEMO产品C', value: 62031.8 },
          { name: 'DEMO产品A', value: 48000 },
          { name: 'DEMO产品B', value: 31000 },
        ],
      }],
    },
  };
}

describe('inferAiChartMeta (LSM-COSTBI-F3)', () => {
  it('pie 图的金额值不会被判成百分比指标，即使标题/回答正文含「占比」「率」', () => {
    const meta = inferAiChartMeta('pie', '各产品销售金额占比', '产品毛利率和占比如下', '销售金额');
    expect(meta.yMetric).not.toBe('pct');
    expect(meta.yMetric).toBe('revenue');
  });

  it('柱图只有数值系列本身叫占比/率才算 pct，标题含「占比」不算', () => {
    expect(inferAiChartMeta('bar', '各产品销售金额占比排名', '', '销售金额').yMetric).toBe('revenue');
    expect(inferAiChartMeta('bar', '各产品', '', '毛利率').yMetric).toBe('pct');
  });

  it('饼图分析里没有 62031.8% 这种乱数，占比按合计算且金额带 ¥', () => {
    const insight = buildProportionInsight(pieChart('占比如下'), { canViewFinance: true });
    expect(insight).not.toBeNull();
    const text = `${insight!.finding}`;
    expect(text).not.toMatch(/62031\.8%/);
    expect(text).toContain('DEMO产品C占44.0%'); // 62031.8 / 141031.8
    expect(text).toContain('¥6.2万');
  });
});
