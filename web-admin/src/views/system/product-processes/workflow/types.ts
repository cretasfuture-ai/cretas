export type ProductProcessNodeKind =
  | 'RAW_MATERIAL'
  | 'PROCESS'
  | 'SEMI_FINISHED'
  | 'FINISHED_GOOD';

export type WorkflowStatus = 'DRAFT' | 'SNAPSHOT' | 'PUBLISHED';
export type ConversionMode = 'ACTUAL_WEIGHT' | 'FIXED_RATIO' | 'SUM_OUTPUTS' | 'FORMULA';
export type PortSelectionMode = 'ALL_REQUIRED' | 'EXACTLY_ONE' | 'AT_LEAST_ONE' | 'OPTIONAL';
export type WorkflowOutputRole = 'MAIN' | 'CO_PRODUCT' | 'BY_PRODUCT';

export interface WorkflowPosition {
  x: number;
  y: number;
}

export interface WorkflowViewport extends WorkflowPosition {
  zoom: number;
}

export interface MaterialNodeData extends Record<string, unknown> {
  name: string;
  skuId: string;
  skuCode?: string | null;
  specification?: string | null;
  baseUnit?: string;
  bound?: boolean;
  /**
   * 这个终端产出用的包材(2026-08-08)。与工序上的 `materialBindings` 同一口径:
   * 进节点 data ⇒ 进 nodesJson ⇒ 进 revisionHash ⇒ **改包材也产生新工艺版本**。
   *
   * 🔴 补的是一个真机测出来的口径漏洞: 阶段 3-1 只把辅料/调料搬进了定义, 包材没搬 ——
   * 于是改辅料版本会跳、改包材不会跳。「画布是什么样 BOM 就是什么样, 只有一个版本号」
   * 在包材这一维上不成立。
   */
  packagingBindings?: WorkflowPackagingBinding[];
  /**
   * 这个产出 Cell 是副产(2026-08-07 阶段 2)。
   *
   * ⛔ 副产**不是** `ProductProcessNodeKind` 的第 5 个值。设计定稿的原话:
   * 「副产不是分类的第 5 个桶, 它是物料上的 isByproduct 标记, 与材质分类正交」。
   * 所以副产节点的 kind 仍是 SEMI_FINISHED —— 它确实是一件要入库的产出物,
   * 图校验(evaluateWorkflowConnection / BOUNDARY_REQUIRED / SKU_REQUIRED)
   * 对它的处理与普通产出完全一致, 一行都不用为它开特例。
   *
   * 与 `ProcessPort.outputRole` 的关系: 那个字段已弃用(见其注释), 不要用它表达副产。
   */
  isByproduct?: boolean;
  /**
   * 这个产出 Cell 是**回料**(2026-08-28 起编辑器已在写它, 本轮补进类型)。
   *
   * 回料 = 金检/X光 剔出、可回到后续批次继续投的独立半成品。⛔ 与副产刻意分成两个键:
   * 副产端口被后端 `getOutputOptions` 过滤掉, 回料端口**必须留在**报工选项里让操作员填数。
   * Java 侧唯一判据是 `WorkflowReworkNodes`(读的就是本字段), 不是端口上的任何东西。
   */
  isRework?: boolean;
  /**
   * 这个产出 Cell 是**不良品**（2026-09-01 owner 订正后加回）。
   *
   * 语义 = 金检/X光 剔出来、**还没复查**的那部分。车间流程是
   * 「剔出 → 复查 → 存着 → 回料直接回过去」。
   *
   * ⛔ 不是「判废」，⛔ 不是「不入库」—— 那两个说法是 2026-09-01 被 owner 否掉的东西，
   *    而我第一版误把整类删掉了。它**要入库**，「存着」就是入库。
   *
   * 🔑 与另外两个标记的关键区别：Java 侧的 `WorkflowDefectiveNodes` 是**纯角色标**，
   * ⛔ 一个字都不出现在任何 `.filter(...)` 里。
   * `WipInventoryServiceImpl.workflowOutputOptions` 只把**副产**滤掉，没有被滤的半成品
   * 产出照常进报工选项、照常入 `semi_finished_inventory` —— 不良品要的
   * 「报得了 + 存得住」走的就是这条默认路径。把它加进过滤会让它从报工屏上消失。
   *
   * ⚠️ 2026-09-01 订正：本注释上一版写着「没有也不需要 Java 判据类」。
   * 那是在 owner 说「好的区别一下吧，补吧」之前 —— 现在有了，但它的职责只有
   * **区分显示**（录入表上不良品和副产一起填，靠这个标打 tag），不是过滤。
   *
   * ⚠️ 诚实边界：没有「复查通过 → 自动变回料」的流转。复查完回投是人在回料 Cell 那边报的。
   */
  isDefective?: boolean;
  /**
   * 本原料 cell 是哪个原料的替代料(值 = 被替代原料 cell 的 node id)。
   * 空/缺省 = 独立投入(与主料一起投)。
   *
   * ⛔ 载体刻意放在**物料节点**上而不是工序的 portGroups:
   * normalizeDraft 每次保存都会 remove 掉 PROCESS 节点的 portGroups,
   * 且 RuntimeCompiler 在 ACTUAL_IO 下完全绕过它(2026-08-10 实测)。
   * 物料节点不被 normalizeDraft 清洗, 字段才活得下来。
   */
  substituteOfNodeId?: string | null;
}

export interface ProcessPort {
  id: string;
  direction: 'INPUT' | 'OUTPUT';
  materialNodeId?: string;
  materialName?: string;
  skuId?: string;
  materialKind?: Exclude<ProductProcessNodeKind, 'PROCESS'>;
  unit: string;
  /** Legacy snapshot field. The editor no longer authors planned input/output quantities. */
  standardQuantity?: number;
  quantityMode?: 'AUTO_CONVERT' | 'FIXED_RATIO';
  conversionRefId?: string | null;
  conversionVersion?: number | null;
  /** Required for every port when one process has multiple outputs. */
  outputRole?: WorkflowOutputRole | null;
  /** Shared-cost allocation percentage; multi-output ports must total 100. */
  costAllocationRatio?: number | null;
  ordinal: number;
}

export interface ProcessPortGroup {
  id: string;
  direction: 'INPUT' | 'OUTPUT';
  label: string;
  mode: PortSelectionMode;
  minSelections: number;
  maxSelections: number;
  portIds: string[];
}

/**
 * 一行调料/辅料投入 —— 2026-08-07 阶段 3(版本合一)起，它是**工艺定义的一部分**。
 *
 * ## 这是本阶段的核心口径变更
 * 以前：投入明细住在 `bom_recipe_items`，画布只是从 BOM 表派生一个只读浮层 cell 来展示，
 *       改克数只动 BOM 草稿、**不产生新工艺版本**（那正是 stripBomOverlay 存在的理由）。
 * 现在：画布定义是权威，BOM 表是投影。改克数 = 改节点 data = 改 nodesJson = 换 revisionHash
 *       = **新工艺版本**。旧版本原样留给已排产批次 —— 这是方案 B 的核心收益。
 *
 * ⛔ 不需要改哈希公式：`WorkflowRevisionSnapshotService#hash` 算的就是整个 nodesJson，
 *    这个字段挂在节点 data 里就自动进 hash。既有 revision 的 nodesJson 不变 ⇒ 它们的
 *    hash 也不变 ⇒ 已排产计划钉的 selected_workflow_revision_hash 不会失配。
 */
export interface WorkflowMaterialBinding {
  /** 指向 raw_material_types(id) —— 与 bom_recipe_items.material_type_id 同一业务键。 */
  materialTypeId: string;
  /** 展示名。权威是 materialTypeId，这里只为读图时不必再查一次档案。 */
  materialName?: string | null;
  /** 每 kg 投入需要多少克。必须 > 0（0 不是「没配」，是「配了个静默无效的行」）。 */
  dosagePerKgG: number;
  /** 后续锅调料比例 0–100。⛔ 只在熟制类工序上有意义，见 seasoningProcessCategory.ts。 */
  subsequentPotRatio?: number | null;
  unit?: string | null;
}

/** 一行包材投入 —— 挂在终端产出节点上。用量是确定性消耗, 必须 > 0(激活闸要求)。 */
export interface WorkflowPackagingBinding {
  materialTypeId: string;
  materialName?: string | null;
  /** 每 1 份成品用多少。⛔ 包材与主料不同: 它不许为空(见 validateActivatableItems)。 */
  standardQuantity: number;
  unit?: string | null;
}

export interface ProcessNodeData extends Record<string, unknown> {
  workProcessId: string;
  processName: string;
  /** New drafts use actual report selections; legacy snapshots may omit this marker. */
  reportingSelectionMode?: 'ACTUAL_IO';
  inputUnit: string;
  outputUnit: string;
  ports: ProcessPort[];
  /** Optional for legacy workflow JSON. Missing groups mean every port is required. */
  portGroups?: ProcessPortGroup[];
  /** Read-only BOM-derived input requirements; absence means the BOM contract is not configured. */
  conversionRule: {
    mode: ConversionMode;
    expression?: string | null;
  };
  reportingRequired: boolean;
  processCategory?: string | null;
  /**
   * 这道工序的调料/辅料投入明细（阶段 3 起是工艺定义的一部分，见 WorkflowMaterialBinding）。
   * 空/缺省 = 这道工序不投调料。
   */
  materialBindings?: WorkflowMaterialBinding[];
  /** 注射量(kg)。⛔ 只在注射类工序上有意义，见 seasoningProcessCategory.ts。 */
  injectionAmount?: number | null;
  standardTime?: number | null;
  allowMultipleUpstreamSources?: boolean;
  allowFinishedGoodsSource?: boolean;
  /** 这道工序接收哪些回料、各按多少比例投 —— 见 {@link WorkflowReworkPolicy}。 */
  reworkPolicy?: WorkflowReworkPolicy | null;
}

/**
 * 一道工序的回料规则 —— **规则住在目标工序上**（承接 PR #417 的载体选择），
 * 随 Workflow 版本保存（进 nodesJson ⇒ 进 revisionHash ⇒ 改它产生新工艺版本）。
 *
 * ⛔ **只有这一份真值**：刻意不在回料 Cell 上再存一份「我能去哪」。同一个关系存两处
 * 一定会漂，而漂的那一份不会报错，只会让画布和保存出来的定义讲两个故事。
 */
export interface WorkflowReworkPolicy {
  /** 允许回投到本工序的回料，以及各自的投入比例。至少 1 条，否则整个 policy 应为 null。 */
  sources: WorkflowReworkSource[];
}

/**
 * 一块回料回投进某一道工序的配方项。
 *
 * 🔴 **`inputRatioPercent` 是 BOM 性质的确定投入比例，不是「上限」**（2026-09-01 owner 订正）。
 * 语义是「这块回料就是按这个百分比投进这道工序的」——由工厂纪律保证照此执行，
 * **实际投了多少不需要记录**。
 *
 * ⛔ 所以措辞里不许出现「上限」「最多」「不超过」：那会让人以为系统在卡一个天花板，
 * 而它其实是在声明一个配方值。
 *
 * ## 分母是**主料**，不是工序总投入（2026-09-01 owner 第二次订正）
 * owner 原话：「比例的话是按照投入的原料或者半成品」。所以算式是：
 *
 *     回料投入量 = 本工序投入的原料/半成品数量 × inputRatioPercent%
 *
 * 例：腌制投 100kg 猪蹄原料、比例 8.5% ⇒ 回料投 8.5kg，本工序共投 108.5kg。
 *
 * ⛔ **不是**「本工序总投入里有 8.5% 是回料」（那样算是 8.5kg 回料 + 91.5kg 主料）。
 * 两种读法差一个量级，而它直接进核算 —— 这就是为什么要写死在类型上，不靠界面措辞。
 *
 * ⚠️ 由此：同一块回料投给多道工序时，各道**各算各的、不要求合计 100%**
 * （每道以自己的主料为分母）。⛔ 不要加「合计必须等于 100」的校验。
 *
 * ## 它拿来干什么：**自动扣减**（2026-09-01 owner 第三次订正，**尚未实现**）
 * owner 原话：「不用提醒，我说的是直接作为自动扣减，拉数据的时候能看到」。
 * - ⛔ 不提醒、不警告、不弹窗（我上一版写成「实时警告」是错的）；
 * - ⛔ 报工时不要操作员为它填一笔投入，也不替他把料配上；
 * - ⛔ 不拦报工；
 * - ✅ 核算时**按这个比例自动扣减**，结果在拉数据时看得到。
 *
 * ⚠️ 诚实边界：上面那条 ✅ **现在不存在**。Java 侧没有任何消费者读本字段 ——
 * `WorkflowReworkNodes` 只认物料节点上的 `data.isRework`。所以它今天是一个
 * **已持久化的配方声明**，扣减引擎还没接。
 * ⛔ UI 上不许写成「系统会自动扣」——那是一句代码给不出证据的话。
 */
export interface WorkflowReworkSource {
  /** 回料 Cell 的 node id。 */
  reworkNodeId: string;
  /**
   * 这块回料按本工序**主料**（投入的原料/半成品）的百分比投（0–100）。
   * ⛔ 不是「占工序总投入的比例」—— 见上方类型注释里的算式。
   */
  inputRatioPercent: number;
}

export type WorkflowNodeData = MaterialNodeData | ProcessNodeData;

export interface ProductProcessWorkflowNode {
  id: string;
  kind: ProductProcessNodeKind;
  position: WorkflowPosition;
  data: WorkflowNodeData;
}

export interface ProductProcessWorkflowEdge {
  id: string;
  source: string;
  sourceHandle: string;
  target: string;
  targetHandle: string;
}

export interface ProcessBranchInput {
  source: ProductProcessWorkflowNode;
  workProcess: {
    id: string;
    processName: string;
    defaultOutputMaterialKind: 'SEMI_FINISHED' | 'FINISHED_GOOD';
  };
  productTypeId: string;
  productName: string;
  /** Bound finished-product identity owns its unit; an unbound semi-finished output has no unit yet. */
  productUnit?: string;
  timestamp: number;
}

export interface ProductProcessWorkflowDefinition {
  id?: number;
  factoryId?: string;
  productTypeId?: string;
  schemaVersion: 1;
  status: WorkflowStatus;
  version: number;
  lockVersion?: number;
  revisionId?: number | null;
  revisionHash?: string | null;
  /** Save response metadata when the server froze a referenced row and forked a draft. */
  autoForked?: boolean;
  forkedFromWorkflowId?: number | null;
  forkedFromVersion?: number | null;
  unitReviewRequired?: boolean;
  nodes: ProductProcessWorkflowNode[];
  edges: ProductProcessWorkflowEdge[];
  viewport: WorkflowViewport;
}

export interface ProductProcessWorkflowActivation {
  id: number;
  factoryId: string;
  productTypeId: string;
  activeWorkflowId: number;
  activeDefinitionVersion: number;
  enabled: boolean;
  /** 后端按已发布画布拓扑派生，用户不可手选。 */
  workflowType?: 'SINGLE_OUTPUT_PRODUCT' | 'RAW_MATERIAL_SPLIT' | 'JOINT_PRODUCTION' | null;
  rootInputProductTypeIds?: string[] | null;
  terminalOutputProductTypeIds?: string[] | null;
  activatedBy?: number | null;
  activatedAt?: string | null;
  lockVersion: number;
}

export type WorkflowBomSyncClassification =
  | 'READY'
  | 'AUTO_MIGRATABLE'
  | 'USER_INPUT_REQUIRED'
  | 'CONFLICT';

export interface WorkflowBomAutomaticMapping {
  materialTypeId: string | null;
  materialName: string | null;
  fromNodeId: string | null;
  toNodeId: string | null;
  toProcessNodeId: string | null;
  toInputPortId: string | null;
  toEdgeId: string | null;
  ownerRecipeId: string | null;
  costScope: string | null;
  costScopeKey: string | null;
}

export interface WorkflowBomSyncIssue {
  code: string;
  materialTypeId: string | null;
  materialName: string | null;
  processNodeId: string | null;
  field: string | null;
  message: string;
  action: string | null;
}

export interface WorkflowBomSyncPreflight {
  classification: WorkflowBomSyncClassification;
  activeBomVersion: number | null;
  syncDraftVersion: number | null;
  activeBomWorkflowRevisionId: number | null;
  targetWorkflowRevisionId: number | null;
  preservedItems: string[];
  automaticMappings: WorkflowBomAutomaticMapping[];
  missingItems: WorkflowBomSyncIssue[];
  conflicts: WorkflowBomSyncIssue[];
  canCompleteAutomatically: boolean;
}

export interface WorkflowPublishAndActivateRequest {
  lockVersion: number;
  idempotencyKey: string;
  revisionId: number;
  revisionHash: string;
  definitionVersion: number;
}

export interface WorkflowPublishAndActivateResponse {
  workflow: ProductProcessWorkflowDefinition;
  activation: ProductProcessWorkflowActivation;
  bomSync: WorkflowBomSyncPreflight;
  planRebind: WorkflowPlanRebindSummary;
  idempotencyKey: string;
  replayed: boolean;
}

export interface WorkflowPlanRebindSummary {
  reboundPlanCount: number;
  preservedStartedPlanCount: number;
  reboundPlanNumbers: string[];
  preservedPlanNumbers: string[];
}

export type WorkflowPatch =
  | { op: 'UPSERT_NODE'; node: ProductProcessWorkflowNode }
  | { op: 'REMOVE_NODE'; nodeId: string }
  | { op: 'UPSERT_EDGE'; edge: ProductProcessWorkflowEdge }
  | { op: 'REMOVE_EDGE'; edgeId: string }
  | { op: 'SET_NODE_FIELD'; nodeId: string; path: string; value: unknown };

export interface WorkflowValidationError {
  code: 'SCHEMA' | 'NODE_REFERENCE' | 'SKU_REQUIRED' | 'PORT_REQUIRED' | 'PORT_GROUP_INVALID'
    | 'OUTPUT_CONTRACT_INVALID' | 'CYCLE' | 'BOUNDARY_REQUIRED';
  message: string;
  nodeId?: string;
  edgeId?: string;
}

export interface LegacyProductWorkProcess {
  id: number;
  workProcessId: string;
  processName?: string;
  processOrder?: number;
  defaultUnit?: string;
  unitOverride?: string;
  reportingRequired?: boolean;
  allowMultipleUpstreamSources?: boolean;
  allowFinishedGoodsSource?: boolean;
}
