import { beforeEach, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useWorkspaceStore } from '@/store/modules/workspace';

beforeEach(() => { sessionStorage.clear(); setActivePinia(createPinia()); });
it('reorders tabs without changing active task, dirty draft or cache identity, and persists order', () => {
  const store = useWorkspaceStore();
  store.setScope('test');
  for (const path of ['/one/create', '/two', '/three']) store.openRoute({ path, fullPath: path });
  const cache = store.cacheKeyFor('/one/create');
  store.moveTab('/three', '/one/create');
  expect(store.tabs.map(tab => tab.path)).toEqual(['/three', '/one/create', '/two']);
  expect(store.activeKey).toBe('/three');
  expect(store.tabs[1]?.dirty).toBe(true);
  expect(store.cacheKeyFor('/one/create')).toBe(cache);
  setActivePinia(createPinia());
  const restored = useWorkspaceStore();
  restored.setScope('test');
  expect(restored.tabs.map(tab => tab.path)).toEqual(['/three', '/one/create', '/two']);
});
it('ignores invalid and external drag keys', () => {
  const store = useWorkspaceStore();
  store.openRoute({ path: '/one', fullPath: '/one' });
  store.moveTab('external', '/one');
  store.moveTab('/one', 'missing');
  expect(store.tabs.map(tab => tab.path)).toEqual(['/one']);
});
