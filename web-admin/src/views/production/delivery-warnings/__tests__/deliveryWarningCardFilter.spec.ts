/**
 * 交货预警 —— 四张汇总卡必须「带真实计数」且「点得动」。
 *
 * 改动前的形态: 四个数(已超期/紧急/预警/正常)**已经算出来、也印在屏上**,
 * 但挂在不可点的 `<el-card>` 上。用户看得到「已超期 5」, 却只能自己在
 * 整张表里翻找那 5 条 —— 数字是死的。
 *
 * 三条判据(与 PendingQueue 那道闸同一套):
 *   ① 数是真的     —— 由实际返回的行算出。
 *   ② 点得动       —— 卡上的数 == 点下去表格里的行数。
 *   ③ 三态分得开   —— 问到了就印(**含 0**), 没问到印 `—`。
 *
 * ⚠️ ③ 在这一屏格外要紧: 「窗口内一条预警都没有」是**好消息**, 而
 * 「接口挂了」是我们什么都不知道。两者都长成 `0/0/0/0`, 界面上必须分得开。
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const apiGet = vi.fn();
const elMessageError = vi.fn();

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
}));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => apiGet(...args) }));
vi.mock('element-plus', () => ({
  ElMessage: { error: (...a: unknown[]) => elMessageError(...a) },
}));

import DeliveryWarnings from '../list.vue';

/** 2 超期 / 1 紧急 / 3 预警 / 1 正常 = 7 条 */
const ROWS = [
  { id: 1, planNumber: 'A-1', warnLevel: 'OVERDUE' },
  { id: 2, planNumber: 'A-2', warnLevel: 'OVERDUE' },
  { id: 3, planNumber: 'B-1', warnLevel: 'URGENT' },
  { id: 4, planNumber: 'C-1', warnLevel: 'WARN' },
  { id: 5, planNumber: 'C-2', warnLevel: 'WARN' },
  { id: 6, planNumber: 'C-3', warnLevel: 'WARN' },
  { id: 7, planNumber: 'D-1', warnLevel: 'NORMAL' },
];

const stubs = {
  'el-button': { template: '<button><slot /></button>' },
  'el-select': { template: '<div><slot /></div>' },
  'el-option': { template: '<div />' },
  'el-row': { template: '<div><slot /></div>' },
  'el-col': { template: '<div><slot /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
  // 表格 stub 把 :data 的长度暴露出来 —— 断言「点下去看到几条」全靠它。
  'el-table': {
    props: ['data', 'emptyText'],
    template:
      '<div class="tbl" :data-row-count="(data || []).length" :data-empty-text="emptyText"><slot /></div>',
  },
  // ⛔ 列 stub 不渲染默认插槽: 那是需要 `row` 作用域的 scoped slot。
  'el-table-column': { template: '<div />' },
};

async function mountPage(response: unknown) {
  if (response instanceof Error) apiGet.mockRejectedValue(response);
  else apiGet.mockResolvedValue(response);
  const wrapper = mount(DeliveryWarnings, { global: { stubs } });
  await flushPromises();
  await flushPromises();
  return wrapper;
}

const ok = (rows: unknown[]) => ({ success: true, data: rows });

/** 元素级切片, ⛔ 不整份文件 toContain。 */
function card(wrapper: ReturnType<typeof mount>, key: string) {
  const el = wrapper.find(`[data-warn-card="${key}"]`);
  expect(el.exists(), `找不到汇总卡「${key}」`).toBe(true);
  return el;
}
function cardCount(wrapper: ReturnType<typeof mount>, key: string): string {
  return card(wrapper, key).find('.card-value').text().trim();
}
function shownRowCount(wrapper: ReturnType<typeof mount>): number {
  return Number(wrapper.find('.tbl').attributes('data-row-count'));
}

const ALL_KEYS = ['ALL', 'OVERDUE', 'URGENT', 'WARN', 'NORMAL'] as const;

beforeEach(() => {
  apiGet.mockReset();
  elMessageError.mockReset();
});

describe('① 卡上的数由真实数据算出', () => {
  it('五张卡的计数与喂进去的行一一对上', async () => {
    const wrapper = await mountPage(ok(ROWS));

    expect(cardCount(wrapper, 'ALL')).toBe('7');
    expect(cardCount(wrapper, 'OVERDUE')).toBe('2');
    expect(cardCount(wrapper, 'URGENT')).toBe('1');
    expect(cardCount(wrapper, 'WARN')).toBe('3');
    expect(cardCount(wrapper, 'NORMAL')).toBe('1');
  });

  it('换一组数据计数跟着变 —— 否则上一条可能只是撞上了写死的常量', async () => {
    const wrapper = await mountPage(ok(ROWS.slice(0, 3)));

    expect(cardCount(wrapper, 'ALL')).toBe('3');
    expect(cardCount(wrapper, 'OVERDUE')).toBe('2');
    expect(cardCount(wrapper, 'URGENT')).toBe('1');
    expect(cardCount(wrapper, 'WARN')).toBe('0');
    expect(cardCount(wrapper, 'NORMAL')).toBe('0');
  });
});

describe('② 卡点得动, 且「点之前的数」==「点之后的条数」', () => {
  it('每张卡点下去, 表格行数都等于它自己印着的那个数', async () => {
    const wrapper = await mountPage(ok(ROWS));

    for (const key of ALL_KEYS) {
      const promised = Number(cardCount(wrapper, key));
      await card(wrapper, key).trigger('click');
      await flushPromises();
      expect(
        shownRowCount(wrapper),
        `卡「${key}」印着 ${promised}, 点下去表格却是 ${shownRowCount(wrapper)} 条 —— `
          + '计数与筛选口径不一致, 这比没有计数更糟。',
      ).toBe(promised);
    }
  });

  it('选中态标在被点的那一张上, 且只有那一张', async () => {
    const wrapper = await mountPage(ok(ROWS));

    await card(wrapper, 'WARN').trigger('click');
    await flushPromises();

    expect(card(wrapper, 'WARN').attributes('aria-pressed')).toBe('true');
    for (const key of ALL_KEYS.filter((k) => k !== 'WARN')) {
      expect(card(wrapper, key).attributes('aria-pressed'), `${key} 不该同时选中`).toBe('false');
    }
  });

  it('再点一次已选中的卡 = 取消筛选, 回到全部', async () => {
    const wrapper = await mountPage(ok(ROWS));

    await card(wrapper, 'OVERDUE').trigger('click');
    await flushPromises();
    expect(shownRowCount(wrapper)).toBe(2);

    await card(wrapper, 'OVERDUE').trigger('click');
    await flushPromises();
    expect(shownRowCount(wrapper)).toBe(7);
    expect(card(wrapper, 'ALL').attributes('aria-pressed')).toBe('true');
  });

  it('阴性对照: 不点任何卡时表格是全量 —— 否则上面几条可能只是恰好相等', async () => {
    const wrapper = await mountPage(ok(ROWS));
    expect(shownRowCount(wrapper)).toBe(7);
  });
});

describe('③ 三态: 读到就印(含 0), 读不到印 —', () => {
  it('🔴 接口抛错: ⛔ 一个 0 都不许印, 五张卡全是 —', async () => {
    const wrapper = await mountPage(new Error('500 Internal Server Error'));

    for (const key of ALL_KEYS) {
      expect(
        cardCount(wrapper, key),
        `卡「${key}」在接口挂掉时印出了 0 —— 用户读到的是「这一档今天一条都没有」(好消息), `
          + '而真相是我们一条都没查到。⛔ 失败不许被渲染成读数。',
      ).not.toBe('0');
      expect(cardCount(wrapper, key)).toBe('—');
    }
    expect(elMessageError).toHaveBeenCalled();
  });

  it('🔴 接口成功且窗口内真的 0 条时, 必须照印 0 —— 否则上一条是恒真式', async () => {
    const wrapper = await mountPage(ok([]));

    for (const key of ALL_KEYS) {
      expect(
        cardCount(wrapper, key),
        `卡「${key}」接口成功且窗口内确实无预警, 却印成了 — 。`
          + '「今天一条都没超期」是这一屏最值钱的好消息, 必须敢报。',
      ).toBe('0');
    }
    expect(elMessageError).not.toHaveBeenCalled();
  });

  it('success:false 与抛错同等对待 —— 都是读不到, 不是读到 0', async () => {
    const wrapper = await mountPage({ success: false, message: '权限不足', data: null });

    expect(cardCount(wrapper, 'ALL')).toBe('—');
    expect(cardCount(wrapper, 'OVERDUE')).toBe('—');
    expect(elMessageError).toHaveBeenCalled();
  });

  it('空表提示也要分得开: 没问到 ≠ 窗口内无预警', async () => {
    const failed = await mountPage(new Error('boom'));
    expect(failed.find('.tbl').attributes('data-empty-text')).toContain('未加载');

    apiGet.mockReset();
    const empty = await mountPage(ok([]));
    expect(empty.find('.tbl').attributes('data-empty-text')).toContain('无交货预警');
  });

  it('🔴 失败时不许留着上一窗口的数据冒充当前读数', async () => {
    const wrapper = await mountPage(ok(ROWS));
    expect(cardCount(wrapper, 'ALL')).toBe('7');

    // 换窗口 → 这一次挂了
    apiGet.mockRejectedValue(new Error('boom'));
    await (wrapper.vm as unknown as { loadData: () => Promise<void> }).loadData();
    await flushPromises();

    expect(
      cardCount(wrapper, 'ALL'),
      '新窗口的请求失败了, 卡上却还印着上一个窗口的 7 —— 过期读数被冒充成了当前读数。',
    ).toBe('—');
    expect(shownRowCount(wrapper)).toBe(0);
  });
});
