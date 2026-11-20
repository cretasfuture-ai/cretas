/**
 * 问数据（AI）回答下方「数据驱动」洞察页脚用的图表元数据 (味美实测 A6/A8, 2026-11-19)。
 *
 * 改前: 指标类型和单位是拿「图标题 + 整段回答文字」做关键词匹配出来的。回答里只要出现
 * 「率」就把整张图当百分比 —— 计数 2,259 架次被写成「541.0%」、良品 77,893.6 kg 被写成
 * 「77893.6%」；回答里出现「金额/收入」就把 kg 写成「元」。
 *
 * 规则 (⛔ 禁止降级处理: 不认识单位就不加单位, 不猜):
 *   1. 指标与单位只看图自己带的信息: 系列名 + 坐标轴名 + 图标题; 系列名有就以系列名为准
 *      (标题常是「按周达成率」, 而这条线画的是「已产出品项数」)。
 *   2. 百分比必须同时满足: 文字里有 率/占比/比例/% ，且所有数值都在 0–100 内。
 *   3. 金额必须文字里有 金额/收入/成本… 且没有 kg/分钟/架次 等非金额单位。
 *   4. 认出单位 (kg/吨/分钟/小时/架次/件/个/台/箱) 才带单位, 否则只写数字。
 *   5. 完全没线索时才回退到回答文字, 且只用来判断餐饮的营收/评价口径, 不产出百分比和单位。
 */
import type { ChartMeta } from '../chartInsight';

export interface AiChartMetaInput {
  chartType: string;
  title: string;
  /** 系列名 (series[].name) */
  seriesNames?: string[];
  /** 坐标轴名 (xAxis.name / yAxis.name) */
  axisNames?: string[];
  /** 全部数值点, 用于判断「是不是真的像百分数」 */
  values?: number[];
  /** 回答正文; 仅在图本身没有任何指标线索时用来判断餐饮营收/评价口径 */
  content?: string;
}

const UNIT_PATTERNS: Array<[RegExp, string]> = [
  [/公斤|千克|kg/i, 'kg'],
  [/吨/, '吨'],
  [/分钟|min/i, '分钟'],
  [/小时/, '小时'],
  [/架次/, '架次'],
  [/件数|件/, '件'],
  [/个数|品项|个/, '个'],
  [/台数|台/, '台'],
  [/箱/, '箱'],
];

const MONEY_WORDS = /元|金额|营收|收入|销售额|营业额|成本|费用|利润|毛利|价|客单/;
const SCALED_YUAN_UNIT = /(?:亿|千万|百万|十万|万|千|百)元/;
const RATE_WORDS = /率|占比|比例|%|％|pct|percent|ratio/i;
const REVIEW_WORDS = /评价|口碑|差评|星级|服务分|环境分|好评|低星|点评|美团/;
const REST_REVENUE_WORDS = /收入|营收|营业额|销售额|堂食|外卖|客单/;

function detectUnit(text: string): string | undefined {
  // 括号里写明的单位优先:「良品（kg）」「时长(分钟)」
  const paren = text.match(/[（(]\s*([^）)\s]{1,4})\s*[）)]/);
  if (paren) {
    const moneyUnit = paren[1].match(SCALED_YUAN_UNIT)?.[0];
    if (moneyUnit) return moneyUnit;
    for (const [re, unit] of UNIT_PATTERNS) if (re.test(paren[1])) return unit;
  }
  const moneyUnit = text.match(SCALED_YUAN_UNIT)?.[0];
  if (moneyUnit) return moneyUnit;
  for (const [re, unit] of UNIT_PATTERNS) if (re.test(text)) return unit;
  return undefined;
}

function looksLikePercentValues(values: number[] | undefined): boolean {
  const nums = (values ?? []).filter((v) => typeof v === 'number' && Number.isFinite(v));
  return nums.length > 0 && nums.every((v) => v >= 0 && v <= 100);
}

export function inferAiChartMeta(input: AiChartMetaInput): NonNullable<ChartMeta> {
  const { chartType, title } = input;
  const names = (input.seriesNames ?? []).filter(Boolean).join(' ');
  const axes = (input.axisNames ?? []).filter(Boolean).join(' ');
  // 系列名优先; 没有系列名才看标题
  const metricText = `${names} ${axes}`.trim() || title;
  const allText = `${title} ${names} ${axes}`;
  const hasClue = RATE_WORDS.test(metricText) || MONEY_WORDS.test(metricText) || detectUnit(metricText) !== undefined;

  const text = hasClue ? metricText : `${metricText} ${input.content ?? ''}`;
  // 餐饮口径 (评价/营收) 沿用原判断: 标题 + 回答文字。只影响 domain / 评价类 yMetric, 不产出百分比和单位。
  const legacyText = `${allText} ${input.content ?? ''}`;
  const isReview = REVIEW_WORDS.test(legacyText);
  const unit = detectUnit(metricText);
  const scaledMoney = unit !== undefined && SCALED_YUAN_UNIT.test(unit);
  // 饼图扇区值是绝对值, 占比由分析自己除合计, 永不判成 pct (LSM-COSTBI-F3 #1901: 金额 62031.8 曾被写成 62031.8%)。
  // values 未提供 (旧调用方只给系列名) 时不做数值范围核对; 提供了 (含空数组) 就必须都在 0–100。
  const isPct = chartType !== 'pie' && RATE_WORDS.test(metricText) && !unit
    && (input.values === undefined || looksLikePercentValues(input.values));
  const isMoney = scaledMoney || (!isPct && !unit
    && (MONEY_WORDS.test(metricText) || (!hasClue && REST_REVENUE_WORDS.test(text))));

  const xDim: ChartMeta['xDim'] = chartType === 'line'
    ? 'time'
    : /平台|点评|美团/.test(allText)
      ? 'channel'
      : /门店|店/.test(allText)
        ? 'store'
        : /菜品|口味|标签|好评词|差评词/.test(allText)
          ? 'product'
          : 'category';

  const yMetric: ChartMeta['yMetric'] = isPct
    ? 'pct'
    : isReview && !scaledMoney
      ? 'count'
      : isMoney
        ? 'revenue'
        : 'quantity';

  const meta: ChartMeta = {
    xDim,
    yMetric,
    aggregation: /平均|均值/.test(metricText) ? 'avg' : 'sum',
    domain: isReview || REST_REVENUE_WORDS.test(legacyText) ? 'restaurant' : 'factory',
  };
  // 万元等显式金额单位照原样传递，无显式单位的金额仍走 ¥。
  if (unit && yMetric !== 'pct') meta.unit = unit;
  return meta;
}

/** ECharts option 里取系列名 / 坐标轴名 / 数值点, 喂给 inferAiChartMeta。 */
export function chartSignalsFromOption(option: Record<string, unknown>): Pick<AiChartMetaInput, 'seriesNames' | 'axisNames' | 'values'> {
  const seriesNames: string[] = [];
  const values: number[] = [];
  const series = Array.isArray(option['series']) ? (option['series'] as Array<Record<string, unknown>>) : [];
  for (const s of series) {
    if (typeof s['name'] === 'string') seriesNames.push(s['name']);
    const data = Array.isArray(s['data']) ? (s['data'] as unknown[]) : [];
    for (const d of data) {
      if (typeof d === 'number' && Number.isFinite(d)) values.push(d);
      else if (d && typeof d === 'object' && typeof (d as { value?: unknown }).value === 'number') {
        const v = (d as { value: number }).value;
        if (Number.isFinite(v)) values.push(v);
      }
    }
  }
  const axisNames: string[] = [];
  for (const key of ['xAxis', 'yAxis'] as const) {
    const ax = option[key];
    for (const a of Array.isArray(ax) ? ax : [ax]) {
      const name = a && typeof a === 'object' ? (a as { name?: unknown }).name : undefined;
      if (typeof name === 'string') axisNames.push(name);
    }
  }
  return { seriesNames, axisNames, values };
}
