import { describe, expect, it } from 'vitest';
import { deriveReworkMatrixContext } from '../workflowReworkMatrix';

function branch(id: string, name: string, allowed: string[] = []) {
  return {
    nodes: [
      {
        id: `grind-${id}`,
        data: {
          kind: 'PROCESS', workProcessId: `WP-GRIND-${id}`, processName: '破碎绞肉',
          inputUnit: 'kg', outputUnit: 'kg', ports: [], conversionRule: { mode: 'ACTUAL_WEIGHT' },
          reportingRequired: true,
          reworkPolicy: {
            targetProductTypeId: id,
            targetProductName: name,
            allowedSourceProductTypeIds: allowed,
            maxInputRatioPercent: 10,
          },
        },
      },
      { id: `mid-${id}`, data: { kind: 'SEMI_FINISHED', name: `${name}馅料`, skuId: `WIP-${id}` } },
      {
        id: `qc-${id}`,
        data: {
          kind: 'PROCESS', workProcessId: `WP-QC-${id}`, processName: '金检/X光',
          inputUnit: 'kg', outputUnit: 'kg', ports: [], conversionRule: { mode: 'ACTUAL_WEIGHT' },
          reportingRequired: true,
        },
      },
      { id: `fin-${id}`, data: { kind: 'FINISHED_GOOD', name, skuId: id, skuCode: `SKU-${id}` } },
      { id: `rw-${id}`, data: { kind: 'SEMI_FINISHED', name: `${name}回料`, skuId: `RW-${id}`, isRework: true } },
    ],
    edges: [
      { source: `grind-${id}`, target: `mid-${id}` },
      { source: `mid-${id}`, target: `qc-${id}` },
      { source: `qc-${id}`, target: `fin-${id}` },
      { source: `qc-${id}`, target: `rw-${id}` },
    ],
  };
}

describe('Workflow 回料来源矩阵', () => {
  it('从四条独立分支派生来源×目标矩阵，并保留每个目标自己的比例', () => {
    const definitions = [
      branch('AMERICAN', '美式汉堡饼', ['AMERICAN']),
      branch('SNACK', '小吃吧', ['AMERICAN', 'SNACK']),
      branch('COOKED', '熟肉饼', ['AMERICAN', 'SNACK', 'COOKED']),
      branch('BURGER', '汉堡肉饼', ['BURGER']),
    ];
    const context = deriveReworkMatrixContext(
      definitions.flatMap((item) => item.nodes),
      definitions.flatMap((item) => item.edges),
      'grind-AMERICAN',
    );

    expect(context.sources.map((item) => item.productTypeId).sort()).toEqual([
      'AMERICAN', 'BURGER', 'COOKED', 'SNACK',
    ]);
    expect(context.targets).toHaveLength(4);
    expect(context.targets.every((target) => target.processNodeId?.startsWith('grind-'))).toBe(true);
    expect(context.rules.find((rule) => rule.targetProductTypeId === 'AMERICAN')?.allowedSourceProductTypeIds)
      .toEqual(['AMERICAN']);
    expect(context.rules.find((rule) => rule.targetProductTypeId === 'SNACK')?.allowedSourceProductTypeIds)
      .toEqual(['AMERICAN', 'SNACK']);
    expect(context.rules.find((rule) => rule.targetProductTypeId === 'COOKED')?.allowedSourceProductTypeIds)
      .toEqual(['AMERICAN', 'SNACK', 'COOKED']);
    expect(context.rules.find((rule) => rule.targetProductTypeId === 'BURGER')?.allowedSourceProductTypeIds)
      .toEqual(['BURGER']);
    expect(context.sourceProductByReworkNodeId['rw-SNACK']).toBe('SNACK');
  });

  it('共用工序通向多个成品时明确标记 shared，不猜配置归属', () => {
    const nodes = [
      {
        id: 'shared-grind',
        data: {
          kind: 'PROCESS', workProcessId: 'WP-SHARED', processName: '破碎绞肉',
          inputUnit: 'kg', outputUnit: 'kg', ports: [], conversionRule: { mode: 'ACTUAL_WEIGHT' },
          reportingRequired: true,
        },
      },
      { id: 'a', data: { kind: 'FINISHED_GOOD', name: '产品A', skuId: 'A' } },
      { id: 'b', data: { kind: 'FINISHED_GOOD', name: '产品B', skuId: 'B' } },
    ];
    const context = deriveReworkMatrixContext(nodes, [
      { source: 'shared-grind', target: 'a' },
      { source: 'shared-grind', target: 'b' },
    ], 'shared-grind');

    expect(context.focusTargetProductTypeId).toBeNull();
    expect(context.targets.every((target) => target.sharedProcess)).toBe(true);
  });
});
