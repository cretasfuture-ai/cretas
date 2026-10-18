import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { usePermissionStore } from './permission';

const api = vi.hoisted(() => ({ getCurrentModulePermissions: vi.fn(), getPlatformPermissions: vi.fn(), getFactoryOverride: vi.fn(), getUserModuleAccess: vi.fn() }));
vi.mock('@/api/permissionApi', () => api);

beforeEach(() => {
  setActivePinia(createPinia());
  vi.resetAllMocks();
  api.getPlatformPermissions.mockResolvedValue([]);
  api.getFactoryOverride.mockResolvedValue({});
  api.getUserModuleAccess.mockResolvedValue([]);
  api.getCurrentModulePermissions.mockResolvedValue({});
});

function storeAs(role: string, factory = 'TEST-NEW', user = '9') {
  const store = usePermissionStore();
  store.setRole(role, factory, 'FACTORY', user, { skipDbLoad: true });
  return store;
}

describe('new factory authoritative role resolution', () => {
  it.each([
    ['procurement_manager', 'procurement', true],
    ['warehouse_manager', 'warehouse', false],
    ['production_manager', 'production', true],
    ['sales_manager', 'sales', true],
    ['finance_manager', 'finance', true],
  ] as const)('%s keeps its authorized operation and price boundary', async (role, module, price) => {
    api.getCurrentModulePermissions.mockResolvedValue({ [module]: 'rw' });
    const store = storeAs(role);
    await store.loadFromDb();
    expect(store.isDbLoaded).toBe(true);
    expect(store.getPermissionLevel(module)).toBe('rw');
    expect(store.canViewPrice).toBe(price);
    if (role !== 'finance_manager') expect(store.canWrite('finance')).toBe(false);
    expect(api.getCurrentModulePermissions).toHaveBeenCalledWith('TEST-NEW');
    expect(api.getPlatformPermissions).not.toHaveBeenCalled();
    expect(api.getFactoryOverride).not.toHaveBeenCalled();
  });

  it('does not resurrect the four extra frontend sales defaults', async () => {
    api.getCurrentModulePermissions.mockResolvedValue({ sales: 'rw', dashboard: 'r', system: 'r', rd: 'rw', report: 'r' });
    const store = storeAs('sales_manager');
    await store.loadFromDb();
    for (const module of ['production', 'warehouse', 'finance', 'analytics'] as const) {
      expect(store.canAccess(module), module).toBe(false);
      expect(store.canWrite(module), module).toBe(false);
    }
  });

  it('preserves explicit server denial and read-only levels', async () => {
    api.getCurrentModulePermissions.mockResolvedValue({ procurement: '-', warehouse: 'r' });
    const store = storeAs('procurement_manager');
    await store.loadFromDb();
    expect(store.canAccess('procurement')).toBe(false);
    expect(store.canWrite('procurement')).toBe(false);
    expect(store.canAccess('warehouse')).toBe(true);
    expect(store.canWrite('warehouse')).toBe(false);
  });

  it('an empty server snapshot cannot borrow local grants', async () => {
    const store = storeAs('production_manager');
    await store.loadFromDb();
    expect(store.canWrite('production')).toBe(false);
  });

  it('honors the backend administrator wildcard without overriding explicit denials', async () => {
    api.getCurrentModulePermissions.mockResolvedValue({ '*': 'rw', finance: '-' });
    const store = storeAs('factory_super_admin');
    await store.loadFromDb();
    expect(store.canWrite('production')).toBe(true);
    expect(store.canAccess('finance')).toBe(false);
  });

  it('failed authoritative loads deny access and can retry immediately', async () => {
    api.getCurrentModulePermissions.mockRejectedValueOnce(new Error('unavailable')).mockResolvedValueOnce({ procurement: 'rw' });
    const store = storeAs('procurement_manager');
    await store.loadFromDb();
    expect(store.isDbLoaded).toBe(false);
    expect(store.dbLoadError).toBe('unavailable');
    expect(store.canAccess('procurement')).toBe(false);
    await store.loadFromDb();
    expect(store.canWrite('procurement')).toBe(true);
  });

  it('a slower previous factory cannot replace the current identity snapshot', async () => {
    let resolveOld!: (levels: Record<string, 'rw'>) => void;
    api.getCurrentModulePermissions.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }))
      .mockResolvedValueOnce({ warehouse: 'r' });
    const store = storeAs('procurement_manager');
    const old = store.loadFromDb();
    store.setRole('warehouse_manager', 'TEST-OTHER', 'FACTORY', '10', { skipDbLoad: true });
    await store.loadFromDb();
    resolveOld({ finance: 'rw' });
    await old;
    expect(store.getPermissionLevel('warehouse')).toBe('r');
    expect(store.canWrite('finance')).toBe(false);
  });

  it('platform accounts retain the platform loader', async () => {
    api.getPlatformPermissions.mockResolvedValue([{ roleCode: 'platform_admin', moduleCode: 'system', permissionLevel: 'rw' }]);
    const store = storeAs('platform_admin', '', '1');
    await store.loadFromDb();
    expect(store.canWrite('system')).toBe(true);
    expect(api.getCurrentModulePermissions).not.toHaveBeenCalled();
  });

  it.each([
    ['restaurant_manager', ['rw', '-', 'r', '-', '-']],
    ['restaurant_purchaser', ['r', '-', '-', '-', 'rw']],
    ['sales_manager', ['-', 'rw', '-', '-', '-']],
    ['hr_admin', ['-', '-', 'rw', '-', '-']],
    ['finance_manager', ['-', '-', '-', 'rw', '-']],
    ['restaurant_chef', ['rw', '-', '-', '-', '-']],
    ['restaurant_owner', ['rw', 'rw', 'rw', 'rw', 'rw']],
  ] as const)('%s retains restaurant department boundaries after a sparse authority load', async (role, expected) => {
    api.getCurrentModulePermissions.mockResolvedValue({ restaurant: 'rw' });
    const store = storeAs(role);
    store.setRole(role, 'TEST-NEW', 'RESTAURANT', '9', { skipDbLoad: true });
    await store.loadFromDb();
    const departments = ['restaurantOps', 'restaurantMarketing', 'restaurantHr', 'restaurantFinance', 'restaurantProcurement'] as const;
    departments.forEach((department, index) => expect(store.getPermissionLevel(department), department).toBe(expected[index]));
  });

  it('explicit server department levels remain bounded by the server restaurant ceiling', async () => {
    api.getCurrentModulePermissions.mockResolvedValue({ restaurant: 'r', restaurantMarketing: 'rw', restaurantHr: '-' });
    const store = storeAs('restaurant_manager');
    store.setRole('restaurant_manager', 'TEST-NEW', 'RESTAURANT', '9', { skipDbLoad: true });
    await store.loadFromDb();
    expect(store.getPermissionLevel('restaurantMarketing')).toBe('r');
    expect(store.getPermissionLevel('restaurantHr')).toBe('-');
    expect(store.getPermissionLevel('restaurantOps')).toBe('r');
  });

  it('factory type denies restaurant departments even with an authoritative wildcard', async () => {
    api.getCurrentModulePermissions.mockResolvedValue({ '*': 'rw', restaurant: 'rw', restaurantHr: 'rw' });
    const store = storeAs('factory_super_admin');
    await store.loadFromDb();
    expect(store.canAccess('restaurantHr')).toBe(false);
    expect(store.canWrite('restaurantProcurement')).toBe(false);
  });
});
