import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive } from 'vue';
import ElementPlus, { ElMessageBox } from 'element-plus';

const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), recall: vi.fn(), push: vi.fn() }));
const route = reactive({ params: { id: 'return-a' }, query: { recallId: '77', batchId: '999' } });
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: mocks.push }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'VISION-ENTRY-A' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true, canViewPrice: true }) }));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => mocks.get(...args), post: (...args: unknown[]) => mocks.post(...args) }));
vi.mock('@/api/factoryRecall', () => ({ getFactoryRecall: (...args: unknown[]) => mocks.recall(...args) }));
import ReturnPage from '../detail.vue';

const order = { id: 'return-a', returnNumber: 'RT-001', returnType: 'SALES_RETURN', status: 'FINANCE_APPROVED', withGoods: true, totalAmount: 60, remark: 'RECALL_EVENT_ID=77;SHIPMENT_ID=actual-delivery;', items: [] };
beforeEach(() => {
  vi.clearAllMocks();
  route.params.id = 'return-a';
  route.query.recallId = '77';
  mocks.get.mockResolvedValue({ success: true, data: order });
  mocks.post.mockResolvedValue({ success: true, data: { ...order, status: 'COMPLETED' } });
  mocks.recall.mockResolvedValue({ id: 77, batchId: 7, batchNumber: 'PB-7', shipments: [{ unit: 'kg', returnOrders: [{ id: 'return-a' }] }] });
});
describe('原销售退货详情复用召回上下文', () => {
  it('按真实召回关联回显单位与批号，返回使用服务端批次而非query', async () => {
    const wrapper = mount(ReturnPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(mocks.recall).toHaveBeenCalledWith('VISION-ENTRY-A', 77);
    expect(wrapper.text()).toContain('召回批次：PB-7；本单数量单位：kg');
    expect(wrapper.text()).toContain('实物退货（隔离入库）');
    expect(wrapper.text()).toContain('由批次召回登记');
    expect(wrapper.text()).not.toContain('RECALL_EVENT_ID=');
    await wrapper.findAll('button').find(button => button.text() === '返回批次召回')!.trigger('click');
    expect(mocks.push).toHaveBeenCalledWith({ path: '/quality/recalls', query: { batchId: '7' } });
    wrapper.unmount();
  });
  it('无匹配退货关联不冒用其他召回批号或单位', async () => {
    mocks.recall.mockResolvedValue({ id: 77, batchId: 7, batchNumber: 'PB-7', shipments: [{ unit: '盒', returnOrders: [{ id: 'another-return' }] }] });
    const wrapper = mount(ReturnPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(wrapper.text()).not.toContain('返回批次召回');
    expect(wrapper.text()).not.toContain('本单数量单位');
    expect(wrapper.text()).toContain('RT-001');
    wrapper.unmount();
  });
  it('确认收货仍调用原完成端点，提示隔离入库与应收调整待财务处理', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm');
    const wrapper = mount(ReturnPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text() === '确认退货收货')!.trigger('click');
    await flushPromises();
    expect(confirm.mock.calls[0][0]).toContain('不能直接销售');
    expect(confirm.mock.calls[0][0]).toContain('应收调整将提交财务处理');
    expect(mocks.post).toHaveBeenCalledWith('/VISION-ENTRY-A/return-orders/return-a/complete', {});
    wrapper.unmount();
    confirm.mockRestore();
  });
  it('切换退货单后，旧召回详情晚到不能覆盖当前批号和单位', async () => {
    let resolveOld!: (value: unknown) => void;
    mocks.recall.mockImplementation((_factory, id) => id === 77
      ? new Promise(resolve => { resolveOld = resolve; })
      : Promise.resolve({ id: 88, batchId: 8, batchNumber: 'PB-8', shipments: [{ unit: '盒', returnOrders: [{ id: 'return-b' }] }] }));
    mocks.get.mockImplementation((path: string) => Promise.resolve({ success: true, data: { ...order, id: path.endsWith('return-b') ? 'return-b' : 'return-a' } }));
    const wrapper = mount(ReturnPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    route.params.id = 'return-b';
    route.query.recallId = '88';
    await flushPromises();
    resolveOld({ id: 77, batchId: 7, batchNumber: 'PB-7', shipments: [{ unit: 'kg', returnOrders: [{ id: 'return-a' }] }] });
    await flushPromises();
    expect(wrapper.text()).toContain('召回批次：PB-8；本单数量单位：盒');
    expect(wrapper.text()).not.toContain('召回批次：PB-7');
    wrapper.unmount();
  });
  it('收货确认框打开后切到另一退货单，不对新旧单据错发操作', async () => {
    let resolveConfirm!: (value: 'confirm') => void;
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => new Promise(resolve => { resolveConfirm = resolve; }));
    const wrapper = mount(ReturnPage, { global: { plugins: [ElementPlus] } });
    await flushPromises();
    await wrapper.findAll('button').find(button => button.text() === '确认退货收货')!.trigger('click');
    route.params.id = 'return-b';
    await flushPromises();
    resolveConfirm('confirm');
    await flushPromises();
    expect(mocks.post).not.toHaveBeenCalled();
    wrapper.unmount();
    confirm.mockRestore();
  });
});
