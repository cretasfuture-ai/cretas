import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useWorkspaceStore, workspaceRouteKey } from '@/store/modules/workspace';

/**
 * 2026-08-19 客户实报的两条, 是同一个根因的两面:
 *   (3) 「关闭标签无法彻底刷新, 必须刷新浏览器」 —— 缓存太黏
 *   (4) 「切换标签导致未保存信息丢失」           —— 缓存被无声销毁
 * 根因: <keep-alive> 的缓存键是 route.path, 与标签生命周期完全脱钩。
 *
 * ⚠️ 这些断言守的是**行为**, 不是某一行实现:
 *    - 关过之后键必须变 (否则拿回旧实例, onMounted 不再执行)
 *    - 没关过时键必须不变 (否则每次切标签都重新 mount, 输入照样丢)
 *    两条必须同时成立 —— 只满足一条就是把一个 bug 换成另一个。
 */
function open(store: ReturnType<typeof useWorkspaceStore>, path: string, query: Record<string, string> = {}) {
  const search = new URLSearchParams(query).toString();
  store.openRoute({
    path,
    fullPath: search ? `${path}?${search}` : path,
    title: path.split('/').filter(Boolean).pop() || '',
    query,
  });
}

describe('workspace tab lifecycle drives the keep-alive cache identity', () => {
  beforeEach(() => {
    sessionStorage.clear();
    setActivePinia(createPinia());
  });

  it('(3) 关掉标签再打开 -> 缓存键变了, 组件会重新 mount', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/warehouse/stocktakes');
    open(store, '/sales/orders');

    const key = workspaceRouteKey({ path: '/warehouse/stocktakes', query: {} });
    const before = store.cacheKeyFor(key);

    store.closeTab(key);
    open(store, '/warehouse/stocktakes');
    const after = store.cacheKeyFor(key);

    expect(after).not.toBe(before);
  });

  it('(4) 只是来回切换 -> 缓存键不变, 已输入的内容留在缓存实例里', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/sales/orders', { create: '1' });
    const key = workspaceRouteKey({ path: '/sales/orders', query: { create: '1' } });
    const before = store.cacheKeyFor(key);

    open(store, '/warehouse/stocktakes');   // 切走
    open(store, '/sales/orders', { create: '1' });  // 切回

    expect(store.cacheKeyFor(key)).toBe(before);
  });

  it('没关过的标签, 键与改动前逐字相同 (不给存量行为引入变化)', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/sales/orders');
    expect(store.cacheKeyFor('/sales/orders')).toBe('/sales/orders');
  });

  it('(4) 标签数超上限时, 先挤掉干净的那个, 不动正在编辑的', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    // 第 1 个是正在新建的脏标签, 后面全是干净的列表页
    open(store, '/sales/orders', { create: '1' });
    const dirtyKey = workspaceRouteKey({ path: '/sales/orders', query: { create: '1' } });
    expect(store.tabs.find((t) => t.key === dirtyKey)?.dirty).toBe(true);

    for (let i = 0; i < 12; i += 1) open(store, `/filler/page-${i}`);

    expect(store.tabs.some((tab) => tab.key === dirtyKey)).toBe(true);
  });

  it('阳性对照: 干净标签确实会被挤掉 (证明上一条不是因为根本没发生挤压)', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/clean/first');
    for (let i = 0; i < 12; i += 1) open(store, `/filler/page-${i}`);
    expect(store.tabs.some((tab) => tab.key === '/clean/first')).toBe(false);
  });

  it('被挤掉的标签同样作废缓存 —— 否则它重开时又拿回旧实例', () => {
    const store = useWorkspaceStore();
    store.setScope('F006');
    open(store, '/clean/first');
    const before = store.cacheKeyFor('/clean/first');
    for (let i = 0; i < 12; i += 1) open(store, `/filler/page-${i}`);
    expect(store.cacheKeyFor('/clean/first')).not.toBe(before);
  });
});
