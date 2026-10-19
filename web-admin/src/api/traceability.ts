import { get } from './request';

/**
 * 溯源查询 —— 一条批次从源头个体到出货的完整链路。
 *
 * 后端: `GET /api/mobile/{factoryId}/traceability/full/{batchNumber}`
 * (axios baseURL 已是 `/api/mobile`, 所以这里的路径从 `/{factoryId}/...` 开始)
 *
 * ⚠️ 下面的类型逐字对着手机端
 * `frontend/CretasFoodTrace/src/services/api/traceabilityApiClient.ts` 抄,
 * ⛔ 不自己发明字段名 —— 两端读的是同一个后端 DTO, 名字漂了就是形态 D。
 *
 * ⚠️ 「批次不存在」在后端是 **HTTP 200 + `{success:false, code:404}`**
 * (TraceabilityController#getFullTrace), 不是 HTTP 404。经 `request.ts` 拦截器
 * 之后两种形状都会变成 `ApiError`, 但字段落点不同:
 *   · 200 + success:false ⇒ `ApiError(message, code=404, status=undefined)`
 *   · 真 HTTP 404         ⇒ `ApiError(message, code='NOT_FOUND', status=404)`
 * 判「未找到」用 {@link isTraceNotFound}, ⛔ 别只看 `status`。
 */

export interface ProductionInfo {
  batchId?: number | null;
  batchNumber: string;
  productName: string;
  productType: string;
  productionDate: string;
  completionTime?: string;
  supervisorName?: string;
  equipmentName?: string;
  quantity?: number;
  goodQuantity?: number | null;
  defectQuantity?: number | null;
  unit?: string;
  qualityStatus: string;
  factoryName: string;
  factoryId: string;
}

export interface MaterialInfo {
  batchNumber: string;
  materialName: string;
  materialType: string;
  supplierName: string;
  supplierCode?: string;
  receiptDate: string;
  expireDate?: string;
  quantity?: number;
  unit?: string;
  storageLocation?: string;
  status: string;
}

export interface QualityInfo {
  inspectionId: string;
  inspectorId?: number | null;
  inspectionDate: string | null;
  inspectorName: string | null;
  result: string;
  passRate?: number;
  sampleSize?: number | null;
  passCount?: number | null;
  failCount?: number | null;
  conclusion?: string;
  remarks?: string;
}

export interface ShipmentInfo {
  shipmentNumber: string;
  shipmentDate: string | null;
  plannedDeliveryDate?: string | null;
  shippedAt?: string | null;
  shipmentTimeSource?: 'LINEAGE_EVENT' | 'LEGACY_DATE_ONLY' | 'UNRECORDED';
  customerName: string;
  logisticsCompany?: string;
  trackingNumber?: string;
  status: string;
  quantity?: number;
  unit?: string;
}

export interface ProcessingStageInfo {
  stageType: string;
  stageName: string;
  stageOrder: number;
  startTime?: string;
  endTime?: string;
  durationMinutes?: number;
  inputWeight?: number;
  outputWeight?: number;
  inputUnit?: string | null;
  outputUnit?: string | null;
  lossRate?: number;
  passRate?: number;
  temperature?: number;
  equipmentName?: string;
  operatorName?: string;
}

// ========== 多跳血缘（来源链） ==========

/**
 * 血缘节点上的源头个体摘要（这块肉来自哪头牛/鸭/猪）。
 *
 * ⛔ 这个类型上**根本没有** externalGeneBankId / dnaSampleCode / sire / dam 字段 ——
 * 后端 DTO 也没有。字段不存在比「记得不要显示」可靠。
 */
export interface SourceIndividualBrief {
  /**
   * 个体档案主键 —— 内部溯源页拿它打开「个体抽屉」。**公开接口恒为 null**。
   * 有它这一行才可点; ⛔ 不要拿 individualCode 兜底。
   */
  id?: string | null;
  individualCode?: string | null;
  /** 耳标号；`tagNumberMasked=true` 时只有末 4 位。 */
  tagNumber?: string | null;
  /** 物种（牛/鸭/猪…）。是**字段值**不是模型名。 */
  species?: string | null;
  breed?: string | null;
  originFarm?: string | null;
  /** UNSAMPLED / SAMPLED / MATCHED / MISMATCH。 */
  dnaVerificationStatus?: string | null;
  tagNumberMasked?: boolean | null;
}

export type LineageNodeType =
  | 'MATERIAL_BATCH'
  | 'PRODUCTION_BATCH'
  | 'FINISHED_BATCH'
  | 'SHIPMENT_RECORD';

export interface LineageNode {
  nodeType: LineageNodeType;
  /** 内部批次主键。⛔ 不要拿它当展示文案的兜底，用 batchNumber。 */
  nodeId?: string | null;
  batchNumber?: string | null;
  /** 物料名 / 产品名 / 客户名。⚠️ resolved=false 时恒为 null。 */
  name?: string | null;
  /** 仅原料节点。 */
  supplierName?: string | null;
  quantity?: number | null;
  unit?: string | null;
  eventTime?: string | null;
  /** 批次实体是否还查得到（血缘边永不删，批次却可能被软删）。 */
  resolved?: boolean;
  /**
   * 源头个体。只有 MATERIAL_BATCH 且该批次登记了个体时才有。
   * `null` = 这批原料没登记个体（调料/包材本来就没有），⛔ 不要兜底成「未知个体」。
   */
  sourceIndividual?: SourceIndividualBrief | null;
}

/** 按跳数分组的一层（1 = 直接上游，2 = 隔一跳…）。 */
export interface LineageDepthGroup {
  depth: number;
  nodes: LineageNode[];
}

export interface LineageSection {
  rootType: string;
  rootId: string;
  /** 上游，depth 由近到远。空数组 = 确实没有上游血缘记录。 */
  ancestors: LineageDepthGroup[];
  descendants?: LineageDepthGroup[] | null;
  maxDepth: number;
  nodeCount: number;
  /**
   * 上游链上**去重后**的源头个体数（「本批来自 N 头牛」里的 N）。
   * 🔴 由后端算, ⛔ 不要在前端重新算这个数。
   */
  sourceIndividualCount?: number | null;
}

export interface FullTraceResponse {
  production: ProductionInfo;
  materials: MaterialInfo[];
  /** 同组主批次记录共用投入；材料数量不得当作本产品独占耗用。 */
  sharedInputSourceBatchNumber?: string | null;
  processingStages?: ProcessingStageInfo[];
  qualityInspections: QualityInfo[];
  shipments: ShipmentInfo[];
  /** 已打印标签上的溯源码; 还没打过标签为 null(后端不再现造扫不回来的码)。 */
  traceCode: string | null;
  queryTime: string;
  /**
   * 多跳血缘。
   * ⚠️ `null`/`undefined` = **本次没拿到**（血缘引擎不可用）；
   * `ancestors: []` = **确实没有上游血缘记录**。两者在 UI 上是不同的话，⛔ 别合并。
   */
  lineage?: LineageSection | null;
}

/**
 * 完整溯源链路。
 *
 * `_silent: true`: 拦截器不弹 toast —— 本页自己在页面内把「未找到批次 X」/错误原话
 * 显示出来, 弹一个一模一样的 toast 只是噪音。
 */
export function getFullTrace(factoryId: string, batchNumber: string) {
  return get<FullTraceResponse>(
    `/${factoryId}/traceability/full/${encodeURIComponent(batchNumber)}`,
    { _silent: true }
  );
}

/**
 * 「批次不存在」判据 —— 见文件头: 两种形状都要认。
 * 入参是 `unknown` 而不是 `ApiError`, 因为调用方拿到的是 catch 出来的东西。
 */
export function isTraceNotFound(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const e = error as { status?: unknown; code?: unknown };
  return e.status === 404 || String(e.code) === '404' || e.code === 'NOT_FOUND';
}
