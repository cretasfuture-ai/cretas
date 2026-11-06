import { beforeEach, describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import { useNavigationVisibilityStore } from '../navigationVisibility';

const identity = {
  factoryId: 'F006',
  role: 'factory_super_admin',
  factoryType: 'FACTORY',
  isPlatform: false,
};

describe('navigation visibility snapshot', () => {
  beforeEach(() => setActivePinia(createPinia()));

  it('publishes only deduplicated paths from the fully filtered sidebar', () => {
    const store = useNavigationVisibilityStore();
    store.publish({ ...identity, paths: ['/dashboard', '/workspaces/overview', '/dashboard'] });

    expect(store.snapshot?.paths).toEqual(['/dashboard', '/workspaces/overview']);
    expect(store.status).toBe('ready');
    expect(store.isPathVisible('/workspaces/overview', identity)).toBe(true);
    expect(store.isPathVisible('/workspaces/exceptions', identity)).toBe(false);
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
