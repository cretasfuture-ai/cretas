/**
 * 销售发货行的批次分配 —— App 侧。
 *
 * 🔴 2026-08-20 新建。后端这套接口一直都在（`SalesDeliveryBatchAllocationController`），
 * 只有网页端接了；App 上仓管做发货确认被「还有 N 行未分配批次」挡住时，
 * 界面只能告诉他「去网页端做」—— 而他站在货堆边，手上只有手机。
 *
 * ⚠️ 后端那两个写端点原来只认 `sales:read_write`，仓储主管是 `sales=r`，
 * 直接接会 403。已同步把权限放宽为「sales:read_write 或 warehouse:read_write」，
 * 理由见控制器里的说明（分配批次本质是仓库动作）。
 */
import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/** 与 warehouseReceivingApiClient 同一形状（该文件里也是本地声明的）。 */
interface ApiResponse<T> {
  success: boolean;
  message?: string;
  data?: T;
}

/** FIFO 推荐出来的一个候选批次。 */
export interface FifoBatchCandidate {
  batchId?: string;
  finishedGoodsBatchId?: string;
  batchNumber?: string;
  productionDate?: string;
  availableQuantity?: number;
  unit?: string;
  [k: string]: unknown;
}

export interface BatchAllocationInput {
  finishedGoodsBatchId: string;
  quantity: number;
}

export interface DeliveryBatchAllocation {
  id: string;
  deliveryItemId: string;
  finishedGoodsBatchId: string;
  batchNumber: string;
  allocatedQty: number;
  unit?: string;
}

class SalesDeliveryBatchAllocationApiClient {
  private base(deliveryItemId: string, factoryId?: string): string {
    const fid = getCurrentFactoryId(factoryId);
    if (!fid) throw new Error('factoryId 是必需的，请先登录');
    return `/api/mobile/${fid}/sales-deliveries/items/${deliveryItemId}/batch-allocations`;
  }

  /** 已保存的真实批次分配；发货历史只能读这一份，不能重新跑 FIFO 推荐。 */
  async list(
    deliveryItemId: string,
    factoryId?: string,
  ): Promise<ApiResponse<DeliveryBatchAllocation[]>> {
    return apiClient.get<ApiResponse<DeliveryBatchAllocation[]>>(
      this.base(deliveryItemId, factoryId),
    );
  }

  /** FIFO 推荐（按生产日期升序）—— 手机端「一键分配」的数据来源。 */
  async recommendFifo(
    deliveryItemId: string,
    params: { productTypeId: string; requiredQty: number; unit?: string; sourceWarehouseCode?: string },
    factoryId?: string,
  ): Promise<ApiResponse<FifoBatchCandidate[]>> {
    return apiClient.get<ApiResponse<FifoBatchCandidate[]>>(
      `${this.base(deliveryItemId, factoryId)}/recommend-fifo`,
      { params },
    );
  }

  /** 写入分配（后端语义：先清空再写入）。 */
  async allocate(
    deliveryItemId: string,
    allocations: BatchAllocationInput[],
    factoryId?: string,
  ): Promise<ApiResponse<{ deliveryItemId: string; fullyAllocated: boolean }>> {
    return apiClient.post<ApiResponse<{ deliveryItemId: string; fullyAllocated: boolean }>>(
      this.base(deliveryItemId, factoryId),
      { allocations },
    );
  }
}

export const salesDeliveryBatchAllocationApiClient = new SalesDeliveryBatchAllocationApiClient();
export default salesDeliveryBatchAllocationApiClient;
