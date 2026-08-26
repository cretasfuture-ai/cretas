import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createTrailingDebouncer, daysInStorage, formatQuantity, maxDaysInStorage, nextLocationCode, normalizeWarehouseLayout, normalizedLocationPrefix } from './warehouseLayoutModel';

describe('真实工厂储存位置与库存时间模型', () => {
  it('empty factory has no fabricated zones, locations, or floors', () => {
    expect(normalizeWarehouseLayout(null)).toEqual({ zones: [], locations: [], floors: [] });
  });

  it('fills the first unused position under the selected zone prefix', () => {
    const zone = { id: 'cold', name: '冷藏区', prefix: 'LC' };
    expect(nextLocationCode(zone, [
      { id: '1', zoneId: 'cold', code: 'LC01', name: '1号位' },
      { id: '3', zoneId: 'cold', code: 'LC03', name: '3号位' },
    ])).toBe('LC02');
  });

  it('computes the age of inventory currently still stored', () => {
    expect(daysInStorage('2026-08-20T00:00:00Z', Date.parse('2026-08-25T12:00:00Z'))).toBe(5);
    expect(daysInStorage(undefined)).toBeNull();
  });

  it('derives a searchable first-level storage type from a position code', () => {
    expect(normalizedLocationPrefix('lc013')).toBe('LC');
    expect(normalizedLocationPrefix('01')).toBe('');
  });
});

describe('2026-08-25 生产实测缺陷的回归钉', () => {
  it('浮点误差不再打给仓管 —— 168.96999999999997 必须收敛成 168.97', () => {
    // 生产现场盘点页实测出现过的两个原值
    expect(formatQuantity(168.96999999999997)).toBe('168.97');
    expect(formatQuantity(29.990000000000002)).toBe('29.99');
    // 阴性对照: 真实精度不许被抹掉
    expect(formatQuantity(0.04)).toBe('0.04');
    expect(formatQuantity(114361)).toBe('114361');
    expect(formatQuantity(1.125)).toBe('1.125');
  });

  it('全部批次都没有入库日期时是「未知」, ⛔ 不是 0 天', () => {
    // 原实现写的是 `Math.max(...entries.map(e => daysInStorage(e) ?? 0))`,
    // 缺日期时返回 0, 等于向仓管断言「今天入库」—— 那是编造的读数。
    expect(maxDaysInStorage([undefined, undefined])).toBeNull();
    expect(maxDaysInStorage([])).toBeNull();
  });

  it('只要有一条批次有日期, 就按有日期的那些算最长在库天数', () => {
    const now = Date.parse('2026-08-25T12:00:00Z');
    expect(maxDaysInStorage([undefined, '2026-08-20T00:00:00Z'], now)).toBe(5);
    expect(maxDaysInStorage(['2026-08-24T00:00:00Z', '2026-08-18T00:00:00Z'], now)).toBe(7);
  });
});


/**
 * 拖拽货架的保存调度。
 *
 * 🔴 为什么是**这里**而不是浏览器里验: 2026-08-26 试了三次用浏览器自动化驱动
 * Vue Flow 的拖拽来数 PUT 次数, 三次都是「节点纹丝不动 / 0 次请求」——
 * Vue Flow 吃的是 d3-drag 的内部 pointer 处理, 合成 PointerEvent 打不进去。
 * 那个读数**既不能证明防抖生效, 也不能证明它没生效**, 是仪器够不着被测行为。
 * ⇒ 把逻辑抽成纯函数, 用假定时器确定性地验。
 *
 * ⚠️ 这里验的是**调度行为**; 「Vue Flow 拖停时确实会调用它」是另一件事,
 *    由 index.vue 的源码闸钉住。⛔ 两层不要混为一谈。
 */
describe('拖拽货架的保存调度 (createTrailingDebouncer)', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('600ms 内连续 3 次微调, 只落一次保存', () => {
    const runs: number[] = [];
    const d = createTrailingDebouncer(600);

    d.schedule(() => runs.push(1));
    vi.advanceTimersByTime(150);
    d.schedule(() => runs.push(2));
    vi.advanceTimersByTime(150);
    d.schedule(() => runs.push(3));

    // 还没到窗口末尾 —— 一次都不该发
    vi.advanceTimersByTime(599);
    expect(runs).toEqual([]);

    vi.advanceTimersByTime(1);
    // 只发一次, 且发的是**最后一次**排的那个回调(拿到的是最新位置)
    expect(runs).toEqual([3]);
  });

  it('两次微调间隔超过窗口, 就该各发各的 —— ⛔ 不是无脑合并', () => {
    // 阴性对照: 如果实现写成「只发第一次就再也不发」, 这条会红。
    const runs: number[] = [];
    const d = createTrailingDebouncer(600);

    d.schedule(() => runs.push(1));
    vi.advanceTimersByTime(600);
    expect(runs).toEqual([1]);

    d.schedule(() => runs.push(2));
    vi.advanceTimersByTime(600);
    expect(runs).toEqual([1, 2]);
  });

  it('cancel() 之后不再落地 —— 用户已经离开页面, 不该再打一次整份 PUT', () => {
    const runs: number[] = [];
    const d = createTrailingDebouncer(600);

    d.schedule(() => runs.push(1));
    expect(d.pending).toBe(true);
    d.cancel();
    expect(d.pending).toBe(false);

    vi.advanceTimersByTime(5000);
    expect(runs).toEqual([]);
  });
});
