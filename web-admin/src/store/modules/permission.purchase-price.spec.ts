import { describe, expect, it, beforeEach, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { usePermissionStore } from './permission';

const permissionApiMocks = vi.hoisted(() => ({
  getPlatformPermissions: vi.fn(),
  getFactoryOverride: vi.fn(),
  getUserModuleAccess: vi.fn(),
  getCurrentModulePermissions: vi.fn(),
}));

vi.mock('@/api/permissionApi', () => permissionApiMocks);

// S5-12: 采购价可见性跟工厂配置的 procurement 读权限走, 不跟固定角色名单 (canViewPrice) 走。
describe('permission store purchase price visibility follows configured procurement read', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    permissionApiMocks.getPlatformPermissions.mockReset().mockResolvedValue([]);
    permissionApiMocks.getFactoryOverride.mockReset().mockResolvedValue({});
    permissionApiMocks.getUserModuleAccess.mockReset().mockResolvedValue([]);
  });

  it('finance_manager in the old price list but procurement "-" cannot see purchase prices; granting r flips it', async () => {
    permissionApiMocks.getCurrentModulePermissions.mockResolvedValueOnce({ procurement: '-', finance: 'rw' });
    const store = usePermissionStore();
    store.setRole('finance_manager', 'F006', 'FACTORY', '21', { skipDbLoad: true });
    await store.loadFromDb();

    expect(store.canViewPrice).toBe(true);
    expect(store.canViewPurchasePrice).toBe(false);

    permissionApiMocks.getCurrentModulePermissions.mockResolvedValueOnce({ procurement: 'r', finance: 'rw' });
    store.setRole('finance_manager', 'F006', 'FACTORY', '22', { skipDbLoad: true });
    await store.loadFromDb();

    expect(store.canViewPurchasePrice).toBe(true);
  });

  it('warehouse_manager outside the old price list sees purchase prices when config grants procurement read', async () => {
    permissionApiMocks.getCurrentModulePermissions.mockResolvedValueOnce({ procurement: 'r', warehouse: 'rw' });
    const store = usePermissionStore();
    store.setRole('warehouse_manager', 'F006', 'FACTORY', '31', { skipDbLoad: true });
    await store.loadFromDb();

    expect(store.canViewPrice).toBe(false);
    expect(store.canViewPurchasePrice).toBe(true);
  });
});
