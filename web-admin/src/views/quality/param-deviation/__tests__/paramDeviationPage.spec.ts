import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';

/**
 * 工艺参数偏离页: 汇总表 + 明细表; 批次号能跳批次详情; 状态筛选只作用于明细;
 * 空状态分两种 —— 「没有偏离」(有参数记录都在范围内) 与「没配参数所以没有数据」(分母为 0)。
 */
const state = vi.hoisted(() => ({ get: vi.fn(), push: vi.fn(), toast: vi.fn() }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: state.push }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F-PD' }) }));
vi.mock('@/utils/errorToast', () => ({ handleCatchError: (...args: unknown[]) => state.toast(...args) }));
vi.mock('@/api/request', () => ({
  default: { get: vi.fn() },
  get: (...args: unknown[]) => state.get(...args),
}));
import Page from '../index.vue';

const ok = (data: unknown) => ({ success: true, data });
const window30 = { startDate: '2026-10-06', endDate: '2026-11-04' };

const summaryWithDeviations = {
  window: window30,
  totalChecked: 5,
  totalDeviations: 3,
  rows: [
    { processName: '炒制', paramKey: 'oilTemp', paramLabel: '油温', unit: '℃', reportCount: 3, deviationCount: 2, highCount: 1, lowCount: 1, deviationRate: 66.7 },
    { processName: '炒制', paramKey: 'fryMinutes', paramLabel: '炒制时间', unit: 'min', reportCount: 2, deviationCount: 1, highCount: 0, lowCount: 1, deviationRate: 50.0 },
  ],
};
const detailRow = {
  reportId: 501, reportDate: '2026-11-03', reportedAt: '2026-11-03T09:30:00', batchId: 77, batchNumber: 'PPD-A1',
  productTypeId: 'PT-A', productName: '牛肉干', processName: '炒制', paramKey: 'oilTemp', paramLabel: '油温',
  measuredValue: 165, unit: '℃', standardValue: 155, rangeMin: 150, rangeMax: 160, status: 'HIGH',
  reporterId: 7, reporterName: '操作员甲',
};
const page = (rows: unknown[], total = rows.length) => ({ content: rows, page: 1, size: 20, totalElements: total, totalPages: 1 });

function route(summary: unknown, details: unknown) {
  state.get.mockImplementation((url: string) => {
    if (url.endsWith('/param-deviation/summary')) return Promise.resolve(ok(summary));
    if (url.endsWith('/param-deviation/details')) return Promise.resolve(ok(details));
    if (url.endsWith('/product-types/active')) return Promise.resolve(ok([{ id: 'PT-A', name: '牛肉干' }]));
    return Promise.reject(new Error(`unexpected GET ${url}`));
  });
}
function callsTo(suffix: string) {
  return state.get.mock.calls.filter((c) => String(c[0]).endsWith(suffix));
}
function mountPage() {
  return mount(Page, { global: { plugins: [ElementPlus] }, attachTo: document.body });
}

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
});

describe('工艺参数偏离页', () => {
  it('进页即查: 默认最近 30 天; 汇总显示分母/偏离次数/偏离率, 明细显示偏离内容', async () => {
    route(summaryWithDeviations, page([detailRow]));
    const wrapper = mountPage();
    await flushPromises();

    const summaryCall = callsTo('/param-deviation/summary')[0];
    expect(summaryCall[0]).toBe('/F-PD/quality/param-deviation/summary');
    const params = (summaryCall[1] as { params: Record<string, string> }).params;
    expect(params.startDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(params.endDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    const days = (Date.parse(`${params.endDate}T00:00:00`) - Date.parse(`${params.startDate}T00:00:00`)) / 86400000 + 1;
    expect(days).toBe(30);
    // 空筛选项不发给后端
    expect(params).not.toHaveProperty('processName');
    expect(params).not.toHaveProperty('status');

    const summaryText = wrapper.find('[data-testid="pd-summary-table"]').text();
    expect(summaryText).toContain('油温');
    expect(summaryText).toContain('66.7%');
    expect(summaryText).toContain('50%');
    const oilRow = wrapper.findAll('[data-testid="pd-summary-table"] tbody tr')[0].text();
    expect(oilRow).toContain('3');   // 填了该参数的次数 (分母)
    expect(oilRow).toContain('2');   // 偏离次数

    const detailText = wrapper.find('[data-testid="pd-detail-table"]').text();
    expect(detailText).toContain('PPD-A1');
    expect(detailText).toContain('牛肉干');
    expect(detailText).toContain('165 ℃');
    expect(detailText).toContain('150 ~ 160 ℃');
    expect(detailText).toContain('偏高');
    expect(detailText).toContain('操作员甲');
    expect(wrapper.find('[data-testid="pd-window"]').text()).toContain('共 5 条参数记录，其中 3 条偏离');
    wrapper.unmount();
  });

  it('批次号可点: 跳批次详情 /production/batches/:id', async () => {
    route(summaryWithDeviations, page([detailRow]));
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.find('[data-testid="pd-batch-501"]').trigger('click');
    expect(state.push).toHaveBeenCalledWith('/production/batches/77');
    wrapper.unmount();
  });

  it('状态筛选只发给明细, 汇总请求不带 status', async () => {
    route(summaryWithDeviations, page([detailRow]));
    const wrapper = mountPage();
    await flushPromises();
    state.get.mockClear();
    route(summaryWithDeviations, page([detailRow]));

    const vm = wrapper.vm as unknown as { filters: { status: string } };
    vm.filters.status = 'LOW';
    await flushPromises();
    await wrapper.find('[data-testid="pd-search"]').trigger('click');
    await flushPromises();

    const detailParams = (callsTo('/param-deviation/details').at(-1)?.[1] as { params: Record<string, unknown> }).params;
    const summaryParams = (callsTo('/param-deviation/summary').at(-1)?.[1] as { params: Record<string, unknown> }).params;
    expect(detailParams.status).toBe('LOW');
    expect(detailParams.page).toBe(1);
    expect(summaryParams).not.toHaveProperty('status');
    wrapper.unmount();
  });

  it('空状态 A: 有参数记录但没偏离 ⇒ 「这段时间没有偏离」, 不说没配参数', async () => {
    route({ window: window30, totalChecked: 8, totalDeviations: 0, rows: [
      { processName: '炒制', paramKey: 'oilTemp', paramLabel: '油温', unit: '℃', reportCount: 8, deviationCount: 0, highCount: 0, lowCount: 0, deviationRate: 0 },
    ] }, page([]));
    const wrapper = mountPage();
    await flushPromises();
    const detail = wrapper.find('[data-testid="pd-detail-table"]').text();
    expect(detail).toContain('这段时间没有偏离');
    expect(detail).toContain('8 条参数记录都在标准范围内');
    expect(detail).not.toContain('没配参数标准');
    // 汇总仍显示分母 8 和 0% —— 有数据, 只是没偏离
    expect(wrapper.find('[data-testid="pd-summary-table"]').text()).toContain('0%');
    wrapper.unmount();
  });

  it('空状态 B: 分母为 0(没有任何参数记录) ⇒ 说明「工序没配参数 / 没填参数所以没有数据」, 不说没有偏离', async () => {
    route({ window: window30, totalChecked: 0, totalDeviations: 0, rows: [] }, page([]));
    const wrapper = mountPage();
    await flushPromises();
    const detail = wrapper.find('[data-testid="pd-detail-table"]').text();
    expect(detail).toContain('工序没配参数标准或报工没填参数，所以没有数据');
    expect(detail).not.toContain('这段时间没有偏离');
    const summary = wrapper.find('[data-testid="pd-summary-table"]').text();
    expect(summary).toContain('没有任何工艺参数数据');
    expect(summary).toContain('上下限');
    wrapper.unmount();
  });

  it('加载失败: 报错、清掉旧数据、不显示「没有偏离」', async () => {
    state.get.mockImplementation((url: string) => {
      if (url.endsWith('/product-types/active')) return Promise.resolve(ok([]));
      return Promise.reject(new Error('boom'));
    });
    const wrapper = mountPage();
    await flushPromises();
    expect(state.toast).toHaveBeenCalled();
    const text = wrapper.text();
    expect(text).toContain('加载失败');
    expect(text).not.toContain('这段时间没有偏离');
    expect(text).not.toContain('所以没有数据');
    wrapper.unmount();
  });

  it('时间范围超过 92 天: 前端先提示且不发请求', async () => {
    route(summaryWithDeviations, page([detailRow]));
    const wrapper = mountPage();
    await flushPromises();
    state.get.mockClear();
    const vm = wrapper.vm as unknown as { filters: { range: [string, string] } };
    vm.filters.range = ['2026-06-01', '2026-11-04'];
    await flushPromises();
    expect(wrapper.find('[data-testid="pd-range-error"]').text()).toContain('最多 92 天');
    await wrapper.find('[data-testid="pd-search"]').trigger('click');
    await flushPromises();
    expect(callsTo('/param-deviation/summary')).toHaveLength(0);
    wrapper.unmount();
  });
});
