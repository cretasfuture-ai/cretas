import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import AppSidebar from '../AppSidebar.vue';
import officialRouter from '@/router';
import { setupRouterGuards } from '@/router/guards';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import type { User } from '@/types/auth';

/**
 * D-S302-03: 财务经理没有 quality 模块, /quality/disposals 进不去。报废审批在财务下单开入口,
 * 真跑路由守卫 + 真侧边栏: 财务经理走得到, 出纳和质检走不到, /quality/disposals 仍然挡财务。
 */
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
const permissionApi = vi.hoisted(() => ({
  getCurrentModulePermissions: vi.fn(), getPlatformPermissions: vi.fn(),
  getFactoryOverride: vi.fn(), getUserModuleAccess: vi.fn(),
}));
vi.mock('@/api/request', () => ({ ...api, ApiError: class extends Error {} }));
vi.mock('@/api/permissionApi', () => permissionApi);

let pinia: ReturnType<typeof createPinia>;
// 后端 GET /disposal-records/approval-permission 的答案 (与 approve/reject 守卫同一份判断)。
// 不同测试按「后端说能 / 不能」设置, 证明入口跟权限走而不是跟岗位名走。
let backendCanApprove: boolean | 'error' = false;

function identity(role: string) {
  const user: User = {
    id: 86, username: 'local-test', email: '', isActive: true, createdAt: '2026-10-02', updatedAt: '2026-10-02',
    userType: 'factory', factoryUser: { role, factoryId: 'FIN-UNIT', factoryType: 'FACTORY', permissions: [] },
  } as User;
  useAuthStore().setUser(user);
  const p = usePermissionStore();
  p.setRole(role, 'FIN-UNIT', 'FACTORY', 86, { skipDbLoad: true });
  p.dbPermissions = { ...p.currentPermissions };
  p.isDbLoaded = true;
  permissionApi.getCurrentModulePermissions.mockResolvedValue({ ...p.currentPermissions });
  permissionApi.getPlatformPermissions.mockResolvedValue(Object.entries(p.currentPermissions)
    .map(([moduleCode, permissionLevel]) => ({ roleCode: role, moduleCode, permissionLevel })));
  return p;
}

function router() {
  const meta = (name: string) => {
    const formal = officialRouter.getRoutes().find((r) => r.name === name);
    if (!formal) throw Error(`Official route ${name} missing`);
    return { ...formal.meta };
  };
  const r = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/dashboard', component: { template: '<div>Home</div>' }, meta: { module: 'dashboard' } },
    { path: '/finance/disposal-approvals', component: { template: '<div>approvals</div>' }, meta: meta('FinanceDisposalApprovals') },
    { path: '/quality/disposals', component: { template: '<div>quality</div>' }, meta: meta('QualityDisposals') },
    { path: '/403', component: { template: '<div>Forbidden</div>' }, meta: { requiresAuth: false } },
  ] });
  setupRouterGuards(r);
  return r;
}

async function sidebar() {
  const r = router();
  await r.push('/dashboard');
  const w = mount(AppSidebar, { global: { plugins: [pinia, r, ElementPlus] } });
  await flushPromises();
  return { w, r };
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  vi.clearAllMocks();
  backendCanApprove = false;
  permissionApi.getPlatformPermissions.mockResolvedValue([]);
  permissionApi.getFactoryOverride.mockResolvedValue({});
  permissionApi.getUserModuleAccess.mockResolvedValue([]);
  api.get.mockImplementation(async (url: string) => {
    if (String(url).endsWith('/disposal-records/approval-permission')) {
      if (backendCanApprove === 'error') throw new Error('403');
      return { success: true, message: '', data: { canApprove: backendCanApprove } };
    }
    return { success: true, message: '', data: [] };
  });
});

describe('财务经理的报废审批入口', () => {
  it('阳性: 财务经理看得到菜单, 点进去到达审批页', async () => {
    backendCanApprove = true;
    identity('finance_manager');
    const { w, r } = await sidebar();
    const target = w.findAll('.el-menu-item').find((n) => n.text() === '报废审批');
    expect(target).toBeDefined();
    await target!.trigger('click');
    await flushPromises();
    expect(r.currentRoute.value.path).toBe('/finance/disposal-approvals');
  });

  it('阴性: 不给财务质检模块, /quality/disposals 仍然进不去', async () => {
    const p = identity('finance_manager');
    expect(p.canAccess('quality')).toBe(false);
    const r = router();
    await r.push('/quality/disposals');
    expect(r.currentRoute.value.path).toBe('/403');
  });

  it('阴性: 同持 finance 的出纳走不到审批页, 菜单也没有', async () => {
    backendCanApprove = false;
    const p = identity('cashier');
    expect(p.canWrite('finance')).toBe(true);
    const { w, r } = await sidebar();
    expect(w.text()).not.toContain('报废审批');
    await r.push('/finance/disposal-approvals');
    expect(r.currentRoute.value.path).toBe('/403');
  });

  it('阴性: 质量经理走不到财务入口, 也不会看到审批按钮的权限', async () => {
    const p = identity('quality_manager');
    expect(p.canWrite('finance')).toBe(false);
    const r = router();
    await r.push('/finance/disposal-approvals');
    expect(r.currentRoute.value.path).toBe('/403');
  });

  it('阳性(按权限不按岗位名): 工厂把审批配给出纳 ⇒ 后端说能 ⇒ 出纳看得到菜单也进得去', async () => {
    backendCanApprove = true;
    identity('cashier');
    const { w, r } = await sidebar();
    expect(w.text()).toContain('报废审批');
    await r.push('/finance/disposal-approvals');
    expect(r.currentRoute.value.path).toBe('/finance/disposal-approvals');
  });

  it('阴性(按权限不按岗位名): 后端说不能 ⇒ 连财务经理也没有菜单、直达 URL 进 /403', async () => {
    backendCanApprove = false;
    identity('finance_manager');
    const { w, r } = await sidebar();
    expect(w.text()).not.toContain('报废审批');
    await r.push('/finance/disposal-approvals');
    expect(r.currentRoute.value.path).toBe('/403');
  });

  it('阴性: 资格读不到 ⇒ 不显示入口也不放行 (fail-closed)', async () => {
    backendCanApprove = 'error';
    identity('finance_manager');
    const { w, r } = await sidebar();
    expect(w.text()).not.toContain('报废审批');
    await r.push('/finance/disposal-approvals');
    expect(r.currentRoute.value.path).toBe('/403');
  });
});
