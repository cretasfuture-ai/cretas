/**
 * SKU 毛利率页: 读 report_sku_gross_margin 的真实返回位置, 两种口径分开显示。
 *
 * 守四件事 (每件事都有一条能让它红的对照):
 *   1. items 在 IntentExecuteResponse.resultData.data.items —— 页面读的就是这个位置;
 *   2. 算不出来 (null) 显示「—」+ 原因, 合法的 0 照印 0.0%;
 *   3. KPI 只统计有实际销售毛利率的 SKU, null 不当 0 拉低平均;
 *   4. 两种口径各自带标签 (实际销售毛利 / 目录价理论毛利), 成本/售价列读 realized.* / catalog.*。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, h, inject, provide, computed, type ComputedRef } from 'vue';

const postMock = vi.fn();
const routeMock = vi.hoisted(() => ({ query: {} as Record<string, unknown> }));
vi.mock('vue-router', () => ({ useRoute: () => routeMock }));
beforeEach(() => { routeMock.query = {}; });

// 请求被拒的模拟: 桩返回 { __reject: err }, 这里把它变成页面看到的 rejected promise
// (vitest 4 把桩自己抛出 / 返回的 rejected promise 记成未处理拒绝, 会让用例无端失败)
vi.mock('@/api/request', () => ({
  post: async (...a: unknown[]) => {
    const r = postMock(...a) as { __reject?: Error } | undefined;
    if (r && r.__reject) throw r.__reject;
    return r;
  },
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: true }) }));
vi.mock('@/utils/echarts', () => ({
  default: {
    init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), isDisposed: () => false }),
  },
}));
vi.mock('@/composables/useChartInsight', async () => {
  const { ref } = await import('vue');
  return { useChartInsight: () => ({ insight: ref(null), loading: ref(false) }) };
});
vi.mock('@/views/smart-bi/components/ChartInsight.vue', () => ({
  default: { name: 'ChartInsight', template: '<div class="chart-insight" />' },
}));

import SkuMargin from '../index.vue';
import { readSkuMarginResponse, fmtPct, fmtMoney } from '../skuMarginData';

// ---- 能渲染作用域插槽的表格桩 ----
const ElTable = defineComponent({
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide('rows', computed(() => props.data));
    return () => h('div', { class: 'el-table' }, slots.default?.());
  },
});
const ElTableColumn = defineComponent({
  props: { prop: { type: String, default: '' }, label: { type: String, default: '' } },
  setup(props, { slots }) {
    const rows = inject<ComputedRef<Record<string, unknown>[]>>('rows');
    return () => h(
      'div',
      { class: 'col', 'data-label': props.label },
      (rows?.value ?? []).map((row, i) =>
        h('div', { class: 'cell', 'data-row': i },
          slots.default ? slots.default({ row }) : String(row[props.prop] ?? '')),
      ),
    );
  },
});

const stubs = {
  ShipmentSkuMargin: { template: '<div class="shipment-margin-entry" />' },
  'el-collapse': { template: '<div><slot /></div>' },
  'el-collapse-item': { template: '<div><slot /></div>' },
  'el-card': { template: '<div class="el-card"><slot name="header" /><slot /></div>' },
  'el-alert': {
    props: ['title', 'type'],
    template: '<div class="el-alert" :data-type="type">{{ title }}<slot /></div>',
  },
  'el-button': { template: '<button><slot /></button>' },
  'el-date-picker': { template: '<input />' },
  'el-empty': { props: ['description'], template: '<div class="el-empty">{{ description }}</div>' },
  'el-pagination': { template: '<div class="el-pagination" />' },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'el-table': ElTable,
  'el-table-column': ElTableColumn,
};
const directives = { loading: {} };

// ---- 后端真实返回形状: ApiResponse.data = IntentExecuteResponse, 工具数据在 resultData.data ----
const itemA = {
  productTypeId: 'A', productName: '牛肉酱', quantity: 10, marginRate: 36.84, marginRateBasis: 'realized',
  realized: {
    status: 'OK', reason: null, note: '其中 1 条明细没有售价，未计入', missingPriceLineCount: 1,
    totalRevenue: 190, revenue: 190, cost: 120, grossProfit: 70, marginRate: 36.84, costCoverage: 100,
  },
  catalog: { status: 'OK', reason: null, price: 100, unitCost: 60, marginRate: 40, costBatchNumber: 'B-A' },
};
const itemNoSales = {
  productTypeId: 'B', productName: '只有目录价', quantity: null, marginRate: null, marginRateBasis: 'realized',
  realized: {
    status: 'NO_SALES', reason: '所选期间没有销售记录', note: null, missingPriceLineCount: null,
    totalRevenue: null, revenue: null, cost: null, grossProfit: null, marginRate: null, costCoverage: null,
  },
  catalog: {
    status: 'NO_BATCH_COST', reason: '没有已算出单位成本的批次', price: 80, unitCost: null, marginRate: null,
    costBatchNumber: null,
  },
};
const itemZero = {
  productTypeId: 'C', productName: '保本产品', quantity: 5, marginRate: 0, marginRateBasis: 'realized',
  realized: {
    status: 'OK', reason: null, note: null, missingPriceLineCount: 0,
    totalRevenue: 100, revenue: 100, cost: 100, grossProfit: 0, marginRate: 0, costCoverage: 100,
  },
  catalog: { status: 'NO_PRICE', reason: '没有目录价', price: null, unitCost: 50, marginRate: null, costBatchNumber: 'B-C' },
};

function okResponse(items: unknown[]) {
  return {
    success: true,
    message: '操作成功',
    data: {
      status: 'SUCCESS',
      message: 'SKU 毛利率共 3 个产品',
      resultData: {
        message: 'SKU 毛利率共 3 个产品',
        data: {
          items,
          totalProducts: items.length,
          truncated: false,
          realizedPeriod: { startDate: '2026-09-07', endDate: '2026-10-06', defaultPeriod: true },
          rateUnit: 'percent',
        },
      },
    },
  };
}

async function mountPage(response: unknown) {
  postMock.mockReset();
  postMock.mockResolvedValue(response);
  const wrapper = mount(SkuMargin, { global: { stubs, directives } });
  await flushPromises();
  return wrapper;
}

function cell(wrapper: ReturnType<typeof mount>, label: string, row: number): string {
  const col = wrapper.findAll('.col').find((c) => c.attributes('data-label') === label);
  expect(col, `找不到列「${label}」`).toBeDefined();
  return col!.findAll('.cell')[row].text();
}

// 表格按「实际销售毛利率」降序: 行 0 = 牛肉酱 36.84, 行 1 = 保本产品 0, 行 2 = 只有目录价 (null 排最后)
const ROW_A = 0;
const ROW_ZERO = 1;
const ROW_NULL = 2;

describe('读取位置: items 在 resultData.data.items', () => {
  it('页面按真实位置读到 3 行, 并向 ai-intents/execute 发请求', async () => {
    const wrapper = await mountPage(okResponse([itemA, itemNoSales, itemZero]));
    expect(postMock).toHaveBeenCalledTimes(1);
    expect(postMock.mock.calls[0][0]).toBe('/F006/ai-intents/execute');
    expect(postMock.mock.calls[0][1]).toMatchObject({ intentCode: 'SKU_GROSS_MARGIN' });
    expect(wrapper.findAll('.col')[0].findAll('.cell')).toHaveLength(3);
    expect(wrapper.text()).toContain('共 3 个SKU');
  });

  it('旧页面读的位置 (res.data.data / res.data.result) 上有 items 也不算数', () => {
    const wrongPlace = {
      status: 'SUCCESS',
      data: { items: [itemA] },
      result: { items: [itemA] },
    };
    expect(readSkuMarginResponse(wrongPlace).rows).toHaveLength(0);
    expect(readSkuMarginResponse(okResponse([itemA]).data).rows).toHaveLength(1);
  });

  it('后端没给 resultData (缓存路径等) → 说明没有明细, 不是 0 行数据', () => {
    const r = readSkuMarginResponse({ status: 'SUCCESS', message: '(缓存结果) ...', resultData: null });
    expect(r.rows).toHaveLength(0);
    expect(r.emptyMessage).toContain('缓存结果');
  });
});

describe('两种口径分开显示, 列读 realized.* / catalog.*', () => {
  it('明确订单成本快照与已过账财务口径，提供既有报表入口', async () => {
    const wrapper = await mountPage(okResponse([itemA]));
    expect(wrapper.text()).toContain('订单成本快照');
    expect(wrapper.text()).toContain('出货后的成本更正');
    expect(wrapper.get('a[href="/finance/three-statements"]').text()).toContain('已过账财务报表');
  });
  it('有两段定义文案, 并回显后端实际回答的统计期间', async () => {
    const wrapper = await mountPage(okResponse([itemA, itemNoSales, itemZero]));
    const defs = wrapper.find('.definitions').text();
    expect(defs).toContain('订单毛利（成本快照）');
    expect(defs).toContain('目录价理论毛利');
    expect(defs).toContain('统计期间 2026-09-07 至 2026-10-06');
    expect(defs).toContain('默认最近 30 天');
  });

  it('牛肉酱: 实际口径与目录价口径是两组不同的数', async () => {
    const wrapper = await mountPage(okResponse([itemA, itemNoSales, itemZero]));
    expect(cell(wrapper, '订单收入', ROW_A)).toBe('¥190.00');
    expect(cell(wrapper, '订单成本快照', ROW_A)).toBe('¥120.00');
    expect(cell(wrapper, '订单毛利率', ROW_A)).toContain('36.8%');
    expect(cell(wrapper, '目录·售价', ROW_A)).toBe('¥100.00');
    expect(cell(wrapper, '目录·单位成本', ROW_A)).toBe('¥60.00');
    expect(cell(wrapper, '目录价理论毛利率', ROW_A)).toContain('40.0%');
    expect(cell(wrapper, '订单毛利率', ROW_A)).not.toContain('40.0%');
    // 后端的提示 (有明细没售价) 原样展示
    expect(cell(wrapper, '订单毛利率', ROW_A)).toContain('其中 1 条明细没有售价，未计入');
  });
});

describe('null → 「—」+ 原因; 0 是合法读数', () => {
  it('没有销售: 实际口径整组 —, 带原因; 目录价口径仍然显示它自己的真数', async () => {
    const wrapper = await mountPage(okResponse([itemA, itemNoSales, itemZero]));
    const margin = cell(wrapper, '订单毛利率', ROW_NULL);
    expect(margin).toContain('—');
    expect(margin).toContain('所选期间没有销售记录');
    expect(margin).not.toContain('0.0%');
    expect(cell(wrapper, '订单收入', ROW_NULL)).toBe('—');
    expect(cell(wrapper, '订单成本快照', ROW_NULL)).toBe('—');
    expect(cell(wrapper, '订单数量', ROW_NULL)).toBe('—');
    // 目录价口径: 价是真的 ¥80, 单位成本和毛利率缺 → — + 原因
    expect(cell(wrapper, '目录·售价', ROW_NULL)).toBe('¥80.00');
    expect(cell(wrapper, '目录·单位成本', ROW_NULL)).toBe('—');
    const catalogMargin = cell(wrapper, '目录价理论毛利率', ROW_NULL);
    expect(catalogMargin).toContain('—');
    expect(catalogMargin).toContain('没有已算出单位成本的批次');
    expect(catalogMargin).not.toContain('0.0%');
  });

  it('对照: 真实的 0% 毛利率印成 0.0%, 不被当成「算不出」', async () => {
    const wrapper = await mountPage(okResponse([itemA, itemNoSales, itemZero]));
    const margin = cell(wrapper, '订单毛利率', ROW_ZERO);
    expect(margin).toContain('0.0%');
    expect(margin).not.toContain('—');
    // 目录价没有 → — + 原因 (同一行里两个口径各自独立)
    expect(cell(wrapper, '目录价理论毛利率', ROW_ZERO)).toContain('没有目录价');
  });

  it('格式化函数: null → —, 0 → 0.0% / ¥0.00', () => {
    expect(fmtPct(null)).toBe('—');
    expect(fmtMoney(null)).toBe('—');
    expect(fmtPct(0)).toBe('0.0%');
    expect(fmtMoney(0)).toBe('¥0.00');
  });
});

describe('KPI 只统计有实际销售毛利率的 SKU', () => {
  it('(36.84 + 0) / 2 = 18.4%, 那个 null 不拉低平均', async () => {
    const wrapper = await mountPage(okResponse([itemA, itemNoSales, itemZero]));
    expect(wrapper.find('.kpi-avg').text()).toBe('18.4%');
    expect(wrapper.text()).toContain('2 / 3 个SKU有订单毛利率');
    expect(wrapper.text()).toContain('产品毛利率简单平均');
    expect(wrapper.find('.chart-insight').exists()).toBe(false);
    expect(wrapper.find('.kpi-best').text()).toBe('36.8%');
    expect(wrapper.find('.kpi-worst').text()).toBe('0.0%');
  });

  it('全部都算不出时, KPI 是 —, 不是 0.0%', async () => {
    const wrapper = await mountPage(okResponse([itemNoSales]));
    expect(wrapper.find('.kpi-avg').text()).toBe('—');
    expect(wrapper.find('.kpi-best').text()).toBe('—');
    expect(wrapper.find('.kpi-worst').text()).toBe('—');
  });
});

describe('失败 / 空结果: 如实说原因, 不换别的数据源顶替', () => {
  beforeEach(() => postMock.mockReset());

  it('NEED_CLARIFICATION → 显示后端文案, 没有表格', async () => {
    const wrapper = await mountPage({
      success: true, message: 'x',
      data: { status: 'NEED_CLARIFICATION', message: '您是想问哪一类产品的毛利率?' },
    });
    expect(wrapper.find('.empty-notice').text()).toContain('您是想问哪一类产品的毛利率?');
    expect(wrapper.find('.el-table').exists()).toBe(false);
    expect(postMock).toHaveBeenCalledTimes(1);   // 没有去别的接口兜底
  });

  it('请求被拒 (权限 / 网络) → 页面上留一份原因', async () => {
    postMock.mockReset();
    // 真实拦截器对 success=false 的响应会 reject 一个 ApiError
    vi.spyOn(console, 'error').mockImplementation(() => {});
    postMock.mockReturnValue({ __reject: new Error('您当前的角色没有权限执行「SKU毛利率查询」操作') });
    const wrapper = mount(SkuMargin, { global: { stubs, directives } });
    await flushPromises();
    const notice = wrapper.find('.empty-notice');
    expect(notice.attributes('data-type')).toBe('error');
    expect(notice.text()).toContain('没有权限');
    expect(wrapper.find('.el-table').exists()).toBe(false);
  });

  it('工具说没有产品 → 显示工具文案', async () => {
    const wrapper = await mountPage({
      success: true, message: 'ok',
      data: {
        status: 'SUCCESS', message: 'm',
        resultData: { message: '没有找到名称含「xx」的在用产品', data: { items: [], totalProducts: 0, truncated: false } },
      },
    });
    expect(wrapper.find('.empty-notice').text()).toContain('没有找到名称含「xx」的在用产品');
  });
});

describe('日期筛选走 request.context', () => {
  it('定位链接的期间进入日期筛选和已有订单查询', async () => {
    routeMock.query = { periodStart: '2026-10-01', periodEnd: '2026-10-31' };
    const wrapper = await mountPage(okResponse([itemA]));
    expect(postMock.mock.calls[0][1].context).toEqual({ startDate: '2026-10-01', endDate: '2026-10-31' });
    const vm = wrapper.vm as unknown as { dateRange: [string, string] | null };
    expect(vm.dateRange).toEqual(['2026-10-01', '2026-10-31']);
    wrapper.unmount();
  });
  it('选了日期 → 第二次请求带 context.startDate / endDate', async () => {
    const wrapper = await mountPage(okResponse([itemA]));
    expect(postMock.mock.calls[0][1]).toEqual({ intentCode: 'SKU_GROSS_MARGIN', userInput: '查询SKU毛利率排名' });
    const vm = wrapper.vm as unknown as {
      dateRange: [string, string] | null;
      handleSearch: () => Promise<void>;
      handleRefresh: () => Promise<void>;
    };
    vm.dateRange = ['2026-08-01', '2026-08-31'];
    await vm.handleSearch();
    await flushPromises();
    expect(postMock).toHaveBeenCalledTimes(2);
    expect(postMock.mock.calls[1][1]).toEqual({
      intentCode: 'SKU_GROSS_MARGIN',
      userInput: '查询SKU毛利率排名',
      context: { startDate: '2026-08-01', endDate: '2026-08-31' },
    });
    await vm.handleRefresh();
    await flushPromises();
    expect(postMock.mock.calls[2][1]).toEqual(postMock.mock.calls[1][1]);
    expect(vm.dateRange).toEqual(['2026-08-01', '2026-08-31']);
  });
});
