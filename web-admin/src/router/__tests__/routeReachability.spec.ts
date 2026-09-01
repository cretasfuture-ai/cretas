import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 每个路由都得有人能走到 —— 棘轮，只禁增长。
 *
 * ## 为什么需要这一条
 *
 * 2026-08 同一周同一形状出现两次：
 *   · PR#76 `/warehouse/storage-locations`（仓储空间）
 *   · PR#96 `/dashboard/...`（车间大屏）
 * 两次都是 **做完了、部署了、RBAC 正确、菜单里零登记**，于是只能手打 URL 才进得去。
 * 前者的后果是生产上 `factory_feature_config` 里 `WAREHOUSE_LAYOUT` **一行都没有** ——
 * 不是功能坏了，是没人找得到。
 *
 * ⇒ 「做完功能」这件事里，此前**没有任何一步是「确认用户能走到」**。这是流程缺口，
 *   不是偶发，所以补一道闸而不是再修一次。
 *
 * ## 判据
 *
 * 一个路由算「可达」，只要满足其一：
 *   1. `menuConfig.ts` 里登记了它（侧边栏点得到）
 *   2. 代码里任何地方以字面量引用了它（`router.push('/x')` / `<RouterLink to="/x">` /
 *      面包屑 / 卡片跳转……）
 * 两者都没有 = 只能手打 URL = 对真实用户等于不存在。
 *
 * ## ⚠️ 这道闸的代理性与已知盲区（写明，不靠加启发式逼近）
 *
 *  · **动态拼接的跳转看不见**：`router.push('/hr/' + sub)` 这种本闸判不出，
 *    会误报成孤儿。⇒ 所以是**棘轮**而不是硬红，误报只需登记一次。
 *  · **只看 web-admin**：RN 侧的可达性不在本闸范围。
 *  · **不判权限**：菜单里有、但当前角色看不到，本闸算可达。那是另一个问题。
 *
 * ## 🔴 建闸过程中这把尺子自己错了三次（留给下一个人）
 *
 * 孤儿数读出过 **62 → 83 → 84 → 92 → 91** 五个值，**同一份代码**。每次都是仪器的问题，
 * 不是代码变了。记在这里是因为三个成因彼此独立，而且都**不报错、只给一个偏小的数**：
 *
 * | 读数 | 成因 |
 * |---|---|
 * | 62 | 早期脚本的扫描范围与此处不同（已废弃，未追查）——⚠️ 它是四个数里唯一没被解释掉的 |
 * | 83 | 排除 `router/index.ts` 写的是 `endswith('router/index.ts')`，而 **Windows 上路径是反斜杠**，该条件恒不成立 ⇒ 路由表自己进了「引用池」，它当然"引用"了每一个路径 |
 * | 84 | 归一化分隔符后的正确值 —— 但只扫了 `router/index.ts` |
 * | 92 | `index.ts` 还 **import 了 `router/modules/*.ts`**（smartbi 21 / production-analytics 7 / ops 5 / crm 1），漏掉 34 条路由、其中 8 条是孤儿 |
 * | **91** | 没剥注释 ⇒ 一条**被注释掉的**路由（`/system/role-permissions`，组件目录都不存在）被数进了存量 |
 *
 * ⇒ 两条可复用的判据：
 *   1. **跨平台路径比较一律先归一化分隔符**，否则 `endswith` 会静默恒假。
 *   2. **「路由表」不一定只有一个文件** —— 先确认入口 import 了谁，再决定扫什么。
 *      这正是仓里说的「我量的不是我想知道的那个数」。
 *
 * ## 🔴 顺带查出来的一件事：E2E 测过的页面，用户走不到
 *
 * 复核这批孤儿时发现有 5 条在 E2E 里被引用（`/analytics/ai-reports`、
 * `/finance/voucher-export`、`/indicator-center`、`/sales/finance-review`、
 * `/system/data-fabric/provenance-config`）。E2E 是**直接敲 URL** 进去的，
 * 那和用户在地址栏手打是同一件事 ⇒ 它们仍是孤儿。
 *
 * ⚠️ 值得记一笔：**E2E 绿着，而功能对真实用户不存在**。E2E 恰好绕开了它本该发现的那个缺口。
 * 所以本闸不看 E2E 目录 —— 「测试能到达」不是「用户能到达」。
 */

const SRC = resolve(__dirname, '../..');

/**
 * ⚠️ 路由不只在 `router/index.ts` 里 —— 还有 `router/modules/*.ts`（smartbi 21 条、
 * production-analytics 7 条、ops 5 条、crm 1 条）由 index.ts import 进来。
 * 只读 index.ts 会漏掉 34 条路由，那正是本文件头部记的那类「量的不是想知道的东西」。
 */
const ROUTER_FILES = [
  join(SRC, 'router/index.ts'),
  ...readdirSync(join(SRC, 'router/modules'))
    .filter((f) => f.endsWith('.ts'))
    .map((f) => join(SRC, 'router/modules', f)),
];
const MENU = readFileSync(join(SRC, 'components/layout/menuConfig.ts'), 'utf8');

/**
 * ⛔ 先剥注释再解析 —— 否则**被注释掉的路由**照样被数成孤儿。
 *
 * 实测：`/system/role-permissions` 在 `index.ts:1326` 是 `// path: 'role-permissions',`，
 * 它的组件目录 `views/system/role-permissions/` **根本不存在**。第一版把它算进了存量，
 * 于是预算虚高 1 条。本仓已多次栽在「闸把注释当成被测对象」上，这是同一形状。
 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

/** 路由表里父子嵌套的相对段要拼成完整路径，否则 `/workflow/acted` 会被读成 `/acted`。 */
function collectRoutePaths(rawSrc: string): Set<string> {
  const src = stripComments(rawSrc);
  const out = new Set<string>();
  const stack: Array<{ depth: number; seg: string }> = [];
  let depth = 0;
  for (const line of src.split('\n')) {
    const m = /path:\s*'([^']*)'/.exec(line);
    if (m) {
      const p = m[1];
      while (stack.length && stack[stack.length - 1].depth >= depth) stack.pop();
      const parent = stack.map((s) => s.seg).join('');
      let cur: string;
      if (p.startsWith('/')) {
        cur = p;
        stack.length = 0;
        stack.push({ depth, seg: p.replace(/\/$/, '') });
      } else {
        cur = p ? `${parent.replace(/\/$/, '')}/${p}` : parent;
        stack.push({ depth, seg: `/${p}` });
      }
      if (cur && !cur.includes(':') && !cur.includes('pathMatch')) out.add(cur);
    }
    depth += (line.split('{').length - 1) + (line.split('[').length - 1)
           - (line.split('}').length - 1) - (line.split(']').length - 1);
  }
  return out;
}

/** 除路由表与菜单本身之外的全部源码，用来判断「有没有别处跳过去」。 */
function readAllOtherSources(dir: string, acc: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (name === 'node_modules') continue;
      readAllOtherSources(full, acc);
      continue;
    }
    if (!/\.(vue|ts|tsx)$/.test(name)) continue;
    // ⚠️ 归一化分隔符再比较 —— 见文件头那次事故
    const posix = full.split('\\').join('/');
    if (
      posix.endsWith('router/index.ts') ||
      posix.includes('/router/modules/') ||
      posix.includes('menuConfig')
    ) {
      continue;
    }
    acc.push(readFileSync(full, 'utf8'));
  }
  return acc;
}

const MENU_PATHS = new Set(
  Array.from(MENU.matchAll(/path:\s*'([^']+)'/g)).map((m: RegExpMatchArray) => m[1]),
);
const ROUTE_PATHS = new Set(
  ROUTER_FILES.flatMap((f) => Array.from(collectRoutePaths(readFileSync(f, 'utf8')))),
);
const OTHER_SOURCE = readAllOtherSources(SRC).join('\n');

/** 登录/错误页等本来就不该进菜单。 */
const NOT_MENU_BY_NATURE = new Set(['/', '/login', '/403', '/404', '/mobile-only', '/demo']);

function isReachable(path: string): boolean {
  if (MENU_PATHS.has(path)) return true;
  return OTHER_SOURCE.includes(`'${path}'`) || OTHER_SOURCE.includes(`"${path}"`);
}

const ORPHANS = Array.from(ROUTE_PATHS)
  .filter((p) => !NOT_MENU_BY_NATURE.has(p) && !p.startsWith('/share'))
  .filter((p) => !isReachable(p))
  .sort();

/**
 * 建闸当天的存量 91 条；2026-08-27 补了会计凭证 3 + 标签识别诊断 2 + 半成品/操作日志 3 个入口后为 83 条。
 *
 * 2026-09-01 LIUSHANMEN 仓储「库存盘点」菜单收拢 (owner 拍板): 6 个菜单项合并为
 * 2 个 hub 入口。合并后的 6 个旧路径全部只是 router redirect（不再是菜单项），
 * 但仍能通过「代码里其它地方以字面量引用了它」这条判据保持可达 —— 已在
 * inventoryQueryTab.ts / stocktakeQueryTab.ts / warehouseHubRedirect.ts /
 * inventory-query 与 stocktake-query 两个 hub 组件的头部注释里逐条写明「旧路径
 * → 新 tab」的映射（本来就该写的文档，同时满足了本闸的可达性判据）。
 * 实测：合并前后 ORPHANS 从 81 降到 **80**（不是升高——旧的 6 条本来就靠菜单
 * 登记撑着可达性，现在换成了字面量引用，账面还倒赚 1，因为其中几条原本已经
 * 靠别处的字面量引用可达，新增的文档对它们是重复覆盖）。
 *
 * ⛔ 这个数只许变小。往上抬 = 又上线了一个用户找不到的页面，
 *    那正是本闸要拦的那件事。修好一个（加菜单项或加跳转入口）就把它调小。
 *
 * ⚠️ 存量里都是真页面，不是空壳 —— 抽验过 `views/hr/salaries/list.vue` **806 行**、
 *    `views/finance/voucher/list.vue` **496 行**。整套 HR（工资单/请假/加班/考勤异常/
 *    年度汇算）、整套凭证（导出/列表/模板）、调拨、半成品仓、研发报价、导入导出中心
 *    都在里面。**做完了，没人找得到。**
 */
const ORPHAN_BUDGET = 80;

describe('路由可达性（棘轮：上线即不可达的页面只许变少）', () => {
  it('阳性对照：仪器确实读到了路由表与菜单', () => {
    // 任一读空都会让下面那条「孤儿数没超预算」假绿。
    expect(ROUTE_PATHS.size).toBeGreaterThan(200);
    expect(MENU_PATHS.size).toBeGreaterThan(100);
    expect(OTHER_SOURCE.length).toBeGreaterThan(100000);
  });

  it('被注释掉的路由不算数（剥注释）', () => {
    // /system/role-permissions 在 index.ts 里是 `// path: 'role-permissions',`,
    // 且 views/system/role-permissions/ 目录不存在。它既不该算路由, 也不该算孤儿。
    expect(ROUTE_PATHS).not.toContain('/system/role-permissions');
    expect(ORPHANS).not.toContain('/system/role-permissions');
  });

  it('阴性对照：菜单里登记过的路径不会被判成孤儿', () => {
    // 这两条是 PR#76/#96 修完之后加进菜单的，必须判为可达；
    // 若它们出现在孤儿名单里，说明判据本身坏了。
    expect(ORPHANS).not.toContain('/warehouse/storage-locations');
    expect(ORPHANS).not.toContain('/warehouse/stocktakes');
  });

  it(`孤儿路由不超过 ${ORPHAN_BUDGET} 条（只许变少）`, () => {
    // 失败时把完整名单打出来，便于判断是"新增了一个"还是"修好了几个该调预算"。
    expect(
      ORPHANS.length,
      `孤儿路由 ${ORPHANS.length} 条（预算 ${ORPHAN_BUDGET}）：\n  ${ORPHANS.join('\n  ')}\n\n` +
        '新增了一个用户找不到的页面？请在 menuConfig.ts 里登记，或在页面里加跳转入口。\n' +
        '修好了几个？把 ORPHAN_BUDGET 调小。⛔ 不许调大。',
    ).toBeLessThanOrEqual(ORPHAN_BUDGET);
  });
});
