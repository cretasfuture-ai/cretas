import type { MaterialNodeData, ProcessNodeData, WorkflowReworkPolicy } from './types';

export const REWORK_REFERENCE_COLORS = [
  '#1b65a8', '#138a72', '#b56a16', '#7b61a8', '#b23a55', '#397a4b',
] as const;

export interface ReworkFlowNodeLike {
  id: string;
  data?: Record<string, unknown>;
}

export interface ReworkFlowEdgeLike {
  source: string;
  target: string;
}

export interface ReworkProductOption {
  productTypeId: string;
  productName: string;
  productCode: string;
  color: string;
}

export interface ReworkTargetOption extends ReworkProductOption {
  processNodeId: string | null;
  processName: string | null;
  sharedProcess: boolean;
}

export interface ReworkMatrixRule extends WorkflowReworkPolicy {
  processNodeId: string;
}

export interface ReworkMatrixContext {
  sources: ReworkProductOption[];
  targets: ReworkTargetOption[];
  rules: ReworkMatrixRule[];
  focusTargetProductTypeId: string | null;
  sourceProductByReworkNodeId: Record<string, string>;
}

function nodeKind(node: ReworkFlowNodeLike): string {
  return String(node.data?.kind ?? '');
}

function materialData(node: ReworkFlowNodeLike): MaterialNodeData {
  return (node.data ?? {}) as MaterialNodeData;
}

function processData(node: ReworkFlowNodeLike): ProcessNodeData {
  return (node.data ?? {}) as ProcessNodeData;
}

function downstreamFinishedIds(
  startNodeId: string,
  nodesById: Map<string, ReworkFlowNodeLike>,
  outgoing: Map<string, string[]>,
): string[] {
  const queue = [...(outgoing.get(startNodeId) ?? [])];
  const visited = new Set<string>([startNodeId]);
  const result: string[] = [];
  while (queue.length > 0) {
    const next = queue.shift()!;
    if (visited.has(next)) continue;
    visited.add(next);
    const node = nodesById.get(next);
    if (!node) continue;
    if (nodeKind(node) === 'FINISHED_GOOD') {
      result.push(next);
      continue;
    }
    queue.push(...(outgoing.get(next) ?? []));
  }
  return [...new Set(result)];
}

function productFromFinishedNode(node: ReworkFlowNodeLike, color: string): ReworkProductOption | null {
  const data = materialData(node);
  const productTypeId = String(data.skuId ?? '').trim();
  if (!productTypeId) return null;
  return {
    productTypeId,
    productName: String(data.name || data.skuCode || productTypeId),
    productCode: String(data.skuCode || productTypeId),
    color,
  };
}

/**
 * 从真实画布拓扑派生矩阵上下文：回料 Cell 的来源身份取同一产出工序的终端成品，
 * 目标身份取点击工序的唯一终端成品。若一道工序仍通向多个成品，明确标成 shared，
 * 不猜它属于哪一列。
 */
export function deriveReworkMatrixContext(
  nodes: ReworkFlowNodeLike[],
  edges: ReworkFlowEdgeLike[],
  focusProcessNodeId: string,
): ReworkMatrixContext {
  const nodesById = new Map(nodes.map((node) => [node.id, node]));
  const outgoing = new Map<string, string[]>();
  const incoming = new Map<string, string[]>();
  edges.forEach((edge) => {
    outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge.target]);
    incoming.set(edge.target, [...(incoming.get(edge.target) ?? []), edge.source]);
  });

  const finishedNodes = nodes.filter((node) => nodeKind(node) === 'FINISHED_GOOD');
  const sortedFinished = [...finishedNodes].sort((a, b) => {
    const left = String(materialData(a).name || materialData(a).skuId || a.id);
    const right = String(materialData(b).name || materialData(b).skuId || b.id);
    return left.localeCompare(right, 'zh-CN');
  });
  const productsById = new Map<string, ReworkProductOption>();
  sortedFinished.forEach((node, index) => {
    const product = productFromFinishedNode(
      node,
      REWORK_REFERENCE_COLORS[index % REWORK_REFERENCE_COLORS.length],
    );
    if (product && !productsById.has(product.productTypeId)) productsById.set(product.productTypeId, product);
  });

  const focusNode = nodesById.get(focusProcessNodeId);
  const focusName = focusNode && nodeKind(focusNode) === 'PROCESS'
    ? String(processData(focusNode).processName || '')
    : '';
  const focusFinishedIds = focusNode
    ? downstreamFinishedIds(focusProcessNodeId, nodesById, outgoing)
    : [];
  const focusTargetProductTypeId = focusFinishedIds.length === 1
    ? String(materialData(nodesById.get(focusFinishedIds[0])!).skuId || '') || null
    : null;

  const processNodes = nodes.filter((node) => nodeKind(node) === 'PROCESS');
  const processTargets = processNodes.map((node) => ({
    node,
    downstream: downstreamFinishedIds(node.id, nodesById, outgoing)
      .map((id) => String(materialData(nodesById.get(id)!).skuId || ''))
      .filter(Boolean),
  }));

  const targets: ReworkTargetOption[] = [...productsById.values()].map((product) => {
    const candidates = processTargets.filter(({ node, downstream }) => {
      if (node.id === focusProcessNodeId) return downstream.includes(product.productTypeId);
      return focusName
        && String(processData(node).processName || '') === focusName
        && downstream.length === 1
        && downstream[0] === product.productTypeId;
    });
    const existingPolicyCandidate = processTargets.find(({ node }) =>
      processData(node).reworkPolicy?.targetProductTypeId === product.productTypeId);
    const chosen = candidates.find(({ node }) => node.id === focusProcessNodeId)
      ?? candidates[0]
      ?? existingPolicyCandidate
      ?? null;
    return {
      ...product,
      processNodeId: chosen?.node.id ?? null,
      processName: chosen ? String(processData(chosen.node).processName || '') : null,
      sharedProcess: Boolean(chosen && chosen.downstream.length !== 1),
    };
  });

  const sourceIds = new Set<string>();
  const sourceProductByReworkNodeId: Record<string, string> = {};
  nodes.filter((node) => materialData(node).isRework === true).forEach((reworkNode) => {
    const producerIds = incoming.get(reworkNode.id) ?? [];
    producerIds.forEach((producerId) => {
      downstreamFinishedIds(producerId, nodesById, outgoing).forEach((finishedId) => {
        if (finishedId === reworkNode.id) return;
        const productTypeId = String(materialData(nodesById.get(finishedId)!).skuId || '');
        if (productTypeId) {
          sourceIds.add(productTypeId);
          sourceProductByReworkNodeId[reworkNode.id] = productTypeId;
        }
      });
    });
  });

  const sources = [...sourceIds]
    .map((id) => productsById.get(id))
    .filter((item): item is ReworkProductOption => Boolean(item));
  const rules = targets.flatMap((target): ReworkMatrixRule[] => {
    if (!target.processNodeId) return [];
    const policy = processData(nodesById.get(target.processNodeId)!).reworkPolicy;
    return [{
      processNodeId: target.processNodeId,
      targetProductTypeId: target.productTypeId,
      targetProductName: target.productName,
      allowedSourceProductTypeIds: Array.isArray(policy?.allowedSourceProductTypeIds)
        ? policy!.allowedSourceProductTypeIds.filter((id) => sourceIds.has(id))
        : [],
      maxInputRatioPercent: Number.isFinite(Number(policy?.maxInputRatioPercent))
        ? Math.max(0, Math.min(100, Number(policy!.maxInputRatioPercent)))
        : 0,
    }];
  });

  return { sources, targets, rules, focusTargetProductTypeId, sourceProductByReworkNodeId };
}

export function referenceColorForProduct(
  context: ReworkMatrixContext,
  productTypeId: string,
): string | null {
  return context.sources.find((source) => source.productTypeId === productTypeId)?.color
    ?? context.targets.find((target) => target.productTypeId === productTypeId)?.color
    ?? null;
}
