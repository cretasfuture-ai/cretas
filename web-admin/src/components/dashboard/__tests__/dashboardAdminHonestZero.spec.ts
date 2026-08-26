/**
 * 首页 Dashboard(factory_super_admin) —— 「加载失败」不许长成「读数是 0」。
 *
 * 🔴 这道闸守的是**一个具体的说谎方式**, 不是「页面能渲染」:
 *   后端四个端点全 500、页面顶上弹着「Dashboard 数据全部加载失败」的同时,
 *   四张 KPI 卡照旧印
 *     「今日产量 0 kg / 完成批次 0 个 / 设备运行 0 台 / 设备告警 0 条」,
 *   配色、单位、字号与真实读数完全一致。厂长读到的是「今天一炉都没开」,
 *   真相是「一条都没查到」—— 两件事的含义完全相反。
 *
 * ▎「没有读数」和「读数是 0」在界面上必须分得开。
 *   0 是一个**声称**, 而请求失败时我们没有任何东西可声称。
 *
 * ⛔ 但 0 也是**合法读数**: 端点成功且返回 0 时(今天确实没产量 / 确实零告警)
 *   必须照印 0。所以这里三态各有一条断言 —— 少任何一条, 另外两条都可能是恒真式。
 *
 * 同一条判据还管 onboarding 引导卡: 它的判定读的是同一批 `?? 0`,
 * 四个端点全挂时会把一个老工厂判成「新工厂, 暂无数据」并请他去做四步引导。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const apiGet = vi.fn();

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({
    factoryId: 'F006',
    user: { fullName: '张厂长', username: 'zhang' },
    isLiushanmenFactory: false,
  }),
}));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => apiGet(...args) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

const elMessageError = vi.fn();
const elMessageWarning = vi.fn();
vi.mock('element-plus', () => ({
  ElMessage: { error: (...a: unknown[]) => elMessageError(...a), warning: (...a: unknown[]) => elMessageWarning(...a) },
}));

import DashboardAdmin from '../DashboardAdmin.vue';

/** 端点成功且**真的**是 0 —— 这是合法读数, 必须照印。 */
const ZERO_OVERVIEW = { todayOutput: 0, completedBatches: 0, summary: { activeAlerts: 0 } };
/** 端点成功且有真实数据。 */
const BUSY_OVERVIEW = { todayOutput: 115, completedBatches: 2, summary: { activeAlerts: 3 } };
const BUSY_EQUIPMENT = { running: 7, activeAlerts: 3, totalEquipment: 9, avgUtilization: 80 };
const ZERO_EQUIPMENT = { running: 0, activeAlerts: 0, totalEquipment: 0, avgUtilization: 0 };

/**
 * 按 URL 分派 —— ⛔ 不按调用顺序分派。
 * 顺序分派会在请求顺序变化时静默把响应对调, 而两个响应都是合法形状 ⇒
 * 断言照样绿, 量的却是另一件事。
 */
function routeGet(o: {
  overview?: unknown;
  production?: unknown;
  quality?: unknown;
  equipment?: unknown;
  productTypes?: unknown;
  executive?: unknown;
} = {}) {
  apiGet.mockImplementation((url: string) => {
    const u = String(url);
    const pick = (v: unknown, fallback: unknown) => {
      const chosen = v === undefined ? fallback : v;
      if (chosen instanceof Error) return Promise.reject(chosen);
      return Promise.resolve(chosen);
    };
    // 顺序要紧: /reports/dashboard/overview 与 /smart-bi/dashboard/executive 都含 "dashboard"
    if (u.includes('/smart-bi/dashboard/executive')) return pick(o.executive, { success: true, data: {} });
    if (u.includes('/product-types')) return pick(o.productTypes, { success: true, data: { totalElements: 296 } });
    if (u.includes('/reports/dashboard/overview')) return pick(o.overview, { success: true, data: BUSY_OVERVIEW });
    if (u.includes('/reports/dashboard/production')) return pick(o.production, { success: true, data: {} });
    if (u.includes('/reports/dashboard/quality')) return pick(o.quality, { success: true, data: {} });
    if (u.includes('/reports/dashboard/equipment')) return pick(o.equipment, { success: true, data: BUSY_EQUIPMENT });
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
};

async function mountDash() {
  const wrapper = mount(DashboardAdmin, { global: { stubs } });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

/** 元素级切片: 只看某一张卡的数值区, ⛔ 不整份文件 toContain。 */
function statValue(wrapper: ReturnType<typeof mount>, title: string): string {
  const card = wrapper.find(`[data-stat="${title}"]`);
  expect(card.exists(), `找不到 KPI 卡「${title}」`).toBe(true);
  return card.find('.stat-value').text().trim();
}
function statCard(wrapper: ReturnType<typeof mount>, title: string) {
  return wrapper.find(`[data-stat="${title}"]`);
}

const ALL_CARDS = ['今日产量', '完成批次', '设备运行', '设备告警'] as const;

beforeEach(() => {
  apiGet.mockReset();
  elMessageError.mockReset();
  elMessageWarning.mockReset();
});

describe('首页 KPI —— 三态必须分得开', () => {
  it('阳性对照: 端点正常时 KPI 照常渲染真实读数 —— 否则下面「失败不渲染数字」是恒真式', async () => {
    routeGet();
    const wrapper = await mountDash();

    expect(statValue(wrapper, '今日产量')).toContain('115');
    expect(statValue(wrapper, '完成批次')).toContain('2');
    expect(statValue(wrapper, '设备运行')).toContain('7');
    expect(statValue(wrapper, '设备告警')).toContain('3');
    // 有读数时单位必须在
    expect(statValue(wrapper, '今日产量')).toContain('kg');
    // 没失败就不许说失败
    for (const t of ALL_CARDS) {
      expect(statCard(wrapper, t).text(), `${t} 不该出现失败说明`).not.toContain('读取失败');
    }
  });

  it('🔴 四个端点全挂: ⛔ 一个 0 都不许印, 四张卡全是 — + 读取失败', async () => {
    const boom = new Error('500 Internal Server Error');
    routeGet({ overview: boom, production: boom, quality: boom, equipment: boom });
    const wrapper = await mountDash();

    for (const t of ALL_CARDS) {
      const shown = statValue(wrapper, t);
      expect(
        shown,
        `「${t}」在全部端点失败时印出了 0 —— 用户读到的是「今天真的是 0」, `
          + '而真相是我们一条都没查到。⛔ 失败不许被渲染成读数。',
      ).not.toBe('0');
      expect(shown).toBe('—');
      expect(statCard(wrapper, t).text(), `${t} 缺少失败说明`).toContain('读取失败');
    }
    // 单位也要收掉: 「— kg」读起来仍像一个产量
    expect(statValue(wrapper, '今日产量')).not.toContain('kg');
    expect(elMessageError).toHaveBeenCalled();
  });

  it('🔴 端点成功且真的是 0 时, 必须照印 0 —— 否则上一条是恒真式', async () => {
    routeGet({
      overview: { success: true, data: ZERO_OVERVIEW },
      equipment: { success: true, data: ZERO_EQUIPMENT },
    });
    const wrapper = await mountDash();

    for (const t of ALL_CARDS) {
      expect(
        statValue(wrapper, t),
        `「${t}」端点成功且返回 0, 却印成了 — 。真读数 0 必须活下来, `
          + '否则我们只是把一种说谎换成了另一种(永远不敢报 0)。',
      ).toContain('0');
      expect(statValue(wrapper, t)).not.toBe('—');
      expect(statCard(wrapper, t).text()).not.toContain('读取失败');
    }
    expect(statValue(wrapper, '今日产量')).toContain('kg');
  });

  it('部分失败: 只有失败那张卡变 — , 成功的卡照印真读数(含 0)', async () => {
    routeGet({
      overview: new Error('overview 500'),
      equipment: { success: true, data: ZERO_EQUIPMENT },
    });
    const wrapper = await mountDash();

    // overview 挂了 → 今日产量/完成批次读不到
    expect(statValue(wrapper, '今日产量')).toBe('—');
    expect(statValue(wrapper, '完成批次')).toBe('—');
    // equipment 好着 → 设备运行是真读数 0
    expect(statValue(wrapper, '设备运行')).toContain('0');
    expect(statCard(wrapper, '设备运行').text()).not.toContain('读取失败');
    // 🔴 告警数两个端点都能供 ⇒ overview 挂了但 equipment 好着时,
    //    必须用 equipment 的真读数, ⛔ 不许因为 overview 挂了就抹成 —
    expect(
      statValue(wrapper, '设备告警'),
      'overview 挂了就把 equipment 已经读到的真实告警数抹掉 ⇒ 白白丢掉一个有效读数',
    ).toContain('0');
  });

  it('success:false 与抛错同等对待 —— 都是读不到, 不是读到 0', async () => {
    routeGet({ overview: { success: false, message: '权限不足', data: null } });
    const wrapper = await mountDash();

    expect(statValue(wrapper, '今日产量')).toBe('—');
    expect(statCard(wrapper, '今日产量').text()).toContain('读取失败');
  });
});

describe('首页 onboarding 引导 —— 读不到不许被判成「新工厂」', () => {
  it('🔴 四个端点全挂时, ⛔ 不许把老工厂请去做「快速开始」引导', async () => {
    const boom = new Error('500');
    routeGet({
      overview: boom, production: boom, quality: boom, equipment: boom,
      // 产品档案数也读不到 —— 这正是真实故障的样子(后端全挂)
      productTypes: boom,
      executive: boom,
    });
    const wrapper = await mountDash();

    expect(
      wrapper.text(),
      '后端全挂 ⇒ 四个 `?? 0` 全为 0 ⇒ 判定「新工厂」成立 ⇒ '
        + '一个有 296 个产品的老工厂被告知「当前工厂暂无数据」并请去做四步引导。'
        + '「读不到」不是「都是 0」。',
    ).not.toContain('当前工厂暂无数据');
    expect(wrapper.text()).not.toContain('快速开始');
  });

  it('阳性对照: 真的是空工厂(端点成功且全 0 + 无产品档案)时, 引导必须出得来', async () => {
    routeGet({
      overview: { success: true, data: ZERO_OVERVIEW },
      equipment: { success: true, data: ZERO_EQUIPMENT },
      productTypes: { success: true, data: { totalElements: 0 } },
      executive: { success: true, data: { kpiCards: [] } },
    });
    const wrapper = await mountDash();

    expect(
      wrapper.text(),
      '真空工厂也不显示引导 ⇒ 上一条「全挂时不显示」是恒真式, 什么都没守住。',
    ).toContain('当前工厂暂无数据');
  });

  it('有产品档案时不显示引导(既有行为不回归)', async () => {
    routeGet({
      overview: { success: true, data: ZERO_OVERVIEW },
      equipment: { success: true, data: ZERO_EQUIPMENT },
      productTypes: { success: true, data: { totalElements: 296 } },
    });
    const wrapper = await mountDash();
    expect(wrapper.text()).not.toContain('当前工厂暂无数据');
  });
});
