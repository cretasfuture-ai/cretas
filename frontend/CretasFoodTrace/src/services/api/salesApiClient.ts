import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 销售管理API客户端
 * 路径：/api/mobile/{factoryId}/sales/*
 */

// ========== 类型定义 ==========

export interface SalesOrder {
  id: string;
  factoryId: string;
  orderNumber: string;
  customerId: string;
  customerName?: string;
  status:
    | 'DRAFT'
    | 'CONFIRMED'
    | 'PENDING_FINANCE_REVIEW'
    | 'FINANCE_APPROVED'
    | 'FINANCE_REJECTED'
    | 'PROCESSING'
    | 'PARTIAL_DELIVERED'
    | 'COMPLETED'
    | 'CANCELLED'
    // T-SHORT-SHIP (2026-09-04): 与后端 SalesOrderStatus.SHORT_CLOSED 对齐 —— 缺此值会让
    // TS 联合类型与运行时实际返回值不一致 (窄类型下这个真实状态会被判定成非法值)。
    | 'SHORT_CLOSED';
  /** Price-sensitive fields are null when the backend masks financial data. */
  totalAmount: number | null;
  orderDate: string;
  expectedDeliveryDate?: string;
  salesperson?: string;
  remark?: string;
  createdBy?: string;
  items?: SalesOrderItem[];
  deliveryRecords?: SalesDeliveryRecord[];
  // Sprint3-G S-LOCK-1: 后端 SalesOrder @Transient @JsonProperty 派生 (聚合 items[]).
  // chip 显示 锁/备/缺. NOT @PriceSensitive — inventory 数据非价格.
  lockedQty?: number;
  reservedQty?: number;
  shortageQty?: number;
  createdAt: string;
  updatedAt?: string;
  // T-SHORT-SHIP: 短交结案落账字段 (后端 SalesOrder.java:187-203, 只在 SHORT_CLOSED 后有值)。
  shortCloseReasonCode?: string | null;
  shortCloseNotes?: string | null;
  shortClosedBy?: number | null;
  shortClosedAt?: string | null;
  shortClosedQuantity?: number | null;
}

/**
 * 短交结案请求体 —— 逐字对应后端 dto/inventory/CloseShortShipmentRequest.java。
 * reasonCode 的四个取值定义在 utils/salesOrderShortClose.ts (ShortCloseReasonCode)。
 */
export interface CloseShortShipmentPayload {
  reasonCode: 'CUSTOMER_WAIVED_REMAINDER' | 'SOURCE_SHORTAGE' | 'ORDER_BALANCE_CANCELLED' | 'OTHER';
  /** 补充说明, ≤ 500 字; reasonCode = OTHER 时后端要求非空。 */
  notes?: string;
}

export interface SalesOrderItem {
  id: string;
  productTypeId: string;
  /** 产品名。后端**实际发的是这个**（实测 'SOP-20260817-01-黄油鸡-成品800g'）。 */
  productName?: string | null;
  /** ⚠️ 后端在这个响应里不发它（恒 undefined）。保留只为兼容旧调用，⛔ 不要拿它当唯一来源。 */
  productTypeName?: string;
  quantity: number;
  deliveredQuantity: number;
  /** 未发货量 = quantity - deliveredQuantity（后端 @Transient getter）。 */
  pendingQuantity?: number;
  unitPrice: number | null;
  unit: string;
  totalPrice: number | null;
  // Sprint3-G S-LOCK-1: 行级 lockedQty / reservedQty 来自 sales_order_items 列;
  // shortageQty 后端 @Transient getter
  // (2026-08-28 起 = quantity - deliveredQuantity - reservedQty, clamp ≥0;
  //  在那之前漏减已发货, 于是发完的单子恒报「缺 = 全量」)。
  lockedQty?: number;
  reservedQty?: number;
  shortageQty?: number;
  packagingSpecId?: string;
  packagingSpecName?: string;
  packagingUnit?: string;
  packagingBaseUnit?: string;
  packagingFactor?: number;
}

export interface SalesDeliveryRecord {
  id: string;
  deliveryNumber: string;
  deliveryDate: string;
  deliveredBy?: string;
  status: string;
  remark?: string;
  items?: SalesDeliveryItem[];
}

export interface SalesDeliveryItem {
  id: string;
  productTypeId: string;
  /** 产品名。后端**实际发的是这个**（实测 deliveries/by-order 返回 'SOP-20260817-01-黄油鸡-成品800g'）。 */
  productName?: string | null;
  /** ⚠️ 后端不发它（实测恒 null）。⛔ 不要拿它当唯一来源，否则退化成 productTypeId 那串 UUID。 */
  productTypeName?: string;
  deliveredQuantity: number;
  unit: string;
  packagingSpecId?: string;
  packagingSpecName?: string;
  packagingUnit?: string;
  packagingBaseUnit?: string;
  packagingFactor?: number;
  batchNumber?: string;
}

/**
 * 与 GET /api/mobile/{factoryId}/sales/finished-goods 的实际响应对齐。
 *
 * 2026-08-28 逐屏走查发现这个接口声明过三个后端**从不产出**的字段,
 * 而 TypeScript 全绿 —— 声明是我们自己写的, 它不会去核对后端发了什么:
 *
 *   productTypeName  后端恒为 null, 真名在 productName 里
 *                    ⇒ 屏上每一行的品名都退化成 productTypeId 那串 UUID
 *   totalQuantity    后端根本没有这个键
 *                    ⇒ 「总量」显示成空, 进度条恒 0,
 *                       而 `availableQuantity / undefined` 是 NaN,
 *                       `NaN < 0.2` 恒 false ⇒「库存偏低」这一档永远走不到
 *   expiryDate       后端发的是 expireDate ⇒ 保质期从来没显示过
 *
 * ⛔ 改这里的字段名之前, 先拿真实响应核一遍键名。
 */
export interface FinishedGoodsBatch {
  id: string;
  factoryId: string;
  batchNumber: string;
  productTypeId: string;
  /** 产品名。后端字段名是 productName; productTypeName 恒 null, 保留仅为兼容旧调用。 */
  productName?: string | null;
  productTypeName?: string | null;
  /** 本批次产出量。后端字段名是 producedQuantity。 */
  producedQuantity?: number | null;
  /** 标称量, 后端可能为 null。 */
  nominalQuantity?: number | null;
  availableQuantity: number;
  reservedQuantity: number;
  shippedQuantity?: number | null;
  unit: string;
  packagingSpecId?: string;
  packagingSpecName?: string | null;
  packagingUnit?: string;
  packagingBaseUnit?: string;
  packagingFactor?: number;
  productionDate: string;
  /** 保质期到期日。后端字段名是 expireDate。 */
  expireDate?: string | null;
  unitCost?: number;
  storageLocation?: string | null;
  createdAt: string;
}

export interface CreateSalesOrderRequest {
  customerId: string;
  /** RN 快速建单当前只开放普通销售；客供代加工由 Web Admin 的结构化来料表单创建。 */
  processingMode: 'STANDARD_SALE' | 'TOLL_PROCESSING';
  materialSupplyMode: 'FACTORY_SUPPLIED' | 'CUSTOMER_SUPPLIED';
  orderDate?: string;
  requiredDeliveryDate?: string;
  expectedDeliveryDate?: string;
  salesperson?: string;
  remark?: string;
  items: {
    productTypeId: string;
    productName?: string;
    quantity: number;
    unitPrice: number;
    unit: string;
    taxRate?: number;
    packagingSpecId?: string;
    /**
     * 规格快照 (如 "310g*42袋/箱")。后端 `CreateSalesOrderRequest.SalesOrderItemDTO.specification`
     * 一直支持，只是这份 TS 契约漏了它 —— 于是 RN 建的单在仓库那头没有规格。
     * 后端 `applyLineDisplaySnapshots` 仅在本字段 blank 时才退到 packagingSpecName，
     * 所以取不到值要送 `undefined`，⛔ 不要送空串。
     */
    specification?: string;
    /**
     * 箱数。后端**不做任何推导**（`applyLineDisplaySnapshots` 只认请求里的值），
     * 客户端不发 = 库里恒为 null。⛔ 算不出来要送 `undefined`，不要送 0 ——
     * 0 会被仓库读成「零箱」。
     */
    boxQuantity?: number;
  }[];
}

export interface CreateDeliveryRequest {
  /** 关联的销售订单ID（可选，支持无单出库） */
  salesOrderId?: string;
  customerId: string;
  deliveryDate: string;
  deliveryAddress?: string;
  logisticsCompany?: string;
  trackingNumber?: string;
  remark?: string;
  items: CreateDeliveryItemRequest[];
}

export interface CreateDeliveryItemRequest {
  salesOrderItemId?: number;
  productTypeId: string;
  productName?: string;
  deliveredQuantity: number;
  unit: string;
  packagingSpecId?: string;
  unitPrice?: number;
  remark?: string;
}

export interface SalesStatistics {
  [key: string]: unknown;
}

// ========== Sprint 2 Track E (S-MRP-1 / N31) — 缺料分析报告 ==========

export interface MaterialNeed {
  materialTypeId: string;
  materialTypeName?: string;
  requiredQuantity: number;
  wastageRate?: number;
  sourceUnit?: string;
}

export interface FgLineItem {
  productTypeId: string;
  productTypeName?: string;
  requiredQuantity: number;
  availableQuantity: number;
  shortfallQuantity: number;
}

export interface MaterialShortageItem {
  materialTypeId: string;
  materialTypeName?: string;
  requiredQuantity: number;
  availableQuantity: number;
  shortfallQuantity: number;
}

export interface ShortagePriceComparison {
  bomStandardPrice?: number;
  movingAvgPrice?: number;
  currentPrice?: number;
  priceAlert?: string;
}

export interface ShortageProcurementSuggestion {
  materialId: string;
  materialName?: string;
  suggestedQty: number;
  unit?: string;
  suggestedSupplierId?: string;
  suggestedSupplierName?: string;
  estimatedPrice?: number;
  estimatedTotal?: number;
  leadDays?: number;
  priceComparison?: ShortagePriceComparison;
}

export interface ShortageProductionSuggestion {
  productId: string;
  productName?: string;
  plannedQty: number;
  workProcessIds?: string[];
  workProcessNames?: string[];
  startDate?: string;
  endDate?: string;
}

export type ShortageAnalysisStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'NOT_AVAILABLE';

export interface ShortageReport {
  id?: string;
  factoryId: string;
  salesOrderId: string;
  analysisStatus: ShortageAnalysisStatus;
  totalRequired?: MaterialNeed[];
  /** 后端 column 名 = available, 实际承载 FG 行项目匹配 (LineItemMatch). */
  available?: FgLineItem[];
  shortage?: MaterialShortageItem[];
  procurementSuggestions?: ShortageProcurementSuggestion[];
  productionSuggestions?: ShortageProductionSuggestion[];
  analysisSummary?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

// ========== API客户端类 ==========

class SalesApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/sales`;
  }

  // ==================== 销售订单 ====================

  /** 获取销售单列表 */
  async getOrders(params?: { page?: number; size?: number }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<SalesOrder> }> {
    return apiClient.get(this.getPath(factoryId) + '/orders', { params });
  }

  /** 按状态查询销售单列表 */
  async getOrdersByStatus(status: SalesOrder['status'], params?: { page?: number; size?: number }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<SalesOrder> }> {
    return apiClient.get(this.getPath(factoryId) + '/orders/by-status', { params: { status, ...params } });
  }

  /** 获取销售单详情 */
  async getOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: SalesOrder }> {
    return apiClient.get(this.getPath(factoryId) + `/orders/${orderId}`);
  }

  /** 创建销售单 */
  async createOrder(data: CreateSalesOrderRequest, factoryId?: string): Promise<{ success: boolean; data: SalesOrder }> {
    return apiClient.post(this.getPath(factoryId) + '/orders', data);
  }

  /** 确认销售单 */
  async confirmOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: SalesOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/orders/${orderId}/confirm`);
  }

  /** 取消销售单 */
  async cancelOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: SalesOrder }> {
    return apiClient.post(this.getPath(factoryId) + `/orders/${orderId}/cancel`);
  }

  /**
   * 短交结案 (PARTIAL_DELIVERED → SHORT_CLOSED): 剩余数量确认不再发货。
   *
   * 后端: SalesController.java:671 `POST /orders/{orderId}/close-short-shipment`,
   * `@RequirePermission("sales:read_write")` (= sales_manager)。
   * body 见 CloseShortShipmentRequest —— reasonCode 必填 (后端 400
   * SALES_SHORT_CLOSE_REASON_REQUIRED), OTHER 时 notes 必填, notes ≤ 500 字。
   *
   * ⚠️ 错误一律**原样抛出** (与 cancelOrder / confirmOrder 同形: apiClient 的响应拦截器
   * 直接 reject axios error, 调用方用 getErrorMsg 取后端 message + actionHint)。
   * ⛔ 不在这里 catch 成 `{ success: false }` —— 那会让 409/403 长得像成功 (核心原则 1: 禁止降级)。
   */
  async closeShortShipment(
    orderId: string,
    payload: CloseShortShipmentPayload,
    factoryId?: string
  ): Promise<{ success: boolean; data: SalesOrder; message?: string }> {
    return apiClient.post(this.getPath(factoryId) + `/orders/${orderId}/close-short-shipment`, payload);
  }

  // ==================== 发货/出库 ====================

  /** 创建发货单 (POST /deliveries) */
  async createDelivery(data: CreateDeliveryRequest, factoryId?: string): Promise<{ success: boolean; data: SalesDeliveryRecord }> {
    return apiClient.post(this.getPath(factoryId) + '/deliveries', data);
  }

  /** 获取发货单列表 (GET /deliveries) */
  async getDeliveries(params?: { page?: number; size?: number }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<SalesDeliveryRecord> }> {
    return apiClient.get(this.getPath(factoryId) + '/deliveries', { params });
  }

  /** 获取发货单详情 (GET /deliveries/{deliveryId}) */
  async getDelivery(deliveryId: string, factoryId?: string): Promise<{ success: boolean; data: SalesDeliveryRecord }> {
    return apiClient.get(this.getPath(factoryId) + `/deliveries/${deliveryId}`);
  }

  /** 发货确认，扣减成品库存 (POST /deliveries/{deliveryId}/ship) */
  async shipDelivery(deliveryId: string, factoryId?: string): Promise<{ success: boolean; data: SalesDeliveryRecord }> {
    return apiClient.post(this.getPath(factoryId) + `/deliveries/${deliveryId}/ship`);
  }

  /** 签收确认 (POST /deliveries/{deliveryId}/delivered) */
  async confirmDelivered(deliveryId: string, factoryId?: string): Promise<{ success: boolean; data: SalesDeliveryRecord }> {
    return apiClient.post(this.getPath(factoryId) + `/deliveries/${deliveryId}/delivered`);
  }

  /** 按销售订单查询发货记录 (GET /deliveries/by-order/{orderId}) */
  async getDeliveriesByOrder(orderId: string, factoryId?: string): Promise<{ success: boolean; data: SalesDeliveryRecord[] }> {
    return apiClient.get(this.getPath(factoryId) + `/deliveries/by-order/${orderId}`);
  }

  // ==================== 成品库存 ====================

  /** 获取成品库存列表 */
  async getFinishedGoods(params?: { page?: number; size?: number }, factoryId?: string): Promise<{ success: boolean; data: PageResponse<FinishedGoodsBatch> }> {
    return apiClient.get(this.getPath(factoryId) + '/finished-goods', { params });
  }

  /** 查询可用成品批次（按产品） */
  async getAvailableBatches(productTypeId: string, factoryId?: string): Promise<{ success: boolean; data: FinishedGoodsBatch[] }> {
    return apiClient.get(this.getPath(factoryId) + '/finished-goods/available', { params: { productTypeId } });
  }

  // ==================== 统计 ====================

  /** 销售统计数据 */
  async getStatistics(factoryId?: string): Promise<{ success: boolean; data: SalesStatistics }> {
    return apiClient.get(this.getPath(factoryId) + '/statistics');
  }

  // ==================== 缺料分析 (Sprint 2 Track E / N31) ====================

  /**
   * 查询销售订单的缺料分析报告 (S-MRP-1)。
   * 报告由后端 SalesOrderShortageReportListener 在财务审核通过后异步生成。
   * 未生成时返回 analysisStatus='NOT_AVAILABLE' (HTTP 200) — 前端展示占位。
   */
  async getShortageReport(orderId: string, factoryId?: string): Promise<{ success: boolean; data: ShortageReport }> {
    return apiClient.get(this.getPath(factoryId) + `/orders/${orderId}/shortage-report`);
  }
}

export const salesApiClient = new SalesApiClient();
