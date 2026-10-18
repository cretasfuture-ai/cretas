import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { reactive } from 'vue';
import RecallPage from '../index.vue';
import {
  acknowledgeFactoryRecallShipment,
  closeFactoryRecall,
  createFactoryRecall,
  createFactoryRecallReturn,
  getFactoryRecall,
  getFactoryRecallFollowUp,
  listFactoryRecalls,
  previewFactoryRecall,
} from '@/api/factoryRecall';

const routeState = reactive<{ query: Record<string, string> }>({ query: { batchId: '7' } });
const router = { push: vi.fn(), replace: vi.fn() };

vi.mock('@/api/factoryRecall', () => ({
  acknowledgeFactoryRecallShipment: vi.fn(),
  closeFactoryRecall: vi.fn(),
  createFactoryRecall: vi.fn(),
  createFactoryRecallReturn: vi.fn(),
  getFactoryRecall: vi.fn(),
  getFactoryRecallFollowUp: vi.fn(),
  listFactoryRecalls: vi.fn(),
  previewFactoryRecall: vi.fn(),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F006' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true }) }));
vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => router,
}));

const preview = {
  batchId: 7,
  batchNumber: 'B-007',
  productName: '测试猪蹄',
  unit: 'kg',
  remainingInventory: 8,
  shipments: [
    {
      shipmentId: 'shipment-1',
      shipmentNumber: 'DLV-001',
      customerName: '测试客户',
      quantity: 5,
      unit: 'kg',
      returnedQuantity: 1,
      pendingReturnQuantity: 2,
      remainingReturnQuantity: 4,
      acknowledged: false,
      returnOrders: [{ id: 'return-1', returnNumber: 'RT-001', status: 'DRAFT', quantity: 1 }],
    },
  ],
};

const detail = {
  ...preview,
  id: 'recall-1',
  eventCode: 'RC-001',
  status: 'OPEN',
  reason: '产品质量异常',
  completedAt: null,
  canClose: false,
  blockers: [{ code: 'PENDING_RETURN', message: '还有 4 kg 退货未完成', shipmentId: 'shipment-1', quantity: 4 }],
};

const followUp = {
  recallId: 'recall-1',
  batchId: 7,
  batchNumber: 'B-007',
  unit: 'kg',
  recallCompleted: false,
  inventoryResolved: false,
  receivableResolved: false,
  completed: false,
  issues: [{ code: 'INVENTORY_PENDING', message: '仍有 2 kg 待处置' }],
  stock: [{
    finishedGoodsBatchId: 'fg-7', batchNumber: 'FG-007', origin: 'ORIGINAL', unit: 'kg',
    remainingQuantity: 2, disposalQuantity: 0, evidenceComplete: false, disposals: [],
  }],
  returns: [{
    returnOrderId: 'return-1', returnNumber: 'RT-001', status: 'DRAFT', quantity: 1,
    receivableStatus: 'PENDING', adjustments: [],
  }],
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolver) => { resolve = resolver; });
  return { promise, resolve };
}

beforeEach(() => {
  routeState.query = { batchId: '7' };
  vi.resetAllMocks();
  vi.mocked(previewFactoryRecall).mockResolvedValue(preview);
  vi.mocked(listFactoryRecalls).mockResolvedValue([detail]);
  vi.mocked(getFactoryRecall).mockResolvedValue(detail);
  vi.mocked(getFactoryRecallFollowUp).mockResolvedValue(followUp);
  vi.mocked(acknowledgeFactoryRecallShipment).mockResolvedValue(detail);
  vi.mocked(createFactoryRecallReturn).mockResolvedValue({ id: 'return-2', returnNumber: 'RT-002', status: 'DRAFT', quantity: 2 });
  vi.mocked(closeFactoryRecall).mockResolvedValue(detail);
});

async function openPage() {
  const wrapper = shallowMount(RecallPage, {
    global: {
      renderStubDefaultSlot: true,
      stubs: {
        'el-button': { props: { disabled: Boolean }, template: '<button :disabled="disabled"><slot /></button>' },
        'el-table': { template: '<div><slot /></div>' },
        'el-table-column': { template: '<div />' },
      },
    },
  });
  await flushPromises();
  return wrapper;
}

describe('quality recalls page', () => {
  it('结案成功后事件列表与详情立即一致，不继续显示库存已隔离', async () => {
    vi.mocked(getFactoryRecall).mockResolvedValue({ ...detail, canClose: true });
    vi.mocked(closeFactoryRecall).mockResolvedValue({ ...detail, status: 'COMPLETED', canClose: true });
    const wrapper = await openPage();
    const vm = wrapper.vm as unknown as { submitClose: () => Promise<void>; detail: typeof detail; recalls: typeof detail[] };
    await vm.submitClose();
    expect(vm.detail.status).toBe('COMPLETED');
    expect(vm.recalls[0].status).toBe('COMPLETED');
    await vm.submitClose();
    expect(closeFactoryRecall).toHaveBeenCalledTimes(1);
    await flushPromises();
    const closeButton = wrapper.findAll('button').find(button => button.text() === '已结案');
    expect(closeButton?.attributes()).toHaveProperty('disabled');
    wrapper.unmount();
  });
  it('loads the exact batch preview and recall list, then displays server batch context', async () => {
    const wrapper = await openPage();
    expect(previewFactoryRecall).toHaveBeenCalledWith('F006', 7);
    expect(listFactoryRecalls).toHaveBeenCalledWith('F006', 7);
    const state = wrapper.vm as unknown as { preview: typeof preview; recalls: typeof detail[]; detail: typeof detail | null };
    expect(state.preview).toMatchObject({ batchNumber: 'B-007', productName: '测试猪蹄' });
    expect(state.preview.shipments[0]).toMatchObject({ shipmentNumber: 'DLV-001', remainingReturnQuantity: 4 });
    expect(state.preview.shipments[0].returnOrders[0]).toMatchObject({ returnNumber: 'RT-001' });
    expect(state.recalls).toHaveLength(1);
    expect(getFactoryRecall).toHaveBeenCalledWith('F006', 'recall-1');
    expect(getFactoryRecallFollowUp).toHaveBeenCalledWith('F006', 'recall-1');
    expect(state.detail).toMatchObject({ id: 'recall-1', eventCode: 'RC-001' });
    expect((wrapper.vm as unknown as { followUp: typeof followUp }).followUp).toMatchObject({
      inventoryResolved: false,
      receivableResolved: false,
    });
    wrapper.unmount();
  });

  it('keeps follow-up errors visible instead of showing a false completed state', async () => {
    vi.mocked(getFactoryRecallFollowUp).mockRejectedValue(new Error('后续接口暂不可用'));
    const wrapper = await openPage();
    const state = wrapper.vm as unknown as { followUp: typeof followUp | null; followUpError: string };
    expect(state.followUp).toBeNull();
    expect(state.followUpError).toBe('后续接口暂不可用');
    wrapper.unmount();
  });

  it('keeps actual return boundaries and routes existing return details', async () => {
    const wrapper = await openPage();
    const vm = wrapper.vm as unknown as {
      openReturnOrder: (id: string) => void;
      returnShipment: { remainingReturnQuantity: number } | null;
      openReturn: (shipment: typeof preview.shipments[number]) => void;
    };
    vm.openReturnOrder('return-1');
    expect(router.push).toHaveBeenCalledWith({
      path: '/sales/returns/return-1',
      query: { recallId: 'recall-1', batchId: '7' },
    });
    vm.openReturn(preview.shipments[0]);
    expect(vm.returnShipment?.remainingReturnQuantity).toBe(4);
    wrapper.unmount();
  });

  it('routes follow-up disposal and receivable work with server-owned identifiers', async () => {
    const wrapper = await openPage();
    const vm = wrapper.vm as unknown as {
      openFollowUpDisposal: (stock: typeof followUp.stock[number], disposalId?: string) => void;
      openReceivable: (returnOrderId: string) => void;
    };
    vm.openFollowUpDisposal(followUp.stock[0], 'disposal-9');
    expect(router.push).toHaveBeenCalledWith({
      path: '/quality/disposals',
      query: { finishedGoodsBatchId: 'fg-7', recallId: 'recall-1', batchId: '7', disposalId: 'disposal-9' },
    });
    vm.openReceivable('return-1');
    expect(router.push).toHaveBeenCalledWith({ path: '/finance/ar-ap', query: { returnOrderId: 'return-1' } });
    wrapper.unmount();
  });

  it('reloads the current event detail when the cached batch page is activated again', async () => {
    const wrapper = await openPage();
    const refreshed = {
      ...detail,
      shipments: [{ ...detail.shipments[0], returnedQuantity: 3, remainingReturnQuantity: 2 }],
    };
    vi.mocked(getFactoryRecall).mockResolvedValue(refreshed);
    const vm = wrapper.vm as unknown as {
      loadBatchContext: (batchId: string) => Promise<void>;
      detail: typeof detail | null;
    };
    await vm.loadBatchContext('7');
    expect(vm.detail?.shipments[0]).toMatchObject({ returnedQuantity: 3, remainingReturnQuantity: 2 });
    wrapper.unmount();
  });

  it('keeps the newly selected batch/event when an earlier detail response arrives late', async () => {
    const wrapper = await openPage();
    const oldSummary = { ...detail, id: 'recall-old', batchId: 7 };
    const newSummary = { ...detail, id: 'recall-new', batchId: 8 };
    const oldDetail = deferred<typeof detail>();
    const newDetail = deferred<typeof detail>();
    const vm = wrapper.vm as unknown as {
      loadBatchContext: (batchId: string) => Promise<void>;
      selectRecall: (item: typeof detail) => Promise<void>;
      detail: (typeof detail) | null;
    };
    vi.mocked(previewFactoryRecall).mockResolvedValue({ ...preview, batchId: 8 });
    vi.mocked(listFactoryRecalls).mockResolvedValue([newSummary]);
    vi.mocked(getFactoryRecall).mockImplementation((_factoryId, id) =>
      String(id) === 'recall-old' ? oldDetail.promise : newDetail.promise,
    );

    const oldRequest = vm.selectRecall(oldSummary);
    await flushPromises();
    const newBatchRequest = vm.loadBatchContext('8');
    await flushPromises();
    newDetail.resolve({ ...detail, id: 'recall-new', batchId: 8 });
    await newBatchRequest;
    oldDetail.resolve({ ...detail, id: 'recall-old', batchId: 7 });
    await oldRequest;

    expect(vm.detail).toMatchObject({ id: 'recall-new', batchId: 8 });
    wrapper.unmount();
  });

  it('uses the server event actions and does not claim a return was received', async () => {
    vi.mocked(createFactoryRecall).mockResolvedValue(detail);
    const wrapper = await openPage();
    const vm = wrapper.vm as unknown as {
      detail: typeof detail;
      returnShipment: typeof preview.shipments[number] | null;
      returnQuantity: number | null;
      actionNotice: string;
      selectRecall: (item: typeof detail) => Promise<void>;
      submitReturn: () => Promise<void>;
    };
    await vm.selectRecall(detail);
    vm.returnShipment = preview.shipments[0];
    vm.returnQuantity = 2.1234;
    await vm.submitReturn();
    await flushPromises();
    expect(createFactoryRecallReturn).toHaveBeenCalledWith('F006', 'recall-1', {
      shipmentId: 'shipment-1', quantity: 2.1234, reason: '客户召回退货',
    });
    expect(vm.actionNotice).toBe('退货草稿已创建，请进入退货审批');
    expect(wrapper.text()).toContain('不代表已收货入库');
    expect(wrapper.text()).not.toContain('已收货入库成功');
    wrapper.unmount();
  });
});
