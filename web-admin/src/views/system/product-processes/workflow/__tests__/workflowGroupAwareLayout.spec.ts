import { describe, expect, it } from 'vitest';
import { autoLayoutWorkflow } from '../workflowModel';
import type {
  ProcessNodeData,
  ProductProcessWorkflowDefinition,
  ProductProcessWorkflowNode,
} from '../types';

/**
 * §11：自动布局必须按 owner group 的**完整包围盒**排间距。
 *
 * 只按 owner 自身高度排，上方的辅料条会插进上一行 Cell、下方的特殊产出会压住下一行 ——
 * 交接把这条明确列成不许出现的回归。
 */

const PROCESS_WIDTH = 472;
const MATERIAL_WIDTH = 240;

function processNode(id: string, inputs: number, outputs: number): ProductProcessWorkflowNode {
  const ports = [
    ...Array.from({ length: inputs }, (_, index) => ({
      id: `in:${id}:${index}`, direction: 'INPUT' as const, unit: 'kg', ordinal: index,
    })),
    ...Array.from({ length: outputs }, (_, index) => ({
      id: `out:${id}:${index}`, direction: 'OUTPUT' as const, unit: 'kg', ordinal: index,
    })),
  ];
  return {
    id,
    kind: 'PROCESS',
    position: { x: 0, y: 0 },
    data: {
      workProcessId: `WP-${id}`,
      processName: id,
      inputUnit: 'kg',
      outputUnit: 'kg',
      ports,
      conversionRule: { mode: 'ACTUAL_WEIGHT' },
      reportingRequired: true,
    } as ProcessNodeData,
  };
}

function materialNode(id: string, kind: 'RAW_MATERIAL' | 'SEMI_FINISHED' | 'FINISHED_GOOD'): ProductProcessWorkflowNode {
  return { id, kind, position: { x: 0, y: 0 }, data: { name: id, skuId: `SKU-${id}` } };
}

/** 建一张有 routeCount 条并行线路的图（每条 原料 → 工序 → 成品）。 */
function buildRoutes(routeCount: number): ProductProcessWorkflowDefinition {
  const nodes: ProductProcessWorkflowNode[] = [];
  const edges = [];
  for (let index = 0; index < routeCount; index += 1) {
    nodes.push(materialNode(`raw${index}`, 'RAW_MATERIAL'));
    nodes.push(processNode(`proc${index}`, 1, 1));
    nodes.push(materialNode(`fin${index}`, 'FINISHED_GOOD'));
    edges.push({
      id: `e1-${index}`, source: `raw${index}`, sourceHandle: 'output',
      target: `proc${index}`, targetHandle: `in:proc${index}:0`,
    });
    edges.push({
      id: `e2-${index}`, source: `proc${index}`, sourceHandle: `out:proc${index}:0`,
      target: `fin${index}`, targetHandle: 'input',
    });
  }
  return {
    schemaVersion: 1, status: 'DRAFT', version: 1, nodes, edges,
    viewport: { x: 0, y: 0, zoom: 1 },
  };
}

interface Rect { id: string; x1: number; y1: number; x2: number; y2: number }

/** 一个节点占的矩形 = owner 自身 + 上下融合附属。 */
function groupRects(
  definition: ProductProcessWorkflowDefinition,
  extent: (id: string) => { above: number; below: number },
): Rect[] {
  return definition.nodes.map((node) => {
    const { above, below } = extent(node.id);
    const width = node.kind === 'PROCESS' ? PROCESS_WIDTH : MATERIAL_WIDTH;
    const height = estimatedHeight(node);
    return {
      id: node.id,
      x1: node.position.x,
      y1: node.position.y - above,
      x2: node.position.x + width,
      y2: node.position.y + height + below,
    };
  });
}

/** 与 workflowModel 内部的 estimatedNodeHeight 同口径（它没有导出，这里只用于验收判据）。 */
function estimatedHeight(node: ProductProcessWorkflowNode): number {
  if (node.kind === 'RAW_MATERIAL') return 240;
  if (node.kind !== 'PROCESS') return 168;
  const data = node.data as ProcessNodeData;
  const inputCount = data.ports.filter((port) => port.direction === 'INPUT').length;
  const outputCount = data.ports.filter((port) => port.direction === 'OUTPUT').length;
  const relationRows = (inputCount > 1 ? 1 : 0) + (outputCount > 1 ? 1 : 0);
  return 300 + (inputCount + outputCount) * 46 + relationRows * 54;
}

function overlappingPairs(rects: Rect[]): Array<[string, string]> {
  const hits: Array<[string, string]> = [];
  for (let i = 0; i < rects.length; i += 1) {
    for (let j = i + 1; j < rects.length; j += 1) {
      const a = rects[i];
      const b = rects[j];
      const overlapsX = a.x1 < b.x2 && b.x1 < a.x2;
      const overlapsY = a.y1 < b.y2 && b.y1 < a.y2;
      if (overlapsX && overlapsY) hits.push([a.id, b.id]);
    }
  }
  return hits;
}

const HEAVY_ATTACHMENTS = (id: string): { above: number; below: number } => (
  id.startsWith('proc') ? { above: 220, below: 180 } : { above: 0, below: 0 }
);

describe('§11 group-aware 自动布局：融合组不许重叠', () => {
  it.each([1, 4, 7])('%i 条线路时，所有 owner group 的包围盒互不重叠', (routeCount) => {
    const laidOut = autoLayoutWorkflow(buildRoutes(routeCount), { fusedExtent: HEAVY_ATTACHMENTS });
    expect(overlappingPairs(groupRects(laidOut, HEAVY_ATTACHMENTS))).toEqual([]);
  });

  it('🔴 阴性对照：不告诉布局附属占多高，同一张图就会重叠 —— 证明这条判据不是恒真式', () => {
    // 形态 B′：一条「不该发生」的断言必须配一条能让它发生的对照。
    // 这里用旧行为（fusedExtent 缺省 = 只按 owner 自身高度排）重跑同一张图。
    const naive = autoLayoutWorkflow(buildRoutes(4));
    expect(overlappingPairs(groupRects(naive, HEAVY_ATTACHMENTS)).length).toBeGreaterThan(0);
  });

  it('纵向间距按每条线路的实际组合高度动态算，不是一个写死的常数', () => {
    const light = autoLayoutWorkflow(buildRoutes(4), {
      fusedExtent: () => ({ above: 0, below: 0 }),
    });
    const heavy = autoLayoutWorkflow(buildRoutes(4), { fusedExtent: HEAVY_ATTACHMENTS });
    const gapOf = (definition: ProductProcessWorkflowDefinition): number => {
      const ys = definition.nodes.filter((node) => node.id.startsWith('proc'))
        .map((node) => node.position.y).sort((a, b) => a - b);
      return ys[1] - ys[0];
    };
    expect(gapOf(heavy)).toBeGreaterThan(gapOf(light));
  });

  it('owner 自己的坐标为上方附属让出空间：group 顶边不越过画布原点', () => {
    // 我想知道的 X：上方附属会不会越过本行的顶边跑出去。
    // 所以量的 Y 必须是 **group 顶边**（position.y - above），不是「谁比谁靠下」。
    const CANVAS_ORIGIN = 32;
    const laidOut = autoLayoutWorkflow(buildRoutes(1), { fusedExtent: HEAVY_ATTACHMENTS });
    const proc = laidOut.nodes.find((node) => node.id === 'proc0');
    if (!proc) throw new Error('Expected process node');
    expect(proc.position.y - HEAVY_ATTACHMENTS(proc.id).above).toBeGreaterThanOrEqual(CANVAS_ORIGIN);

    // 🔴 阴性对照：旧行为（不告诉布局附属占多高）会让顶边跑到原点之上。
    const naive = autoLayoutWorkflow(buildRoutes(1));
    const naiveProc = naive.nodes.find((node) => node.id === 'proc0');
    expect(naiveProc!.position.y - HEAVY_ATTACHMENTS('proc0').above).toBeLessThan(CANVAS_ORIGIN);
  });

  it('不靠无限放大间距掩盖碰撞：横向层距与旧行为一致', () => {
    const heavy = autoLayoutWorkflow(buildRoutes(2), { fusedExtent: HEAVY_ATTACHMENTS });
    const light = autoLayoutWorkflow(buildRoutes(2));
    const xs = (definition: ProductProcessWorkflowDefinition): number[] => [
      ...new Set(definition.nodes.map((node) => node.position.x)),
    ].sort((a, b) => a - b);
    expect(xs(heavy)).toEqual(xs(light));
  });
});
