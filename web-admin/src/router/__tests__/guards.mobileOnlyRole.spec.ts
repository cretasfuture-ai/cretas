/**
 * `/mobile-only` 必须收到**真实角色**，而不是 `unactivated`。
 *
 * 2026-08-28 在测试环境用 f006_operator1 跑全路由验收时发现：登录后落到
 * `/mobile-only?role=unactivated`，而这个账号登录接口明确返回 `role=operator`。
 *
 * 成因是**顺序**（guards.ts）：
 *
 *     authStore.clearAuth();                                     // 先清登录态
 *     next({ path: '/mobile-only', query: { role: authStore.currentRole } });
 *                                          ^^^^^^^^^^^^^^^^^^^^^ 清完才读
 *
 * 而 `auth.ts:73` 是 `if (!u) return 'unactivated'` —— 清完必然读到默认值。
 *
 * 后果不是显示不好看：`mobile-only.vue` 的「移动端支持的功能」整张清单是
 * `v-if="route.query.role === 'operator'"` 之类，role 恒为 unactivated ⇒
 * **一线工人被告知「请用 App」，而下面那张「App 能干什么」的清单恰好漏掉了
 * 他的本职工作**（截图实测：只剩「考勤打卡 / 消息通知」两条无条件项）。
 * 受影响的正是 CLAUDE.md 点名要额外当心的低技术素养用户。
 *
 * ⚠️ 本文件的 `clearAuth` 桩**必须真的把状态清掉**。既有 spec 里它是
 * `vi.fn()` 空壳，照抄的话 `currentRole` 在 clearAuth 之后仍是 'operator'，
 * 这条断言就会在**有 bug 的代码上照样绿** —— 一个恒真式。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createRouter, createMemoryHistory, type RouteRecordRaw } from 'vue-router';

const authState = {
  isAuthenticated: true,
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
  canAccess: vi.fn(() => true),
  setRole: vi.fn(),
  loadFromDb: vi.fn(async () => undefined),
};
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => permState }));

import { setupRouterGuards } from '../guards';

const Blank = { template: '<div />' };

function makeRouter() {
  const routes: RouteRecordRaw[] = [
    { path: '/login', name: 'Login', component: Blank, meta: { requiresAuth: false } },
    { path: '/403', name: 'Forbidden', component: Blank, meta: { requiresAuth: false } },
    { path: '/mobile-only', name: 'MobileOnly', component: Blank, meta: { requiresAuth: false } },
    { path: '/dashboard', name: 'Dash', component: Blank, meta: { requiresAuth: true } },
  ];
  const r = createRouter({ history: createMemoryHistory(), routes });
  setupRouterGuards(r);
  return r;
}

async function landQuery(role: string) {
  authState.currentRole = role;
  authState.user = { id: 1 };
  authState.isAuthenticated = true;
  const r = makeRouter();
  await r.push('/dashboard').catch((): void => undefined);
  await r.isReady();
  return { path: r.currentRoute.value.path, query: r.currentRoute.value.query };
}

beforeEach(() => {
  authState.clearAuth.mockClear();
  permState.setRole.mockClear();
  permState.loadFromDb.mockClear();
});

describe('mobile-only 重定向携带真实角色', () => {
  it('前置条件: 桩的 clearAuth 真的会把 currentRole 打回 unactivated', () => {
    authState.currentRole = 'operator';
    authState.user = { id: 1 };
    authState.clearAuth();
    expect(authState.currentRole).toBe('unactivated');
  });

  for (const role of ['operator', 'quality_inspector', 'warehouse_worker']) {
    it(`${role} 被挡下时, query.role 是 ${role} 而不是 unactivated`, async () => {
      const { path, query } = await landQuery(role);
      expect(path).toBe('/mobile-only');
      expect(query.role).toBe(role);
      expect(query.role).not.toBe('unactivated');
    });
  }

  it('阴性对照: 非 mobile-only 角色不会被送去 /mobile-only', async () => {
    const { path } = await landQuery('factory_super_admin');
    expect(path).not.toBe('/mobile-only');
  });
});
