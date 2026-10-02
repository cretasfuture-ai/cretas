/**
 * 销售报表 4 张经营分析卡片 (业务员 / 区域 / 增长率 / 毛利率)。
 *
 * 守三件事:
 *   1. 「算不出来」(null) 显示「—」, 合法的 0 照印 0 —— 两态各一条断言, 少一条另一条就可能是恒真式;
 *   2. 毛利表被 403 拦下时, 用户看到的是**权限说明**, 不是「请检查网络」;
 *   3. 总览页上标「可用」的卡片, 点了要能跳到一条**真实注册过**的路由。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const pythonFetchMock = vi.fn();
const routerPush = vi.fn();

vi.mock('@/api/smartbi/common', () => ({
  pythonFetch: (...a: unknown[]) => pythonFetchMock(...a),
  getFactoryId: () => 'F006',
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: routerPush }) }));
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn().mockRejectedValue('cancel') } }));

import { money, pct, signedPct, reportErrorText } from '../reportFormat';
import GrossMargin from '../gross-margin.vue';
import Dashboard from '../dashboard.vue';

const stubs = {
  'el-card': { template: '<div class="el-card"><slot /></div>' },
  'el-alert': {
    props: ['title', 'type', 'description'],
    template: '<div class="el-alert" :data-type="type">{{ title }} {{ description }}<slot /></div>',
  },
  'el-button': { template: '<button><slot /></button>' },
  'el-radio-group': { template: '<div><slot /></div>' },
  'el-radio-button': { template: '<span><slot /></span>' },
  'el-date-picker': { template: '<input />' },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'el-tooltip': { template: '<span><slot /></span>' },
  // 表格内容是作用域插槽, 本 spec 不断言表格行, 不渲染
  'el-table': { template: '<div class="el-table" />' },
  'el-table-column': { template: '<div />' },
};

describe('reportFormat: null 与 0 分得开', () => {
  it('null 显示 —', () => {
    expect(pct(null)).toBe('—');
    expect(signedPct(null)).toBe('—');
    expect(money(null)).toBe('—');
  });

  it('0 是合法读数, 照印', () => {
    expect(pct(0)).toBe('0.0%');
    expect(signedPct(0)).toBe('0.0%');
    expect(money(0)).toBe('¥ 0.00');
  });

  it('增长率带正负号', () => {
    expect(signedPct(0.123)).toBe('+12.3%');
    expect(signedPct(-0.5)).toBe('-50.0%');
  });

  it('按状态码给出可操作说明', () => {
    expect(reportErrorText(new Error('Python service error: 403 Forbidden'), '需要价格查看权限')).toBe('需要价格查看权限');
    expect(reportErrorText(new Error('Python service error: 400 Bad Request'))).toContain('日期范围');
    expect(reportErrorText(new Error('Python service error: 500 Internal Server Error'))).toContain('加载失败');
  });
});

describe('销售毛利率页', () => {
  beforeEach(() => {
    pythonFetchMock.mockReset();
  });

  it('403 时显示权限说明, 不显示 KPI', async () => {
    pythonFetchMock.mockRejectedValue(new Error('Python service error: 403 Forbidden'));
    const wrapper = mount(GrossMargin, { global: { stubs } });
    await flushPromises();

    const alert = wrapper.find('.el-alert[data-type="error"]');
    expect(alert.exists()).toBe(true);
    expect(alert.text()).toContain('价格查看权限');
    expect(alert.text()).not.toContain('网络');
    expect(wrapper.findAll('.el-card')).toHaveLength(0);
  });

  it('没有成本数据时毛利显示 —, 并提示成本覆盖率', async () => {
    pythonFetchMock.mockResolvedValue({
      success: true,
      message: 'ok',
      data: {
        startDate: '2026-09-01', endDate: '2026-09-30', order: 'desc', rank: [],
        summary: {
          productCount: 2, costedProductCount: 0, revenue: 500, costedRevenue: 0,
          cost: null, grossProfit: null, marginRate: null, costCoverage: 0,
        },
      },
    });
    const wrapper = mount(GrossMargin, { global: { stubs } });
    await flushPromises();

    const kpi = Object.fromEntries(
      wrapper.findAll('.el-card').map(c => [c.find('.label').text(), c.find('.value').text()]),
    );
    expect(kpi['折后未税收入']).toBe('¥ 500.00');
    expect(kpi['成本']).toBe('—');
    expect(kpi['毛利']).toBe('—');
    expect(kpi['综合毛利率']).toBe('—');
    expect(wrapper.find('.el-alert[data-type="warning"]').text()).toContain('0.0% 的销售收入有成本数据');
  });

  it('查询参数带上排序方向', async () => {
    pythonFetchMock.mockResolvedValue({ success: true, message: 'ok', data: null });
    mount(GrossMargin, { global: { stubs } });
    await flushPromises();
    const url = String(pythonFetchMock.mock.calls[0][0]);
    expect(url).toContain('/api/smartbi/F006/sales-preset/gross-margin?');
    expect(url).toContain('order=desc');
  });
});

describe('销售报表总览: 可用卡片都能跳到真实路由', () => {
  const routerSource = readFileSync(resolve(__dirname, '../../../../router/index.ts'), 'utf-8');

  it.each([
    ['业绩 by 业务员', '/sales/reports/salesperson-performance'],
    ['区域销售对比', '/sales/reports/regional-comparison'],
    ['月度增长率', '/sales/reports/growth-rate'],
    ['销售毛利率 by 产品', '/sales/reports/gross-margin'],
  ])('%s → %s', async (title, path) => {
    routerPush.mockReset();
    const wrapper = mount(Dashboard, { global: { stubs } });
    const card = wrapper.findAll('.el-card').find(c => c.find('.title').text() === title);
    expect(card, `总览页上找不到卡片「${title}」`).toBeDefined();
    expect(card!.find('.el-tag').text()).toBe('可用');

    await card!.trigger('click');
    expect(routerPush).toHaveBeenCalledWith(path);
    // 路由表里真的注册了这条子路由 (sales 下的相对路径)
    expect(routerSource).toContain(`path: '${path.replace('/sales/', '')}'`);
  });

  it('副标题的数量与卡片状态一致', () => {
    const wrapper = mount(Dashboard, { global: { stubs } });
    const cards = wrapper.findAll('.el-card');
    const active = cards.filter(c => c.find('.el-tag').text() === '可用').length;
    expect(wrapper.find('.subtitle').text()).toBe(
      `${cards.length} 张预置销售报表 — ${active} 张可用, ${cards.length - active} 张尚未开放`,
    );
    expect(active).toBe(9);
  });
});
