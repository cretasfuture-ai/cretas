import { flushPromises, mount } from '@vue/test-utils';
import { h, KeepAlive, reactive, ref } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';
import VoucherDetail from '../VoucherDetail.vue';

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  void: vi.fn(),
  confirm: vi.fn(),
  push: vi.fn(),
  refundByRequest: vi.fn(),
  byBusiness: vi.fn(),
}));

const route = reactive<{ name: string; params: { id: string } }>({
  name: 'FinanceVoucherDetail', params: { id: 'voucher-a' },
});
const identity = reactive({ factoryId: 'F001', user: { id: 7 } });

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ back: vi.fn(), push: api.push }),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => identity }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true }),
}));
vi.mock('@/api/voucher', () => ({
  getVoucherDetail: api.get,
  getVoucherByBusiness: api.byBusiness,
  postVoucher: api.post,
  voidVoucher: api.void,
  sumDebit: (entries: Array<{ debit?: number | null }>) => entries.reduce((sum, entry) => sum + Number(entry.debit ?? 0), 0),
  sumCredit: (entries: Array<{ credit?: number | null }>) => entries.reduce((sum, entry) => sum + Number(entry.credit ?? 0), 0),
  isBalanced: (entries: Array<{ debit?: number | null; credit?: number | null }>) => {
    const debit = entries.reduce((sum, entry) => sum + Number(entry.debit ?? 0), 0);
    const credit = entries.reduce((sum, entry) => sum + Number(entry.credit ?? 0), 0);
    return Math.abs(debit - credit) < 0.001;
  },
  VOUCHER_STATUS_LABEL: {
    DRAFT: { text: '草稿', type: 'info' },
    POSTED: { text: '已过账', type: 'success' },
    VOID: { text: '已作废', type: 'danger' },
    REVERSED: { text: '已冲销', type: 'warning' },
  },
  VOUCHER_TYPE_LABEL: { RETURN: '退货' },
  AUXILIARY_TYPE_LABEL: {},
  SOURCE_BUSINESS_TYPE_LABEL: { SALES_ORDER: '销售订单', PURCHASE_ORDER: '采购订单' },
}));
vi.mock('@/api/inventory', () => ({ getReturnRefundByRequest: api.refundByRequest }));
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus');
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, confirm: api.confirm } };
});

function voucher(id: string, sourceBusinessType = 'RETURN_REFUND', sourceBusinessId = 'return-a') {
  return {
    id,
    factoryId: 'F001',
    voucherNumber: `V-${id}`,
    voucherType: 'RETURN',
    voucherDate: '2026-10-16',
    sourceBusinessType,
    sourceBusinessId,
    totalDebit: 0,
    totalCredit: 0,
    status: 'DRAFT',
    description: '退款凭证',
    entries: [{ id: 'line-1', lineNo: 1, subjectCode: '1001', subjectName: '现金', debit: 0, credit: 0 }],
  };
}

function render() {
  return mount(VoucherDetail, { global: { plugins: [ElementPlus] } });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolver) => { resolve = resolver; });
  return { promise, resolve };
}

beforeEach(() => {
  vi.clearAllMocks();
  route.name = 'FinanceVoucherDetail';
  route.params.id = 'voucher-a';
  identity.factoryId = 'F001';
  identity.user.id = 7;
  api.get.mockImplementation((_factoryId: string, id: string) => Promise.resolve(voucher(id)));
  api.post.mockResolvedValue(voucher('voucher-a'));
  api.void.mockResolvedValue(undefined);
  api.confirm.mockResolvedValue(true);
  api.refundByRequest.mockResolvedValue({
    returnOrderId: 'return-a', returnNumber: 'RT-A', eligibilityStatus: 'PAID', reason: null,
    refundableAmount: 0,
    request: {
      id: 'RF-1', requestNumber: 'RF-1', status: 'PAID', amount: 12,
      paymentMethod: 'CASH', createdBy: 7, approvedBy: 1, paidBy: 1, paidAt: '2026-10-16',
      paymentReference: '模拟退款-1', arapTransactionId: 'AR-1',
      ledger: { id: 'LEDGER-1', transactionNumber: 'AR-REFUND-1', transactionType: 'AR_REFUND',
        amount: 12, balanceBefore: 100, balanceAfter: 88, paymentMethod: 'CASH', paymentReference: '模拟退款-1',
        approvedBy: 1, approvedByName: '财务主管', approvedAt: '2026-10-16', operatedBy: 1,
        operatedByName: '出纳', transactionDate: '2026-10-16' },
      voucher: null,
    },
  });
});

describe('凭证详情真实挂载行为', () => {
  it('links an original disposal voucher back to its approval record', async () => {
    api.get.mockResolvedValue({...voucher('voucher-a'),sourceBusinessType:'DISPOSAL_RECORD',sourceBusinessId:'5',status:'REVERSED'});
    const wrapper=render();await flushPromises();
    const approval=wrapper.findAll('button').find(b=>b.text()==='查看报废审批');
    expect(approval).toBeDefined();await approval!.trigger('click');
    expect(api.push).toHaveBeenLastCalledWith({path:'/quality/disposals',query:{disposalId:'5'}});
    expect(api.byBusiness).not.toHaveBeenCalled();wrapper.unmount();
  });
  it('does not offer standalone void for disposal vouchers and explains the correct workflow', async () => {
    api.get.mockResolvedValue({...voucher('voucher-a'),sourceBusinessType:'DISPOSAL_RECORD',sourceBusinessId:'5',status:'POSTED'});
    const wrapper=render();await flushPromises();
    expect(wrapper.text()).toContain('报废凭证不能单独作废或冲销');
    expect(wrapper.findAll('button').some(b=>b.text()==='作废')).toBe(false);
    expect(api.void).not.toHaveBeenCalled();wrapper.unmount();
  });
  function disposalFixtures() {
    const original = { ...voucher('original'), sourceBusinessType: 'DISPOSAL_RECORD', sourceBusinessId: '5', status: 'REVERSED', reversalVoucherId: 'red' };
    const red = { ...voucher('red'), sourceBusinessType: 'VOUCHER_REVERSAL', sourceBusinessId: 'original', originalVoucherId: 'original', status: 'POSTED' };
    const revised = { ...voucher('voucher-a'), sourceBusinessType: 'DISPOSAL_RECORD_REVISION', sourceBusinessId: '5#r2', status: 'POSTED' };
    api.byBusiness.mockResolvedValue(original);
    api.get.mockImplementation((_factory: string, id: string) => Promise.resolve(id === 'red' ? red : revised));
    return { original, red, revised };
  }

  it('links a disposal revision to the matching original, red voucher and disposal record', async () => {
    disposalFixtures();const wrapper=render();await flushPromises();
    expect(api.byBusiness).toHaveBeenCalledWith('F001','DISPOSAL_RECORD','5');
    expect(wrapper.text()).toContain('存货报废损失');expect(wrapper.text()).toContain('报废成本修订');
    for (const [label,path] of [['原凭证 V-original','/finance/voucher/original'],['红字凭证 V-red','/finance/voucher/red']]) {
      await wrapper.findAll('button').find(b=>b.text().includes(label))!.trigger('click');
      expect(api.push).toHaveBeenLastCalledWith(path);
    }
    await wrapper.findAll('button').find(b=>b.text()==='查看报废审批')!.trigger('click');
    expect(api.push).toHaveBeenLastCalledWith({path:'/quality/disposals',query:{disposalId:'5'}});
    wrapper.unmount();
  });

  it('does not display another factory original or request its red voucher', async () => {
    const {original}=disposalFixtures();api.byBusiness.mockResolvedValue({...original,factoryId:'OTHER'});
    const wrapper=render();await flushPromises();
    expect(wrapper.text()).toContain('未找到匹配的原报废凭证');
    expect(wrapper.text()).not.toContain('原凭证 V-original');
    expect(api.get).not.toHaveBeenCalledWith('F001','red');wrapper.unmount();
  });

  it('shows mismatched red links as an error without exposing navigation', async () => {
    const {red,revised}=disposalFixtures();api.get.mockImplementation((_f:string,id:string)=>Promise.resolve(id==='red'?{...red,originalVoucherId:'another'}:revised));
    const wrapper=render();await flushPromises();expect(wrapper.text()).toContain('红字凭证与原报废凭证不匹配');
    expect(wrapper.text()).not.toContain('原凭证 V-original');expect(wrapper.text()).not.toContain('红字凭证 V-red');wrapper.unmount();
  });

  it('ignores a pending disposal lookup after factory or voucher changes', async () => {
    const {original}=disposalFixtures();const pending=deferred<typeof original>();api.byBusiness.mockReturnValueOnce(pending.promise);
    const wrapper=render();await flushPromises();
    identity.factoryId='F002';route.params.id='voucher-b';api.byBusiness.mockResolvedValue(null);await flushPromises();
    pending.resolve(original);await flushPromises();expect(wrapper.text()).not.toContain('原凭证 V-original');
    expect(api.get).not.toHaveBeenCalledWith('F002','red');wrapper.unmount();
  });

  it('keeps the revision visible when related lookup fails and allows explicit retry', async () => {
    disposalFixtures();api.byBusiness.mockRejectedValueOnce(new Error('关联读取失败'));
    const wrapper=render();await flushPromises();expect(wrapper.text()).toContain('关联读取失败');expect(wrapper.text()).toContain('V-voucher-a');
    await wrapper.findAll('button').find(b=>b.text()==='重新读取关联凭证')!.trigger('click');await flushPromises();
    expect(wrapper.text()).toContain('原凭证 V-original');expect(wrapper.text()).not.toContain('关联读取失败');wrapper.unmount();
  });

  it('does not load a return order id as a voucher after leaving the voucher route', async () => {
    const wrapper = render();
    await flushPromises();
    route.name = 'FinanceReturnRefundDetail';
    route.params.id = 'return-a';
    await flushPromises();

    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.get).not.toHaveBeenCalledWith('F001', 'return-a');
    expect(wrapper.text()).not.toContain('V-return-a');
    wrapper.unmount();
  });

  it('ignores a pending voucher response after leaving for a sibling route with the same id', async () => {
    const pending = deferred<ReturnType<typeof voucher>>();
    api.get.mockReturnValueOnce(pending.promise);
    const wrapper = render();
    await flushPromises();
    route.name = 'FinanceReturnRefundDetail';
    await flushPromises();
    pending.resolve(voucher('voucher-a'));
    await flushPromises();

    expect(wrapper.text()).not.toContain('V-voucher-a');
    wrapper.unmount();
  });

  it('keeps a cached voucher inactive and reloads only when its task becomes active again', async () => {
    const visible = ref(true);
    const wrapper = mount({
      setup: () => () => h(KeepAlive, null, { default: () => visible.value ? h(VoucherDetail) : null }),
    }, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    visible.value = false;
    await flushPromises();
    api.get.mockClear();
    route.name = 'FinanceReturnRefundDetail';
    route.params.id = 'return-a';
    await flushPromises();
    route.name = 'FinanceVoucherDetail';
    route.params.id = 'voucher-a';
    await flushPromises();
    expect(api.get).not.toHaveBeenCalled();

    visible.value = true;
    await flushPromises();
    expect(api.get).toHaveBeenCalledTimes(1);
    expect(api.get).toHaveBeenCalledWith('F001', 'voucher-a');
    expect(wrapper.text()).toContain('V-voucher-a');
    wrapper.unmount();
  });

  it('renders refund source in Chinese and preserves a real zero amount', async () => {
    const wrapper = render();
    await flushPromises();

    expect(wrapper.text()).toContain('客户退款');
    expect(wrapper.text()).toContain('¥0.00');
    expect(api.get).toHaveBeenCalledWith('F001', 'voucher-a');
    wrapper.unmount();
  });

  it('looks up the exact refund request before navigating to its ledger', async () => {
    api.get.mockResolvedValueOnce(voucher('voucher-a', 'RETURN_REFUND', 'RF-1'));
    const wrapper = render();
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text().includes('查看退款与资金流水'))!.trigger('click');
    await flushPromises();

    expect(api.refundByRequest).toHaveBeenCalledWith('F001', 'RF-1');
    expect(api.push).toHaveBeenCalledWith({
      path: '/finance/return-refunds/return-a',
      query: { refundRequestId: 'RF-1', ledgerId: 'LEDGER-1' },
    });
    wrapper.unmount();
  });

  it('does not navigate when the exact refund request has no ledger evidence', async () => {
    api.refundByRequest.mockResolvedValueOnce({
      returnOrderId: 'return-a', request: { id: 'RF-1', ledger: null },
    });
    const wrapper = render();
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text().includes('查看退款与资金流水'))!.trigger('click');
    await flushPromises();

    expect(api.push).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('退款申请缺少匹配的退款流水');
    wrapper.unmount();
  });

  it('hides generic void for refund vouchers and keeps reversed vouchers non-actionable', async () => {
    const wrapper = render();
    await flushPromises();
    expect(wrapper.findAll('button').some(button => button.text() === '作废')).toBe(false);
    wrapper.unmount();

    api.get.mockResolvedValueOnce({ ...voucher('voucher-a', 'RETURN_ORDER'), status: 'REVERSED' });
    const reversed = render();
    await flushPromises();
    expect(reversed.text()).toContain('已冲销');
    expect(reversed.text()).toContain('不可再次过账或作废');
    const reversedVoid = reversed.findAll('button').find(button => button.text() === '作废');
    expect(reversedVoid).toBeTruthy();
    expect(reversedVoid!.attributes('disabled')).toBeDefined();
    reversed.unmount();
  });

  it('keeps the original and VOUCHER_REVERSAL vouchers bidirectionally linked', async () => {
    api.get.mockResolvedValueOnce({ ...voucher('voucher-a'), status: 'REVERSED', reversalVoucherId: 'voucher-reversal' });
    const original = render();
    await flushPromises();
    await original.findAll('button').find(button => button.text() === '查看红冲凭证')!.trigger('click');
    expect(api.push).toHaveBeenCalledWith('/finance/voucher/voucher-reversal');
    original.unmount();

    api.get.mockResolvedValueOnce(voucher('voucher-reversal', 'VOUCHER_REVERSAL', 'voucher-a'));
    const reversal = render();
    await flushPromises();
    expect(reversal.text()).toContain('红字冲销凭证');
    expect(reversal.text()).not.toContain('客户退款凭证需由退货退款流程核对');
    await reversal.findAll('button').find(button => button.text() === '查看原始凭证')!.trigger('click');
    expect(api.push).toHaveBeenCalledWith('/finance/voucher/voucher-a');
    reversal.unmount();
  });

  it('does not post after the confirmation context switches to another voucher', async () => {
    const wrapper = render();
    await flushPromises();
    const confirmation = deferred<boolean>();
    api.confirm.mockReturnValueOnce(confirmation.promise);

    const vm = wrapper.vm as unknown as { onPost: () => Promise<void> };
    const pendingPost = vm.onPost();
    await flushPromises();
    route.params.id = 'voucher-b';
    await flushPromises();
    confirmation.resolve(true);
    await pendingPost;

    expect(api.post).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('V-voucher-b');
    wrapper.unmount();
  });

  it('does not post after the confirmation context switches factory or actor', async () => {
    const wrapper = render();
    await flushPromises();
    const confirmation = deferred<boolean>();
    api.confirm.mockReturnValueOnce(confirmation.promise);

    const vm = wrapper.vm as unknown as { onPost: () => Promise<void> };
    const pendingPost = vm.onPost();
    await flushPromises();
    identity.factoryId = 'F002';
    identity.user.id = 8;
    await flushPromises();
    confirmation.resolve(true);
    await pendingPost;

    expect(api.post).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('clears stale voucher and shows the backend error when posting fails', async () => {
    const wrapper = render();
    await flushPromises();
    api.post.mockRejectedValueOnce(new Error('借贷不平衡，不能过账'));

    const vm = wrapper.vm as unknown as { onPost: () => Promise<void> };
    await vm.onPost();
    await flushPromises();

    expect(wrapper.text()).toContain('借贷不平衡，不能过账');
    expect(wrapper.text()).not.toContain('V-voucher-a');
    wrapper.unmount();
  });
});
