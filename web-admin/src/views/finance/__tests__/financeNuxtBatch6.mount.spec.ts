/**
 * 第6批 (财务) 迁移后的真实挂载断言:
 *  - 金额列权限遮蔽: 无权限整列去掉, DOM 里既没有列头也没有数值 (不显示 0);
 *  - 状态 / 类型 / 来源业务显示中文, 不外露英文原码;
 *  - 备注里的内部键值 (XXX_ID=uuid) 不外露;
 *  - 进销存台账: 物料从下拉选 (不再手填 UUID), null 金额 = 「—」、真实 0 = 0.00;
 *  - 批量过账失败明细显示凭证字号, 不显示 UUID。
 * vitest 里没有 Nuxt UI 插件, 用共享最小桩 (UTable 真的渲染每行每列)。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, reactive } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';
import { cleanFinanceRemark, maskedAmount } from '../financeDisplay';

const mocks = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(),
  auth: { factoryId: 'F006' },
  perm: { canWrite: (_m?: string) => true, canViewPrice: true },
  listVouchers: vi.fn(), batchPost: vi.fn(),
  listRedline: vi.fn(),
}));
vi.mock('@/api/request', () => ({ get: mocks.get, post: mocks.post, default: { get: vi.fn() } }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => mocks.auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => mocks.perm }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }));
vi.mock('@/composables/useLinkChipCounts', () => ({ useLinkChipCounts: () => ({ fetchLinkChipCounts: vi.fn(), countsFor: () => ({ fileCount: 0, imageCount: 0, contractCount: 0 }) }) }));
vi.mock('@/composables/useWorkflowStats', () => ({ useWorkflowStats: () => ({ stats: { value: null }, loading: { value: false } }) }));
vi.mock('@/api/voucher', async (orig) => ({ ...(await orig<typeof import('@/api/voucher')>()), listVouchers: mocks.listVouchers, batchPostVouchers: mocks.batchPost }));
vi.mock('@/api/grossMarginConfig', () => ({ listGrossMarginConfigs: mocks.listRedline, createGrossMarginConfig: vi.fn(), updateGrossMarginConfig: vi.fn(), deleteGrossMarginConfig: vi.fn() }));

import CostsPage from '../costs/index.vue';
import PaymentsPage from '../payments/list.vue';
import InvoicesPage from '../invoices/list.vue';
import VoucherListPage from '../voucher/list.vue';
import LedgerPage from '../inventory-ledger/index.vue';
import RedlinePage from '../gross-margin-redline/index.vue';
import ReportsPage from '../reports/index.vue';

const passthrough = (name: string) => defineComponent({ name, setup: (_p, { slots }) => () => h('div', slots.default?.()) });
const stubs = {
  ...nuxtUiStubs,
  UTooltip: passthrough('UTooltip'),
  UIcon: { template: '<i />' },
  USwitch: { template: '<span />' },
  WorkflowBar: { template: '<div />' },
  OrderPeekDrawer: { template: '<div />' },
  InvoicePdfDownload: { template: '<span />' },
  AiUploadHint: { template: '<span />' },
  LinkChipCell: { template: '<span />' },
};
const UUID = '5b8c1d3e-1111-4222-8333-944455556666';

beforeEach(() => {
  for (const m of [mocks.get, mocks.post, mocks.listVouchers, mocks.batchPost, mocks.listRedline]) m.mockReset();
  mocks.auth = reactive({ factoryId: 'F006' });
  mocks.perm = reactive({ canWrite: () => true, canViewPrice: true });
  mocks.get.mockResolvedValue({ success: true, data: {} });
});

describe('cleanFinanceRemark / maskedAmount', () => {
  it('strips internal KEY_ID=value anchors, keeps user text, never leaks a uuid', () => {
    expect(cleanFinanceRemark('手工调整')).toBe('手工调整');
    expect(cleanFinanceRemark(null)).toBe('—');
    expect(cleanFinanceRemark(`SALES_ORDER_ID=${UUID};`)).toBe('—');
    expect(cleanFinanceRemark(`SHIPMENT_ID=${UUID}; 破损冲减`)).toBe('破损冲减');
    expect(cleanFinanceRemark(`REF=${UUID}; 备注`)).toBe('备注');
    expect(cleanFinanceRemark(`RECALL_EVENT_ID=2;PAYMENT_ID=${UUID}`)).toBe('—');
  });
  it('null is a dash, real zero stays zero', () => {
    expect(maskedAmount(null)).toBe('—');
    expect(maskedAmount(undefined)).toBe('—');
    expect(maskedAmount(0)).toBe('0.00');
    expect(maskedAmount(12.5, 4)).toBe('12.5000');
  });
});

describe('costs 交易记录', () => {
  const tx = [{ transactionNumber: 'TX-1', transactionType: 'AR_PAYMENT', counterpartyName: '客户甲', amount: 1200, balanceAfter: 5, remark: `SALES_ORDER_ID=${UUID};` }];
  const setup = () => mocks.get.mockImplementation(async (url: string) => (url.endsWith('/finance/overview')
    ? { success: true, data: { totalReceivable: 100, totalPayable: 40, scopeApplied: false } }
    : { success: true, data: { content: tx, totalElements: 1 } }));

  it('shows Chinese type, hides the internal remark, shows amount when permitted', async () => {
    setup();
    const w = mount(CostsPage, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('客户付款');
    expect(w.text()).not.toContain('AR_PAYMENT');
    expect(w.text()).not.toContain(UUID);
    expect(w.text()).not.toContain('SALES_ORDER_ID');
    expect(w.text()).toContain('¥1,200.00');
  });
  it('price-masked: no amount/balance column and no KPI money, not even a 0', async () => {
    mocks.perm.canViewPrice = false;
    setup();
    const w = mount(CostsPage, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('TX-1');
    expect(w.text()).not.toContain('1,200');
    expect(w.text()).not.toContain('应收总额');
    expect(w.text()).not.toContain('¥0.00');
    expect(w.findAll('th').map((t) => t.text())).not.toContain('金额');
  });
  it('overview missing: KPI shows a dash rather than 0', async () => {
    mocks.get.mockImplementation(async (url: string) => (url.endsWith('/finance/overview')
      ? { success: false, data: null }
      : { success: true, data: { content: [], totalElements: 0 } }));
    const w = mount(CostsPage, { global: { stubs } });
    await flushPromises();
    expect(w.get('[data-testid="kpi-receivable"]').text()).toContain('—');
    expect(w.get('[data-testid="kpi-receivable"]').text()).not.toContain('0.00');
    expect(w.find('[data-testid="costs-overview-error"]').exists()).toBe(true);
  });
});

describe('payments 收款', () => {
  const row = { id: 'p1', paymentNumber: 'PAY-1', customerName: '客户甲', amount: 300, paymentMethod: 'CREDIT', status: 'PENDING', paymentDate: '2026-11-01' };
  const setup = () => mocks.get.mockImplementation(async (url: string) => (url.endsWith('/finance/payments')
    ? { success: true, data: { content: [row], totalElements: 1 } }
    : { success: true, data: { content: [] } }));

  it('Chinese status + method, verify/reject buttons only for PENDING', async () => {
    setup();
    const w = mount(PaymentsPage, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('待核实');
    expect(w.text()).toContain('赊账');
    expect(w.text()).not.toMatch(/PENDING|CREDIT/);
    expect(w.find('[data-testid="payment-verify"]').exists()).toBe(true);
    expect(w.text()).toContain('¥300.00');
  });
  it('price-masked: amount column gone, create button gone', async () => {
    mocks.perm.canViewPrice = false;
    setup();
    const w = mount(PaymentsPage, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('PAY-1');
    expect(w.findAll('th').map((t) => t.text())).not.toContain('收款金额');
    expect(w.text()).not.toContain('300');
    expect(w.find('[data-testid="payment-create"]').exists()).toBe(false);
  });
  it('sales order is a dropdown (USelectMenu), not a typed id', async () => {
    setup();
    const w = mount(PaymentsPage, { global: { stubs } });
    await flushPromises();
    await w.get('[data-testid="payment-create"]').trigger('click');
    expect(w.findAllComponents({ name: 'USelectMenu' }).length).toBe(1);
  });
});

describe('invoices 开票', () => {
  const row = { id: 'i1', invoiceNumber: 'INV-1', customerName: '客户甲', totalAmount: 113, invoiceType: 'SPECIAL', status: 'REQUESTED', requestedAt: '2026-11-01 10:00' };
  const setup = () => mocks.get.mockImplementation(async (url: string) => (url.endsWith('/finance/invoices')
    ? { success: true, data: { content: [row], totalElements: 1 } }
    : { success: true, data: [] }));
  it('Chinese status/type; price-masked drops 价税合计', async () => {
    setup();
    let w = mount(InvoicesPage, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('增值税专票');
    expect(w.text()).toContain('待审核');
    expect(w.text()).not.toMatch(/SPECIAL|REQUESTED/);
    expect(w.text()).toContain('¥113.00');
    w.unmount();
    mocks.perm.canViewPrice = false;
    w = mount(InvoicesPage, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('INV-1');
    expect(w.findAll('th').map((t) => t.text())).not.toContain('价税合计');
    expect(w.text()).not.toContain('113');
  });
});

describe('voucher 凭证列表', () => {
  const v = { id: 'v1', voucherNumber: '记-001', voucherType: 'SALES_RECEIPT', voucherDate: '2026-11-01', totalDebit: 10, totalCredit: 10, sourceBusinessType: 'SALES_ORDER', status: 'DRAFT', createdAt: '2026-11-01' };
  it('source business type shows Chinese, not the enum code', async () => {
    mocks.listVouchers.mockResolvedValue({ content: [v], totalElements: 1 });
    const w = mount(VoucherListPage, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('销售订单');
    expect(w.text()).toContain('草稿');
    expect(w.text()).not.toMatch(/SALES_ORDER|SALES_RECEIPT|DRAFT/);
  });
  it('stocktake sources are Chinese and an unregistered source never leaks its English code', async () => {
    mocks.listVouchers.mockResolvedValue({ content: [
      { ...v, id: 'v2', voucherType: 'INVENTORY_STOCKTAKE', sourceBusinessType: 'INVENTORY_STOCKTAKE', createdAt: '2026-11-14T02:01:54.793128' },
      { ...v, id: 'v3', sourceBusinessType: 'SEMI_FINISHED_STOCKTAKE' },
      { ...v, id: 'v4', sourceBusinessType: 'BRAND_NEW_SOURCE' },
    ], totalElements: 3 });
    const w = mount(VoucherListPage, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('库存盘点');
    expect(w.text()).not.toContain('.793128'); // 创建时间不外露原始 ISO 微秒串
    expect(w.text()).toContain('半成品盘点');
    expect(w.text()).toContain('其他业务');
    expect(w.text()).not.toMatch(/STOCKTAKE|BRAND_NEW_SOURCE|未知状态/);
  });
  it('batch failure detail shows the voucher number, never the voucher uuid', async () => {
    mocks.listVouchers.mockResolvedValue({ content: [v], totalElements: 1 });
    mocks.batchPost.mockResolvedValue({ total: 1, successCount: 0, failureCount: 1, results: [{ voucherId: UUID, voucherNumber: '记-001', success: false, skipped: false, message: '借贷不平' }] });
    const w = mount(VoucherListPage, { global: { stubs } });
    await flushPromises();
    // 桩的 UCheckbox 不发事件: 直接走组件事件
    w.findComponent('[data-testid="voucher-select-all"]').vm.$emit('update:modelValue', true);
    await flushPromises();
    await w.get('[data-testid="voucher-batch-post"]').trigger('click');
    await flushPromises();
    await w.get('[data-testid="sales-confirm-ok"]').trigger('click');
    await flushPromises();
    expect(mocks.batchPost).toHaveBeenCalledWith('F006', ['v1']);
    const failures = w.get('[data-testid="voucher-batch-failures"]').text();
    expect(failures).toContain('记-001');
    expect(failures).toContain('借贷不平');
    expect(w.text()).not.toContain(UUID);
  });
});

describe('inventory-ledger 进销存台账', () => {
  const lines = [
    { materialTypeId: 'm1', materialCode: 'M1', materialName: '牛肉', unit: 'kg', openingQty: 1, inboundQty: null, outboundProductionQty: 0, outboundSalesQty: 0, transferInQty: 0, transferOutQty: 0, adjustQty: 0, closingQty: 1,
      openingAmount: 0, inboundAmount: null, outboundAmount: 5, adjustAmount: null, closingAmount: 7, movingAvgUnitPrice: null },
  ];
  it('material filter is a dropdown fed by the material archive (no typed UUID input)', async () => {
    mocks.get.mockResolvedValue({ success: true, data: [{ id: 'm1', name: '牛肉', code: 'M1' }] });
    const w = mount(LedgerPage, { global: { stubs } });
    await flushPromises();
    expect(mocks.get).toHaveBeenCalledWith('/F006/raw-material-types/active', expect.anything());
    const menu = w.findComponent({ name: 'USelectMenu' });
    expect((menu.props('items') as { label: string }[]).map((i) => i.label)).toEqual(['全部物料', '牛肉（M1）']);
    expect(w.html()).not.toContain('物料类型 ID');
  });
  it('null amounts are a masked dash (with tooltip), a real 0 amount stays 0.00', async () => {
    mocks.get.mockImplementation(async (url: string) => (url.endsWith('/inventory/ledger')
      ? { success: true, data: { startDate: '2026-11-01', endDate: '2026-11-17', lines } }
      : { success: true, data: [] }));
    const w = mount(LedgerPage, { global: { stubs } });
    await flushPromises();
    await w.get('[data-testid="ledger-start"]').setValue('2026-11-01');
    await w.get('[data-testid="ledger-end"]').setValue('2026-11-17');
    await w.get('[data-testid="ledger-query"]').trigger('click');
    await flushPromises();
    const cells = w.findAll('tbody td').map((t) => t.text());
    expect(cells).toContain('0.00');      // 期初金额 = 真实 0
    expect(cells).toContain('7.00');      // 期末金额
    expect(cells).toContain('5.00');      // 出库金额
    expect(w.findAll('[data-testid="ledger-masked"]').length).toBe(3); // 入库金额 / 调整金额 / 移动均价 为 null
    expect(cells).toContain('—');
  });
});

describe('gross-margin-redline', () => {
  it('a product that is not in the active list never leaks its internal id', async () => {
    mocks.get.mockResolvedValue({ success: true, data: [] });
    mocks.listRedline.mockResolvedValue({ success: true, data: [{ id: 'c1', productTypeId: UUID, targetGrossMargin: 0.3, isActive: true, description: null, createdAt: '2026-11-01' }] });
    const w = mount(RedlinePage, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('30%');
    expect(w.text()).not.toContain(UUID);
    expect(w.text()).toContain('未知产品');
  });
});

describe('reports 财务分析', () => {
  it('without price permission: no money at all, just the permission notice', async () => {
    mocks.perm.canViewPrice = false;
    mocks.get.mockResolvedValue({ success: true, data: { metrics: [], trendChart: { data: [{ period: '2026-11', revenue: 99, cost: 1, grossProfit: 98, grossMargin: 1 }] } } });
    const w = mount(ReportsPage, { global: { stubs } });
    await flushPromises();
    expect(w.find('[data-testid="reports-no-permission"]').exists()).toBe(true);
    expect(w.find('[data-testid="reports-stat-cards"]').exists()).toBe(false);
    expect(w.text()).not.toContain('99');
  });
  it('a missing 毛利润 metric is a dash, not 0.00', async () => {
    mocks.get.mockResolvedValue({ success: true, data: { metrics: [], trendChart: { data: [{ period: '2026-11', revenue: 100, cost: 40, grossProfit: 60, grossMargin: 60 }] } } });
    const w = mount(ReportsPage, { global: { stubs } });
    await flushPromises();
    const cards = w.get('[data-testid="reports-stat-cards"]').text();
    expect(cards).toContain('100.00');
    expect(cards).toMatch(/毛利润\s*—/);
  });
});
