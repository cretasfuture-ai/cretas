import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { reactive } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ShipmentList from '../list.vue';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
const auth = reactive({ factoryId: 'F006' });

vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => api.get(...args),
  post: (...args: unknown[]) => api.post(...args),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canViewPrice: true }),
}));

describe('warehouse shipment customer labels', () => {
  beforeEach(() => {
    api.get.mockReset();
    api.post.mockReset();
    auth.factoryId = 'F006';
    api.get.mockImplementation(async (url: string) => {
      if (url.endsWith('/warehouse/deliveries/pending')) {
        return {
          success: true,
          data: {
            content: [{
              id: 'DEL-1',
              deliveryNumber: 'DEL-1',
              customerName: '待确认客户',
              status: 'PENDING_WAREHOUSE_CONFIRM',
            }],
            totalElements: 1,
          },
        };
      }
      if (url.endsWith('/shipments')) {
        return {
          success: true,
          data: {
            content: [{
              id: 'SHIP-1',
              shipmentNumber: 'SHIP-1',
              customerName: '历史客户',
              status: 'SHIPPED',
            }],
            totalElements: 1,
          },
        };
      }
      return { success: true, data: [] };
    });
  });

  it('mounts without the unrestricted customer roster and keeps row-scoped names', async () => {
    const wrapper = mount(ShipmentList, { global: { plugins: [ElementPlus] } });
    await flushPromises();

    expect(wrapper.text()).toContain('待确认客户');
    expect(api.get.mock.calls.map(([url]) => url)).not.toContain('/F006/customers');

    const legacyTab = wrapper.findAll('[role="tab"]').find((tab) => tab.text().includes('历史'));
    expect(legacyTab).toBeDefined();
    await legacyTab!.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('历史客户');
    expect(api.get.mock.calls.map(([url]) => url)).not.toContain('/F006/customers');
    wrapper.unmount();
  });
});
