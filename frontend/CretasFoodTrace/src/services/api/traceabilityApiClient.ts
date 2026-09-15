import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';
import axios, { isAxiosError } from 'axios';
import { API_BASE_URL } from '../../constants/config';

/**
 * 溯源API客户端
 * 路径：/api/mobile/{factoryId}/traceability/* 和 /api/public/trace/*
 *
 * 业务场景：提供产品全链路溯源查询，支持消费者扫码查询
 */

// ========== 类型定义 ==========

export interface ProductionInfo {
  batchNumber: string;
  productName: string;
  productType: string;
  productionDate: string;
  completionTime?: string;
  supervisorName?: string;
  equipmentName?: string;
  quantity?: number;
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
  inspectionDate: string;
  inspectorName: string;
  result: string;
  passRate?: number;
  conclusion?: string;
  remarks?: string;
}

export interface ShipmentInfo {
  shipmentNumber: string;
  shipmentDate: string;
  customerName: string;
  logisticsCompany?: string;
  trackingNumber?: string;
  status: string;
  quantity?: number;
  unit?: string;
}

export interface BatchTraceResponse {
  production: ProductionInfo;
  materialCount: number;
  inspectionCount: number;
  shipmentCount: number;
  qualityStatus: string;
  lastUpdateTime: string;
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
  lossRate?: number;
  passRate?: number;
  temperature?: number;
  equipmentName?: string;
  operatorName?: string;
}

// ========== 多跳血缘（来源链 / 影响面） ==========

/** 血缘图上的一个节点。 */
/**
 * 血缘节点上的源头个体摘要（这块肉来自哪头牛/鸭/猪）。
 *
 * ⚠️ 脱敏由**后端**决定，前端只负责显示：
 * 公开接口上 `individualCode` 恒为 null、`tagNumber` 只有末 4 位（`tagNumberMasked=true`）。
 *
 * ⛔ 这个类型上**根本没有** externalGeneBankId / dnaSampleCode / sire / dam 字段 ——
 * 后端 DTO 也没有。字段不存在比「记得不要显示」可靠。
 */
export interface SourceIndividualBrief {
  /** 内部个体编码；公开接口恒为 null。 */
  individualCode?: string | null;
  /** 耳标号；`tagNumberMasked=true` 时只有末 4 位（如 `*********7788`）。 */
  tagNumber?: string | null;
  /** 物种（牛/鸭/猪…）。是**字段值**不是模型名 —— 本功能对所有物种通用。 */
  species?: string | null;
  breed?: string | null;
  originFarm?: string | null;
  /** UNSAMPLED / SAMPLED / MATCHED / MISMATCH。 */
  dnaVerificationStatus?: string | null;
  /**
   * 上面的 tagNumber 是不是被遮过。
   * 没有它就分不清 `****7788` 是脱敏结果还是耳标本身，也就不知道能不能拿它去检索。
   */
  tagNumberMasked?: boolean | null;
}

export interface LineageNode {
  nodeType: 'MATERIAL_BATCH' | 'PRODUCTION_BATCH' | 'FINISHED_BATCH' | 'SHIPMENT_RECORD';
  /**
   * 内部批次主键。⚠️ **公开（消费者）接口恒为 null** —— 它是数据库主键，
   * PRODUCTION_BATCH 那一类还是顺序自增整数，泄漏出去可枚举。
   * ⛔ 不要拿它当展示文案的兜底，用 batchNumber。
   */
  nodeId?: string | null;
  batchNumber?: string | null;
  /** 物料名 / 产品名 / 客户名。⚠️ resolved=false 时恒为 null —— 那是「查不到」不是「没名字」。 */
  name?: string | null;
  /** 仅原料节点；公开（消费者）接口恒为 null。 */
  supplierName?: string | null;
  quantity?: number | null;
  unit?: string | null;
  eventTime?: string | null;
  /**
   * 批次实体是否还查得到。血缘边是 forensic 的（永不删），批次却可能被软删，
   * 所以这里会有 false。⛔ 前端不要给它编一个「未知批次」的名字。
   */
  resolved?: boolean;
  /**
   * 源头个体。只有 MATERIAL_BATCH 且该批次登记了个体时才有。
   *
   * ⚠️ `null` = 这批原料没登记个体（调料/包材本来就没有），
   * ⛔ 不要兜底成「未知个体」—— 那会把「没有这回事」说成「信息缺失」。
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
  /** 下游；公开接口为 null（本接口不提供），不是空数组。 */
  descendants?: LineageDepthGroup[] | null;
  maxDepth: number;
  nodeCount: number;
  /**
   * 上游链上**去重后**的源头个体数（「本批来自 N 头牛」里的 N）。
   *
   * 🔴 **由后端算**：公开接口上 individualCode 是 null、tagNumber 被遮成末 4 位，
   * 前端拿脱敏值去重会把「末 4 位相同的两头牛」数成一头 —— 只会少报，
   * 而「来自几头牛」少报一头在溯源语境里就是假话。
   * ⛔ 不要在前端重新算这个数。
   */
  sourceIndividualCount?: number | null;
}

/** 召回影响面。 */
export interface ImpactResponse {
  rootType: string;
  rootId: string;
  productionBatches: LineageNode[];
  finishedBatches: LineageNode[];
  shipments: LineageNode[];
  productionBatchCount: number;
  finishedBatchCount: number;
  shipmentCount: number;
  maxDepth: number;
  queryTime: string;
}

export interface FullTraceResponse {
  production: ProductionInfo;
  materials: MaterialInfo[];
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

export interface PublicMaterialInfo {
  materialType: string;
  origin: string;
  receiptDate: string;
}

export interface PublicQualityInfo {
  inspectionDate: string;
  result: string;
  passRate?: number;
}

export interface PublicTraceResponse {
  productName: string;
  batchNumber: string;
  productionDate?: string;
  factoryName: string;
  qualityStatus: string;
  certificationInfo?: string;
  materials: PublicMaterialInfo[];
  qualityInspection?: PublicQualityInfo;
  /** 已打印标签上的溯源码; 还没打过标签为 null(后端不再现造扫不回来的码)。 */
  traceCode: string | null;
  queryTime: string;
  isValid: boolean;
  message: string;
  /** 多跳来源链（脱敏：只有 ancestors，节点上不带供应商）。语义同 FullTraceResponse.lineage。 */
  lineage?: LineageSection | null;
}

// ========== API客户端类 ==========

class TraceabilityApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/traceability`;
  }

  /**
   * 1. 获取基础溯源信息（批次级别）
   * GET /api/mobile/{factoryId}/traceability/batch/{batchNumber}
   * 需要认证
   */
  async getBatchTrace(
    batchNumber: string,
    factoryId?: string
  ): Promise<BatchTraceResponse | null> {
    try {
      const response = await apiClient.get<any>(
        `${this.getPath(factoryId)}/batch/${batchNumber}`
      );
      return response.data || response;
    } catch (error) {
      if (isAxiosError(error) && error.response?.status === 404) {
        return null;
      }
      throw error;
    }
  }

  /**
   * 2. 获取完整溯源链路
   * GET /api/mobile/{factoryId}/traceability/full/{batchNumber}
   * 需要认证
   */
  async getFullTrace(
    batchNumber: string,
    factoryId?: string
  ): Promise<FullTraceResponse | null> {
    try {
      const response = await apiClient.get<any>(
        `${this.getPath(factoryId)}/full/${batchNumber}`
      );
      return response.data || response;
    } catch (error) {
      if (isAxiosError(error) && error.response?.status === 404) {
        return null;
      }
      throw error;
    }
  }

  /**
   * 2.5 召回影响面查询
   * GET /api/mobile/{factoryId}/traceability/impact/{batchType}/{batchId}
   * 需要认证。
   *
   * ⛔ 这里**不吞异常**：召回场景下「没有波及」和「没查成」是天差地别的两件事，
   * 后端也不会返回全 0 空壳。调用方必须自己决定怎么把失败告诉用户。
   */
  async getImpact(
    batchType: 'MATERIAL_BATCH' | 'PRODUCTION_BATCH' | 'FINISHED_BATCH' | 'SHIPMENT_RECORD',
    batchId: string,
    options?: { maxDepth?: number; factoryId?: string }
  ): Promise<ImpactResponse> {
    const maxDepth = options?.maxDepth ?? 5;
    const response = await apiClient.get<any>(
      `${this.getPath(options?.factoryId)}/impact/${batchType}/${encodeURIComponent(batchId)}?maxDepth=${maxDepth}`
    );
    return response.data || response;
  }

  /**
   * 3. 公开溯源查询（消费者扫码，无需认证）
   * GET /api/public/trace/{batchNumber}
   */
  async getPublicTrace(batchNumber: string): Promise<PublicTraceResponse> {
    try {
      // 公开接口不需要认证，直接使用axios
      const response = await axios.get(
        `${API_BASE_URL}/api/public/trace/${batchNumber}`
      );
      return response.data?.data || response.data;
    } catch (error: unknown) {
      console.error('[TraceabilityApiClient] 公开溯源查询失败:', error);
      const axiosErr = error as { response?: { data?: { message?: string } } };
      return {
        productName: '',
        batchNumber: batchNumber,
        factoryName: '',
        qualityStatus: '',
        materials: [],
        traceCode: '',
        queryTime: new Date().toISOString(),
        isValid: false,
        message: axiosErr.response?.data?.message || '溯源查询失败'
      };
    }
  }

  /**
   * 4. 通过溯源码查询（消费者扫码，无需认证）
   * GET /api/public/trace/code/{traceCode}
   */
  async getTraceByCode(traceCode: string): Promise<PublicTraceResponse> {
    try {
      const response = await axios.get(
        `${API_BASE_URL}/api/public/trace/code/${traceCode}`
      );
      return response.data?.data || response.data;
    } catch (error: unknown) {
      console.error('[TraceabilityApiClient] 溯源码查询失败:', error);
      const axiosErr = error as { response?: { data?: { message?: string } } };
      return {
        productName: '',
        batchNumber: '',
        factoryName: '',
        qualityStatus: '',
        materials: [],
        traceCode: traceCode,
        queryTime: new Date().toISOString(),
        isValid: false,
        message: axiosErr.response?.data?.message || '无效的溯源码'
      };
    }
  }
}

export const traceabilityApiClient = new TraceabilityApiClient();
export default traceabilityApiClient;
