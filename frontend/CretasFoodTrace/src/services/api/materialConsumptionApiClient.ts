import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 原材料消耗记录API客户端
 * 路径：/api/mobile/{factoryId}/processing/material-consumptions/*
 *
 * 后端控制器：MaterialConsumptionController.java
 *
 * 已实现端点（7个）：
 * 1. GET  /processing/material-consumptions         - 列表（分页）
 * 2. GET  /processing/material-consumptions/{id}    - 详情
 * 3. GET  /processing/material-consumptions/batch/{productionBatchId} - 按生产批次查询
 * 4. GET  /processing/material-consumptions/material-batch/{batchId}  - 按原材料批次查询
 * 5. GET  /processing/material-consumptions/time-range - 按时间范围查询
 * 6. GET  /processing/material-consumptions/stats   - 消耗统计
 * 7. GET  /processing/material-consumptions/batch/{productionBatchId}/cost - 批次成本汇总
 *
 * 创建消耗记录请使用：processingApiClient.recordMaterialConsumption()
 */

export interface ApiResponse<T> {
  success: boolean;
  code: number;
  message: string;
  data: T;
}

export interface MaterialConsumption {
  id: number;
  factoryId: string;
  productionPlanId?: string;
  productionBatchId?: string;
  batchId: string;
  batchNumber?: string;
  materialTypeName?: string;
  quantity: number;
  unitPrice: number;
  totalCost: number;
  consumptionTime: string;
  consumedAt?: string;
  recordedBy: number;
  recorderName?: string;
  notes?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface ConsumptionQueryParams {
  factoryId?: string;
  productionBatchId?: string;
  batchId?: string;
  startDate?: string;
  endDate?: string;
  page?: number;
  size?: number;
}

export interface ConsumptionStats {
  totalQuantity: number;
  totalCost: number;
  consumptionCount: number;
  byMaterialType?: Array<{
    materialTypeName: string;
    quantity: number;
    cost: number;
  }>;
}

export interface MaterialConsumptionSummaryItem {
  materialTypeName: string;
  plannedQuantity: number;
  actualQuantity: number;
  variance: number;
  /** 没有计划量时为 null —— **不是 0**。渲染成「—」，⛔ 不要 `?? 0`。 */
  achievementRate: number | null;
}

// 后端原始返回格式
interface RawSummaryItem {
  materialTypeId?: string;
  materialTypeName: string;
  plannedQty: number;
  actualQty: number;
  variancePercent?: number;
  unitPrice?: number;
  cost?: number;
}

interface RawBatchConsumptionSummary {
  items: RawSummaryItem[];
  totalPlannedCost: number;
  totalActualCost: number;
  /** 后端在没有计划成本时下发 null（2026-08-28 起）——⛔ 不再兜底成 100。 */
  overallAchievementRate: number | null;
}

export interface BatchConsumptionSummary {
  /**
   * 没有计划数据时为 null —— **不是 0，也不是 100**。
   *
   * 🔴 2026-08-28 逐屏走查: 后端原本在「没有计划成本」时返回 **100**，
   * 于是 web-admin 的「BOM达成率分析」把 8 个批次里 7 个**没有计划数据**的
   * 显示成「达成率 100%」，顶部四张 KPI 卡也全由这些 100 推出来
   * ——「8 个批次执行完美、零超耗」。已改为下发 null。
   *
   * ⛔ 这一侧不要再兜底：`|| 0` 只是把「满分」的谎换成「零分」的谎。
   */
  overallAchievementRate: number | null;
  totalPlannedQuantity: number;
  totalActualQuantity: number;
  materials: MaterialConsumptionSummaryItem[];
}

function transformSummary(raw: RawBatchConsumptionSummary): BatchConsumptionSummary {
  const materials = (raw.items || []).map(item => ({
    materialTypeName: item.materialTypeName,
    plannedQuantity: item.plannedQty || 0,
    actualQuantity: item.actualQty || 0,
    variance: (item.actualQty || 0) - (item.plannedQty || 0),
    // 没有计划量 ⇒ 达成率没有定义。⛔ 不是 0 —— 0 会被读成「一点都没做到」。
    achievementRate: item.plannedQty > 0 ? (item.actualQty / item.plannedQty) * 100 : null,
  }));
  const totalPlanned = materials.reduce((sum, m) => sum + m.plannedQuantity, 0);
  const totalActual = materials.reduce((sum, m) => sum + m.actualQuantity, 0);
  return {
    overallAchievementRate: raw.overallAchievementRate ?? null,
    totalPlannedQuantity: totalPlanned,
    totalActualQuantity: totalActual,
    materials,
  };
}

class MaterialConsumptionApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/processing/material-consumptions`;
  }

  // 1. 获取消耗记录列表（分页）
  async getConsumptions(params?: ConsumptionQueryParams): Promise<ApiResponse<MaterialConsumption[]>> {
    const { factoryId, ...query } = params || {};
    return await apiClient.get(this.getPath(factoryId), { params: query });
  }

  // 2. 获取单条消耗记录详情
  async getConsumptionById(id: number, factoryId?: string): Promise<ApiResponse<MaterialConsumption>> {
    return await apiClient.get(`${this.getPath(factoryId)}/${id}`);
  }

  // 3. 获取生产批次的消耗记录
  async getConsumptionsByBatch(productionBatchId: string, factoryId?: string): Promise<ApiResponse<MaterialConsumption[]>> {
    return await apiClient.get(`${this.getPath(factoryId)}/batch/${productionBatchId}`);
  }

  // 4. 获取原材料批次的消耗记录
  async getConsumptionsByMaterialBatch(batchId: string, factoryId?: string): Promise<ApiResponse<MaterialConsumption[]>> {
    return await apiClient.get(`${this.getPath(factoryId)}/material-batch/${batchId}`);
  }

  // 5. 获取时间范围内的消耗记录
  async getConsumptionsByTimeRange(startDate: string, endDate: string, factoryId?: string): Promise<ApiResponse<MaterialConsumption[]>> {
    return await apiClient.get(`${this.getPath(factoryId)}/time-range`, {
      params: { startDate, endDate }
    });
  }

  // 6. 获取消耗统计
  async getConsumptionStats(params?: { productionBatchId?: string; startDate?: string; endDate?: string; factoryId?: string }): Promise<ApiResponse<ConsumptionStats>> {
    const { factoryId, ...query } = params || {};
    return await apiClient.get(`${this.getPath(factoryId)}/stats`, { params: query });
  }

  // 7. 获取生产批次的消耗成本汇总
  async getBatchConsumptionCost(productionBatchId: string, factoryId?: string): Promise<ApiResponse<{ totalCost: number; totalQuantity: number }>> {
    return await apiClient.get(`${this.getPath(factoryId)}/batch/${productionBatchId}/cost`);
  }

  // 8. 获取生产批次的消耗汇总（BOM达成率）
  async getBatchConsumptionSummary(batchId: string, factoryId?: string): Promise<ApiResponse<BatchConsumptionSummary>> {
    const raw: ApiResponse<RawBatchConsumptionSummary> = await apiClient.get(`${this.getPath(factoryId)}/batch/${batchId}/summary`);
    if (raw.success && raw.data) {
      return { ...raw, data: transformSummary(raw.data) };
    }
    // ⚠️ 请求失败时 **不要**返回 0：那会让「查不到」和「达成率 0%」变成同一个读数,
    //   而这两件事对看屏的人完全不同。null ⇒ 屏上渲染「—」。
    return { ...raw, data: { overallAchievementRate: null, totalPlannedQuantity: 0, totalActualQuantity: 0, materials: [] } };
  }
}

export const materialConsumptionApiClient = new MaterialConsumptionApiClient();
