import { TrendCharts, Histogram, DataLine, User } from '@element-plus/icons-vue';

/** 经营驾驶舱「快捷问答」: 食品工厂问法(点击后由 Dashboard.goToAIQuery 发送)。 */
export const manufacturingQuickQuestions = [
  { text: '本月产量和出成率如何?', icon: TrendCharts },
  { text: '哪个产品毛利最高?', icon: Histogram },
  { text: '原料成本有没有上涨?', icon: DataLine },
  { text: '哪个客户本月采购最多?', icon: User },
];
