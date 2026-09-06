import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { sidebarModuleAxis } from '@/views/system/permission-matrix/moduleAxis';

/**
 * 闸: 全仓「模块清单」向 **L1 `platform_role_permissions` 的模块码集合**收敛 (owner 2026-09-06 裁定)。
 *
 * ## 演进
 * - v1 (#657): 8 份清单两两对称差, 棘轮基线 203 —— 那时还没人拍「哪一份是真的」。
 * - v2.1 (2026-09-07 刀 D): 角色权限页那**两份**清单 (`RoleController.DISPLAY_MODULES` 与
 *   `roles/list.vue` 的 `allPermissionModules`) 已删除 —— 那一页的列改成后端三层现算。
 *   清单数 8 → 6。⚠️ 「两份逐字相同」那条断言随之失去比较对象, ⛔ 没有留成恒真式:
 *   换成「这两份不许长回来」, 并给判据本身配了正反两个对照。
 * - v2: owner 拍了 **L1 为准, 其它份向它收敛; 餐饮不管**。于是轴换成 L1 的模块码集合,
 *   每份清单只报两个数: 「L1 有它没有」(missing) 与 「它有 L1 没有」(extra); 棘轮量的是这两个数的总和。
 *   ⛔ 两两比对不再是判据 —— 8 份互相一致但一起偏离 L1 也是错的。
 *
 * ## L1 在仓里的载体
 * `frontend/CretasFoodTrace/src/utils/permissionMatrix.l1Snapshot.json` —— #654 从 prod 只读导出的
 * L1 快照 (带 `exportedAt`), RN 拿它做离线兜底。这里取它的模块码并集当轴。它会老: 老了轴就老,
 * 刷新方式在 `scripts/gen-permission-l1-snapshot.js` 头部; 本闸把 `exportedAt` 打出来, 报数带口径。
 *
 * ## 三类显式登记 (剔出比对, 但**逐条打印**, ⛔ 不静默)
 * 1. 餐饮码 (`restaurant`, `restaurant*`): Steve 2026-09-06 「餐饮不管」—— 不动不删不补, 单独登记「餐饮侧待定」。
 * 2. 内部码: `production_report_correction` (L1 无行, 但有 2 处后端闸 + F006 的 L2 伪行; 写白名单要认它
 *    才能维护那些 L2 行), `__permissions__` (L2 寄生伪 module)。owner 裁定「降为内部码并登记」。
 * 3. 侧边栏是**菜单的投影**: L1 有而侧边栏没有 = 那个模块没有 web 页面 (work_report / report / operations
 *    是 RN 报工/报表/运营的模块), 登记不算漂; 侧边栏有而 L1 没有 仍然算 (那才是清单漂了)。
 *
 * ## 三态 (硬约束 4)
 *  · 提取器抓不到 / 低于地板 ⇒ INSTRUMENT DEAD 单独红 (打空 ≠ 一致)。
 *  · L1 写白名单 == L2 写白名单 逐字相等 (形态 D′)。
 *  · 其余 ⇒ 棘轮: 与 L1 的偏离总条数 ≤ 基线, ⛔ 只许往下调。
 *
 * ## 代理性与已知盲区
 *  · 正则读字面量; 清单改成运行时拼装会打空 ⇒ 走 INSTRUMENT DEAD。
 *  · 只比同粒度模块码; L4 细粒度 registry 不比。
 *  · 触发面: 读 4 个 Java 文件 + RN 快照 JSON, 都登记在 web-admin-gate.yml (Java 由
 *    `gateTriggerSurfaceCoversWhatItReads.spec.ts` 钉; JSON 那条是手工登记)。
 */

const SRC = resolve(__dirname, '../..');
const REPO = resolve(SRC, '../..');
const JAVA = resolve(REPO, 'backend/java/cretas-api/src/main/java/com/cretas/aims');
const L1_SNAPSHOT = resolve(REPO, 'frontend/CretasFoodTrace/src/utils/permissionMatrix.l1Snapshot.json');

const read = (abs: string) => readFileSync(abs, 'utf8');

function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

function stringLiterals(block: string): string[] {
  return [...block.matchAll(/['"]([A-Za-z_][A-Za-z0-9_]*)['"]/g)].map((m) => m[1]);
}

function between(src: string, open: RegExp): string {
  const m = open.exec(src);
  if (!m) return '';
  return m[1] ?? '';
}

interface ModuleList {
  key: string;
  where: string;
  modules: string[];
  /** true = 菜单投影: 缺项不算漂 (只算 extra) */
  projection?: boolean;
}

function javaAllModules(): ModuleList {
  const src = stripComments(read(resolve(JAVA, 'service/impl/PermissionServiceImpl.java')));
  return { key: 'java.ALL_MODULES', where: 'PermissionServiceImpl.java',
    modules: stringLiterals(between(src, /ALL_MODULES\s*=\s*Arrays\.asList\(([\s\S]*?)\);/)) };
}
function javaL1Allowed(): ModuleList {
  const src = stripComments(read(resolve(JAVA, 'controller/platform/PlatformRolePermissionController.java')));
  return { key: 'java.L1.ALLOWED_MODULES', where: 'PlatformRolePermissionController.java',
    modules: stringLiterals(between(src, /ALLOWED_MODULES\s*=\s*Set\.of\(([\s\S]*?)\);/)) };
}
function javaL2Allowed(): ModuleList {
  const src = stripComments(read(resolve(JAVA, 'controller/FactoryRoleModuleOverrideController.java')));
  return { key: 'java.L2.ALLOWED_MODULES', where: 'FactoryRoleModuleOverrideController.java',
    modules: stringLiterals(between(src, /ALLOWED_MODULES\s*=\s*Set\.of\(([\s\S]*?)\);/)) };
}
// 2026-09-07 刀 D: `java.DISPLAY_MODULES` 与它的前端镜像 `web.rolesPage.allPermissionModules`
// 两份清单**都已删除** —— 角色权限页的列改成后端三层现算 (resolveRoleModuleLevels),
// 中文名走 PermissionModuleCatalog。⇒ 它们的提取器从 LISTS 里摘掉 (留着会读到 0 条 →
// 撞 LIST_SIZE_FLOOR 报 INSTRUMENT DEAD, 那是**假的**仪器故障)。
// 原来钉它俩「逐字相同」的那条断言没有第二份可比了 ⇒ ⛔ 不留恒真, 换成
// 「这两份清单不许长回来」+ 判据自身的阳性对照, 见下方 hardcodedModuleListSignals。
function webModulePermissionsKeys(): ModuleList {
  const src = stripComments(read(resolve(SRC, 'store/modules/permission.ts')));
  const block = between(src, /interface ModulePermissions \{([\s\S]*?)\n\}/);
  const modules = block.split('\n')
    .map((line) => /^\s*([A-Za-z_][A-Za-z0-9_]*)\??:\s*PermissionLevel;/.exec(line)?.[1])
    .filter((x): x is string => Boolean(x));
  return { key: 'web.ModulePermissions', where: 'store/modules/permission.ts (interface)', modules };
}
function webSidebarAxis(): ModuleList {
  return { key: 'web.sidebar', where: 'components/layout/menuConfig.ts (现算)', modules: sidebarModuleAxis(), projection: true };
}
function webStoreAccessibleModules(): ModuleList {
  const src = stripComments(read(resolve(SRC, 'store/modules/permission.ts')));
  const block = between(src, /function getAccessibleModules\(\): ModuleName\[\] \{\s*const modules: ModuleName\[\] = \[([\s\S]*?)\];/);
  return { key: 'web.store.getAccessibleModules', where: 'store/modules/permission.ts (函数内联数组)', modules: stringLiterals(block) };
}
// 2026-09-07 刀 A: `utils/permission.ts` (第三份手写矩阵 + 平台管理员内联数组) 已删 —— 唯一调用点 request.ts
// 的 MATERIAL_UOM_UNCONFIGURED 分支改用 permission store。清单从 9 份降到 8 份, 基线 27 → 21。

const LISTS: ModuleList[] = [
  javaAllModules(),
  javaL1Allowed(),
  javaL2Allowed(),
  webModulePermissionsKeys(),
  webSidebarAxis(),
  webStoreAccessibleModules(),
];

/**
 * 刀 D 删掉的那两份清单「有没有长回来」的判据。
 *
 * ⚠️ 它必须能红: 下面 `判据自身的阳性对照` 拿一段合成源码喂给同一个函数, 证明
 * 「返回空数组」是真的没有, ⛔ 不是我读错了文件或正则失效 (形态 A: 截断把「没有」
 * 变成「我没看到」)。
 *
 * 🔴 **它守的是那两个名字, 不是「不许手工维护清单」这个性质** —— 换个变量名或换个文件
 * 就绕过去了。⛔ 别在 PR 说明里把它说大。真正与名字无关的兜底在另一头:
 * `RoleControllerPermissionsReadL1ChainTest` 的 `verify(never()).getRolePermissions(any())`
 * 加上那组「假的三层链故意返回与旧清单不同的模块集」—— 任何一份写死清单只要真的
 * 被用来出列, 那里就红, 不管它叫什么名字。本条是**便宜的第一道**, 不是唯一一道。
 */
function hardcodedModuleListSignals(javaSrc: string, vueSrc: string): string[] {
  const found: string[] = [];
  if (/DISPLAY_MODULES\s*\.\s*put\s*\(/.test(javaSrc)) found.push('RoleController.DISPLAY_MODULES.put(...)');
  if (/const\s+allPermissionModules\s*=/.test(vueSrc)) found.push('roles/list.vue: const allPermissionModules');
  return found;
}

/** 任何一份真实清单都不会短于这个数; 低于它 = 提取器打空, 不是清单变短。 */
const LIST_SIZE_FLOOR = 8;

// ==================== L1 轴 ====================

interface L1Snapshot { exportedAt: string; rows: number; levels: Record<string, Record<string, string>> }
const SNAPSHOT: L1Snapshot = JSON.parse(read(L1_SNAPSHOT));

function isRestaurantCode(m: string): boolean {
  return m === 'restaurant' || m.startsWith('restaurant');
}
/** 内部码: owner 裁定「降为内部码并登记」, 不参与收敛比对 */
const INTERNAL_CODES = new Set(['production_report_correction', '__permissions__']);

const L1_ALL = new Set(Object.values(SNAPSHOT.levels).flatMap((row) => Object.keys(row)));
const L1_RESTAURANT = [...L1_ALL].filter(isRestaurantCode).sort();
/** 收敛轴 = L1 模块码 − 餐饮码 */
const AXIS = [...L1_ALL].filter((m) => !isRestaurantCode(m)).sort();

interface Deviation { key: string; missing: string[]; extra: string[]; restaurant: string[]; internal: string[]; counted: number }

function deviation(list: ModuleList): Deviation {
  const set = new Set(list.modules);
  const restaurant = list.modules.filter(isRestaurantCode).sort();
  const internal = list.modules.filter((m) => INTERNAL_CODES.has(m)).sort();
  const extra = list.modules.filter((m) => !isRestaurantCode(m) && !INTERNAL_CODES.has(m) && !L1_ALL.has(m)).sort();
  const missing = AXIS.filter((m) => !set.has(m));
  const counted = extra.length + (list.projection ? 0 : missing.length);
  return { key: list.key, missing, extra, restaurant, internal, counted };
}

function renderTable(devs: Deviation[]): string {
  const head = '| 清单 | 条数 | L1 有它没有 (missing) | 它有 L1 没有 (extra) | 餐饮码 (登记) | 内部码 (登记) | 计入棘轮 |\n|---|---|---|---|---|---|---|';
  const body = devs.map((d, i) => {
    const l = LISTS[i];
    const miss = l.projection ? `${d.missing.join(' ') || '—'} (投影, 不计)` : (d.missing.join(' ') || '—');
    return `| ${d.key} | ${l.modules.length} | ${miss} | ${d.extra.join(' ') || '—'} | ${d.restaurant.join(' ') || '—'} | ${d.internal.join(' ') || '—'} | ${d.counted} |`;
  }).join('\n');
  return `${head}\n${body}`;
}

/**
 * 棘轮基线 = 与 L1 轴的偏离总条数 (extra + 非投影清单的 missing)。
 * 2026-09-06 v2 首次实测见 PR body; ⛔ 只许往下调, 每收敛一份就降一次。
 */
// 2026-09-06 v2 首测 29 → 删 ai 27 (#675) → 刀 A 删 utils/permission.ts 21 (#690) → 刀 B 补 operations 19 (#695)
// → 刀 D 删角色权限页两份清单 7。
// ⚠️ 棘轮是 `<=`, 所以「CI 绿」只证明 ≤ 基线, **不证明恰好等于**。每次下调都要把减法算出来:
//    刀 B 给 L1/L2 两份写白名单各补了 operations ⇒ 各自的 missing 从 1 变 0 ⇒ 21 − 2 = 19 (实测一致)。
//    刀 D 删掉的两份清单各有 11 个码、全部落在 17 个码的轴上 ⇒ 各自 missing = 17 − 11 = 6, extra = 0
//    ⇒ 各计 6 ⇒ 19 − 6 − 6 = 7 (2026-09-07 实测一致)。
// 🔴 这个数变小有两种成因: 某份清单真的向 L1 收敛了 / 某份清单**整份不存在了**。
//    刀 D 是后者 —— 偏离条数少了 12, 但那 12 条不是被修好的, 是连同载体一起删的。
//    读数相同而含义不同 ⇒ 报数必须连成因一起报。
const DEVIATION_BASELINE = 7;

describe('module lists converge to L1 (ratchet, source-scan)', () => {
  const devs = LISTS.map(deviation);
  const total = devs.reduce((s, d) => s + d.counted, 0);

  it('INSTRUMENT ALIVE: 每份清单都提取到了且不短于地板; L1 快照非空且带日期', () => {
    const dead = LISTS.filter((l) => l.modules.length < LIST_SIZE_FLOOR).map((l) => `${l.key}@${l.where} → ${l.modules.length} 条`);
    expect(dead, `INSTRUMENT DEAD — 提取器没抓到清单 (先查正则, 再查清单): ${dead.join('; ')}`).toEqual([]);
    for (const l of LISTS) expect(new Set(l.modules).size, `${l.key} 里有重复项`).toBe(l.modules.length);
    expect(AXIS.length, 'INSTRUMENT DEAD — L1 快照里没有非餐饮模块码').toBeGreaterThanOrEqual(LIST_SIZE_FLOOR);
    expect(SNAPSHOT.exportedAt).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('两份逐字重复的写白名单 (L1 / L2) 必须相等 (形态 D′)', () => {
    const l1 = LISTS.find((l) => l.key === 'java.L1.ALLOWED_MODULES')!;
    const l2 = LISTS.find((l) => l.key === 'java.L2.ALLOWED_MODULES')!;
    expect([...l1.modules].sort()).toEqual([...l2.modules].sort());
  });

  // 刀 D 之前这里是「角色权限页两份必须逐字相等」。两份都删了 ⇒ 那条断言没有第二份可比,
  // 留着就是恒真式 (形态 B′)。换成「不许长回来」——它守的是同一件事的下一个状态。
  it('刀 D: 角色权限页那两份写死清单不许长回来', () => {
    const javaSrc = stripComments(read(resolve(JAVA, 'controller/RoleController.java')));
    const vueSrc = stripComments(read(resolve(SRC, 'views/system/roles/list.vue')));

    // 阳性对照: 两个文件都读到了, 而且读到的是刀 D 之后那一版 (不是空串、不是别的文件)
    expect(javaSrc, 'INSTRUMENT DEAD — RoleController.java 没读到改后那版')
      .toContain('resolveRoleModuleLevels');
    expect(vueSrc, 'INSTRUMENT DEAD — roles/list.vue 没读到改后那版')
      .toContain('rolePermissions');

    const found = hardcodedModuleListSignals(javaSrc, vueSrc);
    expect(
      found,
      `角色权限页的模块清单又被写死了: ${found.join('; ')}。`
        + '列由后端 resolveRoleModuleLevels 现算, 中文名由 PermissionModuleCatalog 下发, ⛔ 不要再抄一份。',
    ).toEqual([]);
  });

  /**
   * 刀 D 视觉验收 (2026-09-07) 抓到的缺陷类: 角色权限页把 `restaurantFinance` 这种
   * **英文驼峰码**直接渲染在中文标签旁边给用户看。成因是 `PermissionModuleCatalog` 的
   * 「查不到就原样返回码」兜底 —— 那个兜底方向是对的 (⛔ 不编造)，但它意味着
   * **只要轴上出现一个没登记中文名的码，用户就会看到裸码**。
   *
   * ⇒ 这道闸守的是「轴上每个码都有中文名」。它放在这里而不是 Java 侧, 因为轴的两个来源
   * (L1 快照 / Java `ALL_MODULES`) 本闸已经都读着了, ⛔ 不需要再造一条跨包读取。
   *
   * ⚠️ 代理性: 它按 `m.put("code", "label")` 的字面量读 catalog。catalog 改成运行时拼装
   * 就会读到 0 条 —— 所以下面先断言读到的条数不低于地板 (打空 ≠ 一致)。
   */
  it('轴上每个模块码都必须有中文名 —— 否则用户会看到英文裸码', () => {
    const catalogSrc = stripComments(read(resolve(JAVA, 'permission/PermissionModuleCatalog.java')));
    const labelled = new Map(
      [...catalogSrc.matchAll(/m\.put\("([A-Za-z_][A-Za-z0-9_]*)"\s*,\s*"([^"]+)"\)/g)]
        .map((m) => [m[1], m[2]] as const),
    );
    expect(labelled.size, 'INSTRUMENT DEAD — 没从 PermissionModuleCatalog 读到 put 字面量')
      .toBeGreaterThanOrEqual(LIST_SIZE_FLOOR);

    // 轴 = 后端 knownModuleAxis 的同口径 (L1 出现过的 ∪ 兜底声明的), 减去餐饮码 ——
    // 餐饮码被 RoleController 过滤掉、也不出现在任何 @RequirePermission 里, 见 PR #715。
    const javaAll = javaAllModules().modules;
    const axis = [...new Set([...L1_ALL, ...javaAll])].filter((m) => !isRestaurantCode(m)).sort();
    expect(axis.length, 'INSTRUMENT DEAD — 轴读到空').toBeGreaterThanOrEqual(LIST_SIZE_FLOOR);

    const naked = axis.filter((m) => !labelled.has(m));
    expect(
      naked,
      `这些码会在角色权限页上以英文原样显示给中文用户: ${naked.join(' ')}。`
        + '给它们在 PermissionModuleCatalog 补中文名 (措辞取 menuConfig.ts 的顶层 title), '
        + '并按 CODES_NEWLY_LABELLED_BY_CUT_D 的纪律登记「哪处文案因此变了」。',
    ).toEqual([]);

    // 判据自身的阳性对照: 拿一个确定不在 catalog 里的码, 证明上面那条真会挑出东西
    expect(labelled.has('module_nobody_registered')).toBe(false);
  });

  it('判据自身的阳性对照: 清单真长回来时上面那条会红', () => {
    const fakeJava = 'static { DISPLAY_MODULES.put("production", "生产管理"); }';
    const fakeVue = "const allPermissionModules = [{ key: 'production', label: '生产管理' }];";
    expect(hardcodedModuleListSignals(fakeJava, fakeVue)).toHaveLength(2);
    // 反向: 干净的源码不许被判成「长回来了」(否则闸会天天误报, 形态 E)
    expect(hardcodedModuleListSignals('class RoleController {}', 'const rolePermissions = ref([]);')).toEqual([]);
  });

  it('偏离表 + 棘轮: 与 L1 轴的偏离总条数不许增长 (餐饮码 / 内部码 / 侧边栏缺项 逐条登记, 不计)', () => {
    const report = `[module-lists v2] L1 快照 exportedAt=${SNAPSHOT.exportedAt} rows=${SNAPSHOT.rows}; 轴 (L1 − 餐饮) ${AXIS.length} 个: ${AXIS.join(' ')}`
      + `\n餐饮码 (登记, 不比): ${L1_RESTAURANT.join(' ')}; 内部码 (登记, 不比): ${[...INTERNAL_CODES].join(' ')}`
      + `\n${LISTS.length} 份清单 / 偏离总条数 ${total} (基线 ${DEVIATION_BASELINE})\n${renderTable(devs)}\n`;
    console.log(`\n${report}`);
    // PR body 要贴这张表: MODULE_LISTS_TABLE_OUT=<path> npx vitest run <本文件> 把它落盘 (vitest 通过时不回显 stdout)
    if (process.env.MODULE_LISTS_TABLE_OUT) writeFileSync(process.env.MODULE_LISTS_TABLE_OUT, report, 'utf8');
    expect(
      total,
      `模块清单与 L1 的偏离从基线 ${DEVIATION_BASELINE} 涨到 ${total} —— 有人往某份清单加/删了模块而没向 L1 收敛。⛔ 别改基线; 要么收敛, 要么在 PR 里说明。`,
    ).toBeLessThanOrEqual(DEVIATION_BASELINE);
  });

  it('阴性对照: 往任意一份塞一个 L1 没有的模块 ⇒ 偏离 +1 (证明棘轮量得到 extra)', () => {
    const mutated = { ...LISTS[0], modules: [...LISTS[0].modules, 'module_nobody_registered'] };
    expect(deviation(mutated).counted).toBe(devs[0].counted + 1);
  });

  it('阴性对照: 从非投影清单删掉一个 L1 模块 ⇒ 偏离 +1 (证明棘轮量得到 missing)', () => {
    const target = LISTS.find((l) => l.key === 'java.ALL_MODULES')!;
    const present = target.modules.find((m) => AXIS.includes(m))!;
    const mutated = { ...target, modules: target.modules.filter((m) => m !== present) };
    expect(deviation(mutated).counted).toBe(deviation(target).counted + 1);
  });
});
