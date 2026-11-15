import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useNavigationVisibilityStore, navigationPermissionsReady } from '../navigationVisibility';

const identity = {
  actorId: '43',
  factoryId: 'F006',
  role: 'factory_super_admin',
  factoryType: 'FACTORY',
  isPlatform: false,
};

describe('navigation visibility snapshot', () => {
  it('does not publish the previous actor permissions as the new same-factory actor while authoritative loading is delayed', () => {
    const permission = { currentUserId: '43', currentFactoryId: identity.factoryId, currentRole: identity.role, isDbLoaded: true, isUserModuleAccessLoaded: true };
    const next = { ...identity, actorId: '44' };
    expect(navigationPermissionsReady(identity, permission)).toBe(true);
    expect(navigationPermissionsReady(next, permission)).toBe(false);
    permission.currentUserId = '44'; permission.isDbLoaded = false; permission.isUserModuleAccessLoaded = false;
    expect(navigationPermissionsReady(next, permission)).toBe(false);
    permission.isDbLoaded = true;
    expect(navigationPermissionsReady(next, permission)).toBe(false);
    permission.isUserModuleAccessLoaded = true;
    expect(navigationPermissionsReady(next, permission)).toBe(true);
  });
  it('preserves the existing DEMO mockDemo permission lane without exempting ordinary tenant readiness', () => {
    const permission = { currentUserId: '43', currentFactoryId: 'DEMO_LOGISTICS', currentRole: identity.role, isDbLoaded: false, isUserModuleAccessLoaded: false };
    const demo = { ...identity, factoryId: permission.currentFactoryId };
    expect(navigationPermissionsReady(demo, permission, true)).toBe(true);
    expect(navigationPermissionsReady(demo, permission, false)).toBe(false);
    expect(navigationPermissionsReady({ ...demo, actorId: '44' }, permission, true)).toBe(false);
  });
  beforeEach(() => setActivePinia(createPinia()));

  it('publishes only deduplicated paths from the fully filtered sidebar', () => {
    const store = useNavigationVisibilityStore();
    store.publish({ ...identity, paths: ['/dashboard', '/workspaces/overview', '/dashboard'] });

    expect(store.snapshot?.paths).toEqual(['/dashboard', '/workspaces/overview']);
    expect(store.status).toBe('ready');
    expect(store.isPathVisible('/workspaces/overview', identity)).toBe(true);
    expect(store.isPathVisible('/workspaces/exceptions', identity)).toBe(false);
  });

  it('drops the previous tenant layout profile on invalidation and default-tenant publication', () => {
    const store = useNavigationVisibilityStore();
    store.publish({ ...identity, paths: ['/sales/customers'], menuProfile: 'wholesale' });
    expect(store.snapshot?.menuProfile).toBe('wholesale');
    store.beginLoading();
    expect(store.snapshot).toBeNull();
    store.publish({ ...identity, factoryId: 'OTHER', paths: ['/sales/customers'] });
    expect(store.snapshot?.menuProfile).toBeUndefined();
  });

  it('keeps the snapshot empty while authoritative sidebar configuration is pending or invalid', () => {
    const store = useNavigationVisibilityStore();
    store.publish({ ...identity, paths: ['/workspaces/overview'] });

    store.invalidate();
    expect(store.status).toBe('loading');
    expect(store.snapshot).toBeNull();
    expect(store.isPathVisible('/workspaces/overview', identity)).toBe(false);

    store.markError();
    expect(store.status).toBe('error');
    expect(store.snapshot).toBeNull();
  });

  it('does not let a previous factory response publish after a newer load starts', () => {
    const store = useNavigationVisibilityStore();
    const f006Load = store.beginLoading();
    expect(store.status).toBe('loading');
    expect(store.snapshot).toBeNull();

    const f007Load = store.beginLoading();
    expect(store.markReadyIfCurrent(f006Load)).toBe(false);
    expect(store.status).toBe('loading');
    expect(store.snapshot).toBeNull();

    expect(store.markReadyIfCurrent(f007Load)).toBe(true);
    store.publish({ ...identity, factoryId: 'F007', paths: ['/workspaces/overview'] });
    expect(store.isPathVisible('/workspaces/overview', { ...identity, factoryId: 'F007' })).toBe(true);
    expect(store.isPathVisible('/workspaces/overview', identity)).toBe(false);
  });

  it('fails closed for missing or stale factory identity', () => {
    const store = useNavigationVisibilityStore();
    store.publish({ ...identity, paths: ['/workspaces/overview'] });

    expect(store.isPathVisible('/workspaces/overview', { ...identity, factoryId: '' })).toBe(false);
    expect(store.isPathVisible('/workspaces/overview', { ...identity, factoryId: 'F007' })).toBe(false);
    expect(store.isPathVisible('/workspaces/overview', { ...identity, role: 'permission_admin' })).toBe(false);
    expect(store.isPathVisible('/workspaces/overview', { ...identity, factoryType: 'BRANCH' })).toBe(false);
    expect(store.isPathVisible('/workspaces/overview', { ...identity, isPlatform: true })).toBe(false);
  });

  it('clears the snapshot instead of retaining navigation after sidebar teardown', () => {
    const store = useNavigationVisibilityStore();
    store.publish({ ...identity, paths: ['/workspaces/overview'] });
    store.clear();

    expect(store.snapshot).toBeNull();
    expect(store.isPathVisible('/workspaces/overview', identity)).toBe(false);
  });
});
