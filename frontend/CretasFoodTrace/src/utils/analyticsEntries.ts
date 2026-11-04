import type { User } from '../types/auth';
import { hasModulePermission } from './permissionHelper';

/**
 * 工厂角色在手机上进「AI 分析报告」和「时间范围成本分析」的入口定义 —— 唯一来源。
 *
 * ## 为什么需要这一份（2026-11-04）
 *
 * `AIReportList` / `TimeRangeCostAnalysis` / `AIAnalysisDetail` 此前只注册在
 * `ProcessingStackNavigator`，而那棵树只挂在 `MainNavigator`（AppNavigator 只把
 * platform_admin / permission_admin 派给它）。工厂角色在手机上一个都进不去。
 * Owner 裁定：给「有经营分析权限」的工厂角色开放 ——
 *
 *   factory_super_admin → BossNavigator（总览页「经营分析」）
 *   finance_manager / cashier → FinanceNavigator（首页功能卡 → 隐藏的 FinanceAnalyticsTab）
 *   workshop_supervisor / department_admin → WorkshopSupervisorNavigator（首页快捷操作）
 *
 * 权限判据 = `hasModulePermission(user, 'analytics', 'read')`，与 `AIReportListScreen`
 * 里决定 FAB 的 `canSeeFinanceReport` 是同一个判断（页面内那处保持不动）。
 * 后端 `GET /reports/finance` 要的也是经营分析模块，所以没有它就不放入口，免得点进去 403。
 *
 * ⚠️ 「注册」不等于「授权」：三棵树里这三条路由是无条件注册的，授权只由入口可见性
 * （本文件）+ 页面内判断 + 后端承担。
 */

/** 入口屏。顺序即各处展示顺序。 */
export const ANALYTICS_ENTRIES = [
  { route: 'AIReportList', label: 'AI 分析报告', icon: 'robot-outline', testID: 'analytics-entry-ai-reports' },
  {
    route: 'TimeRangeCostAnalysis',
    label: '时间范围成本分析',
    icon: 'chart-timeline-variant',
    testID: 'analytics-entry-time-range-cost',
  },
] as const;

export type AnalyticsEntry = (typeof ANALYTICS_ENTRIES)[number];
export type AnalyticsEntryRoute = AnalyticsEntry['route'];

/** 这个 user 是否看得到分析入口（有经营分析读权限）。 */
export function canOpenAnalyticsEntries(user: User | null | undefined): boolean {
  return hasModulePermission(user, 'analytics', 'read');
}

/** 按权限过滤后的入口；没有权限返回空数组（⛔ 不返回一个会被渲染成灰按钮的占位）。 */
export function analyticsEntriesFor(user: User | null | undefined): readonly AnalyticsEntry[] {
  return canOpenAnalyticsEntries(user) ? ANALYTICS_ENTRIES : [];
}
