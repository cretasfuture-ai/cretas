import type { MaterialNodeData, ProcessNodeData, WorkflowReworkPolicy } from './types';

/**
 * 回料「允许回投的目标工序」的**唯一真值读写口**。
 *
 * ## 承接自 PR #417
 * #417 引入了 `ProcessNodeData.reworkPolicy` 作为回料规则的载体，并写了
 * `deriveReworkMatrixContext` 从真实画布拓扑派生「哪条产品线路」。本文件**继承那两样**：
 * 载体仍然是目标工序节点上的 `reworkPolicy`，线路派生仍然走「沿出边走到终端成品」。
 *
 * ## 与 #417 的唯一差别：关系的粒度
 * #417 的 `allowedSourceProductTypeIds` 是**产品级**的（一个成品折一道工序），
 * 而已确认的交互（§8）要选的是**工序级**的目标 —— 一条线路里可以有多道工序都允许回投。
 * 产品级模型表达不了「同一线路选两道工序」，所以这里把同一个字段换成
 * `allowedSourceReworkNodeIds`：装的是**回料 Cell 的 node id**。
 *
 * ⛔ 只有这一份真值。刻意**不**在回料 Cell 上再存一份 `allowedTargetIds`：
 * 同一个关系存两处一定会漂（形态 D）。抽屉是源侧视图，读写都经过本文件反查。
 *
 * ## 诚实边界
 * 这份配置**会持久化**（进 nodesJson ⇒ 进 revisionHash ⇒ 改它产生新工艺版本），
 * 但 Java 侧当前**没有任何运行时消费者**：`WorkflowReworkNodes` 只认 `data.isRework`
 * 这个标记，没有读过 `reworkPolicy`。所以 UI 只能说「已配置」，
 * ⛔ 不能说「已限制投料」——那是一句代码给不出证据的话。
 */

export interface ReworkFlowNodeLike {
  id: string;
  data?: Record<string, unknown>;
}

export interface ReworkFlowEdgeLike {
  source: string;
  target: string;
}

/** 一条终端产品线路。`productTypeId` 为空串表示「尚未绑定成品 SKU 的线路」。 */
export interface ReworkRouteGroup {
  routeKey: string;
  /** 线路名 = 终端成品名；共用工序单独成组。 */
  routeName: string;
  productTypeId: string;
  /** 该组内可选的目标工序。 */
  options: ReworkTargetOption[];
  /** 来源回料所属线路排最前。 */
  isSourceRoute: boolean;
  /** 多条线路共用的工序聚在这一组。 */
  isSharedGroup: boolean;
}

export interface ReworkTargetOption {
  processNodeId: string;
  /** 短名（工序名本身），过长由 UI 省略。 */
  processName: string;
  /** 完整名称，进 tooltip / aria-label。 */
  fullLabel: string;
  /** 这道工序通向哪些终端成品（>1 即共用工序）。 */
  routeProductTypeIds: string[];
  selected: boolean;
}

export interface ReworkTargetContext {
  reworkNodeId: string;
  /** 来源工序（产出这块回料的那道工序）。 */
  sourceProcessNodeId: string | null;
  sourceRouteProductTypeIds: string[];
  groups: ReworkRouteGroup[];
  selectedCount: number;
  totalCount: number;
}

function dataOf<T>(node: ReworkFlowNodeLike): T {
  return (node.data ?? {}) as T;
}

function kindOf(node: ReworkFlowNodeLike): string {
  return String(node.data?.kind ?? '');
}

/** 沿出边走到所有终端成品节点 —— 继承 PR #417 的 downstreamFinishedIds。 */
function downstreamFinishedIds(
  startNodeId: string,
  nodesById: Map<string, ReworkFlowNodeLike>,
  outgoing: Map<string, string[]>,
): string[] {
  const queue = [...(outgoing.get(startNodeId) ?? [])];
  const visited = new Set<string>([startNodeId]);
  const result: string[] = [];
  while (queue.length > 0) {
    const next = queue.shift() as string;
    if (visited.has(next)) continue;
    visited.add(next);
    const node = nodesById.get(next);
    if (!node) continue;
    if (kindOf(node) === 'FINISHED_GOOD') {
      result.push(next);
      continue;
    }
    queue.push(...(outgoing.get(next) ?? []));
  }
  return [...new Set(result)];
}

function buildAdjacency(nodes: readonly ReworkFlowNodeLike[], edges: readonly ReworkFlowEdgeLike[]) {
  const nodesById = new Map(nodes.map((node) => [node.id, node]));
  const outgoing = new Map<string, string[]>();
  const incoming = new Map<string, string[]>();
  edges.forEach((edge) => {
    outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge.target]);
    incoming.set(edge.target, [...(incoming.get(edge.target) ?? []), edge.source]);
  });
  return { nodesById, outgoing, incoming };
}

/** 产出这块回料的那道工序。回料节点只可能由一道工序产出（addOutputToProcess 只连一条）。 */
export function sourceProcessOfRework(
  nodes: readonly ReworkFlowNodeLike[],
  edges: readonly ReworkFlowEdgeLike[],
  reworkNodeId: string,
): string | null {
  const { nodesById, incoming } = buildAdjacency(nodes, edges);
  for (const producerId of incoming.get(reworkNodeId) ?? []) {
    const producer = nodesById.get(producerId);
    if (producer && kindOf(producer) === 'PROCESS') return producerId;
  }
  return null;
}

/** 已经允许这块回料回投的目标工序 id（从各工序的 reworkPolicy 反查，唯一真值）。 */
export function selectedTargetProcessIds(
  nodes: readonly ReworkFlowNodeLike[],
  reworkNodeId: string,
): string[] {
  return nodes
    .filter((node) => kindOf(node) === 'PROCESS')
    .filter((node) => {
      const policy = dataOf<ProcessNodeData>(node).reworkPolicy;
      return Array.isArray(policy?.allowedSourceReworkNodeIds)
        && policy.allowedSourceReworkNodeIds.includes(reworkNodeId);
    })
    .map((node) => node.id);
}

/**
 * 按**实际画布拓扑**把可选目标工序分组：
 * - 每道工序沿出边找终端成品；恰好通向一个 ⇒ 归到那条线路；
 * - 通向多个 ⇒ 归到「共用工序」组（规则确定、可测试，⛔ 不随机塞进某一条线路）；
 * - 通向 0 个（线路末端还没绑成品）⇒ 归到「尚未绑定成品的线路」组；
 * - 来源回料所属线路排最前。
 *
 * ⛔ 线路条数由拓扑决定，代码里没有 4，也没有任何示例产品名。
 */
export function deriveReworkTargetContext(
  nodes: readonly ReworkFlowNodeLike[],
  edges: readonly ReworkFlowEdgeLike[],
  reworkNodeId: string,
): ReworkTargetContext {
  const { nodesById, outgoing } = buildAdjacency(nodes, edges);
  const selected = new Set(selectedTargetProcessIds(nodes, reworkNodeId));
  const sourceProcessNodeId = sourceProcessOfRework(nodes, edges, reworkNodeId);

  const finishedName = (productTypeId: string): string => {
    const node = nodes.find((candidate) => kindOf(candidate) === 'FINISHED_GOOD'
      && String(dataOf<MaterialNodeData>(candidate).skuId || '') === productTypeId);
    if (!node) return productTypeId;
    const data = dataOf<MaterialNodeData>(node);
    return String(data.name || data.skuCode || productTypeId);
  };

  const routeIdsOf = (processNodeId: string): string[] => downstreamFinishedIds(processNodeId, nodesById, outgoing)
    .map((id) => String(dataOf<MaterialNodeData>(nodesById.get(id) as ReworkFlowNodeLike).skuId || ''))
    .filter(Boolean)
    .sort();

  const sourceRouteProductTypeIds = sourceProcessNodeId ? routeIdsOf(sourceProcessNodeId) : [];

  const processNodes = nodes.filter((node) => kindOf(node) === 'PROCESS');
  const options: Array<ReworkTargetOption & { routeKey: string }> = processNodes.map((node) => {
    const data = dataOf<ProcessNodeData>(node);
    const routeProductTypeIds = routeIdsOf(node.id);
    const processName = String(data.processName || '未命名工序');
    const routeKey = routeProductTypeIds.length === 1
      ? `product:${routeProductTypeIds[0]}`
      : routeProductTypeIds.length === 0 ? 'unbound' : 'shared';
    const routeLabel = routeProductTypeIds.length === 1
      ? finishedName(routeProductTypeIds[0])
      : routeProductTypeIds.length === 0 ? '尚未绑定成品' : routeProductTypeIds.map(finishedName).join(' / ');
    return {
      routeKey,
      processNodeId: node.id,
      processName,
      fullLabel: `${processName} · ${routeLabel}`,
      routeProductTypeIds,
      selected: selected.has(node.id),
    };
  });

  const byKey = new Map<string, ReworkRouteGroup>();
  options.forEach((option) => {
    const existing = byKey.get(option.routeKey);
    const { routeKey, ...rest } = option;
    if (existing) { existing.options.push(rest); return; }
    const productTypeId = routeKey.startsWith('product:') ? routeKey.slice('product:'.length) : '';
    byKey.set(routeKey, {
      routeKey,
      routeName: routeKey === 'shared' ? '多线路共用工序'
        : routeKey === 'unbound' ? '尚未绑定成品的线路'
          : finishedName(productTypeId),
      productTypeId,
      options: [rest],
      isSourceRoute: routeKey.startsWith('product:')
        && sourceRouteProductTypeIds.includes(productTypeId),
      isSharedGroup: routeKey === 'shared',
    });
  });

  const groups = [...byKey.values()].sort((a, b) => {
    if (a.isSourceRoute !== b.isSourceRoute) return a.isSourceRoute ? -1 : 1;
    // 共用/未绑定组恒在具名线路之后，组序才是确定的、可测试的。
    const rank = (group: ReworkRouteGroup): number => (group.routeKey === 'shared' ? 1 : group.routeKey === 'unbound' ? 2 : 0);
    if (rank(a) !== rank(b)) return rank(a) - rank(b);
    return a.routeName.localeCompare(b.routeName, 'zh-CN');
  });
  groups.forEach((group) => {
    group.options.sort((a, b) => a.processName.localeCompare(b.processName, 'zh-CN')
      || a.processNodeId.localeCompare(b.processNodeId));
  });

  return {
    reworkNodeId,
    sourceProcessNodeId,
    sourceRouteProductTypeIds,
    groups,
    selectedCount: options.filter((option) => option.selected).length,
    totalCount: options.length,
  };
}

export interface ReworkPolicyWriteResult {
  /** 需要写回的工序节点 id → 新的 reworkPolicy（null 表示清空该字段）。 */
  updates: Array<{ processNodeId: string; policy: WorkflowReworkPolicy | null }>;
}

/**
 * 把「这块回料允许回投到哪些工序」写成各目标工序上的 `reworkPolicy` 变更。
 *
 * ⛔ **更新同一组规则，不重复追加**：每次都以「目标工序当前的 allowedSourceReworkNodeIds」
 * 为基准做集合增删，绝不 push 新条目。同一块回料保存两次得到完全相同的结果（幂等）。
 * 目标被取消且该工序不再接受任何回料时，`reworkPolicy` 整个清成 null，
 * 不留一个空数组的空壳（空壳会让「配过又取消」和「从没配过」长得一样）。
 */
export function planReworkTargetWrites(
  nodes: readonly ReworkFlowNodeLike[],
  reworkNodeId: string,
  nextTargetProcessIds: readonly string[],
): ReworkPolicyWriteResult {
  const next = new Set(nextTargetProcessIds);
  const updates: ReworkPolicyWriteResult['updates'] = [];
  nodes.filter((node) => kindOf(node) === 'PROCESS').forEach((node) => {
    const data = dataOf<ProcessNodeData>(node);
    const current = Array.isArray(data.reworkPolicy?.allowedSourceReworkNodeIds)
      ? data.reworkPolicy.allowedSourceReworkNodeIds
      : [];
    const has = current.includes(reworkNodeId);
    const shouldHave = next.has(node.id);
    if (has === shouldHave) return;
    const merged = shouldHave
      ? [...current, reworkNodeId]
      : current.filter((id) => id !== reworkNodeId);
    if (merged.length === 0) {
      updates.push({ processNodeId: node.id, policy: null });
      return;
    }
    updates.push({
      processNodeId: node.id,
      policy: {
        allowedSourceReworkNodeIds: [...new Set(merged)],
        maxInputRatioPercent: clampRatio(data.reworkPolicy?.maxInputRatioPercent),
      },
    });
  });
  return { updates };
}

function clampRatio(value: unknown): number {
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return 0;
  return Math.max(0, Math.min(100, numeric));
}

/**
 * 删除节点后清理指向已不存在节点的回料规则。
 *
 * 不做这一步，被删掉的回料 Cell 会在各工序的 policy 里留下永远选不掉的幽灵 id ——
 * 而它同时是 §9「孤儿 focus ⇒ 全画布变白」的另一个入口。
 */
export function pruneReworkPolicies(
  nodes: readonly ReworkFlowNodeLike[],
): ReworkPolicyWriteResult {
  const liveReworkIds = new Set(nodes
    .filter((node) => dataOf<MaterialNodeData>(node).isRework === true)
    .map((node) => node.id));
  const updates: ReworkPolicyWriteResult['updates'] = [];
  nodes.filter((node) => kindOf(node) === 'PROCESS').forEach((node) => {
    const data = dataOf<ProcessNodeData>(node);
    const current = data.reworkPolicy?.allowedSourceReworkNodeIds;
    if (!Array.isArray(current)) return;
    const kept = current.filter((id) => liveReworkIds.has(id));
    if (kept.length === current.length) return;
    updates.push({
      processNodeId: node.id,
      policy: kept.length === 0 ? null : {
        allowedSourceReworkNodeIds: kept,
        maxInputRatioPercent: clampRatio(data.reworkPolicy?.maxInputRatioPercent),
      },
    });
  });
  return { updates };
}

/**
 * 发布/保存门禁：回料 Cell 必须至少有 1 道允许回投的目标工序。
 *
 * fail closed —— 一块「没有任何去处」的回料在业务上无意义，放它过去只会在报工屏上
 * 出现一个谁也不知道该投到哪里的选项。返回**违规的回料节点 id**，空数组即通过。
 */
export function reworkNodesMissingTargets(
  nodes: readonly ReworkFlowNodeLike[],
): string[] {
  return nodes
    .filter((node) => dataOf<MaterialNodeData>(node).isRework === true)
    .filter((node) => selectedTargetProcessIds(nodes, node.id).length === 0)
    .map((node) => node.id);
}
