/**
 * 网页后台准入：按**权限**判，不按岗位名（2026-11-12 Steve 口径 B14）。
 *
 * 之前 guards.ts 写死 `MOBILE_ONLY_ROLES = operator / quality_inspector / warehouse_worker`，
 * 登录后命中就 clearAuth() 并跳 `/mobile-only`。验收实测：质检员按权限本应能用网页
 * 「质量管理」的处置/超时处置，却被挡在外面。现在的判据是权威权限快照里有没有任一
 * 网页业务模块（dashboard 除外 —— 每个角色都有，算进去闸就恒真）。
 *
 * 本文件钉住：
 *   1. 阳性：一线岗位（含原来三个被写死的岗位）只要有业务模块权限就能进网页；
 *   2. 阴性：任何岗位（含管理员岗位名）只要快照里只剩 dashboard / 全 '-'，就送去 /mobile-only
 *      —— 证明判据是权限而不是岗位名；L4 DENY 覆盖也算；
 *   3. 快照没读到（读失败 / 演示跳过）不在这里下结论，不踢人；
 *   4. 被送去 /mobile-only 时 query.role 是真实角色而不是 unactivated（2026-08-28 回归）。
 *
 * ⚠️ `clearAuth` 桩**必须真的把状态清掉**，否则第 4 条在有 bug 的代码上照样绿（恒真式）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createRouter, createMemoryHistory, type RouteRecordRaw } from 'vue-router';

type Level = 'rw' | 'r' | 'w' | '-';

const authState = {
  isAuthenticated: true,
  isPlatform: false,
  currentRole: 'operator',
  factoryId: 'F006',
  factoryType: 'FACTORY' as string | undefined,
  user: { id: 1 } as { id: number } | undefined,
  // Faithful to the real store: auth.ts computes currentRole from `user`, and
  // returns 'unactivated' when there is none.
  clearAuth: vi.fn(() => {
    authState.user = undefined;
    authState.isAuthenticated = false;
    authState.currentRole = 'unactivated';
  }),
};
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

const permState = {
  isDbLoaded: true,
  currentPermissions: {} as Record<string, Level>,
  userModuleAccess: {} as Record<string, { override: 'GRANT' | 'DENY' | null }>,
  // 与真实 store 同形：先看 L4 覆盖，再看快照里是不是 '-'
  canAccess: vi.fn((m: string) => {
    const o = permState.userModuleAccess[m]?.override;
    if (o === 'DENY') return false;
    if (o === 'GRANT') return true;
    return (permState.currentPermissions[m] ?? '-') !== '-';
  }),
  setRole: vi.fn(),
  loadFromDb: vi.fn(async () => undefined),
};
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => permState }));

import { setupRouterGuards, hasAnyWebBusinessModule } from '../guards';

const Blank = { template: '<div />' };

const ALL_DASH: Record<string, Level> = {
  dashboard: 'r', production: '-', warehouse: '-', quality: '-', procurement: '-',
  sales: '-', hr: '-', equipment: '-', finance: '-', system: '-', analytics: '-',
  scheduling: '-', restaurant: '-', rd: '-',
};

function makeRouter() {
  const routes: RouteRecordRaw[] = [
    { path: '/login', name: 'Login', component: Blank, meta: { requiresAuth: false } },
    { path: '/403', name: 'Forbidden', component: Blank, meta: { requiresAuth: false } },
    { path: '/mobile-only', name: 'MobileOnly', component: Blank, meta: { requiresAuth: false } },
    { path: '/dashboard', name: 'Dash', component: Blank, meta: { requiresAuth: true } },
    { path: '/quality/inspections', name: 'Qi', component: Blank, meta: { requiresAuth: true, module: 'quality' } },
    { path: '/finance/costs', name: 'Fin', component: Blank, meta: { requiresAuth: true, module: 'finance' } },
  ];
  const r = createRouter({ history: createMemoryHistory(), routes });
  setupRouterGuards(r);
  return r;
}

async function land(role: string, perms: Record<string, Level>, to = '/dashboard') {
  authState.currentRole = role;
  authState.user = { id: 1 };
  authState.isAuthenticated = true;
  permState.currentPermissions = perms;
  const r = makeRouter();
  await r.push(to).catch((): void => undefined);
  await r.isReady();
  return { path: r.currentRoute.value.path, query: r.currentRoute.value.query };
}

beforeEach(() => {
  authState.clearAuth.mockClear();
  authState.isPlatform = false;
  permState.isDbLoaded = true;
  permState.userModuleAccess = {};
  permState.setRole.mockClear();
  permState.loadFromDb.mockClear();
});

describe('网页后台准入按权限判, 不按岗位名', () => {
  it('前置条件: 桩的 clearAuth 真的会把 currentRole 打回 unactivated', () => {
    authState.currentRole = 'operator';
    authState.user = { id: 1 };
    authState.clearAuth();
    expect(authState.currentRole).toBe('unactivated');
  });

  // 阳性: 原来被写死挡住的三个岗位, 按 L1 默认权限 (V20260422_01) 都有业务模块 ⇒ 能进
  const L1_DEFAULTS: Array<[string, Record<string, Level>]> = [
    ['quality_inspector', { ...ALL_DASH, production: 'r', quality: 'w' }],
    ['operator', { ...ALL_DASH, production: 'w' }],
    ['warehouse_worker', { ...ALL_DASH, warehouse: 'w' }],
  ];
  for (const [role, perms] of L1_DEFAULTS) {
    it(`阳性: ${role} 有业务模块权限 ⇒ 进得了网页, 不被清登录态`, async () => {
      const { path } = await land(role, perms);
      expect(path).toBe('/dashboard');
      expect(authState.clearAuth).not.toHaveBeenCalled();
    });
  }

  it('阳性: 质检员能进网页「质量管理」页 (验收实测被挡的那条)', async () => {
    const { path } = await land('quality_inspector', { ...ALL_DASH, quality: 'w' }, '/quality/inspections');
    expect(path).toBe('/quality/inspections');
  });

  it('不放宽: 进了网页, 逐页模块闸照旧 —— 质检员去财务页仍 403', async () => {
    const { path } = await land('quality_inspector', { ...ALL_DASH, quality: 'w' }, '/finance/costs');
    expect(path).toBe('/403');
  });

  // 阴性: 只看权限 —— 管理岗位名也挡, 一线岗位名被工厂配成全 '-' 也挡
  for (const role of ['quality_inspector', 'operator', 'factory_super_admin']) {
    it(`阴性: ${role} 的快照只剩 dashboard ⇒ 送去 /mobile-only, query.role 是真实角色`, async () => {
      const { path, query } = await land(role, { ...ALL_DASH });
      expect(path).toBe('/mobile-only');
      expect(query.role).toBe(role);
      expect(query.role).not.toBe('unactivated');
      expect(authState.clearAuth).toHaveBeenCalledTimes(1);
    });
  }

  it('阴性: L4 用户级 DENY 把唯一的业务模块关掉 ⇒ 也送去 /mobile-only', async () => {
    permState.userModuleAccess = { quality: { override: 'DENY' } };
    const { path } = await land('quality_inspector', { ...ALL_DASH, quality: 'w' });
    expect(path).toBe('/mobile-only');
  });

  it('阳性: L4 用户级 GRANT 开出一个模块 ⇒ 能进', async () => {
    permState.userModuleAccess = { quality: { override: 'GRANT' } };
    const { path } = await land('operator', { ...ALL_DASH });
    expect(path).toBe('/dashboard');
  });

  it('权威快照没读到 (读失败/演示跳过) ⇒ 不在准入这里踢人', async () => {
    permState.isDbLoaded = false;
    const { path } = await land('operator', {});
    expect(path).toBe('/dashboard');
    expect(authState.clearAuth).not.toHaveBeenCalled();
  });
});

describe('hasAnyWebBusinessModule (纯判定)', () => {
  const view = (perms: Record<string, Level>) => ({
    currentPermissions: perms,
    userModuleAccess: {},
    canAccess: (m: string) => (perms[m] ?? '-') !== '-',
  });
  it('dashboard / * 不算业务模块', () => {
    expect(hasAnyWebBusinessModule(view({ dashboard: 'rw', '*': 'rw' }))).toBe(false);
  });
  it("只写权限 'w' 也算可用 (与 canAccess 一致)", () => {
    expect(hasAnyWebBusinessModule(view({ dashboard: 'r', production: 'w' }))).toBe(true);
  });
});
