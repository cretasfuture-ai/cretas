import type {
  MaterialNodeData, ProcessNodeData, WorkflowReworkPolicy, WorkflowReworkSource,
} from './types';

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
 * 产品级模型表达不了「同一线路选两道工序」，所以换成 per-(回料, 目标工序) 的配方项
 * `sources: [{ reworkNodeId, inputRatioPercent }]`。
 *
 * 🔴 比例是**每一对**各自一个，不是每道工序一个：同一道工序可以接收两块不同的回料，
 * 各自的投入比例本来就不同 —— 只挂一个数在工序上表达不了，那是 #417 模型的另一个缺口。
 *
 * ⛔ 只有这一份真值。刻意**不**在回料 Cell 上再存一份 `allowedTargetIds`：
 * 同一个关系存两处一定会漂（形态 D）。抽屉是源侧视图，读写都经过本文件反查。
 *
 * ## 诚实边界
 * 这份配方**会持久化**（进 nodesJson ⇒ 进 revisionHash ⇒ 改它产生新工艺版本），
 * 但 Java 侧当前**没有任何运行时消费者**：`WorkflowReworkNodes` 只认 `data.isRework`
 * 这个标记，没有读过 `reworkPolicy`。所以 UI 只能说「已配置」，
 * ⛔ 不能说「已限制投料」——那是一句代码给不出证据的话。
 *
 * ## ⛔ 回料是**可选项**，不许堵塞主线（2026-09-01 owner 裁定）
 * 不配回料、配了回料但还没配回投目标，都**不影响**保存与发布。
 * 交接书 §7 原写「没有目标时保存/发布 fail closed」——那条已被 owner 推翻：
 * 回料本来就是可选能力，让它拦住整张工艺图的发布是本末倒置。
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
  /** 已配的投入比例（BOM 性质的确定值）；没配这一对时为 null。 */
  inputRatioPercent: number | null;
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

function sourcesOf(node: ReworkFlowNodeLike): WorkflowReworkSource[] {
  const policy = dataOf<ProcessNodeData>(node).reworkPolicy;
  return Array.isArray(policy?.sources) ? policy.sources : [];
}

/** 已经允许这块回料回投的目标工序 id（从各工序的 reworkPolicy 反查，唯一真值）。 */
export function selectedTargetProcessIds(
  nodes: readonly ReworkFlowNodeLike[],
  reworkNodeId: string,
): string[] {
  return nodes
    .filter((node) => kindOf(node) === 'PROCESS')
    .filter((node) => sourcesOf(node).some((source) => source.reworkNodeId === reworkNodeId))
    .map((node) => node.id);
}

/** 这块回料投进某道工序的**配方比例**；没配这一对时返回 null（⛔ 不返回 0 —— 0 是合法比例）。 */
export function inputRatioFor(
  nodes: readonly ReworkFlowNodeLike[],
  reworkNodeId: string,
  processNodeId: string,
): number | null {
  const node = nodes.find((candidate) => candidate.id === processNodeId);
  if (!node) return null;
  const source = sourcesOf(node).find((item) => item.reworkNodeId === reworkNodeId);
  return source ? clampRatio(source.inputRatioPercent) : null;
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
      inputRatioPercent: inputRatioFor(nodes, reworkNodeId, node.id),
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

/** 一条待写入的「这块回料 → 这道工序，按这个比例投」。 */
export interface ReworkTargetSelection {
  processNodeId: string;
  inputRatioPercent: number;
}

/**
 * 把「这块回料回投到哪些工序、各按多少比例」写成各目标工序上的 `reworkPolicy` 变更。
 *
 * ⛔ **更新同一组规则，不重复追加**：以目标工序当前的 `sources` 为基准做集合增删改，
 * 绝不 push 重复条目。同一块回料保存两次得到完全相同的结果（幂等）。
 *
 * ⛔ 目标被取消且该工序不再接受任何回料时，`reworkPolicy` 整个清成 null，
 * 不留一个空数组的空壳（空壳会让「配过又取消」和「从没配过」长得一样）。
 *
 * ⚠️ `nextSelections` 允许为空 —— 回料是可选项，用户可以把目标全清掉再保存。
 */
export function planReworkTargetWrites(
  nodes: readonly ReworkFlowNodeLike[],
  reworkNodeId: string,
  nextSelections: readonly ReworkTargetSelection[],
): ReworkPolicyWriteResult {
  const wanted = new Map(nextSelections.map((item) => [item.processNodeId, clampRatio(item.inputRatioPercent)]));
  const updates: ReworkPolicyWriteResult['updates'] = [];
  nodes.filter((node) => kindOf(node) === 'PROCESS').forEach((node) => {
    const current = sourcesOf(node);
    const others = current.filter((source) => source.reworkNodeId !== reworkNodeId);
    const mine = current.find((source) => source.reworkNodeId === reworkNodeId) ?? null;
    const nextRatio = wanted.has(node.id) ? (wanted.get(node.id) as number) : null;
    const unchanged = nextRatio === null
      ? mine === null
      : mine !== null && clampRatio(mine.inputRatioPercent) === nextRatio;
    if (unchanged) return;
    const merged = nextRatio === null
      ? others
      : [...others, { reworkNodeId, inputRatioPercent: nextRatio }];
    updates.push({
      processNodeId: node.id,
      policy: merged.length === 0 ? null : { sources: merged },
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
    const current = sourcesOf(node);
    if (current.length === 0) return;
    const kept = current.filter((source) => liveReworkIds.has(source.reworkNodeId));
    if (kept.length === current.length) return;
    updates.push({
      processNodeId: node.id,
      policy: kept.length === 0 ? null : { sources: kept },
    });
  });
  return { updates };
}

/**
 * 「配了回料但一道回投目标都没配」的回料 Cell。
 *
 * ⛔ 这**不是门禁**（2026-09-01 owner 裁定）：回料是可选能力，不许拦住保存与发布。
 * 它只用来在 Cell 上显示一个可见状态，让用户知道这块回料的去处还没写。
 */
export function reworkNodesWithoutTargets(
  nodes: readonly ReworkFlowNodeLike[],
): string[] {
  return nodes
    .filter((node) => dataOf<MaterialNodeData>(node).isRework === true)
    .filter((node) => selectedTargetProcessIds(nodes, node.id).length === 0)
    .map((node) => node.id);
}

// -- R 编号配对（已确认原型 v8/v11：「回料使用同色 R 编号配对，不绘制永久回头线」）--

/**
 * 配对色板 —— 取自已确认原型 `workflow-rework-prototype/index.html` 的
 * `pair:'R1'..'R4'` 那四条数据（`#2878D0` / `#17846F` / `#C56A10` / `#7656C9`）。
 *
 * ⛔ 不要按「好看」改这四个值：它们同时用在 R 角标、卡片左侧竖条和悬停时的色环上，
 * 换一处就会出现「同一块回料两种颜色」。
 */
export const REWORK_PAIR_COLORS = ['#2878D0', '#17846F', '#C56A10', '#7656C9'] as const;

export interface ReworkPair {
  /** 显示用编号：R1、R2 …… */
  label: string;
  color: string;
  /** 这个编号指向哪块回料 —— 点角标要靠它定位到远端。 */
  reworkNodeId: string;
}

/**
 * 给画布上每一块回料分配 R 编号与配色。
 *
 * 为什么要编号：回投是**跨工序的远程关系**。把它画成连线，一张多线路的图上会出现
 * 十几条横穿画布的回头线，谁连谁根本读不出来。原型的做法是给每块回料一个 R 编号，
 * 在它自己和它的目标工序上各打一个同色角标，悬停时两头一起亮 ——
 * 关系用**配对**表达，不用线。
 *
 * ⚠️ 编号按**节点顺序**分配，不是按名字或 id 排序：用户看到的顺序就是画布上的顺序，
 * 换个排序规则会让同一张图在两次打开之间编号不同。
 */
export function assignReworkPairs(
  nodes: readonly ReworkFlowNodeLike[],
): Map<string, ReworkPair> {
  const pairs = new Map<string, ReworkPair>();
  let index = 0;
  for (const node of nodes) {
    if (dataOf<MaterialNodeData>(node).isRework !== true) continue;
    pairs.set(node.id, {
      label: `R${index + 1}`,
      color: REWORK_PAIR_COLORS[index % REWORK_PAIR_COLORS.length],
      reworkNodeId: node.id,
    });
    index += 1;
  }
  return pairs;
}

/**
 * 一道工序上要显示哪些 R 角标 —— 即「哪些回料允许回投到这道工序」。
 *
 * 真值仍然只有一份（各工序 data.reworkPolicy.sources），这里只是把它翻译成角标。
 */
export function reworkPairsForProcess(
  nodes: readonly ReworkFlowNodeLike[],
  processNodeId: string,
): ReworkPair[] {
  const pairs = assignReworkPairs(nodes);
  const process = nodes.find((node) => node.id === processNodeId);
  if (!process) return [];
  const policy = dataOf<ProcessNodeData>(process).reworkPolicy;
  if (!policy || !Array.isArray(policy.sources)) return [];
  return policy.sources
    .map((source) => pairs.get(source.reworkNodeId))
    .filter((pair): pair is ReworkPair => pair != null);
}

/**
 * 悬停一块回料时该一起亮起来的节点集合 = 它自己 + 它的全部目标工序。
 *
 * ⛔ 不包含中间路径上的节点：原型压暗的是「与这条关系无关的一切」，
 * 而路径是推断出来的，不是用户配的关系。
 */
export function reworkPairFocusIds(
  nodes: readonly ReworkFlowNodeLike[],
  reworkNodeId: string,
): string[] {
  return [reworkNodeId, ...selectedTargetProcessIds(nodes, reworkNodeId)];
}
