import { describe, it, expect } from 'vitest';
import type { LocationQuery } from 'vue-router';
import {
  buildApprovalQueryRedirect,
  buildMaterialFlowRedirect,
  buildProductionKanbanRedirect,
  buildProductionAnalysisRedirect,
  buildProcessConfigRedirect,
  APPROVAL_QUERY_HUB_PATH,
  MATERIAL_FLOW_HUB_PATH,
  PRODUCTION_KANBAN_HUB_PATH,
  PRODUCTION_ANALYSIS_HUB_PATH,
  PROCESS_CONFIG_HUB_PATH,
} from '../productionHubRedirect';

const q = (o: Record<string, string>): LocationQuery => o as unknown as LocationQuery;

describe('productionHubRedirect — 旧页 → 5 个生产管理 hub redirect (LIUSHANMEN IA 收拢)', () => {
  it('hub path 常量', () => {
    expect(APPROVAL_QUERY_HUB_PATH).toBe('/production/approval');
    expect(MATERIAL_FLOW_HUB_PATH).toBe('/production/material-flow');
    expect(PRODUCTION_KANBAN_HUB_PATH).toBe('/production/kanban');
    expect(PRODUCTION_ANALYSIS_HUB_PATH).toBe('/production/analysis-query');
    expect(PROCESS_CONFIG_HUB_PATH).toBe('/production/process-hub');
  });

  it('报工审批 2 个视角各 redirect 到对应 tab', () => {
    expect(buildApprovalQueryRedirect({ query: q({}) }, 'approval')).toEqual({
      path: APPROVAL_QUERY_HUB_PATH, query: { tab: 'approval' },
    });
    expect(buildApprovalQueryRedirect({ query: q({}) }, 'reversal')).toEqual({
      path: APPROVAL_QUERY_HUB_PATH, query: { tab: 'reversal' },
    });
  });

  it('领料 2 个视角各 redirect 到对应 tab', () => {
    expect(buildMaterialFlowRedirect({ query: q({}) }, 'requisitions')).toEqual({
      path: MATERIAL_FLOW_HUB_PATH, query: { tab: 'requisitions' },
    });
    expect(buildMaterialFlowRedirect({ query: q({}) }, 'returns')).toEqual({
      path: MATERIAL_FLOW_HUB_PATH, query: { tab: 'returns' },
    });
  });

  it('生产看板 3 个视角各 redirect 到对应 tab', () => {
    expect(buildProductionKanbanRedirect({ query: q({}) }, 'pending-queue')).toEqual({
      path: PRODUCTION_KANBAN_HUB_PATH, query: { tab: 'pending-queue' },
    });
    expect(buildProductionKanbanRedirect({ query: q({}) }, 'restock-board')).toEqual({
      path: PRODUCTION_KANBAN_HUB_PATH, query: { tab: 'restock-board' },
    });
    expect(buildProductionKanbanRedirect({ query: q({}) }, 'delivery-warnings')).toEqual({
      path: PRODUCTION_KANBAN_HUB_PATH, query: { tab: 'delivery-warnings' },
    });
  });

  it('生产分析 6 个视角各 redirect 到对应 tab', () => {
    for (const tab of [
      'workshop-report', 'data-analysis', 'bom-achievement',
      'process-io', 'labor-efficiency', 'yield-cost',
    ] as const) {
      expect(buildProductionAnalysisRedirect({ query: q({}) }, tab)).toEqual({
        path: PRODUCTION_ANALYSIS_HUB_PATH, query: { tab },
      });
    }
  });

  it('工序 2 个视角各 redirect 到对应 tab', () => {
    expect(buildProcessConfigRedirect({ query: q({}) }, 'work-processes')).toEqual({
      path: PROCESS_CONFIG_HUB_PATH, query: { tab: 'work-processes' },
    });
    expect(buildProcessConfigRedirect({ query: q({}) }, 'product-processes')).toEqual({
      path: PROCESS_CONFIG_HUB_PATH, query: { tab: 'product-processes' },
    });
  });

  it('保留 incoming query (e.g. 深链 ?batchId=x), tab 叠加', () => {
    expect(buildApprovalQueryRedirect({ query: q({ batchId: 'B-1' }) }, 'reversal')).toEqual({
      path: APPROVAL_QUERY_HUB_PATH, query: { batchId: 'B-1', tab: 'reversal' },
    });
  });

  it('5 个 hub 互不影响 (阴性对照: 各自的 redirect 不会写到别的 hub 路径上)', () => {
    const paths = new Set([
      buildApprovalQueryRedirect({ query: q({}) }, 'approval').path,
      buildMaterialFlowRedirect({ query: q({}) }, 'requisitions').path,
      buildProductionKanbanRedirect({ query: q({}) }, 'pending-queue').path,
      buildProductionAnalysisRedirect({ query: q({}) }, 'bom-achievement').path,
      buildProcessConfigRedirect({ query: q({}) }, 'work-processes').path,
    ]);
    expect(paths.size).toBe(5);
  });
});
