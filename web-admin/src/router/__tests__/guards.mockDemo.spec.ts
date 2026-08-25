/**
 * `meta.mockDemo` 不再绕过权限闸 (P0, 2026-08-25)。
 *
 * 修复前 guards.ts 在**所有**闸之前 early-return:
 *     if (to.meta.mockDemo) { next(); return; }
 * 于是 12 条 scheduling 路由的 ROLE_PATH_WHITELIST / module / modulesAll /
 * hideForFactoryTypes / restaurantRoles / roles 全成死代码 —— 任何已登录用户
 * 手输 URL 就进得去。而它是**静态 route meta**, 没有 env / query / localStorage
 * 开关, 生产构建里恒定生效。
 *
 * 修复后只保留一项豁免: **演示租户** (DEMO_* factoryId) 进 mockDemo 页时跳过
 * `module` 那一道闸 (原注释点名的那个理由), 其余闸对所有人照跑。
 *
 * 路由 meta 直接从**真实 router** 读, 不在这里手抄一份 —— 手抄的那份一定会漂。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createRouter, createMemoryHistory, type RouteRecordRaw } from 'vue-router';

const authState = {
  isAuthenticated: true,
  currentRole: 'viewer',
  factoryId: 'F_REAL',
  factoryType: 'FACTORY' as string | undefined,
  user: { id: 1 } as { id: number } | undefined,
  clearAuth: vi.fn(),
};
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

const permState = {
  canAccess: vi.fn((_m: string) => false),
  setRole: vi.fn(),
  loadFromDb: vi.fn(async () => undefined),
};
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => permState }));

import { setupRouterGuards } from '../guards';
import realRouter from '../index';

const Blank = { template: '<div />' };

/** 真实路由表里那条记录的 meta —— 测试不复制一份, 避免与生产表漂移。 */
function realMetaOf(path: string): Record<string, unknown> {
  const rec = realRouter.getRoutes().find((r) => r.path === path);
  if (!rec) throw new Error(`真实路由表里没有 ${path} —— 路由被改名/删了, 这条测试需要跟着改`);
  return rec.meta as Record<string, unknown>;
}

const LOGISTICS_PAGE = '/scheduling/logistics/stores';   // hideForFactoryTypes: FACTORY / RESTAURANT
const FACTORY_PAGE = '/scheduling/overview';             // hideForFactoryTypes: LOGISTICS

function makeRouter(paths: string[]) {
  const routes: RouteRecordRaw[] = [
    { path: '/403', name: 'Forbidden', component: Blank, meta: { requiresAuth: false } },
    { path: '/login', name: 'Login', component: Blank, meta: { requiresAuth: false } },
    { path: '/dashboard', name: 'Dash', component: Blank, meta: { requiresAuth: true } },
    ...paths.map((p) => ({
      path: p,
      name: `T${p}`,
      component: Blank,
      meta: realMetaOf(p),
    })),
  ];
  const r = createRouter({ history: createMemoryHistory(), routes });
  setupRouterGuards(r);
  return r;
}

async function land(path: string): Promise<string> {
  const r = makeRouter([LOGISTICS_PAGE, FACTORY_PAGE]);
  await r.push('/dashboard').catch(() => undefined);
  // /dashboard 本身也会过守卫并调 loadFromDb —— 不清掉的话, 下面那两条
  // 「调了没调 loadFromDb」的断言量到的是热身那一跳, 不是目标那一跳。
  permState.setRole.mockClear();
  permState.loadFromDb.mockClear();
  await r.push(path).catch(() => undefined);
  await r.isReady();
  return r.currentRoute.value.path;
}

beforeEach(() => {
  authState.isAuthenticated = true;
  authState.currentRole = 'viewer';
  authState.factoryId = 'F_REAL';
  authState.factoryType = 'FACTORY';
  authState.user = { id: 1 };
  permState.canAccess.mockReset();
  permState.canAccess.mockImplementation(() => false);
  permState.setRole.mockClear();
  permState.loadFromDb.mockClear();
});

describe('mockDemo 前置条件', () => {
  it('这两条路由在真实路由表里确实带 mockDemo (否则本文件测的是别的东西)', () => {
    expect(realMetaOf(LOGISTICS_PAGE).mockDemo).toBe(true);
    expect(realMetaOf(FACTORY_PAGE).mockDemo).toBe(true);
    expect(realMetaOf(LOGISTICS_PAGE).module).toBe('scheduling');
    expect(realMetaOf(LOGISTICS_PAGE).hideForFactoryTypes).toEqual(['FACTORY', 'RESTAURANT']);
  });

  it('真实路由表里带 mockDemo 的记录数没有悄悄变化', () => {
    const withFlag = realRouter.getRoutes().filter((r) => r.meta?.mockDemo === true);
    expect(withFlag.length).toBe(12);
  });
});

describe('mockDemo 不再绕过权限闸', () => {
  it('普通租户没有 scheduling 模块 → 403 (核心断言)', async () => {
    authState.factoryId = 'F_REAL';
    authState.factoryType = 'LOGISTICS';   // 让 hideForFactoryTypes 不背这个锅
    permState.canAccess.mockImplementation(() => false);
    expect(await land(LOGISTICS_PAGE)).toBe('/403');
  });

  it('阳性对照: 同一个租户有 scheduling 模块时进得去', async () => {
    authState.factoryId = 'F_REAL';
    authState.factoryType = 'LOGISTICS';
    permState.canAccess.mockImplementation((m: string) => m === 'scheduling');
    expect(await land(LOGISTICS_PAGE)).toBe(LOGISTICS_PAGE);
  });

  it('hideForFactoryTypes 重新生效: FACTORY 租户进物流页 → 403', async () => {
    authState.factoryType = 'FACTORY';
    permState.canAccess.mockImplementation(() => true);
    expect(await land(LOGISTICS_PAGE)).toBe('/403');
  });

  it('ROLE_PATH_WHITELIST 重新生效: finance_manager 有模块也进不去', async () => {
    authState.currentRole = 'finance_manager';
    authState.factoryType = 'LOGISTICS';
    permState.canAccess.mockImplementation(() => true);
    expect(await land(LOGISTICS_PAGE)).toBe('/403');
  });

  it('非演示租户进 mockDemo 页时会真的去加载 DB 权限', async () => {
    authState.factoryType = 'LOGISTICS';
    permState.canAccess.mockImplementation(() => true);
    await land(LOGISTICS_PAGE);
    expect(permState.loadFromDb).toHaveBeenCalled();
  });
});

describe('演示租户仍然进得去 (豁免范围只有 module 一道)', () => {
  it('DEMO_* 租户没有 scheduling 模块也能进演示页', async () => {
    authState.factoryId = 'DEMO_LOGISTICS';
    authState.factoryType = 'LOGISTICS';
    permState.canAccess.mockImplementation(() => false);
    expect(await land(LOGISTICS_PAGE)).toBe(LOGISTICS_PAGE);
  });

  it('演示租户也不豁免 hideForFactoryTypes: DEMO_REST 进物流页 → 403', async () => {
    authState.factoryId = 'DEMO_REST';
    authState.factoryType = 'RESTAURANT';
    permState.canAccess.mockImplementation(() => false);
    expect(await land(LOGISTICS_PAGE)).toBe('/403');
  });

  it('演示租户也不豁免 ROLE_PATH_WHITELIST', async () => {
    authState.factoryId = 'DEMO_LOGISTICS';
    authState.factoryType = 'LOGISTICS';
    authState.currentRole = 'finance_manager';
    permState.canAccess.mockImplementation(() => true);
    expect(await land(LOGISTICS_PAGE)).toBe('/403');
  });

  it('演示租户跳过 loadFromDb (原有行为保留)', async () => {
    authState.factoryId = 'DEMO_LOGISTICS';
    authState.factoryType = 'LOGISTICS';
    permState.canAccess.mockImplementation(() => false);
    await land(LOGISTICS_PAGE);
    expect(permState.loadFromDb).not.toHaveBeenCalled();
    expect(permState.setRole).toHaveBeenCalledWith(
      expect.anything(), 'DEMO_LOGISTICS', expect.anything(), expect.anything(),
      { skipDbLoad: true },
    );
  });

  it('豁免只对 mockDemo 页生效: DEMO 租户进非 mockDemo 的兄弟页仍按模块闸判', async () => {
    // /scheduling/plans/create 没有 mockDemo, 同样 module: 'scheduling'
    const path = '/scheduling/plans/create';
    expect(realMetaOf(path).mockDemo).toBeUndefined();
    authState.factoryId = 'DEMO_LOGISTICS';
    authState.factoryType = 'FACTORY';
    permState.canAccess.mockImplementation(() => false);

    const r = makeRouter([path]);
    await r.push('/dashboard').catch(() => undefined);
    await r.push(path).catch(() => undefined);
    expect(r.currentRoute.value.path).toBe('/403');
  });
});
