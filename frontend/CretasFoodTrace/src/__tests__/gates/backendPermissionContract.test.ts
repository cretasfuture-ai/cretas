import fs from 'fs';
import path from 'path';
import { hasPermission, type FactoryUser } from '../../types/auth';
import { HOME_MODULE_SETS, resolveHomeModules } from '../../screens/main/homeModules';

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
    // 🔴 2026-09-06（BUG-HOME-MODULES-EMPTY-FOR-SALES-20260906）：这一条原来断言
    // `HomeScreen.tsx` 里含字面量 `hasPermission(user, permission)`。首页模块表被抽到
    // `screens/main/homeModules.ts`（改成按导航器取模块集）之后它红了 —— 而**行为没坏**，
    // 坏的是断言：它守的是「那行代码在哪个文件里」，不是「首页卡片按后端权限命名空间过滤」。
    //
    // 形态 C‴：把断言从**字面**抬到**性质**，⛔ 不是删掉它。
    // 下面第 1 段仍然钉「用的是权威 hasPermission / 没有自造伪权限」（文本层），
    // 第 2 段直接**跑一遍筛选**（行为层）—— 后者才是真正守住这件事的那一条。
    const home = read('screens/main/HomeScreen.tsx');
    const homeModules = read('screens/main/homeModules.ts');
    const mainNavigator = read('navigation/MainNavigator.tsx');

    // 1) 权威实现：筛选调的是 types/auth 的 hasPermission，不是本地复制品
    expect(homeModules).toContain("from '../../types/auth'");
    expect(homeModules).toContain('hasPermission(user, permission)');
    expect(homeModules).not.toMatch(/function hasPermission\s*\(/);
    // 阴性对照：两个文件里都不许出现自造的伪权限名
    for (const src of [home, homeModules]) {
      expect(src).not.toMatch(/processing_access|logistics_access|trace_access|admin_access/);
    }

    // 2) 每个模块要的权限都得长成后端发得出来的 `<namespace>:<action>`
    const namespaces = new Set<string>();
    for (const specs of Object.values(HOME_MODULE_SETS)) {
      for (const spec of specs) {
        expect(spec.requiredPermissions.length).toBeGreaterThan(0);
        for (const p of spec.requiredPermissions) {
          expect(p).toMatch(/^[a-z]+:[a-z]+$/);
          namespaces.add(p.split(':')[0]!);
        }
      }
    }
    // 阳性对照：真的遍历到了东西（空集合下上面每一条都是恒真式）
    expect(namespaces.size).toBeGreaterThanOrEqual(5);

    // 3) 行为层：`sales:*` 的用户在销售首页有卡，`warehouse:*` 的用户一张都没有。
    //    这一条不依赖任何字面量，把实现挪到哪个文件都照样成立。
    const salesUser = userWith(['sales:*']);
    const warehouseUser = userWith(['warehouse:*']);
    const t = (k: string) => k;
    const yes = resolveHomeModules('sales', salesUser, () => true, t);
    const no = resolveHomeModules('sales', warehouseUser, () => true, t);
    expect(yes.length).toBeGreaterThan(0);
    expect(no).toHaveLength(0);

    expect(mainNavigator).toContain("hasPermission('production:read')");
    expect(mainNavigator).toContain("hasPermission('warehouse:read')");
    expect(mainNavigator).not.toMatch(/processing_access|logistics_access/);
  });
});
