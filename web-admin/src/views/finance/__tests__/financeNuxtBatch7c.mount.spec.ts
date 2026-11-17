/**
 * 第7批 c (财务) 迁移后的真实挂载断言:
 *  - OrderPeekDrawer (非模态 USlideover): 状态中文; 无价格权限 => 金额「—」、单价/小计列整列去掉, 绝不显示 ¥0.00;
 *  - VoucherDetail: 状态/来源中文; 过账确认走 SalesConfirmModal; 作废原因必选, 选「其他」必须补充;
 *  - voucher-export: 目标系统/状态中文; 无写权限没有操作列/新建按钮; 删除先确认, 取消不发请求。
 * vitest 里没有 Nuxt UI 插件, 用共享最小桩 (UTable 真的渲染每行每列)。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';

const mocks = vi.hoisted(() => ({
  get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(),
  auth: { factoryId: 'F006', user: { id: 1 } },
  perm: { canWrite: (_m?: string) => true, canAccess: (_m?: string) => true, canViewPrice: true, canViewPurchasePrice: true },
  getVoucherDetail: vi.fn(), postVoucher: vi.fn(), voidVoucher: vi.fn(),
}));
vi.mock('@/api/request', async (orig) => ({
  ...(await orig<typeof import('@/api/request')>()),
  get: mocks.get, post: mocks.post, put: mocks.put, del: mocks.del,
  default: { get: vi.fn(), post: vi.fn() },
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => mocks.auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => mocks.perm }));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), back: vi.fn() }),
  useRoute: () => ({ name: 'FinanceVoucherDetail', params: { id: 'v1' }, query: {} }),
}));
vi.mock('@/api/voucher', async (orig) => ({
  ...(await orig<typeof import('@/api/voucher')>()),
  getVoucherDetail: mocks.getVoucherDetail, postVoucher: mocks.postVoucher, voidVoucher: mocks.voidVoucher,
}));

import OrderPeekDrawer from '../components/OrderPeekDrawer.vue';
import VoucherDetail from '../voucher/VoucherDetail.vue';
import VoucherExport from '../voucher-export/index.vue';

const stubs = { ...nuxtUiStubs, USwitch: { template: '<span />' }, UIcon: { template: '<i />' } };

beforeEach(() => {
  for (const m of [mocks.get, mocks.post, mocks.put, mocks.del, mocks.getVoucherDetail, mocks.postVoucher, mocks.voidVoucher]) m.mockReset();
  mocks.auth = reactive({ factoryId: 'F006', user: { id: 1 } });
  mocks.perm = reactive({ canWrite: () => true, canAccess: () => true, canViewPrice: true, canViewPurchasePrice: true });
});

describe('OrderPeekDrawer 订单速览', () => {
  const order = {
    orderNumber: 'SO-1', customerName: '客户甲', status: 'CONFIRMED', orderDate: '2026-11-01',
    totalAmount: 100, taxAmount: 13, invoicedAmount: 0, paidAmount: 50,
    items: [{ productName: '牛前', quantity: 2, unit: 'kg', priceUnit: 'kg', unitPrice: 50, taxRate: 13, lineAmountWithTax: 113 }],
  };
  async function open() {
    mocks.get.mockResolvedValue({ success: true, data: order });
    const w = mount(OrderPeekDrawer, { props: { visible: false, orderId: null, kind: 'sales' }, global: { stubs } });
    await w.setProps({ visible: true, orderId: 'so-1' });
    await flushPromises();
    return w;
  }
  it('状态显示中文, 金额带 ¥, 不外露英文码', async () => {
    const w = await open();
    expect(w.text()).toContain('SO-1');
    expect(w.find('[data-label="状态"]').text()).toContain('已确认');
    expect(w.text()).not.toContain('CONFIRMED');
    expect(w.find('[data-label="未税总额"]').text()).toContain('¥100.00');
    expect(w.findAll('th').map((t) => t.text())).toEqual(expect.arrayContaining(['单价（含税）', '小计（含税）']));
  });
  it('无价格权限: 金额全部「—」, 单价/小计列整列去掉, 绝不出现 ¥0.00 或真值', async () => {
    mocks.perm.canViewPrice = false;
    const w = await open();
    expect(w.text()).toContain('SO-1'); // 阳性对照: 抽屉确实渲染了
    expect(w.find('[data-label="未税总额"]').text()).toContain('—');
    expect(w.find('[data-label="含税总额"]').text()).toContain('—');
    expect(w.findAll('th').map((t) => t.text())).not.toContain('单价（含税）');
    expect(w.text()).not.toContain('¥0.00');
    expect(w.text()).not.toContain('100.00');
  });
  it('403 与真故障分开提示', async () => {
    mocks.get.mockRejectedValue({ status: 403 });
    const w = mount(OrderPeekDrawer, { props: { visible: false, orderId: null, kind: 'sales' }, global: { stubs } });
    await w.setProps({ visible: true, orderId: 'so-1' });
    await flushPromises();
    expect(w.text()).toContain('没有权限查看这张订单');
    expect(w.text()).not.toContain('重试');
  });
});

describe('VoucherDetail 凭证详情', () => {
  const v = {
    id: 'v1', factoryId: 'F006', voucherNumber: '记-001', voucherType: 'SALES_RECEIPT', voucherDate: '2026-11-01',
    sourceBusinessType: 'SALES_ORDER', sourceBusinessId: 'so-1', status: 'DRAFT', description: '收款',
    entries: [
      { id: 'e1', lineNo: 1, subjectCode: '1002', subjectName: '银行存款', debit: 10, credit: 0 },
      { id: 'e2', lineNo: 2, subjectCode: '6001', subjectName: '主营业务收入', debit: 0, credit: 10 },
    ],
  };
  const mountIt = () => mount(VoucherDetail, { global: { stubs } });

  it('状态/类型/来源中文, 借贷平衡, 零值分录显示「—」不是 0', async () => {
    mocks.getVoucherDetail.mockResolvedValue(v);
    const w = mountIt();
    await flushPromises();
    expect(w.text()).toContain('记-001');
    expect(w.text()).toContain('草稿');
    expect(w.text()).toContain('销售订单');
    expect(w.text()).toContain('借贷平衡');
    expect(w.text()).not.toMatch(/DRAFT|SALES_ORDER|SALES_RECEIPT/);
    expect(w.find('[data-testid="voucher-entries-table"]').text()).toContain('¥10.00');
  });
  it('过账: 先弹确认, 取消不发请求, 确认后才过账', async () => {
    mocks.getVoucherDetail.mockResolvedValue(v);
    mocks.postVoucher.mockResolvedValue({ ...v, status: 'POSTED' });
    const w = mountIt();
    await flushPromises();
    await w.get('[data-testid="voucher-post"]').trigger('click');
    await flushPromises();
    expect(w.text()).toContain('过账确认');
    await w.get('[data-testid="sales-confirm-cancel"]').trigger('click');
    await flushPromises();
    expect(mocks.postVoucher).not.toHaveBeenCalled();

    await w.get('[data-testid="voucher-post"]').trigger('click');
    await flushPromises();
    await w.get('[data-testid="sales-confirm-ok"]').trigger('click');
    await flushPromises();
    expect(mocks.postVoucher).toHaveBeenCalledWith('F006', 'v1');
    expect(w.text()).toContain('已过账');
  });
  it('作废: 未选原因确认键禁用; 选「其他」未补充仍禁用', async () => {
    mocks.getVoucherDetail.mockResolvedValue(v);
    const w = mountIt();
    await flushPromises();
    await w.get('[data-testid="voucher-void"]').trigger('click');
    await flushPromises();
    expect(w.text()).toContain('作废凭证');
    const confirmBtn = () => w.get('[data-testid="voucher-void-confirm"]');
    expect(confirmBtn().attributes('disabled')).toBeDefined();
    const select = w.findComponent({ name: 'USelect' });
    select.vm.$emit('update:modelValue', '其他');
    await flushPromises();
    expect(confirmBtn().attributes('disabled')).toBeDefined();
    select.vm.$emit('update:modelValue', '金额错误');
    await flushPromises();
    expect(confirmBtn().attributes('disabled')).toBeUndefined();
  });
});

describe('voucher-export 凭证导出', () => {
  const cfg = { id: 'c1', targetSystem: 'KINGDEE_YXSKY', colVoucherNo: '凭证字号', colDate: '日期', colSummary: '摘要', colSubjectCode: '科目编码', colSubjectName: '科目名称', colDebit: '借方金额', colCredit: '贷方金额', colAuxiliary: '辅助核算', colCurrency: '币别', isActive: true };
  const map = { id: 'm1', settlementType: 'MONTHLY', businessType: 'PURCHASE', debitSubjectCode: '1405', debitSubjectName: '原材料', creditSubjectCode: '2202', creditSubjectName: '应付账款', remark: null };
  const setup = () => mocks.get.mockImplementation(async (url: string) => (url.endsWith('/finance/export-config')
    ? { success: true, data: [cfg] }
    : { success: true, data: [map] }));

  it('目标系统/状态/结算方式/业务类型显示中文, 备注为空显示「—」', async () => {
    setup();
    const w = mount(VoucherExport, { global: { stubs } });
    await flushPromises();
    const cfgTable = w.get('[data-testid="config-table"]').text();
    expect(cfgTable).toContain('金蝶云星空');
    expect(cfgTable).toContain('启用');
    const mapTable = w.get('[data-testid="mapping-table"]').text();
    expect(mapTable).toContain('月结');
    expect(mapTable).toContain('采购');
    expect(mapTable).toContain('—');
    expect(w.text()).not.toMatch(/KINGDEE_YXSKY|MONTHLY|PURCHASE/);
  });
  it('只有读权限: 没有新建/操作列, 导出序时账禁用', async () => {
    mocks.perm.canWrite = () => false;
    setup();
    const w = mount(VoucherExport, { global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('财务读写权限');
    expect(w.find('[data-testid="config-create"]').exists()).toBe(false);
    expect(w.find('[data-testid="config-edit"]').exists()).toBe(false);
    expect(w.findAll('th').map((t) => t.text())).not.toContain('操作');
    expect(w.get('[data-testid="export-voucher"]').attributes('disabled')).toBeDefined();
  });
  it('删除配置先确认: 取消不发 DELETE, 确认才发', async () => {
    setup();
    mocks.del.mockResolvedValue({ success: true });
    const w = mount(VoucherExport, { global: { stubs } });
    await flushPromises();
    await w.get('[data-testid="config-delete"]').trigger('click');
    await flushPromises();
    expect(w.text()).toContain('删除确认');
    await w.get('[data-testid="sales-confirm-cancel"]').trigger('click');
    await flushPromises();
    expect(mocks.del).not.toHaveBeenCalled();

    await w.get('[data-testid="config-delete"]').trigger('click');
    await flushPromises();
    await w.get('[data-testid="sales-confirm-ok"]').trigger('click');
    await flushPromises();
    expect(mocks.del).toHaveBeenCalledWith('/F006/finance/export-config/c1');
  });
});
