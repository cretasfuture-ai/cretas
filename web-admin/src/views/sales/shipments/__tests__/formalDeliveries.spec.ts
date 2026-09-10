import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive, nextTick } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import FormalDeliveries from '../FormalDeliveries.vue';

const mocks = vi.hoisted(() => ({ get: vi.fn(), push: vi.fn(), auth: { factoryId: 'A' } }));
vi.mock('@/api/request', () => ({ get: mocks.get }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => mocks.auth }));
const result = (content: object[], totalElements = content.length) => ({ success: true, data: { content, totalElements } });
const mother = { id: 'delivery-id', deliveryNumber: 'DEL-M', salesOrderId: 'order-id', recordRole: 'MASTER', status: 'PENDING_SPLIT', items: [{ id: 1, productName: '鱼', deliveredQuantity: 12, unit: 'kg' }, { id: 2, productName: '虾', deliveredQuantity: 3, unit: '箱' }] };
const render = () => mount(FormalDeliveries, { global: { plugins: [ElementPlus] } });

beforeEach(() => { mocks.get.mockReset(); mocks.push.mockReset(); mocks.auth = reactive({ factoryId: 'A' }); });
describe('formal delivery read model', () => {
  it('uses one-based server pagination, distinguishes roles and preserves per-item quantities', async () => {
    mocks.get.mockResolvedValue(result([mother, { ...mother, id: 'child', deliveryNumber: 'DEL-C', parentDeliveryId: 'delivery-id', recordRole: 'SHIPMENT', shipmentSequence: 1 }, { ...mother, id: 'single', recordRole: 'LEGACY', salesOrderId: null }], 11));
    const wrapper = render(); await flushPromises();
    expect(mocks.get).toHaveBeenCalledWith('/A/sales/deliveries', { params: { page: 1, size: 10 } });
    expect(wrapper.text()).toContain('子发运单'); expect(wrapper.text()).toContain('单次发货单');
    expect(wrapper.text()).toContain('鱼：12 kg'); expect(wrapper.text()).toContain('虾：3 箱');
    const buttons = wrapper.findAll('button').filter(button => button.text() === '查看销售订单');
    await buttons[0].trigger('click'); expect(mocks.push).toHaveBeenCalledWith('/sales/orders/order-id');
    expect(buttons[2].attributes('disabled')).toBeDefined();
    wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('current-change', 2); await flushPromises();
    expect(mocks.get).toHaveBeenLastCalledWith('/A/sales/deliveries', { params: { page: 2, size: 10 } });
    expect(wrapper.find('input').exists()).toBe(false);
  });
  it('distinguishes successful empty results from failures and supports retry', async () => {
    mocks.get.mockResolvedValueOnce(result([])).mockRejectedValueOnce(new Error('403 无权访问')).mockResolvedValueOnce(result([mother]));
    const wrapper = render(); await flushPromises(); expect(wrapper.text()).toContain('暂无正式发货记录');
    await wrapper.findAll('button').find(b => b.text() === '刷新')!.trigger('click'); await flushPromises();
    expect(wrapper.text()).toContain('403 无权访问'); expect(wrapper.text()).not.toContain('暂无正式发货记录');
    await wrapper.findAll('button').find(b => b.text() === '重试')!.trigger('click'); await flushPromises();
    expect(wrapper.text()).toContain('DEL-M'); expect(wrapper.text()).not.toContain('403 无权访问');
  });
  it('rejects incomplete responses instead of claiming no records', async () => {
    mocks.get.mockResolvedValue({ success: true, data: {} });
    const wrapper = render(); await flushPromises(); expect(wrapper.text()).toContain('响应不完整');
  });
  it('discards earlier factory responses and resets page', async () => {
    let finish!: (value: unknown) => void;
    mocks.get.mockReturnValueOnce(new Promise(resolve => { finish = resolve; })).mockResolvedValue(result([{ ...mother, deliveryNumber: 'FACTORY-B' }]));
    const wrapper = render(); mocks.auth.factoryId = 'B'; await nextTick(); await flushPromises();
    finish(result([mother])); await flushPromises();
    expect(wrapper.text()).toContain('FACTORY-B'); expect(wrapper.text()).not.toContain('DEL-M');
    expect(mocks.get).toHaveBeenLastCalledWith('/B/sales/deliveries', { params: { page: 1, size: 10 } });
  });
  it('discards an earlier refresh and requests again after tab remount', async () => {
    let finish!: (value: unknown) => void;
    mocks.get.mockReturnValueOnce(new Promise(resolve => { finish = resolve; })).mockResolvedValue(result([{ ...mother, deliveryNumber: 'NEW-TAB' }]));
    const old = render(); old.unmount();
    const current = render(); await flushPromises(); finish(result([mother])); await flushPromises();
    expect(current.text()).toContain('NEW-TAB'); expect(current.text()).not.toContain('DEL-M');
  });
});
