import React from 'react';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import type { ProcessingStackParamList } from '../types/navigation';
// 通用分析屏 —— 复用 ProcessingStackNavigator 里注册的同一批组件，⛔ 不复制屏代码。
import AIReportListScreen from '../screens/processing/AIReportListScreen';
import AIAnalysisDetailScreen from '../screens/processing/AIAnalysisDetailScreen';
import TimeRangeCostAnalysisScreen from '../screens/processing/TimeRangeCostAnalysisScreen';
import { screenErrorBoundary } from '../components/common/ScreenErrorBoundary';

const Stack = createNativeStackNavigator<
  Pick<ProcessingStackParamList, 'AIReportList' | 'AIAnalysisDetail' | 'TimeRangeCostAnalysis'>
>();

/**
 * 财务线（finance_manager / cashier）的分析栈：AI 分析报告 + 时间范围成本分析。
 *
 * 三条路由是一张联通图，⛔ 不能只注册入口屏：
 *   · `AIReportList`  → `navigate('AIAnalysisDetail', { reportId, reportType, title })`
 *   · `AIReportList`  → `navigate('TimeRangeCostAnalysis')`（右下角「生成新报告」）
 *
 * 由 `FinanceNavigator` 以**隐藏 Tab**（不占底栏）挂载，入口是首页功能卡
 * （`homeModules.ts` 的 `aiReports` / `timeRangeCost`，受 analytics 读权限控制）。
 * 屏自带 Appbar，所以这里不开 header。
 *
 * 由 `__tests__/gates/analyticsEntriesReachableByFactoryRoles.test.ts` 钉住。
 */
export default function AnalyticsReportStackNavigator() {
  return (
    <Stack.Navigator screenLayout={screenErrorBoundary} screenOptions={{ headerShown: false }}>
      <Stack.Screen name="AIReportList" component={AIReportListScreen} />
      <Stack.Screen name="AIAnalysisDetail" component={AIAnalysisDetailScreen} />
      <Stack.Screen name="TimeRangeCostAnalysis" component={TimeRangeCostAnalysisScreen} />
    </Stack.Navigator>
  );
}
