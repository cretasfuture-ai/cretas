// 采购收货 API client — 单元 G (F006 R-B3, 2026-06-02).
//
// Backend endpoints (PurchaseController):
//   GET /api/mobile/{factoryId}/purchase/orders/{orderId}/cumulative-received  (累计已收, 跨页权威值)
//   GET /api/mobile/{factoryId}/purchase/orders/{orderId}/receives            (分次收货时序明细)
//
// axios baseURL = '/api/mobile' so caller-side URLs start at "/{factoryId}/...".
// 同 orderCopy.ts 模式 (不带 /mobile/ 前缀).
import { get, post } from './request';
import type { FactoryWarehouse } from './factoryWarehouse';

/** 累计已收 — 单行明细 (后端 PurchaseServiceImpl.getCumulativeReceived lines). */
export interface CumulativeReceivedLine {
  materialId: string;
  materialName: string;
  plannedQty: number;
  receivedQty: number;
  pendingQty: number;
  /**
   * 累计可收上限 = plannedQty × (1 + 超收上限率)。
   * ⛔ `null` = **不限制超收**，不是 0 也不是 plannedQty —— 兜底成数字会把
   * 「没有上限」显示成一个具体的上限，正是 2026-08-25 那个缺陷的形状。
   */
  maxReceivableQty: number | null;
  unit: string;
}

/** 累计已收汇总 (Issue #787 endpoint). 替代 FE-only page-rows 聚合, 跨页准确. */
export interface CumulativeReceived {
  poId: string;
  orderNumber: string;
  plannedTotal: number;
  cumulativeReceived: number;
  /**
   * 本工厂**实际生效**的采购超收上限率 (0.30 = 最多多收 30%)。
   * `null` = 不限制超收。
   *
   * ⚠️ 这是后端执行侧 (`PurchaseServiceImpl.resolveOverReceiveLimit`) 用的同一个值。
   * ⛔ 界面上的超收上限一律用它插值显示，不要再写死百分比 —— 展示写死 30% 而执行侧
   * 实际是 0%，就是仓管员点确认收货吃 409、货堵在门口的那个缺陷。
   */
  overReceiveLimitRate: number | null;
  /** 面向用户的措辞: "不限制" / "30%" / "12.5%"。直接显示这个，不要自己格式化。 */
  overReceiveLimitLabel: string;
  /** 这个上限从哪来: "unconfigured" / "property:..." / "factory-settings:..."。排查用。 */
  overReceiveLimitSource: string;
  /** 全单累计可收上限; `null` = 不限制。 */
  maxReceivableTotal: number | null;
  lines: CumulativeReceivedLine[];
}

/** 分次收货 — 单次事件内的物料明细行. */
export interface ReceiveSequenceItem {
  materialName: string;
  quantity: number;
  unit: string;
}

/** 分次收货 — 单次收货事件 (单元 G). */
export interface ReceiveSequenceEntry {
  seq: number;
  receiveId: string;
  receiveNumber: string;
  receiveDate: string;
  createdAt: string;
  createdByName: string | null;
  totalQuantity: number;
  items: ReceiveSequenceItem[];
}

export interface PurchaseReceivingTaskItem {
  purchaseOrderItemId?: number | null;
  salesOrderItemId?: number | null;
  materialTypeId: string;
  materialName: string;
  orderedQuantity: number;
  receivedQuantity: number;
  activeDraftAllocatedQuantity: number;
  /** 还差多少**收齐** = 下单 − 已收 − 草稿占用。⛔ 这不是"最多能收多少"。 */
  remainingReceivableQuantity: number;
  /**
   * 本次**最多还能收多少** = 下单×(1+超收上限) − 已收 − 草稿占用。
   * `null` = 不限制超收 —— 输入框此时不该设上限。
   * ⛔ 不要兜底成 `remainingReceivableQuantity`：那会把超收的货挡在门口，
   *    而供应商多送 5% 是常态。
   */
  maxReceivableQuantity?: number | null;
  unit: string;
  specification?: string | null;
  /** 行级合同号(下单时填的); 后端在行级为空时已回落到单头框架合同号 */
  contractNumber?: string | null;
  materialPackagingSpecId?: string | null;
  inventoryBaseUnit?: string | null;
  packageToBaseFactor?: number | null;
  packagingSpecs?: Array<{
    id: string;
    name: string;
    packageUnit: string;
    baseUnit: string;
    conversionFactor: number;
    defaultSpec?: boolean | null;
    active?: boolean | null;
  }>;
}

export interface PurchaseReceivingTask {
  taskId: string;
  sourceType: 'PURCHASE';
  purchaseOrderId: string;
  orderNumber: string;
  supplierId: string;
  supplierName?: string | null;
  expectedDeliveryDate?: string | null;
  status: 'WAITING_RECEIVE' | 'RECEIVING';
  statusLabel: string;
  warehouseId?: string | null;
  warehouseName?: string | null;
  responsibleName?: string | null;
  activeReceiptId?: string | null;
  activeReceiptNumber?: string | null;
  activeReceiptCount: number;
  receiptConflict: boolean;
  /** 本工厂实际生效的采购超收上限率 (0.30 = 最多多收 30%); `null` = 不限制超收。 */
  overReceiveLimitRate?: number | null;
  /** 面向仓管员的措辞: "不限制" / "30%"。直接显示, 不要自己格式化。 */
  overReceiveLimitLabel?: string | null;
  /** 上限来源: unconfigured / property:... / factory-settings:...。排查用。 */
  overReceiveLimitSource?: string | null;
  items: PurchaseReceivingTaskItem[];
}

export interface CustomerSuppliedReceivingTask {
  taskId: string;
  sourceType: 'SALES_ORDER_CUSTOMER_SUPPLIED';
  salesOrderId: string;
  salesOrderNo: string;
  customerId: string;
  customerName: string;
  expectedArrivalAt?: string | null;
  status: 'WAITING_RECEIVE' | 'RECEIVING';
  statusLabel: string;
  warehouseId?: string | null;
  warehouseName?: string | null;
  responsibleName?: string | null;
  activeReceiptId?: string | null;
  activeReceiptNumber?: string | null;
  activeReceiptCount: number;
  receiptConflict: boolean;
  items: PurchaseReceivingTaskItem[];
}

export interface CustomerMaterialArrivalTask {
  taskId: string;
  sourceType: 'CUSTOMER_MATERIAL_ARRIVAL';
  sourceId: string;
  sourceNumber: string;
  inboundReason: 'CUSTOMER_MATERIAL' | 'GIFT' | 'OTHER';
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
  items: PurchaseReceivingTaskItem[];
}

export type WarehouseReceivingTask = PurchaseReceivingTask | CustomerSuppliedReceivingTask | CustomerMaterialArrivalTask;

export interface WarehouseReceivingTaskFilters {
  purchaseOrderId?: string;
  orderNumber?: string;
  salesOrderId?: string;
  salesOrderNo?: string;
  arrivalNoticeId?: string;
  sourceType?: 'PURCHASE' | 'SALES_ORDER_CUSTOMER_SUPPLIED' | 'CUSTOMER_MATERIAL_ARRIVAL';
}

export interface CreateCustomerSuppliedReceiptRequest {
  receivedQuantity: number;
  unit?: string;
  /** 客户/供应商给的批号 —— 落 material_batches.supplier_batch_number, **不是厂号** */
  externalBatchNumber?: string;
  /** 与采购收货同一套可追溯字段 (V20261029_61) */
  contractNumber?: string;
  factoryNumber?: string;
  boxCount?: number;
  productionDate?: string;
  expireDate?: string;
  originPlace?: string;
  notes?: string;
  idempotencyKey: string;
}

export interface CustomerSuppliedReceiptResult {
  id: string;
  batchNumber?: string | null;
  quantity?: number | null;
  currentQuantity?: number | null;
  unit?: string | null;
}

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

/**
 * 生产结单后等待仓库确认的既有 settlement 投影。
 * 来源是真实 ProductionSettlement，不是另一套入库任务。
 */
export function getPendingPurchaseReceivingTasks(
  factoryId: string,
  filters?: { purchaseOrderId?: string; orderNumber?: string },
) {
  return get<PurchaseReceivingTask[]>(`/${factoryId}/warehouse/receiving/tasks`, {
    params: {
      purchaseOrderId: filters?.purchaseOrderId || undefined,
      orderNumber: filters?.orderNumber || undefined,
    },
  });
}

export function getPendingWarehouseReceivingTasks(
  factoryId: string,
  filters?: WarehouseReceivingTaskFilters,
) {
  return get<WarehouseReceivingTask[]>(`/${factoryId}/warehouse/receiving/tasks`, {
    params: {
      purchaseOrderId: filters?.purchaseOrderId || undefined,
      orderNumber: filters?.orderNumber || undefined,
      salesOrderId: filters?.salesOrderId || undefined,
      salesOrderNo: filters?.salesOrderNo || undefined,
      arrivalNoticeId: filters?.arrivalNoticeId || undefined,
      sourceType: filters?.sourceType || undefined,
    },
  });
}

export interface CreateCustomerMaterialArrivalReceiptRequest {
  idempotencyKey: string;
  materialTypeId: string;
  warehouseId: string;
  receivedQuantity: number;
  unit?: string;
  productionDate?: string;
  expireDate?: string;
  externalBatchNumber?: string;
  contractNumber?: string;
  factoryNumber?: string;
  boxCount?: number;
  originPlace?: string;
  notes?: string;
  completeNotice: boolean;
}

export function createCustomerMaterialArrivalReceipt(
  factoryId: string,
  noticeId: string,
  request: CreateCustomerMaterialArrivalReceiptRequest,
) {
  return post<CustomerSuppliedReceiptResult>(
    `/${factoryId}/warehouse/receiving/arrival-notices/${noticeId}/receipts`,
    request,
  );
}

export function createCustomerSuppliedReceipt(
  factoryId: string,
  taskId: string,
  request: CreateCustomerSuppliedReceiptRequest,
) {
  return post<CustomerSuppliedReceiptResult>(
    `/${factoryId}/warehouse/receiving/tasks/${taskId}/receipts`,
    request,
  );
}

export function closePurchaseReceivingTask(
  factoryId: string,
  taskId: string,
  request: ClosePurchaseReceivingTaskRequest,
) {
  return post<'CLOSED' | 'COMPLETED'>(
    `/${factoryId}/warehouse/receiving/tasks/${taskId}/close-short`,
    request,
  );
}

/**
 * 采购订单累计已收汇总 (跨页权威值, 替代 list.vue 的 page-local cumulativeForRow).
 * 异常走 axios interceptor (sticky toast).
 */
export function getCumulativeReceived(factoryId: string, orderId: string) {
  return get<CumulativeReceived>(
    `/${factoryId}/purchase/orders/${orderId}/cumulative-received`
  );
}

/**
 * 采购订单分次收货时序明细 (第N次/日期/数量), createdAt 升序.
 * 客户张权: "第一次收了多少第二次收了多少更直观". 空列表 = 暂无收货记录.
 */
export function getOrderReceiveSequence(factoryId: string, orderId: string) {
  return get<ReceiveSequenceEntry[]>(
    `/${factoryId}/purchase/orders/${orderId}/receives`
  );
}

/**
 * 采购入库默认仓 — 后端解析本工厂 PURCHASE_INBOUND_DEFAULT 配置 (未配置回退物流仓 WH-LOG)。
 * 供「新建入库单」预填入库仓库 (防呆 Rule 1)。data 为 null = 工厂缺仓库 seed → 前端回退本地默认逻辑。
 * 权限对齐入库单读取, 实际收货的仓管/采购员均可读 (不像超管专属的 /factory/warehouse-defaults)。
 */
export function getPurchaseInboundDefaultWarehouse(factoryId: string) {
  return get<FactoryWarehouse | null>(
    `/${factoryId}/warehouse/receiving/default-warehouse`
  );
}
