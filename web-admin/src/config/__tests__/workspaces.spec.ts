import { describe, expect, it } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';
import router from '@/router';
import { menuConfig, type MenuItem } from '@/components/layout/menuConfig';
import { useAuthStore } from '@/store/modules/auth';
import type { User } from '@/types/auth';
import {
  FACTORY_WORKSPACES,
  resolveWorkspaceFactoryTypeFromUser,
  visibleFactoryWorkspaces,
  visibleWorkspaceLinks,
  type WorkspaceVisibilityContext,
} from '../workspaces';

function context(overrides: Partial<WorkspaceVisibilityContext> = {}): WorkspaceVisibilityContext {
  return {
    role: 'factory_super_admin',
    factoryType: 'FACTORY',
    factoryId: 'F006',
    isPlatform: false,
    canAccess: () => true,
    canVisitRoute,
    isPathVisible: canSeeSidebarPath,
    ...overrides,
  };
}

function canVisitRoute(
  path: string,
  context: WorkspaceVisibilityContext,
  expectedModule: string,
): boolean {
  const canonicalPath = path.split('?')[0];
  const resolved = router.resolve(path);
  if (resolved.matched.length === 0 || resolved.path !== canonicalPath) return false;
  const meta = resolved.meta as { hidden?: boolean; module?: string; roles?: unknown; hideForFactoryTypes?: unknown };
  if (meta.hidden === true || meta.module !== expectedModule) return false;
  if (Array.isArray(meta.roles) && !meta.roles.includes(context.role)) return false;
  if (Array.isArray(meta.hideForFactoryTypes) && meta.hideForFactoryTypes.includes(context.factoryType)) return false;
  return true;
}

function canSeeSidebarPath(path: string, context: WorkspaceVisibilityContext): boolean {
  const canonicalPath = path.split('?')[0];
  const findMenuChain = (items: readonly MenuItem[], chain: MenuItem[] = []): MenuItem[] | undefined => {
    for (const item of items) {
      const nextChain = [...chain, item];
      if (item.path === canonicalPath) return nextChain;
      const childChain = item.children?.length ? findMenuChain(item.children, nextChain) : undefined;
      if (childChain) return childChain;
    }
    return undefined;
  };
  const menuChain = findMenuChain(menuConfig);
  return menuChain?.every((item) => (
    (!item.roles?.length || item.roles.includes(context.role))
    && (!item.hideForFactoryTypes?.includes(context.factoryType))
    && (!item.allowedFactoryTypes?.length || item.allowedFactoryTypes.includes(context.factoryType))
    && (!item.requiresFactoryContext || (Boolean(context.factoryId?.trim()) && context.isPlatform === false))
  )) ?? false;
}

describe('factory workspace catalog', () => {
  it('defines the four named workspaces with reachable router records', () => {
    expect(FACTORY_WORKSPACES.map((workspace) => [workspace.path, workspace.title])).toEqual([
      ['/workspaces/overview', '经营总览'],
      ['/workspaces/exceptions', '异常中心'],
      ['/workspaces/plans-approvals', '方案与审批'],
      ['/workspaces/trace-review', '追溯与复盘'],
    ]);

    for (const workspace of FACTORY_WORKSPACES) {
      expect(router.resolve(workspace.path).matched.length, workspace.path).toBeGreaterThan(0);
      expect(workspace.links.length, workspace.path).toBeGreaterThan(0);
      for (const link of workspace.links) {
        expect(router.resolve(link.path).matched.length, link.path).toBeGreaterThan(0);
      }
    }
  });

  it.each([
    ['factory_super_admin', 'FACTORY'],
    ['permission_admin', 'CENTRAL_KITCHEN'],
  ])('allows eligible factory administrator context: %s/%s', (role, factoryType) => {
    const workspaces = visibleFactoryWorkspaces(context({ role, factoryType }));
    expect(workspaces).toHaveLength(4);
    expect(workspaces.every((workspace) => workspace.links.length > 0)).toBe(true);
  });

  it.each([
    ['warehouse_worker', 'FACTORY', false],
    ['finance_manager', 'FACTORY', false],
    ['factory_super_admin', 'RESTAURANT', false],
    ['factory_super_admin', 'LOGISTICS', false],
    ['factory_super_admin', 'HEADQUARTERS', false],
    ['factory_super_admin', 'BRANCH', false],
    ['factory_super_admin', '', false],
    ['unknown_role', 'FACTORY', false],
    ['factory_super_admin', 'FACTORY', true],
  ])('hides the catalog for ineligible context: %s/%s/platform=%s', (role, factoryType, isPlatform) => {
    expect(visibleFactoryWorkspaces(context({ role, factoryType, isPlatform }))).toEqual([]);
  });

  it('requires a non-empty factory tenant even for an otherwise eligible role and type', () => {
    expect(visibleFactoryWorkspaces(context({ factoryId: '' }))).toEqual([]);
    expect(visibleFactoryWorkspaces(context({ factoryId: null }))).toEqual([]);
  });

  it.each([undefined, ''])('does not inherit authStore FACTORY fallback for raw factoryType=%s', (rawFactoryType) => {
    setActivePinia(createPinia());
    const auth = useAuthStore();
    auth.setUser({
      id: 1,
      username: 'workspace-admin',
      email: '',
      isActive: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      userType: 'factory',
      factoryUser: {
        role: 'factory_super_admin',
        factoryId: 'F006',
        factoryType: rawFactoryType,
        permissions: [],
      },
    } as User);

    expect(auth.factoryType).toBe('FACTORY');
    expect(resolveWorkspaceFactoryTypeFromUser(auth.user)).toBe('');
  });

  it('uses only the raw factory-user structure for workspace factory type', () => {
    expect(resolveWorkspaceFactoryTypeFromUser(null)).toBe('');
    expect(resolveWorkspaceFactoryTypeFromUser({ userType: 'platform', factoryUser: { factoryType: 'FACTORY' } })).toBe('');
    expect(resolveWorkspaceFactoryTypeFromUser({ userType: 'factory' })).toBe('');
    expect(resolveWorkspaceFactoryTypeFromUser({ userType: 'factory', factoryUser: { factoryType: 'BRANCH' } })).toBe('');
    expect(resolveWorkspaceFactoryTypeFromUser({ userType: 'factory', factoryUser: { factoryType: 'CENTRAL_KITCHEN' } })).toBe('CENTRAL_KITCHEN');
  });

  it('filters each existing business entry by its live module permission', () => {
    const exceptions = FACTORY_WORKSPACES.find((workspace) => workspace.id === 'exceptions')!;
    const links = visibleWorkspaceLinks(exceptions, context({ canAccess: (module) => module === 'dashboard' }));
    expect(links).toEqual([]);

    const overview = FACTORY_WORKSPACES.find((workspace) => workspace.id === 'overview')!;
    const dashboardOnly = visibleWorkspaceLinks(overview, context({ canAccess: (module) => module === 'dashboard' }));
    expect(dashboardOnly.map((link) => link.path)).toEqual(['/dashboard']);
  });

  it('does not let permission_admin revive the owner-hidden AI report menu entry', () => {
    const overview = FACTORY_WORKSPACES.find((workspace) => workspace.id === 'overview')!;
    const links = visibleWorkspaceLinks(overview, context({ role: 'permission_admin' }));
    expect(links.map((link) => link.path)).toEqual(['/dashboard']);
  });

  it('keeps an eligible empty workspace explicit rather than inventing an entry', () => {
    const workspaces = visibleFactoryWorkspaces(context({ canAccess: (module) => module === 'dashboard' }));
    expect(workspaces).toHaveLength(4);
    expect(workspaces.find((workspace) => workspace.id === 'exceptions')?.links).toEqual([]);
    expect(workspaces.find((workspace) => workspace.id === 'plans-approvals')?.links).toEqual([
      expect.objectContaining({ path: '/workflow/pending' }),
    ]);
  });
});
