import { describe, expect, it, vi } from 'vitest';
import router from '../index';
import { menuConfig, platformMenuConfig, type MenuItem } from '@/components/layout/menuConfig';

// Inspect the real registered route without attaching global login/network guards.
vi.mock('../guards', () => ({ setupRouterGuards: vi.fn() }));

const path = '/scheduling/proposal-comparison';
const roles = ['factory_super_admin', 'permission_admin'];

function leaves(items: MenuItem[]): MenuItem[] {
  return items.flatMap(item => item.children?.length ? leaves(item.children) : [item]);
}

describe('proposal comparison route and navigation', () => {
  it('resolves the concrete authenticated scheduling route rather than a fallback or demo', () => {
    const resolved = router.resolve(path);
    expect(resolved.name).toBe('SchedulingProposalComparison');
    expect(resolved.matched.at(-1)?.path).toBe(path);
    expect(resolved.meta.requiresAuth).toBe(true);
    expect(resolved.meta.module).toBe('scheduling');
    expect(resolved.meta.mockDemo).not.toBe(true);
    expect(resolved.meta.roles).toEqual(roles);
    expect(resolved.meta.hideForFactoryTypes).toEqual(['LOGISTICS']);
  });

  it('exposes exactly one factory scheduling menu entry with the same two roles', () => {
    const matches = leaves(menuConfig).filter(item => item.path === path);
    expect(matches).toHaveLength(1);
    expect(matches[0]).toMatchObject({title: '多方案比较', module: 'scheduling', roles,
      hideForFactoryTypes: ['LOGISTICS']});
    const scheduling = menuConfig.find(item => item.path === '/scheduling');
    expect(scheduling?.children).toContain(matches[0]);
    expect(leaves(platformMenuConfig).some(item => item.path === path)).toBe(false);
  });

  it('keeps operational and platform roles outside this new comparison route', () => {
    const allowed = router.resolve(path).meta.roles as string[];
    for (const role of ['production_manager', 'warehouse_manager', 'finance_manager',
      'operator', 'platform_admin']) {
      expect(allowed).not.toContain(role);
    }
    expect(leaves(menuConfig).find(item => item.path === '/scheduling/plans')).toBeDefined();
  });
});
