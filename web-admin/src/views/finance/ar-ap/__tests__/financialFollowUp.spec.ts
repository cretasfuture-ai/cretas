import { flushPromises, mount } from '@vue/test-utils';
import { reactive } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';
import ReturnDetail from '../../../sales/returns/detail.vue';

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  financial: vi.fn(),
  resubmit: vi.fn(),
  prompt: vi.fn(),
}));
const route = reactive<{ name?: string; params: { id: string }; query: Record<string, unknown> }>({
  name: 'SalesReturnDetail', params: { id: 'return-a' }, query: {},
});
const identity = reactive({ factoryId: 'A', user: { id: 1 } });

vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/api/request', () => ({ get: api.get, post: api.post }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => identity }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canViewPrice: true, canWrite: () => true, canAccess: () => true }),
}));
vi.mock('@/api/factoryRecall', () => ({ getFactoryRecall: vi.fn() }));
vi.mock('@/api/returnFinancialFollowUp', () => ({
  getReturnFinancialFollowUp: api.financial,
  resubmitReturnAdjustment: api.resubmit,
  financialStatusText: (status: string) => status,
}));
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus');
  return {
    ...actual,
    ElMessageBox: { ...actual.ElMessageBox, prompt: api.prompt },
  };
});

const order = {
  id: 'return-a', returnNumber: 'RT-A', returnType: 'SALES_RETURN', status: 'COMPLETED',
  withGoods: true, totalAmount: 150, items: [],
};

function financial(status = 'REJECTED', canResubmit = true) {
  return {
    returnOrderId: 'return-a', returnNumber: 'RT-A', status, canResubmit,
    voucher: null, adjustments: [],
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolver) => { resolve = resolver; });
  return { promise, resolve };
}

function render() {
  return mount(ReturnDetail, {
    global: { plugins: [ElementPlus] },
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  route.name = 'SalesReturnDetail'; route.params.id = 'return-a'; route.query = {};
  identity.factoryId = 'A'; identity.user.id = 1;
  api.get.mockResolvedValue({ success: true, data: order });
  api.financial.mockResolvedValue(financial());
  api.resubmit.mockResolvedValue({ success: true });
});

describe('退货应收重提边界', () => {
  it('double click during reason prompt creates only one resubmission with reason only', async () => {
    const wrapper = await render(); await flushPromises();
    const prompt = deferred<{ value: string }>();
    api.prompt.mockReturnValue(prompt.promise);
    const vm = wrapper.vm as unknown as { resubmitAdjustment: () => Promise<void> };
    const first = vm.resubmitAdjustment();
    await flushPromises();
    const second = vm.resubmitAdjustment();
    expect(api.prompt).toHaveBeenCalledTimes(1);
    prompt.resolve({ value: '复核后重提' });
    await first; await second; await flushPromises();
    expect(api.resubmit).toHaveBeenCalledTimes(1);
    expect(api.resubmit).toHaveBeenCalledWith('A', 'return-a', '复核后重提');
    expect(api.resubmit.mock.calls[0]).toHaveLength(3);
    wrapper.unmount();
  });

  it('cancel, route switch, or actor switch before confirmation performs zero writes', async () => {
    const wrapper = await render(); await flushPromises();
    const vm = wrapper.vm as unknown as { resubmitAdjustment: () => Promise<void> };

    api.prompt.mockRejectedValueOnce('cancel');
    await vm.resubmitAdjustment();
    expect(api.resubmit).not.toHaveBeenCalled();

    const routePrompt = deferred<{ value: string }>();
    api.prompt.mockReturnValueOnce(routePrompt.promise);
    const routePending = vm.resubmitAdjustment();
    await flushPromises();
    route.params.id = 'return-b';
    await flushPromises();
    routePrompt.resolve({ value: '旧页面不可提交' });
    await routePending;
    expect(api.resubmit).not.toHaveBeenCalled();

    route.params.id = 'return-a'; identity.user.id = 1; await flushPromises();
    const actorPrompt = deferred<{ value: string }>();
    api.prompt.mockReturnValueOnce(actorPrompt.promise);
    const actorPending = vm.resubmitAdjustment();
    await flushPromises();
    identity.user.id = 2;
    actorPrompt.resolve({ value: '旧操作人不可提交' });
    await actorPending;
    expect(api.resubmit).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('does not expose or submit resubmission for a pending financial adjustment', async () => {
    api.financial.mockResolvedValue(financial('PENDING', true));
    const wrapper = await render(); await flushPromises();
    const vm = wrapper.vm as unknown as { resubmitAdjustment: () => Promise<void> };
    await vm.resubmitAdjustment();
    expect(api.prompt).not.toHaveBeenCalled();
    expect(api.resubmit).not.toHaveBeenCalled();
    expect(wrapper.text()).not.toContain('核对后重新提交');
    wrapper.unmount();
  });
});
