import type { FullTraceResponse, LineageNode } from '@/api/traceability';
import { qualityStatusLabel, shipmentStatusLabel, nodeTypeLabel, UNNAMED_NODE_TEXT } from './traceDisplay';

/**
 * 溯源 DAG 的纯函数组装 —— 只用现有 `getFullTrace` 返回的数据, 不碰后端契约。
 *
 * 边的来源 (⛔ 不编造后端没给的连线):
 *   · 原料(depth=1) → 工序 → … → 本批次: 一跳上游本来就是本批次的直接原料, 有工序时串在工序链上。
 *   · 本批次 → 质检 / 出货 / 下游批次(depth=1), 出货 → 客户 (按客户名合并)。
 * 缺口: 后端血缘只给「第几跳」分组, 没给 depth≥2 节点连到哪个上一层节点, 也没给每道工序各自消耗哪些原料。
 *   · depth≥2 的上游/下游节点画在图上, 但 linked=false, 不连线, 节点上标「具体连线未记录」。
 *   · 工序统一串成一条线, 原料全部接第一道工序。
 */

export type TraceNodeKind =
  | 'upstream' // 原料批次 / 更上游批次
  | 'stage'
  | 'root'
  | 'quality'
  | 'downstream'
  | 'shipment'
  | 'customer';

export type TraceTone = 'success' | 'warning' | 'danger' | 'neutral';

export interface TraceGraphNode {
  id: string;
  kind: TraceNodeKind;
  /** 第几列 (从 0 起, 左 → 右)。 */
  column: number;
  title: string;
  /** 「标签：值」行, 缺数据显示「未记录」。 */
  lines: Array<{ label: string; value: string }>;
  statusText: string;
  tone: TraceTone;
  /** false = 后端没给这个节点连到谁, 图上不连线。 */
  linked: boolean;
  /** 点击后可以跳转 / 打开的目标, 页面据此决定动作。 */
  target?:
    | { type: 'individual'; id: string }
    | { type: 'batch-evidence' }
    | { type: 'quality' }
    | { type: 'trace-batch'; batchNumber: string };
}

export interface TraceGraphEdge {
  id: string;
  source: string;
  target: string;
}

export interface TraceGraph {
  nodes: TraceGraphNode[];
  edges: TraceGraphEdge[];
  /** 列标题, 下标 = column。 */
  columns: string[];
}

export const MISSING = '未记录';

function qty(quantity?: number | null, unit?: string | null): string {
  if (quantity == null) return MISSING;
  return `${quantity}${unit ? ` ${unit}` : ''}`;
}
function text(value?: string | null): string {
  return value && value.trim() ? value : MISSING;
}

function lineageNodeKey(node: LineageNode, depth: number, index: number): string {
  return `${depth}:${node.nodeType}:${node.nodeId || node.batchNumber || `idx${index}`}`;
}

function lineageTone(node: LineageNode): TraceTone {
  return node.resolved === false ? 'danger' : 'neutral';
}

export function buildTraceGraph(trace: FullTraceResponse): TraceGraph {
  const nodes: TraceGraphNode[] = [];
  const edges: TraceGraphEdge[] = [];
  const addEdge = (source: string, target: string) => {
    edges.push({ id: `${source}->${target}`, source, target });
  };

  const ancestors = [...(trace.lineage?.ancestors ?? [])].sort((a, b) => b.depth - a.depth);
  const descendants = [...(trace.lineage?.descendants ?? [])].sort((a, b) => a.depth - b.depth);
  const stages = [...(trace.processingStages ?? [])].sort((a, b) => (a.stageOrder ?? 0) - (b.stageOrder ?? 0));
  const inspections = trace.qualityInspections ?? [];
  const shipments = trace.shipments ?? [];

  const columns: string[] = [];
  const newColumn = (label: string) => {
    columns.push(label);
    return columns.length - 1;
  };

  // —— 上游 (远 → 近) ——
  const upstreamIds: string[] = [];
  let hopOneIds: string[] = [];
  for (const group of ancestors) {
    const col = newColumn(group.depth === 1 ? '原料批次' : `上游 ${group.depth} 跳`);
    group.nodes.forEach((node, i) => {
      const id = `up:${lineageNodeKey(node, group.depth, i)}`;
      const unresolved = node.resolved === false;
      const linked = group.depth === 1;
      const lines = [
        { label: '名称', value: unresolved ? MISSING : text(node.name) },
        { label: '数量', value: qty(node.quantity, node.unit) },
      ];
      if (node.supplierName !== undefined) lines.push({ label: '供应商', value: text(node.supplierName) });
      nodes.push({
        id,
        kind: 'upstream',
        column: col,
        title: node.batchNumber || UNNAMED_NODE_TEXT,
        lines,
        statusText: unresolved ? '批次已查不到' : linked ? nodeTypeLabel(node.nodeType) : `${nodeTypeLabel(node.nodeType)} · 具体连线未记录`,
        tone: lineageTone(node),
        linked,
        target: node.sourceIndividual?.id ? { type: 'individual', id: node.sourceIndividual.id } : undefined,
      });
      upstreamIds.push(id);
      if (linked) hopOneIds.push(id);
    });
  }
  // 没有多跳血缘时退回一跳 materials 列表。
  if (ancestors.length === 0 && trace.materials.length > 0) {
    const col = newColumn('原料批次');
    trace.materials.forEach((m, i) => {
      const id = `up:mat:${m.batchNumber || i}`;
      nodes.push({
        id,
        kind: 'upstream',
        column: col,
        title: m.batchNumber || UNNAMED_NODE_TEXT,
        lines: [
          { label: '名称', value: text(m.materialName) },
          { label: '数量', value: qty(m.quantity, m.unit) },
          { label: '供应商', value: text(m.supplierName) },
        ],
        statusText: '原料批次',
        tone: 'neutral',
        linked: true,
      });
      hopOneIds.push(id);
    });
  }

  // —— 工序 ——
  let prevIds = hopOneIds;
  stages.forEach((stage, i) => {
    const col = newColumn(i === 0 ? '工序' : '');
    const id = `stage:${i}`;
    nodes.push({
      id,
      kind: 'stage',
      column: col,
      title: `第${stage.stageOrder ?? i + 1}道 · ${stage.stageName || stage.stageType || MISSING}`,
      lines: [
        { label: '操作员', value: text(stage.operatorName) },
        {
          label: '投入→产出',
          value:
            stage.inputWeight != null && stage.outputWeight != null
              ? `${stage.inputWeight}${stage.inputUnit || ''} → ${stage.outputWeight}${stage.outputUnit || ''}`
              : MISSING,
        },
      ],
      statusText: '加工',
      tone: 'neutral',
      linked: true,
    });
    for (const p of prevIds) addEdge(p, id);
    prevIds = [id];
  });

  // —— 本批次 ——
  const rootCol = newColumn('本批次');
  const p = trace.production;
  const rootId = 'root';
  nodes.push({
    id: rootId,
    kind: 'root',
    column: rootCol,
    title: p.batchNumber,
    lines: [
      { label: '产品', value: text(p.productName) },
      { label: '数量', value: qty(p.quantity, p.unit) },
    ],
    statusText: qualityStatusLabel(p.qualityStatus) || MISSING,
    tone: toneOfQuality(p.qualityStatus),
    linked: true,
    target: { type: 'batch-evidence' },
  });
  for (const pid of prevIds) addEdge(pid, rootId);

  // —— 质检 + 下游批次 (同一列) ——
  const hasPost = inspections.length > 0 || descendants.length > 0;
  if (hasPost) {
    const col = newColumn(inspections.length > 0 ? '质检' : '下游批次');
    inspections.forEach((q, i) => {
      const id = `qc:${q.inspectionId || i}`;
      nodes.push({
        id,
        kind: 'quality',
        column: col,
        title: `质检 #${q.inspectionId ? q.inspectionId.slice(-8) : MISSING}`,
        lines: [
          { label: '检验员', value: text(q.inspectorName) },
          { label: '抽检合格率', value: q.passRate != null ? `${q.passRate}%` : MISSING },
        ],
        statusText: qualityStatusLabel(q.result) || '待定',
        tone: toneOfQuality(q.result),
        linked: true,
        target: { type: 'quality' },
      });
      addEdge(rootId, id);
    });
    descendants.forEach((group) => {
      const linked = group.depth === 1;
      group.nodes.forEach((node, i) => {
        if (node.nodeType === 'SHIPMENT_RECORD') return; // 出货以 shipments 为准, 不重复画
        const id = `down:${lineageNodeKey(node, group.depth, i)}`;
        nodes.push({
          id,
          kind: 'downstream',
          column: col,
          title: node.batchNumber || UNNAMED_NODE_TEXT,
          lines: [
            { label: '名称', value: node.resolved === false ? MISSING : text(node.name) },
            { label: '数量', value: qty(node.quantity, node.unit) },
          ],
          statusText: linked ? nodeTypeLabel(node.nodeType) : `${nodeTypeLabel(node.nodeType)} · 具体连线未记录`,
          tone: lineageTone(node),
          linked,
          target: node.batchNumber ? { type: 'trace-batch', batchNumber: node.batchNumber } : undefined,
        });
        if (linked) addEdge(rootId, id);
      });
    });
  }

  // —— 出货 → 客户 ——
  if (shipments.length > 0) {
    const shipCol = newColumn('出货');
    const custCol = newColumn('客户');
    const customerIds = new Map<string, string>();
    shipments.forEach((s, i) => {
      const id = `ship:${s.shipmentNumber || i}`;
      nodes.push({
        id,
        kind: 'shipment',
        column: shipCol,
        title: s.shipmentNumber || UNNAMED_NODE_TEXT,
        lines: [
          { label: '数量', value: qty(s.quantity, s.unit) },
          { label: '物流单号', value: text(s.trackingNumber) },
        ],
        statusText: shipmentStatusLabel(s.status),
        tone: s.status?.toLowerCase() === 'returned' ? 'danger' : s.status?.toLowerCase() === 'pending' ? 'warning' : 'neutral',
        linked: true,
      });
      addEdge(rootId, id);
      const cname = s.customerName && s.customerName.trim() ? s.customerName.trim() : '';
      const ckey = cname || `__missing__`;
      let cid = customerIds.get(ckey);
      if (!cid) {
        cid = `cust:${ckey}`;
        customerIds.set(ckey, cid);
        nodes.push({
          id: cid,
          kind: 'customer',
          column: custCol,
          title: cname || MISSING,
          lines: [{ label: '客户', value: cname || MISSING }],
          statusText: '客户',
          tone: 'neutral',
          linked: true,
        });
      }
      addEdge(id, cid);
    });
  }

  return { nodes, edges, columns };
}

function toneOfQuality(status?: string | null): TraceTone {
  switch (status?.toUpperCase()) {
    case 'PASSED':
    case 'PASS':
    case 'QUALIFIED':
    case 'REWORK_COMPLETED':
      return 'success';
    case 'FAILED':
    case 'FAIL':
    case 'SCRAPPED':
      return 'danger';
    case 'PENDING':
    case 'PENDING_INSPECTION':
    case 'INSPECTING':
    case 'NEEDS_REVIEW':
    case 'CONDITIONAL':
    case 'PARTIAL_PASS':
    case 'REWORK_REQUIRED':
    case 'REWORKING':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 选中节点后的整条链: 沿边向上游 + 向下游各自一直走到头 (正查 + 反查同一次算出)。 */
export function chainOf(graph: TraceGraph, nodeId: string): { nodes: Set<string>; edges: Set<string> } {
  const nodesSet = new Set<string>([nodeId]);
  const edgesSet = new Set<string>();
  const walk = (dir: 'up' | 'down') => {
    const queue = [nodeId];
    const seen = new Set(queue);
    while (queue.length) {
      const cur = queue.shift()!;
      for (const e of graph.edges) {
        const from = dir === 'down' ? e.source : e.target;
        const to = dir === 'down' ? e.target : e.source;
        if (from !== cur) continue;
        edgesSet.add(e.id);
        nodesSet.add(to);
        if (!seen.has(to)) {
          seen.add(to);
          queue.push(to);
        }
      }
    }
  };
  walk('up');
  walk('down');
  return { nodes: nodesSet, edges: edgesSet };
}
