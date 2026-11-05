import type { MenuItem } from './menuConfig';

/** Navigation shortcuts only; AppSidebar still applies every module/feature gate. */
export function getF006DemoMenu(factoryId: string | undefined, role: string): MenuItem[] {
  if (factoryId !== 'F006' || role !== 'factory_super_admin') return [];
  return [{
    // Submenu namespace: the parent expands; only its leaf items navigate.
    path: '/f006-demo-center', title: '演示中心', icon: 'Monitor', module: 'dashboard',
    children: [
      { path: '/demo-flow', title: '一键流程演示', icon: '', module: 'dashboard' },
      { path: '/smart-bi/dashboard', title: 'SmartBI 经营驾驶舱', icon: '', module: 'analytics', groupLabel: '分析与 AI' },
      { path: '/smart-bi/analysis', title: 'AI 问答与数据分析', icon: '', module: 'analytics' },
      { path: '/smart-bi/analysis-hub', title: '经营分析', icon: '', module: 'analytics' },
      { path: '/production-analytics/cost', title: '生产成本分析', icon: '', module: 'analytics' },
      { path: '/dashboard/ai-value', title: 'AI 工作台', icon: '', module: 'dashboard' },
      { path: '/production/traceability', title: '产品反向溯源', icon: '', module: 'production', groupLabel: '质量与追溯' },
      { path: '/quality/label-qc', title: '视觉质检图片', icon: '', module: 'quality' },
      { path: '/quality/vision-training', title: '视觉质检样例库', icon: '', module: 'quality' },
      { path: '/procurement/orders', title: '采购订单', icon: '', module: 'procurement', groupLabel: '进销存与生产' },
      { path: '/warehouse/materials', title: '仓库收货与批次', icon: '', module: 'warehouse' },
      { path: '/production/plans', title: '生产计划与报工', icon: '', module: 'production' },
      { path: '/production/material-flow', title: '生产领料', icon: '', module: 'production' },
      { path: '/sales/finished-goods', title: '成品库存', icon: '', module: 'sales' },
      { path: '/sales/orders', title: '销售订单与出库', icon: '', module: 'sales' },
    ],
  }];
}
