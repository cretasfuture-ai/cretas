/**
 * 生产分析 & 人效分析 路由模块
 * 独立于SmartBI的生产数据分析模块
 *
 * 2026-09-02 LIUSHANMEN 生产管理菜单收拢 (owner 拍板): 'production' 与
 * 'yield-cost' 两条已合并进 /production/analysis-query 的「生产数据分析」/
 * 「成品出厂核算」tab (见 views/production/analysis/index.vue +
 * router/productionHubRedirect.ts)，component 换成 redirect (保书签, ⛔ 不删),
 * meta 原样不动。'cost-summary' 不在本轮合并范围内 (owner 拍板的合并表未列它)，
 * 保持独立菜单项 —— ⛔ 不要顺手把它也塞进 hub。
 */
import type { RouteRecordRaw } from 'vue-router';
import { buildProductionAnalysisRedirect } from '../productionHubRedirect';

const productionAnalyticsRoutes: RouteRecordRaw[] = [
  {
    path: 'production-analytics',
    name: 'ProductionAnalytics',
    redirect: '/production-analytics/production',
    meta: { requiresAuth: true, title: '生产分析', icon: 'Histogram', module: 'analytics' },
    children: [
      {
        path: 'production',
        name: 'PAProduction',
        redirect: (to) => buildProductionAnalysisRedirect(to, 'data-analysis'),
        meta: { requiresAuth: true, title: '生产数据分析', icon: 'Histogram', module: 'analytics' },
      },
      {
        path: 'efficiency',
        name: 'PAEfficiency',
        component: () => import('@/views/production-analytics/EfficiencyAnalysis.vue'),
        meta: { requiresAuth: true, title: '人效分析', icon: 'User', module: 'analytics' },
      },
      {
        path: 'cost',
        name: 'PACost',
        component: () => import('@/views/production-analytics/FactoryCostAnalysis.vue'),
        meta: { requiresAuth: true, title: '成本分析', icon: 'Coin', module: 'analytics' },
      },
      {
        path: 'yield-cost',
        name: 'PAYieldCost',
        redirect: (to) => buildProductionAnalysisRedirect(to, 'yield-cost'),
        meta: { requiresAuth: true, title: '成品出厂核算', icon: 'Coin', module: 'analytics' },
      },
      {
        path: 'cost-summary',
        name: 'PACostSummary',
        component: () => import('@/views/production-analytics/M67CostSummary.vue'),
        meta: { requiresAuth: true, title: '成本汇总', icon: 'Tickets', module: 'analytics' },
      },
      {
        path: 'three-price-comparison',
        name: 'PAThreePriceComparison',
        component: () => import('@/views/production-analytics/ThreePriceComparison.vue'),
        meta: { requiresAuth: true, title: '三价对比看板', icon: 'Money', module: 'analytics' },
      },
    ],
  },
];

export default productionAnalyticsRoutes;
