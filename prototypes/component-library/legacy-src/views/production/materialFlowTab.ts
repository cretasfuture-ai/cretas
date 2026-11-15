/**
 * 领料 hub tab 解析 —— LIUSHANMEN 生产管理 IA 收拢 (owner 拍板, 2026-09-02)。
 *
 * 把「领料单」(原「物料需求单」, #460 已改名) + 「退料记录」合并到一个入口
 * (/production/material-flow)。?tab= query 同步，与 inventoryQueryTab.ts 同模式 ——
 * 纯函数，方便单测。
 *
 * 旧路径仍可直接访问，只重定向不删除。两个原页面 (views/factory/material-requisitions/list.vue
 * / views/factory/material-returns/list.vue) 内容原样未改动 —— 见 hub 组件头部说明。
 */
export type MaterialFlowTab = 'requisitions' | 'returns';

export const MATERIAL_FLOW_TABS: MaterialFlowTab[] = ['requisitions', 'returns'];

/** 默认 tab = 领料单 (原 /production/material-requisitions，合并前最常用)。 */
export const DEFAULT_MATERIAL_FLOW_TAB: MaterialFlowTab = 'requisitions';

/**
 * 从 ?tab= query 解析激活 tab。非法/缺省 → 默认 requisitions。
 */
export function resolveMaterialFlowTab(query: Record<string, unknown> | null | undefined): MaterialFlowTab {
  const t = query?.tab;
  return MATERIAL_FLOW_TABS.includes(t as MaterialFlowTab)
    ? (t as MaterialFlowTab)
    : DEFAULT_MATERIAL_FLOW_TAB;
}
