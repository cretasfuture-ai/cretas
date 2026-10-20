import request, { post } from '@/api/request';
import { getPurchaseTaskGrantStatus, type IntentExecuteResponse } from '@/api/smartbi/intent-chat';

export interface ControlledPurchaseOutcome {
  grantId: string;
  state: 'PENDING' | 'EXECUTING' | 'DRAFT' | 'REVOKED' | 'EXPIRED' | 'UNKNOWN' | string;
  purchaseOrderId?: string | null;
  grossAmount?: string | number | null;
  quantity?: string | number | null;
  version?: number | null;
  replay?: boolean;
}

export interface ControlledPurchaseParams {
  supplierId: string;
  materialTypeId: string;
  quantity: string;
  unit: 'kg';
  unitPrice: string;
  currency: 'CNY';
  taxRate: '0' | '13';
  amountCap: string;
  expectedDeliveryDate?: string;
  remark?: string;
}

export async function previewControlledPurchase(
  factoryId: string,
  params: ControlledPurchaseParams,
): Promise<IntentExecuteResponse> {
  const payload: Record<string, unknown> = { ...params };
  if (!String(params.expectedDeliveryDate || '').trim()) delete payload.expectedDeliveryDate;
  if (!String(params.remark || '').trim()) delete payload.remark;
  const response = await request.post<IntentExecuteResponse>(`/${factoryId}/ai-intents/execute`, {
    intentCode: 'PURCHASE_ORDER_CREATE',
    userInput: '创建受控采购草稿',
    previewOnly: true,
    mode: 'OPERATE',
    context: payload,
  });
  const envelope = response as unknown as { data?: IntentExecuteResponse };
  return envelope.data ?? (response as unknown as IntentExecuteResponse);
}

export async function getControlledPurchaseStatus(
  factoryId: string,
  grantId: string,
): Promise<ControlledPurchaseOutcome> {
  return getPurchaseTaskGrantStatus(factoryId, grantId);
}

export async function revokeControlledPurchase(
  factoryId: string,
  grantId: string,
  expectedVersion: number | null | undefined,
  reason: string,
): Promise<ControlledPurchaseOutcome> {
  const response = await post<ControlledPurchaseOutcome>(
    `/${factoryId}/ai-purchase-task-grants/${encodeURIComponent(grantId)}/revoke`,
    { expectedVersion, reason: reason.trim() },
  );
  if (!response.success || !response.data) throw new Error(response.message || '撤销采购授权失败');
  if (response.data.grantId !== grantId || typeof response.data.state !== 'string') {
    throw new Error('撤销结果与当前采购参考不匹配，请查询授权状态');
  }
  return response.data;
}
