import { describe, expect, it } from 'vitest';
import { applyWorkflowPatches } from '../workflowModel';
import type { ProcessNodeData, ProductProcessWorkflowDefinition } from '../types';

/**
 * AI 草稿这条链上，本轮**动过**的正是补丁的字段白名单与校验器
 * （新增 `isRework` / `reworkPolicy`）。
 *
 * ⚠️ 这两处是**同一道闸的两个承载点**（见 workflowModel.ts 里 fieldPaths 的注释）：
 * 键没进 `materialDataKeys` / `processDataKeys` 会被 `hasAllowedKeys` 拒，
 * 值形状不对会被 `isOptionalReworkPolicy` 拒，两种都报同一句话。
 * 所以正例和反例都要有，否则分不清「支持了」和「恰好没被这条测试碰到」。
 */

function baseDefinition(): ProductProcessWorkflowDefinition {
  return {
    schemaVersion: 1,
    status: 'DRAFT',
    version: 1,
    nodes: [
      {
        id: 'process:cook',
        kind: 'PROCESS',
        position: { x: 0, y: 0 },
        data: {
          workProcessId: 'WP-COOK',
          processName: '熟制',
          inputUnit: 'kg',
          outputUnit: 'kg',
          ports: [],
          conversionRule: { mode: 'ACTUAL_WEIGHT' },
          reportingRequired: true,
        } as ProcessNodeData,
      },
    ],
    edges: [],
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

describe('AI 补丁：回料相关字段', () => {
  it('可以建一个带 isRework 标记的产出 Cell', () => {
    const result = applyWorkflowPatches(baseDefinition(), [{
      op: 'UPSERT_NODE',
      node: {
        id: 'material:output:1',
        kind: 'SEMI_FINISHED',
        position: { x: 100, y: 100 },
        data: { name: '金检剔出回料', skuId: 'PT-REWORK', isRework: true },
      },
    }]);
    expect(result.errors).toEqual([]);
    expect(result.summary).toHaveLength(1);
    const created = result.definition.nodes.find((node) => node.id === 'material:output:1');
    expect((created?.data as { isRework?: boolean })?.isRework).toBe(true);
  });

  it('可以给目标工序写 reworkPolicy（per-(回料, 工序) 的配方项）', () => {
    const definition = baseDefinition();
    const process = definition.nodes[0];
    const result = applyWorkflowPatches(definition, [{
      op: 'UPSERT_NODE',
      node: {
        ...process,
        data: {
          ...(process.data as ProcessNodeData),
          reworkPolicy: { sources: [{ reworkNodeId: 'material:output:1', inputRatioPercent: 15 }] },
        } as ProcessNodeData,
      },
    }]);
    expect(result.errors).toEqual([]);
    const updated = result.definition.nodes.find((node) => node.id === 'process:cook');
    expect((updated?.data as ProcessNodeData).reworkPolicy)
      .toEqual({ sources: [{ reworkNodeId: 'material:output:1', inputRatioPercent: 15 }] });
  });

  it('🔴 形状不对的 reworkPolicy 被明确拒绝，⛔ 不是静默丢字段', () => {
    // 交接 §12：AI 返回当前不支持的字段时，要明确列出 rejected/unsupported，
    // 不得静默丢字段后仍提示「已完成」。
    const definition = baseDefinition();
    const process = definition.nodes[0];
    const bad = [
      { sources: [] },                                              // 空数组：应写成 null
      { sources: [{ reworkNodeId: '', inputRatioPercent: 10 }] },    // 空 id
      { sources: [{ reworkNodeId: 'r', inputRatioPercent: 150 }] },  // 超范围
      { sources: [{ reworkNodeId: 'r' }] },                          // 缺比例
      { allowedSourceReworkNodeIds: ['r'], maxInputRatioPercent: 10 }, // #417 的旧形状
    ];
    for (const policy of bad) {
      const result = applyWorkflowPatches(definition, [{
        op: 'UPSERT_NODE',
        node: { ...process, data: { ...(process.data as ProcessNodeData), reworkPolicy: policy } as ProcessNodeData },
      }]);
      expect(result.errors.length, JSON.stringify(policy)).toBeGreaterThan(0);
      expect(result.summary, JSON.stringify(policy)).toHaveLength(0);
    }
  });

  it('阳性对照：同一条补丁去掉非法字段就能过 —— 证明红不是因为别的原因', () => {
    const definition = baseDefinition();
    const process = definition.nodes[0];
    const ok = applyWorkflowPatches(definition, [{
      op: 'UPSERT_NODE',
      node: { ...process, data: { ...(process.data as ProcessNodeData) } },
    }]);
    expect(ok.errors).toEqual([]);
  });

  it('⛔ 不良品那类标记不存在，AI 也造不出来', () => {
    // 业务上没有「判废/不入库」这一类（owner 2026-09-01），所以画布上不该出现它的标记。
    const result = applyWorkflowPatches(baseDefinition(), [{
      op: 'UPSERT_NODE',
      node: {
        id: 'material:output:2',
        kind: 'SEMI_FINISHED',
        position: { x: 0, y: 0 },
        data: { name: '判废', skuId: 'X', isDefective: true } as Record<string, unknown>,
      },
    }]);
    expect(result.errors.length).toBeGreaterThan(0);
  });
});
