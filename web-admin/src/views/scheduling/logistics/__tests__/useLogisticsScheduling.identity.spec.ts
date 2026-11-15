import { reactive } from 'vue';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { resetLogisticsScheduling, useLogisticsScheduling } from '../useLogisticsScheduling';
import Workbench from '../workbench/index.vue';

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {}, meta: {} }), useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));

const api = vi.hoisted(() => ({
  commitOrderImport: vi.fn(),
  createOrdersManual: vi.fn(),
  confirmAllTrips: vi.fn(),
  confirmPlan: vi.fn(),
  confirmTrip: vi.fn(),
  createDriver: vi.fn(),
  exportPlanCsv: vi.fn(),
  exportPlanXlsx: vi.fn(),
  generatePlan: vi.fn(),
  getImportTemplate: vi.fn(),
  getOrderBatch: vi.fn(),
  getPlan: vi.fn(),
  listDrivers: vi.fn(),
  listOrderBatches: vi.fn(),
  listOrders: vi.fn(),
  listPlans: vi.fn(),
  listVehicles: vi.fn(),
  markDelivered: vi.fn(),
  markDeliveryException: vi.fn(),
  moveStop: vi.fn(),
  resetDelivery: vi.fn(),
  previewOrderImport: vi.fn(),
  previewOrderImportPaste: vi.fn(),
  regeneratePlan: vi.fn(),
  reorderStops: vi.fn(),
  setTripDriver: vi.fn(),
  setTripVehicle: vi.fn(),
  setVehicleDrivers: vi.fn(),
  updateDriver: vi.fn(),
  updateOrderLocation: vi.fn(),
  updateVehicleProfile: vi.fn(),
}));

const auth = reactive<{ factoryId: string; user: { id: number } | null }>({
  factoryId: 'F-A',
  user: { id: 101 },
});

vi.mock('@/api/logistics', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));

function page<T>(content: T[]) {
  return { data: { content, totalElements: content.length, totalPages: 1, size: 1000, number: 0 } };
}

const orderA = {
  id: 'ORDER-A',
  storeName: 'A门店',
  address: 'A地址',
  boxes: 1,
  pieces: 2,
  weightKg: 3,
  volumeCbm: 0.1,
  longitude: 121.47,
  latitude: 31.23,
  locationStatus: 'RESOLVED',
};

const planA = {
  id: 'PLAN-A',
  factoryId: 'F-A',
  orderBatchId: 'BATCH-A',
  planDate: '2026-11-15',
  planNumber: 'PLAN-A-001',
  targetLoadPct: 88,
  status: 'DRAFT',
  distanceSource: 'MAINTAINED_MATRIX',
  totalStores: 1,
  totalTrips: 0,
  totalDistanceKm: 0,
  version: 1,
  trips: [],
  unassignedStoreIds: [],
  assignedVehicleCount: 0,
  additionalVehicleCount: 0,
};

const batchA = {
  id: 'BATCH-A',
  factoryId: 'F-A',
  businessDate: '2026-11-15',
  source: 'MANUAL',
  status: 'IMPORTED',
  totalOrders: 1,
  validOrders: 1,
  invalidOrders: 0,
  createdAt: '2026-11-15T00:00:00Z',
};

describe('useLogisticsScheduling identity isolation', () => {
  it('uses the actual workbench handlers to deny readonly/pending writes and retain an authorized manual preview', async () => {
    api.listVehicles.mockResolvedValue({ data: [] }); api.listDrivers.mockResolvedValue({ data: [] }); api.listPlans.mockResolvedValue(page([]));
    const wrapper = shallowMount(Workbench, { props: { employeeCanWrite: false, employeeNavigationPending: true } }); await flushPromises();
    const vm = wrapper.vm as unknown as { submitManual(payload: { businessDate: null; rows: [] }): Promise<void>; regenerateRoutes(): Promise<void>; confirmSchedule(): Promise<void> };
    await vm.submitManual({ businessDate: null, rows: [] }); await vm.regenerateRoutes(); await vm.confirmSchedule();
    expect(api.createOrdersManual).not.toHaveBeenCalled(); expect(api.regeneratePlan).not.toHaveBeenCalled(); expect(api.confirmPlan).not.toHaveBeenCalled();
    await wrapper.setProps({ employeeNavigationPending: false }); await flushPromises();
    await vm.submitManual({ businessDate: null, rows: [] });
    expect(api.createOrdersManual).not.toHaveBeenCalled();
    api.createOrdersManual.mockResolvedValueOnce({ data: { validRows: 0, jobId: 'PREVIEW' } });
    await wrapper.setProps({ employeeCanWrite: true });
    await vm.submitManual({ businessDate: null, rows: [] });
    expect(api.createOrdersManual).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });
  it('does not commit manual preview after the actor changes while preview is pending', async () => {
    let finish!: (value: unknown) => void;
    api.createOrdersManual.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const state = useLogisticsScheduling();
    const pending = state.submitManualOrders(null, []);
    auth.user = { id: 202 };
    finish({ data: { validRows: 1, jobId: 'OLD-PREVIEW' } });
    await pending;
    expect(api.createOrdersManual).toHaveBeenCalledTimes(1);
    expect(api.commitOrderImport).not.toHaveBeenCalled();
  });
  it.each(['exportCsv', 'exportXlsx'] as const)('does not download delayed %s data after tenant changes', async (method) => {
    const state = useLogisticsScheduling();
    state.plan.value = planA as unknown as NonNullable<typeof state.plan.value>;
    const mock = method === 'exportCsv' ? api.exportPlanCsv : api.exportPlanXlsx;
    let finish!: (value: unknown) => void;
    mock.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const createElement = vi.spyOn(document, 'createElement');
    const pending = state[method]();
    auth.factoryId = 'F-B';
    finish({ data: new Blob(['old tenant']) });
    await pending;
    expect(mock).toHaveBeenCalledTimes(1);
    expect(createElement).not.toHaveBeenCalled();
    createElement.mockRestore();
  });
  beforeEach(() => {
    Object.values(api).forEach((mock) => mock.mockReset());
    auth.factoryId = 'F-A';
    auth.user = { id: 101 };
    resetLogisticsScheduling();
  });

  it('creates fresh plan and orders refs when factory or actor changes', async () => {
    api.listOrders.mockResolvedValue(page([orderA]));
    api.getPlan.mockResolvedValue({ data: planA });
    api.getOrderBatch.mockResolvedValue({ data: batchA });

    const state = useLogisticsScheduling();
    const oldOrders = state.orders;
    const oldPlan = state.plan;
    await state.loadOrders('BATCH-A');
    await state.openPlan('PLAN-A');
    expect(oldOrders.value).toHaveLength(1);
    expect(oldPlan.value?.id).toBe('PLAN-A');

    auth.factoryId = 'F-B';
    auth.user = { id: 202 };
    const next = useLogisticsScheduling();

    expect(next.orders).not.toBe(oldOrders);
    expect(next.plan).not.toBe(oldPlan);
    expect(next.orders.value).toEqual([]);
    expect(next.plan.value).toBeNull();
    expect(oldOrders.value[0].id).toBe('ORDER-A');
    expect(oldPlan.value?.factoryId).toBe('F-A');
  });

  it('keeps delayed loadOrders and openPlan responses inside the abandoned identity state', async () => {
    let resolveOrders!: (value: unknown) => void;
    api.listOrders.mockImplementationOnce(() => new Promise((resolve) => { resolveOrders = resolve; }));
    const state = useLogisticsScheduling();
    const oldOrders = state.orders;
    const loadingOldOrders = state.loadOrders('BATCH-A');

    auth.factoryId = 'F-B';
    auth.user = { id: 202 };
    const next = useLogisticsScheduling();
    resolveOrders(page([orderA]));
    await loadingOldOrders;
    expect(next.orders.value).toEqual([]);
    expect(oldOrders.value).toHaveLength(1);

    let resolvePlan!: (value: unknown) => void;
    api.getPlan.mockImplementationOnce(() => new Promise((resolve) => { resolvePlan = resolve; }));
    api.getOrderBatch.mockResolvedValue({ data: batchA });
    api.listOrders.mockResolvedValue(page([orderA]));
    const oldOpenPlan = state.openPlan;
    const oldPlan = state.plan;
    const openingOldPlan = oldOpenPlan('PLAN-A');

    auth.factoryId = 'F-C';
    auth.user = { id: 303 };
    const newest = useLogisticsScheduling();
    resolvePlan({ data: planA });
    await openingOldPlan;
    expect(newest.plan.value).toBeNull();
    expect(newest.orders.value).toEqual([]);
    expect(oldPlan.value?.id).toBe('PLAN-A');
  });

  it('does not call the API without both factory and actor identity', async () => {
    auth.factoryId = '';
    auth.user = { id: 101 };
    resetLogisticsScheduling();
    const stateWithoutFactory = useLogisticsScheduling();
    await stateWithoutFactory.loadOrders('BATCH-A');
    expect(await stateWithoutFactory.openPlan('PLAN-A')).toBe(false);

    auth.factoryId = 'F-B';
    auth.user = null;
    const stateWithoutActor = useLogisticsScheduling();
    await stateWithoutActor.loadOrders('BATCH-B');
    expect(await stateWithoutActor.openPlan('PLAN-B')).toBe(false);
    expect(api.listOrders).not.toHaveBeenCalled();
    expect(api.getPlan).not.toHaveBeenCalled();
    expect(api.getOrderBatch).not.toHaveBeenCalled();
  });

  it('prevents a captured old method from sending under a new tenant', async () => {
    const state = useLogisticsScheduling();
    const oldLoadOrders = state.loadOrders;
    const oldOrdersError = state.ordersError;

    auth.factoryId = 'F-B';
    auth.user = { id: 202 };
    const next = useLogisticsScheduling();
    await oldLoadOrders('BATCH-A');

    expect(api.listOrders).not.toHaveBeenCalled();
    expect(oldOrdersError.value).toContain('账号或企业已切换');
    expect(next.ordersError.value).toBeNull();
  });
});
