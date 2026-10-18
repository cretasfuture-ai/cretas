import { get, post } from './request';
import { ApiError } from '@/types/api';

export type ReturnSourceStatus = 'VERIFIED' | 'LEGACY_SINGLE_LINE' | 'UNKNOWN' | string;

export interface ReturnedStockLine {
  id: string;
  batchNumber: string;
  unit: string;
  remainingQuantity: number | null;
  status: string;
}

export interface ReturnStockLineageLine {
  returnOrderItemId: number | string;
  sourceFinishedGoodsBatchId: string | null;
  sourceBatchNumber: string | null;
  productTypeId: string | null;
  unit: string | null;
  quantity: number | null;
  sourceStatus: ReturnSourceStatus;
  returnedStock: ReturnedStockLine[];
}

export interface ReturnOrderStockLineage {
  returnOrderId: string;
  returnNumber: string;
  lines: ReturnStockLineageLine[];
}

export type ReturnRefundEligibilityStatus =
  | 'ELIGIBLE'
  | 'PENDING_AR_APPROVAL'
  | 'NOT_ELIGIBLE'
  | 'ALREADY_REQUESTED'
  | 'ALREADY_PAID'
  | 'REVERSED'
  | 'INCONSISTENT'
  | string;

export interface ReturnRefundVoucher {
  id: string;
  voucherNumber: string;
  status: string;
}

export interface ReturnRefundLedger {
  id: string;
  transactionNumber: string;
  transactionType: 'AR_REFUND' | string;
  amount: number | null;
  balanceBefore?: number | null;
  balanceAfter: number | null;
  paymentMethod: string | null;
  paymentReference: string | null;
  sourceType?: string | null;
  sourceId?: string | null;
  approvedBy: number | null;
  approvedByName?: string | null;
  approvedAt: string | null;
  operatedBy: number | null;
  operatedByName?: string | null;
  transactionDate: string | null;
}

export interface ReturnRefundReversal {
  status: string;
  reason: string | null;
  requestedAt: string | null;
  ledger: ReturnRefundLedger;
  voucher?: ReturnRefundVoucher | null;
}

export interface ReturnRefundRequest {
  id: string;
  requestNumber: string;
  status: string;
  amount: number | null;
  paymentMethod: string | null;
  createdBy: number | null;
  approvedBy: number | null;
  paidBy: number | null;
  paidAt: string | null;
  paymentReference: string | null;
  arapTransactionId: string | null;
  ledger?: ReturnRefundLedger | null;
  voucher: ReturnRefundVoucher | null;
  reversal?: ReturnRefundReversal | null;
}

export interface ReturnRefund {
  returnOrderId: string;
  returnNumber: string;
  eligibilityStatus: ReturnRefundEligibilityStatus;
  reason: string | null;
  refundableAmount: number | null;
  request: ReturnRefundRequest | null;
}

function basePath(factoryId: string, returnOrderId: string): string {
  return `/${factoryId}/return-orders/${encodeURIComponent(returnOrderId)}`;
}

function refundRequestPath(factoryId: string, requestId: string): string {
  return `/${factoryId}/return-refunds/${encodeURIComponent(requestId)}`;
}

function unwrap<T>(response: { success: boolean; data?: T; message?: string }, fallback: string): T {
  if (!response.success || response.data == null) throw new ApiError(response.message || fallback);
  return response.data;
}

export async function getReturnOrderStockLineage(
  factoryId: string,
  returnOrderId: string,
): Promise<ReturnOrderStockLineage> {
  const response = await get<ReturnOrderStockLineage>(`${basePath(factoryId, returnOrderId)}/stock-lineage`);
  const data = unwrap(response, '退货来源读取失败');
  if (data.returnOrderId !== returnOrderId || !Array.isArray(data.lines)) {
    throw new ApiError('退货来源与当前退货单不一致，请重新读取');
  }
  return data;
}

export async function getReturnRefund(factoryId: string, returnOrderId: string): Promise<ReturnRefund> {
  const response = await get<ReturnRefund>(`${basePath(factoryId, returnOrderId)}/refund`);
  const data = unwrap(response, '退款信息读取失败');
  if (data.returnOrderId !== returnOrderId) throw new ApiError('退款来源与当前退货单不一致，请重新读取');
  return data;
}

/** Read one refund request by its exact id; never fall back to a return-order summary. */
export async function getReturnRefundByRequest(
  factoryId: string,
  requestId: string,
): Promise<ReturnRefund> {
  const normalizedRequestId = String(requestId || '').trim();
  if (!normalizedRequestId) throw new ApiError('退款申请号为空，无法读取退款流水');
  const response = await get<ReturnRefund>(refundRequestPath(factoryId, normalizedRequestId));
  const data = unwrap(response, '退款申请读取失败');
  if (!data.returnOrderId || !data.request || data.request.id !== normalizedRequestId) {
    throw new ApiError('退款申请来源与当前请求不一致，请重新读取');
  }
  return data;
}

export async function createReturnRefund(
  factoryId: string,
  returnOrderId: string,
  amount: number,
  remark: string,
): Promise<ReturnRefund> {
  return unwrap(await post<ReturnRefund>(`${basePath(factoryId, returnOrderId)}/refund`, { amount, remark }), '创建退款申请失败');
}

export async function submitReturnRefund(factoryId: string, returnOrderId: string): Promise<ReturnRefund> {
  return unwrap(await post<ReturnRefund>(`${basePath(factoryId, returnOrderId)}/refund/submit`, {}), '提交退款申请失败');
}

export async function approveReturnRefund(factoryId: string, returnOrderId: string, note: string): Promise<ReturnRefund> {
  return unwrap(await post<ReturnRefund>(`${basePath(factoryId, returnOrderId)}/refund/approve`, { note }), '审批退款申请失败');
}

export async function markReturnRefundPaid(
  factoryId: string,
  returnOrderId: string,
  paymentMethod: 'CASH' | 'BANK_TRANSFER',
  paymentReference: string,
  remark: string,
): Promise<ReturnRefund> {
  return unwrap(await post<ReturnRefund>(`${basePath(factoryId, returnOrderId)}/refund/mark-paid`, {
    paymentMethod, paymentReference, remark,
  }), '登记退款已支付失败');
}

export async function rejectReturnRefund(factoryId: string, returnOrderId: string, reason: string): Promise<ReturnRefund> {
  return unwrap(await post<ReturnRefund>(`${basePath(factoryId, returnOrderId)}/refund/reject`, { reason }), '驳回退款申请失败');
}

function assertRefundResponse(data: ReturnRefund, returnOrderId: string, requestId: string): ReturnRefund {
  if (data.returnOrderId !== returnOrderId || !data.request || data.request.id !== requestId) {
    throw new ApiError('退款申请来源与当前请求不一致，请重新读取');
  }
  return data;
}

export async function requestReturnRefundReversal(
  factoryId: string,
  returnOrderId: string,
  requestId: string,
  paymentReference: string,
  reason: string,
): Promise<ReturnRefund> {
  const normalizedRequestId = String(requestId || '').trim();
  const normalizedPaymentReference = paymentReference.trim();
  const normalizedReason = reason.trim();
  if (!normalizedRequestId || !normalizedPaymentReference || !normalizedReason) {
    throw new ApiError('撤销退款申请号、款项收回凭据和原因均不能为空');
  }
  if (normalizedPaymentReference.length > 100) throw new ApiError('款项收回凭据最多100字');
  if (normalizedReason.length > 400) throw new ApiError('撤销退款原因最多400字');
  const response = await post<ReturnRefund>(`${basePath(factoryId, returnOrderId)}/refund/reversal`, {
    requestId: normalizedRequestId,
    paymentReference: normalizedPaymentReference,
    reason: normalizedReason,
  });
  return assertRefundResponse(unwrap(response, '申请撤销退款失败'), returnOrderId, normalizedRequestId);
}

export async function approveReturnRefundReversal(
  factoryId: string,
  returnOrderId: string,
  requestId: string,
  reversalTransactionId: string,
  options?: { silent?: boolean },
): Promise<ReturnRefund> {
  const normalizedRequestId = String(requestId || '').trim();
  const normalizedTransactionId = String(reversalTransactionId || '').trim();
  if (!normalizedRequestId || !normalizedTransactionId) throw new ApiError('撤销退款申请号或撤销流水号为空');
  const url = `${basePath(factoryId, returnOrderId)}/refund/reversal/approve`;
  const payload = {
    requestId: normalizedRequestId,
    reversalTransactionId: normalizedTransactionId,
  };
  const response = options?.silent
    ? await post<ReturnRefund>(url, payload, { _silent: true })
    : await post<ReturnRefund>(url, payload);
  return assertRefundResponse(unwrap(response, '确认撤销退款失败'), returnOrderId, normalizedRequestId);
}

export async function rejectReturnRefundReversal(
  factoryId: string,
  returnOrderId: string,
  requestId: string,
  reversalTransactionId: string,
  reason: string,
): Promise<ReturnRefund> {
  const normalizedRequestId = String(requestId || '').trim();
  const normalizedTransactionId = String(reversalTransactionId || '').trim();
  const normalizedReason = reason.trim();
  if (!normalizedRequestId || !normalizedTransactionId || !normalizedReason) {
    throw new ApiError('撤销退款申请号、撤销流水号和驳回原因均不能为空');
  }
  if (normalizedReason.length > 80) throw new ApiError('驳回撤销退款原因最多80字');
  const response = await post<ReturnRefund>(`${basePath(factoryId, returnOrderId)}/refund/reversal/reject`, {
    requestId: normalizedRequestId,
    reversalTransactionId: normalizedTransactionId,
    reason: normalizedReason,
  });
  return assertRefundResponse(unwrap(response, '驳回撤销退款申请失败'), returnOrderId, normalizedRequestId);
}
