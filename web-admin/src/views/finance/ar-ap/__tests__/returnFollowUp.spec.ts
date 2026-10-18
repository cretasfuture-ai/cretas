import { mount, flushPromises } from '@vue/test-utils';
import { reactive } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), push: vi.fn(), replace: vi.fn() }));
const route = reactive({ query: {} as Record<string, unknown> });
const identity = reactive({ factoryId: 'A', user: { id: 1 } });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: api.push, replace: api.replace }) }));
vi.mock('@/api/request', () => ({ get: api.get, post: api.post }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => identity }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: true, canWrite: () => true }) }));
import Ledger from '../index.vue';
import Adjustments from '../../adjustments/list.vue';

function financial(id = 'return-a', state = 'APPROVED') {
  return { success: true, data: { returnOrderId: id, returnNumber: id === 'return-a' ? 'RT-A' : 'RT-B',
    status: state, canResubmit: false, voucher: null, adjustments: [
      { id: 'old-'+id, factoryId: 'A', transactionNumber: 'REJECTED-'+id, transactionType: 'AR_ADJUSTMENT',
        counterpartyType: 'CUSTOMER', counterpartyId: 'customer-a', counterpartyName: '测试客户',
        amount: -150, balanceAfter: 690, approvalStatus: 'REJECTED', operatedBy: 99,
        sourceType: 'RETURN_ORDER', sourceId: id, createdAt: '2026-10-16T10:00:00' },
      { id: 'new-'+id, factoryId: 'A', transactionNumber: 'LATEST-'+id, transactionType: 'AR_ADJUSTMENT',
        counterpartyType: 'CUSTOMER', counterpartyId: 'customer-a', counterpartyName: '测试客户',
        amount: -150, balanceAfter: state === 'PENDING' ? 690 : 540, approvalStatus: state, operatedBy: 99,
        sourceType: 'RETURN_ORDER', sourceId: id, createdAt: '2026-10-16T11:00:00' },
    ] } };
}
const render = (component: typeof Ledger | typeof Adjustments) => mount(component, { global: { plugins: [ElementPlus] } });

beforeEach(() => {
  vi.clearAllMocks(); route.query = { returnOrderId: 'return-a' }; identity.factoryId = 'A'; identity.user.id = 1;
  api.get.mockImplementation(async (url: string) => {
    if (url.endsWith('/return-a/financial-follow-up')) return financial();
    if (url.endsWith('/return-b/financial-follow-up')) return financial('return-b');
    throw new Error('Unexpected unscoped read: '+url);
  });
});

describe('退货来源应收读取与导航', () => {
  it('opens the actual receivable tab and reads complete source history without the general ledger page', async () => {
    const wrapper = render(Ledger); await flushPromises();
    expect(wrapper.text()).toContain('仅显示退货单 RT-A');
    expect(wrapper.text()).toContain('LATEST-return-a');
    expect(wrapper.text()).toContain('REJECTED-return-a');
    expect(wrapper.text()).toContain('540.00');
    expect(api.get.mock.calls.every(([url]) => String(url).endsWith('/return-a/financial-follow-up'))).toBe(true);
    await wrapper.findAll('button').find(button => button.text() === '返回原退货单')!.trigger('click');
    expect(api.push).toHaveBeenCalledWith('/finance/return-refunds/return-a');
    expect(api.post).not.toHaveBeenCalled(); wrapper.unmount();
  });

  it('invalid source context never falls back to the full ledger', async () => {
    route.query = { returnOrderId: ['return-a', 'return-b'] };
    const wrapper = render(Ledger); await flushPromises();
    expect(wrapper.text()).toContain('退货单链接无效');
    expect(api.get).not.toHaveBeenCalled(); expect(api.post).not.toHaveBeenCalled(); wrapper.unmount();
  });

  it('an unavailable financial source is an explicit error and does not reveal unrelated rows', async () => {
    api.get.mockRejectedValue(new Error('本单读取失败'));
    const wrapper = render(Ledger); await flushPromises();
    expect(wrapper.text()).toContain('本单读取失败'); expect(wrapper.text()).not.toContain('LATEST-return-a');
    expect(api.get.mock.calls.every(([url]) => String(url).includes('financial-follow-up'))).toBe(true); wrapper.unmount();
  });

  it('a late old-return response cannot replace the currently selected source', async () => {
    let finish!: (value: ReturnType<typeof financial>) => void;
    api.get.mockImplementation((url: string) => url.includes('/return-a/')
      ? new Promise(resolve => { finish = resolve; }) : Promise.resolve(financial('return-b')));
    const wrapper = render(Ledger); await flushPromises();
    route.query = { returnOrderId: 'return-b' }; await flushPromises();
    finish(financial('return-a')); await flushPromises();
    expect(wrapper.text()).toContain('仅显示退货单 RT-B'); expect(wrapper.text()).toContain('LATEST-return-b');
    expect(wrapper.text()).not.toContain('LATEST-return-a'); wrapper.unmount();
  });

  it('pending approvals are filtered from the complete source result and preserve a link to full history', async () => {
    api.get.mockResolvedValue(financial('return-a', 'PENDING'));
    const wrapper = render(Adjustments); await flushPromises();
    const state = wrapper.vm as unknown as { tableData: Array<{ id: string }> };
    expect(wrapper.text()).toContain('仅显示退货单 RT-A');
    expect(state.tableData.map(row => row.id)).toEqual(['new-return-a']);
    expect(state.tableData.every(row => row.id !== 'old-return-a')).toBe(true);
    expect(wrapper.text()).toContain('待审批: 1 条');
    await wrapper.findAll('button').find(button => button.text() === '查看本单全部调整记录')!.trigger('click');
    expect(api.push).toHaveBeenCalledWith({ path: '/finance/ar-ap', query: { returnOrderId: 'return-a' } });
    expect(api.post).not.toHaveBeenCalled(); wrapper.unmount();
  });

  it('adjustment source links use the finance return-refund alias', async () => {
    api.get.mockResolvedValue(financial('return-a', 'PENDING'));
    const wrapper = render(Adjustments); await flushPromises();
    const sourceButton = wrapper.findAll('button').find(button => button.text() === '查看原退货单');
    expect(sourceButton).toBeTruthy();
    await sourceButton!.trigger('click');
    expect(api.push).toHaveBeenCalledWith('/finance/return-refunds/return-a');
    expect(api.post).not.toHaveBeenCalled(); wrapper.unmount();
  });

  it('foreign transaction provenance is rejected even when the parent response id matches', async () => {
    const response = financial(); response.data.adjustments[0]!.factoryId = 'B'; api.get.mockResolvedValue(response);
    const wrapper = render(Ledger); await flushPromises();
    expect(wrapper.text()).toContain('应收流水来源与当前退货单不一致'); expect(wrapper.text()).not.toContain('LATEST-return-a');
    wrapper.unmount();
  });
});
