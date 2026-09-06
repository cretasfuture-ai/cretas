import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { sidebarModuleAxis } from '@/views/system/permission-matrix/moduleAxis';

/**
 * 闸: 全仓「模块清单」两两一致 —— 只读源码, 不改行为 (线内任务 3, 2026-09-06)。
 *
 * ## 为什么有这道闸
 * PR #646 数出全仓 9 处模块清单, 去掉两对逐字重复和一份粒度不同的, 剩 **7 份互不一致**
 * 的同粒度清单 (形态 D: 同一个东西有 N 份, 它一定会漂)。本闸把它们**现算**出来两两比对,
 * 把差异表打到控制台并钉一个**棘轮** —— 只禁增长, ⛔ 不在这里挑「哪一份是真的」:
 * 那是 owner 的裁定; 裁定之后把别的份收敛过去, 棘轮基线就往 0 走。
 *
 * ## 三态 (硬约束 4)
 *  · 提取器抓不到 / 抓到的条数低于地板 ⇒ **INSTRUMENT DEAD**, 单独一条断言、单独措辞,
 *    ⛔ 不折叠进「一致」。正则打空的样子和「清单空了」一模一样, 必须分开报。
 *  · 两份逐字重复的 (L1 写白名单 == L2 写白名单) ⇒ 硬断言相等 (形态 D′: 抽不成一份就钉一致)。
 *  · 其余 ⇒ 棘轮: 两两对称差总条数 ≤ 基线。
 *
 * ## 代理性与已知盲区 (写明, 不靠加启发式逼近)
 *  · 用正则读字面量, 不解析 AST。清单若改成运行时拼装 (例如从枚举生成), 提取器会打空 ⇒ 走
 *    INSTRUMENT DEAD 那条, 不会假绿。
 *  · 只比**同粒度**的模块码 (dashboard / production / restaurantOps …)。
 *    `ProductionModuleRegistry.MODULES` 与 `web-admin moduleRegistry.ts` 是 L4 细粒度
 *    (production_plan / finance_ap …), 与这里的清单不是同一种东西, ⛔ 不比。
 *  · `utils/permission.ts` 与 RN `permissionHelper.ts` 的**角色×模块矩阵**的键集合是
 *    隐式清单, 这里不数 (RN 那份已在 #654 换成 L1 生成快照; web-admin 那份是 stores/permission*
 *    的后续)。
 *  · 触发面: 本闸读 4 个 Java 文件, 它们**必须**登记进 web-admin-gate.yml 的 paths
 *    (`gateTriggerSurfaceCoversWhatItReads.spec.ts` 钉着这件事)。
 */

const SRC = resolve(__dirname, '../..');
const REPO = resolve(SRC, '../..');
const JAVA = resolve(REPO, 'backend/java/cretas-api/src/main/java/com/cretas/aims');

const read = (abs: string) => readFileSync(abs, 'utf8');

/** 剥注释 —— 被注释掉的模块名不算清单成员 (本仓多次栽在这上面)。 */
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
}

/** #1 后端解析链的「已知模块」硬编码清单 (超管 rw 全集就是它)。 */
function javaAllModules(): ModuleList {
  const src = stripComments(read(resolve(JAVA, 'service/impl/PermissionServiceImpl.java')));
  const block = between(src, /ALL_MODULES\s*=\s*Arrays\.asList\(([\s\S]*?)\);/);
  return { key: 'java.ALL_MODULES', where: 'PermissionServiceImpl.java', modules: stringLiterals(block) };
}

/** #2 L1 写白名单 (PUT platform_role_permissions 只认这些模块)。 */
function javaL1Allowed(): ModuleList {
  const src = stripComments(read(resolve(JAVA, 'controller/platform/PlatformRolePermissionController.java')));
  const block = between(src, /ALLOWED_MODULES\s*=\s*Set\.of\(([\s\S]*?)\);/);
  return { key: 'java.L1.ALLOWED_MODULES', where: 'PlatformRolePermissionController.java', modules: stringLiterals(block) };
}

/** #3 L2 写白名单 —— 与 #2 逐字重复的第二份。 */
function javaL2Allowed(): ModuleList {
  const src = stripComments(read(resolve(JAVA, 'controller/FactoryRoleModuleOverrideController.java')));
  const block = between(src, /ALLOWED_MODULES\s*=\s*Set\.of\(([\s\S]*?)\);/);
  return { key: 'java.L2.ALLOWED_MODULES', where: 'FactoryRoleModuleOverrideController.java', modules: stringLiterals(block) };
}

/** #4 角色权限页展示的模块 (RoleController → roles/list.vue)。 */
function javaDisplayModules(): ModuleList {
  const src = stripComments(read(resolve(JAVA, 'controller/RoleController.java')));
  const modules = [...src.matchAll(/DISPLAY_MODULES\.put\("([A-Za-z_][A-Za-z0-9_]*)"/g)].map((m) => m[1]);
  return { key: 'java.DISPLAY_MODULES', where: 'RoleController.java', modules };
}

/** #6 web-admin 前端兜底矩阵的键 (ModulePermissions 接口)。 */
function webModulePermissionsKeys(): ModuleList {
  const src = stripComments(read(resolve(SRC, 'store/modules/permission.ts')));
  const block = between(src, /interface ModulePermissions \{([\s\S]*?)\n\}/);
  const modules = block
    .split('\n')
    .map((line) => /^\s*([A-Za-z_][A-Za-z0-9_]*)\??:\s*PermissionLevel;/.exec(line)?.[1])
    .filter((x): x is string => Boolean(x));
  return { key: 'web.ModulePermissions', where: 'store/modules/permission.ts (interface)', modules };
}

/** #7 侧边栏实际渲染用的模块 (从 menuConfig 现算, #646 选它做权限矩阵页的轴)。 */
function webSidebarAxis(): ModuleList {
  return { key: 'web.sidebar', where: 'components/layout/menuConfig.ts (现算)', modules: sidebarModuleAxis() };
}

/** #9 permission store 的 getAccessibleModules 内联数组。 */
function webStoreAccessibleModules(): ModuleList {
  const src = stripComments(read(resolve(SRC, 'store/modules/permission.ts')));
  const block = between(src, /function getAccessibleModules\(\): ModuleName\[\] \{\s*const modules: ModuleName\[\] = \[([\s\S]*?)\];/);
  return { key: 'web.store.getAccessibleModules', where: 'store/modules/permission.ts (函数内联数组)', modules: stringLiterals(block) };
}

/** #10 utils/permission.ts 给平台管理员返回的内联数组 (#646 表里没数到的第 10 份)。 */
function webUtilsPlatformAdminModules(): ModuleList {
  const src = stripComments(read(resolve(SRC, 'utils/permission.ts')));
  const block = between(src, /function getAccessibleModules[\s\S]*?isPlatformAdmin\(user\)\)\s*\{\s*return \[([\s\S]*?)\];/);
  return { key: 'web.utils.platformAdminModules', where: 'utils/permission.ts (平台管理员内联数组)', modules: stringLiterals(block) };
}

const LISTS: ModuleList[] = [
  javaAllModules(),
  javaL1Allowed(),
  javaL2Allowed(),
  javaDisplayModules(),
  webModulePermissionsKeys(),
  webSidebarAxis(),
  webStoreAccessibleModules(),
  webUtilsPlatformAdminModules(),
];

/** 任何一份真实清单都不会短于这个数; 低于它 = 提取器打空, 不是清单变短。 */
const LIST_SIZE_FLOOR = 8;

function symDiff(a: string[], b: string[]): { onlyA: string[]; onlyB: string[] } {
  const sa = new Set(a);
  const sb = new Set(b);
  return {
    onlyA: [...sa].filter((x) => !sb.has(x)).sort(),
    onlyB: [...sb].filter((x) => !sa.has(x)).sort(),
  };
}

interface PairRow { a: string; b: string; onlyA: string[]; onlyB: string[]; size: number }

function pairwise(lists: ModuleList[]): PairRow[] {
  const rows: PairRow[] = [];
  for (let i = 0; i < lists.length; i += 1) {
    for (let j = i + 1; j < lists.length; j += 1) {
      const { onlyA, onlyB } = symDiff(lists[i].modules, lists[j].modules);
      rows.push({ a: lists[i].key, b: lists[j].key, onlyA, onlyB, size: onlyA.length + onlyB.length });
    }
  }
  return rows;
}

function renderTable(lists: ModuleList[], rows: PairRow[]): string {
  const union = [...new Set(lists.flatMap((l) => l.modules))].sort();
  const head = `| module | ${lists.map((l) => l.key).join(' | ')} |\n|---|${lists.map(() => '---|').join('')}`;
  const body = union
    .map((m) => `| ${m} | ${lists.map((l) => (l.modules.includes(m) ? '✓' : '·')).join(' | ')} |`)
    .join('\n');
  const counts = `| **条数** | ${lists.map((l) => l.modules.length).join(' | ')} |`;
  const pairs = rows
    .filter((r) => r.size > 0)
    .map((r) => `| ${r.a} vs ${r.b} | ${r.size} | 只在前者: ${r.onlyA.join(' ') || '—'} | 只在后者: ${r.onlyB.join(' ') || '—'} |`)
    .join('\n');
  return `${head}\n${body}\n${counts}\n\n| pair | 对称差 | | |\n|---|---|---|---|\n${pairs}`;
}

/**
 * 棘轮基线 = 2026-09-06 实测的两两对称差总条数 (8 份清单 28 对)。
 * ⛔ 只许往下调。owner 裁定「哪一份是真的」之后, 把别的份收敛过去, 这个数就往 0 走。
 */
const PAIRWISE_DIFF_BASELINE = 203;   // 2026-09-06 实测: 28 对里 27 对不相等, 对称差 203 条

describe('module lists pairwise consistency (ratchet, source-scan)', () => {
  const rows = pairwise(LISTS);
  const total = rows.reduce((s, r) => s + r.size, 0);
  const unequalPairs = rows.filter((r) => r.size > 0).length;

  it('INSTRUMENT ALIVE: 每一份清单都提取到了, 且不短于地板 (打空 ≠ 一致)', () => {
    const dead = LISTS.filter((l) => l.modules.length < LIST_SIZE_FLOOR).map((l) => `${l.key}@${l.where} → ${l.modules.length} 条`);
    expect(dead, `INSTRUMENT DEAD — 提取器没抓到清单 (先查正则, 再查清单): ${dead.join('; ')}`).toEqual([]);
    // 每份都是集合: 同一份里不该有重复项
    for (const l of LISTS) {
      expect(new Set(l.modules).size, `${l.key} 里有重复项`).toBe(l.modules.length);
    }
  });

  it('两份逐字重复的写白名单 (L1 / L2) 必须相等 —— 抽不成一份就钉一致 (形态 D′)', () => {
    const l1 = LISTS.find((l) => l.key === 'java.L1.ALLOWED_MODULES')!;
    const l2 = LISTS.find((l) => l.key === 'java.L2.ALLOWED_MODULES')!;
    expect([...l1.modules].sort()).toEqual([...l2.modules].sort());
  });

  it('差异表 (给 owner 定哪一份是真的) + 棘轮: 两两对称差总条数不许增长', () => {
    const table = renderTable(LISTS, rows);
    console.log(`\n[module-lists] 8 份清单 / ${rows.length} 对 / 不相等 ${unequalPairs} 对 / 对称差总条数 ${total} (基线 ${PAIRWISE_DIFF_BASELINE})\n${table}\n`);
    expect(
      total,
      `模块清单两两对称差从基线 ${PAIRWISE_DIFF_BASELINE} 涨到 ${total} —— 有人往其中一份加/删了模块而没同步别的份。` +
        `⛔ 别改基线; 要么同步各份, 要么在 PR 里说明。`,
    ).toBeLessThanOrEqual(PAIRWISE_DIFF_BASELINE);
  });

  it('阴性对照: 往任意一份塞一个别处没有的模块, 对称差必然增长 (证明棘轮量得到)', () => {
    const mutated = LISTS.map((l, i) => (i === 0 ? { ...l, modules: [...l.modules, 'module_nobody_registered'] } : l));
    const mutatedTotal = pairwise(mutated).reduce((s, r) => s + r.size, 0);
    expect(mutatedTotal).toBe(total + (LISTS.length - 1));
  });
});
