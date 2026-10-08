import { describe, expect, it } from 'vitest';
import { previewWorkflowAiDefinitions, previewWorkflowAiSpec, workflowAiStableJson } from '../workflowAiPreview';
import type { ProductProcessWorkflowDefinition } from '../types';

import { EMPTY_CANVAS, LARGE_SPEC, LARGE_PATHS } from './workflowAiPreview.fixture';

describe('workflow AI complete change projection', () => {
  it('pairs nodes by business identity and graph position, independent of storage order', () => {
    const before = {
      ...EMPTY_CANVAS,
      nodes: [
        { id: 'raw', kind: 'RAW_MATERIAL', data: { name: '原料', skuId: 'RM-1' } },
        { id: 'p1', kind: 'PROCESS', data: { processName: '切分', workProcessId: 'WP-1', reportingRequired: false, ports: [] } },
        { id: 'p2', kind: 'PROCESS', data: { processName: '切分', workProcessId: 'WP-2', reportingRequired: false, ports: [] } },
        { id: 'fin', kind: 'FINISHED_GOOD', data: { name: '成品', skuId: 'FG-1' } },
      ],
      edges: [{ source: 'raw', target: 'p1' }, { source: 'p1', target: 'p2' }, { source: 'p2', target: 'fin' }],
    } as unknown as ProductProcessWorkflowDefinition;
    const after = {
      ...before,
      // React Flow may persist a different array order and regenerate runtime ids.
      nodes: [
        { id: 'new-fin', kind: 'FINISHED_GOOD', data: { name: '成品', skuId: 'FG-1' } },
        { id: 'new-p2', kind: 'PROCESS', data: { processName: '切分', workProcessId: 'WP-2', reportingRequired: true, ports: [] } },
        { id: 'new-raw', kind: 'RAW_MATERIAL', data: { name: '原料', skuId: 'RM-1' } },
        { id: 'new-p1', kind: 'PROCESS', data: { processName: '切分', workProcessId: 'WP-1', reportingRequired: false, ports: [] } },
      ],
      edges: [{ source: 'new-p2', target: 'new-fin' }, { source: 'new-raw', target: 'new-p1' }, { source: 'new-p1', target: 'new-p2' }],
    } as unknown as ProductProcessWorkflowDefinition;
    const preview = previewWorkflowAiDefinitions(before, after);
    if (!preview.ok) throw new Error(preview.message);
    expect(preview.rows).toHaveLength(1);
    expect(preview.rows[0].text).toContain('免报 → 报工');
    expect(preview.rows[0].text).not.toMatch(/p1|p2|UUID/);
  });

  it('reports connection set additions and same-name packaging archive switches', () => {
    const before = {
      ...EMPTY_CANVAS,
      nodes: [
        { id: 'p', kind: 'PROCESS', data: { processName: '包装', workProcessId: 'WP-P', reportingRequired: false, ports: [] } },
        { id: 'f', kind: 'FINISHED_GOOD', data: { name: '成品', skuId: 'FG-1', packagingBindings: [{ materialName: '成品盒', materialTypeId: 'PK-1', standardQuantity: 1, unit: '盒' }] } },
      ], edges: [{ source: 'p', target: 'f' }],
    } as unknown as ProductProcessWorkflowDefinition;
    const after = {
      ...before,
      nodes: [{ ...before.nodes[0] }, { ...before.nodes[1], data: { ...before.nodes[1].data, packagingBindings: [{ materialName: '成品盒', materialTypeId: 'PK-2', standardQuantity: 1, unit: '盒' }] } }],
      edges: [{ source: 'p', target: 'f' }, { source: 'f', target: 'p' }],
    } as unknown as ProductProcessWorkflowDefinition;
    const preview = previewWorkflowAiDefinitions(before, after);
    if (!preview.ok) throw new Error(preview.message);
    expect(preview.rows.some(row => row.path.startsWith('canvas.edges.added[') && row.text.includes('新增连接'))).toBe(true);
    expect(preview.rows.some(row => row.text.includes('档案绑定已切换'))).toBe(true);
    expect(preview.rows.every(row => !row.text.includes('PK-1') && !row.text.includes('PK-2'))).toBe(true);
  });

  it('diffs the complete semantic before/after canvas, including compiler-only fields', () => {
    const before = {
      ...EMPTY_CANVAS,
      nodes: [{ id: 'process1', kind: 'PROCESS', data: { processName: '前处理', reportingRequired: false, ports: [] } },
        { id: 'finished', kind: 'FINISHED_GOOD', data: { name: '成品', packagingBindings: [{ materialName: '成品盒' }] } }],
      edges: [{ id: 'old-edge', source: 'process1', target: 'finished' }],
    } as unknown as ProductProcessWorkflowDefinition;
    const after = {
      ...before,
      nodes: [{ id: 'new-process', kind: 'PROCESS', data: { processName: '前处理', reportingRequired: true, ports: [] } },
        { id: 'new-finished', kind: 'FINISHED_GOOD', data: { name: '成品' } }],
      edges: [{ id: 'new-edge', source: 'new-process', target: 'new-finished' }],
    } as unknown as ProductProcessWorkflowDefinition;
    const preview = previewWorkflowAiDefinitions(before, after);
    if (!preview.ok) throw new Error(preview.message);
    expect(preview.rows.map(row => row.text).join('\n')).toContain('是否报工：免报 → 报工');
    expect(preview.rows.map(row => row.text).join('\n')).toContain('包材配置');
    expect(preview.rows.some(row => row.path.startsWith('canvas.edges'))).toBe(false);
  });

  it('projects compiler defaults and preserved terminal packaging loss into the confirmation diff', () => {
    const graph = {
      ...EMPTY_CANVAS,
      nodes: [
        { id: 'raw', kind: 'RAW_MATERIAL', data: { name: '原料', baseUnit: 'kg' } },
        { id: 'process', kind: 'PROCESS', data: {
          processName: '前处理', reportingRequired: false, ports: [
            { direction: 'INPUT', materialNodeId: 'raw', unit: 'kg' },
            { direction: 'OUTPUT', materialNodeId: 'finished', unit: 'kg', materialKind: 'FINISHED_GOOD' },
          ],
        } },
        { id: 'finished', kind: 'FINISHED_GOOD', data: {
          name: '成品', baseUnit: 'kg', packagingBindings: [{ materialName: '成品盒', standardQuantity: 1, unit: '盒' }],
        } },
      ],
      edges: [{ source: 'raw', target: 'process' }, { source: 'process', target: 'finished' }],
    } as unknown as ProductProcessWorkflowDefinition;
    const preview = previewWorkflowAiSpec(graph, {
      rawMaterials: ['原料'],
      steps: [{ process: '前处理', outputs: [{ kind: 'FINISHED_GOOD', name: '成品', unit: 'kg', byproduct: false }] }],
    });
    if (!preview.ok) throw new Error(preview.message);
    expect(preview.rows.map(row => row.path)).toEqual([
      'steps[0].outputs[0].packagingBindings',
      'steps[0].reportingRequired',
    ]);
    expect(preview.rows.map(row => row.text).join('\n')).toContain('是否报工：否 → 是');
    expect(preview.rows.map(row => row.text).join('\n')).toContain('包材配置');
  });

  it('lists every independent field including unknown keys, false, zero and null without mutating the graph', () => {
    const before = workflowAiStableJson(EMPTY_CANVAS);
    const preview = previewWorkflowAiSpec(EMPTY_CANVAS, LARGE_SPEC);
    expect(preview.ok).toBe(true);
    if (!preview.ok) throw new Error(preview.message);
    expect(preview.rows.map(row => row.path)).toEqual(LARGE_PATHS);
    expect(preview.rows).toHaveLength(29);
    expect(preview.rows.map(row => row.text).join('\n')).toContain('mystery · literal：未设置 → <原样>');
    expect(preview.rows.map(row => row.text).join('\n')).toContain('后续锅比例：未设置 → 0');
    expect(workflowAiStableJson(EMPTY_CANVAS)).toBe(before);
  });

  it('shows deletions, order changes and all output/input changes against topology rather than screen position', () => {
    const graph = {
      ...EMPTY_CANVAS,
      nodes: [
        { id: 'raw', kind: 'RAW_MATERIAL', data: { name: '肉' } },
        { id: 'a', kind: 'PROCESS', data: { processName: '切分', processCategory: '加工', ports: [], responsibleWorkerName: '李师傅' } },
        { id: 'b', kind: 'PROCESS', data: { processName: '装盒', processCategory: '包装', ports: [] } },
        { id: 'c', kind: 'PROCESS', data: { processName: '称重', ports: [] } },
      ], edges: [{ source: 'raw', target: 'a' }, { source: 'a', target: 'b' }, { source: 'b', target: 'c' }],
    } as unknown as ProductProcessWorkflowDefinition;
    const preview = previewWorkflowAiSpec(graph, { rawMaterials: ['肉'], steps: [
      { process: '装盒', processCategory: '包装', inputs: [], outputs: [] },
      { process: '切分', processCategory: '加工', responsibleWorkerName: '张师傅', inputs: [], outputs: [] },
    ] });
    if (!preview.ok) throw new Error(preview.message);
    expect(preview.rows.map(row => row.path)).toEqual([
      'steps[0].order', 'steps[1].order', 'steps[1].responsibleWorkerName', 'removed[2]', 'removed[2].fields.process',
      'removed[2].fields.outputs', 'removed[2].fields.inputs',
    ]);
    expect(preview.rows.map(row => row.text).join('\n')).toContain('负责人：李师傅 → 张师傅');
  });

  it.each([null, {}, { steps: [] }, { steps: [{ process: 12 }] }])('rejects malformed spec %j without executing', spec => {
    expect(previewWorkflowAiSpec(EMPTY_CANVAS, spec).ok).toBe(false);
  });
});
