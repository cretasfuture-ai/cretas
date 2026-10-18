import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));

vi.mock('@/api/request', () => requestMock);

import {
  approveReturnRefund,
  approveReturnRefundReversal,
  createReturnRefund,
  getReturnOrderStockLineage,
  getReturnRefund,
  getReturnRefundByRequest,
  markReturnRefundPaid,
  rejectReturnRefund,
  rejectReturnRefundReversal,
  requestReturnRefundReversal,
  submitReturnRefund,
} from '../inventory';

const refundView = {
  returnOrderId: 'return/7',
  returnNumber: 'RT-7',
  eligibilityStatus: 'ELIGIBLE',
  reason: null,
  refundableAmount: 88.5,
  request: {
    id: 'RF-7', requestNumber: 'RF-20261016-7', status: 'PAID', amount: 88.5,
    paymentMethod: 'CASH', createdBy: 93001, approvedBy: 1, paidBy: 1,
    paidAt: '2026-10-16T11:00:00', paymentReference: '模拟-7', arapTransactionId: 'AR-7',
    ledger: {
      id: 'LEDGER-7', transactionNumber: 'AR-REFUND-7', transactionType: 'AR_REFUND',
      amount: 88.5, balanceBefore: 100.5, balanceAfter: 12, paymentMethod: 'CASH', paymentReference: '模拟-7',
      approvedBy: 1, approvedByName: '财务主管', approvedAt: '2026-10-16T10:00:00', operatedBy: 1,
      operatedByName: '出纳', transactionDate: '2026-10-16',
    },
    voucher: null,
  },
};

describe('退货来源与客户退款 API boundary', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reads verified and legacy stock lineage without price data', async () => {
    requestMock.get.mockResolvedValueOnce({ success: true, data: {
      returnOrderId: 'return/7',
      returnNumber: 'RT-7',
      lines: [{
        returnOrderItemId: 12,
        sourceFinishedGoodsBatchId: 'FG-1',
        sourceBatchNumber: 'FG-20261016-1',
        productTypeId: 'P-1',
        unit: 'kg',
        quantity: 2,
        sourceStatus: 'VERIFIED',
        returnedStock: [{ id: 'RS-1', batchNumber: 'FG-20261016-1-R', unit: 'kg', remainingQuantity: 2, status: 'AVAILABLE' }],
      }],
    } });

    const result = await getReturnOrderStockLineage('F001', 'return/7');

    expect(requestMock.get).toHaveBeenCalledWith('/F001/return-orders/return%2F7/stock-lineage');
    expect(result.lines[0].sourceStatus).toBe('VERIFIED');
    expect(result.lines[0]).not.toHaveProperty('price');
  });

  it('reads refund eligibility and sends exact lifecycle payloads', async () => {
    requestMock.get.mockResolvedValueOnce({ success: true, data: refundView });
    requestMock.post
      .mockResolvedValueOnce({ success: true, data: refundView })
      .mockResolvedValueOnce({ success: true, data: refundView })
      .mockResolvedValueOnce({ success: true, data: refundView })
      .mockResolvedValueOnce({ success: true, data: refundView })
      .mockResolvedValueOnce({ success: true, data: refundView });

    await getReturnRefund('F001', 'return/7');
    await createReturnRefund('F001', 'return/7', 88.5, '退货冲减货款');
    await submitReturnRefund('F001', 'return/7');
    await approveReturnRefund('F001', 'return/7', '应收已核对');
    await markReturnRefundPaid('F001', 'return/7', 'CASH', 'CASH-20261016-7', '人工登记已退款');
    await rejectReturnRefund('F001', 'return/7', '凭据不足');

    expect(requestMock.get).toHaveBeenCalledWith('/F001/return-orders/return%2F7/refund');
    expect(requestMock.post).toHaveBeenNthCalledWith(1, '/F001/return-orders/return%2F7/refund', {
      amount: 88.5, remark: '退货冲减货款',
    });
    expect(requestMock.post).toHaveBeenNthCalledWith(2, '/F001/return-orders/return%2F7/refund/submit', {});
    expect(requestMock.post).toHaveBeenNthCalledWith(3, '/F001/return-orders/return%2F7/refund/approve', { note: '应收已核对' });
    expect(requestMock.post).toHaveBeenNthCalledWith(4, '/F001/return-orders/return%2F7/refund/mark-paid', {
      paymentMethod: 'CASH', paymentReference: 'CASH-20261016-7', remark: '人工登记已退款',
    });
    expect(requestMock.post).toHaveBeenNthCalledWith(5, '/F001/return-orders/return%2F7/refund/reject', { reason: '凭据不足' });
  });

  it('rejects a response for a different return order instead of showing it as current', async () => {
    requestMock.get.mockResolvedValueOnce({ success: true, data: { ...refundView, returnOrderId: 'return/other' } });

    await expect(getReturnRefund('F001', 'return/7')).rejects.toThrow('退款来源与当前退货单不一致');
  });

  it('reads an exact refund request and rejects an unrelated request id', async () => {
    requestMock.get.mockResolvedValueOnce({ success: true, data: refundView });
    await getReturnRefundByRequest('F001', 'RF-7');
    expect(requestMock.get).toHaveBeenCalledWith('/F001/return-refunds/RF-7');

    requestMock.get.mockResolvedValueOnce({
      success: true,
      data: { ...refundView, request: { ...refundView.request, id: 'RF-other' } },
    });
    await expect(getReturnRefundByRequest('F001', 'RF-7')).rejects.toThrow('退款申请来源与当前请求不一致');
  });

  it('sends reversal request and four-eyes approval commands with exact ids', async () => {
    requestMock.post
      .mockResolvedValueOnce({ success: true, data: refundView })
      .mockResolvedValueOnce({ success: true, data: refundView })
      .mockResolvedValueOnce({ success: true, data: refundView });

    await requestReturnRefundReversal('F001', 'return/7', 'RF-7', '收回-7', '客户已退回款项');
    await approveReturnRefundReversal('F001', 'return/7', 'RF-7', 'REV-LEDGER-7');
    await rejectReturnRefundReversal('F001', 'return/7', 'RF-7', 'REV-LEDGER-7', '凭据需补充');

    expect(requestMock.post).toHaveBeenNthCalledWith(1, '/F001/return-orders/return%2F7/refund/reversal', {
      requestId: 'RF-7', paymentReference: '收回-7', reason: '客户已退回款项',
    });
    expect(requestMock.post).toHaveBeenNthCalledWith(2, '/F001/return-orders/return%2F7/refund/reversal/approve', {
      requestId: 'RF-7', reversalTransactionId: 'REV-LEDGER-7',
    });
    expect(requestMock.post).toHaveBeenNthCalledWith(3, '/F001/return-orders/return%2F7/refund/reversal/reject', {
      requestId: 'RF-7', reversalTransactionId: 'REV-LEDGER-7', reason: '凭据需补充',
    });
  });

  it('allows the mounted refund page to own approval error presentation', async () => {
    requestMock.post.mockResolvedValueOnce({ success: true, data: refundView });
    await approveReturnRefundReversal('F001', 'return/7', 'RF-7', 'REV-LEDGER-7', { silent: true });
    expect(requestMock.post).toHaveBeenCalledWith('/F001/return-orders/return%2F7/refund/reversal/approve', {
      requestId: 'RF-7', reversalTransactionId: 'REV-LEDGER-7',
    }, { _silent: true });
  });

  it('rejects reversal commands with missing identity fields before sending', async () => {
    await expect(requestReturnRefundReversal('F001', 'return/7', ' ', '收回-7', '原因')).rejects.toThrow('不能为空');
    await expect(approveReturnRefundReversal('F001', 'return/7', 'RF-7', '')).rejects.toThrow('为空');
    await expect(rejectReturnRefundReversal('F001', 'return/7', 'RF-7', 'REV-7', ' ')).rejects.toThrow('不能为空');
    expect(requestMock.post).not.toHaveBeenCalled();
  });

  it('rejects an overlong reversal rejection reason before sending', async () => {
    await expect(rejectReturnRefundReversal('F001', 'return/7', 'RF-7', 'REV-7', 'a'.repeat(81)))
      .rejects.toThrow('最多80字');
    expect(requestMock.post).not.toHaveBeenCalled();
  });

  it('accepts a 400-character reversal reason but rejects 401 and overlong evidence', async () => {
    requestMock.post.mockResolvedValueOnce({ success: true, data: refundView });
    await requestReturnRefundReversal('F001', 'return/7', 'RF-7', 'r'.repeat(100), 'a'.repeat(400));
    expect(requestMock.post).toHaveBeenCalledWith('/F001/return-orders/return%2F7/refund/reversal', {
      requestId: 'RF-7', paymentReference: 'r'.repeat(100), reason: 'a'.repeat(400),
    });

    requestMock.post.mockClear();
    await expect(requestReturnRefundReversal('F001', 'return/7', 'RF-7', '收回-7', 'a'.repeat(401)))
      .rejects.toThrow('撤销退款原因最多400字');
    await expect(requestReturnRefundReversal('F001', 'return/7', 'RF-7', 'r'.repeat(101), '原因'))
      .rejects.toThrow('款项收回凭据最多100字');
    expect(requestMock.post).not.toHaveBeenCalled();
  });
});
