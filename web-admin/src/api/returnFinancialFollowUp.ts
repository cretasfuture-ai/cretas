import { get, post } from './request';

export interface ReturnAdjustmentRow {
  id: string;
  factoryId: string;
  transactionNumber: string;
  transactionType: 'AR_ADJUSTMENT';
  counterpartyType: 'CUSTOMER';
  counterpartyId: string;
  counterpartyName?: string;
  amount: number | null;
  balanceAfter: number | null;
  approvalStatus: 'PENDING' | 'APPROVED' | 'REJECTED';
  operatedBy: number;
  sourceType: 'RETURN_ORDER';
  sourceId: string;
  createdAt: string;
  remark?: string;
}

export interface ReturnFinancialFollowUp {
  returnOrderId: string;
  returnNumber: string;
  status: string;
  canResubmit: boolean;
  adjustments: ReturnAdjustmentRow[];
  voucher: { id: string; voucherNumber: string; status: string } | null;
}

export function validReturnOrderId(value: unknown): string | null {
  return typeof value === 'string' && /^[A-Za-z0-9_-]{1,191}$/.test(value) ? value : null;
}

export async function getReturnFinancialFollowUp(factoryId: string, returnOrderId: string): Promise<ReturnFinancialFollowUp> {
  const response = await get<ReturnFinancialFollowUp>(
    `/${factoryId}/return-orders/${encodeURIComponent(returnOrderId)}/financial-follow-up`,
  );
  if (!response.success || !response.data || response.data.returnOrderId !== returnOrderId
      || !Array.isArray(response.data.adjustments)) throw new Error(response.message || '退货应收处理信息不完整，请重新读取');
  if (response.data.adjustments.some(row => row.factoryId !== factoryId || row.sourceType !== 'RETURN_ORDER'
      || row.sourceId !== returnOrderId || row.transactionType !== 'AR_ADJUSTMENT')) {
    throw new Error('应收流水来源与当前退货单不一致，请重新读取');
  }
  return response.data;
}

export async function resubmitReturnAdjustment(factoryId: string, returnOrderId: string, reason: string): Promise<void> {
  const response = await post(`/${factoryId}/return-orders/${encodeURIComponent(returnOrderId)}/resubmit-adjustment`, { reason });
  if (!response.success) throw new Error(response.message || '重新提交应收调整失败');
}

export function financialStatusText(status: string | undefined): string {
  return ({ PENDING: '待财务审批', APPROVED: '应收调整已生效', REJECTED: '已驳回，需核对后重提',
    MISSING: '未找到应收调整，需财务核对', NOT_DUE: '退货尚未完成', NOT_REQUIRED: '零金额，无需调整',
    INCONSISTENT: '调整证据不一致，需财务核对' } as Record<string, string>)[status || ''] || '尚未确认';
}
