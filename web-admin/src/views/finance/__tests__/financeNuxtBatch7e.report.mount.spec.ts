/**
 * 第7b批: 财务三大报表 (利润表 / 资产负债表 / 现金流量表 / 壳页) 迁到 Nuxt UI 后的真实挂载断言,
 * 加凭证类型中文 (详情头部不再露 TRANSFER 之类原码)。
 * 缺值显示「—」不显示 0; 真实 0 仍显示 0.00。vitest 里没有 Nuxt UI 插件, 用共享最小桩。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
import ElementPlus from 'element-plus';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';
import { voucherTypeText } from '@/utils/enumDisplay';

const api = vi.hoisted(() => ({
  income: vi.fn(), balance: vi.fn(), cash: vi.fn(), exportBalance: vi.fn(),
  getVoucher: vi.fn(), push: vi.fn(),
}));
const auth = reactive({ factoryId: 'F006', user: { id: 7 } });
const route = reactive<{ name: string; params: Record<string, string>; query: Record<string, unknown> }>({
  name: 'FinanceVoucherDetail', params: { id: 'v1' }, query: {},
});
vi.mock('@/api/financeReport', () => ({
  getIncomeStatement: api.income, getBalanceSheet: api.balance, getCashFlow: api.cash,
  exportBalanceSheet: api.exportBalance, exportCashFlow: vi.fn(),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canAccess: () => true }),
}));
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ back: vi.fn(), push: api.push }) }));
vi.mock('@/api/inventory', () => ({ getReturnRefundByRequest: vi.fn() }));
vi.mock('@/api/voucher', async (orig) => ({
  ...(await orig<typeof import('@/api/voucher')>()),
  getVoucherDetail: api.getVoucher,
  getVoucherByBusiness: vi.fn(),
}));

import IncomeStatement from '../report/IncomeStatement.vue';
import BalanceSheet from '../report/BalanceSheet.vue';
import CashFlow from '../report/CashFlow.vue';
import VoucherDetail from '../voucher/VoucherDetail.vue';
import { VOUCHER_TYPE_LABEL } from '@/api/voucher';

const stubs = { ...nuxtUiStubs };
const now = new Date();
const Y = now.getFullYear();
const M = now.getMonth() + 1;

function incomeData(over: Record<string, unknown> = {}) {
  return {
    factoryId: 'F006', startYear: Y, startMonth: 1, endYear: Y, endMonth: M,
    totalRevenue: 1000, totalCost: 400, grossProfit: 600, totalExpense: 100, operatingProfit: 500,
    incomeTax: 50, netProfit: 450, grossMarginStatus: 'CALCULABLE', pendingDraftVoucherCount: 0,
    revenues: [{ accountCode: '6001', accountName: '主营业务收入', amount: 1000 }], costs: [], expenses: [],
    ...over,
  };
}

beforeEach(() => {
  api.income.mockReset(); api.balance.mockReset(); api.cash.mockReset(); api.getVoucher.mockReset();
  auth.factoryId = 'F006';
  route.query = {};
});

describe('利润表 (Nuxt UI)', () => {
  it('KPI 与明细表显示金额, 缺值显示「—」不显示 0', async () => {
    api.income.mockResolvedValue({ success: true, data: incomeData({ totalRevenue: null, revenues: [{ accountCode: '6001', accountName: '收入', amount: null }] }) });
    const w = mount(IncomeStatement, { global: { stubs } });
    await flushPromises();
    expect(w.get('[data-testid="income-kpi-revenue"]').text()).toContain('—');
    expect(w.get('[data-testid="income-kpi-revenue"]').text()).not.toContain('0.00');
    expect(w.get('[data-testid="income-statement-table"]').text()).toContain('450.00');
    expect(w.get('[data-testid="income-detail-revenues"]').text()).toContain('—');
    expect(w.get('[data-testid="income-detail-revenues"]').text()).not.toContain('0.00');
  });
  it('真实 0 仍显示 0.00', async () => {
    api.income.mockResolvedValue({ success: true, data: incomeData({ totalExpense: 0 }) });
    const w = mount(IncomeStatement, { global: { stubs } });
    await flushPromises();
    expect(w.get('[data-testid="income-statement-table"]').text()).toContain('0.00');
  });
});

describe('资产负债表 (Nuxt UI)', () => {
  const bs = (over: Record<string, unknown> = {}) => ({
    success: true,
    data: {
      assets: [{ accountCode: '1001', accountName: '库存现金', amount: 800 }],
      liabilities: [{ accountCode: '2202', accountName: '应付账款', amount: 300 }],
      equity: [{ accountCode: '4001', accountName: '实收资本', amount: 500 }],
      totalAssets: 800, totalLiabilities: 300, totalEquity: 500, totalLiabilitiesAndEquity: 800,
      balanceCheck: true, periodStatus: 'CLOSED', pendingDraftVoucherCount: 0, ...over,
    },
  });
  it('三张表与合计、平衡提示都渲染', async () => {
    api.balance.mockResolvedValue(bs());
    const w = mount(BalanceSheet, { global: { stubs } });
    await flushPromises();
    expect(api.balance).toHaveBeenCalledWith('F006', Y, M);
    const t = w.get('[data-testid="balance-sheet"]').text();
    for (const x of ['库存现金', '应付账款', '实收资本', '资产合计', '800.00', '平衡校验通过']) expect(t).toContain(x);
  });
  it('未结账时提示并能跳到期间结账; 合计缺值显示「—」', async () => {
    api.balance.mockResolvedValue(bs({ periodStatus: 'OPEN', totalAssets: null }));
    const w = mount(BalanceSheet, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('开账中');
    await w.get('[data-testid="balance-go-period"]').trigger('click');
    expect(api.push).toHaveBeenCalledWith({ name: 'FinanceAccountingPeriod', query: { year: String(Y), month: String(M) } });
    expect(w.text()).toMatch(/资产合计\s*—/);
  });
  it('平衡校验失败显示错误提示', async () => {
    api.balance.mockResolvedValue(bs({ balanceCheck: false, totalLiabilitiesAndEquity: 700 }));
    const w = mount(BalanceSheet, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('平衡校验失败');
    expect(w.text()).not.toContain('平衡校验通过');
  });
});

describe('现金流量表 (Nuxt UI)', () => {
  const cf = (over: Record<string, unknown> = {}) => ({
    success: true,
    data: {
      operatingNetCashFlow: 120, investingNetCashFlow: -30, financingNetCashFlow: null, netIncreaseInCash: 90,
      operatingActivities: [{ counterpartCode: '6001', counterpartName: '销售回款', inflow: 200, outflow: 80, netAmount: 120 }],
      investingActivities: [], financingActivities: [], pendingDraftVoucherCount: 0, ...over,
    },
  });
  it('四张卡 + 三段明细; 缺值「—」、空段「无相关活动」', async () => {
    api.cash.mockResolvedValue(cf());
    const w = mount(CashFlow, { global: { stubs } });
    await flushPromises();
    expect(api.cash).toHaveBeenCalledWith('F006', Y, 1, Y, M);
    expect(w.get('[data-testid="cashflow-kpi-operating"]').text()).toContain('120.00');
    expect(w.get('[data-testid="cashflow-kpi-investing"]').text()).toContain('-30.00');
    expect(w.get('[data-testid="cashflow-kpi-financing"]').text()).toContain('—');
    expect(w.get('[data-testid="cashflow-kpi-financing"]').text()).not.toContain('0.00');
    expect(w.get('[data-testid="cashflow-section-operating"]').text()).toContain('销售回款');
    expect(w.get('[data-testid="cashflow-section-investing"]').text()).toContain('无相关活动');
  });
});

describe('凭证类型中文', () => {
  it('后端 VoucherType 全部 12 个值 + 盘点来源码都有中文, 未知码不露英文', () => {
    const backend = ['SALES_RECEIPT', 'PURCHASE_PAYMENT', 'INVENTORY_TRANSFER', 'EXPENSE', 'WAGE', 'RETURN',
      'DEPRECATION', 'PL_CLOSING', 'INVENTORY_STOCKTAKE', 'COST_CARRYOVER', 'CASH_RECEIPT', 'CASH_PAYMENT'];
    for (const c of backend) expect(voucherTypeText(c), c).not.toMatch(/[A-Za-z_]/);
    expect(voucherTypeText('INVENTORY_STOCKTAKE')).toBe('库存盘点');
    expect(voucherTypeText('SEMI_FINISHED_STOCKTAKE')).toBe('半成品盘点');
    expect(voucherTypeText('TRANSFER')).toBe('调拨');
    expect(voucherTypeText('SOMETHING_NEW')).toBe('其他类型');
    expect(voucherTypeText(null)).toBe('—');
  });
  it('与 api/voucher 的 VOUCHER_TYPE_LABEL 两份一致', () => {
    for (const [k, v] of Object.entries(VOUCHER_TYPE_LABEL)) expect(voucherTypeText(k), k).toBe(v);
  });
  it('凭证详情头部「类型」显示中文, 不显示 TRANSFER 原码', async () => {
    api.getVoucher.mockResolvedValue({
      id: 'v1', factoryId: 'F006', voucherNumber: 'V-1', voucherType: 'TRANSFER', voucherDate: '2026-10-16',
      sourceBusinessType: 'SALES_ORDER', sourceBusinessId: 'x', totalDebit: 0, totalCredit: 0, status: 'DRAFT',
      description: '调拨凭证', entries: [{ id: 'l1', lineNo: 1, subjectCode: '1001', subjectName: '现金', debit: 0, credit: 0 }],
    });
    const w = mount(VoucherDetail, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(w.text()).toContain('类型: 调拨');
    expect(w.text()).not.toContain('TRANSFER');
  });
});
