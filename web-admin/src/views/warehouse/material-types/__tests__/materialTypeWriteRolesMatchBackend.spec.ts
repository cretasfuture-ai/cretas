/**
 * 缺陷 46 follow-up (2026-09-04, owner 拍板): 「只让仓储主管改」物料字典。
 *
 * 这道闸钉的是「两份一致」, 不是「各自都对」(与 reportApprovalMenuMatchesBackendRoles.spec.ts
 * 同一模式) —— MATERIAL_TYPE_WRITE_ROLES 从 Java 源码推导期望值, ⛔ 不在测试里手抄一份角色
 * 名单, 手抄的那份会跟着漂, 而漂了不会有人发现 (measurement-and-wiring 形态 D)。
 *
 * 四处来源必须两两一致:
 *   ① RawMaterialTypeController#createMaterialType 的 @RequireRole
 *      (updateMaterialType / deleteMaterialType 与其逐字节相同, 由后端
 *       RawMaterialTypeManagerOnlyRoleGateTest 钉住, 本闸不重复读它们)
 *   ② MaterialPackagingHierarchyController#upsert 的 @RequireRole (物料主记录与包装规格
 *      是两个独立事务, 漏了任一边会让「保存成功但包装规格 403」这种更隐蔽的形式复发)
 *   ③ RequireRoleInterceptor.PLATFORM_ADMIN_ROLES (无条件放行, 不写进 @RequireRole 本身
 *      但拦截器逻辑上等价于放行)
 *   ④ list.vue 的本地 `MATERIAL_TYPE_WRITE_ROLE_SET`（2026-09-04 CI 追加）——
 *      list.vue **不 import** materialTypeWriteRoles.ts 里的数组，而是自己在
 *      `<script setup>` 里重复声明了一份同样内容的字面量 `Set`。原因: 分支可达性
 *      静态分析器 (`src/__tests__/gates/branchReachability.spec.ts`) 的 `fileEnv`
 *      只解析同一个 `.vue` 文件 `<script>` 里的 `VariableDeclaration`，跨文件的函数
 *      调用 `canManageMaterialTypes(role)` 对它是不可判定的 `unknown`，而它的
 *      `core()` 会把 `and`/`or` 链里的 `unknown` 操作数直接丢弃 —— 于是
 *      `canWrite && canManageMaterialTypes(role)` 被它读成只剩 `canWrite`，取反后
 *      与原式拼成 `canWrite && !canWrite`，被误判成一条恒假的「空转分支」（棘轮
 *      18→19，2026-09-04 CI 实测）。改成本地字面量 `Set([...]).has(...)` 后分析器
 *      认得出，但这就制造了本文件开头那条规则本来要杜绝的「手抄」——本测试补一条
 *      「list.vue 那份 == materialTypeWriteRoles.ts 那份」的相等断言，堵住这个缺口。
 */
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { MATERIAL_TYPE_WRITE_ROLES, canManageMaterialTypes } from '../materialTypeWriteRoles';

/** list.vue 里 `MATERIAL_TYPE_WRITE_ROLE_SET = new Set([...])` 里的角色字面量。 */
function listVueRoleSet(): string[] {
  const src = readFileSync(resolve(process.cwd(), 'src/views/warehouse/material-types/list.vue'), 'utf8');
  const m = src.match(/MATERIAL_TYPE_WRITE_ROLE_SET\s*=\s*new Set\(\[([\s\S]*?)\]\)/);
  expect(m, 'list.vue 里读不到 MATERIAL_TYPE_WRITE_ROLE_SET —— 本闸失去被测对象, 或者它又改回了'
    + ' import 的写法(那样分支可达性闸会重新把写门禁读成空转分支)').toBeTruthy();
  return [...m![1].matchAll(/'([a-z_]+)'/g)].map((x) => x[1]);
}

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
  return readFileSync(
    join(repoRoot(), 'backend/java/cretas-api/src/main/java/com/cretas/aims', rel),
    'utf8',
  );
}

/** 抠出某个方法体上紧邻的 @RequireRole({...}) —— ⛔ 不能抓成同文件里别的方法的。 */
function requireRoleOf(source: string, methodMarker: string): string[] {
  const start = source.indexOf(methodMarker);
  expect(start, `没找到 ${methodMarker} —— 本闸失去被测对象`).toBeGreaterThan(-1);
  const end = source.indexOf('public ApiResponse', start);
  expect(end, `没找到 ${methodMarker} 之后的方法签名`).toBeGreaterThan(start);
  const block = source.slice(start, end);
  const ann = block.match(/@RequireRole\(\{([^}]*)\}\)/);
  expect(ann, `${methodMarker} 上读不到 @RequireRole —— 后端换了闸的形态, 本闸必须跟着改`)
    .toBeTruthy();
  return [...ann![1].matchAll(/"([a-z_]+)"/g)].map((m) => m[1]);
}

function platformAdminRoles(): string[] {
  const src = readJava('config/RequireRoleInterceptor.java');
  const block = src.match(/PLATFORM_ADMIN_ROLES\s*=\s*Set\.of\(([\s\S]*?)\);/);
  expect(block, '读不到 PLATFORM_ADMIN_ROLES —— 绕过名单变了形态').toBeTruthy();
  return [...block![1].matchAll(/"([a-z_]+)"/g)].map((m) => m[1]);
}

describe('物料字典写门禁: MATERIAL_TYPE_WRITE_ROLES 必须等于后端实际放行的角色全集', () => {
  it('三个来源都真的解析出了角色(阳性对照 —— 空集会让下面的相等断言恒真)', () => {
    const src = readJava('controller/RawMaterialTypeController.java');
    expect(requireRoleOf(src, '@PostMapping').length === 0).toBe(false);
    expect(platformAdminRoles().length).toBeGreaterThan(0);
    expect(MATERIAL_TYPE_WRITE_ROLES.length).toBeGreaterThan(0);
  });

  it('MATERIAL_TYPE_WRITE_ROLES == RawMaterialTypeController@RequireRole ∪ PLATFORM_ADMIN_ROLES', () => {
    const rawMaterialSrc = readJava('controller/RawMaterialTypeController.java');
    // @PostMapping (裸, 无路径) 在这个 controller 里只出现在 createMaterialType 上,
    // 是它唯一的锚点 —— 与 reportApprovalMenuMatchesBackendRoles.spec.ts 用
    // @GetMapping("/pending-approval") 做锚点是同一手法: 用一个方法独有、不会撞到
    // 别的方法的注解字符串, 而不是方法签名本身 (注解顺序是 Mapping → Operation →
    // RequireRole → 方法签名, 与后端 ProcessWorkReportingController 的写法一致)。
    const createRoles = requireRoleOf(rawMaterialSrc, '@PostMapping');
    const expected = [...new Set([...createRoles, ...platformAdminRoles()])].sort();
    const actual = [...MATERIAL_TYPE_WRITE_ROLES].sort();

    expect(
      actual,
      'MATERIAL_TYPE_WRITE_ROLES 与后端放行的角色不是同一批 ⇒ 差集里的角色要么'
        + '「看得见点不开」(前端多), 要么「前端放行、后端仍 403」(前端少)。'
        + `\n  后端: ${expected.join(', ')}\n  前端: ${actual.join(', ')}`,
    ).toEqual(expected);
  });

  it('updateMaterialType / deleteMaterialType 的 @RequireRole 与 createMaterialType 逐字节相同', () => {
    const rawMaterialSrc = readJava('controller/RawMaterialTypeController.java');
    const createRoles = requireRoleOf(rawMaterialSrc, '@PostMapping');
    const updateRoles = requireRoleOf(rawMaterialSrc, '@PutMapping("/{id}")');
    const deleteRoles = requireRoleOf(rawMaterialSrc, '@DeleteMapping("/{id}")');

    expect([...updateRoles].sort()).toEqual([...createRoles].sort());
    expect([...deleteRoles].sort()).toEqual([...createRoles].sort());
  });

  it('MaterialPackagingHierarchyController#upsert/delete 的 @RequireRole 与 createMaterialType 逐字节相同', () => {
    const rawMaterialSrc = readJava('controller/RawMaterialTypeController.java');
    const packagingSrc = readJava('controller/MaterialPackagingHierarchyController.java');
    const createRoles = requireRoleOf(rawMaterialSrc, '@PostMapping');
    const upsertRoles = requireRoleOf(packagingSrc, '@PutMapping("/by-material/{materialTypeId}")');
    const deleteRoles = requireRoleOf(packagingSrc, '@DeleteMapping("/by-material/{materialTypeId}")');

    expect(
      [...upsertRoles].sort(),
      'material-packaging 的角色白名单与 raw-material-types 漂了 —— 会让「物料主记录保存'
        + '成功、包装规格却 403」这种更隐蔽的形式复发',
    ).toEqual([...createRoles].sort());
    expect([...deleteRoles].sort()).toEqual([...createRoles].sort());
  });

  it('🔴 阴性对照: warehouse_worker 不在名单里 —— 它正是本次改动要挡住的角色', () => {
    expect(
      MATERIAL_TYPE_WRITE_ROLES,
      'warehouse_worker 回到名单里, 就等于把「只让仓储主管改」这条 owner 裁定撤销了。',
    ).not.toContain('warehouse_worker');
    expect(canManageMaterialTypes('warehouse_worker')).toBe(false);
  });

  it('🔴 阴性对照: warehouse_manager 在名单里 —— 加白名单不许误伤仓储主管', () => {
    expect(MATERIAL_TYPE_WRITE_ROLES).toContain('warehouse_manager');
    expect(canManageMaterialTypes('warehouse_manager')).toBe(true);
  });

  it('⛔ 阴性对照: 不存在的 factory_admin 角色不该出现在前端名单里', () => {
    expect(
      MATERIAL_TYPE_WRITE_ROLES,
      'FactoryUserRole 里没有 factory_admin 这个值 (见后端 CanvasThresholdsControllerPermissionTest.deadRoleIsGone)',
    ).not.toContain('factory_admin');
  });

  it('canManageMaterialTypes(null/undefined/空串) 一律 false —— 不能把「未登录」判成可写', () => {
    expect(canManageMaterialTypes(null)).toBe(false);
    expect(canManageMaterialTypes(undefined)).toBe(false);
    expect(canManageMaterialTypes('')).toBe(false);
  });

  it('阳性对照: list.vue 那份本地 Set 字面量真的解析出了角色(空集会让下面的相等断言恒真)', () => {
    expect(listVueRoleSet().length).toBeGreaterThan(0);
  });

  it('list.vue 的 MATERIAL_TYPE_WRITE_ROLE_SET 与 materialTypeWriteRoles.ts 的 MATERIAL_TYPE_WRITE_ROLES 逐字节相同', () => {
    const fromVue = [...listVueRoleSet()].sort();
    const fromModule = [...MATERIAL_TYPE_WRITE_ROLES].sort();
    expect(
      fromVue,
      'list.vue 里为兼容分支可达性静态分析器而重复声明的本地角色 Set 与'
        + ' materialTypeWriteRoles.ts 漂了 —— 两份手抄内容不许不一致。'
        + `\n  materialTypeWriteRoles.ts: ${fromModule.join(', ')}\n  list.vue: ${fromVue.join(', ')}`,
    ).toEqual(fromModule);
  });

  it('🔴 阴性对照: list.vue 那份本地 Set 里也不许出现 warehouse_worker', () => {
    expect(listVueRoleSet()).not.toContain('warehouse_worker');
  });
});
