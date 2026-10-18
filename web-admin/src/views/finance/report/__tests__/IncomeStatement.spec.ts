import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
const api = vi.hoisted(() => ({ get: vi.fn(), message: vi.fn() }));
const auth = reactive({ factoryId: 'NEW' });
const route = reactive({ query: {} as Record<string, unknown> });
vi.mock('@/api/financeReport', () => ({ getIncomeStatement: api.get }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('vue-router', () => ({ useRoute: () => route }));
vi.mock('element-plus', async original => ({ ...await original<typeof import('element-plus')>(), ElMessage: api.message }));
import Panel from '../IncomeStatement.vue';

const link = { factoryId: 'NEW', periodStart: '2026-10-01', periodEnd: '2026-10-31' };
function result(factory = 'NEW', month = 10, revenue = 720) {
  return { success: true, data: { factoryId: factory, startYear: 2026, startMonth: month, endYear: 2026, endMonth: month,
    totalRevenue: revenue, totalCost: 346.12, grossProfit: revenue-346.12, totalExpense: 0, operatingProfit: revenue-346.12,
    incomeTax: 0, netProfit: revenue-346.12, grossMarginStatus: 'CALCULABLE', pendingDraftVoucherCount: 0,
    revenues: [], costs: [], expenses: [] } };
}
const stubs = {
  'el-card': { template: '<div><slot name="header" /><slot /></div>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-date-picker': { template: '<div />' },
  'el-alert': { props: ['title'], template: '<div role="alert">{{ title }}<slot name="title" /></div>' },
  'el-skeleton': { template: '<div>正在读取</div>' },
  'el-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
  'el-row': { template: '<div><slot /></div>' }, 'el-col': { template: '<div><slot /></div>' },
  'el-table': { template: '<div><slot /></div>' }, 'el-table-column': { template: '<div />' },
  'el-collapse': { template: '<div><slot /></div>' }, 'el-collapse-item': { template: '<div><slot /></div>' },
};
function page() { return mount(Panel, { global: { stubs } }); }
beforeEach(() => { auth.factoryId = 'NEW'; route.query = { ...link }; api.get.mockReset(); api.message.mockReset(); api.get.mockResolvedValue(result()); });
describe('同厂同期间利润表', () => {
  it('链接直接请求10月，不扩成年度累计', async () => {
    const wrapper = page(); await flushPromises();
    expect(api.get).toHaveBeenCalledWith('NEW', 2026, 10, 2026, 10);
    expect(wrapper.text()).toContain('2026-10 至'); expect(wrapper.text()).toContain('720.00'); expect(wrapper.text()).toContain('373.88'); wrapper.unmount();
  });
  it('读取失败时立即清除旧数，错误留在页面上，正常重试可恢复', async () => {
    const wrapper = page(); await flushPromises();
    api.get.mockRejectedValueOnce(new Error('本地报表读取失败'));
    await wrapper.get('button').trigger('click'); await flushPromises();
    expect(wrapper.get('[data-testid="income-error"]').text()).toContain('本地报表读取失败');
    expect(wrapper.text()).not.toContain('720.00'); expect(wrapper.text()).not.toContain('373.88');
    await wrapper.get('button').trigger('click'); await flushPromises(); expect(wrapper.text()).toContain('720.00'); wrapper.unmount();
  });
  it('切期间期间清旧数据，慢旧请求不覆盖最新期间', async () => {
    let resolveOld!: (v: unknown) => void;
    api.get.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }));
    const wrapper = page();
    api.get.mockResolvedValue(result('NEW', 9, 90));
    const vm = wrapper.vm as unknown as { dateRange: [string,string] | null };
    vm.dateRange = ['2026-09','2026-09']; await flushPromises();
    expect(api.get).toHaveBeenLastCalledWith('NEW',2026,9,2026,9);
    resolveOld(result()); await flushPromises();
    expect(wrapper.text()).toContain('90.00'); expect(wrapper.text()).not.toContain('720.00'); wrapper.unmount();
  });
  it('切厂时不沿用旧厂结果，旧请求失败也不覆盖新厂', async () => {
    route.query = { periodStart:'2026-10-01', periodEnd:'2026-10-31' };
    let rejectOld!: (e: Error) => void;
    api.get.mockReturnValueOnce(new Promise((_, reject) => { rejectOld = reject; }));
    const wrapper = page(); api.get.mockResolvedValue(result('OTHER',10,100));
    auth.factoryId = 'OTHER'; await flushPromises();
    rejectOld(new Error('旧厂读取失败')); await flushPromises();
    expect(api.get).toHaveBeenLastCalledWith('OTHER',2026,10,2026,10);
    expect(wrapper.text()).toContain('100.00'); expect(wrapper.text()).not.toContain('720.00'); expect(api.message).not.toHaveBeenCalled(); wrapper.unmount();
  });
  it.each([{ factoryId: 'OTHER' }, { startMonth: 9 }])('返回工厂或期间错误不显示金额 %j', async change => {
    api.get.mockResolvedValue({ ...result(), data: { ...result().data, ...change } });
    const wrapper = page(); await flushPromises();
    expect(wrapper.text()).toContain('工厂或期间与本次查询不一致'); expect(wrapper.text()).not.toContain('720.00'); wrapper.unmount();
  });
  it('外厂链接不发送请求，部分月不偷偷扩月', async () => {
    route.query.factoryId = 'OTHER'; const wrapper = page(); await flushPromises();
    expect(api.get).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('所属工厂与当前账号不一致');
    route.query = { ...link, periodStart: '2026-10-16' }; await flushPromises();
    expect(api.get).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('完整会计月'); wrapper.unmount();
  });
  it('月份清空不会报错或把旧数作为零显示', async () => {
    const wrapper = page(); await flushPromises(); const calls = api.get.mock.calls.length;
    const vm = wrapper.vm as unknown as { dateRange: [string,string] | null }; vm.dateRange = null; await flushPromises();
    expect(api.get).toHaveBeenCalledTimes(calls); expect(wrapper.text()).not.toContain('720.00'); expect(wrapper.text()).toContain('有效的起止月份'); wrapper.unmount();
  });
  it('卸载后的读取失败不弹错误', async () => {
    let reject!: (e: Error) => void; api.get.mockReturnValueOnce(new Promise((_, r) => { reject = r; }));
    const wrapper = page(); wrapper.unmount(); reject(new Error('已离开页面')); await flushPromises(); expect(api.message).not.toHaveBeenCalled();
  });
  it('无价格权限返回null时金额显示未知，不伪装成零', async () => {
    api.get.mockResolvedValue({ ...result(), data: { ...result().data, revenues:null,costs:null,expenses:null,
      totalRevenue:null,totalCost:null,grossProfit:null,totalExpense:null,operatingProfit:null,incomeTax:null,netProfit:null } });
    const wrapper=page();await flushPromises();expect(wrapper.text()).toContain('—');
    expect(wrapper.text()).not.toContain('720.00');expect(wrapper.text()).not.toContain('0.00');wrapper.unmount();
  });
});
