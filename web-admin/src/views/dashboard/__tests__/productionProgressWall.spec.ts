/**
 * B8 生产进度数字大屏 —— 「空态」与「一屏 0」必须长得不一样。
 *
 * 🔴 这道闸守的是**一个具体的说谎方式**, 不是「页面能渲染」:
 *   当天一条生产计划都没有时, 顶部那排大字如果照旧印
 *   `总计划 0 / 总工单 0 / 已完成 0 / 进行中 0 / 未开始 0 / 整体进度 0%`,
 *   挂在车间墙上的效果就是「今天产线是停的」——
 *   而真相是「今天没有排计划」, 两件事对车间主管的含义完全相反。
 *
 * ▎「没有读数」和「读数是 0」在界面上必须分得开。
 *   0 是一个**声称**, 而没有计划时我们没有任何东西可声称。
 *
 * 同一条判据也管交货预警那一格: 那格的数来自**另一个端点**,
 * 它挂了的时候必须显示「读不到」, ⛔ 不能显示 0 ——
 * 「今天没有超期计划」是车间最想听到的一句话, 不许由一个失败的请求说出来。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';

const apiGet = vi.fn();

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => apiGet(...args) }));

import ProductionProgressWall from '../production-progress.vue';

const PROGRESS_URL = '/F006/dashboard/production-progress';
const WARNINGS_URL = '/F006/production-plans/delivery-warnings';

/** 一天里一条计划都没排 —— 后端如实返回全 0 的 summary 和空 plans。 */
const EMPTY_DAY = {
  date: '2026-08-26',
  summary: {
    totalPlans: 0,
    totalWorkOrders: 0,
    completedWorkOrders: 0,
    inProgressWorkOrders: 0,
    pendingWorkOrders: 0,
    overallProgressPct: 0,
    totalPlannedQuantity: 0,
    totalReportedQuantity: 0,
  },
  plans: [],
};

/** 有计划的一天 —— summary 是真实读数, 必须照常显示。 */
const BUSY_DAY = {
  date: '2026-08-26',
  summary: {
    totalPlans: 2,
    totalWorkOrders: 5,
    completedWorkOrders: 2,
    inProgressWorkOrders: 1,
    pendingWorkOrders: 2,
    overallProgressPct: 40,
    totalPlannedQuantity: 800,
    totalReportedQuantity: 320,
  },
  plans: [
    {
      planId: 'PP-1', planNumber: 'PP-2026-0001', productName: '卤鸭脖', customerName: '六扇门',
      plannedQuantity: 500, reportedQuantity: 320, progressPct: 64, status: 'IN_PROGRESS',
      processes: [{ processName: '腌制', plannedQty: 500, reportedQty: 320, pct: 64, status: 'IN_PROGRESS' }],
    },
    {
      planId: 'PP-2', planNumber: 'PP-2026-0002', productName: '卤鸡爪', customerName: '六扇门',
      plannedQuantity: 300, reportedQuantity: 0, progressPct: 0, status: 'PENDING',
      processes: [{ processName: '总进度', plannedQty: 300, reportedQty: 0, pct: 0, status: 'PENDING' }],
    },
  ],
};

const WARNINGS = [
  { planId: 'PP-9', planNumber: 'PP-2026-0009', warnLevel: 'OVERDUE', daysUntilDeadline: -3 },
  { planId: 'PP-8', planNumber: 'PP-2026-0008', warnLevel: 'OVERDUE', daysUntilDeadline: -1 },
  { planId: 'PP-7', planNumber: 'PP-2026-0007', warnLevel: 'URGENT', daysUntilDeadline: 1 },
];

/**
 * 按 URL 分派 —— ⛔ 不按调用顺序分派。
 * 顺序分派会在「我改了请求发出的顺序」时静默把两个响应对调,
 * 而两个响应都是合法形状 ⇒ 断言照样绿, 量的却是另一件事。
 */
function routeGet(overrides: { progress?: unknown; warnings?: unknown } = {}) {
  apiGet.mockImplementation((url: string) => {
    if (String(url).includes('/dashboard/production-progress')) {
      const v = 'progress' in overrides ? overrides.progress : EMPTY_DAY;
      if (v instanceof Error) return Promise.reject(v);
      return Promise.resolve({ data: v });
    }
    if (String(url).includes('/delivery-warnings')) {
      const v = 'warnings' in overrides ? overrides.warnings : WARNINGS;
      if (v instanceof Error) return Promise.reject(v);
      return Promise.resolve({ data: v });
    }
    return Promise.reject(new Error(`未预期的请求: ${url}`));
  });
}

async function mountWall() {
  const wrapper = mount(ProductionProgressWall, {
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

describe('B8 大屏 —— 空态 vs 一屏 0', () => {
  beforeEach(() => {
    apiGet.mockReset();
  });

  it('阳性对照: 有计划时 summary 大字照常渲染 —— 否则下面那条「不渲染」是恒真式', async () => {
    routeGet({ progress: BUSY_DAY });
    const wrapper = await mountWall();

    expect(wrapper.text()).toContain('总计划');
    expect(wrapper.findAll('.summary-card').length).toBeGreaterThan(0);
    expect(wrapper.text()).toContain('40%');
    expect(wrapper.text()).not.toContain('当天暂无生产计划');
  });

  it('当天没有生产计划时, ⛔ 不许渲染那一排 0 —— 只出空态', async () => {
    routeGet({ progress: EMPTY_DAY });
    const wrapper = await mountWall();

    expect(wrapper.text()).toContain('当天暂无生产计划');
    expect(
      wrapper.findAll('.summary-card').length,
      '当天零计划却渲染了 summary 大字 ⇒ 墙上是「总计划 0 / 整体进度 0%」, '
        + '车间读成「今天停产」。没有读数时不许印 0。',
    ).toBe(0);
    expect(wrapper.text()).not.toContain('整体进度');
  });
});

describe('B8 大屏 —— 交货预警(延期数)那一格', () => {
  beforeEach(() => {
    apiGet.mockReset();
  });

  it('端点正常: 只数 OVERDUE, 不把 URGENT/WARN 混进来', async () => {
    routeGet({ progress: BUSY_DAY, warnings: WARNINGS });
    const wrapper = await mountWall();

    const overdue = wrapper.find('.summary-card.overdue');
    expect(overdue.exists(), '有计划的一天必须有「已超期」那一格').toBe(true);
    // 3 条里 2 条 OVERDUE, 第 3 条是 URGENT —— 显示 2 而不是 3。
    expect(overdue.find('.summary-value').text()).toBe('2');
  });

  it('🔴 端点挂了必须显示读不到, ⛔ 不许显示 0', async () => {
    routeGet({ progress: BUSY_DAY, warnings: new Error('boom') });
    const wrapper = await mountWall();

    const overdue = wrapper.find('.summary-card.overdue');
    expect(overdue.exists()).toBe(true);
    const shown = overdue.find('.summary-value').text();
    expect(
      shown,
      '交货预警端点失败时显示了 0 ⇒ 墙上写着「已超期 0」, '
        + '而真相是我们根本没问到。⛔ 失败不许被渲染成好消息。',
    ).not.toBe('0');
    expect(shown).toBe('—');
    expect(overdue.text()).toContain('读取失败');
  });

  it('端点正常且真的零超期时, 才允许显示 0 —— 否则上一条是恒真式', async () => {
    routeGet({ progress: BUSY_DAY, warnings: [] });
    const wrapper = await mountWall();

    const overdue = wrapper.find('.summary-card.overdue');
    expect(overdue.find('.summary-value').text()).toBe('0');
    expect(overdue.text()).not.toContain('读取失败');
  });
});
