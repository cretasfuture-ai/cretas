/**
 * 委外单 (DECISIONS 第十九节): 独立委外单 (按委外厂) + 任务行 (工单 / 工序可空)。
 * 发退料、收货、退货、损耗都挂在任务行上; 物权仍是我方, 委外厂结存 = 委外仓里本单的批次。
 * 后端: /api/mobile/{factoryId}/outsource-orders (OutsourceOrderController)。本文件只放读接口。
 */
import { get } from './request';
import type { ApiResponse } from '@/types/api';

export type OutsourceOrderStatus = 'DRAFT' | 'RELEASED' | 'CLOSED' | 'CANCELLED';
export type OutsourceLineStatus = 'OPEN' | 'COMPLETED' | 'CANCELLED';
export type OutsourceType = 'PROCESS' | 'WHOLE';
export type OutsourceMovementType = 'ISSUE' | 'MATERIAL_RETURN' | 'RECEIVE' | 'CONSUME' | 'GOODS_RETURN' | 'LOSS';

/** 后端 BigDecimal 序列化成 number; null = 没有这个数 (不是 0) */
type Num = number | null;

export interface OutsourceOrderSummary {
  id: string;
  orderNo: string;
  supplierId: string;
  supplierName: string | null;
  status: OutsourceOrderStatus;
  expectedReturnDate: string | null;
  lineCount: number;
  plannedQuantity: Num;
  receivedGoodQuantity: Num;
  outsourceBalance: Num;
  feeAmount: Num;
  createdAt: string | null;
}

export interface OutsourceLineView {
  id: string;
  lineNo: number;
  outsourceType: OutsourceType;
  productionPlanId: string | null;
  productionPlanNumber: string | null;
  workProcessTaskId: number | null;
  processName: string | null;
  outputKind: 'FINISHED' | 'SEMI';
  productTypeId: string;
  outputName: string | null;
  unit: string | null;
  plannedQuantity: Num;
  unitFee: Num;
  taxRate: Num;
  receivedGoodQuantity: Num;
  receivedDefectQuantity: Num;
  returnedGoodsQuantity: Num;
  reworkPendingQuantity: Num;
  remainingToReceive: Num;
  feeAmount: Num;
  outsourceBalance: Num;
  status: OutsourceLineStatus;
}

export interface OutsourceBalanceView {
  lineId: string;
  batchId: string;
  batchNumber: string;
  materialTypeId: string | null;
  productTypeId: string | null;
  name: string | null;
  sourceBatchId: string | null;
  issuedQuantity: Num;
  currentQuantity: Num;
  unit: string | null;
  unitPrice: Num;
}

export interface OutsourceMovementView {
  id: string;
  lineId: string;
  movementType: OutsourceMovementType;
  parentMovementId: string | null;
  bizDate: string | null;
  sourceBatchId: string | null;
  sourceBatchNumber: string | null;
  targetBatchId: string | null;
  targetBatchNumber: string | null;
  targetBatchKind: 'MATERIAL' | 'FINISHED' | null;
  quantity: Num;
  defectQuantity: Num;
  unit: string | null;
  unitCost: Num;
  amount: Num;
  apTransactionId: string | null;
  qualityReturnOrderId: string | null;
  reason: string | null;
  createdBy: number | null;
  createdAt: string | null;
}

export interface OutsourceOrderDetail {
  id: string;
  orderNo: string;
  supplierId: string;
  supplierName: string | null;
  status: OutsourceOrderStatus;
  outsourceWarehouseId: string | null;
  expectedReturnDate: string | null;
  remark: string | null;
  releasedAt: string | null;
  closedAt: string | null;
  createdAt: string | null;
  feeAmount: Num;
  lines: OutsourceLineView[];
  balances: OutsourceBalanceView[];
  movements: OutsourceMovementView[];
}

/** 工单详情里的委外工序 */
export interface OutsourceProcessLink {
  workProcessTaskId: number | null;
  productionPlanId: string;
  orderId: string;
  orderNo: string;
  orderStatus: OutsourceOrderStatus;
  supplierId: string;
  supplierName: string | null;
  lineId: string;
  lineStatus: OutsourceLineStatus;
  plannedQuantity: Num;
  receivedGoodQuantity: Num;
  feeAmount: Num;
}

export interface OutsourcePage {
  content: OutsourceOrderSummary[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface OutsourceListQuery {
  supplierId?: string;
  status?: OutsourceOrderStatus[];
  keyword?: string;
  page?: number;
  size?: number;
}

const base = (factoryId: string) => `/${factoryId}/outsource-orders`;

export function listOutsourceOrders(factoryId: string, q: OutsourceListQuery): Promise<ApiResponse<OutsourcePage>> {
  // status 多值: 后端 @RequestParam List<String> 认逗号分隔
  const params: Record<string, string | number> = { page: q.page ?? 1, size: q.size ?? 20 };
  if (q.supplierId) params.supplierId = q.supplierId;
  if (q.status && q.status.length) params.status = q.status.join(',');
  if (q.keyword && q.keyword.trim()) params.keyword = q.keyword.trim();
  return get(base(factoryId), { params });
}

export function getOutsourceOrder(factoryId: string, orderId: string): Promise<ApiResponse<OutsourceOrderDetail>> {
  return get(`${base(factoryId)}/${encodeURIComponent(orderId)}`);
}

export function listOutsourceProcessLinks(factoryId: string, productionPlanId: string): Promise<ApiResponse<OutsourceProcessLink[]>> {
  return get(`${base(factoryId)}/process-links`, { params: { productionPlanId } });
}

export const ORDER_STATUS_LABEL: Record<OutsourceOrderStatus, string> = {
  DRAFT: '草稿', RELEASED: '已下达', CLOSED: '已结单', CANCELLED: '已取消',
};
export const ORDER_STATUS_TAG: Record<OutsourceOrderStatus, 'info' | 'primary' | 'success' | 'warning'> = {
  DRAFT: 'info', RELEASED: 'primary', CLOSED: 'success', CANCELLED: 'warning',
};
export const LINE_STATUS_LABEL: Record<OutsourceLineStatus, string> = {
  OPEN: '进行中', COMPLETED: '已完成', CANCELLED: '已取消',
};
export const MOVEMENT_LABEL: Record<OutsourceMovementType, string> = {
  ISSUE: '发料', MATERIAL_RETURN: '退料', RECEIVE: '收货', CONSUME: '耗用', GOODS_RETURN: '退货', LOSS: '损耗',
};
export const OUTSOURCE_TYPE_LABEL: Record<OutsourceType, string> = { PROCESS: '工序委外', WHOLE: '整单委外' };
