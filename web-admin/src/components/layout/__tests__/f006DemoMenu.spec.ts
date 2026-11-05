import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import router from '@/router';
import { getF006DemoMenu } from '../f006DemoMenu';
import { usePermissionStore } from '@/store/modules/permission';
import { createPinia, setActivePinia } from 'pinia';

describe('F006 administrator demo navigation', () => {
  it.each([
    ['F001', 'factory_super_admin'], ['F006', 'viewer'],
    ['F006', 'finance_manager'], ['F006', 'platform_admin'], ['', 'factory_super_admin'],
  ])('does not expose the demo catalog to %s / %s', (factory, role) => {
    expect(getF006DemoMenu(factory, role)).toEqual([]);
  });

  it('provides reachable routes for the complete demonstration without overriding permission checks', () => {
    setActivePinia(createPinia());
    const permissions = usePermissionStore();
    permissions.setRole('factory_super_admin', 'F006', 'FACTORY');
    const [group] = getF006DemoMenu('F006', 'factory_super_admin');
    expect(group.title).toBe('演示中心');
    const leaves = group.children!;
    expect(new Set(leaves.map(item => item.path)).size).toBe(leaves.length);
    expect(leaves.map(item => item.path)).toEqual(expect.arrayContaining([
      '/demo-flow', '/smart-bi/dashboard', '/smart-bi/analysis', '/production-analytics/cost',
      '/quality/label-qc', '/production/traceability', '/production/plans',
      '/procurement/orders', '/warehouse/materials', '/production/material-flow', '/sales/orders',
    ]));
    for (const item of leaves) {
      expect(router.resolve(item.path).matched.length, item.path).toBeGreaterThan(0);
      expect(router.resolve(item.path).name, item.path).not.toBe('NotFound');
      expect(router.resolve(item.path).meta.module, item.path).toBe(item.module);
      expect(permissions.canAccess(item.module), item.path).toBe(true);
    }
    const sidebar = readFileSync(resolve(__dirname, '../AppSidebar.vue'), 'utf8');
    expect(sidebar).toContain('getF006DemoMenu(effectiveFactoryId.value, roleCode.value)');
    expect(sidebar).toContain('return [...demoMenu, ...sourceMenu]');
    expect(sidebar).toContain('.map(item => filterMenuItem(item))');
  });
});
