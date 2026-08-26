/**
 * 未完成计划队列 —— 状态胶囊必须「带真实计数」且「点得动」。
 *
 * 改动前的形态: 三个数(待执行/进行中/已超期)**已经算出来、也印在屏上**,
 * 但挂在 `<el-tag>` 上, 全文没有一处 @click 落在它们身上。用户看得到
 * 「进行中 6」, 却没有任何办法只看那 6 条 —— 数字是死的。
 *
 * 这道闸守三件事, 三条缺一不可:
 *
 *   ① **数是真的**   —— 由实际拉到的行算出, 不是写死的常量。
 *   ② **点得动**     —— 点胶囊真的把表格收窄到那一类, 且**行数与胶囊上的数相等**。
 *                       这一条是「计数」与「筛选」之间唯一的契约: 用户点之前
 *                       读到的那个数, 就是点之后会看到的条数。
 *   ③ **三态分得开** —— 问到了就印(**含 0**), 没问到印 `—`。
 *
 * ⚠️ ③ 为什么必须有两条相反方向的断言: 只断言「失败时不印 0」, 会被一个
 * 「永远不敢报 0」的实现骗过去; 只断言「0 要照印」, 又会被 `?? 0` 骗过去。
 * 而 0 在筛选胶囊上恰好是**最有用**的读数 ——「这一类今天一条都没有」。
 * ⛔ 所以既不许把失败印成 0, 也不许把真实的 0 印成 —。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const apiGet = vi.fn();

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => apiGet(...args) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('element-plus', () => ({
  ElMessage: Object.assign(
    (...a: unknown[]) => elMessage(...a),
    { error: (...a: unknown[]) => elMessage(...a) },
  ),
}));
const elMessage = vi.fn();

import PendingQueue from '../PendingQueue.vue';

/** 日期锚点: 相对今天算, 避免用例随时间流逝变红。 */
function daysFromToday(n: number): string {
  const d = new Date();
  d.setHours(12, 0, 0, 0);
  d.setDate(d.getDate() + n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** 2 条 PENDING(其中 1 条已超期) */
const PENDING_ROWS = [
  { id: 1, planNumber: 'PP-001', status: 'PENDING', plannedDate: daysFromToday(-3) },
  { id: 2, planNumber: 'PP-002', status: 'PENDING', plannedDate: daysFromToday(5) },
];
/** 3 条 IN_PROGRESS(其中 1 条已超期) */
const IN_PROGRESS_ROWS = [
  { id: 3, planNumber: 'IP-001', status: 'IN_PROGRESS', plannedDate: daysFromToday(-1) },
  { id: 4, planNumber: 'IP-002', status: 'IN_PROGRESS', plannedDate: daysFromToday(2) },
  { id: 5, planNumber: 'IP-003', status: 'IN_PROGRESS', plannedDate: daysFromToday(9) },
];

/**
 * 按 status 参数分派, ⛔ 不按调用顺序分派 ——
 * 顺序分派会在两个请求的发起顺序变化时静默把响应对调, 而两个响应都是合法形状,
 * 断言照样绿, 量的却是另一件事。
 */
function routeGet(o: { pending?: unknown; inProgress?: unknown } = {}) {
  const ok = (rows: unknown[]) => ({ success: true, data: { content: rows } });
  apiGet.mockImplementation((_url: string, cfg?: { params?: { status?: string } }) => {
    const status = cfg?.params?.status;
    const pick = (v: unknown, fallback: unknown) => {
      const chosen = v === undefined ? fallback : v;
      if (chosen instanceof Error) return Promise.reject(chosen);
      return Promise.resolve(chosen);
    };
    if (status === 'PENDING') return pick(o.pending, ok(PENDING_ROWS));
    if (status === 'IN_PROGRESS') return pick(o.inProgress, ok(IN_PROGRESS_ROWS));
    return Promise.reject(new Error(`未预期的 status: ${status}`));
  });
}

const stubs = {
  'el-button': { template: '<button><slot /></button>' },
  'el-input': { template: '<input />' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-tooltip': { template: '<span><slot /></span>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-empty': {
    props: ['description'],
    template: '<div class="empty" :data-desc="description"><slot /></div>',
  },
  // 表格 stub 必须把 :data 的长度暴露出来 —— 断言「点下去看到几条」全靠它。
  'el-table': {
    props: ['data'],
    template: '<div class="tbl" :data-row-count="(data || []).length"><slot /></div>',
  },
  // ⛔ 列 stub 不渲染默认插槽: 那是个需要 `row` 作用域的 scoped slot,
  //    空渲染会拿 undefined 去读字段而炸。本闸只关心行数, 不关心单元格内容。
  'el-table-column': { template: '<div />' },
};

async function mountQueue() {
  const wrapper = mount(PendingQueue, { global: { stubs } });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

/** 元素级切片: 只取某一个胶囊的计数区, ⛔ 不整份文件 toContain。 */
function capsule(wrapper: ReturnType<typeof mount>, key: string) {
  const el = wrapper.find(`[data-capsule="${key}"]`);
  expect(el.exists(), `找不到状态胶囊「${key}」`).toBe(true);
  return el;
}
function capsuleCount(wrapper: ReturnType<typeof mount>, key: string): string {
  return capsule(wrapper, key).find('.capsule-count').text().trim();
}
function shownRowCount(wrapper: ReturnType<typeof mount>): number {
  return Number(wrapper.find('.tbl').attributes('data-row-count'));
}

const ALL_KEYS = ['ALL', 'PENDING', 'IN_PROGRESS', 'OVERDUE'] as const;

beforeEach(() => {
  apiGet.mockReset();
  elMessage.mockReset();
});

describe('① 胶囊上的数由真实数据算出', () => {
  it('四个胶囊的计数与喂进去的行一一对上', async () => {
    routeGet();
    const wrapper = await mountQueue();

    expect(capsuleCount(wrapper, 'ALL')).toBe('5');
    expect(capsuleCount(wrapper, 'PENDING')).toBe('2');
    expect(capsuleCount(wrapper, 'IN_PROGRESS')).toBe('3');
    // 一条 PENDING(-3天) + 一条 IN_PROGRESS(-1天)
    expect(capsuleCount(wrapper, 'OVERDUE')).toBe('2');
  });

  it('换一组数据, 计数跟着变 —— 否则上一条可能只是撞上了写死的常量', async () => {
    routeGet({
      pending: { success: true, data: { content: [PENDING_ROWS[1]] } },
      inProgress: { success: true, data: { content: IN_PROGRESS_ROWS.slice(0, 2) } },
    });
    const wrapper = await mountQueue();

    expect(capsuleCount(wrapper, 'ALL')).toBe('3');
    expect(capsuleCount(wrapper, 'PENDING')).toBe('1');
    expect(capsuleCount(wrapper, 'IN_PROGRESS')).toBe('2');
    expect(capsuleCount(wrapper, 'OVERDUE')).toBe('1');
  });
});

describe('② 胶囊点得动, 且「点之前的数」==「点之后的条数」', () => {
  it('每个胶囊点下去, 表格行数都等于它自己印着的那个数', async () => {
    routeGet();
    const wrapper = await mountQueue();

    for (const key of ALL_KEYS) {
      const promised = Number(capsuleCount(wrapper, key));
      await capsule(wrapper, key).trigger('click');
      await flushPromises();
      expect(
        shownRowCount(wrapper),
        `胶囊「${key}」印着 ${promised}, 点下去表格却是 ${shownRowCount(wrapper)} 条 —— `
          + '计数与筛选口径不一致, 这比没有计数更糟。',
      ).toBe(promised);
    }
  });

  it('选中态标在被点的那一个上, 且只有那一个', async () => {
    routeGet();
    const wrapper = await mountQueue();

    await capsule(wrapper, 'IN_PROGRESS').trigger('click');
    await flushPromises();

    expect(capsule(wrapper, 'IN_PROGRESS').attributes('aria-pressed')).toBe('true');
    for (const key of ALL_KEYS.filter((k) => k !== 'IN_PROGRESS')) {
      expect(capsule(wrapper, key).attributes('aria-pressed'), `${key} 不该同时选中`).toBe('false');
    }
  });

  it('再点一次已选中的胶囊 = 取消筛选, 回到全部', async () => {
    routeGet();
    const wrapper = await mountQueue();

    await capsule(wrapper, 'PENDING').trigger('click');
    await flushPromises();
    expect(shownRowCount(wrapper)).toBe(2);

    await capsule(wrapper, 'PENDING').trigger('click');
    await flushPromises();
    expect(shownRowCount(wrapper)).toBe(5);
    expect(capsule(wrapper, 'ALL').attributes('aria-pressed')).toBe('true');
  });

  it('阴性对照: 不点任何胶囊时表格是全量 —— 否则上面几条可能只是恰好相等', async () => {
    routeGet();
    const wrapper = await mountQueue();
    expect(shownRowCount(wrapper)).toBe(5);
  });
});

describe('③ 三态: 读到就印(含 0), 读不到印 —', () => {
  it('🔴 两路都挂: ⛔ 一个 0 都不许印, 四个胶囊全是 —', async () => {
    const boom = new Error('500 Internal Server Error');
    routeGet({ pending: boom, inProgress: boom });
    const wrapper = await mountQueue();

    for (const key of ALL_KEYS) {
      expect(
        capsuleCount(wrapper, key),
        `胶囊「${key}」在接口全挂时印出了 0 —— 用户读到的是「这一类今天一条都没有」, `
          + '而真相是我们一条都没查到。⛔ 失败不许被渲染成读数。',
      ).not.toBe('0');
      expect(capsuleCount(wrapper, key)).toBe('—');
    }
  });

  it('🔴 接口成功且真的是 0 条时, 必须照印 0 —— 否则上一条是恒真式', async () => {
    routeGet({
      pending: { success: true, data: { content: [] } },
      inProgress: { success: true, data: { content: [] } },
    });
    const wrapper = await mountQueue();

    for (const key of ALL_KEYS) {
      expect(
        capsuleCount(wrapper, key),
        `胶囊「${key}」接口成功且返回 0 条, 却印成了 — 。`
          + '真读数 0 必须活下来, 否则我们只是把一种说谎换成了另一种(永远不敢报 0)。',
      ).toBe('0');
    }
  });

  it('部分失败: 只有挂掉那一路变 —, 好着的那一路照印真读数', async () => {
    routeGet({ pending: new Error('PENDING 500') });
    const wrapper = await mountQueue();

    expect(capsuleCount(wrapper, 'PENDING')).toBe('—');
    // IN_PROGRESS 那一路好着 ⇒ 已经读到的 3 条不许被连坐抹掉
    expect(
      capsuleCount(wrapper, 'IN_PROGRESS'),
      'PENDING 挂了就把 IN_PROGRESS 已经读到的真实条数抹掉 ⇒ 白白丢掉一个有效读数',
    ).toBe('3');
    // 「全部」「已超期」横跨两路 ⇒ 任一路缺了就都不算数
    expect(capsuleCount(wrapper, 'ALL')).toBe('—');
    expect(capsuleCount(wrapper, 'OVERDUE')).toBe('—');
  });

  it('success:false 与抛错同等对待 —— 都是读不到, 不是读到 0', async () => {
    routeGet({ pending: { success: false, message: '权限不足', data: null } });
    const wrapper = await mountQueue();
    expect(capsuleCount(wrapper, 'PENDING')).toBe('—');
  });

  it('🔴 已超期为 0 时胶囊仍要在场 —— 消失的胶囊和「没问到」长得一模一样', async () => {
    // 全部都是未来的日期 ⇒ 真实的「零超期」, 这是好消息, 必须看得见
    routeGet({
      pending: { success: true, data: { content: [PENDING_ROWS[1]] } },
      inProgress: { success: true, data: { content: [IN_PROGRESS_ROWS[2]] } },
    });
    const wrapper = await mountQueue();

    expect(wrapper.find('[data-capsule="OVERDUE"]').exists()).toBe(true);
    expect(capsuleCount(wrapper, 'OVERDUE')).toBe('0');
  });
});

describe('空表的说法要跟着「为什么空」走', () => {
  function emptyDesc(wrapper: ReturnType<typeof mount>): string {
    return wrapper.find('.empty').attributes('data-desc') ?? '';
  }

  it('🔴 筛「已超期」筛出 0 条时, ⛔ 不许说「暂无未完成计划」', async () => {
    // 队列里有 5 条计划, 只是一条都没超期 —— 这是好消息, 不是空队列
    routeGet({
      pending: { success: true, data: { content: [PENDING_ROWS[1]] } },
      inProgress: { success: true, data: { content: [IN_PROGRESS_ROWS[2]] } },
    });
    const wrapper = await mountQueue();

    await capsule(wrapper, 'OVERDUE').trigger('click');
    await flushPromises();

    expect(shownRowCount(wrapper)).toBe(0);
    expect(
      emptyDesc(wrapper),
      '筛掉了 ≠ 队列空了。文员筛「已超期」看到「暂无未完成计划」, '
        + '会以为整个队列都空了, 而实际上还有 2 条计划等着做。',
    ).not.toBe('暂无未完成计划');
    expect(emptyDesc(wrapper)).toContain('已超期');
  });

  it('阳性对照: 队列真的空时, 照说「暂无未完成计划」', async () => {
    routeGet({
      pending: { success: true, data: { content: [] } },
      inProgress: { success: true, data: { content: [] } },
    });
    const wrapper = await mountQueue();
    expect(emptyDesc(wrapper)).toBe('暂无未完成计划');
  });

  it('没问到时说的是「没加载」, 不是「没有」', async () => {
    const boom = new Error('500');
    routeGet({ pending: boom, inProgress: boom });
    const wrapper = await mountQueue();

    expect(emptyDesc(wrapper)).not.toBe('暂无未完成计划');
    expect(emptyDesc(wrapper)).toContain('未加载');
  });
});

describe('计数口径 = 关键词过滤之后', () => {
  it('搜索框收窄后, 胶囊的数仍等于点下去的行数', async () => {
    routeGet();
    const wrapper = await mountQueue();

    // 只留 planNumber 含 "IP-" 的三条 IN_PROGRESS
    (wrapper.vm as unknown as { keyword: string }).keyword = 'IP-';
    await flushPromises();

    expect(capsuleCount(wrapper, 'ALL')).toBe('3');
    expect(capsuleCount(wrapper, 'PENDING')).toBe('0');
    expect(capsuleCount(wrapper, 'IN_PROGRESS')).toBe('3');

    await capsule(wrapper, 'IN_PROGRESS').trigger('click');
    await flushPromises();
    expect(shownRowCount(wrapper)).toBe(3);
  });
});
