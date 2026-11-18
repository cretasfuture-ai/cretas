import { describe, expect, it } from 'vitest';
import type { ProductProcessWorkflowDefinition } from '../types';
import { fillOmittedSpecFields, preserveExistingWorkflowValues } from '../workflowAiPreserve';
import { previewWorkflowAiDefinitions } from '../workflowAiPreview';
import { isWorkflowCheckOnlyRequest } from '../workflowAiIntent';
import { EMPTY_CANVAS } from './workflowAiPreview.fixture';

/**
 * BP-38 缺陷形态: 「【演示L3】卤味三拼」卤制工序已有投入单位 kg、辅料八角 2 g/kg、
 * 端口全部绑定物料节点; 模型只交回一份残缺规格, 编译器从零重建后这些全成了空值。
 */
function existingCanvas(): ProductProcessWorkflowDefinition {
  return {
    ...EMPTY_CANVAS,
    nodes: [
      { id: 'raw-pork', kind: 'RAW_MATERIAL', position: { x: 0, y: 0 },
        data: { name: '猪肉', skuId: 'RM-PORK', skuCode: 'RM-PORK', bound: true, baseUnit: 'kg' } },
      { id: 'raw-brine', kind: 'RAW_MATERIAL', position: { x: 0, y: 100 },
        data: { name: '卤汁', skuId: 'RM-BRINE', skuCode: 'RM-BRINE', bound: true, baseUnit: 'kg' } },
      { id: 'proc-lu', kind: 'PROCESS', position: { x: 200, y: 0 }, data: {
        workProcessId: 'WP-LU', processName: '卤制', processCategory: '熟制',
        inputUnit: 'kg', outputUnit: 'kg', reportingRequired: true,
        conversionRule: { mode: 'ACTUAL_WEIGHT', expression: 'input * 0.85' },
        materialBindings: [{ materialTypeId: 'RM-STAR-ANISE', materialName: '八角', dosagePerKgG: 2, unit: 'g' }],
        ports: [
          { id: 'in-main', direction: 'INPUT', materialNodeId: 'raw-pork', materialName: '猪肉', skuId: 'RM-PORK', unit: 'kg', ordinal: 0 },
          { id: 'in-brine', direction: 'INPUT', materialNodeId: 'raw-brine', materialName: '卤汁', skuId: 'RM-BRINE', unit: 'kg', ordinal: 1 },
          { id: 'out-main', direction: 'OUTPUT', materialNodeId: 'semi-lu', materialName: '卤肉', skuId: 'SKU-LU', unit: 'kg', ordinal: 0 },
        ],
        portGroups: [{ id: 'g1', direction: 'INPUT', label: '主料', mode: 'ALL_REQUIRED', minSelections: 2, maxSelections: 2, portIds: ['in-main', 'in-brine'] }],
      } },
      { id: 'semi-lu', kind: 'SEMI_FINISHED', position: { x: 400, y: 0 },
        data: { name: '卤肉', skuId: 'SKU-LU', skuCode: 'SKU-LU', bound: true, baseUnit: 'kg' } },
    ],
    edges: [
      { id: 'e1', source: 'raw-pork', sourceHandle: 'output', target: 'proc-lu', targetHandle: 'in-main' },
      { id: 'e2', source: 'raw-brine', sourceHandle: 'output', target: 'proc-lu', targetHandle: 'in-brine' },
      { id: 'e3', source: 'proc-lu', sourceHandle: 'out-main', target: 'semi-lu', targetHandle: 'input' },
    ],
  } as unknown as ProductProcessWorkflowDefinition;
}

/** 编译器从残缺规格重建出的样子: id 全换, 单位/辅料/档案绑定/端口组都是空的。 */
function compiledFromPartialSpec(): ProductProcessWorkflowDefinition {
  return {
    ...EMPTY_CANVAS,
    nodes: [
      { id: 'gen-raw-1', kind: 'RAW_MATERIAL', position: { x: 0, y: 0 },
        data: { name: '猪肉', skuId: '', skuCode: '待绑定原料 SKU', bound: false, baseUnit: '' } },
      { id: 'gen-raw-2', kind: 'RAW_MATERIAL', position: { x: 0, y: 100 },
        data: { name: '卤汁', skuId: '', skuCode: '待绑定原料 SKU', bound: false, baseUnit: '' } },
      { id: 'gen-proc', kind: 'PROCESS', position: { x: 200, y: 0 }, data: {
        workProcessId: 'WP-LU', processName: '卤制', processCategory: '熟制',
        inputUnit: '', outputUnit: '', reportingRequired: true,
        conversionRule: { mode: 'ACTUAL_WEIGHT' },
        ports: [
          { id: 'gen-in-1', direction: 'INPUT', materialNodeId: 'gen-raw-1', unit: '', ordinal: 0 },
          { id: 'gen-in-2', direction: 'INPUT', materialNodeId: 'gen-raw-2', unit: '', ordinal: 1 },
          { id: 'gen-out-1', direction: 'OUTPUT', materialNodeId: 'gen-semi', unit: '', ordinal: 0 },
        ],
      } },
      { id: 'gen-semi', kind: 'SEMI_FINISHED', position: { x: 400, y: 0 },
        data: { name: '卤肉', skuId: '', skuCode: '待选择或现场创建 SKU', bound: false, baseUnit: '' } },
    ],
    edges: [
      { id: 'g1', source: 'gen-raw-1', sourceHandle: 'output', target: 'gen-proc', targetHandle: 'gen-in-1' },
      { id: 'g2', source: 'gen-raw-2', sourceHandle: 'output', target: 'gen-proc', targetHandle: 'gen-in-2' },
      { id: 'g3', source: 'gen-proc', sourceHandle: 'gen-out-1', target: 'gen-semi', targetHandle: 'input' },
    ],
  } as unknown as ProductProcessWorkflowDefinition;
}

const nodeData = (definition: ProductProcessWorkflowDefinition, id: string) => (
  definition.nodes.find(node => node.id === id)!.data as Record<string, unknown>
);

describe('BP-38 AI 补丁不清空已有配置', () => {
  it('阳性对照: 不做保留时, 预览确实会出现把已有值清空的标红行', () => {
    const preview = previewWorkflowAiDefinitions(existingCanvas(), compiledFromPartialSpec());
    if (!preview.ok) throw new Error(preview.message);
    const clears = preview.rows.filter(row => row.clears);
    expect(clears.length).toBeGreaterThan(5);
    expect(clears.some(row => row.path.endsWith('.inputUnit'))).toBe(true);
    expect(clears.some(row => row.path.includes('.materialBindings'))).toBe(true);
  });

  it('模型没给的字段保留原值: 投入单位、辅料八角、端口绑定、物料档案、端口组', () => {
    const before = existingCanvas();
    const after = compiledFromPartialSpec();
    const { definition, preserved } = preserveExistingWorkflowValues(before, after);
    const proc = nodeData(definition, 'gen-proc');
    expect(proc.inputUnit).toBe('kg');
    expect(proc.outputUnit).toBe('kg');
    expect(proc.materialBindings).toEqual([{ materialTypeId: 'RM-STAR-ANISE', materialName: '八角', dosagePerKgG: 2, unit: 'g' }]);
    expect((proc.conversionRule as Record<string, unknown>).expression).toBe('input * 0.85');
    const ports = proc.ports as Array<Record<string, unknown>>;
    expect(ports.map(port => [port.id, port.materialNodeId, port.skuId, port.unit])).toEqual([
      ['gen-in-1', 'gen-raw-1', 'RM-PORK', 'kg'],
      ['gen-in-2', 'gen-raw-2', 'RM-BRINE', 'kg'],
      ['gen-out-1', 'gen-semi', 'SKU-LU', 'kg'],
    ]);
    // 端口组引用的是新端口 id, 不留悬空引用
    expect((proc.portGroups as Array<Record<string, unknown>>)[0].portIds).toEqual(['gen-in-1', 'gen-in-2']);
    expect(nodeData(definition, 'gen-raw-1')).toMatchObject({ skuId: 'RM-PORK', bound: true, baseUnit: 'kg', skuCode: 'RM-PORK' });
    expect(nodeData(definition, 'gen-semi')).toMatchObject({ skuId: 'SKU-LU', bound: true, baseUnit: 'kg' });
    expect(preserved).toBeGreaterThan(0);
    // 输入不被改写
    expect(nodeData(after, 'gen-proc').inputUnit).toBe('');

    const preview = previewWorkflowAiDefinitions(before, definition);
    if (!preview.ok) throw new Error(preview.message);
    expect(preview.rows.filter(row => row.clears)).toEqual([]);
  });

  it('模型给出的非空新值不被旧值覆盖', () => {
    const after = compiledFromPartialSpec();
    (nodeData(after, 'gen-proc') as Record<string, unknown>).inputUnit = 'g';
    (nodeData(after, 'gen-proc') as Record<string, unknown>).materialBindings = [
      { materialTypeId: 'RM-CINNAMON', materialName: '桂皮', dosagePerKgG: 1 },
    ];
    const { definition } = preserveExistingWorkflowValues(existingCanvas(), after);
    expect(nodeData(definition, 'gen-proc').inputUnit).toBe('g');
    expect(nodeData(definition, 'gen-proc').materialBindings).toEqual([
      { materialTypeId: 'RM-CINNAMON', materialName: '桂皮', dosagePerKgG: 1 },
    ]);
  });

  it('结构性删除补不回来, 但会标红要求逐项确认', () => {
    const after = compiledFromPartialSpec();
    after.nodes = after.nodes.filter(node => node.id !== 'gen-raw-2');
    after.edges = after.edges.filter(edge => edge.source !== 'gen-raw-2');
    const proc = nodeData(after, 'gen-proc');
    proc.ports = (proc.ports as Array<Record<string, unknown>>).filter(port => port.id !== 'gen-in-2');
    const { definition } = preserveExistingWorkflowValues(existingCanvas(), after);
    const preview = previewWorkflowAiDefinitions(existingCanvas(), definition);
    if (!preview.ok) throw new Error(preview.message);
    const clears = preview.rows.filter(row => row.clears);
    expect(clears.some(row => row.path.startsWith('canvas.edges.removed'))).toBe(true);
    expect(clears.some(row => row.text.includes('卤汁'))).toBe(true);
  });

  it('规格层: 模型保留了工序却没写额外投入/产出/入口原料时按当前画布补; 显式 [] 不补', () => {
    const before = existingCanvas();
    const filled = fillOmittedSpecFields(before, { steps: [{ process: '卤制' }] }) as Record<string, unknown>;
    const step = (filled.steps as Array<Record<string, unknown>>)[0];
    expect(step.inputs).toEqual(['卤汁']);
    expect((step.outputs as Array<Record<string, unknown>>)[0]).toMatchObject({ name: '卤肉', unit: 'kg', kind: 'SEMI_FINISHED' });
    expect(filled.rawMaterials).toEqual(['猪肉', '卤汁']);

    const unitless = fillOmittedSpecFields(before, { rawMaterials: ['猪肉'], steps: [{ process: '卤制', inputs: [], outputs: [{ name: '卤肉' }] }] }) as Record<string, unknown>;
    const explicit = (unitless.steps as Array<Record<string, unknown>>)[0];
    expect(explicit.inputs).toEqual([]);
    expect((explicit.outputs as Array<Record<string, unknown>>)[0]).toMatchObject({ name: '卤肉', unit: 'kg' });
  });
});

describe('BP-38 检查类问题判定', () => {
  it.each([
    '检查当前 Workflow 的 SKU 上下游承接',
    '检查投入与产出单位、数量换算是否完整',
    '检查分流、合流和同 SKU 多投入是否合理',
    '帮我核对一下卤制的辅料用量',
  ])('「%s」是只读检查', text => {
    expect(isWorkflowCheckOnlyRequest(text)).toBe(true);
  });

  it.each([
    '根据我的描述生成工序调整草稿',
    '检查并补齐投入单位',
    '把卤制的投入单位改成 g',
    '新增一道切片工序',
  ])('「%s」是修改请求', text => {
    expect(isWorkflowCheckOnlyRequest(text)).toBe(false);
  });
});
