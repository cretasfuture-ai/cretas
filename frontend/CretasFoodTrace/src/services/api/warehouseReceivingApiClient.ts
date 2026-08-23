import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

export type UnorderedInboundReason = 'CUSTOMER_MATERIAL' | 'GIFT' | 'OTHER';

export interface CustomerMaterialArrivalTask {
  taskId: string;
  sourceType: 'CUSTOMER_MATERIAL_ARRIVAL';
  sourceId: string;
  sourceNumber: string;
  inboundReason: UnorderedInboundReason;
  customerId?: string | null;
  customerName?: string | null;
  expectedArrivalAt?: string | null;
  status: 'OPEN' | 'PARTIALLY_RECEIVED';
  statusLabel: string;
  warehouseId?: string | null;
  warehouseName?: string | null;
  responsibleName?: string | null;
  activeReceiptId?: string | null;
  activeReceiptNumber?: string | null;
  activeReceiptCount: number;
  receiptConflict: boolean;
  items: unknown[];
}

export interface WarehouseReceivingTaskFilters {
  arrivalNoticeId?: string;
  sourceType?: 'CUSTOMER_MATERIAL_ARRIVAL';
}

export interface CreateCustomerMaterialArrivalReceiptRequest {
  idempotencyKey: string;
  materialTypeId: string;
  warehouseId: string;
  receivedQuantity: number;
  unit: string;
  externalBatchNumber?: string;
  notes?: string;
  completeNotice: boolean;
}

export interface MaterialBatchReceiptResult {
  id: string;
  batchNumber?: string | null;
  quantity?: number | null;
  currentQuantity?: number | null;
  unit?: string | null;
}

interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
}


/**
 * 提前关单的原因码 —— 与后端 / web-admin 保持同一套。
 * ⚠️ 取值来自 `web-admin/src/api/purchaseReceive.ts` 的 `PurchaseReceivingCloseReason`，
 * ⛔ 不要在 App 侧另造一套（同一个东西有两份，它一定会漂）。
 */
export type PurchaseReceivingCloseReason =
  | 'SUPPLIER_SHORT_SHIPMENT'
  | 'QUALITY_REJECTION'
  | 'PURCHASE_BALANCE_CANCELLED'
  | 'DEMAND_CHANGED'
  | 'OTHER';

export interface ClosePurchaseReceivingTaskRequest {
  reasonCode: PurchaseReceivingCloseReason;
  notes?: string;
}

class WarehouseReceivingApiClient {
  private getPath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录');
    }
    return `/api/mobile/${currentFactoryId}/warehouse/receiving`;
  }

  async listCustomerMaterialArrivalTasks(
    filters?: WarehouseReceivingTaskFilters,
    factoryId?: string,
  ): Promise<ApiResponse<CustomerMaterialArrivalTask[]>> {
    return apiClient.get<ApiResponse<CustomerMaterialArrivalTask[]>>(
      `${this.getPath(factoryId)}/tasks`,
      {
        params: {
          arrivalNoticeId: filters?.arrivalNoticeId || undefined,
          sourceType: 'CUSTOMER_MATERIAL_ARRIVAL',
        },
      },
    );
  }

  async receiveCustomerMaterialArrival(
    noticeId: string,
    payload: CreateCustomerMaterialArrivalReceiptRequest,
    factoryId?: string,
  ): Promise<ApiResponse<MaterialBatchReceiptResult>> {
    return apiClient.post<ApiResponse<MaterialBatchReceiptResult>>(
      `${this.getPath(factoryId)}/arrival-notices/${noticeId}/receipts`,
      payload,
    );
  }
  /**
   * 提前关单（收货未达预计数量时主动结束）。
   *
   * <p>后端在 M-14/M-15 那轮就做好了，App 侧一直没接 —— 仓管在手机上只能一直挂着，
   * 或者回网页端关。⚠️ taskId 传的是 **purchaseOrderId**（与 web-admin 同口径，
   * 见 `PendingPurchaseReceivingPanel.vue` 调用处）。
   *
   * @returns 'CLOSED' = 已提前关闭；'COMPLETED' = 恰好收满，按正常完成处理
   */
  async closePurchaseReceivingTask(
    purchaseOrderId: string,
    payload: ClosePurchaseReceivingTaskRequest,
    factoryId?: string,
  ): Promise<ApiResponse<'CLOSED' | 'COMPLETED'>> {
    return apiClient.post<ApiResponse<'CLOSED' | 'COMPLETED'>>(
      `${this.getPath(factoryId)}/tasks/${purchaseOrderId}/close-short`,
      payload,
    );
  }
}

export const warehouseReceivingApiClient = new WarehouseReceivingApiClient();
export default warehouseReceivingApiClient;
