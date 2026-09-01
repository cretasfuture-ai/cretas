/**
 * 分支可达性闸（棘轮：空转的权限分支只许变少）—— web-admin 侧的二阶可达性。
 *
 * 前一道守的是**页面**：`src/router/__tests__/routeReachability.spec.ts`（存量 91）
 * ——「有没有人走得到这个路由」。
 * 它守不住这一类：**页面可达 ≠ 那个分支可达**。
 *
 * 本闸问下一层：走得到这个页面的那些主体，能不能让 `v-if="canWrite"` /
 * `:disabled="!canWrite"` 两边都取到值？举不出反例 ⇒ 那道判断从没拒绝过任何人
 * （或者从没放行过任何人）。
 *
 * 分析器在 `lib/branchReachability.ts`，链是**算**出来的，不靠人追、不靠正则：
 *
 *   (角色, 工厂类型) → **真的跑一遍 `router/guards.ts` 的 beforeEach** → 能进哪些路由
 *                   → 路由挂哪个 `.vue`（router 表里的动态 import）
 *                   → 那个 `.vue` 的 script + template 里有哪些权限分支
 *                   → **真的 pinia permission / auth store** 说该主体的权限是什么
 *
 * ## 建闸当天的读数（2026-08-27，origin/main 7841126f35）
 *
 * ```
 * 82 个主体 × 265 条挂了视图的路由 = 21730 次守卫调用 → 4206 对 (主体, 路由) 放行
 * 255 个可达视图文件 → 239 条权限分支
 *   空转  18
 *   有效 182
 *   判不了 39
 * ```
 *
 * ## 三态，⛔ 不许二分
 *
 * 「判不了」单列，并且**单独钉一个上限**。只钉空转数的话，任何让分析器读不懂的改动
 * 都会让它变小、看起来像修好了 —— 本仓记过这个形状（「把一道从没执行的闸换成
 * 另一道从没执行的闸」）。
 *
 * ## 棘轮量的是「有多少个空转分支」，⛔ 不是「有多少行」
 *
 * 同一个条件出现在 `const` + 三处 `v-if`，算 **1 条**（分析器按**权限骨架**的
 * 规范式去重，并把顶层取反抹掉，于是 `canWrite` 与 `!canWrite` 是同一条）。
 * 本仓记过：按行数算会因为「同一个问题被拆成几处」而虚高。
 *
 * ## ⛔ 为什么是棘轮而不是硬红
 *
 * 建闸当天存量 18 条。硬红的闸当天就会被关掉（形态 E：窄而可信 > 宽而被关掉）。
 * 而且这 18 条里有相当一部分**不是 bug 而是设计**：路由 `meta.module` 已经在守卫里
 * 拦过一次，页面里再 `canAccess('finance')` 自然恒真。改它们要动产品判断
 * （是删掉这道多余的判断，还是把路由闸放宽让更多角色进来只读），不是机械改写。
 *
 * ## ⚠️ CI 上不跑（账单）——本闸只在本地跑
 *
 * 一次全量约 20 秒。跑法：
 *   `npx vitest run src/__tests__/gates/branchReachability.spec.ts`
 *   `BRANCH_REACH_REPORT=/tmp/report.md npx vitest run …`（导出完整清单）
 *
 * ⚠️ 类型检查**不能**只跑 `vue-tsc -b`：`tsconfig.app.json` 的 exclude 里有
 *    `src/**\/__tests__/**`，本目录一个文件都不在它的检查范围内（实测：往
 *    `lib/branchReachability.ts` 里塞一个必错的类型，`vue-tsc -b` 仍然 rc=0）。
 *    ⇒ 用 `npx vue-tsc -p tsconfig.gates.json`（同一个探针在它上面 rc=2）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { describe, it, expect, vi, beforeAll } from 'vitest';
import {
  analyze,
  buildHarness,
  judgeSource,
  parseView,
  SRC_DIR,
  BOOL_MEMBERS,
  STRING_MEMBERS,
  VUE_NODE,
  FACTORY_TYPES,
  RUNTIME_CONFIGURABLE_PERMISSION_MODULES,
  type AnalysisResult,
  type Finding,
  type Harness,
} from './lib/branchReachability';

/**
 * 让 `loadFromDb` 立即失败。
 *
 * ⚠️ 这**不改变模型**：不 mock 时 axios 在 jsdom 里最终也失败，同样回落 hardcoded
 *    `PERMISSION_MATRIX`。落到的状态一模一样（`isDbLoaded === false &&
 *    dbPermissions === null`，`buildHarness` 逐个主体断言了这一点）。
 *    差别只有速度：实测 50 条路由 **4106ms → 86ms**（48 倍）。
 */
vi.mock('@/api/permissionApi', () => ({
  getPlatformPermissions: () => Promise.reject(new Error('gate: offline by design')),
  getFactoryOverride: () => Promise.reject(new Error('gate: offline by design')),
  getUserModuleAccess: () => Promise.reject(new Error('gate: offline by design')),
}));

let RESULT: AnalysisResult;
let H: Harness;

beforeAll(async () => {
  H = await buildHarness();
  RESULT = await analyze();
}, 600000);

/**
 * 建闸当天的存量：**18 条空转分支**。
 *
 * ⛔ 只许变小。往上抬 = 又上线了一个「对能走到这里的所有主体恒真/恒假」的权限判断，
 *    那正是本闸要拦的那件事。修好了几条就把这个数调小。
 *
 * ⚠️ 调这个数之前先跑一次看清单 —— 数变小可能是**分析器读不懂了**
 *    （条目掉进「判不了」），不是修好了。所以下面同时钉了「判不了」的上限，
 *    以及三个**覆盖面下限**。
 */
const VACUOUS_BUDGET = 18;

/**
 * 「判不了」的上限（建闸当天 39 条）。
 *
 * 🔴 这一条和空转预算是**配对**的。多出来的通常是**分析器读不懂了**
 *    （块体函数里的早返回、外部 helper、动态模块名），去 `lib/` 里补对应的解析，
 *    ⛔ 不要直接抬上限。
 */
const UNDECIDED_BUDGET = 45;

function render(f: Finding): string {
  const sites = f.sites.map((s) => 'L' + s.line + '(' + s.kind + ')').join(' ');
  return (
    f.relFile + '  ' + sites +
    '\n    条件: ' + f.key +
    '\n    可达主体(' + f.principals.length + '): ' + f.principals.slice(0, 8).join(' ') +
    (f.principals.length > 8 ? ' …' : '') +
    '\n    判定: ' + f.detail
  );
}

describe('分支可达性（棘轮：空转的权限分支只许变少）', () => {
  it('阳性对照：仪器确实读到了成规模的路由 / 主体 / 视图 / 分支', () => {
    // 任何一项读空，下面的棘轮都会假绿 —— 一个扫 0 个文件的闸永远是绿的。
    const s = RESULT.stats;
    expect(s.principals).toBeGreaterThanOrEqual(80);
    expect(s.routes).toBeGreaterThan(280);
    expect(s.redirectRoutes).toBeGreaterThan(30);

    // 🔴 下面五个是**下限棘轮**（建闸当天 265 / 255 / 4206 / 239 / 21730，留 ~3% 余量）。
    //
    // 只写「> 50」这种松下限是不够的：让分析器**看见更少**的改动会让空转数静默变小，
    // 看起来像修好了。⇒ 覆盖面变小也要红。真的删页面了？确认后把这几个数一起调小。
    //
    // 🔴 2026-09-02 调小 (LIUSHANMEN 生产管理菜单收拢, owner 拍板): 19 个各自独立
    // 的旧路由 (component: 指向真实页面文件) 里 14 条改成了 redirect (合并进 5 个
    // hub, 见 router/index.ts + router/modules/production-analytics.ts + 新增的
    // views/production/{approval-hub,material-flow,kanban,analysis,process-hub}/)。
    // 页面文件本身**一个都没删**(仍以未改动的原样组件身份内嵌在 hub 的 el-tab-pane
    // 里)，但它们不再各自拥有一条独立的、带真实 component 的顶级路由，于是
    // routesWithFile / reachableViewFiles / allowedPairs / guardCalls 这四个统计口径
    // (按「(主体, 有真实组件的路由)」遍历算出来的) 一起真实变小 —— 与 #463(仓储
    // 「库存盘点」6→2 hub) 同一形状。branchesExamined 没跌破原阈值 (235 ≥ 232)，
    // 不必跟着调。⛔ 这不是分析器读不懂了(`阳性对照：路由表 → 视图文件的连接
    // 一条都没漏` 那条仍然全绿，namedRoutesMissingFile 仍是 []) —— 是真的减少了
    // 可达面, 按本闸自己的说明「确认后把这几个数一起调小」。
    // 调小后 (2026-09-02 实测 253 / 242 / 4024 / 20746，留 ~3% 余量):
    expect(s.routesWithFile).toBeGreaterThanOrEqual(245);
    expect(s.reachableViewFiles).toBeGreaterThanOrEqual(234);
    expect(s.allowedPairs).toBeGreaterThanOrEqual(3903);
    expect(s.branchesExamined).toBeGreaterThanOrEqual(232);
    expect(s.guardCalls).toBeGreaterThanOrEqual(20123);

    // 三态都得有人 —— 全落进一态说明分类器退化了。
    expect(RESULT.vacuous.length).toBeGreaterThan(0);
    expect(RESULT.effective.length).toBeGreaterThan(0);
    expect(RESULT.undecided.length).toBeGreaterThan(0);
  });

  it('阳性对照：路由表 → 视图文件的连接**一条都没漏**', () => {
    // 这是本闸最容易静默失明的一处：join 不上 ⇒ 那些页面的分支一条都看不见，
    // 而空转数只会**变小**（看起来像修好了）。
    // 判据是机械的：有 name、不是 redirect 的路由，必须对上一个真实存在的组件文件。
    expect(RESULT.stats.namedRoutesMissingFile).toEqual([]);
    expect(RESULT.stats.unresolvedComponentSpecs).toEqual([]);
  });

  it('阳性对照：主体宇宙覆盖三种租户，且每个主体的权限模型确实是 hardcoded 矩阵', () => {
    const ids = H.principals.map((p) => p.id);
    expect(ids).toContain('platform_admin@-');
    for (const ft of FACTORY_TYPES) {
      expect(ids).toContain('factory_super_admin@' + ft);
      expect(ids).toContain('viewer@' + ft);
    }
    // 盲区 2 的显式登记：本闸量的是「按角色默认权限」，不是某个真实账号。
    for (const p of H.principals) {
      expect(p.perm.isDbLoaded).toBe(false);
      expect(p.perm.dbPermissions).toBeNull();
    }
  });

  it('阳性对照：Vue 模板 AST 的节点类型常量没有漂', () => {
    // `vue/compiler-sfc` 不导出 NodeTypes，分析器本地写死了数值。
    // Vue 哪天改了枚举，这里当场红，⛔ 不会静默扫出 0 条指令。
    const probe = parseView(path.join(SRC_DIR, 'views/error/403.vue'));
    expect(probe).not.toBeNull();
    const root = probe?.template as { type: number; children?: unknown[] } | null;
    expect(root?.type).toBe(VUE_NODE.ROOT);
    expect(Array.isArray(root?.children)).toBe(true);

    // 再对一段自造模板确认 DIRECTIVE / SIMPLE_EXPRESSION 两个值也没漂 ——
    // 这两个是分析器真正依赖的。
    const out = judgeSource(
      H,
      '<script setup lang="ts">' +
        "import { usePermissionStore } from '@/store/modules/permission';" +
        'const permissionStore = usePermissionStore();' +
        '</script>' +
        '<template><div v-if="permissionStore.canWrite(\'hr\')">x</div></template>',
      ['hr_admin@FACTORY'],
    );
    expect(out.map((o) => o.key)).toContain("perm.canWrite('hr')");
  });

  /**
   * 名字长得像谓词、但**不是权限谓词**的 store 成员 —— 显式登记 + 写理由。
   *
   * ⛔ 登记是留痕，不是豁免。加一条就要写清「它为什么不是权限判断」。
   * 这两个是 `loadFromDb` 的**加载状态标志**：它们描述的是「后端权限拿到没有」，
   * 不是「这个角色能不能做某件事」。本闸的模型里它们恒为 false（盲区 2）。
   */
  const NOT_A_PERMISSION_PREDICATE: Record<string, string> = {
    isDbLoaded: 'loadFromDb 的加载状态标志，不是权限判断',
    isUserModuleAccessLoaded: 'L4 用户级覆盖的加载状态标志，不是权限判断',
  };

  it('成员表完备性：permission store 上的 is*/can*/has* 成员必须全部登记', () => {
    // ⛔ 这一条守的是「哪些成员算权限谓词」这张表不漂（形态 D）。
    //    权限**语义**不复制（分析器调的是真 store），但**成员**是一张手写表，会漏。
    //    漏登记的谓词会被当成「读不懂」⇒ 用到它的分支全部掉进「判不了」，闸对那一块失明。
    const p = H.principals[0];
    expect(p).toBeDefined();
    const store = p?.perm as unknown as Record<string, unknown>;
    const registered = new Set([...Object.keys(BOOL_MEMBERS), ...Object.keys(STRING_MEMBERS)]);
    const missing = Object.keys(store)
      .filter((k) => /^(is|can|has)[A-Z]/.test(k))
      .filter((k) => {
        const v = store[k];
        return typeof v === 'function' || typeof v === 'boolean';
      })
      .filter((k) => !registered.has(k))
      .filter((k) => !Object.prototype.hasOwnProperty.call(NOT_A_PERMISSION_PREDICATE, k));
    if (missing.length > 0) {
      throw new Error(
        'permission store 新增了权限谓词但没登记进 BOOL_MEMBERS / STRING_MEMBERS：\n  ' +
          missing.join('\n  ') +
          '\n未登记的谓词会被当成「读不懂」⇒ 用到它的分支全部掉进「判不了」，闸对那一块失明。',
      );
    }
    expect(missing).toEqual([]);
  });

  // ==================== 阴性对照 ====================
  //
  // ⚠️ 四条全部走 `judgeSource` —— 与生产路径**同一条** `branchesIn` / `toExpr` /
  //    `judge`，只是把「可达主体集」直接给定，不经过路由与守卫。
  //    ⛔ 不是复制品：改坏 `toExpr` 或 `judge`，这四条会跟着红。

  const SFC = (script: string, template = '<div/>'): string =>
    '<script setup lang="ts">' +
    "import { computed } from 'vue';" +
    "import { useAuthStore } from '@/store/modules/auth';" +
    "import { usePermissionStore } from '@/store/modules/permission';" +
    'const authStore = useAuthStore();' +
    'const permissionStore = usePermissionStore();' +
    script +
    '</script><template>' + template + '</template>';

  it('阴性对照 ①：已知恒真 → 空转（举不出反例）', () => {
    // 只有这两个角色可达，而条件把它们都列上了 ⇒ 这道判断从不拒绝任何人。
    const out = judgeSource(
      H,
      SFC(
        "const isAdmin = computed(() => authStore.currentRole === 'platform_admin'" +
          " || authStore.currentRole === 'permission_admin');",
        '<div v-if="isAdmin">x</div>',
      ),
      ['platform_admin@-', 'permission_admin@FACTORY'],
    );
    const f = out.find((o) => o.key.includes('platform_admin'));
    expect(f).toBeDefined();
    expect(f?.verdict).toBe('VACUOUS');
    expect(f?.detail).toContain('恒为真');
  });

  it('阴性对照 ②：Kleene 让含运行时数据的条件也判得出恒假 → 空转', () => {
    // `row.locked` 是运行时数据（U）。`!isAdmin` 对两个可达主体恒假，
    // 而 `and(U, F) = F` ⇒ 整条恒假，**不需要知道 row.locked 是什么**。
    // ⚠️ 二值逻辑在这里只能报「判不了」，那样就抓不到这一整类。
    // ⚠️ 条件**内联写**，⛔ 不另外声明 `const isAdmin` ——
    //    否则那个 const 会成为同一条（同一个权限骨架）的另一个站点，
    //    判定就落到 `isAdmin` 自己身上（恒真），Kleene 那条路根本不会被走到。
    //    这正是「变异打在看不见的地方」的同族：对照要打在**被守的那条路径**上。
    const out = judgeSource(
      H,
      SFC(
        '',
        '<div v-if="row.locked && !(authStore.currentRole === \'platform_admin\'' +
          ' || authStore.currentRole === \'permission_admin\')">x</div>',
      ),
      ['platform_admin@-', 'permission_admin@FACTORY'],
    );
    expect(out).toHaveLength(1);
    const f = out[0];
    expect(f?.verdict).toBe('VACUOUS');
    expect(f?.detail).toContain('恒为假');
    // 它必须是**整条 Kleene 判出来的**，⛔ 不是退到权限骨架才判出来的
    // （骨架那条路的措辞是「权限部分 … 恒为」）。
    expect(f?.detail).not.toContain('权限部分');
  });

  it('阴性对照 ③：已知有效的分支不许被误报成空转', () => {
    // hr_admin 对 hr 是 read_write，viewer 是 read ⇒ 举得出反例。
    const out = judgeSource(
      H,
      SFC("const canWrite = computed(() => permissionStore.canWrite('hr'));", '<div v-if="canWrite">x</div>'),
      ['hr_admin@FACTORY', 'viewer@FACTORY'],
    );
    const f = out.find((o) => o.key === "perm.canWrite('hr')");
    expect(f).toBeDefined();
    expect(f?.verdict).toBe('EFFECTIVE');
    expect(f?.values['hr_admin@FACTORY']).toBe('T');
    expect(f?.values['viewer@FACTORY']).toBe('F');
  });

  it('动态配置对照：不硬编码默认角色的 L1/L2 模块仍有真/假两侧', () => {
    expect(RUNTIME_CONFIGURABLE_PERMISSION_MODULES)
      .toContain('production_report_correction');
    const out = judgeSource(
      H,
      SFC(
        "const canCorrect = computed(() => permissionStore.canWrite('production_report_correction'));",
        '<div v-if="canCorrect">x</div>',
      ),
      ['production_manager@FACTORY', 'operator@FACTORY'],
    );
    const finding = out.find((item) =>
      item.key === "perm.canWrite('production_report_correction')");
    expect(finding?.verdict).toBe('EFFECTIVE');
    expect(finding?.detail).toContain('L1/L2 运行时配置');
    expect(finding?.values['production_manager@FACTORY']).toBe('U');
    expect(finding?.values['operator@FACTORY']).toBe('U');
  });

  it('阴性对照 ④：判不了的必须单列，⛔ 不许折叠成「有效」或「空转」', () => {
    // 模块名是运行时变量 ⇒ 判不出来。它**被记了一条**（因为确实碰了权限成员），
    // 但落在「判不了」，⛔ 不许被当成没事、也⛔ 不许被算进空转预算。
    const out = judgeSource(
      H,
      SFC(
        'const mod = computed(() => route.params.m as string);' +
          'const ok = computed(() => permissionStore.canAccess(mod.value));',
        '<div v-if="ok">x</div>',
      ),
      ['hr_admin@FACTORY', 'viewer@FACTORY'],
    );
    expect(out.length).toBeGreaterThan(0);
    const f = out[0];
    expect(f?.verdict).toBe('UNDECIDED');
    expect(f?.values['hr_admin@FACTORY']).toBe('U');
    expect(f?.values['viewer@FACTORY']).toBe('U');
  });

  it('阴性对照 ⑤：权限骨架把「权限部分 + 运行时数据」判出来，且不吞掉反例', () => {
    // `canWrite && row.status === 'ACTIVE'` 整条是 U（因为 row.status 未知），
    // 退到权限骨架 `canWrite('hr')` 之后仍然举得出反例 ⇒ EFFECTIVE，⛔ 不是「判不了」。
    // 这一条守的是：骨架投影**不会把有效判成空转**。
    // 同 ②：内联写，让这一条**只有一个站点**，判定才会真的走到骨架那条路。
    const out = judgeSource(
      H,
      SFC('', '<div v-if="permissionStore.canWrite(\'hr\') && row.status === \'ACTIVE\'">x</div>'),
      ['hr_admin@FACTORY', 'viewer@FACTORY'],
    );
    expect(out).toHaveLength(1);
    const f = out[0];
    expect(f?.verdict).toBe('EFFECTIVE');
    expect(f?.detail).toContain('权限部分');
    // 骨架去重之后的 key 就是那个权限判断本身
    expect(f?.key).toBe("perm.canWrite('hr')");
  });

  it('阴性对照 ⑥：`ref(...)` 的初始值不是它的值 —— ⛔ 不许被当成常量', () => {
    // `const mode = ref('add')` 之后 `mode === 'edit'` **不是**恒假：ref 是可变状态。
    // 剥 `ref` 的那一版把它读成 `'add' === 'edit'`，一口气造出 9 条**恒假**的假阳性
    // ——恒假的假阳性最难看出来，它长得跟真的空转一模一样。
    const out = judgeSource(
      H,
      SFC("const mode = ref('add');", '<div v-if="mode === \'edit\' && permissionStore.canWrite(\'hr\')">x</div>'),
      ['hr_admin@FACTORY', 'viewer@FACTORY'],
    );
    const f = out.find((o) => o.key.includes("canWrite('hr')"));
    expect(f).toBeDefined();
    expect(f?.verdict).not.toBe('VACUOUS');
  });

  // ==================== 真实用例 ====================

  it('真实用例：AlertDashboard 的「餐饮租户」分支从不执行', () => {
    // `/analytics/alerts` 在路由上对 RESTAURANT 隐藏 ⇒ 走得到它的 13 个主体
    // 没有一个是餐饮租户，而页面里还写着 `factoryType === 'RESTAURANT'` 的分支。
    // 这是本闸的第一个真实用例，也是「必须把工厂类型算进主体」的证明：
    // 只按角色量，这一条根本判不出来。
    // ⚠️ 按**完整 key** 取，⛔ 不用 `includes('RESTAURANT')` ——
    //    同一个文件里还有一条 `(!auth.factoryType === 'RESTAURANT' && canWrite('equipment'))`，
    //    它按 key 排序排在前面且是 EFFECTIVE。第一版用 includes，`find` 拿到的是它，
    //    断言红在「EFFECTIVE ≠ VACUOUS」上，看起来像分析器错了 —— 是**选择器**错了。
    const f = RESULT.findings.find(
      (x) =>
        x.relFile.endsWith('analytics/AlertDashboard.vue') &&
        x.key === "auth.factoryType === 'RESTAURANT'",
    );
    expect(f).toBeDefined();
    expect(f?.verdict).toBe('VACUOUS');
    expect(f?.detail).toContain('恒为假');
    expect(f?.principals.every((p) => !p.endsWith('@RESTAURANT'))).toBe(true);
  });

  it('真实用例：路由 meta.module 已经拦过一次，页面里再判一次就是空转', () => {
    // `/finance/reports` 的 `meta.module = 'finance'` 让守卫先拦一道；
    // 页面里 `canAccess('finance')` 对**所有走得到的主体**恒为真。
    const f = RESULT.findings.find(
      (x) => x.relFile.endsWith('finance/reports/index.vue') && x.key === "perm.canAccess('finance')",
    );
    expect(f).toBeDefined();
    expect(f?.verdict).toBe('VACUOUS');
    expect(f?.detail).toContain('恒为真');
  });

  // ==================== 棘轮 ====================

  it(`空转的权限分支不超过 ${VACUOUS_BUDGET} 条（只许变少）`, () => {
    if (RESULT.vacuous.length > VACUOUS_BUDGET) {
      throw new Error(
        '空转的权限分支 ' + RESULT.vacuous.length + ' 条（预算 ' + VACUOUS_BUDGET + '）：\n\n' +
          RESULT.vacuous.map(render).join('\n\n') +
          '\n\n新增了一个「对能走到这里的所有主体恒真/恒假」的权限判断？\n' +
          '要么把它删掉（它守不住任何东西），要么让某个走得到的主体真的取到另一个值。\n' +
          '修好了几条？把 VACUOUS_BUDGET 调小。⛔ 不许调大。',
      );
    }
    expect(RESULT.vacuous.length).toBeLessThanOrEqual(VACUOUS_BUDGET);
  });

  it(`判不了的分支不超过 ${UNDECIDED_BUDGET} 条（防止「读不懂」被当成「修好了」）`, () => {
    if (RESULT.undecided.length > UNDECIDED_BUDGET) {
      throw new Error(
        '判不了的分支 ' + RESULT.undecided.length + ' 条（上限 ' + UNDECIDED_BUDGET + '）：\n\n' +
          RESULT.undecided.map(render).join('\n\n') +
          '\n\n多出来的通常是**分析器读不懂了**（块体函数里的早返回 / 外部 helper /\n' +
          '动态模块名 / 新的取角色写法），而不是代码变差了。\n' +
          '去 lib/branchReachability.ts 里补对应的解析，⛔ 不要直接抬上限。',
      );
    }
    expect(RESULT.undecided.length).toBeLessThanOrEqual(UNDECIDED_BUDGET);
  });

  it('清单可导出（BRANCH_REACH_REPORT=<path> 时落盘，供 PR / 人工复核）', () => {
    const out = process.env.BRANCH_REACH_REPORT;
    if (!out) {
      expect(RESULT.findings.length).toBeGreaterThan(0);
      return;
    }
    const section = (title: string, list: Finding[]): string =>
      '## ' + title + '（' + list.length + ' 条）\n\n' + list.map(render).join('\n\n');
    const body = [
      '# web-admin 分支可达性分析报告',
      '',
      '```json',
      JSON.stringify(RESULT.stats, null, 2),
      '```',
      '',
      section('空转 VACUOUS', RESULT.vacuous),
      '',
      section('判不了 UNDECIDED', RESULT.undecided),
      '',
      section('有效 EFFECTIVE', RESULT.effective),
      '',
    ].join('\n');
    fs.writeFileSync(out, body, 'utf8');
    expect(fs.existsSync(out)).toBe(true);
  });
});
