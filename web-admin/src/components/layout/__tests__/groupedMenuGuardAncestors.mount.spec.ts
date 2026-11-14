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
let extraFeatures: Record<string, unknown>[] = [];

function identity(role: string, onlyModules?: string[], factoryType = 'FACTORY') {
  const user: User = {
    id: 87, username: 'local-test', email: '', isActive: true, createdAt: '2026-11-13', updatedAt: '2026-11-13',
    userType: 'factory', factoryUser: { role, factoryId: 'P2-UNIT', factoryType, permissions: [] },
  } as User;
  useAuthStore().setUser(user);
  const p = usePermissionStore();
  p.setRole(role, 'P2-UNIT', factoryType, 87, { skipDbLoad: true });
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
  extraFeatures = [];
  permissionApi.getPlatformPermissions.mockResolvedValue([]);
  permissionApi.getFactoryOverride.mockResolvedValue({});
  permissionApi.getUserModuleAccess.mockResolvedValue([]);
  api.get.mockImplementation(async (url: string) => {
    if (String(url).endsWith('/feature-config')) {
      return { success: true, message: '', data: [{ moduleId: 'p2_test', enabled: true, config: { disabledMenuPaths } }, ...extraFeatures] };
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

/**
 * P2b（迁移方案 M3）：「数据与分析」组对工厂默认显示，按厂可关（真挂载侧栏）。
 * 关的两档：只写 showInSidebar=false ⇒ 原 /smart-bi 组的叶子消失；
 * 平台「工厂菜单」页关 analytics（后端同时写 menuVisible=false + showInSidebar=false）⇒ 整组消失。
 */
const SMART_BI_LEAVES = ['经营驾驶舱', 'AI 问答 / 数据分析', '经营分析'];

describe('P2b「数据与分析」对工厂默认显示、按厂可关（真挂载）', () => {
  it('阳性：FACTORY 工厂超管，没有任何 analytics 配置时整组可见，含原 /smart-bi 的页', async () => {
    identity('factory_super_admin');
    const titles = await sidebarTitles();
    expect(titles).toContain('数据与分析');
    for (const t of SMART_BI_LEAVES) expect(titles, t).toContain(t);
    // 叶子自身的工厂隐藏不受影响
    expect(titles).not.toContain('收入管理报表');
  });

  it('阴性：该厂 showInSidebar=false ⇒ 原 /smart-bi 的页全部消失，别的组照常', async () => {
    identity('factory_super_admin');
    extraFeatures = [{ moduleId: 'analytics', enabled: true, config: { showInSidebar: false } }];
    const titles = await sidebarTitles();
    for (const t of SMART_BI_LEAVES) expect(titles, t).not.toContain(t);
    expect(titles).toContain('生产管理');
  });

  it('阴性：平台关掉该厂 analytics（menuVisible=false + showInSidebar=false）⇒「数据与分析」整组消失', async () => {
    identity('factory_super_admin');
    extraFeatures = [{ moduleId: 'analytics', enabled: true, config: { showInSidebar: false, menuVisible: false, disabledMenuPaths: [] } }];
    const titles = await sidebarTitles();
    expect(titles).not.toContain('数据与分析');
    for (const t of [...SMART_BI_LEAVES, 'AI经营报告']) expect(titles, t).not.toContain(t);
    expect(titles).toContain('生产管理');
    expect(titles).toContain('主数据');
  });

  it('旧别名 smartbi 行写 showInSidebar=false 也能关', async () => {
    identity('factory_super_admin');
    extraFeatures = [{ moduleId: 'smartbi', enabled: true, config: { showInSidebar: false } }];
    const titles = await sidebarTitles();
    for (const t of SMART_BI_LEAVES) expect(titles, t).not.toContain(t);
  });

  it('模块权限照旧：没有 analytics 模块的账号仍看不到这一组', async () => {
    identity('sales_manager', ['dashboard', 'sales']);
    const titles = await sidebarTitles();
    expect(titles).not.toContain('数据与分析');
    for (const t of SMART_BI_LEAVES) expect(titles, t).not.toContain(t);
  });

  for (const type of ['RESTAURANT', 'LOGISTICS']) {
    it(`${type}：这个开关只管工厂，showInSidebar=false 不改变它的侧栏`, async () => {
      identity('factory_super_admin', undefined, type);
      const plain = await sidebarTitles();
      extraFeatures = [{ moduleId: 'analytics', enabled: true, config: { showInSidebar: false } }];
      const withOff = await sidebarTitles();
      expect(plain.length).toBeGreaterThan(0);
      expect(withOff).toEqual(plain);
    });
  }
});

describe('P3 顶层入口「今天」「待办」（真挂载）', () => {
  it('工厂超管、一线单部门角色都看得到，且排在全部功能分组之前', async () => {
    for (const [role, mods] of [['factory_super_admin', undefined], ['sales_manager', ['dashboard', 'sales']], ['warehouse_worker', ['dashboard', 'warehouse']]] as const) {
      pinia = createPinia();
      setActivePinia(pinia);
      identity(role, mods as string[] | undefined);
      const titles = await sidebarTitles();
      expect(titles, role).toContain('今天');
      expect(titles, role).toContain('待办');
      expect(titles.indexOf('今天'), role).toBeLessThan(titles.indexOf('待办'));
      expect(titles.indexOf('待办'), role).toBeLessThan(titles.findIndex((t) => t === '概览'));
    }
  });

  it('阴性对照：餐饮 / 物流仍用原分组，没有这两个入口', async () => {
    const { factoryMenuForType } = await import('../menuConfig');
    const flat = (items: { title: string; children?: unknown[] }[]): string[] => items.flatMap((i) => [i.title, ...flat((i.children ?? []) as never)]);
    expect(flat(factoryMenuForType('RESTAURANT') as never)).not.toContain('今天');
    expect(flat(factoryMenuForType('LOGISTICS') as never)).not.toContain('待办');
    expect(flat(factoryMenuForType('FACTORY') as never)).toEqual(expect.arrayContaining(['今天', '待办']));
  });
});
