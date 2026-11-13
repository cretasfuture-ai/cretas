import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createTodayLoader, READ_TIMEOUT_MS, SRC_IDS, type TodayContext } from '../todayData';
import { tabBadge, TODO_TABS } from '../todoTabs';

/**
 * G-1 / G-2 + 数据口径的行为测试。
 * 假 get 按 URL 回数据；「挂起」= 返回永不 resolve 的 Promise 且不理 signal（最坏情形）。
 */
type Handler = (url: string, config?: Record<string, unknown>) => Promise<unknown>;

const ok = (data: unknown, extra: Record<string, unknown> = {}) => Promise.resolve({ success: true, data, ...extra });
const NEVER = () => new Promise<never>(() => undefined);

function makeGet(overrides: Record<string, Handler> = {}) {
  const calls: { url: string; config?: Record<string, unknown> }[] = [];
  const get = (url: string, config?: Record<string, unknown>) => {
    calls.push({ url, config });
    for (const [frag, h] of Object.entries(overrides)) if (url.includes(frag)) return h(url, config) as never;
    if (url.includes('/workflow/instances/pending')) return ok({ items: [{ instanceId: 'a', businessSummary: 'PO-1' }], total: 7 }) as never;
    if (url.includes('/workflow/instances/my-created')) return ok({ content: [], totalElements: 2 }) as never;
    if (url.includes('/workflow/instances/acted')) return ok({ content: [], totalElements: 5 }) as never;
    if (url.includes('/workflow/instances/copied')) return ok({ content: [], totalElements: 1 }) as never;
    if (url.includes('/disposal-records')) return ok([{ id: 'd1', recordNumber: 'DS-1' }], { totalElements: 3 }) as never;
    if (url.includes('/material-batches/expiring')) return ok([{ id: 'b1', batchNumber: 'B1' }, { id: 'b2', batchNumber: 'B2' }]) as never;
    if (url.includes('/material-batches/expired')) return ok([{ id: 'b2', batchNumber: 'B2' }]) as never;
    if (url.includes('/delivery-risks')) return ok([
      { orderNumber: 'SO-1', riskLevel: 'RISK', ownerUserId: 9 },
      { orderNumber: 'SO-2', riskLevel: 'UNKNOWN', ownerUserId: 10 },
      { orderNumber: 'SO-3', riskLevel: 'RISK', ownerUserId: 10 },
    ]) as never;
    if (url.includes('/warehouse/receiving/tasks')) return ok([{ id: 1 }, { id: 2 }]) as never;
    if (url.includes('/task-badges/')) return ok({ state: 'KNOWN', count: 4 }) as never;
    return Promise.reject(new Error(`unexpected ${url}`));
  };
  return { get, calls };
}

const ctxOf = (patch: Partial<TodayContext> = {}): TodayContext => ({
  factoryId: 'F1', userId: '10', canRead: () => true, disposalCanApprove: async () => false, ...patch,
});

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => { vi.useRealTimers(); });

describe('今天 / 待办 数据口径', () => {
  it('各来源数字来自对应接口；报废审批只在有资格时计入，且没资格时根本不发请求', async () => {
    const a = makeGet();
    const L = createTodayLoader({ get: a.get as never });
    await L.load(ctxOf({ disposalCanApprove: async () => true }));
    expect(L.T.s.approve.count).toBe(7 + 3);
    expect(L.T.s.approve.note).toContain('报废审批 3');
    expect(L.T.s.mine.count).toBe(2);
    expect(L.T.s.acted.count).toBe(5);
    expect(L.T.s.copied.count).toBe(1);
    expect(L.T.s.expiry.count).toBe(2); // b2 同时在 expiring 与 expired 里，只算一次
    expect(L.T.s.risk.count).toBe(2);
    expect(L.T.s.assigned.count).toBe(2); // ownerUserId == 10 的两条
    expect(L.T.s.receiving.count).toBe(2);
    expect(L.T.s.unordered.count).toBe(4);

    const b = makeGet();
    const L2 = createTodayLoader({ get: b.get as never });
    await L2.load(ctxOf({ disposalCanApprove: async () => false }));
    expect(L2.T.s.approve.count).toBe(7);
    expect(b.calls.some((c) => c.url.includes('/disposal-records'))).toBe(false);
    expect(L2.T.s.approve.note).toContain('没有审批资格');
  });

  it('权限不够的来源不发请求，标「不适用」并写原因（不是 0）', async () => {
    const a = makeGet();
    const L = createTodayLoader({ get: a.get as never });
    await L.load(ctxOf({ canRead: (m) => !m.includes('warehouse') && !m.includes('inventory') }));
    expect(L.T.s.expiry.state).toBe('na');
    expect(L.T.s.expiry.note).toContain('没有仓储');
    expect(L.T.s.receiving.state).toBe('na');
    expect(a.calls.some((c) => c.url.includes('material-batches') || c.url.includes('receiving/tasks'))).toBe(false);
  });

  it('task-badge 服务端说 UNKNOWN ⇒ 读取失败，不当 0', async () => {
    const a = makeGet({ 'task-badges/sales-orders': () => ok({ state: 'UNKNOWN', count: null, reason: '计数暂时无法计算' }) });
    const L = createTodayLoader({ get: a.get as never });
    await L.load(ctxOf());
    expect(L.T.s.salesOrders.state).toBe('error');
    expect(L.T.s.salesOrders.note).toContain('计数暂时无法计算');
    expect(L.T.s.unordered.state).toBe('ok');
  });
});

describe('G-1：读取超时 + 释放 inflight（阳性对照）', () => {
  it('一路请求永远挂起：10 s 内该路显示「读取失败」、其余照常；带了 signal 并被 abort', async () => {
    let hungSignal: AbortSignal | undefined;
    const a = makeGet({ '/delivery-risks': (_u, c) => { hungSignal = c?.signal as AbortSignal; return NEVER(); } });
    const L = createTodayLoader({ get: a.get as never });
    const p = L.load(ctxOf());
    await vi.advanceTimersByTimeAsync(READ_TIMEOUT_MS - 1);
    expect(L.T.busy, '时限前还在读').toBe(true);
    await vi.advanceTimersByTimeAsync(2);
    await p;
    expect(L.T.busy).toBe(false);
    expect(L.T.s.risk.state).toBe('error');
    expect(L.T.s.risk.note).toContain('读取失败');
    expect(L.T.s.assigned.state).toBe('error');
    expect(L.T.s.approve.state).toBe('ok');
    expect(hungSignal?.aborted).toBe(true);
  });

  it('超时后下一次进入重新发请求并读到（inflight 已释放）', async () => {
    let hang = true;
    const a = makeGet({ '/delivery-risks': (_u, c) => (hang ? NEVER() : (makeGet().get('/F1/delivery-risks', c) as never)) });
    const L = createTodayLoader({ get: a.get as never });
    const first = L.load(ctxOf());
    await vi.advanceTimersByTimeAsync(READ_TIMEOUT_MS + 1);
    await first;
    expect(L.T.s.risk.state).toBe('error');
    const before = a.calls.filter((c) => c.url.includes('/delivery-risks')).length;
    hang = false;
    await L.load(ctxOf());
    expect(a.calls.filter((c) => c.url.includes('/delivery-risks')).length, '第二次确实发了新请求').toBe(before + 1);
    expect(L.T.s.risk.state).toBe('ok');
    expect(L.T.s.risk.count).toBe(2);
  });

  it('阳性对照：没有超时（时限拉到极大）时，挂起的请求会让后续 load() 一直复用同一个 inflight、不发新请求', async () => {
    const a = makeGet({ '/delivery-risks': () => NEVER() });
    const L = createTodayLoader({ get: a.get as never, timeoutMs: 2 ** 31 - 1 });
    const first = L.load(ctxOf());
    await vi.advanceTimersByTimeAsync(READ_TIMEOUT_MS * 3);
    const n = a.calls.length;
    const second = L.load(ctxOf());
    expect(second, '被挂起的请求拖住').toBe(first);
    expect(a.calls.length).toBe(n);
    expect(L.T.busy).toBe(true);
  });

  it('报废审批资格读取挂起：同样限时，approve 显示读取失败而不是半个数', async () => {
    const a = makeGet();
    const L = createTodayLoader({ get: a.get as never });
    const p = L.load(ctxOf({ disposalCanApprove: () => NEVER() }));
    await vi.advanceTimersByTimeAsync(READ_TIMEOUT_MS + 1);
    await p;
    expect(L.T.s.approve.state).toBe('error');
    expect(L.T.s.approve.count).toBe(0);
    expect(L.T.s.mine.state).toBe('ok');
  });
});

describe('G-2：页签角标只在读到数时画', () => {
  it('读到数画；失败 / 不适用 / 没有来源（等我确认）都是 null，而不是「—」或 0', async () => {
    const a = makeGet({ '/workflow/instances/my-created': () => Promise.reject(new Error('boom')) });
    const L = createTodayLoader({ get: a.get as never });
    await L.load(ctxOf({ canRead: (m) => !(m.length === 1 && m[0] === 'sales') }));
    expect(tabBadge('approve', L.T.s)).toBe(7);
    expect(tabBadge('mine', L.T.s), '读取失败').toBeNull();
    expect(L.T.s.mine.note).toContain('读取失败：boom');
    expect(tabBadge('confirm', L.T.s), '没有来源').toBeNull();
    expect(TODO_TABS.find((t) => t.id === 'confirm')?.reason).toContain('P8');
    // 业务待办：销售订单角标不适用不参与；其余 ok ⇒ 2 + 2 + 2 + 4
    expect(L.T.s.salesOrders.state).toBe('na');
    expect(tabBadge('work', L.T.s)).toBe(2 + 2 + 2 + 4);
    expect(tabBadge('cc', L.T.s)).toBe(5 + 1);
  });

  it('多来源页签里有一路失败 ⇒ 总数不可信 ⇒ 不画；全部不适用 ⇒ 不画', async () => {
    const a = makeGet({ '/warehouse/receiving/tasks': () => Promise.reject(new Error('503')) });
    const L = createTodayLoader({ get: a.get as never });
    await L.load(ctxOf());
    expect(tabBadge('work', L.T.s)).toBeNull();
    const b = makeGet();
    const L2 = createTodayLoader({ get: b.get as never });
    await L2.load(ctxOf({ canRead: () => false }));
    expect(tabBadge('work', L2.T.s)).toBeNull();
    expect(tabBadge('assigned', L2.T.s)).toBeNull();
  });

  it('SRC_IDS 与页签来源互相覆盖（没有来源游离在页签之外）', () => {
    const inTabs = new Set(TODO_TABS.flatMap((t) => t.sources));
    for (const id of SRC_IDS) expect(inTabs.has(id), id).toBe(true);
  });
});
