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
import { financeManagerMenu, menuConfig } from '../menuConfig';
import type { MenuItem } from '../menuConfig';
import type { FactoryType, User } from '@/types/auth';
import { resolveModuleRegistryItem } from '@/config/moduleRegistry';

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
const permissionApi = vi.hoisted(() => ({ getPlatformPermissions: vi.fn(), getFactoryOverride: vi.fn(), getUserModuleAccess: vi.fn() }));
vi.mock('@/api/request', () => ({ ...api, ApiError: class extends Error {} }));
vi.mock('@/api/permissionApi', () => permissionApi);
let pinia: ReturnType<typeof createPinia>;
function identity(factoryType: FactoryType = 'FACTORY', level: 'rw' | 'r' | '-' = 'rw') {
  const user: User = { id: 86, username: 'FIN-local-test', email: '', isActive: true, createdAt: '2026-10-02', updatedAt: '2026-10-02',
    userType: 'factory', factoryUser: { role: 'finance_manager', factoryId: 'FIN-UNIT', factoryType, permissions: [] } };
  useAuthStore().setUser(user);
  const p = usePermissionStore(); p.setRole('finance_manager', 'FIN-UNIT', factoryType, 86, { skipDbLoad: true });
  p.dbPermissions = { ...p.currentPermissions, finance: level }; p.isDbLoaded = true;
  permissionApi.getPlatformPermissions.mockResolvedValue(Object.entries(p.currentPermissions).map(([moduleCode, permissionLevel]) => ({ roleCode: 'finance_manager', moduleCode, permissionLevel })));
  return p;
}
function router() {
  const formal = officialRouter.getRoutes().find(r => r.name === 'FinanceAdjustments');
  if (!formal) throw Error('Official FinanceAdjustments route missing');
  const r = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/dashboard', component: { template: '<div>Home</div>' }, meta: { module: 'dashboard' } },
    { path: '/finance/adjustments', component: { template: '<div>Adjustment destination</div>' }, meta: { ...formal.meta } },
    { path: '/403', component: { template: '<div>Forbidden</div>' }, meta: { requiresAuth: false } },
  ] }); setupRouterGuards(r); return r;
}
async function sidebar() {
  const r = router(); await r.push('/dashboard');
  const w = mount(AppSidebar, { global: { plugins: [pinia, r, ElementPlus] } }); await flushPromises(); return { w, r };
}
function flatten(items: MenuItem[]): MenuItem[] { return items.flatMap(item => [item, ...flatten(item.children || [])]); }
beforeEach(() => {
  pinia = createPinia(); setActivePinia(pinia); vi.clearAllMocks();
  permissionApi.getPlatformPermissions.mockResolvedValue([]); permissionApi.getFactoryOverride.mockResolvedValue({}); permissionApi.getUserModuleAccess.mockResolvedValue([]);
  api.get.mockResolvedValue({ success: true, message: '', data: [] });
});
describe('FIN fresh dedicated menu through current sidebar and route contracts', () => {
  it('normal factory finance menu renders adjustment approval and navigates the official route', async () => {
    identity(); const { w, r } = await sidebar(); const target = w.findAll('.el-menu-item').find(n => n.text() === '调整审批');
    expect(target).toBeDefined(); await target!.trigger('click'); await flushPromises();
    expect(r.currentRoute.value.path).toBe('/finance/adjustments'); expect(r.currentRoute.value.meta.module).toBe('finance'); expect(api.post).not.toHaveBeenCalled();
  });
  it('retains factory OA and all four existing finance entries', async () => {
    identity(); const { w } = await sidebar(); for (const title of ['个人 OA', '应收应付', '销售开票', '采购收票', '收款管理']) expect(w.text()).toContain(title);
    expect(w.text()).not.toContain('供应商月对账'); expect(w.text()).not.toContain('智能数据分析');
  });
  it('read-only finance can navigate without gaining write permission', async () => {
    const p = identity('FACTORY', 'r'); const before = JSON.stringify(p.currentPermissions); const { w } = await sidebar();
    expect(w.text()).toContain('调整审批'); expect(p.canWrite('finance')).toBe(false); expect(JSON.stringify(p.currentPermissions)).toBe(before); expect(api.post).not.toHaveBeenCalled();
  });
  it('module deny hides the menu and unchanged guards reject direct route', async () => {
    identity('FACTORY', '-'); const { w, r } = await sidebar(); expect(w.text()).not.toContain('调整审批');
    await r.push('/finance/adjustments'); expect(r.currentRoute.value.path).toBe('/403'); expect(api.post).not.toHaveBeenCalled();
  });
  it('current L4 deny hides finance entry without weakening its module gate', async () => {
    const p = identity(); const module = resolveModuleRegistryItem('finance'); expect(module).toBeDefined();
    const denied = [{ moduleCode: module!.moduleCode, displayName: 'Finance', category: 'Finance', permissionModule: 'finance', roleDefaultAllowed: true, override: 'DENY' as const, effectiveAllowed: false }];
    p.applyUserModuleAccess(denied); permissionApi.getUserModuleAccess.mockResolvedValue(denied);
    const { w, r } = await sidebar(); expect(w.text()).not.toContain('调整审批'); await r.push('/finance/adjustments'); expect(r.currentRoute.value.path).toBe('/403');
  });
  it('restaurant dedicated finance remains unchanged and excludes factory adjustment entry', async () => {
    identity('RESTAURANT'); const { w } = await sidebar(); expect(w.text()).not.toContain('调整审批');
    expect(w.text()).toContain('供应商月对账'); expect(w.text()).toContain('成本归因');
    expect(flatten(financeManagerMenu).find(m => m.path === '/finance/adjustments')?.hideForFactoryTypes).toEqual(['RESTAURANT']);
  });
  it('does not transplant the old dashboard gate over current production entry', () => {
    expect(flatten(menuConfig).find(m => m.path === '/dashboard/production-progress')?.module).toBe('production');
  });
});
