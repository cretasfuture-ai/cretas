import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
import { ApiError } from '@/types/api';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';
import ReturnDetail from '../detail.vue';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  lineage: vi.fn(),
  refund: vi.fn(),
  createRefund: vi.fn(),
  requestReversal: vi.fn(),
  approveReversal: vi.fn(),
  rejectReversal: vi.fn(),
  refundByRequest: vi.fn(),
  submitRefund: vi.fn(),
  approveRefund: vi.fn(),
  markPaid: vi.fn(),
  rejectRefund: vi.fn(),
  confirm: vi.fn(),
  prompt: vi.fn(),
  financial: vi.fn(),
  push: vi.fn(),
  replace: vi.fn(),
  permissions: { sales: true, finance: true },
}));

const route = reactive<{ params: { id: string }; query: Record<string, unknown>; name: string }>({
  params: { id: 'return-a' }, query: {}, name: 'SalesReturnDetail',
});
const identity = reactive({ factoryId: 'F001', user: { id: 7 } });

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus');
  return {
    ...actual,
    ElMessageBox: { ...actual.ElMessageBox, confirm: mocks.confirm, prompt: mocks.prompt },
  };
});

vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: mocks.push, replace: mocks.replace, back: vi.fn() }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => identity }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    canWrite: (module: string) => module === 'finance' ? mocks.permissions.finance : mocks.permissions.sales,
    canViewPrice: true,
    canAccess: () => true,
  }),
}));
vi.mock('@/api/request', () => ({ get: mocks.get, post: mocks.post }));
vi.mock('@/api/factoryRecall', () => ({ getFactoryRecall: vi.fn() }));
vi.mock('@/api/returnFinancialFollowUp', () => ({
  getReturnFinancialFollowUp: mocks.financial,
  resubmitReturnAdjustment: vi.fn(),
  financialStatusText: (status: string) => status,
}));
vi.mock('@/api/inventory', () => ({
  getReturnOrderStockLineage: mocks.lineage,
  getReturnRefund: mocks.refund,
  getReturnRefundByRequest: mocks.refundByRequest,
  createReturnRefund: mocks.createRefund,
  requestReturnRefundReversal: mocks.requestReversal,
  approveReturnRefundReversal: mocks.approveReversal,
  rejectReturnRefundReversal: mocks.rejectReversal,
  submitReturnRefund: mocks.submitRefund,
  approveReturnRefund: mocks.approveRefund,
  markReturnRefundPaid: mocks.markPaid,
  rejectReturnRefund: mocks.rejectRefund,
}));

const lineageA = {
  returnOrderId: 'return-a', returnNumber: 'RT-A', lines: [{
    returnOrderItemId: 'item-a', sourceFinishedGoodsBatchId: 'FG-1', sourceBatchNumber: 'FG-SOURCE-A',
    productTypeId: 'P-1', unit: 'kg', quantity: 2, sourceStatus: 'VERIFIED',
    returnedStock: [{ id: 'stock-a', batchNumber: 'FG-RETURN-A', unit: 'kg', remainingQuantity: 2, status: 'QUARANTINED' }],
  }],
};
const refundEligible = {
  returnOrderId: 'return-a', returnNumber: 'RT-A', eligibilityStatus: 'ELIGIBLE', reason: null,
  refundableAmount: 12, request: null,
};

function paidRefund(overrides: Record<string, unknown> = {}) {
  return {
    ...refundEligible,
    eligibilityStatus: 'PAID',
    refundableAmount: 0,
    request: {
      id: 'RF-1', requestNumber: 'RF-20261016-1', status: 'PAID', amount: 12,
      paymentMethod: 'CASH', createdBy: 3, approvedBy: 1, paidBy: 1,
      paidAt: '2026-10-16T11:00:00', paymentReference: '模拟-退款-1', arapTransactionId: 'AR-1',
      ledger: {
        id: 'LEDGER-1', transactionNumber: 'AR-REFUND-1', transactionType: 'AR_REFUND', amount: 12,
        balanceBefore: 100, balanceAfter: 88, paymentMethod: 'CASH', paymentReference: '模拟-退款-1',
        approvedBy: 1, approvedByName: '财务主管', approvedAt: '2026-10-16T10:00:00', operatedBy: 1,
        operatedByName: '出纳', transactionDate: '2026-10-16',
      }, voucher: null,
    },
    ...overrides,
  };
}

function pendingReversalRefund() {
  return paidRefund({ request: {
    ...paidRefund().request,
    reversal: {
      status: 'PENDING', reason: '款项已收回', requestedAt: '2026-10-16T12:00:00',
      ledger: {
        id: 'REV-LEDGER-1', transactionNumber: 'AR-REVERSAL-1', transactionType: 'AR_ADJUSTMENT', amount: -12,
        balanceBefore: null, balanceAfter: null, paymentMethod: 'CASH', paymentReference: '收回-1',
        approvedBy: null, approvedByName: null, approvedAt: null, operatedBy: 7,
        operatedByName: '发起人', transactionDate: '2026-10-16',
      }, voucher: null,
    },
  } });
}

function order(id: string, status = 'COMPLETED') {
  return {
    id, returnNumber: id === 'return-a' ? 'RT-A' : 'RT-B', returnType: 'SALES_RETURN', status,
    withGoods: true, totalAmount: 12, items: [{ id: id === 'return-a' ? 'item-a' : 'item-b', itemName: '成品', quantity: 2, unitPrice: 6 }],
  };
}

function render() {
  return mount(ReturnDetail, { global: { plugins: [ElementPlus] } });
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.permissions.sales = true;
  mocks.permissions.finance = true;
  route.params.id = 'return-a'; route.query = {}; route.name = 'SalesReturnDetail';
  identity.factoryId = 'F001'; identity.user.id = 7;
  mocks.get.mockImplementation((path: string) => Promise.resolve({ success: true, data: path.endsWith('return-a') ? order('return-a') : order('return-b') }));
  mocks.lineage.mockResolvedValue(lineageA);
  mocks.refund.mockResolvedValue(refundEligible);
  mocks.refundByRequest.mockResolvedValue(refundEligible);
  mocks.createRefund.mockResolvedValue(refundEligible);
  mocks.requestReversal.mockResolvedValue(refundEligible);
  mocks.approveReversal.mockResolvedValue(refundEligible);
  mocks.rejectReversal.mockResolvedValue(refundEligible);
  mocks.submitRefund.mockResolvedValue(refundEligible);
  mocks.approveRefund.mockResolvedValue(refundEligible);
  mocks.markPaid.mockResolvedValue(refundEligible);
  mocks.rejectRefund.mockResolvedValue(refundEligible);
  mocks.confirm.mockResolvedValue(true);
  mocks.prompt.mockResolvedValue({ value: '凭据不足' });
  mocks.replace.mockImplementation(async (location: { query?: Record<string, unknown> }) => {
    route.query = location.query || {};
  });
  mocks.financial.mockResolvedValue({ returnOrderId: 'return-a', returnNumber: 'RT-A', status: 'NOT_REQUIRED', canResubmit: false, adjustments: [], voucher: null });
});

describe('销售退货详情的来源与现金退款', () => {
  it.each(['FINANCE_APPROVED', 'PROCESSING'])('财务别名在%s时不显示无销售权限的收货操作，返回财务列表', async (status) => {
    route.name = 'FinanceReturnRefundDetail';
    mocks.permissions.sales = false;
    mocks.get.mockResolvedValue({ success: true, data: order('return-a', status) });
    const wrapper = render();
    await flushPromises();

    expect(wrapper.findAll('button').some(button => /确认退货收货|标记完成/.test(button.text()))).toBe(false);
    expect(mocks.post).not.toHaveBeenCalled();
    await wrapper.findAll('button').find(button => button.text() === '返回')!.trigger('click');
    expect(mocks.push).toHaveBeenCalledWith('/finance/adjustments');
    wrapper.unmount();
  });

  it('财务别名可挂载退款卡，但销售提交按钮保持禁用', async () => {
    route.name = 'FinanceReturnRefundDetail';
    mocks.permissions.sales = false;
    mocks.get.mockResolvedValue({ success: true, data: order('return-a', 'DRAFT') });
    const wrapper = render();
    await flushPromises();

    expect(wrapper.text()).toContain('客户现金退款（人工登记）');
    const submitButton = wrapper.findAll('button').find(button => button.text().includes('提交审批'));
    expect(submitButton).toBeTruthy();
    expect(submitButton!.attributes('disabled')).toBeDefined();
    expect(mocks.post).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('renders stable source and returned-stock mapping, then creates a refund through the mounted button', async () => {
    const wrapper = render();
    await flushPromises();

    expect(wrapper.text()).toContain('FG-SOURCE-A');
    expect(wrapper.text()).toContain('FG-RETURN-A · 剩余 2 kg');
    const createButton = wrapper.findAll('button').find(button => button.text().includes('新建退款申请'));
    expect(createButton).toBeTruthy();
    await createButton!.trigger('click');
    const vm = wrapper.vm as unknown as { refundAmount: number | null; refundRemark: string; submitRefundCreate: () => Promise<void> };
    vm.refundAmount = 12;
    vm.refundRemark = '已核对退货应收冲减';
    await wrapper.findAll('button').find(button => button.text().includes('创建申请'))!.trigger('click');
    await flushPromises();

    expect(mocks.createRefund).toHaveBeenCalledWith('F001', 'return-a', 12, '已核对退货应收冲减');
    wrapper.unmount();
  });

  it('does not let a late lineage response from the previous return overwrite the current return', async () => {
    let resolveOld!: (value: unknown) => void;
    mocks.lineage.mockReset();
    mocks.lineage.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }));
    mocks.lineage.mockResolvedValue({ ...lineageA, returnOrderId: 'return-b', lines: [{ ...lineageA.lines[0], returnOrderItemId: 'item-b', sourceBatchNumber: 'FG-SOURCE-B', returnedStock: [] }] });
    const wrapper = render();
    await flushPromises();
    route.params.id = 'return-b';
    await flushPromises();
    resolveOld(lineageA);
    await flushPromises();

    expect(wrapper.text()).toContain('FG-SOURCE-B');
    expect(wrapper.text()).not.toContain('FG-SOURCE-A');
    wrapper.unmount();
  });

  it('keeps the refund error visible instead of showing an eligible or paid state', async () => {
    mocks.refund.mockRejectedValueOnce(new Error('应收冲减证据未核对'));
    const wrapper = render();
    await flushPromises();

    expect(wrapper.text()).toContain('应收冲减证据未核对');
    expect(wrapper.text()).toContain('退款信息暂不可用');
    wrapper.unmount();
  });

  it('does not present zero refundable amount as an actionable refund', async () => {
    mocks.refund.mockResolvedValueOnce({ ...refundEligible, refundableAmount: 0 });
    const wrapper = render();
    await flushPromises();

    expect(wrapper.text()).toContain('当前没有可退金额，请先核对原销售单收款和应收调整');
    const createButton = wrapper.findAll('button').find(button => button.text().includes('新建退款申请'));
    expect(createButton?.attributes('disabled')).toBeDefined();
    wrapper.unmount();
  });

  it('uses the mounted payment dialog to register a manual refund with evidence', async () => {
    mocks.refund.mockResolvedValueOnce({
      ...refundEligible,
      request: {
        id: 'RF-1', requestNumber: 'RF-20261016-1', status: 'APPROVED', amount: 12,
        paymentMethod: null, createdBy: 3, approvedBy: 1, paidBy: null, paidAt: null,
        paymentReference: null, arapTransactionId: null, voucher: null,
      },
    });
    const wrapper = render();
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text().includes('登记已退款'))!.trigger('click');
    const vm = wrapper.vm as unknown as {
      refundPaymentReference: string;
      refundPaymentRemark: string;
      submitRefundPaid: () => Promise<void>;
    };
    vm.refundPaymentReference = '模拟-退款-1';
    vm.refundPaymentRemark = '本地演练请注明模拟';
    await wrapper.findAll('button').find(button => button.text().includes('确认登记'))!.trigger('click');
    await flushPromises();

    expect(mocks.markPaid).toHaveBeenCalledWith('F001', 'return-a', 'CASH', '模拟-退款-1', '本地演练请注明模拟');
    wrapper.unmount();
  });

  it('requires reversal evidence in the mounted dialog and submits the exact refund request', async () => {
    mocks.refund.mockResolvedValueOnce(paidRefund());
    const wrapper = render();
    await flushPromises();

    await wrapper.findAll('button').find(button => button.text() === '申请撤销退款')!.trigger('click');
    await wrapper.findAll('button').find(button => button.text() === '提交撤销申请')!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('请填写款项收回凭据');
    expect(mocks.requestReversal).not.toHaveBeenCalled();

    const vm = wrapper.vm as unknown as { reversalPaymentReference: string; reversalReason: string };
    vm.reversalPaymentReference = '收回-凭据-1';
    vm.reversalReason = '款项已收回，申请撤销原退款';
    await wrapper.findAll('button').find(button => button.text() === '提交撤销申请')!.trigger('click');
    await flushPromises();

    expect(mocks.requestReversal).toHaveBeenCalledWith(
      'F001', 'return-a', 'RF-1', '收回-凭据-1', '款项已收回，申请撤销原退款',
    );
    wrapper.unmount();
  });

  it('accepts a 400-character reversal reason from the mounted dialog', async () => {
    mocks.refund.mockResolvedValueOnce(paidRefund());
    const wrapper = render();
    await flushPromises();

    await wrapper.findAll('button').find(button => button.text() === '申请撤销退款')!.trigger('click');
    const vm = wrapper.vm as unknown as { reversalPaymentReference: string; reversalReason: string };
    vm.reversalPaymentReference = 'r'.repeat(100);
    vm.reversalReason = 'a'.repeat(400);
    await wrapper.findAll('button').find(button => button.text() === '提交撤销申请')!.trigger('click');
    await flushPromises();

    expect(mocks.requestReversal).toHaveBeenCalledWith('F001', 'return-a', 'RF-1', 'r'.repeat(100), 'a'.repeat(400));
    wrapper.unmount();
  });

  it('rejects a 401-character reversal reason before submitting from the mounted dialog', async () => {
    mocks.refund.mockResolvedValueOnce(paidRefund());
    const wrapper = render();
    await flushPromises();

    await wrapper.findAll('button').find(button => button.text() === '申请撤销退款')!.trigger('click');
    const vm = wrapper.vm as unknown as { reversalPaymentReference: string; reversalReason: string };
    vm.reversalPaymentReference = '收回-凭据-1';
    vm.reversalReason = 'a'.repeat(401);
    await wrapper.findAll('button').find(button => button.text() === '提交撤销申请')!.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('撤销退款原因最多400字');
    expect(mocks.requestReversal).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('keeps pending reversal balances unchanged and blocks self approval', async () => {
    mocks.refund.mockResolvedValueOnce(pendingReversalRefund());
    const wrapper = render();
    await flushPromises();

    expect(wrapper.text()).toContain('撤销待审批');
    expect(wrapper.text()).toContain('待审批（余额未变）');
    const approve = wrapper.findAll('button').find(button => button.text().includes('确认款项已收回并撤销'))!;
    const reject = wrapper.findAll('button').find(button => button.text() === '驳回')!;
    expect(approve.attributes('disabled')).toBeDefined();
    expect(reject.attributes('disabled')).toBeDefined();
    expect(mocks.approveReversal).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('lets an independent finance user approve or reject by reversal ledger id', async () => {
    identity.user.id = 8;
    mocks.refund.mockResolvedValue(pendingReversalRefund());
    const wrapper = render();
    await flushPromises();

    mocks.confirm.mockResolvedValueOnce(true);
    await wrapper.findAll('button').find(button => button.text().includes('确认款项已收回并撤销'))!.trigger('click');
    await flushPromises();
    expect(mocks.approveReversal).toHaveBeenCalledWith('F001', 'return-a', 'RF-1', 'REV-LEDGER-1', { silent: true });

    await flushPromises();
    mocks.prompt.mockResolvedValueOnce({ value: '凭据需补充' });
    await wrapper.findAll('button').find(button => button.text() === '驳回')!.trigger('click');
    await flushPromises();
    const promptOptions = mocks.prompt.mock.calls.at(-1)?.[2];
    expect(promptOptions.inputValidator('a'.repeat(80))).toBe(true);
    expect(promptOptions.inputValidator('a'.repeat(81))).toBe('驳回原因最多80字');
    expect(mocks.rejectReversal).toHaveBeenCalledWith('F001', 'return-a', 'RF-1', 'REV-LEDGER-1', '凭据需补充');
    wrapper.unmount();
  });

  it('opens only one reversal approval confirmation while the first is pending', async () => {
    identity.user.id = 8;
    mocks.refund.mockResolvedValue(pendingReversalRefund());
    let resolveConfirm!: (value: boolean) => void;
    mocks.confirm.mockReturnValueOnce(new Promise<boolean>(resolve => { resolveConfirm = resolve; }));
    const wrapper = render();
    await flushPromises();
    const button = wrapper.findAll('button').find(item => item.text().includes('确认款项已收回并撤销'))!;
    await button.trigger('click');
    await button.trigger('click');
    await wrapper.findAll('button').find(item => item.text() === '驳回')!.trigger('click');
    expect(mocks.confirm).toHaveBeenCalledTimes(1);
    expect(mocks.prompt).not.toHaveBeenCalled();
    expect(mocks.approveReversal).not.toHaveBeenCalled();
    resolveConfirm(true);
    await flushPromises();
    expect(mocks.approveReversal).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('keeps rejection and approval mutually exclusive while the rejection reason is pending', async () => {
    identity.user.id = 8;
    mocks.refund.mockResolvedValue(pendingReversalRefund());
    let resolvePrompt!: (value: { value: string }) => void;
    mocks.prompt.mockReturnValueOnce(new Promise<{ value: string }>(resolve => { resolvePrompt = resolve; }));
    const wrapper = render();
    await flushPromises();
    const reject = wrapper.findAll('button').find(item => item.text() === '驳回')!;
    await reject.trigger('click');
    await reject.trigger('click');
    await wrapper.findAll('button').find(item => item.text().includes('确认款项已收回并撤销'))!.trigger('click');
    expect(mocks.prompt).toHaveBeenCalledTimes(1);
    expect(mocks.confirm).not.toHaveBeenCalled();
    expect(mocks.rejectReversal).not.toHaveBeenCalled();
    resolvePrompt({ value: '本地核对凭据不足' });
    await flushPromises();
    expect(mocks.rejectReversal).toHaveBeenCalledTimes(1);
    expect(mocks.approveReversal).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('keeps a locked reversal pending and offers exact same-actor period recovery', async () => {
    identity.user.id = 8;
    mocks.refund.mockResolvedValue(pendingReversalRefund());
    mocks.approveReversal.mockRejectedValueOnce(new ApiError('2026-10 期间已结账', '400', 400, '/finance/accounting-period?year=2026&month=10'));
    const wrapper = render();
    await flushPromises();
    mocks.confirm.mockResolvedValueOnce(true);
    await wrapper.findAll('button').find(button => button.text().includes('确认款项已收回并撤销'))!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('本次撤销未生效');
    expect(wrapper.text()).toContain('待审批（余额未变）');
    await wrapper.findAll('button').find(button => button.text() === '处理锁账并返回本单')!.trigger('click');
    expect(mocks.push).toHaveBeenCalledWith({ path: '/finance/accounting-period', query: {
      year: '2026', month: '10', _returnTo: '/finance/return-refunds/return-a?refundRequestId=RF-1&ledgerId=REV-LEDGER-1',
      _periodRecovery: '1', sourceFactoryId: 'F001', sourceActorId: '8',
    } });
    identity.user.id = 9;
    await flushPromises();
    expect(wrapper.findAll('button').some(button => button.text() === '处理锁账并返回本单')).toBe(false);
    wrapper.unmount();
  });

  it('does not convert unrelated approval errors into an accounting recovery route', async () => {
    identity.user.id = 8;
    mocks.refund.mockResolvedValue(pendingReversalRefund());
    mocks.approveReversal.mockRejectedValueOnce(new ApiError('没有审批权限', '403', 403, '/finance/accounting-period?year=2026&month=10'));
    const wrapper = render();
    await flushPromises();
    mocks.confirm.mockResolvedValueOnce(true);
    await wrapper.findAll('button').find(button => button.text().includes('确认款项已收回并撤销'))!.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('没有审批权限');
    expect(wrapper.findAll('button').some(button => button.text() === '处理锁账并返回本单')).toBe(false);
    wrapper.unmount();
  });

  it('shows CANCELLED with the original ledger and approved reverse ledger while allowing a new refund', async () => {
    mocks.refund.mockResolvedValueOnce({
      ...paidRefund(),
      eligibilityStatus: 'REVERSED',
      refundableAmount: 12,
      request: {
        ...paidRefund().request,
        status: 'CANCELLED',
        voucher: { id: 'V-ORIGINAL-1', voucherNumber: 'V-ORIGINAL-1', status: 'REVERSED' },
        reversal: {
          status: 'APPROVED', reason: '客户已收回款项', requestedAt: '2026-10-16T12:00:00',
          ledger: {
            id: 'REV-LEDGER-1', transactionNumber: 'AR-REVERSAL-1', transactionType: 'AR_ADJUSTMENT', amount: -12,
            balanceBefore: 88, balanceAfter: 100, paymentMethod: 'CASH', paymentReference: '收回-1',
            approvedBy: 8, approvedByName: '独立财务', approvedAt: '2026-10-16T12:30:00', operatedBy: 7,
            operatedByName: '发起人', transactionDate: '2026-10-16',
          }, voucher: { id: 'V-REV-1', voucherNumber: 'V-REV-1', status: 'POSTED' },
        },
      },
    });
    const wrapper = render();
    await flushPromises();

    expect(wrapper.text()).toContain('已撤销退款，可重新申请');
    expect(wrapper.text()).toContain('AR-REFUND-1');
    expect(wrapper.text()).toContain('AR-REVERSAL-1');
    expect(wrapper.text()).toContain('独立财务（编号 #8）');
    expect(wrapper.text()).toContain('V-REV-1');
    expect(wrapper.text()).toContain('V-ORIGINAL-1（已冲销）');
    expect(wrapper.text()).not.toContain('REVERSED');
    expect(wrapper.findAll('button').some(button => button.text().includes('新建退款申请'))).toBe(true);
    wrapper.unmount();
  });

  it('shows the returned AR refund ledger and does not confuse PAID with a missing ledger', async () => {
    mocks.refund.mockResolvedValueOnce({
      ...refundEligible,
      eligibilityStatus: 'PAID',
      refundableAmount: 0,
      request: {
        id: 'RF-1', requestNumber: 'RF-20261016-1', status: 'PAID', amount: 12,
        paymentMethod: 'CASH', createdBy: 3, approvedBy: 1, paidBy: 1,
        paidAt: '2026-10-16T11:00:00', paymentReference: '模拟-退款-1', arapTransactionId: 'AR-1',
        ledger: {
          id: 'LEDGER-1', transactionNumber: 'AR-REFUND-1', transactionType: 'AR_REFUND', amount: 12,
          balanceBefore: 100, balanceAfter: 88, paymentMethod: 'CASH', paymentReference: '模拟-退款-1', approvedBy: 1,
          approvedByName: '财务主管', approvedAt: '2026-10-16T10:00:00', operatedBy: 1,
          operatedByName: '出纳', transactionDate: '2026-10-16',
        },
        voucher: null,
      },
    });
    const wrapper = render();
    await flushPromises();

    expect(wrapper.text()).toContain('AR-REFUND-1');
    expect(wrapper.text()).toContain('客户退款结清');
    expect(wrapper.text()).toContain('财务主管（编号 #1）');
    expect(wrapper.text()).toContain('客户余额（登记前）');
    expect(wrapper.text()).toContain('100.00');
    expect(wrapper.text()).toContain('客户余额（登记后）');
    expect(wrapper.text()).not.toContain('资金流水待核对');
    wrapper.unmount();
  });

  it('marks a PAID refund without a ledger as pending reconciliation', async () => {
    mocks.refund.mockResolvedValueOnce({
      ...refundEligible,
      eligibilityStatus: 'PAID',
      refundableAmount: 0,
      request: { id: 'RF-1', requestNumber: 'RF-20261016-1', status: 'PAID', amount: 12,
        paymentMethod: 'CASH', createdBy: 3, approvedBy: 1, paidBy: 1, paidAt: '2026-10-16T11:00:00',
        paymentReference: '模拟-退款-1', arapTransactionId: 'AR-1', ledger: null, voucher: null },
    });
    const wrapper = render();
    await flushPromises();

    expect(wrapper.text()).toContain('已登记退款，资金流水待核对');
    wrapper.unmount();
  });

  it('shows only the exact request and ledger selected by voucher query', async () => {
    route.query = { refundRequestId: 'RF-1', ledgerId: 'LEDGER-1' };
    mocks.refundByRequest.mockResolvedValueOnce({
      ...refundEligible,
      request: {
        id: 'RF-1', requestNumber: 'RF-20261016-1', status: 'PAID', amount: 12,
        createdBy: 3, approvedBy: 1, paidBy: 1, paymentMethod: 'CASH', paidAt: '2026-10-16',
        paymentReference: '模拟-退款-1', arapTransactionId: 'AR-1',
        ledger: { id: 'LEDGER-1', transactionNumber: 'AR-REFUND-1', transactionType: 'AR_REFUND',
          amount: 12, balanceBefore: 100, balanceAfter: 88, paymentMethod: 'CASH', paymentReference: '模拟-退款-1',
          approvedBy: 1, approvedByName: '财务主管', approvedAt: '2026-10-16', operatedBy: 1,
          operatedByName: '出纳', transactionDate: '2026-10-16' }, voucher: null,
      },
    });
    const wrapper = render();
    await flushPromises();
    expect(wrapper.text()).toContain('财务主管（编号 #1）');
    expect(wrapper.text()).toContain('出纳（编号 #1）');
    expect(wrapper.text()).not.toContain('退款链接与当前退款申请或资金流水不一致');
    wrapper.unmount();
  });

  it('accepts the reversal ledger id for an exact historical request query', async () => {
    route.query = { refundRequestId: 'RF-1', ledgerId: 'REV-LEDGER-1' };
    mocks.refundByRequest.mockResolvedValueOnce(pendingReversalRefund());
    const wrapper = render();
    await flushPromises();

    expect(wrapper.text()).toContain('AR-REVERSAL-1');
    expect(wrapper.text()).not.toContain('退款链接与当前退款申请或资金流水不一致');
    wrapper.unmount();
  });

  it('rejects an exact refund request that belongs to another return order', async () => {
    route.query = { refundRequestId: 'RF-other', ledgerId: 'LEDGER-other' };
    mocks.refundByRequest.mockResolvedValueOnce({
      ...refundEligible,
      returnOrderId: 'return-b',
      request: { id: 'RF-other', status: 'PAID', amount: 12, ledger: { id: 'LEDGER-other' } },
    });
    const wrapper = render();
    await flushPromises();

    expect(wrapper.text()).toContain('退款申请来源与当前退货单不一致');
    expect(wrapper.text()).not.toContain('LEDGER-other');
    wrapper.unmount();
  });

  it('clears stale historical query after creating a new refund request', async () => {
    route.query = { refundRequestId: 'RF-1', ledgerId: 'LEDGER-1' };
    mocks.refundByRequest.mockResolvedValueOnce({
      ...paidRefund(),
      eligibilityStatus: 'REVERSED',
      refundableAmount: 12,
      request: { ...paidRefund().request, status: 'CANCELLED' },
    });
    mocks.createRefund.mockResolvedValueOnce(refundEligible);
    mocks.refund.mockResolvedValue({
      ...refundEligible,
      eligibilityStatus: 'ALREADY_REQUESTED',
      refundableAmount: 0,
      request: { id: 'RF-2', requestNumber: 'RF-20261016-2', status: 'PENDING', amount: 12, createdBy: 7 },
    });
    const wrapper = render();
    await flushPromises();

    await wrapper.findAll('button').find(button => button.text().includes('新建退款申请'))!.trigger('click');
    const vm = wrapper.vm as unknown as { refundRemark: string };
    vm.refundRemark = '重新申请退款';
    await wrapper.findAll('button').find(button => button.text().includes('创建申请'))!.trigger('click');
    await flushPromises();

    expect(mocks.createRefund).toHaveBeenCalledWith('F001', 'return-a', 12, '重新申请退款');
    expect(mocks.replace).toHaveBeenCalledWith({ query: {} });
    expect(route.query).toEqual({});
    expect(mocks.refund).toHaveBeenCalled();
    wrapper.unmount();
  });

  it('clears a reversal dialog and stale reversal state when tenant or actor changes', async () => {
    mocks.refund.mockResolvedValueOnce(paidRefund());
    const wrapper = render();
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text() === '申请撤销退款')!.trigger('click');
    expect(wrapper.text()).toContain('款项收回凭据');

    identity.factoryId = '';
    identity.user.id = undefined as unknown as number;
    await flushPromises();

    expect(wrapper.text()).not.toContain('款项收回凭据');
    expect(wrapper.text()).not.toContain('提交撤销申请');
    expect(mocks.requestReversal).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('clears paid refund sensitive state when factory and actor are cleared while an old response is pending', async () => {
    const paidRefund = {
      ...refundEligible,
      eligibilityStatus: 'PAID',
      refundableAmount: 0,
      request: {
        id: 'RF-1', requestNumber: 'RF-20261016-1', status: 'PAID', amount: 12,
        createdBy: 3, approvedBy: 1, paidBy: 1, paymentMethod: 'CASH', paidAt: '2026-10-16',
        paymentReference: '模拟-退款-1', arapTransactionId: 'AR-1',
        ledger: {
          id: 'LEDGER-1', transactionNumber: 'AR-REFUND-1', transactionType: 'AR_REFUND',
          amount: 12, balanceBefore: 100, balanceAfter: 88, paymentMethod: 'CASH',
          paymentReference: '模拟-退款-1', approvedBy: 1, approvedByName: '财务主管',
          approvedAt: '2026-10-16', operatedBy: 1, operatedByName: '出纳', transactionDate: '2026-10-16',
        }, voucher: null,
      },
    };
    mocks.refund.mockResolvedValueOnce(paidRefund);
    const wrapper = render();
    await flushPromises();
    expect(wrapper.text()).toContain('财务主管（编号 #1）');
    expect(wrapper.text()).toContain('AR-REFUND-1');

    let resolveLate!: (value: unknown) => void;
    mocks.refund.mockImplementationOnce(() => new Promise(resolve => { resolveLate = resolve; }));
    route.params.id = 'return-b';
    await flushPromises();
    expect(resolveLate).toBeTypeOf('function');

    identity.factoryId = '';
    identity.user.id = undefined as unknown as number;
    await flushPromises();
    expect(wrapper.text()).not.toContain('财务主管');
    expect(wrapper.text()).not.toContain('出纳');
    expect(wrapper.text()).not.toContain('AR-REFUND-1');
    expect(wrapper.text()).not.toContain('100.00');

    resolveLate(paidRefund);
    await flushPromises();
    expect(wrapper.text()).not.toContain('财务主管');
    expect(wrapper.text()).not.toContain('AR-REFUND-1');
    wrapper.unmount();
  });

  it('rejects a mismatched refund query instead of showing another refund record', async () => {
    route.query = { refundRequestId: 'RF-other', ledgerId: 'LEDGER-other' };
    mocks.refundByRequest.mockResolvedValueOnce({
      ...refundEligible,
      request: { id: 'RF-1', status: 'PAID', amount: 12, ledger: { id: 'LEDGER-1' } },
    });
    const wrapper = render();
    await flushPromises();
    expect(wrapper.text()).toContain('退款链接与当前退款申请或资金流水不一致');
    expect(wrapper.text()).not.toContain('新建退款申请');
    wrapper.unmount();
  });
});
