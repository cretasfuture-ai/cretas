import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus, { ElMessage } from 'element-plus';
import ReturnList from './list.vue';
import { listReturnOrders, completeReturnOrder } from '@/api/returnOrder';

const access = vi.hoisted(() => ({ finance: true }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F-RETURN' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({
  canViewPrice: true, canWrite: (module: string) => module !== 'finance' || access.finance,
}) }));
vi.mock('element-plus', async importOriginal => {
  const actual = await importOriginal<typeof import('element-plus')>();
  return { ...actual, ElMessage: vi.fn(), ElMessageBox: { ...actual.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') } };
});
vi.mock('@/api/returnOrder', () => ({
  listReturnOrders: vi.fn(), submitReturnOrder: vi.fn(), approveReturnOrder: vi.fn(),
  financeApproveReturnOrder: vi.fn(), financeRejectReturnOrder: vi.fn(), rejectReturnOrder: vi.fn(),
  completeReturnOrder: vi.fn(),
}));

const row = {
  id: 'R1', factoryId: 'F-RETURN', returnNumber: 'RT-PUR-TEST', returnType: 'PURCHASE_RETURN' as const,
  status: 'FINANCE_APPROVED' as const, counterpartyId: 'S1', returnDate: '2026-10-16',
  totalAmount: 100, withGoods: true, createdBy: 1,
};

beforeEach(() => {
  vi.clearAllMocks(); access.finance = true;
  vi.mocked(listReturnOrders).mockResolvedValue({ content: [row], totalElements: 1, totalPages: 1 });
});

describe('purchase return completion notice', () => {
  it('keeps the pending payable adjustment notice visible after actual completion action', async () => {
    const hint = '退货已完成。100元余额冲减待财务审批，供应商应付余额尚未变动。';
    vi.mocked(completeReturnOrder).mockResolvedValue({ ...row, status: 'COMPLETED', completionHint: hint });
    const wrapper = mount(ReturnList, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    const button = wrapper.findAll('button').find(button => button.text() === '完成出货');
    expect(button).toBeDefined(); await button!.trigger('click'); await flushPromises();
    expect(completeReturnOrder).toHaveBeenCalledWith('F-RETURN', 'R1');
    expect(ElMessage).toHaveBeenLastCalledWith(expect.objectContaining({ message: hint, duration: 0, showClose: true }));
    wrapper.unmount();
  });

  it('retains ordinary success feedback when the response has no completion hint', async () => {
    vi.mocked(completeReturnOrder).mockResolvedValue({ ...row, status: 'COMPLETED' });
    const wrapper = mount(ReturnList, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text() === '完成出货')!.trigger('click');
    await flushPromises();
    expect(ElMessage).toHaveBeenLastCalledWith(expect.objectContaining({ message: '完成成功', type: 'success', duration: 3000 }));
    wrapper.unmount();
  });

  it('keeps completion unavailable before finance approval', async () => {
    access.finance = false;
    vi.mocked(listReturnOrders).mockResolvedValue({ content: [{ ...row, status: 'APPROVED' }], totalElements: 1, totalPages: 1 });
    const wrapper = mount(ReturnList, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(wrapper.text()).toContain('待财务审批');
    expect(wrapper.findAll('button').some(button => button.text() === '完成出货')).toBe(false);
    expect(completeReturnOrder).not.toHaveBeenCalled();
    wrapper.unmount();
  });
});
