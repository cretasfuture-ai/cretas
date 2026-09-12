import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useWorkspaceStore } from '../workspace';

describe('workspace duplicate identity and labels', () => {
  beforeEach(() => {
    sessionStorage.clear();
    setActivePinia(createPinia());
    vi.restoreAllMocks();
  });

  function openOriginal(title = '销售订单') {
    const store = useWorkspaceStore();
    store.setScope('LOCAL');
    store.openRoute({ path: '/sales/orders', fullPath: '/sales/orders?status=DRAFT', title });
    return store;
  }

  function duplicate(store: ReturnType<typeof useWorkspaceStore>) {
    const target = store.duplicateRoute()!;
    store.openRoute({ ...target, fullPath: target.path + '?' + new URLSearchParams(target.query), title: '销售订单' });
    return store.activeTab!;
  }

  it('does not append another number when copying a copy', () => {
    const store = openOriginal();
    expect(duplicate(store).title).toBe('销售订单 2');
    expect(duplicate(store).title).toBe('销售订单 3');
    expect(new URL(store.activeTab!.fullPath, 'https://local.test').searchParams.get('status')).toBe('DRAFT');
  });

  it('does not reuse an occupied number after closing a middle tab', () => {
    const store = openOriginal();
    const second = duplicate(store);
    duplicate(store);
    store.closeTab(second.key);
    store.openRoute({ path: '/sales/orders', fullPath: '/sales/orders?status=DRAFT', title: '销售订单' });
    duplicate(store);
    expect(new Set(store.tabs.map(tab => tab.title)).size).toBe(store.tabs.length);
  });

  it('keeps independent cache identities for copies created within the same millisecond', () => {
    vi.spyOn(Date, 'now').mockReturnValue(1000);
    const store = openOriginal();
    const original = store.activeTab!;
    store.markDirty(original.key, true);
    const second = duplicate(store);
    const third = duplicate(store);
    expect(store.tabs).toHaveLength(3);
    expect(new Set([original, second, third].map(tab => store.cacheKeyFor(tab.key))).size).toBe(3);
    expect(store.tabs[0].dirty).toBe(true);
  });

  it('preserves a meaningful title ending in a number across copies and reload', () => {
    const store = openOriginal('产线 2');
    expect(duplicate(store).title).toBe('产线 2 2');
    setActivePinia(createPinia());
    const restored = useWorkspaceStore();
    restored.setScope('LOCAL');
    const last = restored.tabs.at(-1)!;
    const url = new URL(last.fullPath, 'https://local.test');
    restored.openRoute({ path: last.path, fullPath: last.fullPath, title: '销售订单', query: Object.fromEntries(url.searchParams) });
    expect(duplicate(restored).title).toBe('产线 2 3');
  });
});
