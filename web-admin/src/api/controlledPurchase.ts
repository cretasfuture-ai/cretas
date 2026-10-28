import request, { get, post } from '@/api/request';
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

export interface PurchaseTimerBinding {
  bindingId: string;
  grantId: string;
  taskId: string;
  executeAt: string;
  expiresAt: string;
  state: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'REFUSED' | 'UNKNOWN';
  purchaseOrderId?: string | null;
  failureReason?: string | null;
  approvedSnapshot?: string | null;
  serverNow?: string;
  serverZone?: string;
}

export async function getPurchaseTimer(factoryId: string, grantId: string): Promise<PurchaseTimerBinding | null> {
  const response = await get<PurchaseTimerBinding | null>(`/${factoryId}/ai-purchase-task-grants/${encodeURIComponent(grantId)}/timer`);
  if (!response.success) throw new Error(response.message || '定时绑定读取失败');
  if (response.data == null) return null;
  if (response.data.grantId !== grantId || !response.data.bindingId || !response.data.state) throw new Error('定时绑定与当前授权不匹配');
  return response.data;
}

export async function bindPurchaseTimer(factoryId: string, grantId: string, expectedVersion: number, executeAt: string): Promise<PurchaseTimerBinding> {
  const response = await post<PurchaseTimerBinding>(`/${factoryId}/ai-purchase-task-grants/${encodeURIComponent(grantId)}/timer`, { expectedVersion, executeAt });
  if (!response.success || !response.data || response.data.grantId !== grantId || !response.data.bindingId) throw new Error(response.message || '定时绑定未确认，请读取原状态，禁止重复提交');
  return response.data;
}

export async function reconcilePurchaseTimer(factoryId: string, grantId: string): Promise<PurchaseTimerBinding> {
  const response = await post<PurchaseTimerBinding>(`/${factoryId}/ai-purchase-task-grants/${encodeURIComponent(grantId)}/timer/reconcile`, {});
  if (!response.success || !response.data || response.data.grantId !== grantId) throw new Error(response.message || '持久定时结果无法核对');
  return response.data;
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

export async function authorizeControlledPurchaseForLater(
  factoryId: string,
  proofToken: string,
  commandDigest: string,
  expiresAt: string,
): Promise<ControlledPurchaseOutcome> {
  const response = await post<ControlledPurchaseOutcome>(
    `/${factoryId}/ai-purchase-task-grants/authorize-later`, { proofToken, commandDigest, expiresAt },
  );
  if (!response.success || !response.data) throw new Error(response.message || '稍后执行授权失败');
  return response.data;
}

export async function executeDeferredControlledPurchase(
  factoryId: string,
  grantId: string,
  expectedVersion: number,
  channel: 'http' | 'tool' | 'skill' = 'http',
): Promise<ControlledPurchaseOutcome> {
  const operation = channel === 'http' ? 'execute' : `execute-${channel}`;
  const response = await post<ControlledPurchaseOutcome>(
    `/${factoryId}/ai-purchase-task-grants/${encodeURIComponent(grantId)}/${operation}`, { expectedVersion },
  );
  if (!response.success || !response.data) throw new Error(response.message || '稍后执行采购失败');
  return response.data;
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
