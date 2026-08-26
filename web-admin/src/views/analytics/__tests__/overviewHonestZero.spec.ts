/**
 * 数据分析中心概览页 —— 「加载失败」不许长成「读数是 0」。
 *
 * 这一页有**餐饮 / 工厂两套分支**(`isRestaurant`), 两边得的是同一个病:
 *
 *   工厂侧: `overviewData` 初值全 0, catch 里只 console.error + toast,
 *          **从不重置数据、从不置失败标志** ⇒ 模板继续渲染那批初始 0。
 *   餐饮侧: 同样的初值 0, 而它的 catch **连 toast 都没有** —— 更隐蔽,
 *          用户看到的是一整屏静悄悄的 0, 连「出事了」这个信号都没有。
 *
 * ▎「没有读数」和「读数是 0」在界面上必须分得开。
 *
 * ⛔ 但 0 也是**合法读数**(「损耗 ¥0」「低库存 0」是好消息), 端点成功时必须照印。
 *   所以三态各有一条断言 —— 少任何一条, 另外两条都可能是恒真式。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const apiGet = vi.fn();
const pythonFetchMock = vi.fn();
const factoryType = { value: 'FACTORY' };

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({
    factoryId: 'F006',
    get factoryType() { return factoryType.value; },
  }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canViewPrice: true }),
}));
vi.mock('@/api/request', () => ({ get: (...a: unknown[]) => apiGet(...a) }));
vi.mock('@/api/smartbi/common', () => ({ pythonFetch: (...a: unknown[]) => pythonFetchMock(...a) }));

const elMessageError = vi.fn();
const elMessageWarning = vi.fn();
vi.mock('element-plus', () => ({
  ElMessage: { error: (...a: unknown[]) => elMessageError(...a), warning: (...a: unknown[]) => elMessageWarning(...a) },
}));

import AnalyticsOverview from '../index.vue';

const stubs = {
  'el-card': { template: '<div class="el-card"><slot name="header" /><slot /></div>' },
  'el-row': { template: '<div><slot /></div>' },
  'el-col': { template: '<div><slot /></div>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-alert': { props: ['title', 'type'], template: '<div class="el-alert" :data-type="type">{{ title }}<slot /></div>' },
  // ⚠️ 不渲染默认插槽: el-table-column 的默认插槽是**作用域插槽**(`{ row }`),
  //    照搬 `<slot />` 会让 row 为 undefined, 渲染期抛错 → 组件停在上一帧,
  //    所有断言都读到过期 DOM。这是仪器的坑, 不是被测组件的。
  //    Top5 表格不在本 spec 的断言范围内, 直接不渲染它的内容。
  'el-table': { template: '<div class="el-table" />' },
  'el-table-column': { template: '<div />' },
  'router-link': { template: '<a><slot /></a>' },
};

async function mountView() {
  const wrapper = mount(AnalyticsOverview, { global: { stubs } });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

/** 元素级切片: 只看某一张卡的数值区, ⛔ 不整份文件 toContain。 */
function statValue(wrapper: ReturnType<typeof mount>, title: string): string {
  const card = wrapper.find(`[data-stat="${title}"]`);
  expect(card.exists(), `找不到卡片「${title}」`).toBe(true);
  return card.find('.stat-value').text().trim();
}
function statCard(wrapper: ReturnType<typeof mount>, title: string) {
  return wrapper.find(`[data-stat="${title}"]`);
}

beforeEach(() => {
  apiGet.mockReset();
  pythonFetchMock.mockReset();
  elMessageError.mockReset();
  elMessageWarning.mockReset();
  factoryType.value = 'FACTORY';
});

// ───────────────────────── 工厂侧 ─────────────────────────

/** 按 URL 分派, ⛔ 不按调用顺序 —— 顺序分派会在请求顺序变化时静默把响应对调。 */
function routeFactory(o: {
  production?: unknown; quality?: unknown; overview?: unknown; equipment?: unknown; trends?: unknown;
} = {}) {
  apiGet.mockImplementation((url: string) => {
    const u = String(url);
    const pick = (v: unknown, fallback: unknown) => {
      const chosen = v === undefined ? fallback : v;
      if (chosen instanceof Error) return Promise.reject(chosen);
      return Promise.resolve(chosen);
    };
    if (u.includes('/dashboard/trends')) return pick(o.trends, { success: true, data: {} });
    if (u.includes('/dashboard/production')) {
      return pick(o.production, { success: true, data: { todayOutput: 115, completedBatches: 2 } });
    }
    if (u.includes('/dashboard/quality')) {
      return pick(o.quality, { success: true, data: { passRate: 68.33, pendingCount: 4 } });
    }
    if (u.includes('/dashboard/overview')) {
      return pick(o.overview, { success: true, data: { materialBatches: 31, lowStockMaterials: 2 } });
    }
    if (u.includes('/dashboard/equipment')) {
      return pick(o.equipment, { success: true, data: { totalEquipments: 9, alertCount: 3 } });
    }
    return Promise.reject(new Error(`未预期的请求: ${u}`));
  });
}

const FACTORY_SOURCED = ['生产', '质量', '仓储', '设备'] as const;

describe('工厂侧概览 —— 三态', () => {
  it('阳性对照: 端点正常时照常渲染真实读数 —— 否则「失败不渲染数字」是恒真式', async () => {
    routeFactory();
    const wrapper = await mountView();

    expect(statValue(wrapper, '生产')).toContain('115');
    expect(statValue(wrapper, '质量')).toBe('68.3%');
    expect(statValue(wrapper, '仓储')).toContain('31');
    expect(statValue(wrapper, '设备')).toContain('9');
    for (const t of FACTORY_SOURCED) {
      expect(statCard(wrapper, t).text(), `${t} 不该出现失败说明`).not.toContain('读取失败');
    }
    expect(elMessageWarning).not.toHaveBeenCalled();
  });

  it('🔴 四个端点全挂: ⛔ 一个 0 都不许印', async () => {
    const boom = new Error('500 Internal Server Error');
    routeFactory({ production: boom, quality: boom, overview: boom, equipment: boom });
    const wrapper = await mountView();

    for (const t of FACTORY_SOURCED) {
      const shown = statValue(wrapper, t);
      expect(
        shown,
        `「${t}」在端点失败时印出了 0/0.0% —— 用户读到的是真实读数, `
          + '而真相是我们一条都没查到。⛔ 失败不许被渲染成读数。',
      ).not.toMatch(/^(0|0\.0%)$/);
      expect(shown).toBe('—');
      expect(statCard(wrapper, t).text(), `${t} 缺少失败说明`).toContain('读取失败');
    }
    // 合格率尤其危险: 0.0% 合格率是灾难级读数, 不能由一个失败的请求说出来
    expect(statValue(wrapper, '质量')).not.toBe('0.0%');
    expect(elMessageWarning).toHaveBeenCalled();
  });

  it('🔴 端点成功且真的是 0 时必须照印 0 —— 否则上一条是恒真式', async () => {
    routeFactory({
      production: { success: true, data: { todayOutput: 0, completedBatches: 0 } },
      quality: { success: true, data: { passRate: 0, pendingCount: 0 } },
      overview: { success: true, data: { materialBatches: 0, lowStockMaterials: 0 } },
      equipment: { success: true, data: { totalEquipments: 0, alertCount: 0 } },
    });
    const wrapper = await mountView();

    expect(
      statValue(wrapper, '生产'),
      '端点成功且返回 0, 却印成了 — 。真读数 0 必须活下来, 否则只是把一种说谎换成了另一种。',
    ).toBe('0');
    expect(statValue(wrapper, '质量')).toBe('0.0%');
    expect(statValue(wrapper, '仓储')).toBe('0');
    expect(statValue(wrapper, '设备')).toBe('0');
    for (const t of FACTORY_SOURCED) {
      expect(statCard(wrapper, t).text()).not.toContain('读取失败');
    }
  });

  it('部分失败: 只有失败那张卡变 — , 成功的卡照印真读数', async () => {
    routeFactory({ quality: new Error('quality 500') });
    const wrapper = await mountView();

    expect(statValue(wrapper, '质量')).toBe('—');
    expect(statCard(wrapper, '质量').text()).toContain('读取失败');
    // 其它三张不受牵连
    expect(statValue(wrapper, '生产')).toContain('115');
    expect(statCard(wrapper, '生产').text()).not.toContain('读取失败');
    expect(statValue(wrapper, '设备')).toContain('9');
  });

  it('success:false 与抛错同等对待', async () => {
    routeFactory({ equipment: { success: false, message: '权限不足', data: null } });
    const wrapper = await mountView();

    expect(statValue(wrapper, '设备')).toBe('—');
    expect(statCard(wrapper, '设备').text()).toContain('读取失败');
  });

  it('🔴 销售/成本两张卡没有任何数据源 ⇒ ⛔ 永远不许印 0', async () => {
    routeFactory();
    const wrapper = await mountView();

    for (const t of ['销售', '成本'] as const) {
      expect(
        statValue(wrapper, t),
        `「${t}」没有任何赋值点(loadOverviewData 只打四个端点, 后端 overview 也不返回这些字段), `
          + '它印的 0 是永久的初值, 与数据和加载成败都无关 —— 那是最纯粹的假读数。',
      ).toBe('—');
      expect(statCard(wrapper, t).text()).toContain('尚未接入');
    }
  });
});

// ───────────────────────── 餐饮侧 ─────────────────────────

const RESTAURANT_SOURCED = ['领料', '损耗', '盘点', '食材成本'] as const;

const OPS_BUSY = {
  success: true,
  data: {
    totals: {
      totalRequisitions: 128, totalReqQty: 940.5, totalReqCost: 86400,
      totalWastage: 12, totalWastageCost: 3200,
      totalStocktaking: 6, totalShortage: 18.5, activeDays: 27,
    },
    top5Ingredients: [{ name: '牛腩', category: '肉类', cost: 12800 }],
  },
};
const OPS_TRUE_ZERO = {
  success: true,
  data: {
    totals: {
      totalRequisitions: 0, totalReqQty: 0, totalReqCost: 0,
      totalWastage: 0, totalWastageCost: 0,
      totalStocktaking: 0, totalShortage: 0, activeDays: 0,
    },
    top5Ingredients: [],
  },
};

describe('餐饮侧概览 —— 三态', () => {
  beforeEach(() => {
    factoryType.value = 'RESTAURANT';
  });

  it('阳性对照: 端点正常时照常渲染真实读数', async () => {
    pythonFetchMock.mockResolvedValue(OPS_BUSY);
    const wrapper = await mountView();

    expect(statValue(wrapper, '领料')).toContain('128');
    expect(statValue(wrapper, '损耗')).toContain('¥');
    expect(statValue(wrapper, '盘点')).toContain('18.5');
    expect(wrapper.find('.ops-failed-alert').exists()).toBe(false);
    expect(elMessageError).not.toHaveBeenCalled();
  });

  it('🔴 端点抛错: ⛔ 一个 0 都不许印, 而且必须有一个用户看得见的信号', async () => {
    pythonFetchMock.mockRejectedValue(new Error('500 Internal Server Error'));
    const wrapper = await mountView();

    for (const t of RESTAURANT_SOURCED) {
      const shown = statValue(wrapper, t);
      expect(
        shown,
        `「${t}」在端点失败时印出了 0/¥0 —— 店长读到的是「这个月什么都没干」, `
          + '而真相是我们一条都没查到。',
      ).not.toMatch(/0/);
      expect(shown).toBe('—');
    }
    // ¥ 也要收掉: 「¥—」读起来仍像一个金额
    expect(statValue(wrapper, '损耗')).not.toContain('¥');
    // 🔴 这条 catch 原本连 toast 都没有 —— 用户完全收不到「出事了」的信号
    expect(elMessageError, '餐饮侧读取失败时没有任何用户可见信号').toHaveBeenCalled();
    expect(wrapper.find('.ops-failed-alert').exists(), '缺少解释「—」含义的失败横幅').toBe(true);
  });

  it('🔴 端点成功且真的是 0 时必须照印 0 —— 否则上一条是恒真式', async () => {
    pythonFetchMock.mockResolvedValue(OPS_TRUE_ZERO);
    const wrapper = await mountView();

    expect(
      statValue(wrapper, '领料'),
      '端点成功且返回 0, 却印成了 — 。真读数 0 必须活下来。',
    ).toBe('0');
    // 「损耗 ¥0」是好消息, 必须说得出口
    expect(statValue(wrapper, '损耗')).toBe('¥0');
    expect(statValue(wrapper, '盘点')).toBe('0');
    expect(wrapper.find('.ops-failed-alert').exists()).toBe(false);
    expect(elMessageError).not.toHaveBeenCalled();
  });

  it('success:false 也算读不到, ⛔ 不许把初始 0 当成读数留在界面上', async () => {
    pythonFetchMock.mockResolvedValue({ success: false, message: '租户无权限' });
    const wrapper = await mountView();

    expect(statValue(wrapper, '领料')).toBe('—');
    expect(wrapper.find('.ops-failed-alert').exists()).toBe(true);
    expect(elMessageError).toHaveBeenCalled();
  });

  it('Top 食材那一格失败时不许把成本印成 ¥0', async () => {
    pythonFetchMock.mockRejectedValue(new Error('boom'));
    const wrapper = await mountView();

    const card = statCard(wrapper, 'Top 食材');
    expect(card.exists()).toBe(true);
    expect(
      card.find('.stat-footer').text().trim(),
      '读不到时印「¥0」= 把「不知道」说成「零成本」',
    ).not.toContain('¥0');
  });
});
