/**
 * 第7批a (财务·调整审批) 迁移后的真实挂载断言:
 *  - 类型显示中文 (AR调整 / AP调整 / 退款撤销调整), 不外露英文原码;
 *  - 无查看价格权限: 整列去掉 金额 / 提交时余额, DOM 里没有 0;
 *  - 审批走确认弹窗 (Nuxt UI), 确认后才发 approve 请求, 取消零请求。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { adjustmentStubs, confirmButton, closeConfirmModal } from '../adjustments/__tests__/adjustmentStubs';

const mocks = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(),
  auth: { factoryId: 'F006', user: { id: 1 } },
  perm: { canWrite: (_m?: string) => true, canViewPrice: true },
}));
vi.mock('@/api/request', () => ({ get: mocks.get, post: mocks.post }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => mocks.auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => mocks.perm }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock('@/api/inventory', () => ({ getReturnRefundByRequest: vi.fn() }));
vi.mock('@/api/returnFinancialFollowUp', () => ({ getReturnFinancialFollowUp: vi.fn(), validReturnOrderId: () => null }));

import AdjustmentsPage from '../adjustments/list.vue';

const base = {
  factoryId: 'F006', counterpartyType: 'CUSTOMER', counterpartyId: 'C-1', counterpartyName: '六膳门客户',
  amount: 50, balanceAfter: 120, approvalStatus: 'PENDING', operatedBy: 9, createdAt: '2026-11-16T00:00:00Z',
};
const rows = [
  { ...base, id: 'ADJ-AR-0001', transactionType: 'AR_ADJUSTMENT' },
  { ...base, id: 'ADJ-AP-0002', transactionType: 'AP_ADJUSTMENT', counterpartyType: 'SUPPLIER', counterpartyName: '供应商甲' },
];

async function render() {

  const w = mount(AdjustmentsPage, { attachTo: document.body, global: { stubs: adjustmentStubs } });
  await flushPromises();
  return w;
}

beforeEach(() => {
  closeConfirmModal();
  mocks.get.mockReset(); mocks.post.mockReset();
  mocks.auth = reactive({ factoryId: 'F006', user: { id: 1 } });
  mocks.perm = reactive({ canWrite: () => true, canViewPrice: true });
  mocks.get.mockResolvedValue({ success: true, data: { content: rows.map(r => ({ ...r })), totalElements: 2 } });
});

describe('调整审批 (Nuxt UI 迁移)', () => {
  it('类型显示中文, 不外露英文原码', async () => {
    const w = await render();
    const text = w.text();
    expect(text).toContain('AR调整');
    expect(text).toContain('AP调整');
    expect(text).not.toMatch(/AR_ADJUSTMENT|AP_ADJUSTMENT|PENDING/);
    w.unmount();
  });

  it('有价格权限: 金额 / 提交时余额两列在', async () => {
    const w = await render();
    const heads = w.findAll('thead th').map(n => n.text());
    expect(heads).toContain('金额');
    expect(heads.some(h => h.includes('提交时余额'))).toBe(true);
    expect(w.text()).toContain('应收 +50.00 元');
    w.unmount();
  });

  it('无价格权限: 整列去掉, 页面里没有金额也没有 0.00', async () => {
    mocks.perm.canViewPrice = false;
    const w = await render();
    const heads = w.findAll('thead th').map(n => n.text());
    expect(heads).not.toContain('金额');
    expect(heads.some(h => h.includes('余额'))).toBe(false);
    expect(w.text()).toContain('六膳门客户');
    expect(w.text()).not.toMatch(/50\.00|120\.00|0\.00/);
    w.unmount();
  });

  it('审批走确认弹窗: 取消零请求, 确认后才发 approve', async () => {
    mocks.post.mockResolvedValue({ success: true, data: { ...rows[0], approvalStatus: 'APPROVED', balanceAfter: 170 } });
    const w = await render();
    await w.findAll('button').find(b => b.text() === '审批')!.trigger('click');
    await flushPromises();
    expect(document.body.querySelector('[data-testid="sales-confirm-message"]')?.textContent).toContain('确认审批通过该调整');
    confirmButton('取消')!.click();
    await flushPromises();
    expect(mocks.post).not.toHaveBeenCalled();

    await w.findAll('button').find(b => b.text() === '审批')!.trigger('click');
    await flushPromises();
    confirmButton('确认审批')!.click();
    await flushPromises();
    expect(mocks.post).toHaveBeenCalledTimes(1);
    expect(mocks.post).toHaveBeenCalledWith('/F006/finance/adjustment/ADJ-AR-0001/approve');
    w.unmount();
  });
});
