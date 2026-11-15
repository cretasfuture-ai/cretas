import { computed, reactive, ref } from 'vue';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import OrdersPage from '../orders/index.vue';
import ResourcesPage from '../resources/index.vue';
import RecordsPage from '../records/index.vue';

const auth = reactive({ factoryId: 'F-A', user: { id: 101 } as { id: number } | null, currentRole: 'dispatcher-a' });
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));

const availabilityApi = vi.hoisted(() => ({
  list: vi.fn(),
  upsert: vi.fn(),
  remove: vi.fn(),
}));
vi.mock('@/api/logistics', () => ({
  listDailyAvailability: availabilityApi.list,
  upsertDailyAvailability: availabilityApi.upsert,
  deleteDailyAvailability: availabilityApi.remove,
}));

const orderA = { id: 'ORDER-A', storeName: 'A敏感门店', address: 'A地址', boxes: 1, pieces: 1, weightKg: 1, volumeCbm: 1, longitude: 120, latitude: 30, locationStatus: 'RESOLVED' };
const orderB = { ...orderA, id: 'ORDER-B', storeName: 'B门店' };
const vehicleA = { id: 'VEHICLE-A', plateNumber: 'A-SENSITIVE', capacityCbm: 10, maxWeightKg: 100, source: 'OWNED', bodyType: '冷藏', serviceAreas: ['A'], availableFrom: '08:00', availableTo: '18:00', active: true, version: 1, drivers: [] };
const vehicleB = { ...vehicleA, id: 'VEHICLE-B', plateNumber: 'B-PLATE', serviceAreas: ['B'] };
const driverA = { id: 'DRIVER-A', name: 'A敏感司机', phone: '13000000000', employmentType: 'OWNED', serviceAreas: ['A'], availableFrom: '08:00', availableTo: '18:00', active: true };
const driverB = { ...driverA, id: 'DRIVER-B', name: 'B司机', phone: '' };
const planA = { id: 'PLAN-A', planNumber: 'A敏感计划', planDate: '2026-11-15', status: 'CONFIRMED', totalStores: 1, totalTrips: 0, totalDistanceKm: 0 };
const planB = { ...planA, id: 'PLAN-B', planNumber: 'B计划' };

function page<T>(content: T[]) {
  return { content, totalElements: content.length, totalPages: 1, size: 20, number: 0 };
}

function scope(label: 'A' | 'B') {
  const isA = label === 'A';
  const order = isA ? orderA : orderB;
  const vehicle = isA ? vehicleA : vehicleB;
  const driver = isA ? driverA : driverB;
  const planRow = isA ? planA : planB;
  return {
    batches: ref([{ id: `BATCH-${label}`, factoryId: `F-${label}`, businessDate: '2026-11-15', source: 'MANUAL', status: 'IMPORTED', totalOrders: 1, validOrders: 1, invalidOrders: 0, createdAt: '' }]),
    ordersPage: ref(page([order])), orders: ref([order]), ordersLoading: ref(false), ordersError: ref<string | null>(null),
    vehicles: ref([vehicle]), drivers: ref([driver]), resourcesLoading: ref(false), resourcesError: ref<string | null>(null),
    planHistory: ref(page([planRow])), recordsLoading: ref(false), recordsError: ref<string | null>(null),
    plan: ref(null), planError: ref<string | null>(null), executionBusyId: ref<string | null>(null),
    stores: ref([]), scheduleResult: ref({ trips: [], unassignedStoreIds: [], assignedVehicleCount: 0, additionalVehicleCount: 0 }),
    selectedTripId: ref<string | null>(null), selectedStoreId: ref<string | null>(null),
    loadBatches: vi.fn().mockResolvedValue(undefined), loadOrdersPage: vi.fn().mockResolvedValue(undefined),
    downloadTemplate: vi.fn().mockResolvedValue(undefined), updateLocation: vi.fn().mockResolvedValue(true),
    loadResources: vi.fn().mockResolvedValue(undefined), saveVehicleProfile: vi.fn().mockResolvedValue(true),
    saveVehicleDrivers: vi.fn().mockResolvedValue(true), saveDriver: vi.fn().mockResolvedValue(true), addDriver: vi.fn().mockResolvedValue(true),
    loadPlanHistory: vi.fn().mockResolvedValue(undefined), openPlan: vi.fn().mockResolvedValue(true),
    markStoreDelivered: vi.fn().mockResolvedValue(true), markStoreException: vi.fn().mockResolvedValue(true), resetStoreDelivery: vi.fn().mockResolvedValue(true),
    exportCsv: vi.fn().mockResolvedValue(undefined), exportXlsx: vi.fn().mockResolvedValue(undefined),
    selectTrip: vi.fn(), selectStore: vi.fn(),
  };
}

let stateA = scope('A');
let stateB = scope('B');
const stateProxy = new Proxy({} as ReturnType<typeof scope>, {
  get(_target, property) { return Reflect.get(auth.factoryId === 'F-B' ? stateB : stateA, property); },
});
vi.mock('../useLogisticsScheduling', () => ({ useLogisticsScheduling: () => stateProxy }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), RouterLink: { template: '<a><slot /></a>' } }));

const wrappers: VueWrapper[] = [];
function render(component: typeof OrdersPage | typeof ResourcesPage | typeof RecordsPage) {
  const wrapper = mount(component, {
    global: {
      plugins: [ElementPlus],
      stubs: { teleport: true, LogisticsMap: true, RouteCards: true, ScheduleTimetable: true, ExecutionTracker: true, RouterLink: true },
    },
  });
  wrappers.push(wrapper);
  return wrapper;
}
function switchToB() {
  auth.factoryId = 'F-B'; auth.user = { id: 202 }; auth.currentRole = 'dispatcher-b';
}

beforeEach(() => {
  auth.factoryId = 'F-A'; auth.user = { id: 101 }; auth.currentRole = 'dispatcher-a';
  stateA = scope('A'); stateB = scope('B');
  availabilityApi.list.mockReset().mockResolvedValue({ data: [] });
  availabilityApi.upsert.mockReset().mockResolvedValue({ data: {} });
  availabilityApi.remove.mockReset().mockResolvedValue({ data: {} });
});
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()));

describe('logistics consumer page identity isolation', () => {
  it('clears A dialogs, performs zero stale writes, and reloads B across all three mounted pages', async () => {
    const orders = render(OrdersPage);
    await flushPromises();
    (orders.vm as unknown as { openDetail: (order: typeof orderA) => void }).openDetail(orderA);
    await flushPromises();
    expect(orders.text()).toContain('A敏感门店');
    switchToB(); await flushPromises();
    expect(orders.text()).not.toContain('A敏感门店');
    await (orders.vm as unknown as { saveLocation: () => Promise<void> }).saveLocation();
    expect(stateA.updateLocation).not.toHaveBeenCalled(); expect(stateB.updateLocation).not.toHaveBeenCalled();
    expect(stateB.loadBatches).toHaveBeenCalledTimes(1); expect(stateB.loadOrdersPage).toHaveBeenCalledWith('BATCH-B', 0, 20);
    orders.unmount(); wrappers.splice(wrappers.indexOf(orders), 1);

    auth.factoryId = 'F-A'; auth.user = { id: 101 }; auth.currentRole = 'dispatcher-a';
    const resources = render(ResourcesPage); await flushPromises();
    (resources.vm as unknown as { openEditDriver: (driver: typeof driverA) => void }).openEditDriver(driverA);
    await flushPromises(); expect(resources.text()).toContain('A敏感司机');
    switchToB(); await flushPromises();
    expect(resources.text()).not.toContain('A敏感司机');
    await (resources.vm as unknown as { saveDriverForm: () => Promise<void> }).saveDriverForm();
    expect(stateA.saveDriver).not.toHaveBeenCalled(); expect(stateB.saveDriver).not.toHaveBeenCalled();
    expect(stateB.loadResources).toHaveBeenCalledTimes(1);
    expect(availabilityApi.list).toHaveBeenCalledWith('F-B', expect.any(String));
    resources.unmount(); wrappers.splice(wrappers.indexOf(resources), 1);

    auth.factoryId = 'F-A'; auth.user = { id: 101 }; auth.currentRole = 'dispatcher-a';
    let finishOld!: (value: boolean) => void;
    stateA.openPlan.mockImplementationOnce(() => new Promise((resolve) => { finishOld = resolve; }));
    const records = render(RecordsPage); await flushPromises();
    const opening = (records.vm as unknown as { showDetails: (plan: typeof planA) => Promise<void> }).showDetails(planA);
    await flushPromises(); expect(records.text()).toContain('A敏感计划');
    switchToB(); await flushPromises();
    expect(records.text()).not.toContain('A敏感计划');
    await (records.vm as unknown as { onDeliver: (id: string) => Promise<void> }).onDeliver('ORDER-A');
    expect(stateA.markStoreDelivered).not.toHaveBeenCalled(); expect(stateB.markStoreDelivered).not.toHaveBeenCalled();
    finishOld(true); await opening; await flushPromises();
    expect(records.text()).not.toContain('A敏感计划');
    expect(stateB.loadPlanHistory).toHaveBeenCalledTimes(1);
  });

  it('does not issue initial requests without complete factory, actor, and role identity', async () => {
    auth.factoryId = ''; auth.user = null; auth.currentRole = '';
    render(OrdersPage); render(ResourcesPage); render(RecordsPage);
    await flushPromises();
    expect(stateA.loadBatches).not.toHaveBeenCalled();
    expect(stateA.loadResources).not.toHaveBeenCalled();
    expect(stateA.loadPlanHistory).not.toHaveBeenCalled();
    expect(availabilityApi.list).not.toHaveBeenCalled();
  });
});
