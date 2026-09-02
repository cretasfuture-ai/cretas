import { describe, expect, it } from 'vitest';
import {
  ASSIGN_OPERATOR_FOCUS_QUERY_KEY,
  PLAN_HAS_NO_FINISHED_BATCH_TOOLTIP,
  assignProcessOperatorTarget,
  finishedGoodsBatchCandidates,
} from '../assignProcessOperatorEntry';
import type { ProductionDocumentTrace } from '@/types/productionDocumentTrace';

/**
 * 缺陷 51: 计划 → 成品批次(PB-*) 的解析必须走后端已排除 CLERK_WIP 的那条链路
 * (ProductionDocumentTraceService → documentType === 'PRODUCTION_BATCH'),
 * ⛔ 不按名字前缀猜。
 */
describe('finishedGoodsBatchCandidates', () => {
  it('只挑 PRODUCTION_BATCH 类型的追踪条目，其它类型(含 CLERK_WIP 相关单据)一律排除', () => {
    const trace: ProductionDocumentTrace = {
      productionPlanId: 'PP-1',
      planNumber: 'PP-2026-0001',
      documents: [
        { documentType: 'MATERIAL_REQUISITION', documentId: 'MR-1' },
        { documentType: 'PRODUCTION_BATCH', documentId: '101', documentNumber: 'PB-20260901-ABC' },
        { documentType: 'PRODUCTION_SETTLEMENT', documentId: 'SET-1' },
      ],
      missingLinks: [],
    };
    const candidates = finishedGoodsBatchCandidates(trace);
    expect(candidates).toHaveLength(1);
    expect(candidates[0].documentId).toBe('101');
    expect(candidates[0].documentNumber).toBe('PB-20260901-ABC');
  });

  it('多个成品批次(联产/多产品)全部保留，不悄悄只留第一个', () => {
    const trace: ProductionDocumentTrace = {
      productionPlanId: 'PP-2',
      planNumber: 'PP-2026-0002',
      documents: [
        { documentType: 'PRODUCTION_BATCH', documentId: '201' },
        { documentType: 'PRODUCTION_BATCH', documentId: '202' },
      ],
      missingLinks: [],
    };
    expect(finishedGoodsBatchCandidates(trace).map((d) => d.documentId)).toEqual(['201', '202']);
  });

  it('计划尚未开工(空 documents / 缺失 trace) → 空数组，不抛错', () => {
    expect(finishedGoodsBatchCandidates({ documents: [] })).toEqual([]);
    expect(finishedGoodsBatchCandidates(null)).toEqual([]);
    expect(finishedGoodsBatchCandidates(undefined)).toEqual([]);
    expect(finishedGoodsBatchCandidates({ documents: undefined as unknown as never[] })).toEqual([]);
  });

  it('阳性对照: PLAN_HAS_NO_FINISHED_BATCH_TOOLTIP 与空结果搭配使用时文案存在', () => {
    expect(PLAN_HAS_NO_FINISHED_BATCH_TOOLTIP).toBe('计划尚未生成成品批次');
  });
});

describe('assignProcessOperatorTarget', () => {
  it('跳批次详情页，并带上让详情页滚动定位+高亮的 query', () => {
    const target = assignProcessOperatorTarget('101');
    expect(target).toEqual({
      path: '/production/batches/101',
      query: { [ASSIGN_OPERATOR_FOCUS_QUERY_KEY]: '1' },
    });
  });

  it('批次 id 会被编码，防止特殊字符破坏路径', () => {
    const target = assignProcessOperatorTarget('a/b c');
    expect((target as { path: string }).path).toBe('/production/batches/a%2Fb%20c');
  });
});
