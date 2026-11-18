/**
 * AI 问答图的 ChartMeta 推断（从 AIQuery.vue 抽出，便于单测）。
 *
 * LSM-COSTBI-F3：旧写法 `/占比|率/.test(标题 + 回答正文)` 把「各产品销售金额占比」饼图
 * 判成百分比指标，于是图下分析把金额 62031.8 写成「（62031.8%）」。
 * 现在：
 *  - 饼图的扇区值是绝对值，占比由分析自己除合计，永不判成 pct；
 *  - 柱/线图只有「数值系列本身的名字」是占比/率才算 pct，标题和回答正文不参与；
 *  - 系列名带「金额」时按金额写 ¥（原先销售金额会被当成件数、丢掉单位）。
 */
import type { ChartWithMeta } from './components/chartInsight';

type Meta = NonNullable<ChartWithMeta['meta']>;

export function inferAiChartMeta(
  chartType: string,
  title: string,
  content = '',
  seriesName = '',
): Meta {
  const text = `${title} ${content}`;
  const isReview = /评价|口碑|差评|星级|服务分|环境分|好评|低星|点评|美团/.test(text);
  const isRevenue = /收入|营收|营业额|销售额|堂食|外卖|客单/.test(text);
  const isAmountSeries = /金额|销售额|营收|收入/.test(seriesName);
  const xDim: Meta['xDim'] = chartType === 'line'
    ? 'time'
    : /平台|点评|美团/.test(text)
      ? 'channel'
      : /门店|店/.test(text)
        ? 'store'
        : /菜品|口味|标签|好评词|差评词/.test(text)
          ? 'product'
          : 'category';
  const isPct = chartType !== 'pie' && /占比|率/.test(seriesName);
  const yMetric: Meta['yMetric'] = isPct
    ? 'pct'
    : isReview
      ? 'count'
      : isRevenue || isAmountSeries
        ? 'revenue'
        : 'quantity';
  return {
    xDim,
    yMetric,
    aggregation: /平均|均值/.test(text) ? 'avg' : 'sum',
    domain: isReview || isRevenue ? 'restaurant' : 'factory',
  };
}
