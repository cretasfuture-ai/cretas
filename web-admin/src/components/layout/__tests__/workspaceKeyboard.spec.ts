import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import WorkspaceTabs from '../WorkspaceTabs.vue';
import { useWorkspaceStore } from '@/store/modules/workspace';

const { push } = vi.hoisted(() => ({ push: vi.fn().mockResolvedValue(undefined) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }));

let wrapper: VueWrapper;
beforeEach(() => { sessionStorage.clear(); setActivePinia(createPinia()); push.mockClear(); });
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = ''; });

function openTabs() {
  const store = useWorkspaceStore();
  for (const path of ['/sales/orders', '/sales/orders/new', '/production/plans']) {
    store.openRoute({ path, fullPath: path, title: path });
  }
  wrapper = mount(WorkspaceTabs, {
    attachTo: document.body,
    global: { stubs: { ElTooltip: { template: '<span><slot /></span>' }, ElButton: { template: '<button><slot /></button>' } } },
  });
  return store;
}

describe('workspace close keyboard and focus contract', () => {
  it('uses native sibling buttons so Space and Enter share browser activation', () => {
    openTabs();
    expect(wrapper.findAll('.workspace-tab-close').every(button => button.element.tagName === 'BUTTON')).toBe(true);
    expect(wrapper.findAll('button button, button [role="button"]')).toHaveLength(0);
  });

  it('closes the active task and focuses the surviving active task after navigation', async () => {
    const store = openTabs();
    const close = wrapper.findAll('.workspace-tab-close')[2]!;
    (close.element as HTMLButtonElement).focus();
    await close.trigger('click'); await flushPromises();
    expect(store.tabs.map(tab => tab.key)).toEqual(['/sales/orders', '/sales/orders/new']);
    expect(push).toHaveBeenCalledExactlyOnceWith('/sales/orders/new');
    expect((document.activeElement as HTMLElement).dataset.workspaceKey).toBe('/sales/orders/new');
  });

  it('closing an inactive task preserves active navigation and surviving draft/cache', async () => {
    const store = openTabs();
    const draftKey = '/sales/orders/new';
    store.markDirty(draftKey, true);
    const cache = store.cacheKeyFor(draftKey);
    await wrapper.findAll('.workspace-tab-close')[0]!.trigger('click'); await flushPromises();
    expect(push).not.toHaveBeenCalled();
    expect(store.activeKey).toBe('/production/plans');
    expect((document.activeElement as HTMLElement).dataset.workspaceKey).toBe('/production/plans');
    expect(store.tabs.find(tab => tab.key === draftKey)?.dirty).toBe(true);
    expect(store.cacheKeyFor(draftKey)).toBe(cache);
  });

  it('does not offer a close control for the last remaining task', async () => {
    openTabs();
    await wrapper.findAll('.workspace-tab-close')[2]!.trigger('click'); await flushPromises();
    await wrapper.findAll('.workspace-tab-close')[1]!.trigger('click'); await flushPromises();
    expect(wrapper.findAll('.workspace-tab-close')).toHaveLength(0);
    expect(document.activeElement).toBe(wrapper.get('.workspace-tab').element);
  });
});
