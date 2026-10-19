import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive, ref } from 'vue';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import Plans from '../list.vue';

const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), sales: '-', finance: '-',
  customerFailure: false, customerMalformed: false }));
vi.mock('@/api/request', () => ({ get: mocks.get, post: mocks.post, put: mocks.put, default: { get: mocks.get, post: mocks.post } }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006', user: {}, isAuthenticated: true }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => reactive({
  isDbLoaded: true, dbLoadError: null, currentUserId: 'production-user', canViewPrice: false,
  canWrite: () => true, canAccess: () => true,
  getPermissionLevel: (module: 'sales' | 'finance') => mocks[module],
}) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  useRoute: () => ({ query: {}, path: '/production/plans' }) }));
vi.mock('@/composables/useWorkflowStats', () => ({ useWorkflowStats: () => ({ stats: ref(null), refresh: vi.fn() }) }));
vi.mock('@/composables/useListSummary', () => ({ useListSummary: () => ({ summary: ref(null), loading: ref(false), error: ref(null), refresh: vi.fn() }) }));

describe('real mounted production plan page customer wiring', () => {
  beforeEach(() => {
    setActivePinia(createPinia()); vi.clearAllMocks(); mocks.sales = '-'; mocks.finance = '-'; mocks.customerFailure = false;
    mocks.customerMalformed = false;
    mocks.get.mockImplementation(async (url: string) => {
      if (url.endsWith('/customers')) {
        if (mocks.customerFailure) throw new Error('customer API unavailable');
        if (mocks.customerMalformed) return { success: true, data: {} };
        return { success: true, data: [{ id: 'customer-1', name: 'Authorized customer' }] };
      }
      return { success: true, data: { content: [], totalElements: 0 } };
    });
  });
  function mountPage() {
    return shallowMount(Plans, { global: { renderStubDefaultSlot: true,
      stubs: { ElTable: { template: '<div />' }, ElTableColumn: { template: '<div />' } },
      directives: { loading: () => {} }, config: { warnHandler: () => {} } } });
  }
  it('does not request the roster even when UI module access is granted; other production loads still run', async () => {
    const page = mountPage(); await flushPromises();
    expect(mocks.get.mock.calls.some(([url]) => String(url).endsWith('/customers'))).toBe(false);
    expect(mocks.get.mock.calls.some(([url]) => String(url).includes('production-plans'))).toBe(true);
    expect(page.text()).toContain('当前账号无客户名单读取权限');
    expect(page.find('[label="关联客户(选填)"]').findComponent({ name: 'ElSelect' }).props('disabled')).toBe(true);
    expect(mocks.post).not.toHaveBeenCalled(); expect(mocks.put).not.toHaveBeenCalled();
  });
  it('uses the actual customer endpoint for an effective reader', async () => {
    mocks.sales = 'r'; const page = mountPage(); await flushPromises();
    expect(mocks.get).toHaveBeenCalledWith('/F006/customers', { params: { size: 200 } });
    expect(page.find('[data-testid="plan-customer-read-status"]').exists()).toBe(false);
    expect(page.find('[label="关联客户(选填)"]').findComponent({ name: 'ElSelect' }).props('disabled')).toBe(false);
  });
  it('shows an authorized API failure instead of silently presenting an empty roster', async () => {
    mocks.finance = 'rw'; mocks.customerFailure = true;
    const page = mountPage(); await flushPromises();
    expect(page.text()).toContain('customer API unavailable'); expect(page.text()).not.toContain('暂无可关联客户');
  });
  it('rejects a malformed successful roster response instead of treating it as empty', async () => {
    mocks.sales = 'rw'; mocks.customerMalformed = true;
    const page = mountPage(); await flushPromises();
    expect(page.text()).toContain('客户列表响应无效，请重试');
    expect(page.text()).not.toContain('暂无可关联客户');
  });
});
