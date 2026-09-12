import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useWorkspaceStore } from '../workspace';

describe('sidebar fresh workspace instances', () => {
  beforeEach(() => { sessionStorage.clear(); setActivePinia(createPinia()); });
  const menu = { path: '/sales/orders', fullPath: '/sales/orders?status=DRAFT', title: '销售订单' };

  it('uses the normal menu URL on first open, including its explicit filter', () => {
    expect(useWorkspaceStore().menuRoute(menu)).toBe(menu.fullPath);
  });

  it('opens an independent task using menu defaults without touching the original draft or reference', () => {
    const store = useWorkspaceStore();
    store.openRoute({ ...menu, fullPath: '/sales/orders?status=PROCESSING&edit=1' });
    store.markDirty(menu.path, true);
    store.pinReference(menu.path);
    const original = { ...store.tabs[0] };
    store.openRoute({ path: '/warehouse/materials', fullPath: '/warehouse/materials', title: '原料库存' });
    const target = store.menuRoute(menu);
    expect(target).toMatchObject({ path: menu.path, query: { status: 'DRAFT', _taskBaseTitle: menu.title, _taskLabel: '销售订单 2' } });
    if (typeof target === 'string') throw new Error('Expected new task');
    expect(target.query.edit).toBeUndefined();
    expect(target.query._task).toBeTruthy();
    expect(store.tabs[0]).toEqual(original);
    expect(store.referenceKey).toBe(menu.path);
    expect(store.activeKey).toBe('/warehouse/materials');
  });

  it('allocates distinct labels and identities across repeated menu opens and an absent original', () => {
    const store = useWorkspaceStore();
    store.openRoute(menu);
    const keys = new Set([store.activeKey]);
    for (const ordinal of [2, 3, 4]) {
      const target = store.menuRoute(menu);
      if (typeof target === 'string') throw new Error('Expected new task');
      expect(target.query._taskLabel).toBe(`销售订单 ${ordinal}`);
      store.openRoute({ ...target, fullPath: target.path + '?' + new URLSearchParams(target.query) });
      expect(keys.has(store.activeKey)).toBe(false);
      keys.add(store.activeKey);
      if (ordinal === 2) store.closeTab(menu.path);
    }
  });
});
