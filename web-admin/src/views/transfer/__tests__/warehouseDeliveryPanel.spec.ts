import { describe, expect, it, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const panel = readFileSync(resolve(import.meta.dirname, '..', 'WarehouseDeliveryPanel.vue'), 'utf8');
const detail = readFileSync(resolve(import.meta.dirname, '..', 'detail.vue'), 'utf8');
const api = readFileSync(resolve(import.meta.dirname, '..', '..', '..', 'api', 'warehouseTransferDelivery.ts'), 'utf8');
const { getDelivery, dispatch } = vi.hoisted(() => ({ getDelivery: vi.fn(), dispatch: vi.fn() }));
vi.mock('@/api/warehouseTransferDelivery', () => ({ getWarehouseTransferDelivery: getDelivery, dispatchWarehouseTransfer: dispatch, receiveWarehouseTransfer: vi.fn() }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F1', user: { id: 7 } }) }));
import WarehouseDeliveryPanel from '../WarehouseDeliveryPanel.vue';
describe('warehouse delivery panel wiring', () => {
  beforeEach(() => { getDelivery.mockReset(); dispatch.mockReset(); getDelivery.mockResolvedValue({ success: true, data: { supported: true, staged: false, stage: '待发运', transferId: 'T1', transferNumber: 'TRF-1', inTransitByUnit: {}, items: [{ id: 1, itemName: '原料', unit: 'kg', sourceBatchId: 'SRC', shippedQuantity: 0, receivedQuantity: 0, pendingQuantity: 0, receipts: [] }] } }); dispatch.mockResolvedValue({ success: true, data: { id: 'T1', status: 'SHIPPED' } }); });
  it('mounts on the original transfer detail and uses fixed endpoints', () => {
    expect(detail).toContain('<WarehouseDeliveryPanel');
    expect(api).toContain('warehouse-dispatch');
    expect(api).toContain('warehouse-receipts');
    expect(panel).toContain('已发 / 已收 / 待收');
  });
  it('enforces incremental bounds and preserves unknown responses', () => {
    expect(panel).toContain('待收数量');
    expect(panel).toContain('收货结果未知，请刷新查看真实状态');
    expect(panel).toContain('requestKey');
  });
  it('refreshes delivery after dispatch and rotates key only after success', () => {
    expect(panel).toContain('await load()');
    expect(panel).toContain('persistResolved(envelope)');
    expect(panel).toContain('submittedIds');
    expect(panel).toContain('targetBatchId');
    expect(panel).toContain('待核请求不可修改');
    expect(detail).toContain('@changed="loadTransfer"');
  });
  it('mounts and refreshes DTO after dispatch response is an InternalTransfer', async () => {
    const wrapper = mount(WarehouseDeliveryPanel, { props: { transferId: 'T1', status: 'APPROVED', canWrite: true }, global: { stubs: { 'el-card': { template: '<div><slot name="header"/><slot/></div>' }, 'el-button': { template: '<button @click="$emit(\'click\')"><slot/></button>' }, 'el-alert': true, 'el-table': true, 'el-table-column': true, 'el-input-number': true } } });
    await flushPromises();
    await wrapper.findAll('button')[1].trigger('click');
    await flushPromises();
    expect(dispatch).toHaveBeenCalledWith('F1', 'T1');
    expect(getDelivery).toHaveBeenCalledTimes(2);
  });
});
