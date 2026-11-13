import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { createRouter, createMemoryHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import AppSidebar from '../AppSidebar.vue';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import type { User } from '@/types/auth';

/**
 * P2 #1692 审查第 2 条：真挂载侧栏，守 canSeeMenuNode 里「搬出来的叶子要过原父组」这一步。
 * SKU 管理从「生产管理」搬到「设置 › 主数据」，带 guardAncestors=[/production]。
 * 工厂 feature-config 的 disabledMenuPaths 写父路径 /production 时，搬家前 SKU 跟着生产组一起消失；
 * 搬家后也必须消失。只关 /system/products 则只少 SKU（阴性对照：父组判据没有误伤别的项）。
 */
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
const permissionApi = vi.hoisted(() => ({
  getCurrentModulePermissions: vi.fn(), getPlatformPermissions: vi.fn(),
  getFactoryOverride: vi.fn(), getUserModuleAccess: vi.fn(),
}));
vi.mock('@/api/request', () => ({ ...api, ApiError: class extends Error {} }));
vi.mock('@/api/permissionApi', () => permissionApi);

let pinia: ReturnType<typeof createPinia>;
let disabledMenuPaths: string[] = [];

function identity(role: string, onlyModules?: string[]) {
  const user: User = {
    id: 87, username: 'local-test', email: '', isActive: true, createdAt: '2026-11-13', updatedAt: '2026-11-13',
    userType: 'factory', factoryUser: { role, factoryId: 'P2-UNIT', factoryType: 'FACTORY', permissions: [] },
  } as User;
  useAuthStore().setUser(user);
  const p = usePermissionStore();
  p.setRole(role, 'P2-UNIT', 'FACTORY', 87, { skipDbLoad: true });
  p.dbPermissions = onlyModules
    // 模拟库里只开了这几个模块的账号（与 prod 上 QA 销售主管实测一致：只看得到销售相关）
    ? Object.fromEntries(Object.keys(p.currentPermissions).map((m) => [m, onlyModules.includes(m) ? 'rw' : '-'])) as typeof p.currentPermissions
    : { ...p.currentPermissions };
  p.isDbLoaded = true;
  permissionApi.getCurrentModulePermissions.mockResolvedValue({ ...p.currentPermissions });
}

async function mountSidebar() {
  const r = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/:p(.*)*', component: { template: '<div />' } },
  ] });
  await r.push('/dashboard');
  const w = mount(AppSidebar, { global: { plugins: [pinia, r, ElementPlus] } });
  await flushPromises();
  return w;
}

async function sidebarTitles(): Promise<string[]> {
  const r = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/:p(.*)*', component: { template: '<div />' } },
  ] });
  await r.push('/dashboard');
  const w = mount(AppSidebar, { global: { plugins: [pinia, r, ElementPlus] } });
  await flushPromises();
  return w.findAll('.menu-title-copy').map((n) => n.text());
}

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  vi.clearAllMocks();
  disabledMenuPaths = [];
  permissionApi.getPlatformPermissions.mockResolvedValue([]);
  permissionApi.getFactoryOverride.mockResolvedValue({});
  permissionApi.getUserModuleAccess.mockResolvedValue([]);
  api.get.mockImplementation(async (url: string) => {
    if (String(url).endsWith('/feature-config')) {
      return { success: true, message: '', data: [{ moduleId: 'p2_test', enabled: true, config: { disabledMenuPaths } }] };
    }
    return { success: true, message: '', data: [] };
  });
});

describe('原型分组侧栏：搬出来的叶子仍受原父组判据约束（真挂载）', () => {
  it('阳性对照：不关任何路径时「主数据」里有 SKU 管理，「生产管理」组在', async () => {
    identity('factory_super_admin');
    const titles = await sidebarTitles();
    expect(titles).toContain('主数据');
    expect(titles).toContain('SKU 管理');
    expect(titles).toContain('生产管理');
    expect(titles).toContain('生产计划');
  });

  it('关原父路径 /production：生产组消失，已搬到主数据的 SKU 管理也消失', async () => {
    identity('factory_super_admin');
    disabledMenuPaths = ['/production'];
    const titles = await sidebarTitles();
    expect(titles).not.toContain('生产管理');
    expect(titles).not.toContain('SKU 管理');
    // 主数据里别的项不受影响
    expect(titles).toContain('主数据');
  });

  it('阴性对照：只关 /system/products：只少 SKU 管理，生产组照常', async () => {
    identity('factory_super_admin');
    disabledMenuPaths = ['/system/products'];
    const titles = await sidebarTitles();
    expect(titles).not.toContain('SKU 管理');
    expect(titles).toContain('生产管理');
    expect(titles).toContain('生产计划');
  });
});

describe('「全部铺开」按可见叶子数（审查 #1692 第 3 条，08-21 owner 口径）', () => {
  it('销售主管（单部门，叶子少）：所有分组含嵌套的「概览 › 工作台」都展开', async () => {
    identity('sales_manager', ['dashboard', 'sales']);
    const w = await mountSidebar();
    const subMenus = w.findAll('.el-sub-menu');
    expect(subMenus.length).toBeGreaterThan(2);
    const closed = subMenus.filter((n) => !n.classes().includes('is-opened')).map((n) => n.attributes('aria-label'));
    expect(closed).toEqual([]);
    expect(subMenus.map((n) => n.attributes('aria-label'))).toContain('工作台');
  });

  it('阴性对照：工厂超管（跨部门，叶子多）不铺开', async () => {
    identity('factory_super_admin');
    const w = await mountSidebar();
    const all = w.findAll('.el-sub-menu').length;
    const opened = w.findAll('.el-sub-menu.is-opened').length;
    expect(all).toBeGreaterThan(5);
    // 只展开当前所在组（/dashboard 在「概览」里），其余收起
    expect(opened).toBeLessThanOrEqual(1);
  });
});
