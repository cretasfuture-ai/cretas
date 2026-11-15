import type { RouteLocationRaw } from 'vue-router';
import type { ProductionDocumentTrace, ProductionTraceDocument } from '@/types/productionDocumentTrace';

/**
 * 缺陷 51 (owner 拍板 2026-09-02, 生产计划列表「分配工序操作员」入口):
 * 「本来也不应该放在生产批次，应该放在生产计划的操作里面」。
 *
 * 一张生产计划开工后会生成约 10 个批次 —— 9 个 `CLK-W-*` 逐道半成品工件批次
 * (`batchType = 'CLERK_WIP'`) + 1 个 `PB-*` 成品批次, 工序任务
 * (`work_process_tasks.production_batch_id`) 只挂在成品批次上。分配操作员的
 * 入口原来只在 `production/batches/detail.vue` 的「工序明细」卡片里, 用户
 * 从计划列表根本找不到那唯一一个入口(#缺陷51)。
 *
 * ⛔ 不按 "PB-" 前缀猜批次 —— 后端 `ProductionDocumentTraceService` 已经算好了
 * 「这张计划关联的正式生产批次」这个概念: 它调用
 * `ProductionBatchRepository#findByFactoryIdAndProductionPlanId`, 该查询
 * `AND (b.batchType IS NULL OR b.batchType <> 'CLERK_WIP')` —— 天然排除了
 * 9 个内部工件批次, 只剩成品批次, 以 `documentType === 'PRODUCTION_BATCH'` 的
 * 追踪条目形式下发 (`documentId` = 批次主键, 与 batches/detail.vue 路由参数同源)。
 * 这条 API (`getProductionDocumentTrace`) 本就是 list.vue「查看单据追踪」用的那个,
 * 不是新接口。
 */

/** 深链 query key: 批次详情页据此滚动到并高亮「工序明细」卡片。 */
export const ASSIGN_OPERATOR_FOCUS_QUERY_KEY = 'focusAssignment';

/** 计划尚未生成任何成品批次(还没开工/尚未 spawn)时的提示文案。 */
export const PLAN_HAS_NO_FINISHED_BATCH_TOOLTIP = '计划尚未生成成品批次';

/**
 * 从计划的单据追踪响应里，挑出「正式生产批次」(即成品批次，已排除 CLERK_WIP 内部工件)。
 *
 * 空/缺失的 trace 视为「还没有批次」，返回空数组而不是抛错 —— 调用方按
 * `length === 0` 判断要不要灰显入口。
 */
export function finishedGoodsBatchCandidates(
  trace: Pick<ProductionDocumentTrace, 'documents'> | null | undefined,
): ProductionTraceDocument[] {
  if (!trace || !Array.isArray(trace.documents)) return [];
  return trace.documents.filter((doc) => doc?.documentType === 'PRODUCTION_BATCH');
}

/**
 * 目标路由: 跳去该批次的详情页，并带上让详情页滚动定位 + 高亮「工序明细」卡片的 query。
 *
 * ⚠️ 路径与 `documentTraceNavigation.ts` 里 `PRODUCTION_BATCH` 那一支同源
 * (`/production/batches/${id}`) —— 不重新发明一套跳转规则，只多带一个 query。
 */
export function assignProcessOperatorTarget(batchId: string): RouteLocationRaw {
  return {
    path: `/production/batches/${encodeURIComponent(batchId)}`,
    query: { [ASSIGN_OPERATOR_FOCUS_QUERY_KEY]: '1' },
  };
}
