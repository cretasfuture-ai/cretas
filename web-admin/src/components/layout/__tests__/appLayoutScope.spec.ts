import { afterEach, expect, it, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { defineComponent, h, reactive, ref } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';
import AppLayout from '../AppLayout.vue';
import { useWorkspaceStore } from '@/store/modules/workspace';

const state = vi.hoisted(() => ({ auth: null as null | { factoryId: string; currentRole: string } }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => state.auth }));
vi.mock('@/store/modules/app', () => ({ useAppStore: () => ({ initResponsive() {}, isMobile: false, currentSidebarWidth: 220 }) }));
let wrapper: VueWrapper | undefined;
afterEach(() => wrapper?.unmount());

it('registers the unchanged route in a new factory scope and replaces the old draft', async () => {
  sessionStorage.clear();
  state.auth = reactive({ factoryId: 'LOCAL_A', currentRole: 'factory_super_admin' });
  const Draft = defineComponent({ setup() { const value = ref(''); return () => h('input', {
    value: value.value, onInput: (event: Event) => { value.value = (event.target as HTMLInputElement).value; },
  }); } });
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/draft', component: Draft, meta: { title: '新建草稿' } }] });
  const pinia = createPinia();
  await router.push('/draft'); await router.isReady();
  wrapper = mount(AppLayout, { global: { plugins: [pinia, router], stubs: {
    AppSidebar: true, AppHeader: true, WorkspaceTabs: true, WorkspaceReferencePane: true,
    ReturnBanner: true, InlineCustomerService: true,
  } } });
  await flushPromises();
  await wrapper.get('input').setValue('factory A draft');
  state.auth.factoryId = 'LOCAL_B';
  await flushPromises();
  expect(router.currentRoute.value.path).toBe('/draft');
  expect(useWorkspaceStore(pinia).activeKey).toBe('/draft');
  expect(wrapper.get<HTMLInputElement>('input').element.value).toBe('');
});
