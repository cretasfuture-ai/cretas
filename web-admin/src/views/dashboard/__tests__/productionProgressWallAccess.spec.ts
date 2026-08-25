/**
 * B8 生产进度大屏 —— 「菜单看得见」和「后端放行」必须是同一个判据。
 *
 * 🔴 本仓已经吃过两次同一形状的亏, 两次都写在 menuConfig 的注释里:
 *   · /dashboard/ai-value  菜单挂 module:'dashboard'(人人都有), 页面要 AI 数据权限
 *     ⇒ 销售主管看得见、点进去 403。
 *   · /rd/samples          菜单挂 'production'(几乎所有生产角色都有), 页面调 /rd/*
 *     ⇒ 差集那 9 个角色必 403。
 *   ▎菜单闸判一个模块, 后端闸判另一个模块, 中间那个差就是「看得见点不开」。
 *
 * 这块墙的两侧现在是对齐的, 而**对齐是手工维护的两份**, 它一定会漂:
 *
 *   menuConfig  module: 'production'
 *        ↑ 必须相等 ↓
 *   ProductionProgressDashboardController  @RequireModule("production_plan")
 *        → ProductionModuleRegistry  ("production_plan", …, "production")
 *        → UserModuleAccessServiceImpl.roleDefaultAllows: production:read/write/read_write
 *
 * ⇒ 这道闸钉的是**两份一致**, 不是「各自都对」。各自都对是两条独立断言,
 *   它们同时绿也不排除两份内容不同。
 *
 * ⚠️ 本闸读的是 Java 源码文本。它看不见运行时(工厂有没有停用 production_plan 模块、
 *    有没有给某个账号下 DENY override) —— 那两件事只能实测, 已在交接里单列。
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { setActivePinia, createPinia } from 'pinia';
import { menuConfig } from '@/components/layout/menuConfig';
import { usePermissionStore } from '@/store/modules/permission';

const WALL_PATH = '/dashboard/production-progress';

function repoRoot(): string {
  let dir = resolve(process.cwd());
  for (let i = 0; i < 6; i += 1) {
    try {
      readFileSync(join(dir, 'backend/java/cretas-api/pom.xml'), 'utf8');
      return dir;
    } catch { /* keep walking up */ }
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error('找不到 backend/java/cretas-api —— 本闸不静默跳过');
}

function readJava(rel: string): string {
  return readFileSync(join(repoRoot(), 'backend/java/cretas-api/src/main/java/com/cretas/aims', rel), 'utf8');
}

function findMenuItem(nodes: any[], path: string): any | null {
  for (const node of nodes ?? []) {
    if (node?.path === path) return node;
    const hit = node?.children ? findMenuItem(node.children, path) : null;
    if (hit) return hit;
  }
  return null;
}

describe('B8 大屏 —— 菜单模块必须等于后端 @RequireModule 映射出来的模块', () => {
  it('菜单项存在(阳性对照 —— 它不在的话下面每一条都无意义)', () => {
    expect(findMenuItem(menuConfig as any[], WALL_PATH)).not.toBeNull();
  });

  it('三份声明指向同一个权限模块: menuConfig / 后端注解 / 注册表映射', () => {
    const item = findMenuItem(menuConfig as any[], WALL_PATH)!;

    // ① 后端类级注解要的模块码
    const controller = readJava('controller/ProductionProgressDashboardController.java');
    const moduleCode = controller.match(/@RequireModule\("([^"]+)"\)/)?.[1];
    expect(moduleCode, '没在 Controller 上读到 @RequireModule —— 闸自己坏了, 不是被测对象变了')
      .toBeTruthy();

    // ② 注册表把模块码映射成的 permissionModule
    const registry = readJava('permission/ProductionModuleRegistry.java');
    const row = registry.match(
      new RegExp(`new ModuleDefinition\\("${moduleCode}"\\s*,[^)]*?"([a-z_]+)"\\s*\\)`),
    );
    expect(row, `注册表里找不到 ${moduleCode} 的定义`).toBeTruthy();
    const permissionModule = row![1];

    // ③ 菜单声明的模块 —— 必须与 ② 相等
    expect(
      item.module,
      `菜单挂 '${item.module}' 而后端 @RequireModule("${moduleCode}") 要的是 `
        + `'${permissionModule}' ⇒ 两侧判据不同, 差集里的角色「看得见点不开」。`,
    ).toBe(permissionModule);
  });
});

describe('B8 大屏 —— workshop_supervisor 的可达性(菜单侧)', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('车间主管在 FACTORY 租户下能访问 production 模块', () => {
    const store = usePermissionStore();
    store.setRole('workshop_supervisor', 'F006', 'FACTORY', 1, { skipDbLoad: true });
    const item = findMenuItem(menuConfig as any[], WALL_PATH)!;

    expect(store.canAccess(item.module)).toBe(true);
  });

  it('🔴 阴性对照: 同一个人访问 analytics 是被拒的 —— 所以「挂 analytics 就打不开」不是空话', () => {
    const store = usePermissionStore();
    store.setRole('workshop_supervisor', 'F006', 'FACTORY', 1, { skipDbLoad: true });

    expect(
      store.canAccess('analytics'),
      '车间主管居然有 analytics —— 那 menuConfig 里「⛔ 不能挂 analytics」那段理由就过期了, '
        + '要么改注释要么改结论, 不要两边都留着。',
    ).toBe(false);
  });

  it('这块墙没有叠 roles 白名单 —— 叠了就会把车间主管挡在门外', () => {
    const item = findMenuItem(menuConfig as any[], WALL_PATH)!;
    if (item.roles?.length) {
      expect(
        item.roles,
        'roles 白名单一旦出现, workshop_supervisor 必须在里面 —— 他是这块墙的受众。',
      ).toContain('workshop_supervisor');
    } else {
      expect(item.roles).toBeUndefined();
    }
  });

  it('父组 /production 对 FACTORY 不隐藏 —— 父组被摘掉会整棵子树一起消失', () => {
    const group = (menuConfig as any[]).find((n) => n.path === '/production');
    expect(group, '找不到 /production 组').toBeTruthy();
    expect(group.hideForFactoryTypes ?? []).not.toContain('FACTORY');
    // 本项确实挂在那个组下面 —— 否则上面这条守的是别人的树。
    expect(findMenuItem(group.children ?? [], WALL_PATH)).not.toBeNull();
  });
});
