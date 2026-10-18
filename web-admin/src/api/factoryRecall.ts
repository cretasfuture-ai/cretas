import { get, post } from './request';

export interface RecallReturnOrderSummary {
  id: number | string;
  returnNumber: string;
  status: string;
  quantity: number;
}

export interface RecallShipmentSummary {
  shipmentId: string;
  shipmentNumber: string;
  customerName: string;
  quantity: number;
  unit: string;
  returnedQuantity: number;
  pendingReturnQuantity: number;
  remainingReturnQuantity: number;
  acknowledged: boolean;
  returnOrders: RecallReturnOrderSummary[];
}

export interface RecallBlocker {
  code: string;
  message: string;
  shipmentId?: string | null;
  quantity?: number | null;
}

export interface RecallBatchContext {
  batchId: number;
  batchNumber: string;
  productName: string;
  unit: string;
  remainingInventory: number;
  shipments: RecallShipmentSummary[];
}

export interface FactoryRecallDetail extends RecallBatchContext {
  id: number | string;
  eventCode: string;
  status: string;
  reason: string;
  completedAt?: string | null;
  canClose: boolean;
  blockers: RecallBlocker[];
}

export interface FactoryRecallSummary {
  id: number | string;
  eventCode: string;
  batchId: number;
  batchNumber: string;
  productName: string;
  status: string;
  reason: string;
  completedAt?: string | null;
}

export type RecallFollowUpOrigin = 'ORIGINAL' | 'RETURNED';
export type RecallReceivableStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'MISSING'
  | 'NOT_DUE'
  | 'NOT_REQUIRED'
  | 'INCONSISTENT';

export interface RecallFollowUpIssue {
  code: string;
  message: string;
}

export interface RecallFollowUpDisposal {
  id: number | string;
  status: string;
  quantity: number;
}

export interface RecallFollowUpStock {
  finishedGoodsBatchId: number | string;
  batchNumber: string;
  origin: RecallFollowUpOrigin;
  unit: string;
  remainingQuantity: number;
  disposalQuantity: number;
  evidenceComplete: boolean;
  disposals: RecallFollowUpDisposal[];
}

export interface RecallFollowUpAdjustment {
  id: number | string;
  transactionNumber: string;
  approvalStatus: string;
}

export interface RecallFollowUpReturn {
  returnOrderId: number | string;
  returnNumber: string;
  status: string;
  quantity: number;
  receivableStatus: RecallReceivableStatus;
  adjustments: RecallFollowUpAdjustment[];
}

export interface RecallFollowUp {
  recallId: number | string;
  batchId: number;
  batchNumber: string;
  unit: string;
  recallCompleted: boolean;
  inventoryResolved: boolean;
  receivableResolved: boolean;
  completed: boolean;
  issues: RecallFollowUpIssue[];
  stock: RecallFollowUpStock[];
  returns: RecallFollowUpReturn[];
}

export interface CreateFactoryRecallRequest {
  batchId: number;
  reason: string;
}

export interface CustomerAckRequest {
  shipmentId: string;
  note: string;
}

export interface RecallReturnRequest {
  shipmentId: string;
  quantity: number;
  reason: string;
}

export interface RecallReturnSummary {
  id: number | string;
  returnNumber: string;
  status: string;
  quantity: number;
}

function basePath(factoryId: string): string {
  return `/${factoryId}/quality-recalls`;
}

function unwrap<T>(response: { success: boolean; data?: T; message?: string }, fallback: string): T {
  if (!response.success || response.data == null) throw new Error(response.message || fallback);
  return response.data;
}

export async function listFactoryRecalls(factoryId: string, batchId?: number): Promise<FactoryRecallSummary[]> {
  const response = await get<FactoryRecallSummary[]>(basePath(factoryId), {
    params: batchId == null ? undefined : { batchId },
  });
  return unwrap(response, '召回记录加载失败');
}

export async function previewFactoryRecall(factoryId: string, batchId: number): Promise<FactoryRecallDetail> {
  const response = await get<FactoryRecallDetail>(`${basePath(factoryId)}/preview`, { params: { batchId } });
  return unwrap(response, '批次召回预览加载失败');
}

export async function createFactoryRecall(
  factoryId: string,
  payload: CreateFactoryRecallRequest,
): Promise<FactoryRecallDetail> {
  const response = await post<FactoryRecallDetail>(basePath(factoryId), payload);
  return unwrap(response, '发起召回失败');
}

export async function getFactoryRecall(factoryId: string, id: number | string): Promise<FactoryRecallDetail> {
  const response = await get<FactoryRecallDetail>(`${basePath(factoryId)}/${encodeURIComponent(id)}`);
  return unwrap(response, '召回详情加载失败');
}

export async function getFactoryRecallFollowUp(
  factoryId: string,
  id: number | string,
): Promise<RecallFollowUp> {
  const response = await get<RecallFollowUp>(
    `${basePath(factoryId)}/${encodeURIComponent(id)}/follow-up`,
  );
  return unwrap(response, '召回后续处理读取失败');
}

export async function acknowledgeFactoryRecallShipment(
  factoryId: string,
  id: number | string,
  payload: CustomerAckRequest,
): Promise<FactoryRecallDetail> {
  const response = await post<FactoryRecallDetail>(
    `${basePath(factoryId)}/${encodeURIComponent(id)}/customer-ack`,
    payload,
  );
  return unwrap(response, '记录客户联系凭据失败');
}

export async function createFactoryRecallReturn(
  factoryId: string,
  id: number | string,
  payload: RecallReturnRequest,
): Promise<RecallReturnSummary> {
  const response = await post<RecallReturnSummary>(
    `${basePath(factoryId)}/${encodeURIComponent(id)}/returns`,
    payload,
  );
  return unwrap(response, '登记退货失败');
}

export async function closeFactoryRecall(
  factoryId: string,
  id: number | string,
  note: string,
): Promise<FactoryRecallDetail> {
  const response = await post<FactoryRecallDetail>(
    `${basePath(factoryId)}/${encodeURIComponent(id)}/close`,
    { note },
  );
  return unwrap(response, '召回结案失败');
}
