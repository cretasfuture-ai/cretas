import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { factoryViewContext } from './platformFactoryContext';
import { usePermissionStore } from './permission';

vi.mock('@/api/permissionApi', () => ({
  getPlatformPermissions: vi.fn().mockResolvedValue([]),
  getFactoryOverride: vi.fn().mockResolvedValue({}),
  getUserModuleAccess: vi.fn().mockResolvedValue([]),
  getCurrentModulePermissions: vi.fn().mockResolvedValue({}),
}));

describe('platform factory view permission boundary', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    factoryViewContext.value = null;
  });

  it('keeps platform access but removes all UI write capability in factory view', () => {
    const store = usePermissionStore();
    store.setRole('platform_admin', '', '', '62', { skipDbLoad: true });
    expect(store.canAccess('production')).toBe(true);
    expect(store.canWrite('production')).toBe(true);
    factoryViewContext.value = {
      factoryId: 'F006', factoryName: '六膳门', factoryType: 'FACTORY',
      accessToken: 'view-token', expiresIn: 300, expiresAt: Date.now() + 300000, readOnly: true,
    };
    expect(store.canAccess('production')).toBe(true);
    expect(store.canWrite('production')).toBe(false);
  });
});

