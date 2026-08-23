import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useWorkspaceStore, WORKSPACE_STALE_AFTER_MS } from '@/store/modules/workspace';

/**
 * 🔴 2026-08-20 owner 实测:「我在个人 OA 里点击通过以后, 再点到财务管理里面, 还是待审批;
 * 到仓储管理的入库任务与批次, 点进去没有任何东西。但我刷新了一下网页, 它就更新好了。」
 *
 * 根因与 2026-08-19 那两条同源: {@code <keep-alive>} 缓存住组件实例, 切回标签只触发
 * activated, ⛔ 不二次 onMounted, 而全仓 353 个取数页面里 351 个只在 onMounted 取数。
 *
 * ⚠️ 这些断言守的是**行为**, 每条正向断言都配了一条能让它红的阴性对照 ——
 * 「切回来会重新取数」和「不该重取的别重取」必须同时成立, 只满足一条就是把一个 bug
 * 换成另一个 (无脑全量重取 = 10 个标签来回切就打 10 轮接口)。
 */
function open(
  store: ReturnType<typeof useWorkspaceStore>,
  path: string,
  module: string,
  query: Record<string, string> = {},
) {
  const search = new URLSearchParams(query).toString();
  store.openRoute({
    path,
    fullPath: search ? `${path}?${search}` : path,
    title: path.split('/').filter(Boolean).pop() || '',
    module,
    query,
  });
}

describe('写操作驱动的标签失效 (方案 A)', () => {
  beforeEach(() => {
    sessionStorage.clear();
    setActivePinia(createPinia());
  });

  it('批完之后, 受影响业务域的其它标签换了缓存键 -> 切回去会重新 mount 取数', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/finance/costs', 'finance');
    open(store, '/warehouse/materials', 'warehouse');
    open(store, '/workflow/pending', 'dashboard');   // 停在 OA 页, 这里点「通过」

    const financeBefore = store.cacheKeyFor('/finance/costs');
    const warehouseBefore = store.cacheKeyFor('/warehouse/materials');

    store.invalidateModules(['dashboard', 'procurement', 'finance', 'warehouse']);

    expect(store.cacheKeyFor('/finance/costs')).not.toBe(financeBefore);
    expect(store.cacheKeyFor('/warehouse/materials')).not.toBe(warehouseBefore);
  });

  it('阴性对照: 不相干业务域的标签一动不动 (⛔ 不是把所有标签清一遍)', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/hr/employees', 'hr');
    open(store, '/workflow/pending', 'dashboard');

    const hrBefore = store.cacheKeyFor('/hr/employees');
    store.invalidateModules(['dashboard', 'procurement', 'finance', 'warehouse']);

    expect(store.cacheKeyFor('/hr/employees')).toBe(hrBefore);
  });

  it('阴性对照: 当前标签自己不动 —— 发起写操作的页面自己会刷 (loadPending)', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/finance/costs', 'finance');
    open(store, '/workflow/pending', 'dashboard');

    const activeBefore = store.cacheKeyFor('/workflow/pending');
    store.invalidateModules(['dashboard', 'finance']);

    expect(store.activeKey).toBe('/workflow/pending');
    expect(store.cacheKeyFor('/workflow/pending')).toBe(activeBefore);
  });

  it('阴性对照: 正在填的脏标签不动 —— 重挂载会吞掉用户打的字', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/finance/payment-requests', 'finance', { create: '1' });
    const dirtyKey = '/finance/payment-requests';
    expect(store.tabs.find((tab) => tab.key === dirtyKey)?.dirty).toBe(true);
    open(store, '/workflow/pending', 'dashboard');

    const before = store.cacheKeyFor(dirtyKey);
    store.invalidateModules(['dashboard', 'finance']);

    expect(store.cacheKeyFor(dirtyKey)).toBe(before);
  });

  it('阴性对照: 没记到 module 的标签不动 (走时间兜底, ⛔ 不顺手清掉)', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/legacy/page', '');
    open(store, '/workflow/pending', 'dashboard');

    const before = store.cacheKeyFor('/legacy/page');
    store.invalidateModules(['dashboard', 'finance', 'warehouse', '']);

    expect(store.cacheKeyFor('/legacy/page')).toBe(before);
  });

  it('module 记的是路由 meta.module —— AppLayout 不传它, 这里就一个都匹配不到', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/finance/costs', 'finance');
    expect(store.tabs.find((tab) => tab.key === '/finance/costs')?.module).toBe('finance');
  });
});

describe('时间兜底失效 (方案 B)', () => {
  beforeEach(() => {
    sessionStorage.clear();
    setActivePinia(createPinia());
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-20T09:00:00Z'));
  });
  afterEach(() => vi.useRealTimers());

  it('切回一个放了很久的标签 -> 缓存键变了, 重新取数', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/finance/costs', 'finance');
    const before = store.cacheKeyFor('/finance/costs');
    open(store, '/workflow/pending', 'dashboard');   // 切走

    vi.setSystemTime(Date.now() + WORKSPACE_STALE_AFTER_MS + 1_000);
    open(store, '/finance/costs', 'finance');        // 切回

    expect(store.cacheKeyFor('/finance/costs')).not.toBe(before);
  });

  it('阴性对照: 刚看过就切回来 -> 键不变 (⛔ 不是每次切标签都重取)', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/finance/costs', 'finance');
    const before = store.cacheKeyFor('/finance/costs');
    open(store, '/workflow/pending', 'dashboard');

    vi.setSystemTime(Date.now() + 5_000);
    open(store, '/finance/costs', 'finance');

    expect(store.cacheKeyFor('/finance/costs')).toBe(before);
  });

  it('阴性对照: 同一个标签内翻页/改筛选不算「切回来」, 隔多久都不重挂载', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/finance/costs', 'finance');
    const before = store.cacheKeyFor('/finance/costs');

    vi.setSystemTime(Date.now() + WORKSPACE_STALE_AFTER_MS * 10);
    open(store, '/finance/costs', 'finance', { page: '3' });   // 同一个 tab key

    expect(store.cacheKeyFor('/finance/costs')).toBe(before);
  });

  it('阴性对照: 脏标签放再久也不重挂载', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/sales/orders', 'sales', { create: '1' });
    const before = store.cacheKeyFor('/sales/orders');
    open(store, '/workflow/pending', 'dashboard');

    vi.setSystemTime(Date.now() + WORKSPACE_STALE_AFTER_MS * 10);
    open(store, '/sales/orders', 'sales', { create: '1' });

    expect(store.cacheKeyFor('/sales/orders')).toBe(before);
  });

  it('重新 mount 之后计时归零 —— 立刻再切走切回不会又重挂载一次', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/finance/costs', 'finance');
    open(store, '/workflow/pending', 'dashboard');

    vi.setSystemTime(Date.now() + WORKSPACE_STALE_AFTER_MS + 1_000);
    open(store, '/finance/costs', 'finance');
    const afterRefresh = store.cacheKeyFor('/finance/costs');

    open(store, '/workflow/pending', 'dashboard');
    vi.setSystemTime(Date.now() + 5_000);
    open(store, '/finance/costs', 'finance');

    expect(store.cacheKeyFor('/finance/costs')).toBe(afterRefresh);
  });
});
