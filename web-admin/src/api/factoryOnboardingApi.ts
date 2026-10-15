/**
 * 对话建厂 API client.
 *
 * 后端: TemplatePackageController @ /api/platform
 *       ConversationController   @ /api/mobile/{factoryId}/conversation
 *
 * 一条链四步:
 *   1. POST /factories/{id}/ai-initialize/converse              开多轮会话
 *   2. POST /api/mobile/{id}/conversation/{sessionId}/reply     逐轮回答(复用已有路由)
 *      GET  /factories/{id}/ai-initialize/converse/{sessionId}  进度(remaining / 已收集 / 拼好的描述)
 *   3. POST /factories/{id}/ai-initialize                       描述 → 结构化配置(只生成不落库)
 *   4. POST /factories/{id}/ai-initialize/apply                 人工确认后落库
 *
 * ⚠️ 3 与 4 是**故意分开的**: 生成结果必须先给人看过、可改, 才允许写库。
 *    UI 不得把两步合成一个按钮 —— 那等于取消了「预览」这道防呆。
 *
 * RBAC: super_admin(平台管理员) / factory_super_admin。其余角色后端 403,
 *       由 request.ts 全局处理。
 *
 * @since 2026-08-10
 */
import request from './request'

/**
 * ⚠️ 本文件所有 URL 都写成了**绝对路径**, 而 request 实例的 baseURL 是 `/api/mobile`
 *    (VITE_API_BASE_URL)。不覆盖 baseURL 的话会拼成
 *    `/api/mobile/api/platform/...` → 404。
 *
 *    2026-08-10 实测: type-check 过、build 过、页面也渲染出来了, **但每一个
 *    API 调用都 404** —— 编译期完全看不出来, 只有真在浏览器里点才暴露。
 *
 *    同项目既有写法: permissionApi.ts 调 /api/platform/* 时同样传 `{ baseURL: '' }`。
 */
const ABS = { baseURL: '' } as const

/**
 * ⚠️ 本文件一律**不传分页参数**。
 *
 * 2026-08-10 实测, 这几个接口的分页约定**互不一致**:
 *   /api/platform/factories        0-based —— 传 page=1 只剩最后 2 条(总共 22 条)
 *   /api/mobile/{f}/product-types      1-based —— 传 page=0 直接返回 null
 *   /api/mobile/{f}/raw-material-types 1-based —— 同上
 *   /api/mobile/{f}/form-templates     0/1 都能返回
 *
 * 而四个接口在**不传参数**时都返回全量。平台侧这几张表数据量小(工厂 22 条、
 * 单厂的模板/品类几十条), 不分页是安全的; 若将来量大, 必须**逐个接口**确认
 * 起始页, 不能假设它们同一套。
 *
 * 这个坑的形状: page=1 时接口回 200 + 空数组, 页面显示「还没有工厂」——
 * 看起来像"数据没建", 而不是"分页传错了"。
 */

// ==================== 类型 (与后端 DTO 同步) ====================

/** 后端 ConversationService.ConversationResponse */
export interface ConversationResponse {
  sessionId: string
  currentRound: number
  maxRounds: number
  status: string
  completed: boolean
  /** 助手消息 —— 下一个要问的问题 */
  message: string
  intentCode?: string | null
  intentName?: string | null
  confidence?: number | null
  requiresConfirmation?: boolean
  extractionOutcome?: 'SUCCESS' | 'PARTIAL_SUCCESS' | 'PROVIDER_UNAVAILABLE' | 'INVALID_EXTRACTION' | 'UNKNOWN' | 'CONFLICT'
  providerAttempt?: {
    attemptId: string
    provider: string
    model?: string | null
    latencyMs: number
    attemptCount: number
    failureCategory?: string | null
    retryable: boolean
  } | null
  requestId?: string | null
  idempotentReplay?: boolean
  progressMutated?: boolean
  correctionApplied?: boolean
  invalidFields?: string[]
  fieldReceipts?: ConversationFieldReceipt[]
  resumed?: boolean
  history?: ConversationHistoryMessage[]
}

export interface ConversationFieldReceipt {
  field: string
  resolution: 'ACCEPTED' | 'REJECTED' | 'UNKNOWN' | 'CONFLICT'
  canonicalValue?: string | null
  reasonCode?: string | null
}

export interface ConversationHistoryMessage {
  role: 'user' | 'assistant' | string
  content: string
  extractionOutcome?: ConversationResponse['extractionOutcome'] | null
  correctionApplied?: boolean
  fieldReceipts?: ConversationFieldReceipt[]
}

/** 一道待答题的渲染信息 */
/** 问过没拿到、已经放弃的题 —— 带原因给客户看 */
export interface SkippedItem {
  name: string
  label: string
  /** 为什么放弃(服务端原话, 例:「问了 2 次没拿到确切答案」) */
  why?: string | null
}

export interface PendingItem {
  name: string
  label: string
  /** CHOICE = 画按钮; NARRATIVE = 画输入框 */
  inputType: 'CHOICE' | 'NARRATIVE' | string
  options?: string[] | null
  multiSelect?: boolean
  allowNone?: boolean
  /** 提示语, 带例子 —— 客户靠它知道要说到多细 */
  hint?: string | null
  /** 案例建议值, 可为空 */
  suggestion?: string | null
  repeatIndex?: number | null
}

export interface FactoryDecisionGuidanceChoice {
  label: string
  /** Exact answer when selectable; absent means editable example only. */
  value?: string | null
  example?: string | null
  consequence?: string | null
}

/** Server-owned single-decision contract for the guided factory-design conversation. */
export interface FactoryDecisionPacket {
  decisionId: string
  field: string
  label: string
  question: string
  whyItMatters: string
  inputType: 'CHOICE' | 'NARRATIVE' | string
  options: string[]
  multiSelect: boolean
  allowNone: boolean
  allowUnknown: boolean
  unknownAnswerValue: string
  state: 'PENDING' | 'UNRESOLVED' | string
  acceptedDecision?: string | null
  unresolvedFollowUp?: string | null
  recommendation?: string | null
  recommendationReason?: string | null
  guidanceChoices: FactoryDecisionGuidanceChoice[]
  downstreamImpacts: string[]
}

export interface FactoryFactEvidenceItem {
  field: string
  label: string
  value: string
  sourceKind: 'USER_STRUCTURED' | 'USER_NARRATIVE' | 'LEGACY_SESSION' | string
  evidenceStatus: 'CONFIRMED' | 'LEGACY_UNATTRIBUTED' | string
  reasonCode?: string | null
  capturedAt?: string | null
  evidenceRef: string
  referenceMatched: boolean
}

export interface FactoryRouteCompilationIssue {
  code: string
  message: string
  followUpQuestion: string
}

/** Read-only projection from confirmed interview facts; never a published Workflow. */
export interface FactoryRouteCompilation {
  productIndex: number
  productName: string
  routeShapes: string[]
  routeDetails: string
  status: 'READY' | 'BLOCKED' | 'INCOMPLETE' | string
  previewKind: 'COMPILED_LINEAR' | 'COMPILED_EXPLICIT' | 'ORDER_SKELETON' | string
  publishState: 'NOT_PUBLISHED'
  topologyComplete: boolean
  graph: CanonicalRouteGraph
  /** User-confirmed edge semantics that are not yet executable Workflow fields. */
  edgeIntents?: Array<{
    fromNodeId: string
    fromLabel: string
    toNodeId: string
    toLabel: string
    type: 'FORWARD' | 'BRANCH' | 'MERGE' | 'REWORK'
    condition: string
    reworkExit: string
  }>
  issues: FactoryRouteCompilationIssue[]
  sourceFields: string[]
}

/** Platform-owned recommendation pack; never contains customer or source-factory provenance. */
export interface PlatformInterviewRecommendation {
  industryHint: string
  intro?: string
  steps?: Array<Record<string, unknown>>
  variants?: string[]
  stepCount?: number
}

/** 后端 TemplatePackageController.FactoryBuildProgress */
export interface FactoryBuildProgress {
  sessionId: string
  factoryId: string
  status: string
  currentRound: number
  maxRounds: number
  /** 还剩几项没问到 —— 进度条读这个, 不要去数轮次 */
  remaining: number
  pending: string[]
  pendingLabels: string[]
  answered: string[]
  collected: Record<string, string>
  history?: ConversationHistoryMessage[]
  /** 只有 remaining=0 时才有值 */
  assembledDescription?: string | null
  readyForGenerate: boolean
  /**
   * 待答题的渲染信息 —— 界面按这个画, ⛔ 前端不再自己维护一份题目表。
   *
   * 页面此前写死 5 个 key, 其中 3 个后端早就不问了, 界面在展示不存在的题。
   * 题目是服务端定义的, 就由服务端一次给全。
   */
  pendingItems?: PendingItem[]
  /**
   * 问过两次没拿到、**已经放弃**的题 —— 带原因。
   *
   * ⛔ 它们**不在** pendingItems 里: 那一份是「还问得动的题」, 界面拿它第一项当
   * 「当前这一题」。放弃的项留在里面, 界面就会一直指着一道系统早就不问了的题
   * (2026-08-18 真实浏览器实测: 标签写着「第2道·投入主原料」而对话在问
   * 「第4道·工序名称」, 客户照标签答 → 答案落到别的题上 → 又放弃 → 级联)。
   *
   * ⚠️ 但也**不能就这么消失** —— 那是「被系统悄悄放弃」。要带原因显示出来。
   */
  skippedItems?: SkippedItem[]
  /** 已答题的中文标签(题目名 → 标签) —— 前端猜不出展开项的标签, 由服务端给 */
  answeredLabels?: Record<string, string>
  /** 平台给的逐题建议; ⛔ 建议不是答案, 要客户点「采纳」才提交 */
  suggestions?: Record<string, string>
  /** 平台拥有的行业建议，不含客户、租户、来源工厂或相似度。 */
  platformRecommendation?: PlatformInterviewRecommendation | null
  /** 没有平台建议、或道数与建议对不上时的说明 —— 必须显示出来, 不许静默 */
  recommendationNote?: string | null
  /** Current single decision; the UI must not parse chat prose to reconstruct this. */
  currentDecision?: FactoryDecisionPacket | null
  /** Explicitly unknown answers that must be resolved before complete confirmation. */
  unresolvedDecisions?: FactoryDecisionPacket[]
  /** Derived from collected values + typed receipts; it is not a second answer store. */
  factEvidence?: FactoryFactEvidenceItem[]
  /** Derived from collected route facts; preview-only and always NOT_PUBLISHED. */
  routePreviews?: FactoryRouteCompilation[]
}

export interface EntitySchema {
  entityType: string
  entityName: string
  description?: string
  fields: Array<Record<string, unknown>>
}

export interface TypeItem {
  code: string
  name: string
  /** product_types / raw_material_types 的 unit 列是 NOT NULL —— 空值后端会拒 */
  unit: string
  description?: string
  /** Explicit material storage choice; absent means still awaiting confirmation. */
  storageType?: 'fresh' | 'frozen' | 'dry' | 'normal' | null
  /** 决定产品进入 Workflow/销售等下游目录；普通工厂成品默认 FINISHED_PRODUCT */
  productCategory?: string
  /** 一级包装单位；与 boxConversionCoefficient 成对出现 */
  level1Unit?: string
  /** 1 个一级包装单位等于多少个基本单位 */
  boxConversionCoefficient?: number | null
  /** 物料 L1 分类；产品可不填 */
  category?: string
}

export interface ConversionItem {
  fromCode: string
  toCode: string
  rate: number | null
  note?: string
  /**
   * 转换率来源。Agent 推演只能进入待确认态，不能直接成为可应用配置。
   * USER_CONFIRMED = 用户明确给出；CALCULATED = 可解释公式；AI_INFERRED = 待确认。
   */
  provenance?: 'USER_CONFIRMED' | 'CALCULATED' | 'AI_INFERRED'
}

/**
 * 一条要开的告警。
 *
 * ⛔ 这一段**不来自 LLM** —— 告警类型是固定可枚举的, 由服务端
 * `interview-defaults` 的 `alerts` 段下发目录, 用户勾选后原样回传。
 * 让模型生成只会多一处幻觉(编出一个 AlertType 里没有的类型)。
 */
export interface AlertItem {
  alertType: string
  enabled: boolean
  /** 只有 needsAmount 的类型要填; 不填就开会让每张单子都触发一条告警 */
  amountThreshold?: number
}

/**
 * 一条要盯的生产指标。
 *
 * ⛔ 与 AlertItem 不是一回事: AlertItem 落 alert_rules(事件/定时告警),
 * 这个落 alert_thresholds(异常检测, 按最近若干批的均值判)。两张表不互通。
 */
export interface MetricWatchItem {
  metricName: string
  enabled: boolean
  /** ⛔ 必填 —— 没有阈值的「盯着」是空转 */
  threshold?: number
}

export interface WorkProcessSuggestion {
  name: string
  category?: string
  sortOrder?: number
  unit?: string
  outputUnit?: string
  yieldMin?: number
  yieldMax?: number
  outputKind?: 'SEMI_FINISHED' | 'FINISHED_GOOD' | string
  description?: string
  customFields?: Array<Record<string, unknown>>
  byproducts?: Array<Record<string, unknown>>
  auxMaterials?: RecipeSuggestion[]
  packMaterials?: RecipeSuggestion[]
}

export interface RecipeSuggestion {
  name: string
  quantity?: number | null
  unit?: string
  unitPrice?: number | null
  remark?: string
}

export interface ProductProcessSuggestion {
  productCode: string
  processNames: string[]
  /** 缺失时保持既有线性路线；存在时由服务端按显式图校验，不做静默回退。 */
  routeNodes?: FactoryRouteNodeSuggestion[]
  routeEdges?: FactoryRouteEdgeSuggestion[]
}

export interface FactoryRouteNodeSuggestion {
  id: string
  label: string
}

export interface FactoryRouteEdgeSuggestion {
  fromNodeId: string
  toNodeId: string
  type: 'FORWARD' | 'BRANCH' | 'MERGE' | 'REWORK'
}

export interface HaccpSuggestion {
  name: string
  hazardType?: string
  criticalLimitMin?: number | null
  criticalLimitMax?: number | null
  unit?: string
  monitoringProcedure?: string
}

export interface ThresholdSuggestion {
  key: string
  value: string
}

export interface WarehouseSuggestion {
  code: string
  name: string
  type: string
  remarks?: string
}

export interface CodeSegmentSuggestion {
  code: string
  label: string
}

export interface ModuleConfigSuggestion {
  moduleId: string
  moduleName?: string
  enabled?: boolean
  config?: Record<string, unknown>
}

export interface SuggestedData {
  productTypes: TypeItem[]
  materialTypes: TypeItem[]
  conversionRates: ConversionItem[]
  workProcesses?: WorkProcessSuggestion[]
  haccpCheckpoints?: HaccpSuggestion[]
  modules?: Record<string, boolean>
  thresholds?: ThresholdSuggestion[]
  warehouses?: WarehouseSuggestion[]
  productProcesses?: ProductProcessSuggestion[]
  provisionDefaultUsers?: boolean
  codeSegments?: CodeSegmentSuggestion[]
  alerts?: AlertItem[]
  metricWatches?: MetricWatchItem[]
  moduleConfigs?: ModuleConfigSuggestion[]
  indicatorOptOut?: string[]
}

/**
 * 一条「客户说了、而生成端没配出来」。
 *
 * ⛔ 与 `skipped*` 不是一回事: `skipped*` 是「载荷里有、落库时跳过」;
 *    这一份是「客户说过、而**载荷里根本没有它**」——
 *    它在 planned / written / skipped 里一处都不出现, 三份对账全对得上,
 *    而客户说的东西少了两件。⇒ 只有单独带着它, 复核才说得出这件事。
 */
export interface NotBuiltItem {
  /** 与预览分组同名 —— 复核向导按它归屏 */
  group: string
  /** 客户说的那个东西（⛔ 用他说的名字，不是机器编码） */
  item: string
  why: string
  howToFix: string
  /**
   * `missing`=他说了没配上；`extra`=配出来了他没说过。
   * ⚠️ 老快照里没有这个字段 ⇒ 一律按 `missing` 处理，⛔ 不许因为它是 null 就不显示。
   */
  kind?: string | null
}

/** 后端 AIFactoryInitResponse */
export interface AIFactoryInitResult {
  success: boolean
  industryCode?: string
  industryName?: string
  schemas?: EntitySchema[]
  suggestedData?: SuggestedData
  aiSummary?: string
  message?: string
  /** 客户说了、而生成端没配出来的那几条 —— 要跟着 apply 一起提交，否则复核看不到 */
  notBuilt?: NotBuiltItem[]
}

/** 后端 TemplatePackageController.ApplyAIInitResult */
export interface ApplyAIInitResult {
  success: boolean
  factoryId: string
  message: string
  writtenTemplates: string[]
  writtenProductTypes: string[]
  writtenMaterialTypes: string[]
  writtenConversions: string[]
  skippedTemplates: string[]
  skippedProductTypes: string[]
  skippedMaterialTypes: string[]
  skippedConversions: string[]
  /**
   * 🔴 2026-08-15 补齐:后端 `ApplyAIInitResult` 逐类带回 **16 类**,
   * 而这个接口原本只声明了上面 4 类 —— 剩下 12 类**在类型上不存在**,
   * 于是那一屏想显示也拿不到(`vue-tsc` 会红)。
   *
   * ⚠️ 数据本身一直是到的(JSON 原样传),丢的是**类型契约** ——
   * 这是「产出端有了 ≠ 消费端收得到」在 TS 层的形态:不报错、不留痕。
   *
   * 由 `scripts/tests/check-after-apply-visible.py` 钉住:
   * 后端新增一类而这里/那一屏没跟 ⇒ 闸红。
   */
  writtenWorkProcesses?: string[]
  skippedWorkProcesses?: string[]
  writtenWarehouses?: string[]
  skippedWarehouses?: string[]
  writtenProductProcesses?: string[]
  writtenCodeSegments?: string[]
  skippedCodeSegments?: string[]
  writtenUsers?: string[]
  writtenModules?: string[]
  writtenPackagingConversions?: string[]
  writtenAlerts?: string[]
  skippedAlerts?: string[]
  writtenMetricWatches?: string[]
  skippedMetricWatches?: string[]
  writtenThresholds?: string[]
  writtenModuleConfigs?: string[]
  skippedModuleConfigs?: string[]
  writtenModuleGates?: string[]
  skippedModuleGates?: string[]
  unclassifiedMaterialTypes?: string[]
  writtenHaccpCheckpoints?: string[]
  skippedHaccpCheckpoints?: string[]
  /** 本次落库对应的计划 —— 与预览同一份, 用来证明「做的就是给你看过的」 */
  planned?: PlannedChange[]
  /** 客户说了而没配出来的那几条 —— 原样来自请求，随快照存下来给复核用 */
  notBuilt?: NotBuiltItem[]
}

type Envelope<T> = { success: boolean; data: T; message?: string }

// ==================== 1. 开会话 ====================

export function startFactoryBuildConversation(factoryId: string, forceRestart = false) {
  return request.post<unknown, Envelope<ConversationResponse>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/converse`,
    undefined,
    forceRestart ? { ...ABS, params: { forceRestart: true } } : ABS
  )
}

// ==================== 2. 回答 / 进度 ====================

/**
 * 继续平台建厂专用会话；与普通工厂的会话存储和回复入口隔离。
 */
export function replyFactoryBuildConversation(
  factoryId: string,
  sessionId: string,
  userReply: string,
  requestId: string
) {
  return request.post<unknown, Envelope<ConversationResponse>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/converse/${encodeURIComponent(sessionId)}/reply`,
    { userReply, requestId },
    ABS
  )
}

export function getFactoryBuildProgress(factoryId: string, sessionId: string) {
  return request.get<unknown, Envelope<FactoryBuildProgress>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/converse/${encodeURIComponent(sessionId)}`,
    ABS
  )
}

/** Read-only recovery probe. It never creates, cancels or restarts a conversation. */
export function getRecoverableFactoryBuildConversation(factoryId: string) {
  return request.get<unknown, Envelope<FactoryBuildProgress | null>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/converse/recoverable`,
    ABS
  )
}

/**
 * 结构化答题 —— 枚举题走这条, ⛔ 不走 LLM 抽取。
 *
 * 对枚举项跑抽取是**负作用**: 它会把「差不多」的说法归一到某个选项,
 * 而那正是我们不想要的猜测。2026-08-10 实测过同源缺陷 ——
 * 抽取器把一句话劈成两半填进两个参数, 用户真正回答工序链的那句被整句丢弃, 没有一层报错。
 *
 * 后端**先全量校验再写入**: 任何一项不合规 → 400 且一项都不写。
 */
export function submitStructuredAnswers(
  factoryId: string,
  sessionId: string,
  answers: Record<string, string | string[]>
) {
  return request.post<unknown, Envelope<FactoryBuildProgress>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/converse/${encodeURIComponent(sessionId)}/answers`,
    answers,
    ABS
  )
}

/**
 * 表格题的现值与默认值。
 *
 * ⚠️ defaultsFrom 的取值必须与 Java 侧 `InterviewDefaults.DefaultsFrom` 逐字一致。
 * 2026-08-11 发现它已经漂了: 权限段返回 `PLATFORM_ROLE_TABLE`, 而这里的联合类型
 * 说那不可能出现 —— TS 上看着类型安全, 实际后端天天在返回它。
 * `defaultsFromContract.spec.ts` 现在会拿 Java 枚举逐个比, 少一个就红。
 */
export interface InterviewDefaultsSection {
  key: string
  title: string
  mode: string
  /** ⛔ NONE 表示没有默认可给, 界面不许预填, 更不许去抄别家工厂的配置 */
  defaultsFrom:
    | 'GLOBAL_ROW'
    | 'ENTITY_DEFAULT'
    | 'PLATFORM_ROLE_TABLE'
    | 'PRODUCT_CATALOG'
    | 'NONE'
  defaultsNote?: string | null
  currentRows: number
  rows: Array<Record<string, unknown>>
}

export function getInterviewDefaults(factoryId: string) {
  return request.get<unknown, Envelope<InterviewDefaultsSection[]>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/interview-defaults`,
    ABS
  )
}

// ==================== 3. 生成 (不落库) ====================

export function aiInitializeFactory(
  factoryId: string,
  payload: {
    factoryDescription: string
    industryHint?: string
    factoryName?: string
    includeBusinessData?: boolean
  }
) {
  return request.post<unknown, Envelope<AIFactoryInitResult>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize`,
    payload,
    ABS
  )
}

/**
 * 一条计划中的改动 —— 服务端算的, 预览与落库**共用同一份**。
 *
 * ⛔ 前端不再自己算差异: 本地算过一版, 后果是
 *   · 转换率写死 exists=false → 预览说「新建 2 项」实际写 0 项;
 *   · 只列 模板/产品/原料/转换率 四类, 而落库已经会写工序/配方/存放区/
 *     产品工序链/模块开关/预警阈值 —— **客户确认的东西里有一半没给他看过**。
 * 这类漏报在界面上是"看不见的": 少的那几类根本不存在, 没有一层会提醒。
 */
export interface PlannedChange {
  group: string
  key: string
  detail: string
  /** 新建 / 覆盖 / 跳过 */
  action: string
  exists: boolean
}

/** 落库前预览: 只算不写, 且**先跑与落库同一份校验**(不合规这一步就 400)。 */
export function previewAIInitializedConfig(
  factoryId: string,
  payload: ApplyAIInitRequest
) {
  return request.post<unknown, Envelope<PlannedChange[]>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/preview`,
    payload,
    ABS
  )
}

// ==================== 4. 落库 ====================

/**
 * 落库。后端**先全量校验再写入**, 任何一条不合规 → 400 且一行都不写,
 * message 里逐条列出问题(形如 `productTypes[0].unit 为空`)。
 * 前端不要自己拼 fallback 文案 —— 原样显示后端 message(防呆 4 位一体 b)。
 */
/**
 * 落库/预览的请求体 —— **两处必须是同一个类型**。
 *
 * ⛔ 之前 apply 用的是匿名内联类型, 我给 preview 写了个 `ApplyAIInitRequest` 却没定义,
 *    本地 `vue-tsc -p tsconfig.json` 0 错, CI 的 `vue-tsc -b` 直接 TS2304。
 *    判据: **本地跑的 type-check 命令要和 CI 一样**, 否则本地 0 错证明不了 CI 会绿。
 */
export interface ApplyAIInitRequest {
    industryCode?: string
    industryName?: string
    schemas: EntitySchema[]
    suggestedData: SuggestedData
    /** Explicit optional capability needs; this records intent only and never invents facts. */
    draftCapabilityIntents?: string[]
    /** false = 已存在的跳过并如实报告; true = 覆盖 */
    overwrite: boolean
    /** ⛔ 必须带上：不带它，复核界面就说不出「你说的那两个存放区没建」 */
    notBuilt?: NotBuiltItem[]
}

export type FactoryBlueprintLifecycle =
  | 'DRAFT'
  | 'VALIDATED'
  | 'PUBLISHED'
  | 'APPLIED'
  | 'SUPERSEDED'
  | 'ROLLED_BACK'

export interface CanonicalBlueprintCapability {
  id: string
  label: string
  description: string
  displayOrder: number
  dependsOn: string[]
  configurationTypes: string[]
  moduleSchemaCodes: string[]
  sectorProfiles?: string[]
  authorityRefs?: string[]
  requirementCodes?: string[]
  configurationRefs: string[]
  plannedCount: number
  included: boolean
  status: 'NOT_STARTED' | 'READY' | 'BLOCKED'
  missingDependencies: string[]
  source: 'ONBOARDING_QUESTIONNAIRE_AGENT_SERVER_PLAN'
  sourceUpdatedAt: string
  confirmationState: 'PENDING' | 'CONFIRMED'
  blueprintPublishState: 'NOT_PUBLISHED' | 'PUBLISHED'
  configurationApplyState: 'NOT_APPLIED' | 'APPLIED'
  workflowPublishState: 'NOT_APPLICABLE' | 'NOT_PUBLISHED'
  acceptanceEligible: boolean
  acceptanceState: 'NOT_APPLICABLE' | 'NOT_READY' | 'PENDING' | 'ACCEPTED' | 'REJECTED'
  validationIssues: CanonicalBlueprintValidationIssue[]
  configurationReceipts: Array<Record<string, unknown>>
  acceptanceReceipts: Array<Record<string, unknown>>
  nextStep: string
}

export interface CanonicalCapabilityDefinition {
  id: string
  label: string
  description: string
  displayOrder: number
  dependsOn: string[]
  configurationTypes: string[]
  moduleSchemaCodes: string[]
  sectorProfiles: string[]
  authorityRefs: string[]
  requirementCodes: string[]
  /** Server-owned eligibility for the onboarding draft-intent checkbox list. */
  draftIntentEligible: boolean
}

export interface CanonicalCapabilityCatalog {
  catalogVersion: string
  digest: string
  source: 'SERVER_VERSIONED_CAPABILITY_CATALOG'
  capabilityCount: number
  definitions: CanonicalCapabilityDefinition[]
  loadedAt: string
}

export interface CanonicalBlueprintValidationIssue {
  code: string
  capabilityId: string
  message: string
  recovery: string
}

export interface CanonicalBlueprintValidation {
  valid: boolean
  blockingIssues: CanonicalBlueprintValidationIssue[]
  warnings: CanonicalBlueprintValidationIssue[]
  businessWriteCount: number
  calculatedAt: string
}

export interface CanonicalBlueprintSimulation {
  safe: boolean
  safetyScope: 'CONFIGURATION_APPLY_PREFLIGHT'
  checks: Record<string, boolean>
  blockingIssues: CanonicalBlueprintValidationIssue[]
  limitations: string[]
  affectedModules: string[]
  changes: PlannedChange[]
  diff: Array<{ group: string; createCount: number; overwriteCount: number; skipCount: number }>
  workflow: {
    state: 'NOT_PUBLISHED'
    simulationExecuted: false
    businessWriteCount: number
    nextStep: string
  }
  businessWriteCount: number
  calculatedAt: string
}

export interface CanonicalBlueprintActor {
  type: 'PLATFORM_ADMIN' | 'FACTORY_USER'
  id: number
}

export interface CanonicalBlueprintReceipt {
  id: string
  operationType: 'VALIDATE' | 'SIMULATE' | 'PUBLISH' | 'APPLY' | 'ACCEPT' | 'RECOVERY_PLAN'
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'PARTIAL_FAILED' | 'ROLLED_BACK'
  idempotencyKey: string
  blueprintVersion: number
  operatedBy?: number | null
  operatedActor?: CanonicalBlueprintActor | null
  businessWriteCount: number
  result: Record<string, unknown> | null
  moduleResults: Array<Record<string, unknown>> | null
  recoveryPlan: Record<string, unknown> | null
  createdAt: string
}

export interface CanonicalFactoryBlueprint {
  id: string
  factoryId: string
  factoryName: string
  schemaVersion: 'factory-blueprint/v1'
  capabilityCatalogVersion: string
  capabilityCatalogDigest: string
  version: number
  rowVersion: number
  lifecycleState: FactoryBlueprintLifecycle
  scopeType: 'FACTORY'
  createdBy?: number | null
  updatedBy?: number | null
  createdActor?: CanonicalBlueprintActor | null
  updatedActor?: CanonicalBlueprintActor | null
  publishedActor?: CanonicalBlueprintActor | null
  source: 'ONBOARDING_QUESTIONNAIRE_AGENT_SERVER_PLAN'
  sessionId?: string | null
  /** 访谈源事实，仅参与语义校验，不进入 Apply 配置载荷。 */
  customerSupplied?: string | null
  conversionEvidence: CanonicalConversionEvidence[]
  /** 服务端保存并回读的精确用户复核载荷；刷新后恢复专业编辑器，不依赖本地摘要。 */
  sourcePayload: ApplyAIInitRequest
  capabilities: CanonicalBlueprintCapability[]
  plannedChanges: PlannedChange[]
  workflowLanes: Array<{ productCode: string; productName: string; processNames: string[] }>
  routeGraphs: CanonicalRouteGraph[]
  configurationState: 'NOT_APPLIED' | 'APPLIED'
  acceptanceState: 'NOT_READY' | 'PENDING' | 'PARTIAL' | 'ACCEPTED' | 'REJECTED'
  workflowState: 'NOT_PUBLISHED'
  validation?: CanonicalBlueprintValidation | null
  simulation?: CanonicalBlueprintSimulation | null
  latestReceipt?: CanonicalBlueprintReceipt | null
  createdAt: string
  updatedAt: string
  publishedAt?: string | null
}

export interface CanonicalRouteGraph {
  productCode: string
  productName: string
  nodes: Array<{ id: string; label: string }>
  edges: Array<{
    fromNodeId: string
    toNodeId: string
    type: 'FORWARD' | 'BRANCH' | 'MERGE' | 'REWORK'
  }>
  entryNodeIds: string[]
  terminalNodeIds: string[]
  validation: {
    valid: boolean
    issues: Array<{ code: string; message: string; nodeIds: string[] }>
  }
}

export interface CanonicalConversionEvidence {
  fromCode: string
  toCode: string
  provenance: 'USER_CONFIRMED' | 'CALCULATED' | 'AI_INFERRED'
}

export interface CanonicalFactoryBlueprintVersion {
  blueprintId: string
  factoryId: string
  version: number
  schemaVersion: string
  lifecycleState: FactoryBlueprintLifecycle
  published: boolean
  publishedAt?: string | null
  createdAt: string
}

export interface CanonicalFactoryBlueprintVersionDetail extends CanonicalFactoryBlueprintVersion {
  publishedBy?: number | null
  createdActor?: CanonicalBlueprintActor | null
  publishedActor?: CanonicalBlueprintActor | null
  snapshot: Record<string, unknown>
  changeSummary: unknown
}

interface CanonicalBlueprintOperationRequest {
  idempotencyKey: string
  expectedRowVersion: number
}

export interface CanonicalCapabilityAcceptanceDecision {
  capabilityId: string
  decision: 'ACCEPTED' | 'REJECTED'
  note?: string
  evidenceRefs: string[]
}

export function saveCanonicalFactoryBlueprintDraft(
  factoryId: string,
  payload: {
    factoryName?: string
    sessionId?: string
    customerSupplied?: string
    conversionEvidence?: CanonicalConversionEvidence[]
    idempotencyKey: string
    expectedRowVersion?: number
    payload: ApplyAIInitRequest
  }
) {
  return request.post<unknown, Envelope<CanonicalFactoryBlueprint>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/blueprints/draft`,
    payload,
    ABS
  )
}

export function getCurrentCanonicalFactoryBlueprint(factoryId: string) {
  return request.get<unknown, Envelope<CanonicalFactoryBlueprint | null>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/blueprints/current`,
    ABS
  )
}

export function getCanonicalFactoryCapabilityCatalog(factoryId: string, catalogVersion?: string) {
  const versionQuery = catalogVersion
    ? `?catalogVersion=${encodeURIComponent(catalogVersion)}`
    : ''
  return request.get<unknown, Envelope<CanonicalCapabilityCatalog>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/blueprints/catalog${versionQuery}`,
    ABS
  )
}

export function getCanonicalFactoryBlueprintVersions(factoryId: string, blueprintId: string) {
  return request.get<unknown, Envelope<CanonicalFactoryBlueprintVersion[]>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/blueprints/${encodeURIComponent(blueprintId)}/versions`,
    ABS,
  )
}

export function getCanonicalFactoryBlueprintVersion(
  factoryId: string, blueprintId: string, version: number,
) {
  return request.get<unknown, Envelope<CanonicalFactoryBlueprintVersionDetail>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/blueprints/${encodeURIComponent(blueprintId)}/versions/${version}`,
    ABS,
  )
}

function canonicalBlueprintOperation(
  factoryId: string,
  blueprintId: string,
  operation: 'validate' | 'simulate' | 'publish' | 'apply' | 'recovery-plan',
  payload: CanonicalBlueprintOperationRequest
) {
  return request.post<unknown, Envelope<CanonicalFactoryBlueprint>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/blueprints/${encodeURIComponent(blueprintId)}/${operation}`,
    payload,
    ABS
  )
}

export const validateCanonicalFactoryBlueprint = (
  factoryId: string, blueprintId: string, payload: CanonicalBlueprintOperationRequest
) => canonicalBlueprintOperation(factoryId, blueprintId, 'validate', payload)

export const simulateCanonicalFactoryBlueprint = (
  factoryId: string, blueprintId: string, payload: CanonicalBlueprintOperationRequest
) => canonicalBlueprintOperation(factoryId, blueprintId, 'simulate', payload)

export const publishCanonicalFactoryBlueprint = (
  factoryId: string, blueprintId: string, payload: CanonicalBlueprintOperationRequest
) => canonicalBlueprintOperation(factoryId, blueprintId, 'publish', payload)

export const applyCanonicalFactoryBlueprint = (
  factoryId: string, blueprintId: string, payload: CanonicalBlueprintOperationRequest
) => canonicalBlueprintOperation(factoryId, blueprintId, 'apply', payload)

export function acceptCanonicalFactoryBlueprintCapabilities(
  factoryId: string,
  blueprintId: string,
  payload: CanonicalBlueprintOperationRequest & { decisions: CanonicalCapabilityAcceptanceDecision[] },
) {
  return request.post<unknown, Envelope<CanonicalFactoryBlueprint>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/blueprints/${encodeURIComponent(blueprintId)}/accept`,
    payload,
    ABS,
  )
}

export const getCanonicalFactoryBlueprintRecoveryPlan = (
  factoryId: string, blueprintId: string, payload: CanonicalBlueprintOperationRequest
) => canonicalBlueprintOperation(factoryId, blueprintId, 'recovery-plan', payload)

export function applyAIInitializedConfig(
  factoryId: string,
  payload: ApplyAIInitRequest
) {
  return request.post<unknown, Envelope<ApplyAIInitResult>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/apply`,
    payload,
    ABS
  )
}

// ==================== 现值 (防呆 Rule 1: 预先显示边界) ====================

/**
 * 读工厂**当前**已有的配置, 用于落库前显示「现值 → 确认后的值」。
 *
 * ⛔ 只显示「将写入 N 条」是不够的 —— 用户无从判断这次改动的范围:
 *    是新建还是覆盖? 覆盖掉的是什么? 这正是防呆 Rule 1 要挡的
 *    「填完点提交才知道超了」那类体验。
 *
 * 三个接口都是既有的, 不需要后端新增。任一失败不阻断流程(现值显示为"未知"),
 * 但**不会**因此把差异面板藏起来 —— 藏起来等于悄悄取消了预览。
 */
export interface CurrentFactoryConfig {
  templateEntityTypes: string[]
  productCodes: string[]
  materialCodes: string[]
  /** 已存在的转换率, 形如 "RAW_CODE→PRODUCT_CODE" */
  conversionPairs: string[]
}

export async function fetchCurrentFactoryConfig(factoryId: string): Promise<CurrentFactoryConfig> {
  const fid = encodeURIComponent(factoryId)
  const pick = <T,>(r: unknown, map: (x: Record<string, unknown>) => T): T[] => {
    const d = (r as { data?: unknown })?.data
    const rows = Array.isArray(d) ? d
      : ((d as { content?: unknown[] })?.content
        ?? (d as { list?: unknown[] })?.list
        ?? (d as { records?: unknown[] })?.records ?? [])
    return (rows as Array<Record<string, unknown>>).map(map).filter(Boolean) as T[]
  }
  const safe = async <T,>(fn: () => Promise<T[]>): Promise<T[]> => {
    try { return await fn() } catch { return [] }
  }
  const rowsOf = async (path: string) =>
    safe(async () => pick<Record<string, unknown>>(
    await request.get(`/${fid}/${path}`), (x) => x))

  const [templates, products, materials, conversions] = await Promise.all([
    rowsOf('form-templates'),
    rowsOf('product-types'),
    rowsOf('raw-material-types'),
    rowsOf('conversions'),
  ])

  // 转换率表存的是 id 不是 code(materialTypeId / productTypeId), 而 AI 给的是 code。
  // ⚠️ 2026-08-10 实测到的缺陷: 此前这里没读转换率, diffRows 里一律按「新建」显示,
  //    结果预览承诺「将新建 2 项」而实际全部跳过(后端按 (原料,成品) 幂等)。
  //    那是 Rule 1「预先显示边界」的实打实违反 —— 预览必须说的是**会发生什么**。
  //    (当初我以为「没有现值查询接口」, 其实 GET /conversions 一直都在, 是我没找。)
  const idToCode = (rows: Array<Record<string, unknown>>) => {
    const m = new Map<string, string>()
    rows.forEach((r) => {
      const id = String(r.id ?? '')
      const code = String(r.code ?? '')
      if (id && code) m.set(id, code)
    })
    return m
  }
  const matMap = idToCode(materials)
  const prodMap = idToCode(products)
  const conversionPairs = conversions
    .map((c) => {
      const from = matMap.get(String(c.materialTypeId ?? ''))
      // productCode 是接口直接给的; 拿不到再回落到 id 映射
      const to = (c.productCode ? String(c.productCode) : '')
        || prodMap.get(String(c.productTypeId ?? '')) || ''
      return from && to ? `${from}→${to}` : ''
    })
    .filter(Boolean)

  return {
    templateEntityTypes: templates.map((x) => String(x.entityType ?? '')).filter(Boolean),
    productCodes: products.map((x) => String(x.code ?? '')).filter(Boolean),
    materialCodes: materials.map((x) => String(x.code ?? '')).filter(Boolean),
    conversionPairs,
  }
}

// ==================== 显示层: 把编码换成客户说的名字 ====================

/**
 * 从预览条目的 `detail` 里取出**名字那一段**。
 *
 * ⚠️ 这是**显示用的启发式**, 不是契约: `detail` 是给人看的一句话,
 *    各类的写法不同(实测):
 *      产品类型   `冻干鸡胸粒 / 袋`
 *      原料类型   `冷冻鸡胸肉 / kg / 分类 原料`
 *      转换率     `冷冻鸡胸肉 → 冻干鸡胸粒，出成率 0.98`
 *      表单模板   `原料批次 · 10 个字段`
 *      存放区     `成品仓`
 *    取第一个分隔符之前的那一段即可。取不到就退回原样, ⛔ 不留空。
 */
function headOfDetail(detail: string): string {
  const cut = detail.split(/\s*[，,]|\s+\/\s+|\s+·\s+/)[0]
  return (cut || detail).trim()
}

/**
 * 把一段客户可见文字里的**机器编码**换成他自己说过的名字。
 *
 * ## 为什么需要
 *
 * owner 2026-08-17: 建厂过程里客户看到的文字不许出现编程语言/字段名/编码。
 * 而 apply 回执的每一类都是 `List<String>` 的**编码**清单
 * (`writtenProductTypes: ["FD_CHICKEN"]`)—— 里面没有一个中文字。
 *
 * ## ⛔ 为什么不在后端改
 *
 * 那些键是**对账用的**: 预览与回执按它逐条比(`preview-group-to-result.txt` +
 * `PreviewMatchesApplyTest`)。改成中文就等于把机器对账的键换掉。
 * ⇒ 键留给机器, **显示的时候**查表换成名字。表就是同一次响应里的 `planned`。
 *
 * ⚠️ 按长度倒序替换 —— `FD_CHICKEN` 是 `FD_CHICKEN_BULK` 的前缀。
 * ⚠️ 查不到的原样留着(比如没有 planned 快照时), ⛔ 不留空、不猜。
 *
 * 🔴 **只换含拉丁字母的键**(= 机器编码)。2026-08-17 真实浏览器里量出来的缺陷:
 *    工序那一类的键**本身就是中文**(「冻干」), 而它是「冻干鸡胸粒」的子串 ⇒
 *    无差别替换把产品名切成了「加工 kg→kg 出成 0.98–0.98鸡胸粒」。
 *    纯中文的键本来就是给人看的, 一个字都不用换。
 *    ⚠️ 这个缺陷单测和 tsc 都是绿的, 只有把六屏点一遍才看得见。
 */
export function humanizeKeys(text: string, planned?: PlannedChange[] | null): string {
  if (!text || !planned || !planned.length) return text
  let out = text
  const pairs = planned
    .map((p) => [String(p.key ?? ''), headOfDetail(String(p.detail ?? ''))] as const)
    .filter(([k, name]) => k && name && k !== name && /[A-Za-z]/.test(k))
    .sort((a, b) => b[0].length - a[0].length)
  for (const [code, name] of pairs) {
    out = out.split(code).join(name)
  }
  return out
}

// ==================== 段③ 复核 ====================

/**
 * 建厂复核快照 —— 「当时说的」+「当时做的」。
 *
 * ⛔ 后端读的是**快照**, 不重算预览: 库已经被那次 apply 改过了,
 *    重算出来每一条都会变成「已存在/跳过」, 与客户当时确认的那份对不上。
 *
 * ⚠️ `result` 为 null 有**两种**情况, 界面必须分开说:
 *    ① 这个工厂还没成功建过厂; ② 那次快照存在补 `result_json` 这一列**之前**(老行)。
 *    两者都**不是**「建厂什么都没写」——把它显示成「写了 0 条」就是编了一个读数。
 */
export interface FactoryBuildReview {
  planned: PlannedChange[]
  plannedByGroup: Record<string, number>
  result: ApplyAIInitResult | null
}

export function getFactoryBuildReview(factoryId: string) {
  return request.get<unknown, Envelope<FactoryBuildReview>>(
    `/api/platform/factory-assembly/factories/${encodeURIComponent(factoryId)}/ai-initialize/review`,
    ABS
  )
}

/**
 * 「现在库里的」那一份 —— 复核三份对账的第三份。
 *
 * ⛔ 后端**不给**这一份(它只给快照两份), 六类各自去查自己的列表端点 ——
 *    这是设计卡 §二 定的分工, 不是漏了。
 *
 * ⚠️ 返回 `null` 表示**这一类没查到**(端点 404/500/没权限), ⛔ 不是 0 条。
 *    把查不到显示成 0 会让对账区报出一个不存在的缺口。
 *
 * ⚠️ `_silent`: 失败由复核页自己那一列如实显示成「查不到」, ⛔ 不再弹一个
 *    全局红条 —— 那条消息说的是字段名(`缺少必要参数: productTypeId`),
 *    对着复核页的客户没有任何意义。
 */
const SILENT = { baseURL: '', _silent: true } as const

async function rowsOfPath(factoryId: string, path: string): Promise<unknown[] | null> {
  try {
    const r = await request.get<unknown, unknown>(
      `/api/mobile/${encodeURIComponent(factoryId)}/${path}`, SILENT)
    const d = (r as { data?: unknown })?.data
    const rows = Array.isArray(d) ? d
      : ((d as { content?: unknown[] })?.content
        ?? (d as { list?: unknown[] })?.list
        ?? (d as { records?: unknown[] })?.records)
    return Array.isArray(rows) ? rows : null
  } catch {
    return null
  }
}

export async function countLiveRows(factoryId: string, path: string): Promise<number | null> {
  const rows = await rowsOfPath(factoryId, path)
  return rows === null ? null : rows.length
}

/**
 * Workflow 画布按产品数据库 id 打开，而建厂建议使用稳定的产品编码。
 * 这里在写入完成后只读解析一次，避免把 code 当 id 导航到空画布。
 */
export async function resolveProductTypeIdByCode(
  factoryId: string,
  productCode: string,
): Promise<string | null> {
  const products = await rowsOfPath(factoryId, 'product-types')
  if (products === null) return null
  const matched = (products as Array<Record<string, unknown>>).find(
    (product) => String(product.code ?? '') === productCode,
  )
  const id = String(matched?.id ?? '')
  return id || null
}

/**
 * 产品工序链的「现在库里的」—— **只能按产品逐个查**，返回的是**有链的产品数**。
 *
 * ⚠️ 单位必须与另外两列一致(都是「几条链」)，⛔ 不是「几行」——
 *    一个产品走 4 道工序在库里是 4 行，按行数会显示成「库里 4 条」而预览说「1 条」。
 *
 * 🔴 2026-08-17 真实浏览器实测: `GET /api/mobile/{f}/product-work-processes`
 *    **必须**带 `productTypeId`(`@RequestParam String productTypeId`), 全仓
 *    没有「列出这家厂全部工序链」的端点 —— 不带参数时后端回
 *    `缺少必要参数: productTypeId`, 复核页那一列会显示成「查不到」。
 *
 * ⛔ 不为此新造一个后端端点(那超出段③「只导航不写库」的范围), 也⛔ 不把
 *    「查不到」当成 0 —— 改成**扇出**: 先取这家厂的产品类型, 再逐个查它的链。
 *
 * ⚠️ 代价说清楚: 这是 N+1 次请求(N = 产品类型数)。复核页一次只开一屏、
 *    产品类型是几十条量级, 可以接受; 哪天产品上千要换成后端聚合。
 */
export async function countProductProcessRows(factoryId: string): Promise<number | null> {
  const products = await rowsOfPath(factoryId, 'product-types')
  if (products === null) return null
  const ids = (products as Array<Record<string, unknown>>)
    .map((p) => String(p.id ?? '')).filter(Boolean)
  const counts = await Promise.all(ids.map((id) =>
    rowsOfPath(factoryId, `product-work-processes?productTypeId=${encodeURIComponent(id)}`)))
  // ⛔ 任一条查不到就整体报「查不到」—— 把失败的那几个当 0 加起来会得出一个
  //    偏小的数, 而它长得完全像一个正常读数。
  if (counts.some((c) => c === null)) return null
  // 🔴 2026-08-18 真实浏览器实测(工厂超管账号, 第 4 屏): 三列写着
  //    「说要写 1 条 / 写入 1 条 / **现在库里 4 条**」——
  //    前两列数的是**链**(一个产品一条), 这一列原来数的是**行**
  //    (那一个产品走 4 道工序 = 4 行)。数没错, **单位不是同一个**,
  //    而这一屏的全部意义就是「三份对得上」。⇒ 这里也按链数。
  return counts.reduce((n, c) => n + ((c as unknown[]).length > 0 ? 1 : 0), 0)
}

// ==================== 工厂列表 (页面自带选择器) ====================

/**
 * ⚠️ web-admin 目前**没有工厂列表页**(2026-08-10 实测: router 里没有
 * `path: 'factories'`, views 下也没有对应组件)。所以本页不能只靠 `?factoryId=`
 * 进入 —— 那样用户没有任何入口, 页面本身就是防呆 Rule 5 要禁的死路。
 * 这里直接读平台侧的工厂列表, 让页面自足。
 */
export interface FactoryBrief {
  id: string
  name: string
  industryCode?: string
  isActive?: boolean
}

export async function listFactories(): Promise<FactoryBrief[]> {
  const r = await request.get<unknown, { success: boolean; data: unknown }>(
    '/api/platform/factories', ABS
  )
  const d = r?.data as unknown
  const rows = Array.isArray(d)
    ? d
    : ((d as { list?: unknown[]; records?: unknown[]; content?: unknown[] })?.list
      ?? (d as { records?: unknown[] })?.records
      ?? (d as { content?: unknown[] })?.content
      ?? [])
  return (rows as Array<Record<string, unknown>>)
    .map((x) => ({
      id: String(x.id ?? ''),
      name: String(x.name ?? x.factoryName ?? ''),
      industryCode: x.industryCode ? String(x.industryCode) : undefined,
      isActive: x.isActive !== false,
    }))
    .filter((f) => f.id)
}
