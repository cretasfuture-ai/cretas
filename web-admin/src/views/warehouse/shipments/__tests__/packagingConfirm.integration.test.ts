import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ShipmentList from '../list.vue';
import { ApiError } from '@/types/api';

/**
 * O-309-1: 确认发货时后端因规格不一致拦下来 → 列出批次和两边规格让仓管确认 →
 * 确认才写分配 (带确认标记) 再发一次; 取消什么都不写。
 */
const get = vi.fn();
const post = vi.fn();
const confirm = vi.hoisted(() => vi.fn());
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canAccess: () => true, canViewPrice: false }),
}));
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => get(...args), post: (...args: unknown[]) => post(...args) }));
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus');
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, confirm } };
});

type Vm = {
  confirmingDelivery: { id: string } | null;
  confirmItems: unknown[];
  submitConfirmDelivery: () => Promise<void>;
};

const CONFIRM_URL = '/F006/warehouse/deliveries/D1/confirm';
const ALLOCATE_URL = '/F006/sales-deliveries/items/271/batch-allocations';
const mismatch = () => new ApiError('规格不一致', 'BATCH_PACKAGING_SPEC_MISMATCH', 409);

async function mountDialog() {
  const wrapper = shallowMount(ShipmentList, { global: {
    stubs: { 'el-table': { template: '<div />' }, 'el-dialog': { template: '<div />' }, 'el-drawer': { template: '<div />' } },
    directives: { loading: () => {} },
  } });
  await flushPromises();
  const vm = wrapper.vm as unknown as Vm;
  vm.confirmingDelivery = { id: 'D1' };
  vm.confirmItems = [{ id: '271', productName: '卤牛肉', plannedQty: 1, actualQty: 1, unit: '箱',
    productTypeId: 'SKU-BEEF', sourceWarehouseCode: '', batches: [] }];
  return vm;
}

describe('出货确认 · 规格不一致换算确认', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    confirm.mockReset();
    get.mockImplementation(async (url: string) => url.includes('recommend-fifo')
      ? { success: true, data: [{ batchId: 'FG02', batchNumber: 'QA-FG02', recommendedQuantity: 1, unit: '箱',
          packagingMismatch: true, batchPackagingLabel: '1托盘=96盒', linePackagingLabel: '1箱=8盒' }] }
      : { success: true, data: { content: [], totalElements: 0 } });
  });

  it('确认后写分配 (带确认标记) 再发一次, 提示里有品名、批次和两边规格', async () => {
    post.mockRejectedValueOnce(mismatch()).mockResolvedValue({ success: true });
    confirm.mockResolvedValue('confirm');
    const vm = await mountDialog();

    await vm.submitConfirmDelivery();
    await flushPromises();

    const text = String(confirm.mock.calls[0][0]);
    for (const part of ['卤牛肉', 'QA-FG02', '1托盘=96盒', '1箱=8盒']) expect(text).toContain(part);
    expect(post.mock.calls.map(([url]) => url)).toEqual([CONFIRM_URL, ALLOCATE_URL, CONFIRM_URL]);
    expect(post.mock.calls[1][1]).toEqual({ allocations: [
      { finishedGoodsBatchId: 'FG02', allocatedQty: 1, packagingConversionConfirmed: true },
    ] });
    expect(vm.confirmingDelivery).toBeNull();
  });

  it('阴性: 取消就什么都不写, 对话框还开着', async () => {
    post.mockRejectedValueOnce(mismatch());
    confirm.mockRejectedValue('cancel');
    const vm = await mountDialog();

    await vm.submitConfirmDelivery();
    await flushPromises();

    expect(post.mock.calls.map(([url]) => url)).toEqual([CONFIRM_URL]);
    expect(vm.confirmingDelivery).toEqual({ id: 'D1' });
  });

  it('阴性: 别的 409 不弹换算确认, 也不写分配', async () => {
    post.mockRejectedValueOnce(new ApiError('库存不足', 'OTHER', 409));
    const vm = await mountDialog();

    await vm.submitConfirmDelivery();
    await flushPromises();

    expect(confirm).not.toHaveBeenCalled();
    expect(post).toHaveBeenCalledTimes(1);
  });

  it('规格一致的行不带确认标记, 也不问', async () => {
    post.mockResolvedValue({ success: true });
    const vm = await mountDialog();

    await vm.submitConfirmDelivery();
    await flushPromises();

    expect(confirm).not.toHaveBeenCalled();
    expect(post.mock.calls.map(([url]) => url)).toEqual([CONFIRM_URL]);
  });
});
