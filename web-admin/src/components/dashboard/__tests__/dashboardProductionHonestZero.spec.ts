/**
 * 生产经理 Dashboard(production_manager / workshop_supervisor) ——
 * 同一道闸(见 dashboardAdminHonestZero.spec.ts)在这一屏上的第二个实例。
 *
 * 🔴 LIUSHANMEN 实测(2026-09-01, lsm015): 一个 08-29 建的批次连续跑了 3 天仍是
 *    IN_PROGRESS, 「进行中批次」卡照印「0」——因为它读的是
 *    `productionStats.value?.inProgressBatches ?? 0`, 而
 *    `/reports/dashboard/production?period=today` 的响应里**根本没有这个字段**
 *    (production 端点只按「今天创建」筛, 见 ProcessingServiceImpl.getProductionStatistics)。
 *    `undefined ?? 0` 印出一个自信的假 0。
 *
 * ▎ 语义订正, 不是换字段名那么简单: 「进行中」对生产经理的含义是「当前还没结束」,
 *   不是「今天开始的」——不管用哪个 period 限定的字段, 只要按创建时间筛, 语义都是错的。
 *   正确数据源是 `/reports/dashboard/overview` 的 `inProgressBatches`
 *   (=IN_PROGRESS 状态的全量历史计数, 无日期过滤查询), 而且这个请求本来就要发。
 *
 * 三态判据与 dashboardAdminHonestZero.spec.ts 完全一致:
 *   还没问到 / 问了读不到(印 '—' + 「读取失败」) / 真读数(含合法 0, 照印)。
 *
 * ⚠️ DELIBERATELY_NOT_FIXED —— 本组件里另有两处 `?? 0`(完成批次 / 完成率)
 *   刻意不升级到三态, 见 DashboardProduction.vue 顶部注释和 PR 描述。
 *   这份闸对它们只做「锁定当前行为不被意外改变」的最小断言, 不代表认可这个行为。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const apiGet = vi.fn();

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({
    factoryId: 'LIUSHANMEN',
    user: { fullName: '六膳门生产经理', username: 'lsm015' },
    roleMetadata: { displayName: '生产经理' },
    isLiushanmenFactory: true,
  }),
}));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => apiGet(...args) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

const elMessageError = vi.fn();
const elMessageWarning = vi.fn();
vi.mock('element-plus', () => ({
  ElMessage: { error: (...a: unknown[]) => elMessageError(...a), warning: (...a: unknown[]) => elMessageWarning(...a) },
}));

import DashboardProduction from '../DashboardProduction.vue';

/**
 * LIUSHANMEN 2026-09-01 实测形状: overview 有一个全量历史进行中批次(08-29 建、
 * 至今未结束), production(period=today) 完全没有今天创建的批次
 * ⇒ 与生产真实响应体一致的 "complete response" (defect① 报告里贴出的那份)。
 */
const LIUSHANMEN_OVERVIEW = {
  todayOutput: 0,
  completedBatches: 0,      // 今日完成 0(真实, 08-29/08-30 完成的不算"今天")
  completionRate: 0,        // 后端从未赋值过这个 key —— 本闸不修它, 只锁定现状
  inProgressBatches: 1,     // 全量历史 IN_PROGRESS 计数, 真值(defect① 的核心断言)
};
/** production 端点(period=today)的真实响应形状: 没有 inProgressBatches / pendingBatches。 */
const LIUSHANMEN_PRODUCTION_TODAY = {
  productTypeStats: [], totalOutput: 0, averageEfficiency: 0,
  completedBatches: 0, batchStatusDistribution: [], totalBatches: 0,
};

/** 端点成功且**真的**是 0 —— 合法读数, 必须照印。 */
const ZERO_OVERVIEW = { todayOutput: 0, completedBatches: 0, completionRate: 0, inProgressBatches: 0 };
const ZERO_PRODUCTION = { ...LIUSHANMEN_PRODUCTION_TODAY };
/** 端点成功且有真实数据(阳性对照)。 */
const BUSY_OVERVIEW = { todayOutput: 115, completedBatches: 2, completionRate: 76, inProgressBatches: 3 };
const BUSY_PRODUCTION = { ...LIUSHANMEN_PRODUCTION_TODAY, completedBatches: 2, totalOutput: 115 };

function routeGet(o: { overview?: unknown; production?: unknown } = {}) {
  apiGet.mockImplementation((url: string) => {
    const u = String(url);
    const pick = (v: unknown, fallback: unknown) => {
      const chosen = v === undefined ? fallback : v;
      if (chosen instanceof Error) return Promise.reject(chosen);
      return Promise.resolve(chosen);
    };
    if (u.includes('/reports/dashboard/overview')) return pick(o.overview, { success: true, data: BUSY_OVERVIEW });
    if (u.includes('/reports/dashboard/production')) return pick(o.production, { success: true, data: BUSY_PRODUCTION });
    return Promise.reject(new Error(`未预期的请求: ${u}`));
  });
}

const stubs = {
  'el-card': { template: '<div class="el-card"><slot name="header" /><slot /></div>' },
  'el-row': { template: '<div><slot /></div>' },
  'el-col': { template: '<div><slot /></div>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-progress': { template: '<div class="el-progress" />' },
};

async function mountDash() {
  const wrapper = mount(DashboardProduction, { global: { stubs } });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

function statValue(wrapper: ReturnType<typeof mount>, title: string): string {
  const card = wrapper.find(`[data-stat="${title}"]`);
  expect(card.exists(), `找不到卡片「${title}」`).toBe(true);
  return card.find('.stat-value, .count').text().trim();
}
function statCard(wrapper: ReturnType<typeof mount>, title: string) {
  return wrapper.find(`[data-stat="${title}"]`);
}

beforeEach(() => {
  apiGet.mockReset();
  elMessageError.mockReset();
  elMessageWarning.mockReset();
});

describe('生产经理 Dashboard —— 「进行中批次」不许长成「读数是 0」(Defect①/②)', () => {
  it('🔴 LIUSHANMEN 实测形状: production 端点没有 inProgressBatches 字段, ' +
    'KPI 卡与批次状态分布必须用 overview 的全量历史值, 不许印假 0', async () => {
    routeGet({
      overview: { success: true, data: LIUSHANMEN_OVERVIEW },
      production: { success: true, data: LIUSHANMEN_PRODUCTION_TODAY },
    });
    const wrapper = await mountDash();

    expect(
      statValue(wrapper, '进行中批次'),
      'productionStats 里没有 inProgressBatches, 但 overview 里有真实全量值(1)——' +
        '这张卡不许因为 productionStats 缺字段就印 0',
    ).toContain('1');
    expect(
      statValue(wrapper, '进行中'),
      '批次状态分布的「进行中」与 KPI 卡同源同修, 必须显示同一个真值',
    ).toContain('1');
    expect(statCard(wrapper, '进行中批次').text()).not.toContain('读取失败');
  });

  it('阳性对照: 两个端点都正常时, 「进行中批次」照常渲染 overview 的真实读数', async () => {
    routeGet();
    const wrapper = await mountDash();

    expect(statValue(wrapper, '进行中批次')).toContain('3');
    expect(statValue(wrapper, '进行中')).toContain('3');
    expect(statValue(wrapper, '今日产量')).toContain('115');
    expect(statValue(wrapper, '今日产量')).toContain('kg');
    expect(statCard(wrapper, '进行中批次').text()).not.toContain('读取失败');
  });

  it('🔴 overview 端点挂了: 「进行中批次」⛔ 不许印 0, 必须是 — + 读取失败', async () => {
    const boom = new Error('500 Internal Server Error');
    routeGet({ overview: boom });
    const wrapper = await mountDash();

    expect(
      statValue(wrapper, '进行中批次'),
      'overview 读不到时印出了 0 —— 用户读到的是「没有进行中批次」, ' +
        '真相是我们一条都没查到。⛔ 失败不许被渲染成读数。',
    ).not.toBe('0');
    expect(statValue(wrapper, '进行中批次')).toBe('—');
    expect(statCard(wrapper, '进行中批次').text()).toContain('读取失败');

    expect(statValue(wrapper, '进行中')).toBe('—');
    expect(statCard(wrapper, '进行中').text()).toContain('读取失败');
    // 只有 overview 挂了、production 好着 ⇒ 部分失败, 走 warning 分支(不是 error)。
    expect(elMessageWarning).toHaveBeenCalled();
  });

  it('🔴 端点成功且真的是 0 时, 「进行中批次」必须照印 0 —— 否则上一条是恒真式', async () => {
    routeGet({
      overview: { success: true, data: ZERO_OVERVIEW },
      production: { success: true, data: ZERO_PRODUCTION },
    });
    const wrapper = await mountDash();

    expect(
      statValue(wrapper, '进行中批次'),
      '真读数是 0 却印成了 —, 说明我们只是把一种说谎换成了另一种(永远不敢报 0)。',
    ).toContain('0'); // KPI 卡带单位「个」, 取的是 "0 个"
    expect(statValue(wrapper, '进行中批次')).not.toBe('—');
    expect(statCard(wrapper, '进行中批次').text()).not.toContain('读取失败');
    expect(statValue(wrapper, '进行中')).toBe('0'); // 批次状态分布无单位, 纯数字
  });

  it('「待开始」: production 端点成功但真实响应体没有 pendingBatches 字段 —— ' +
    '印 — , ⛔ 不印 0, 且不许说「读取失败」(请求本身没有失败)', async () => {
    routeGet({ production: { success: true, data: LIUSHANMEN_PRODUCTION_TODAY } });
    const wrapper = await mountDash();

    expect(statValue(wrapper, '待开始')).not.toBe('0');
    expect(statValue(wrapper, '待开始')).toBe('—');
    expect(
      statCard(wrapper, '待开始').text(),
      'production 请求是成功的(200), 只是响应体里没有这个字段 —— ' +
        '不该被贴上「读取失败」这个不实的标签',
    ).not.toContain('读取失败');
  });

  it('「待开始」: production 端点整个挂了时, 必须显式说明读取失败', async () => {
    routeGet({ production: new Error('500') });
    const wrapper = await mountDash();

    expect(statValue(wrapper, '待开始')).toBe('—');
    expect(statCard(wrapper, '待开始').text()).toContain('读取失败');
  });

  it('部分失败: overview 挂了, 「今日产量」「进行中批次」变 —, ' +
    'production 好着的话「待开始」用真读数', async () => {
    routeGet({
      overview: new Error('overview 500'),
      production: { success: true, data: BUSY_PRODUCTION },
    });
    const wrapper = await mountDash();

    expect(statValue(wrapper, '今日产量')).toBe('—');
    expect(statValue(wrapper, '进行中批次')).toBe('—');
    // production 好着但真实响应体没有 pendingBatches —— 印 —, 不是失败, 是字段缺
    expect(statValue(wrapper, '待开始')).toBe('—');
    expect(statCard(wrapper, '待开始').text()).not.toContain('读取失败');
  });
});

describe('DELIBERATELY_NOT_FIXED —— 「完成批次」「完成率」本 PR 刻意不升级三态', () => {
  it('两个端点都挂了时, 「完成批次」「完成率」现状是照印 0(不是 —), ' +
    '这是已知、已在 PR 里报告的缺口, 不是本闸的断言目标 —— 这条测试只锁定现状不被意外改变', async () => {
    const boom = new Error('500');
    routeGet({ overview: boom, production: boom });
    const wrapper = await mountDash();

    // 与「进行中批次」形成对照: 同样是 overview 挂了, 「完成批次」「完成率」没有
    // 升级三态, 现状是继续印 0 —— 这不是本 PR 想要的最终状态, 是显式登记的缺口。
    // KPI 卡带单位, 取的是 "0 个" / "0 %"; 批次状态分布无单位, 纯数字。
    expect(statValue(wrapper, '完成批次')).toContain('0');
    expect(statValue(wrapper, '完成率')).toContain('0');
    // 已完成(批次状态分布)同一个字段, 同一个现状
    expect(statValue(wrapper, '已完成')).toBe('0');
  });
});
