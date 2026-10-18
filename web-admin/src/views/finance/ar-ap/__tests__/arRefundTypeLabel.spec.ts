import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';
import Ledger from '../index.vue';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), push: vi.fn(), refundByRequest: vi.fn() }));
const route = reactive({ query: {} as Record<string, unknown> });
const identity = reactive({ factoryId: 'F001', user: { id: 1 } });

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(resolver => { resolve = resolver; });
  return { promise, resolve };
}

vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: api.push, replace: vi.fn() }) }));
vi.mock('@/api/request', () => ({ get: api.get, post: api.post }));
vi.mock('@/api/inventory', () => ({ getReturnRefundByRequest: api.refundByRequest }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => identity }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: true, canWrite: () => true }) }));

beforeEach(() => {
  vi.clearAllMocks();
  route.query = {};
  identity.factoryId = 'F001';
  identity.user.id = 1;
  api.get.mockImplementation((url: string) => {
    if (url.endsWith('/finance/overview')) return Promise.resolve({ success: true, data: {} });
    if (url.endsWith('/finance/transactions')) return Promise.resolve({ success: true, data: {
      content: [{ id: 'AR-REFUND-1', transactionNumber: 'AR-REFUND-1', transactionType: 'AR_REFUND',
        counterpartyName: '测试客户', amount: 12, balanceAfter: 88, paymentMethod: 'CASH', transactionDate: '2026-10-16',
        sourceType: 'RETURN_REFUND', sourceId: 'RF-1' }],
      totalElements: 1,
    } });
    return Promise.resolve({ success: true, data: [] });
  });
  api.refundByRequest.mockResolvedValue({
    returnOrderId: 'return-a', request: { id: 'RF-1', arapTransactionId: 'AR-REFUND-1', ledger: { id: 'AR-REFUND-1' } },
  });
});

describe('应收流水客户退款类型中文映射', () => {
  it('refreshes the overview when returning from receivable transactions', async () => {
    const wrapper = mount(Ledger, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.get('#tab-receivable').trigger('click');
    await flushPromises();
    api.get.mockClear();
    api.get.mockResolvedValueOnce({ success: true, data: { totalReceivable: 525 } });

    await wrapper.get('#tab-overview').trigger('click');
    await flushPromises();

    expect(api.get).toHaveBeenCalledWith('/F001/finance/overview');
    expect(wrapper.text()).toContain('525.00');
    wrapper.unmount();
  });

  it('refreshes overview for a new factory and ignores the old response', async () => {
    const oldOverview = deferred<unknown>();
    const newOverview = deferred<unknown>();
    let overviewCalls = 0;
    api.get.mockImplementation((url: string) => {
      if (url.endsWith('/finance/overview')) return overviewCalls++ === 0 ? oldOverview.promise : newOverview.promise;
      return Promise.resolve({ success: true, data: { content: [], totalElements: 0 } });
    });
    const wrapper = mount(Ledger, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    identity.factoryId = 'F002';
    await flushPromises();

    expect(api.get).toHaveBeenCalledWith('/F002/finance/overview');
    oldOverview.resolve({ success: true, data: { totalReceivable: 111 } });
    await flushPromises();
    expect(wrapper.text()).not.toContain('111.00');
    newOverview.resolve({ success: true, data: { totalReceivable: 222 } });
    await flushPromises();
    expect(wrapper.text()).toContain('222.00');
    wrapper.unmount();
  });

  it('invalidates overview when the actor changes in the same factory', async () => {
    const oldOverview = deferred<unknown>();
    const newOverview = deferred<unknown>();
    let overviewCalls = 0;
    api.get.mockImplementation((url: string) => {
      if (url.endsWith('/finance/overview')) return overviewCalls++ === 0 ? oldOverview.promise : newOverview.promise;
      return Promise.resolve({ success: true, data: { content: [], totalElements: 0 } });
    });
    const wrapper = mount(Ledger, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    identity.user.id = 2;
    await flushPromises();

    expect(api.get).toHaveBeenCalledWith('/F001/finance/overview');
    oldOverview.resolve({ success: true, data: { totalReceivable: 111 } });
    await flushPromises();
    expect(wrapper.text()).not.toContain('111.00');
    newOverview.resolve({ success: true, data: { totalReceivable: 333 } });
    await flushPromises();
    expect(wrapper.text()).toContain('333.00');
    wrapper.unmount();
  });

  it('clears cached overview and shows an explicit error after refresh failure', async () => {
    api.get.mockImplementation((url: string) => {
      if (url.endsWith('/finance/overview')) return Promise.resolve({ success: true, data: { totalReceivable: 100 } });
      return Promise.resolve({ success: true, data: { content: [], totalElements: 0 } });
    });
    const wrapper = mount(Ledger, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(wrapper.text()).toContain('100.00');
    api.get.mockImplementation((url: string) => {
      if (url.endsWith('/finance/overview')) return Promise.reject(new Error('概览读取失败'));
      return Promise.resolve({ success: true, data: { content: [], totalElements: 0 } });
    });
    await wrapper.findAll('button').find(button => button.text() === '刷新')!.trigger('click');
    await flushPromises();

    expect(wrapper.text()).not.toContain('100.00');
    expect(wrapper.text()).toContain('概览读取失败');
    wrapper.unmount();
  });

  it('refreshes aging for a new factory and ignores the old response', async () => {
    const oldAging = deferred<unknown>();
    const newAging = deferred<unknown>();
    let agingCalls = 0;
    api.get.mockImplementation((url: string) => {
      if (url.endsWith('/finance/overview')) return Promise.resolve({ success: true, data: {} });
      if (url.endsWith('/finance/aging')) return agingCalls++ === 0 ? oldAging.promise : newAging.promise;
      return Promise.resolve({ success: true, data: { content: [], totalElements: 0 } });
    });
    const wrapper = mount(Ledger, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.get('#tab-aging').trigger('click');
    await flushPromises();
    identity.factoryId = 'F002';
    await flushPromises();

    expect(api.get).toHaveBeenCalledWith('/F002/finance/aging', { params: { counterpartyType: 'CUSTOMER' } });
    oldAging.resolve({ success: true, data: [{ counterpartyName: '旧工厂客户' }] });
    await flushPromises();
    expect(wrapper.text()).not.toContain('旧工厂客户');
    newAging.resolve({ success: true, data: [{ counterpartyName: '新工厂客户' }] });
    await flushPromises();
    expect(wrapper.text()).toContain('新工厂客户');
    wrapper.unmount();
  });

  it('refreshes aging for a new actor and ignores the prior actor response', async () => {
    const oldAging = deferred<unknown>();
    const newAging = deferred<unknown>();
    let agingCalls = 0;
    api.get.mockImplementation((url: string) => {
      if (url.endsWith('/finance/overview')) return Promise.resolve({ success: true, data: {} });
      if (url.endsWith('/finance/aging')) return agingCalls++ === 0 ? oldAging.promise : newAging.promise;
      return Promise.resolve({ success: true, data: { content: [], totalElements: 0 } });
    });
    const wrapper = mount(Ledger, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.get('#tab-aging').trigger('click');
    await flushPromises();
    identity.user.id = 2;
    await flushPromises();

    expect(api.get).toHaveBeenCalledWith('/F001/finance/aging', { params: { counterpartyType: 'CUSTOMER' } });
    oldAging.resolve({ success: true, data: [{ counterpartyName: '旧财务客户' }] });
    await flushPromises();
    expect(wrapper.text()).not.toContain('旧财务客户');
    newAging.resolve({ success: true, data: [{ counterpartyName: '新财务客户' }] });
    await flushPromises();
    expect(wrapper.text()).toContain('新财务客户');
    wrapper.unmount();
  });

  it('clears cached aging and shows an explicit error after refresh failure', async () => {
    let agingCalls = 0;
    api.get.mockImplementation((url: string) => {
      if (url.endsWith('/finance/overview')) return Promise.resolve({ success: true, data: {} });
      if (url.endsWith('/finance/aging')) {
        agingCalls += 1;
        return agingCalls === 1
          ? Promise.resolve({ success: true, data: [{ counterpartyName: '已缓存客户' }] })
          : Promise.reject(new Error('账龄读取失败'));
      }
      return Promise.resolve({ success: true, data: { content: [], totalElements: 0 } });
    });
    const wrapper = mount(Ledger, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.get('#tab-aging').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('已缓存客户');

    const vm = wrapper.vm as unknown as { agingType: string; handleAgingTypeChange: () => void };
    vm.agingType = 'SUPPLIER';
    vm.handleAgingTypeChange();
    await flushPromises();
    expect(wrapper.text()).not.toContain('已缓存客户');
    expect(wrapper.text()).toContain('账龄读取失败');
    wrapper.unmount();
  });

  it('renders the actual aging API amounts and six date buckets without invented ranges', async () => {
    api.get.mockImplementation((url: string) => Promise.resolve({ success: true, data: url.endsWith('/finance/aging')
      ? [{ counterpartyName: '真实接口口径客户', total: 321, current: 101, days1_30: 102, days31_60: 103,
        days61_90: 104, days91_120: 105, days120plus: 106 }] : {} }));
    const wrapper = mount(Ledger, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.get('#tab-aging').trigger('click');
    await flushPromises();
    for (const amount of ['321.00','101.00','102.00','103.00','104.00','105.00','106.00']) {
      expect(wrapper.text()).toContain(amount);
    }
    expect(wrapper.text()).toContain('91-120天');
    expect(wrapper.text()).toContain('120天以上');
    expect(wrapper.text()).not.toContain('91-180天');
    wrapper.unmount();
  });

  it('uses both receivable and payable overdue fields and explains snapshot aging labels', async () => {
    api.get.mockImplementation((url: string) => {
      if (url.endsWith('/finance/overview')) return Promise.resolve({ success: true, data: {
        overdueReceivable: 12, overduePayable: 3, overdueARCount: 2, overdueAPCount: 1,
      } });
      if (url.endsWith('/finance/transactions')) return Promise.resolve({ success: true, data: {
        content: [{ id: 'AR-1', transactionNumber: 'AR-1', transactionType: 'AR_INVOICE', amount: 12, balanceAfter: 8,
          counterpartyName: '测试客户', transactionDate: '2026-10-16' }], totalElements: 1,
      } });
      return Promise.resolve({ success: true, data: [] });
    });
    const wrapper = mount(Ledger, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(wrapper.text()).toContain('15.00');
    expect(wrapper.text()).toContain('3 笔');
    await wrapper.get('#tab-receivable').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('交易后余额（历史快照）');
    await wrapper.get('#tab-aging').trigger('click');
    expect(wrapper.text()).toContain('账龄按发票剩余未清金额统计；与含退款及撤销的净应收不同');
    wrapper.unmount();
  });

  it('renders AR_REFUND as 客户退款结清 in the mounted transaction table', async () => {
    const wrapper = mount(Ledger, { global: { plugins: [ElementPlus] } });
    const vm = wrapper.vm as unknown as { activeTab: string };
    vm.activeTab = 'receivable';
    await flushPromises();

    expect(wrapper.text()).toContain('客户退款结清');
    expect(wrapper.text()).not.toContain('AR_REFUND');
    await wrapper.findAll('button').find(button => button.text().includes('查看退款与流水'))!.trigger('click');
    await flushPromises();
    expect(api.refundByRequest).toHaveBeenCalledWith('F001', 'RF-1');
    expect(api.push).toHaveBeenCalledWith({
      path: '/finance/return-refunds/return-a',
      query: { refundRequestId: 'RF-1', ledgerId: 'AR-REFUND-1' },
    });
    wrapper.unmount();
  });

  it('does not navigate when refund evidence does not match the clicked AR row', async () => {
    api.refundByRequest.mockResolvedValueOnce({
      returnOrderId: 'return-a',
      request: { id: 'RF-1', arapTransactionId: 'OTHER-AR', ledger: { id: 'OTHER-LEDGER' } },
    });
    const wrapper = mount(Ledger, { global: { plugins: [ElementPlus] } });
    const vm = wrapper.vm as unknown as { activeTab: string };
    vm.activeTab = 'receivable';
    await flushPromises();

    await wrapper.findAll('button').find(button => button.text().includes('查看退款与流水'))!.trigger('click');
    await flushPromises();

    expect(api.refundByRequest).toHaveBeenCalledWith('F001', 'RF-1');
    expect(api.push).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('navigates a RETURN_REFUND_REVERSAL row by its exact reversal ledger id', async () => {
    api.get.mockImplementation((url: string) => {
      if (url.endsWith('/finance/overview')) return Promise.resolve({ success: true, data: {} });
      if (url.endsWith('/finance/transactions')) return Promise.resolve({ success: true, data: {
        content: [{ id: 'REV-LEDGER-1', transactionNumber: 'AR-REVERSAL-1', transactionType: 'AR_ADJUSTMENT',
          counterpartyName: '测试客户', amount: -12, balanceAfter: 100, paymentMethod: 'CASH', transactionDate: '2026-10-16',
          approvalStatus: 'PENDING', sourceType: 'RETURN_REFUND_REVERSAL', sourceId: 'RF-1' }],
        totalElements: 1,
      } });
      return Promise.resolve({ success: true, data: [] });
    });
    api.refundByRequest.mockResolvedValueOnce({
      returnOrderId: 'return-a', request: {
        id: 'RF-1', arapTransactionId: 'AR-REFUND-1',
        ledger: { id: 'AR-REFUND-1' },
        reversal: { status: 'PENDING', ledger: { id: 'REV-LEDGER-1' } },
      },
    });
    const wrapper = mount(Ledger, { global: { plugins: [ElementPlus] } });
    const vm = wrapper.vm as unknown as { activeTab: string };
    vm.activeTab = 'receivable';
    await flushPromises();

    expect(wrapper.text()).toContain('退款撤销调整');
    expect(wrapper.text()).toContain('请从退款详情审核');
    expect(wrapper.text()).not.toContain('付款并核销');
    await wrapper.findAll('button').find(button => button.text().includes('查看撤销退款'))!.trigger('click');
    await flushPromises();
    expect(api.push).toHaveBeenCalledWith({
      path: '/finance/return-refunds/return-a',
      query: { refundRequestId: 'RF-1', ledgerId: 'REV-LEDGER-1' },
    });
    wrapper.unmount();
  });
});
