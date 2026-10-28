/**
 * 角标不许发「当前账号没权限读」的请求; 角标数字来自服务端真实计数, 算不出来就明说「未知」。
 *
 * 背景(2026-08-17, 报工审批页实测): 文员打开 /production/approval, console 里
 * 连续三条 `/sales/orders` 403。那不是审批页发的 —— 是侧边栏角标 store 对所有人
 * 一视同仁地发三个请求, 而文员没有 sales 读权限。
 *
 * 请求带 `_silent: true` 所以不弹 toast, 但 403 照样进 console, 服务端照样被打。
 * ⇒ 判据是「【没发】那个请求」, ⛔ 不是「发了但把错误吞掉」。
 *
 * S6-09: 以前销售订单角标取第一页 500 条再数, 超过 500 条就隐藏。现在走服务端
 * `GET /task-badges/*` 的真实 COUNT; 服务端说 UNKNOWN 或请求失败 ⇒ 「未知」, 不是 0、也不是隐藏。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

const getMock = vi.fn();
const receivingMock = vi.fn();
const canAccessMock = vi.fn();

vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => getMock(...args) }));
vi.mock('@/api/purchaseReceive', () => ({
  getPendingWarehouseReceivingTasks: (...args: unknown[]) => receivingMock(...args),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canAccess: canAccessMock }),
}));

import { useTaskBadgeStore } from '../taskBadges';
import { startVisibleBadgeRefresh } from '@/utils/badgeRefreshTimer';

/** 服务端计数接口的返回: 真实总数。 */
const known = (count: number) => ({ success: true, data: { state: 'KNOWN', count } });
const unknownCount = { success: true, data: { state: 'UNKNOWN', count: null, reason: '计数暂时无法计算' } };

const SALES_URL = '/task-badges/sales-orders';
const INBOUND_URL = '/task-badges/unordered-inbound';
const callsTo = (fragment: string) => getMock.mock.calls.filter((args) => String(args[0]).includes(fragment));

describe('taskBadges — 没权限的模块不发请求', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });
  beforeEach(() => {
    setActivePinia(createPinia());
    getMock.mockReset().mockResolvedValue(known(0));
    receivingMock.mockReset().mockResolvedValue({ success: true, data: [] });
    canAccessMock.mockReset();
  });

  it('没有 sales 读权限 ⇒ 一次销售订单计数都不发', async () => {
    canAccessMock.mockImplementation((m: string) => m !== 'sales');

    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();

    // 判据钉在【那个 URL 上】—— 「get 被调用了几次」不够精确, 别的角标也走同一个 get。
    expect(callsTo(SALES_URL)).toHaveLength(0);

    // 没权限 ⇒ 角标隐藏, 且【不是】「未知」(没发请求, 没有什么算不出来)
    expect(store.badge('salesOrders')).toBeNull();
    expect(store.isUnknown('salesOrders')).toBe(false);

    // 阳性对照: 有权限的那两个照常发 —— 否则这条断言可能只是「整个 loadAll 没跑」
    expect(receivingMock).toHaveBeenCalledTimes(1);
    expect(callsTo(INBOUND_URL)).toHaveLength(1);
  });

  it('有 sales 读权限 ⇒ 照常发, 且角标算得出来', async () => {
    canAccessMock.mockReturnValue(true);
    getMock.mockImplementation(async (url: string) => (String(url).includes(SALES_URL) ? known(2) : known(0)));

    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();

    expect(callsTo(SALES_URL)).toHaveLength(1);
    expect(store.badge('salesOrders')).toBe(2);
  });

  it('S6-09 超过 500 条待办 ⇒ 显示服务端给的真实总数, 不隐藏', async () => {
    canAccessMock.mockReturnValue(true);
    getMock.mockImplementation(async (url: string) => (String(url).includes(SALES_URL) ? known(1234) : known(0)));
    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();

    expect(store.badge('salesOrders')).toBe(1234);
    expect(store.isUnknown('salesOrders')).toBe(false);
    // 不再带 size=500 去取一页订单自己数
    expect(callsTo('/sales/orders')).toHaveLength(0);
  });

  it('S6-09 服务端说算不出来(UNKNOWN) ⇒ 角标是「未知」, 不是 0, 也不是悄悄隐藏', async () => {
    canAccessMock.mockReturnValue(true);
    getMock.mockImplementation(async (url: string) => (String(url).includes(SALES_URL) ? unknownCount : known(3)));
    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();

    expect(store.isUnknown('salesOrders')).toBe(true);
    expect(store.badge('salesOrders')).toBeNull();
    // 阴性对照: 另一个能算出来的照常显示数字, 没有被一起标成未知
    expect(store.badge('unorderedInbound')).toBe(3);
    expect(store.isUnknown('unorderedInbound')).toBe(false);
    // 未知的不计入合计
    expect(store.totalActionable).toBe(3);
  });

  it('S6-09 请求失败 ⇒ 未知; 之后重试成功 ⇒ 恢复成数字', async () => {
    canAccessMock.mockReturnValue(true);
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000);
    getMock.mockImplementation(async (url: string) => {
      if (String(url).includes(SALES_URL)) throw new Error('unavailable');
      return known(0);
    });
    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();
    expect(store.isUnknown('salesOrders')).toBe(true);

    getMock.mockImplementation(async () => known(5));
    now.mockReturnValue(61_001);
    await store.loadAll();
    expect(store.isUnknown('salesOrders')).toBe(false);
    expect(store.badge('salesOrders')).toBe(5);
  });

  it('五分钟内复用角标结果，业务写入通知和切换工厂仍立即刷新', async () => {
    canAccessMock.mockReturnValue(true);
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000);
    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();
    await store.loadAll();
    expect(callsTo(SALES_URL)).toHaveLength(1);
    expect(receivingMock).toHaveBeenCalledTimes(1);

    await store.loadAll(true);
    expect(callsTo(SALES_URL)).toHaveLength(2);

    now.mockReturnValue(301_001);
    await store.loadAll();
    expect(callsTo(SALES_URL)).toHaveLength(3);

    store.setFactory('F007');
    await store.loadAll();
    expect(getMock).toHaveBeenCalledWith('/F007/task-badges/sales-orders', expect.anything());
  });

  it('刷新通知到达正在加载的角标时，完成后再取一次新值', async () => {
    canAccessMock.mockReturnValue(true);
    let resolveSales!: (value: ReturnType<typeof known>) => void;
    getMock.mockImplementationOnce(() => new Promise((resolve) => { resolveSales = resolve; }));
    const store = useTaskBadgeStore();
    store.setFactory('F006');
    const first = store.loadAll();
    await store.loadAll(true);
    resolveSales(known(0));
    await first;
    await vi.waitFor(() => expect(callsTo(SALES_URL)).toHaveLength(2));
  });

  it('失败的角标标为未知并在一分钟后重试，避免切页时反复请求', async () => {
    canAccessMock.mockReturnValue(true);
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000);
    getMock.mockRejectedValue(new Error('unavailable'));
    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();
    expect(store.badge('salesOrders')).toBeNull();
    expect(store.isUnknown('salesOrders')).toBe(true);
    await store.loadAll();
    expect(callsTo(SALES_URL)).toHaveLength(1);
    now.mockReturnValue(61_001);
    await store.loadAll();
    expect(callsTo(SALES_URL)).toHaveLength(2);
  });

  it('可见侧栏在首次加载后六分钟内刷新，隐藏时暂停', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-28T00:00:00Z'));
    canAccessMock.mockReturnValue(true);
    const store = useTaskBadgeStore();
    store.setFactory('F006');
    let visible = true;
    const stop = startVisibleBadgeRefresh(() => { void store.loadAll(); }, () => visible);
    await vi.advanceTimersByTimeAsync(10_000); // Initial response lands after the timer starts.
    await store.loadAll();
    expect(callsTo(SALES_URL)).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(5 * 60_000 - 10_000);
    expect(callsTo(SALES_URL)).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(callsTo(SALES_URL)).toHaveLength(2);
    visible = false;
    await vi.advanceTimersByTimeAsync(5 * 60_000);
    expect(callsTo(SALES_URL)).toHaveLength(2);
    stop();
  });

  it('可见侧栏的失败请求在一分钟后自动重试', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-09-28T00:00:00Z'));
    canAccessMock.mockReturnValue(true);
    getMock.mockRejectedValue(new Error('unavailable'));
    const store = useTaskBadgeStore();
    store.setFactory('F006');
    const stop = startVisibleBadgeRefresh(() => { void store.loadAll(); }, () => true);
    await store.loadAll();
    expect(callsTo(SALES_URL)).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(callsTo(SALES_URL)).toHaveLength(2);
    stop();
  });

  it('切换工厂时丢弃旧请求结果并立即读取新工厂', async () => {
    canAccessMock.mockReturnValue(true);
    let resolveOld!: (value: ReturnType<typeof known>) => void;
    getMock.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }));
    getMock.mockResolvedValue(known(1));
    const store = useTaskBadgeStore();
    store.setFactory('F006');
    const oldLoad = store.loadAll();
    store.setFactory('F007');
    await store.loadAll();
    resolveOld(known(2));
    await oldLoad;
    expect(store.badge('salesOrders')).toBe(1);
    expect(getMock).toHaveBeenCalledWith('/F007/task-badges/sales-orders', expect.anything());
  });

  /**
   * 🔴 M-13 (2026-08-20 prod 实测)
   *
   * 财务主管打开任何页面, console 里刷几十条 `/warehouse/receiving/tasks` 403。
   * 根因是守卫查错了模块: 守卫写 procurement, 接口要 warehouse:read_write / warehouse:read / inventory:write。
   * ⇒ 这两条断言钉的是「守卫的模块必须和接口要求的对上」。
   */
  it('M-13 财务主管(有 procurement / 无 warehouse+inventory) ⇒ 一次待收货任务都不发', async () => {
    // sales 给 true 仅作阳性对照 —— 否则全不发时, 断言可能只是「loadAll 根本没跑」。
    canAccessMock.mockImplementation(
      (m: string) => m === 'procurement' || m === 'finance' || m === 'sales');

    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();

    expect(receivingMock).not.toHaveBeenCalled();
    expect(store.badge('warehouseReceiving')).toBeNull();

    // 阳性对照: 有权限的 sales 照常发 ⇒ 证明 loadAll 真的跑到了这一步
    expect(callsTo(SALES_URL)).toHaveLength(1);
  });

  /**
   * ⚠️ 这条防的是「修过头」: 接口是 warehouse **或** inventory 任一即可,
   * 若守卫只查 warehouse, 只有 inventory 权限的角色会被白白挡住。
   */
  it('M-13 只有 inventory 没有 warehouse ⇒ 待收货任务**照常发**(后端是「任一」)', async () => {
    canAccessMock.mockImplementation((m: string) => m === 'inventory');

    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();

    expect(receivingMock).toHaveBeenCalledTimes(1);
  });
});
