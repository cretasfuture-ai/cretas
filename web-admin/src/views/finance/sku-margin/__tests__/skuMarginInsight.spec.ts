/**
 * SKU 毛利率页「一图一分析」(问题 40) + 图排序 (问题 50) 的真实页面入口断言:
 *   - 分析来自页面真实 tableData, 换数据结论跟着变;
 *   - 有算不出毛利率的行时分析里如实说, 不当 0;
 *   - 切换排列方式时图 setOption 的柱序真的变 (排序接上了);
 *   - 无价格权限时整块（图+分析）不出现, 也没有 ¥。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, h } from 'vue';

const postMock = vi.fn();
const setOption = vi.hoisted(() => vi.fn());
const perm = vi.hoisted(() => ({ canViewPrice: true }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }) }));
vi.mock('@/api/request', () => ({ post: async (...a: unknown[]) => postMock(...a) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => perm }));
vi.mock('@/utils/echarts', () => ({
  default: { init: () => ({ setOption, resize: vi.fn(), dispose: vi.fn(), isDisposed: () => false }) },
}));

import SkuMargin from '../index.vue';
import { nuxtUiStubs } from '@/__tests__/helpers/nuxtUiStubs';

const stubs = {
  ...nuxtUiStubs,
  ShipmentSkuMargin: { template: '<div />' },
  UTable: defineComponent({ setup: () => () => h('div') }),
  UAlert: { template: '<div />' },
  UIcon: { template: '<i />' },
  USelect: { template: '<div />' },
  UPagination: { template: '<div />' },
  ChartSortControl: defineComponent({
    props: { modelValue: { type: String, default: '' } },
    emits: ['update:modelValue'],
    setup(props, { emit }) {
      return () => h('button', { class: 'sort-stub', onClick: () => emit('update:modelValue', 'valueAsc') }, props.modelValue);
    },
  }),
};

const item = (name: string, rate: number | null) => ({
  productTypeId: name, productName: name, quantity: 1, marginRate: rate, marginRateBasis: 'realized',
  realized: { status: rate === null ? 'NO_SALES' : 'OK', reason: null, note: null, missingPriceLineCount: 0,
    totalRevenue: 1, revenue: 1, cost: 1, grossProfit: 0, marginRate: rate, costCoverage: 100 },
  catalog: { status: 'NO_PRICE', reason: null, price: null, unitCost: null, marginRate: null, costBatchNumber: null },
});
const ok = (items: unknown[]) => ({
  success: true, message: 'ok',
  data: { status: 'SUCCESS', message: 'm', resultData: { message: 'm', data: {
    items, totalProducts: items.length, truncated: false,
    realizedPeriod: { startDate: '2026-10-01', endDate: '2026-10-30', defaultPeriod: true }, rateUnit: 'percent' } } },
});

async function mountPage(items: unknown[]) {
  postMock.mockReset();
  postMock.mockResolvedValue(ok(items));
  const w = mount(SkuMargin, { global: { stubs }, attachTo: document.body });
  await flushPromises();
  await w.get('[data-testid="margin-reference-toggle"]').trigger('click');
  await flushPromises();
  return w;
}

beforeEach(() => {
  setOption.mockReset();
  perm.canViewPrice = true;
  // jsdom 里元素尺寸恒为 0，页面 initChart 会提前返回；给个非零尺寸让图真的画
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', { configurable: true, get: () => 600 });
  Object.defineProperty(HTMLElement.prototype, 'clientHeight', { configurable: true, get: () => 360 });
});

describe('SKU 毛利率 一图一分析', () => {
  it('分析按页面数据现算：首尾、低毛利、算不出的', async () => {
    const w = await mountPage([item('牛肉酱', 36.84), item('猪蹄', 9.2), item('无销售', null)]);
    const t = w.get('[data-testid="margin-chart-insight"]').text();
    expect(t).toContain('从 36.8%（牛肉酱）到 9.2%（猪蹄）');
    expect(t).toContain('另有 1 个算不出毛利率');
    expect(t).toContain('猪蹄 9.2%');
  });
  it('换数据结论跟着变', async () => {
    const w = await mountPage([item('甲', 50), item('乙', 45)]);
    const t = w.get('[data-testid="margin-chart-insight"]').text();
    expect(t).toContain('从 50%（甲）到 45%（乙）');
    expect(t).not.toContain('另有');
  });
  it('排序控件真的改变图的柱序（升序 → 最高的在最底）', async () => {
    const w = await mountPage([item('甲', 50), item('乙', 10), item('丙', 30)]);
    const order = () => (setOption.mock.calls.at(-1)?.[0].yAxis.data as string[]);
    // 默认降序 + reverse: 最高在数组末尾（图最上方）
    expect(order()).toEqual(['乙', '丙', '甲']);
    await w.get('.sort-stub').trigger('click');
    await flushPromises();
    expect(order()).toEqual(['甲', '丙', '乙']);
  });
  it('无价格权限：图与分析整块不出现，也没有 ¥', async () => {
    perm.canViewPrice = false;
    postMock.mockResolvedValue(ok([item('甲', 50)]));
    const w = mount(SkuMargin, { global: { stubs } });
    await flushPromises();
    expect(w.find('[data-testid="margin-chart-insight"]').exists()).toBe(false);
    expect(w.text()).not.toContain('¥');
  });
});
