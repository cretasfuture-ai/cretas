import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, h, provide, inject, computed, reactive, type ComputedRef } from 'vue';
const api = vi.hoisted(() => ({ get: vi.fn() }));
const testRoute = reactive({ query: {} as Record<string, unknown> });
vi.mock('vue-router', () => ({ useRoute: () => testRoute }));
vi.mock('@/api/financeReport', () => ({ getShipmentSkuMargin: api.get }));
import Panel from '../ShipmentSkuMargin.vue';

const table = defineComponent({ props: ['data'], setup(p, { slots }) {
  provide('rows', computed(() => p.data)); return () => h('div', slots.default?.());
} });
const column = defineComponent({ props: ['prop', 'label'], setup(p, { slots }) {
  const rows = inject<ComputedRef<Record<string, unknown>[]>>('rows');
  return () => h('div', { 'data-column': p.label }, (rows?.value ?? []).map(row =>
    h('span', slots.default ? slots.default({ row }) : String(row[p.prop] ?? ''))));
} });
const stubs = {
  'el-alert': { props: ['title', 'type'], template: '<div class="notice" :data-type="type">{{ title }}</div>' },
  'el-descriptions': { template: '<div><slot /></div>' },
  'el-descriptions-item': { props: ['label'], template: '<div>{{ label }}<slot /></div>' },
  'el-table': table, 'el-table-column': column,
  'el-button': { template: '<button><slot /></button>' },
  'el-dialog': { props: ['modelValue', 'title'], template: '<section v-if="modelValue" role="dialog"><h2>{{ title }}</h2><slot /></section>' },
};
const payload = {
  periodStart: '2026-10-01', periodEnd: '2026-10-31',
  rows: [
    { productTypeId: 'A', productName: '散装', unit: 'kg', quantity: 9, revenue: 270, cost: 137.62, grossProfit: 132.38, marginRate: 49.03, shipmentLineCount: 3, batchCount: 2 },
    { productTypeId: 'B', productName: '盒装', unit: '盒', quantity: 30, revenue: 450, cost: 208.5, grossProfit: 241.5, marginRate: 53.67, shipmentLineCount: 3, batchCount: 2, sources: [{ shipmentLineId: 42, deliveryNumber: 'SHIP-BOX-001', deliveryDate: '2026-10-16', quantity: 30, unit: '盒', revenue: 450, rawCost: 208.495, cost: 208.5, batches: [
      { batchNumber: '盒装批次&A', quantity: 14, unit: '盒', unitCost: 6.4165, rawCost: 89.831, cost: 89.83 },
      { batchNumber: '盒装批次B', quantity: 16, unit: '盒', unitCost: 7.4165, rawCost: 118.664, cost: 118.66 },
    ] }] },
  ],
  totalRevenue: 720, totalCost: 346.12, grossProfit: 373.88, marginRate: 51.93, roundingAdjustment: 0,
  reconciliationStatus: 'MATCH', reconciliationMessage: '实际出货与同期间已过账利润表一致',
  financialStatement: { totalRevenue: 720, totalCost: 346.12, grossProfit: 373.88, netProfit: 373.88 },
};
function page() { return mount(Panel, { props: { factoryId: 'NEW', period: ['2026-10-01', '2026-10-31'], refreshKey: 0 }, global: { stubs, directives: { loading: {} } } }); }
beforeEach(() => { testRoute.query = {}; api.get.mockReset(); api.get.mockResolvedValue({ success: true, data: payload }); });
describe('实际出货SKU毛利', () => {
  it('保留出货金额，并显示独立退货事实与利润表的整月核对', async () => {
    api.get.mockResolvedValue({ success: true, data: { ...payload,
      reconciliationReturnedRevenue: 75, reconciliationReturnedCost: 38.08,
      reconciliationNetRevenue: 645, reconciliationNetCost: 308.04, reconciliationNetGrossProfit: 336.96,
      financialStatement: { totalRevenue: 645, totalCost: 308.04, grossProfit: 336.96, netProfit: 332.25 },
    } });
    const wrapper = page(); await flushPromises();
    const bridge = wrapper.get('[aria-label="整月退货与利润表核对"]');
    for (const value of ['本期退货冲减收入', '¥75.00', '实物退货恢复成本', '¥38.08', '¥645.00', '¥308.04', '¥336.96']) expect(bridge.text()).toContain(value);
    expect(wrapper.text()).toContain('¥720.00'); expect(wrapper.text()).toContain('¥346.12');
    expect(wrapper.text()).toContain('净利润 ¥332.25'); wrapper.unmount();
  });
  it('缺少退货核对字段时不伪造零调整或由财务金额反推差额', async () => {
    api.get.mockResolvedValue({ success: true, data: { ...payload, reconciliationReturnedRevenue: 75,
      reconciliationReturnedCost: null, financialStatement: { totalRevenue: 645, totalCost: 308.04 } } });
    const wrapper = page(); await flushPromises();
    expect(wrapper.find('[aria-label="整月退货与利润表核对"]').exists()).toBe(false);
    wrapper.unmount();
  });
  it('明示退货核对中原值统一取分与显示金额相减的一分钱差额', async () => {
    api.get.mockResolvedValue({ success: true, data: { ...payload, totalCost: 441.62,
      reconciliationReturnedRevenue: 75, reconciliationReturnedCost: 41.08,
      reconciliationNetRevenue: 765, reconciliationNetCost: 400.53, reconciliationNetGrossProfit: 364.47 } });
    const wrapper = page(); await flushPromises();
    expect(wrapper.get('[aria-label="整月退货与利润表核对"]').text()).toContain('¥-0.01 的舍入差额');
    wrapper.unmount();
  });
  it('真实零退货也展示整月核对，不用于不完整月份', async () => {
    api.get.mockResolvedValue({ success: true, data: { ...payload, reconciliationReturnedRevenue: 0,
      reconciliationReturnedCost: 0, reconciliationNetRevenue: 720, reconciliationNetCost: 346.12,
      reconciliationNetGrossProfit: 373.88 } });
    const wrapper = page(); await flushPromises();
    expect(wrapper.get('[aria-label="整月退货与利润表核对"]').text()).toContain('¥0.00');
    api.get.mockResolvedValue({ success: true, data: { ...payload, financialStatement: null } });
    await wrapper.setProps({ period: ['2026-10-19', '2026-10-19'] }); await flushPromises();
    expect(wrapper.find('[aria-label="整月退货与利润表核对"]').exists()).toBe(false); wrapper.unmount();
  });
  it('仅完整会计月提供同厂同期间利润表链接', async () => {
    const wrapper = page(); await flushPromises();
    const link = wrapper.findAll('a').find(a => a.text() === '查看同期间利润表'); expect(link).toBeDefined();
    expect(Object.fromEntries(new URL(link!.attributes('href'),'http://localhost').searchParams)).toEqual({tab:'income-statement',factoryId:'NEW',periodStart:'2026-10-01',periodEnd:'2026-10-31'});
    await wrapper.setProps({ period:['2026-10-16','2026-10-31'] }); await flushPromises();
    expect(wrapper.findAll('a').some(a=>a.text()==='查看同期间利润表')).toBe(false); expect(wrapper.text()).toContain('当前不是完整会计月'); wrapper.unmount();
  });
  it('读取本厂同月实际成本，显示双SKU与按总收入计算的合计毛利率', async () => {
    const wrapper = page(); await flushPromises();
    expect(api.get).toHaveBeenCalledWith('NEW', '2026-10-01', '2026-10-31');
    for (const text of ['9 公斤', '30 盒', '¥137.62', '¥208.50', '¥132.38', '¥241.50', '¥720.00', '¥346.12', '¥373.88', '51.9%']) expect(wrapper.text()).toContain(text);
    expect(wrapper.find('.notice').attributes('data-type')).toBe('success');
  });
  it('刷新失败后清除旧数字，不用订单或零成本替代', async () => {
    const wrapper = page(); await flushPromises();
    api.get.mockRejectedValue(new Error('实际出货批次缺少成本'));
    await wrapper.setProps({ refreshKey: 1 }); await flushPromises();
    expect(wrapper.text()).toContain('实际出货批次缺少成本');
    expect(wrapper.text()).not.toContain('¥720.00'); expect(wrapper.text()).not.toContain('¥0.00');
  });
  it('期间不完整或账表不符时显示真实警告', async () => {
    api.get.mockResolvedValue({ success: true, data: { ...payload, reconciliationStatus: 'PARTIAL_PERIOD', financialStatement: null, reconciliationMessage: '请选择完整会计月核对' } });
    const wrapper = page(); await flushPromises(); expect(wrapper.find('.notice').attributes('data-type')).toBe('warning'); expect(wrapper.text()).toContain('请选择完整会计月核对');
    api.get.mockResolvedValue({ success: true, data: { ...payload, reconciliationStatus: 'MISMATCH', reconciliationMessage: '账表存在差异' } });
    await wrapper.setProps({ refreshKey: 1 }); await flushPromises(); expect(wrapper.text()).toContain('账表存在差异');
  });
  it('旧工厂慢请求不能覆盖切厂后的数据', async () => {
    let resolveOld!: (v: unknown) => void;
    api.get.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }));
    const wrapper = page(); await wrapper.setProps({ factoryId: 'OTHER' }); await flushPromises();
    resolveOld({ success: true, data: { ...payload, totalRevenue: 999999 } }); await flushPromises();
    expect(wrapper.text()).toContain('¥720.00'); expect(wrapper.text()).not.toContain('999,999');
  });
  it('零金额保留，零收入的毛利率仍为空', async () => {
    api.get.mockResolvedValue({ success: true, data: { ...payload, rows: [], totalRevenue: 0, totalCost: 0, grossProfit: 0, marginRate: null } });
    const wrapper = page(); await flushPromises(); expect(wrapper.text()).toContain('¥0.00'); expect(wrapper.text()).toContain('—');
  });
  it('逐SKU显示与统一取整有差额时明示', async () => {
    api.get.mockResolvedValue({ success: true, data: { ...payload, roundingAdjustment: 0.01 } });
    const wrapper = page(); await flushPromises(); expect(wrapper.text()).toContain('舍入差额为 ¥0.01');
  });
  it('复用同次汇总展示出货行和多批次原生数量成本，并正确编码既有追溯入口', async () => {
    const wrapper = page(); await flushPromises();
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
    await wrapper.get('button').trigger('click');
    const dialog = wrapper.get('[role="dialog"]');
    for (const text of ['盒装 · 实际出货来源', 'SHIP-BOX-001', '第 42 行', '14 盒', '16 盒', '6.4165 元/盒', '7.4165 元/盒', '¥450.00', '¥208.50', '舍入差额']) expect(dialog.text()).toContain(text);
    expect(dialog.findAll('a').find(a => a.text() === '查看批次追溯')?.attributes('href')).toBe('/production/traceability?batchNumber='+encodeURIComponent('盒装批次&A'));
    expect(api.get).toHaveBeenCalledTimes(1);
  });
  it.each([{ refreshKey: 1 }, { factoryId: 'OTHER' }, { period: ['2026-09-01', '2026-09-30'] }])('刷新、切厂、切期间时立即清除来源 %j', async (change) => {
    const wrapper = page(); await flushPromises(); await wrapper.get('button').trigger('click');
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true);
    api.get.mockReturnValue(new Promise(() => {}));
    await wrapper.setProps(change);
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false); expect(wrapper.text()).not.toContain('SHIP-BOX-001');
  });
  it('脱敏或来源未返回时不提供假来源按钮', async () => {
    api.get.mockResolvedValue({ success: true, data: { ...payload, rows: payload.rows.map(row => ({ ...row, sources: null })) } });
    const wrapper = page(); await flushPromises(); expect(wrapper.find('button').exists()).toBe(false);
  });
  it('本厂同期间链接只定位查询结果里的指定行，刷新不会重新弹出旧来源', async () => {
    testRoute.query = { factoryId: 'NEW', periodStart: '2026-10-01', periodEnd: '2026-10-31', sourceSku: 'B', sourceLineId: '42' };
    const wrapper = page(); await flushPromises();
    expect(wrapper.get('[role="dialog"]').text()).toContain('SHIP-BOX-001');
    expect(wrapper.get('[data-source-line-id="42"]').classes()).toContain('focused-line');
    await wrapper.setProps({ refreshKey: 1 }); await flushPromises();
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false); wrapper.unmount();
  });
  it('外厂或不存在的来源链接不会打开旧对象', async () => {
    testRoute.query = { factoryId: 'OTHER', periodStart: '2026-10-01', periodEnd: '2026-10-31', sourceSku: 'B', sourceLineId: '42' };
    const wrapper = page(); await flushPromises(); expect(wrapper.find('[role="dialog"]').exists()).toBe(false);
    testRoute.query.factoryId = 'NEW'; await flushPromises(); expect(wrapper.find('[role="dialog"]').exists()).toBe(true);
    testRoute.query.sourceLineId = '999'; await flushPromises();
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false); expect(wrapper.text()).toContain('未找到这条出货来源');
    expect(wrapper.find('button').exists()).toBe(true);
    await wrapper.get('button').trigger('click'); expect(wrapper.get('[role="dialog"]').text()).toContain('SHIP-BOX-001'); wrapper.unmount();
  });
  it('明确显示收入按行合计和成本的分位差额', async () => {
    const base = payload.rows[1].sources![0];
    const sources = [64.17, 74.17, 70.17].map((cost, i) => ({ ...base, shipmentLineId: 40+i, quantity: 10, revenue: 150, cost }));
    api.get.mockResolvedValue({ success: true, data: { ...payload, rows: [{ ...payload.rows[1], sources }] } });
    const wrapper = page(); await flushPromises(); await wrapper.get('button').trigger('click');
    const text = wrapper.get('[role="dialog"]').text();
    for (const value of ['出货行收入合计 ¥450.00', '出货行显示成本相加 ¥208.51', '统一取整成本 ¥208.50', '显示舍入差额 ¥-0.01']) expect(text).toContain(value);
    expect(wrapper.get('[role="dialog"]').findAll('a').find(a => a.text() === '直接打开这一行')?.attributes('href')).toContain('sourceLineId=40');
    wrapper.unmount();
  });
  it('来源金额未知时不显示假合计或零差额', async () => {
    const base = payload.rows[1];
    api.get.mockResolvedValue({ success: true, data: { ...payload, rows: [{ ...base, cost: null, revenue: null, sources: [{ ...base.sources![0], cost: null, revenue: null }] }] } });
    const wrapper = page(); await flushPromises(); await wrapper.get('button').trigger('click');
    const text = wrapper.get('[role="dialog"]').text();
    for (const value of ['出货行收入合计', '出货行显示成本相加', '显示舍入差额', '¥0.00']) expect(text).not.toContain(value);
    wrapper.unmount();
  });
});
