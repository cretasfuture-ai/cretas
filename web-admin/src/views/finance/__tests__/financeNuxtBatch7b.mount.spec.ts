/**
 * 第7批 b (财务: 应收应付 / 期间结账 / 凭证模板) 迁移后的真实挂载断言:
 *  - 类型 / 状态 / 支付方式 / 对账结论显示中文, 不外露英文原码;
 *  - 无查看价格权限: 应收应付整页不渲染任何金额 (不显示 0);
 *  - 确认路径走 SalesConfirmModal: 点确认才发请求, 点取消不发请求。
 * vitest 里没有 Nuxt UI 插件, 用共享最小桩。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';

const mocks = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(),
  auth: { factoryId: 'F006', user: { id: 1 } },
  perm: { canWrite: (_m?: string) => true, canViewPrice: true },
  list: vi.fn(), del: vi.fn(), setDefault: vi.fn(),
}));
vi.mock('@/api/request', () => ({ get: mocks.get, post: mocks.post, default: { get: vi.fn() } }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => mocks.auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => mocks.perm }));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useRoute: () => ({ query: {}, fullPath: '/x' }),
}));
vi.mock('@/api/inventory', () => ({ getReturnRefundByRequest: vi.fn() }));
vi.mock('@/api/voucher', async (orig) => ({
  ...(await orig<typeof import('@/api/voucher')>()),
  listVoucherTemplates: mocks.list,
  deleteVoucherTemplate: mocks.del,
  setVoucherTemplateDefault: mocks.setDefault,
}));

import ArApPage from '../ar-ap/index.vue';
import PeriodPage from '../accounting-period/index.vue';
import TemplatePage from '../voucher-template/index.vue';

const stubs = { ...nuxtUiStubs, UIcon: { template: '<i />' }, USwitch: { template: '<span />' } };
const btn = (w: ReturnType<typeof mount>, label: string) => w.findAll('button').find(b => b.text() === label)!;
const confirmOk = (w: ReturnType<typeof mount>) => w.get('[data-testid="sales-confirm-ok"]');
const confirmCancel = (w: ReturnType<typeof mount>) => w.get('[data-testid="sales-confirm-cancel"]');

beforeEach(() => {
  for (const m of [mocks.get, mocks.post, mocks.list, mocks.del, mocks.setDefault]) m.mockReset();
  mocks.auth = reactive({ factoryId: 'F006', user: { id: 1 } });
  mocks.perm = reactive({ canWrite: () => true, canViewPrice: true });
});

describe('ar-ap 应收应付', () => {
  const apRow = {
    id: 'ap1', transactionNumber: 'AP-1', transactionType: 'AP_INVOICE', counterpartyId: 's1', counterpartyName: '供应商乙',
    amount: 800, balanceAfter: 800, outstandingAmount: 800, paymentMethod: 'BANK_TRANSFER', transactionDate: '2026-11-01',
  };
  const setup = () => mocks.get.mockImplementation(async (url: string) => {
    if (url.endsWith('/finance/overview')) return { success: true, data: { totalReceivable: 1500, totalPayable: 800 } };
    if (url.endsWith('/finance/transactions')) return { success: true, data: { content: [apRow], totalElements: 1 } };
    return { success: true, data: [] };
  });

  it('shows Chinese type and payment method, never the English codes', async () => {
    setup();
    const w = mount(ArApPage, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('1,500.00');
    await btn(w, '应付账款').trigger('click');
    await flushPromises();
    expect(w.text()).toContain('应付挂账');
    expect(w.text()).toContain('银行转账');
    expect(w.text()).not.toMatch(/AP_INVOICE|BANK_TRANSFER/);
    expect(w.text()).toContain('¥800.00');
  });

  it('price-masked: no tabs, no money anywhere, not even a 0', async () => {
    mocks.perm.canViewPrice = false;
    setup();
    const w = mount(ArApPage, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('您没有查看价格/财务数据的权限');
    expect(w.text()).not.toMatch(/1,500|¥0|0\.00/);
  });

  it('settle path: confirm sends the settlement, cancel sends nothing', async () => {
    setup();
    mocks.post.mockResolvedValue({ success: true, data: {}, message: '付款核销成功' });
    const w = mount(ArApPage, { global: { stubs } });
    await flushPromises();
    await btn(w, '应付账款').trigger('click');
    await flushPromises();
    await btn(w, '付款并核销').trigger('click');
    await flushPromises();
    expect(w.get('[data-testid="settle-summary"]').text()).toContain('供应商乙');
    await w.get('[data-testid="settle-reference"]').setValue('BANK-001');
    await btn(w, '确认付款').trigger('click');
    await flushPromises();
    expect(w.get('[data-testid="sales-confirm-message"]').text()).toContain('供应商乙');
    await confirmCancel(w).trigger('click');
    await flushPromises();
    expect(mocks.post).not.toHaveBeenCalled();

    await btn(w, '确认付款').trigger('click');
    await flushPromises();
    await confirmOk(w).trigger('click');
    await flushPromises();
    expect(mocks.post).toHaveBeenCalledTimes(1);
    expect(mocks.post.mock.calls[0][0]).toBe('/F006/finance/payables/ap1/settlements');
    expect(mocks.post.mock.calls[0][1]).toMatchObject({ supplierId: 's1', amount: 800, paymentMethod: 'BANK_TRANSFER', paymentReference: 'BANK-001' });
  });
});

describe('accounting-period 期间结账', () => {
  const period = (status: string, month = 1) => ({
    id: `p${month}`, factoryId: 'F006', year: 2026, month, status, adjustDeadline: '2026-02-01T00:00:00Z',
    reportReadyAt: '2026-02-01T00:00:00Z', reconciliationStatus: 'PASS', netProfitSnapshot: 12,
  });
  const setup = (rows: unknown[]) => mocks.get.mockResolvedValue({ success: true, data: rows });

  it('Chinese status and reconciliation result, no English codes', async () => {
    setup([period('CLOSED'), period('OPEN', 2), period('PENDING_CLOSE', 3)]);
    const w = mount(PeriodPage, { global: { stubs } });
    await flushPromises();
    for (const t of ['已结账', '开账中', '审批中', '对账 通过']) expect(w.text()).toContain(t);
    expect(w.text()).not.toMatch(/对账 PASS|PENDING_CLOSE/);
  });

  it('confirm-close path: cancel posts nothing, confirm posts confirm-close', async () => {
    setup([period('PENDING_CLOSE', 3)]);
    mocks.post.mockResolvedValue({ success: true, data: period('CLOSED', 3) });
    const w = mount(PeriodPage, { global: { stubs } });
    await flushPromises();
    await btn(w, '确认结账').trigger('click');
    await flushPromises();
    expect(w.get('[data-testid="sales-confirm-message"]').text()).toContain('2026-03');
    await confirmCancel(w).trigger('click');
    await flushPromises();
    expect(mocks.post).not.toHaveBeenCalled();

    await btn(w, '确认结账').trigger('click');
    await flushPromises();
    await confirmOk(w).trigger('click');
    await flushPromises();
    expect(mocks.post).toHaveBeenCalledWith('/F006/finance/accounting-periods/confirm-close', { year: 2026, month: 3 });
  });

  it('read-only account: close/reopen buttons are disabled', async () => {
    mocks.perm.canWrite = () => false;
    setup([period('OPEN')]);
    const w = mount(PeriodPage, { global: { stubs } });
    await flushPromises();
    expect(btn(w, '发起结账').attributes('disabled')).toBeDefined();
  });
});

describe('voucher-template 凭证模板', () => {
  const tpl = { id: 't1', voucherType: 'SALES_RECEIPT', name: '销售收款-标准', isActive: true, isDefault: false, entries: [{}, {}] };

  it('Chinese voucher type and status, entry count', async () => {
    mocks.list.mockResolvedValue([tpl]);
    const w = mount(TemplatePage, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('销售收款');
    expect(w.text()).toContain('启用');
    expect(w.text()).toContain('2 行');
    expect(w.text()).not.toContain('SALES_RECEIPT');
  });

  it('delete path: cancel keeps the template, confirm deletes it', async () => {
    mocks.list.mockResolvedValue([tpl]);
    mocks.del.mockResolvedValue(undefined);
    const w = mount(TemplatePage, { global: { stubs } });
    await flushPromises();
    await btn(w, '删除').trigger('click');
    await flushPromises();
    expect(w.get('[data-testid="sales-confirm-message"]').text()).toContain('销售收款-标准');
    await confirmCancel(w).trigger('click');
    await flushPromises();
    expect(mocks.del).not.toHaveBeenCalled();

    await btn(w, '删除').trigger('click');
    await flushPromises();
    await confirmOk(w).trigger('click');
    await flushPromises();
    expect(mocks.del).toHaveBeenCalledWith('F006', 't1');
  });
});
