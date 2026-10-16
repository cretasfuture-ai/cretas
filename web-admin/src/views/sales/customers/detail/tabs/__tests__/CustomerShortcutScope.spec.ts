import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref } from 'vue';
import InvoicesTab from '../InvoicesTab.vue';
import PaymentsTab from '../PaymentsTab.vue';
import QuotesTab from '../QuotesTab.vue';
import { ApiError } from '@/types/api';

const get = vi.hoisted(() => vi.fn());
vi.mock('@/api/request', () => ({ get }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: ref('F1') }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: ref(true) }) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

const global = { stubs: {
  'el-skeleton': true,
  'el-empty': { props: ['description'], template: '<p>{{description}}</p>' },
  'el-result': { props: ['title'], template: '<p>{{title}}</p>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-table': true, 'el-table-column': true, 'el-tag': true, 'el-pagination': true,
} };
describe.each([
  ['开票', InvoicesTab, '/F1/finance/invoices/by-customer'],
  ['收款', PaymentsTab, '/F1/finance/payments/by-customer'],
  ['报价单', QuotesTab, '/F1/quotes/by-customer'],
] as const)('%s shortcut scope', (_label, component, path) => {
  beforeEach(() => { get.mockReset(); });
  it('shows an explicit restriction for the real client ApiError and handles it locally', async () => {
    get.mockRejectedValue(new ApiError('权限不足', 'FORBIDDEN', 403));
    const wrapper=mount(component, { props: {customerId:'C1'}, global });
    await flushPromises();
    expect(wrapper.text()).toContain('当前数据范围或权限不支持此客户快捷页');
    expect(wrapper.text()).not.toContain('暂无');
    expect(get).toHaveBeenCalledWith(path, expect.objectContaining({
      params: expect.objectContaining({customerId:'C1'}), _silent:true,
    }));
    wrapper.unmount();
  });
  it('keeps the allowed empty result distinct from access denial', async () => {
    get.mockResolvedValue({success:true,data:{content:[],totalElements:0}});
    const wrapper=mount(component, { props: {customerId:'C1'}, global });
    await flushPromises();
    expect(wrapper.text()).toContain('暂无');
    expect(wrapper.text()).not.toContain('当前数据范围或权限不支持');
    wrapper.unmount();
  });
});
