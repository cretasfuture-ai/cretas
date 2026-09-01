/**
 * LIUSHANMEN 生产管理菜单收拢 (2026-09-02, owner 拍板) — 5 个 hub 的 redirect
 * 构造器 (保书签)。
 *
 * 19 个旧的散落条目合并进 5 个 hub 后，旧路径用 function-form redirect 桥接，
 * **必须保留 incoming query**。这层是纯函数，方便单测。
 *
 * 与 warehouseHubRedirect.ts (LIUSHANMEN 仓储「库存盘点」菜单收拢) 同模式。
 */
import type { LocationQuery, LocationQueryRaw } from 'vue-router';
import type { ApprovalQueryTab } from '@/views/production/approvalQueryTab';
import type { MaterialFlowTab } from '@/views/production/materialFlowTab';
import type { ProductionKanbanTab } from '@/views/production/productionKanbanTab';
import type { ProductionAnalysisTab } from '@/views/production/productionAnalysisTab';
import type { ProcessConfigTab } from '@/views/production/processConfigTab';

export const APPROVAL_QUERY_HUB_PATH = '/production/approval';
export const MATERIAL_FLOW_HUB_PATH = '/production/material-flow';
export const PRODUCTION_KANBAN_HUB_PATH = '/production/kanban';
export const PRODUCTION_ANALYSIS_HUB_PATH = '/production/analysis-query';
export const PROCESS_CONFIG_HUB_PATH = '/production/process-hub';

export interface RedirectTarget {
  path: string;
  query: LocationQueryRaw;
}

/** 构造一个保留 incoming query 并叠加 `tab` 的报工审批 hub redirect 目标。 */
export function buildApprovalQueryRedirect(to: { query: LocationQuery }, tab: ApprovalQueryTab): RedirectTarget {
  return { path: APPROVAL_QUERY_HUB_PATH, query: { ...to.query, tab } };
}

/** 构造一个保留 incoming query 并叠加 `tab` 的领料 hub redirect 目标。 */
export function buildMaterialFlowRedirect(to: { query: LocationQuery }, tab: MaterialFlowTab): RedirectTarget {
  return { path: MATERIAL_FLOW_HUB_PATH, query: { ...to.query, tab } };
}

/** 构造一个保留 incoming query 并叠加 `tab` 的生产看板 hub redirect 目标。 */
export function buildProductionKanbanRedirect(to: { query: LocationQuery }, tab: ProductionKanbanTab): RedirectTarget {
  return { path: PRODUCTION_KANBAN_HUB_PATH, query: { ...to.query, tab } };
}

/** 构造一个保留 incoming query 并叠加 `tab` 的生产分析 hub redirect 目标。 */
export function buildProductionAnalysisRedirect(to: { query: LocationQuery }, tab: ProductionAnalysisTab): RedirectTarget {
  return { path: PRODUCTION_ANALYSIS_HUB_PATH, query: { ...to.query, tab } };
}

/** 构造一个保留 incoming query 并叠加 `tab` 的工序 hub redirect 目标。 */
export function buildProcessConfigRedirect(to: { query: LocationQuery }, tab: ProcessConfigTab): RedirectTarget {
  return { path: PROCESS_CONFIG_HUB_PATH, query: { ...to.query, tab } };
}
