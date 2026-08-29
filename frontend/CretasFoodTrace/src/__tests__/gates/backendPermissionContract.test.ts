import fs from 'fs';
import path from 'path';
import { hasPermission, type FactoryUser } from '../../types/auth';

const SRC = path.resolve(__dirname, '../..');
const read = (relativePath: string) => fs.readFileSync(path.join(SRC, relativePath), 'utf8');

function userWith(permissions: string[]): FactoryUser {
  return {
    id: 1,
    username: 'permission-probe',
    email: '',
    createdAt: '2026-08-29T00:00:00Z',
    updatedAt: '2026-08-29T00:00:00Z',
    isActive: true,
    userType: 'factory',
    factoryUser: {
      role: 'operator',
      factoryId: 'F006',
      factoryType: 'FACTORY',
      permissions,
    },
  };
}

describe('backend permission authority contract', () => {
  it('supports exact, module wildcard, and global wildcard grants', () => {
    expect(hasPermission(userWith(['sales:read']), 'sales:read')).toBe(true);
    expect(hasPermission(userWith(['sales:read']), 'sales:write')).toBe(false);
    expect(hasPermission(userWith(['sales:*']), 'sales:write')).toBe(true);
    expect(hasPermission(userWith(['sales:*']), 'warehouse:read')).toBe(false);
    expect(hasPermission(userWith(['*:*']), 'system:write')).toBe(true);
    expect(hasPermission(userWith([]), 'production:read')).toBe(false);
  });

  it('reads unified-login permissions as one required flat array', () => {
    const apiTypes = read('types/apiResponses.ts');
    const authService = read('services/auth/authService.ts');

    expect(apiTypes).toContain('permissions: string[];');
    expect(authService).toContain('if (!Array.isArray(data.permissions))');
    expect(authService).toContain('permissions: backendUser.permissions');
    expect(authService).not.toMatch(/permissions\?\.(features|modules)/);
    expect(authService).not.toContain('UserPermissions');
  });

  it('has one production hasPermission implementation and no role-derived grants', () => {
    const authTypes = read('types/auth.ts');
    const navigationHelper = read('utils/navigationHelper.ts');
    const roleMapping = read('utils/roleMapping.ts');

    expect(authTypes.match(/export function hasPermission\s*\(/g)).toHaveLength(1);
    expect(navigationHelper).not.toMatch(/export function hasPermission\s*\(/);
    expect(navigationHelper).toContain('return hasPermission(user, module);');
    expect(authTypes).not.toContain('getDefaultPermissionsForRole');
    expect(roleMapping).not.toContain('generatePermissionsFromRole');
  });

  it('uses backend permission namespaces for home cards and main tabs', () => {
    const home = read('screens/main/HomeScreen.tsx');
    const mainNavigator = read('navigation/MainNavigator.tsx');

    expect(home).toContain('hasPermission(user, permission)');
    expect(home).not.toMatch(/processing_access|logistics_access|trace_access|admin_access/);
    expect(mainNavigator).toContain("hasPermission('production:read')");
    expect(mainNavigator).toContain("hasPermission('warehouse:read')");
    expect(mainNavigator).not.toMatch(/processing_access|logistics_access/);
  });
});
