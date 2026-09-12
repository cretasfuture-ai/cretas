import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { nextTick } from 'vue';
import WorkspaceTabs from '../WorkspaceTabs.vue';
import { useWorkspaceStore } from '@/store/modules/workspace';

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
let wrapper: VueWrapper;
beforeEach(() => { sessionStorage.clear(); setActivePinia(createPinia()); });
afterEach(() => wrapper?.unmount());

function render(title: string, base?: string) {
  const query: Record<string, string> = { _task: 'copy', _taskLabel: title };
  if (base) query._taskBaseTitle = base;
  const store = useWorkspaceStore();
  store.openRoute({ path: '/sales/orders', fullPath: '/sales/orders?' + new URLSearchParams(query), query, title: '销售订单' });
  wrapper = mount(WorkspaceTabs, { global: { stubs: { ElTooltip: { template: '<span><slot /></span>' }, ElButton: true } } });
}

it('keeps the generated ordinal separate from ellipsized text without changing the complete label', () => {
  render('产线 2 3', '产线 2');
  expect(wrapper.get('.workspace-tab-text').text()).toBe('产线 2');
  expect(wrapper.get('.workspace-tab-number').text()).toBe('3');
  expect(wrapper.get('.workspace-tab-title').text()).toBe('产线 2 3');
});

it('does not mistake meaningful trailing digits or custom labels for a generated ordinal', () => {
  render('产线 2');
  expect(wrapper.find('.workspace-tab-number').exists()).toBe(false);
  expect(wrapper.get('.workspace-tab-title').text()).toBe('产线 2');
});

it('numbers the original only while its own copies exist without changing its route, key or draft', async () => {
  const store = useWorkspaceStore();
  store.openRoute({ path: '/sales/orders', fullPath: '/sales/orders', title: '产线 2' });
  store.markDirty('/sales/orders', true);
  const before = { ...store.tabs[0] };
  wrapper = mount(WorkspaceTabs, { global: { stubs: { ElTooltip: { template: '<span><slot /></span>' }, ElButton: true } } });
  expect(wrapper.get('.workspace-tab-title').text()).toBe('产线 2');
  const target = store.duplicateRoute()!;
  store.openRoute({ path: target.path, fullPath: target.path + '?' + new URLSearchParams(target.query), query: target.query });
  await nextTick();
  expect(wrapper.findAll('.workspace-tab-title').map(node => node.text())).toEqual(['产线 2 1', '产线 2 2']);
  expect(wrapper.findAll('.workspace-tab-number').map(node => node.text())).toEqual(['1', '2']);
  expect(wrapper.get('.workspace-tab-container').attributes('title')).toContain('产线 2 1 ·');
  expect(store.tabs[0]).toEqual(before);
  store.closeTab(store.tabs[1]!.key);
  await nextTick();
  expect(wrapper.get('.workspace-tab-title').text()).toBe('产线 2');
  expect(store.tabs[0]).toEqual(before);
});

it('does not number an unrelated route with the same title', () => {
  const store = useWorkspaceStore();
  store.openRoute({ path: '/warehouse', fullPath: '/warehouse', title: '产线 2' });
  render('产线 2 3', '产线 2');
  expect(wrapper.findAll('.workspace-tab-title')[0]!.text()).toBe('产线 2');
});

it('does not treat malformed copy metadata as a numbered sibling', () => {
  const store = useWorkspaceStore();
  store.openRoute({ path: '/sales/orders', fullPath: '/sales/orders', title: '产线 2' });
  render('产线 2 copy', '产线 2');
  expect(wrapper.findAll('.workspace-tab-title')[0]!.text()).toBe('产线 2');
});
