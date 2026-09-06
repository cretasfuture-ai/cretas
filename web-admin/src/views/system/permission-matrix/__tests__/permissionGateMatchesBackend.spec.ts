/**
 * 契约: 权限矩阵页的**三处门禁写的是同一个权限码**。
 *
 * - 菜单项 `/system/permission-matrix` 的 `module`
 * - 路由 `SystemPermissionMatrix` 的 `meta.module` (guards.ts 用它调 canAccess)
 * - 后端 `PermissionMatrixController` 的 `@RequirePermission`
 *
 * 为什么要有这条闸: 本仓反复出现「菜单看得见 / 点进去 403」——成因就是这两侧
 * 各写各的。⛔ 页面组件内部**不再**自己判一次 (那对全部可达主体恒为真, 是空转分支,
 * `branchReachability` 闸会把它算进预算), 所以这两处 + 后端就是全部门禁, 必须钉住。
 *
 * ⚠️ 判据读的是**后端源文件**, 不是前端自己抄的一份常量 —— 形态 D: 两份一定会漂,
 * 所以其中一份必须对另一份校验。
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { menuConfig, type MenuItem } from '@/components/layout/menuConfig';

const PAGE_PATH = '/system/permission-matrix';
const ROUTER_SRC = resolve(__dirname, '../../../../router/index.ts');
const CONTROLLER_SRC = resolve(
  __dirname,
  '../../../../../../backend/java/cretas-api/src/main/java/com/cretas/aims/controller/permission/PermissionMatrixController.java',
);

function findMenuItem(items: MenuItem[], path: string): MenuItem | null {
  for (const item of items) {
    if (item.path === path) return item;
    const hit = item.children ? findMenuItem(item.children, path) : null;
    if (hit) return hit;
  }
  return null;
}

describe('权限矩阵页门禁 = 后端权限码', () => {
  it('侧边栏里有这一项, 且 module 是 system', () => {
    const item = findMenuItem(menuConfig, PAGE_PATH);
    expect(item, `menuConfig 里找不到 ${PAGE_PATH} —— 页面没有入口`).not.toBeNull();
    expect(item!.module).toBe('system');
    // ⛔ 不许加 roles 白名单: 那会让菜单比接口更严/更松, 两个判据分叉
    expect(item!.roles).toBeUndefined();
  });

  it('路由 meta.module 也是 system', () => {
    const src = readFileSync(ROUTER_SRC, 'utf-8');
    const block = src.match(
      /name:\s*'SystemPermissionMatrix'[\s\S]{0,400}?meta:\s*\{[^}]*\}/,
    );
    expect(block, 'router/index.ts 里找不到 SystemPermissionMatrix —— 闸自己瞎了').not.toBeNull();
    expect(block![0]).toContain("module: 'system'");
  });

  it('后端控制器的 @RequirePermission 用的正是 system:read / system:read_write', () => {
    const java = readFileSync(CONTROLLER_SRC, 'utf-8');
    // 前提断言: 真的读到了那个控制器
    expect(java).toContain('class PermissionMatrixController');
    expect(java).toContain('@RequirePermission({"system:read", "system:read_write"})');
    // 阴性对照: 后端如果改成别的模块, 上面那条会红而不是静默通过
    expect(java).not.toMatch(/@RequirePermission\(\{"(?!system:)/);
  });

  it('⛔ 后端没有任何写入端点 (step 1 只读)', () => {
    const java = readFileSync(CONTROLLER_SRC, 'utf-8');
    expect(java).not.toContain('@PostMapping');
    expect(java).not.toContain('@PutMapping');
    expect(java).not.toContain('@PatchMapping');
    expect(java).not.toContain('@DeleteMapping');
    // 阳性对照: GET 是有的, 否则「一个映射都没有」也能让上面四条全绿
    expect(java).toContain('@GetMapping("/matrix")');
  });
});
