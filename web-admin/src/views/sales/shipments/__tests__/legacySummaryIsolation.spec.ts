import { mount, flushPromises } from '@vue/test-utils';
import { reactive } from 'vue';
import { beforeEach, it, expect, vi } from 'vitest';
import ElementPlus from 'element-plus';
import Page from '../list.vue';
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
const auth = reactive({ factoryId: 'A' });
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: true }) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => api.get(...args), post: (...args: unknown[]) => api.post(...args) }));
vi.mock('@/components/dialog/CreateReturnOrderDialog.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/api/printApi', () => ({ safePrint: vi.fn() }));
vi.mock('@/composables/useRowActions', () => ({ computeRowActions: () => [] }));
const summary = (label: string, value: number) => ({ success: true, data: { stats: [{ label, value, format: 'number' }] } });
beforeEach(() => {
  auth.factoryId = 'A'; api.post.mockReset(); api.get.mockReset();
  api.get.mockImplementation(async (url: string) => ({ success: true, data: { content: url.endsWith('/shipments') ? [{ id: url, shipmentNumber: 'RECORD-' + url.split('/')[1], status: 'SHIPPED' }] : [], totalElements: 1 } }));
});
async function legacy() {
  const wrapper = mount(Page, { global: { plugins: [ElementPlus] } });
  await wrapper.findAll('[role="tab"]').find(tab => tab.text().includes('历史'))!.trigger('click');
  await flushPromises(); return wrapper;
}
it('keeps real B TableFooter after pending A summary arrives late', async () => {
  let finish!: (value: unknown) => void;
  api.post.mockImplementation((url: string) => url.includes('/A/') ? new Promise(resolve => { finish = resolve; }) : Promise.resolve(summary('B统计', 7)));
  const wrapper = await legacy();
  auth.factoryId = 'B'; await flushPromises();
  expect(wrapper.text()).toContain('RECORD-B'); expect(wrapper.find('.table-footer').text()).toContain('B统计');
  finish(summary('A旧工厂统计', 999)); await flushPromises();
  expect(wrapper.find('.table-footer').text()).toContain('B统计');
  expect(wrapper.text()).not.toContain('A旧工厂统计'); expect(wrapper.find('.table-footer').text()).not.toContain('999');
  expect(api.post.mock.calls.map(call => call[0])).toEqual(['/A/list-summary/shipment', '/B/list-summary/shipment']);
});
it('removes already-loaded A statistics while B is pending and exposes B errors', async () => {
  let rejectB!: (error: Error) => void;
  api.post.mockImplementation((url: string) => url.includes('/A/') ? Promise.resolve(summary('A旧工厂统计', 999)) : new Promise((_, reject) => { rejectB = reject; }));
  const wrapper = await legacy(); expect(wrapper.text()).toContain('A旧工厂统计');
  auth.factoryId = 'B'; await flushPromises();
  expect(wrapper.text()).toContain('RECORD-B'); expect(wrapper.text()).not.toContain('A旧工厂统计');
  rejectB(new Error('403 B汇总无权访问')); await flushPromises();
  expect(wrapper.text()).toContain('403 B汇总无权访问'); expect(wrapper.find('.table-footer').exists()).toBe(false);
});
