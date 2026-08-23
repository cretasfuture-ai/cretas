/**
 * 角标不许发「当前账号没权限读」的请求。
 *
 * 背景(2026-08-17, 报工审批页实测): 文员打开 /production/approval, console 里
 * 连续三条 `/sales/orders` 403。那不是审批页发的 —— 是侧边栏角标 store 对所有人
 * 一视同仁地发三个请求, 而文员没有 sales 读权限。
 *
 * 请求带 `_silent: true` 所以不弹 toast, 但 403 照样进 console, 服务端照样被打。
 * ⇒ 判据是「【没发】那个请求」, ⛔ 不是「发了但把错误吞掉」。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';

const getMock = vi.fn();
const receivingMock = vi.fn();
const arrivalsMock = vi.fn();
const canAccessMock = vi.fn();

vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => getMock(...args) }));
vi.mock('@/api/purchaseReceive', () => ({
  getPendingWarehouseReceivingTasks: (...args: unknown[]) => receivingMock(...args),
}));
vi.mock('@/api/customerMaterialArrival', () => ({
  listCustomerMaterialArrivals: (...args: unknown[]) => arrivalsMock(...args),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canAccess: canAccessMock }),
}));

import { useTaskBadgeStore } from '../taskBadges';

const okPage = (content: unknown[]) => ({
  success: true,
  data: { content, totalElements: content.length },
});

describe('taskBadges — 没权限的模块不发请求', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    getMock.mockReset().mockResolvedValue(okPage([]));
    receivingMock.mockReset().mockResolvedValue({ success: true, data: [] });
    arrivalsMock.mockReset().mockResolvedValue({ success: true, data: [] });
    canAccessMock.mockReset();
  });

  it('没有 sales 读权限 ⇒ 一次 /sales/orders 都不发', async () => {
    canAccessMock.mockImplementation((m: string) => m !== 'sales');

    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();

    // 判据钉在【那个 URL 上】—— 「get 被调用了几次」不够精确,
    // 别的角标也走同一个 get。
    const salesCalls = getMock.mock.calls.filter(
      (args) => String(args[0]).includes('/sales/orders'));
    expect(salesCalls).toHaveLength(0);

    // 角标本身仍然是隐藏的(fail-closed), 与「请求失败」时的观感一致
    expect(store.badge('salesOrders')).toBeNull();

    // 阳性对照: 有权限的那两个照常发 —— 否则这条断言可能只是「整个 loadAll 没跑」
    expect(receivingMock).toHaveBeenCalledTimes(1);
    expect(arrivalsMock).toHaveBeenCalledTimes(1);
  });

  it('有 sales 读权限 ⇒ 照常发, 且角标算得出来', async () => {
    canAccessMock.mockReturnValue(true);
    getMock.mockResolvedValue(okPage([
      { status: 'PENDING' },
      { status: 'COMPLETED' }, // 终态, 不计入
      { status: 'CONFIRMED' },
    ]));

    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();

    const salesCalls = getMock.mock.calls.filter(
      (args) => String(args[0]).includes('/sales/orders'));
    expect(salesCalls).toHaveLength(1);
    expect(store.badge('salesOrders')).toBe(2);
  });

  /**
   * 🔴 M-13 (2026-08-20 prod 实测, 本轮第一条阻塞)
   *
   * 财务主管 `f006_finance_mgr` 打开**任何**页面(经营驾驶舱、智能数据分析都算),
   * console 里刷几十条 `/warehouse/receiving/tasks` 403, 前端还弹「操作无法完成」——
   * **让财务以为是自己的审批操作失败了**, 那张待审采购单他因此打不开、审不了。
   *
   * 根因不是「没配权限」, 是**守卫查错了模块**:
   *   守卫写的   BADGE_MODULE.warehouseReceiving = 'procurement'
   *   接口要的   @RequirePermission({"warehouse:read_write","warehouse:read","inventory:write"})
   * 财务有 procurement 读权限 ⇒ 守卫放行 ⇒ 发请求 ⇒ 接口要 warehouse ⇒ 403。
   *
   * ⇒ 这两条断言钉的是「守卫的模块必须和接口要求的对上」。
   */
  it('M-13 财务主管(有 procurement / 无 warehouse+inventory) ⇒ 一次待收货任务都不发', async () => {
    // 财务主管的真实权限形状: 有 finance/procurement, 没有 warehouse/inventory。
    // sales 给 true 仅作阳性对照 —— 否则三个全不发时, 断言可能只是「loadAll 根本没跑」。
    canAccessMock.mockImplementation(
      (m: string) => m === 'procurement' || m === 'finance' || m === 'sales');

    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();

    expect(receivingMock).not.toHaveBeenCalled();
    expect(store.badge('warehouseReceiving')).toBeNull();

    // 阳性对照: 有权限的 sales 照常发 ⇒ 证明 loadAll 真的跑到了这一步
    const salesCalls = getMock.mock.calls.filter(
      (args) => String(args[0]).includes('/sales/orders'));
    expect(salesCalls).toHaveLength(1);
  });

  /**
   * ⚠️ 这条防的是「修过头」: 接口是 warehouse **或** inventory 任一即可,
   * 若守卫只查 warehouse, 只有 inventory 权限的角色会被白白挡住 ——
   * 那是把 403 换成了「功能消失」, 同样是错的。
   */
  it('M-13 只有 inventory 没有 warehouse ⇒ 待收货任务**照常发**(后端是「任一」)', async () => {
    canAccessMock.mockImplementation((m: string) => m === 'inventory');

    const store = useTaskBadgeStore();
    store.setFactory('F006');
    await store.loadAll();

    expect(receivingMock).toHaveBeenCalledTimes(1);
  });
});
