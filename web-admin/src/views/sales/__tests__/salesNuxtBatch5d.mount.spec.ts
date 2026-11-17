/**
 * 第5批d (销售报价/车辆/需求/提醒) 迁移后的真实挂载断言:
 *  - 报价列表: 单价/毛利率整列受 canViewPrice 控制, 遮蔽时 DOM 里既没有列头也没有数值 (不显示 0);
 *  - 确认弹窗 (取代 ElMessageBox): 取消 → null, 必填校验拦住空输入, 确认 → 返回输入值;
 *  - 车辆删除确认: 取消不发 DELETE。
 * vitest 里没有 Nuxt UI 插件, 用共享最小桩 (UTable 真的渲染每行每列)。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, reactive } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { nuxtUiStubs } from '@/views/procurement/suppliers/__tests__/nuxtUiStubs';
import SalesConfirmModal from '../components/SalesConfirmModal.vue';
import QuoteList from '../quotes/list.vue';
import VehicleList from '../vehicles/list.vue';

const mocks = vi.hoisted(() => ({
  get: vi.fn(), del: vi.fn(),
  auth: { factoryId: 'F006' },
  perm: { canWrite: () => true, canViewPrice: true },
}));
vi.mock('@/api/request', () => ({ get: mocks.get, post: vi.fn(), put: vi.fn(), del: mocks.del }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => mocks.auth }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => mocks.perm }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

const UPagination = defineComponent({ name: 'UPagination', setup: () => () => h('nav') });
const stubs = { ...nuxtUiStubs, UPagination };
const quoteRow = { id: 'q1', quoteNo: 'Q-001', status: 'PENDING_APPROVAL', unitPrice: 12.5, unit: 'kg', marginRate: 0.35, quoteType: 'FIXED' };

beforeEach(() => {
  mocks.get.mockReset(); mocks.del.mockReset();
  mocks.auth = reactive({ factoryId: 'F006' });
  mocks.perm = reactive({ canWrite: () => true, canViewPrice: true });
});

describe('sales quotes price masking', () => {
  const mountQuotes = async () => {
    mocks.get.mockResolvedValue({ success: true, data: { content: [quoteRow], totalElements: 1 } });
    const wrapper = mount(QuoteList, { global: { stubs } });
    await flushPromises();
    return wrapper;
  };
  it('shows 单价/毛利率 columns with values when canViewPrice', async () => {
    const wrapper = await mountQuotes();
    expect(wrapper.find('[data-col="单价"]').exists()).toBe(true);
    expect(wrapper.find('[data-col="毛利率"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('¥12.50/kg');
    expect(wrapper.text()).toContain('35.0%');
  });
  it('removes the columns and the numbers entirely when price is masked', async () => {
    mocks.perm.canViewPrice = false;
    const wrapper = await mountQuotes();
    expect(wrapper.text()).toContain('Q-001');
    expect(wrapper.find('[data-col="单价"]').exists()).toBe(false);
    expect(wrapper.find('[data-col="毛利率"]').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('12.50');
    expect(wrapper.text()).not.toContain('35.0%');
    expect(wrapper.text()).not.toContain('未报价');
  });
});

describe('SalesConfirmModal', () => {
  const mountModal = () => mount(SalesConfirmModal, { global: { stubs } });
  const ask = (w: ReturnType<typeof mountModal>, o: Parameters<InstanceType<typeof SalesConfirmModal>['ask']>[0]) =>
    (w.vm as unknown as { ask: (x: typeof o) => Promise<string | null> }).ask(o);
  it('cancel resolves null', async () => {
    const w = mountModal();
    const p = ask(w, { title: 't', message: 'm' });
    await w.get('[data-testid="sales-confirm-cancel"]').trigger('click');
    expect(await p).toBeNull();
  });
  it('required input blocks empty confirm, then returns the typed value', async () => {
    const w = mountModal();
    let settled = false;
    const p = ask(w, { title: 't', message: 'm', input: { label: '驳回原因', required: true, errorText: '驳回原因必填' } }).then((v) => { settled = true; return v; });
    await w.get('[data-testid="sales-confirm-ok"]').trigger('click');
    await flushPromises();
    expect(settled).toBe(false);
    await w.get('input').setValue('价格太高');
    await w.get('[data-testid="sales-confirm-ok"]').trigger('click');
    expect(await p).toBe('价格太高');
  });
  it('confirm without input resolves empty string (not null)', async () => {
    const w = mountModal();
    const p = ask(w, { title: 't', message: 'm' });
    await w.get('[data-testid="sales-confirm-ok"]').trigger('click');
    expect(await p).toBe('');
  });
});

describe('vehicles delete confirmation', () => {
  it('cancel sends no DELETE; confirm sends exactly one', async () => {
    mocks.get.mockResolvedValue({ success: true, data: [{ id: 'v1', plateNumber: '沪A12345', status: 'available' }] });
    mocks.del.mockResolvedValue({ success: true });
    const wrapper = mount(VehicleList, { global: { stubs } });
    await flushPromises();
    const delBtn = () => wrapper.findAll('button').find((b) => b.text() === '删除')!;
    await delBtn().trigger('click');
    await wrapper.get('[data-testid="sales-confirm-cancel"]').trigger('click');
    await flushPromises();
    expect(mocks.del).not.toHaveBeenCalled();
    await delBtn().trigger('click');
    await wrapper.get('[data-testid="sales-confirm-ok"]').trigger('click');
    await flushPromises();
    expect(mocks.del).toHaveBeenCalledTimes(1);
    expect(mocks.del).toHaveBeenCalledWith('/F006/vehicles/v1');
  });
});
