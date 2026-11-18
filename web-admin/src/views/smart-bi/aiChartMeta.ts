/**
 * AI 问答图的 ChartMeta 推断 —— 旧签名适配层。
 *
 * LSM-COSTBI-F3 (#1901) 与味美 A6 (#1923) 各自抽出过一份 inferAiChartMeta, 两份会漂 (形态 D)。
 * 现在唯一实现在 ./components/chat/aiChartMeta.ts (只看系列名/坐标轴/标题 + 数值范围, 饼图永不 pct);
 * 本文件只把旧的 (chartType, title, content, seriesName) 调用转过去, 不再有自己的判断逻辑。
 */
import type { ChartWithMeta } from './components/chartInsight';
import { inferAiChartMeta as inferFromSignals } from './components/chat/aiChartMeta';

type Meta = NonNullable<ChartWithMeta['meta']>;

export function inferAiChartMeta(
  chartType: string,
  title: string,
  content = '',
  seriesName = '',
): Meta {
  return inferFromSignals({
    chartType,
    title,
    content,
    seriesNames: seriesName ? [seriesName] : [],
  });
}
