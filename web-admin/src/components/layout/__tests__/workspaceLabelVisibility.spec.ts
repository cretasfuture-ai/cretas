import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
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
