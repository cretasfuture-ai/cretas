/**
 * 生产分析 hub tab 解析 —— LIUSHANMEN 生产管理 IA 收拢 (owner 拍板, 2026-09-02)。
 *
 * 把 6 个视角合并到一个入口 (/production/analysis-query —— ⛔ 不叫 /production/analysis,
 * 见下方「为什么不能叫 /production/analysis」)：
 *   车间实时生产报表 / 生产数据分析 / BOM达成率分析 / 工序投入产出对比 /
 *   人效双口径对比 / 成品出厂核算
 * ?tab= query 同步，与 inventoryQueryTab.ts 同模式 —— 纯函数，方便单测。
 *
 * 🔴 为什么不能叫 /production/analysis: menuConfig.spec.ts 的
 * 「groups production with inline section labels instead of nested lanes」
 * 明确断言 production.children 不含 '/production/analysis' —— 那条闸守的是
 * 「生产管理组用 groupLabel 分段, 不许再长出嵌套子菜单」这个既有设计决策 (与
 * '/production/process' '/production/config' 同一批被禁的名字)。用
 * -query 后缀避免撞上这条闸的字面匹配, 同时与仓储那两个 hub 的命名风格一致。
 *
 * 🔴 6 个视角原本的可见性不是同一个模块/业态门控 (合并前逐条各写各的):
 *   车间实时生产报表 / 生产数据分析 / 成品出厂核算   module:'analytics', hideForFactoryTypes: ['RESTAURANT','FACTORY']
 *   BOM达成率分析 / 工序投入产出对比 / 人效双口径对比  module:'production', 无 hideForFactoryTypes
 * 合并成一个入口后, 路由级 meta 只能挂一份 —— 用 modulesAny:['production','analytics']
 * (路由级放宽为「二者居一」, 父级 /production 组本身已强制要求 production, 所以这不
 * 会让任何原本进不了 /production 组的人多得到访问权), 而**業态门控保留在组件里**:
 * 上述 3 个「analytics 系」tab 只在 factoryType 不是 RESTAURANT/FACTORY 时渲染
 * (与合并前 hideForFactoryTypes: ['RESTAURANT','FACTORY'] 逐字节等价), 见
 * views/production/analysis/index.vue 的 showAnalyticsTabs。
 */
export type ProductionAnalysisTab =
  | 'workshop-report'
  | 'data-analysis'
  | 'bom-achievement'
  | 'process-io'
  | 'labor-efficiency'
  | 'yield-cost';

export const PRODUCTION_ANALYSIS_TABS: ProductionAnalysisTab[] = [
  'workshop-report', 'data-analysis', 'bom-achievement', 'process-io', 'labor-efficiency', 'yield-cost',
];

/** 合并前的 3 个「analytics 系」tab —— 只在非 RESTAURANT/FACTORY 业态渲染 (业态门控原样保留)。 */
export const FACTORY_TYPE_GATED_TABS: ProductionAnalysisTab[] = ['workshop-report', 'data-analysis', 'yield-cost'];

/**
 * 默认 tab = BOM达成率分析。
 *
 * ⚠️ 不选「车间实时生产报表」(owner 目标表里排第一) 当默认 —— 它对 FACTORY/RESTAURANT
 * 业态恒不可见 (见上)，选它当默认会让绝大多数工厂租户落地就看见一个空 tab。
 * BOM达成率分析对所有业态都可见，是三个「production 系」tab 里排第一的。
 */
export const DEFAULT_PRODUCTION_ANALYSIS_TAB: ProductionAnalysisTab = 'bom-achievement';

/** 这个 factoryType 能不能看到 3 个 analytics 系 tab —— 与合并前 hideForFactoryTypes 逐字节等价。 */
export function canSeeFactoryTypeGatedTabs(factoryType: string | null | undefined): boolean {
  return factoryType !== 'RESTAURANT' && factoryType !== 'FACTORY';
}

/**
 * 从 ?tab= query 解析激活 tab。非法/缺省，或该业态看不到这个 tab → 回落默认。
 */
export function resolveProductionAnalysisTab(
  query: Record<string, unknown> | null | undefined,
  factoryType?: string | null,
): ProductionAnalysisTab {
  const t = query?.tab;
  if (!PRODUCTION_ANALYSIS_TABS.includes(t as ProductionAnalysisTab)) {
    return DEFAULT_PRODUCTION_ANALYSIS_TAB;
  }
  const tab = t as ProductionAnalysisTab;
  if (FACTORY_TYPE_GATED_TABS.includes(tab) && !canSeeFactoryTypeGatedTabs(factoryType)) {
    return DEFAULT_PRODUCTION_ANALYSIS_TAB;
  }
  return tab;
}
