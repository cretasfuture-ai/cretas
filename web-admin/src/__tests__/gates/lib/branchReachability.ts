/**
 * 分支可达性分析器（web-admin / Vue）—— 二阶可达性。
 *
 * 屏级可达（`src/router/__tests__/routeReachability.spec.ts`，存量 91）问的是
 * 「有没有人走得到这个页面」。这里问下一层：
 * **走得到这个页面的那些角色，能不能让这个权限分支两边都取到值？**
 *
 * 举不出一个「走得到 + 条件为假」的角色 ⇒ 那个 `v-if="canWrite"` /
 * `:disabled="!canWrite"` 从来没有执行过另一侧 —— 页面可达，**分支的状态不可达**。
 *
 * ## 算的是这条链（不靠人追、不靠正则）
 *
 *   (角色, 工厂类型)  →  能进哪些路由      **直接驱动 `router/guards.ts` 的真守卫**
 *                     →  那些路由挂哪个视图  router 表里的 `component: () => import(...)`
 *                     →  视图里有哪些权限分支 `<script setup>` 的 if/三元/const
 *                                            + `<template>` 的 v-if/v-show/:prop
 *                     →  该主体的权限是什么   **调真的 pinia permission / auth store**
 *
 * ## ⛔ 权限语义不复制一份（与 RN 侧同一条纪律）
 *
 * 分析器**不重写** `PERMISSION_MATRIX`、不重写 `canAccess` 的兜底规则、
 * 也不重写 `guards.ts` 里那七道闸（MOBILE_ONLY_ROLES / ROLE_PATH_WHITELIST /
 * module / modulesAll / hideForFactoryTypes / restaurantRoles / roles）。
 *
 *  · **可达性**：把 `setupRouterGuards` 挂到一个**假 router** 上，截获它注册的
 *    `beforeEach`，然后拿合成主体逐条路由**真的跑一遍那个守卫**，看 `next()`
 *    是不是放行。守卫改了，本闸自动跟着改 —— 形态 D（两份必漂）在这里物理上不成立。
 *  · **权限**：每个主体一份**真的 pinia store**（`setRole` 之后调真的
 *    `canAccess` / `canWrite` / `canViewPrice`）。
 *
 * 代价是多了一张**成员表**（哪些 store 成员算权限谓词），那张表由
 * `branchReachability.spec.ts` 里一道完备性断言钉住：permission store 上任何
 * `is*` / `can*` / `has*` 成员没登记进来就红。
 *
 * ## Vue 侧与 RN 侧的机制差异（这一段是本文件存在的理由）
 *
 * | | RN | web-admin |
 * |---|---|---|
 * | 谁决定可达 | `AppNavigator` 的 `RoleBasedNavigator` 分流 + Stack 注册 | `router/guards.ts` 的 `beforeEach` + 路由 `meta` |
 * | 主体 | 角色 | **(角色, 工厂类型)** —— `hideForFactoryTypes` / `restaurantRoles` / `FACTORY_TYPE_MODULE_FILTER` 三处都按工厂类型分流，只按角色量会把三种租户的判定糊成一个 |
 * | 权限谓词的载体 | `utils/permissionHelper.ts` 的**纯函数** | **pinia store 的方法**（有状态：`setRole` 之后才有意义） |
 * | 分支长在哪 | 一个 `.tsx` 的 JSX | **两个语法体系**：`<script setup>`（TS）+ `<template>`（Vue 指令，表达式是字符串） |
 * | 判定用的树 | `ts.createSourceFile` | `vue/compiler-sfc` 拆块 →<br>script 走 `ts.createSourceFile`；template 的每条指令表达式**再喂给 TS 解析器** |
 *
 * ⚠️ 曾经的 `src/utils/permission.ts`（`hasModulePermission` / `isPlatformAdmin`，RN `permissionHelper`
 *    的对应物）**已于 2026-09-07 #690 删除**（清单收敛刀 A：第三份手写矩阵，唯一调用点改用 permission store）。
 *    本分析器不登记它们 —— 现在是因为它们不存在，不是因为「零 import」。
 *    ⛔ 别照这条注释去找那个文件。
 *
 * ## 三态，⛔ 不许二分
 *
 *   空转 (VACUOUS)     能走到这页的主体里，条件恒真或恒假 —— 举不出反例
 *   有效 (EFFECTIVE)   举得出反例：有主体为真、有主体为假
 *   判不了 (UNDECIDED)  长得像权限分支但分析器判不出（动态模块名 / 读不懂的别名 /
 *                      零主体可达）
 *
 * 「判不了」单列。把不确定折叠进「有效」会让闸看起来干净而实际失明。
 *
 * ## 判定分两级：先 Kleene，再「权限骨架」
 *
 * ① **Kleene 三值**。`disabled = row.locked && !canWrite` 里 `row.locked` 是运行时
 *    数据（U），但 `and(U, F) = F` —— 只要 `!canWrite` 恒假，整条恒假，
 *    **不需要知道 row.locked 是什么**。二值逻辑在这里只能报「判不了」。
 *
 * ② Kleene 之后仍有 U 的，退一步问「**它的权限部分**判得出来吗」：把表达式投影到
 *    它的角色可判定内容（`core()`：从 and/or 链里摘掉 unknown 操作数），再判一次。
 *    于是 `v-if="canWrite && row.status === 'ACTIVE'"` 会被判成
 *    「权限部分 `canWrite('equipment')` 恒真/恒假」而不是一律「判不了」。
 *
 *    ⚠️ 这一级**改变了 VACUOUS 的含义**：从「这个分支不会变」变成
 *    「**这个分支的权限内容**不会变」。对一道**权限**闸来说后者才是要问的问题，
 *    而且它是本闸能在 Vue 上长期活下去的原因 —— 不然
 *    `v-if="canX && row.y"` 这种再普通不过的新代码每写一条就把「判不了」顶上去一条，
 *    而那既不是缺陷也没法「补解析」修掉（形态 E：宽而被关掉的闸覆盖率归零）。
 *
 * ## 去重：按语义，⛔ 不按行
 *
 * `const canWrite = computed(() => permissionStore.canWrite('equipment'))` 与
 * 模板里三处 `v-if="canWrite && …"` 是**同一个问题**，算 **1 条**（见 `dedupeKey`：
 * 按权限骨架的规范式去重，并把顶层取反抹掉，于是 `canWrite` 与 `!canWrite` 同条）。
 * 本仓记过这条：按行数算会因为「同一个问题被拆成几处」而虚高。
 *
 * ## ⚠️ 已知盲区（写明，⛔ 不靠加启发式逼近）
 *
 *  1. **主体是笛卡尔积**：25 个工厂角色 × 3 种工厂类型，其中不少组合现实中不存在
 *     （`restaurant_chef` 开在 `FACTORY` 租户）。方向是**保守的**：主体多 ⇒ 更容易
 *     举出反例 ⇒ 只会把「空转」误判成「有效」，不会反过来。棘轮量的是空转数，宁可少报。
 *  2. **合成 user 不带后端权限**：显式跳过加载，`dbPermissions`
 *     为 null，走 hardcoded `PERMISSION_MATRIX`。这是「按角色默认权限」的模型，
 *     不是「按某个真实账号」。L2 工厂覆盖 / L4 用户覆盖不在视野内。
 *  3. **只看视图文件自己**。视图里渲染的子组件（`src/components/**`）里的权限分支
 *     不在本分析器视野内 —— 那是下一道闸的事。
 *  4. **`redirect` 路由没有视图文件**，链在这里断掉（本仓 309 条路由里 44 条如此）。
 *  5. **同名局部变量跨作用域**取文件里第一个声明。文件内重名会误解析。
 *  6. **动态 import 路径拼接**读不出来 ⇒ 那条链断掉，下游视图不计入。
 *  7. **`v-for` 里的作用域变量**一律当运行时数据（U）。这是对的：它们确实是数据。
 *  8. **`authStore.factoryId` 不是权限轴** —— 见下方 `DELIBERATELY_NOT_A_PERMISSION_MEMBER`。
 *     「按 factoryId 分支」这一类本闸看不见。
 *  9. **权限派生对象的字段读看不见**：`const p = computed(() => f(role)); v-if="p.canAdjust"`
 *     —— `p.canAdjust` 不算权限读（见 `mentionsCarrier` 里的说明）。
 *     那个 `const p = …` 本身会被记一条「判不了」，所以**不是完全无声**，
 *     但字段级的分支判不出来。
 * 10. **外部 helper 不跟进**：`canConfigureUnifiedOaForRole(currentRole)` 这类
 *     从别的模块 import 进来的纯函数，本分析器不追它的实现（RN 侧的 `resolveExport`
 *     没有移植过来）⇒ 落在「判不了」。这是当前 39 条判不了里最大的一族。
 * 11. **块体函数里有早返回的判不了**：`() => { if (canWrite) return true; … }`
 *     ——只有「唯一出口」的块体会被展开（见 `soleReturnOf`）。
 * 12. **`v-else` 不单独记**：它的条件是对应 `v-if` 的取反，而去重会把顶层取反抹掉，
 *     两者本来就是同一条。
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import ts from 'typescript';
import { createPinia, setActivePinia } from 'pinia';

/**
 * ⛔ 不要写成 `import { parse } from 'vue/compiler-sfc'`。
 *
 * 实测：ESM import 走 vite 的浏览器侧解析，`vue/compiler-sfc` 会被拖进转换管线，
 * **整个 spec 卡死**（两次 600s 超时，`RUN` 之后一行都没有，看起来像死循环而不是
 * 依赖问题）。`createRequire` 直接从 node 拿，同一个包 **3.4s 全程跑完**。
 * 这里唯一需要的是编译器的 `parse`，不需要它进 vite 的模块图。
 */
interface SfcBlock {
  content: string;
  lang?: string;
  loc: { start: { line: number } };
}
interface SfcDescriptor {
  script: SfcBlock | null;
  scriptSetup: SfcBlock | null;
  template: (SfcBlock & { ast?: unknown }) | null;
}
const nodeRequire = createRequire(import.meta.url);
const { parse: parseSFC } = nodeRequire('vue/compiler-sfc') as {
  parse: (src: string, opts: { filename: string }) => { descriptor: SfcDescriptor };
};

// ==================== 目录常量 ====================

export const SRC_DIR = path.resolve(__dirname, '../../..');
const ROUTER_DIR = path.join(SRC_DIR, 'router');
const VIEWS_DIR = path.join(SRC_DIR, 'views');

// ==================== Vue 模板 AST 的节点类型 ====================

/**
 * `vue/compiler-sfc` 不导出 `NodeTypes`，`@vue/compiler-dom` 只是 `vue` 的传递依赖。
 * ⇒ 本地写死数值，并由 `spec` 里的阳性对照**对着一次真解析核对**：
 *   Vue 哪天改了枚举值，闸会当场红，⛔ 不会静默扫出 0 条指令。
 */
export const VUE_NODE = {
  ROOT: 0,
  ELEMENT: 1,
  TEXT: 2,
  COMMENT: 3,
  SIMPLE_EXPRESSION: 4,
  INTERPOLATION: 5,
  ATTRIBUTE: 6,
  DIRECTIVE: 7,
  COMPOUND_EXPRESSION: 8,
} as const;

// ==================== 主体（角色 × 工厂类型） ====================

/** 工厂角色可能落在哪几种租户里。空串是平台用户（`authStore.factoryType` 对平台用户返回 ''）。 */
export const FACTORY_TYPES = ['FACTORY', 'RESTAURANT', 'LOGISTICS'] as const;

export const PROBE_FACTORY_ID = 'PROBE_FACTORY';

export interface Principal {
  id: string;
  role: string;
  factoryType: string;
  isPlatform: boolean;
  /** 真的 pinia store 实例（每个主体一份，互不干扰） */
  auth: PiniaAuthStore;
  perm: PiniaPermissionStore;
  pinia: ReturnType<typeof createPinia>;
}

interface PiniaAuthStore {
  user: unknown;
  currentRole: string;
  factoryId: string;
  factoryType: string;
  businessDomain: string;
  isAuthenticated: boolean;
  [k: string]: unknown;
}

interface PiniaPermissionStore {
  currentRole: string;
  isDbLoaded: boolean;
  dbPermissions: unknown;
  canAccess: (m: string) => boolean;
  canWrite: (m: string) => boolean;
  hasFullAccess: (m: string) => boolean;
  canAccessSmartBI: () => boolean;
  canWriteSmartBI: () => boolean;
  hasAnyWriteAccess: () => boolean;
  canViewPrice: boolean;
  getPermissionLevel: (m: string) => string;
  setRole: (role: string, factoryId?: string, factoryType?: string, userId?: string | number, options?: { skipDbLoad?: boolean }) => void;
  [k: string]: unknown;
}

function syntheticUser(role: string, factoryType: string, isPlatform: boolean): unknown {
  const base = {
    id: 1,
    username: 'probe_' + role,
    email: 'probe@example.com',
    role,
    roleCode: role,
    isActive: true,
  };
  if (isPlatform) {
    return { ...base, userType: 'platform', platformUser: { role, permissions: [] } };
  }
  return {
    ...base,
    userType: 'factory',
    factoryUser: {
      role,
      factoryId: PROBE_FACTORY_ID,
      factoryType,
      permissions: [],
    },
  };
}

// ==================== 权限谓词成员表 ====================

/**
 * store 成员名 → **怎么在真 store 上调它**。⛔ 这里只登记「谁是权限谓词」，
 * 不复制任何权限语义。
 *
 * `kind` 说明它长什么样：
 *   'method0' 无参方法   'method1' 单个字面量模块名参数   'prop' 布尔属性(computed)
 */
export interface BoolMember {
  store: 'perm' | 'auth';
  kind: 'method0' | 'method1' | 'prop';
}

export const BOOL_MEMBERS: Record<string, BoolMember> = {
  canAccess: { store: 'perm', kind: 'method1' },
  canWrite: { store: 'perm', kind: 'method1' },
  hasFullAccess: { store: 'perm', kind: 'method1' },
  canAccessSmartBI: { store: 'perm', kind: 'method0' },
  canWriteSmartBI: { store: 'perm', kind: 'method0' },
  hasAnyWriteAccess: { store: 'perm', kind: 'method0' },
  canViewPrice: { store: 'perm', kind: 'prop' },
  isAuthenticated: { store: 'auth', kind: 'prop' },
};

/**
 * 这些模块故意不进入前端 hardcoded 角色矩阵，只能由后端 L1/L2 权限配置赋值。
 * 对它们用离线默认矩阵只能得到恒假；分析时必须保留“未配置/已配置”这条运行时轴。
 */
export const RUNTIME_CONFIGURABLE_PERMISSION_MODULES = new Set([
  'production_report_correction',
]);

/** store 成员名 → 读一个字符串。用于 `xxx === 'literal'` 这类比较。 */
export interface StringMember {
  store: 'perm' | 'auth';
  kind: 'method1' | 'prop';
}

export const STRING_MEMBERS: Record<string, StringMember> = {
  currentRole: { store: 'auth', kind: 'prop' },
  factoryType: { store: 'auth', kind: 'prop' },
  businessDomain: { store: 'auth', kind: 'prop' },
  getPermissionLevel: { store: 'perm', kind: 'method1' },
};

/**
 * ⛔ `authStore.factoryId` **故意不登记**（盲区 8）。
 *
 * 它是**租户 id**，不是权限轴：全仓 287 处引用绝大多数是「拼 API 路径 / 传查询参数」，
 * 而本分析器的所有主体共用同一个 `PROBE_FACTORY_ID`
 * ⇒ 任何 `authStore.factoryId === 'XXX'` 在这里必然恒假，
 *   那是**仪器造出来的**恒假，不是代码里的空转。
 *
 * 实测（登记它的那一版）：`?{factoryId.value}` 一条就贡献了 162 条「判不了」，
 * 连带把 `const res = await get(…factoryId…)` 传播成「权限载体」，
 * 又带出 ~300 条 `if (res.success)`。1545 条里 1333 条判不了（86%）。
 * ⇒ 「按 factoryId 分支」这一类本闸看不见，写在这里，⛔ 不靠加启发式补。
 */
export const DELIBERATELY_NOT_A_PERMISSION_MEMBER = ['factoryId', 'factoryName'] as const;

/**
 * `authStore.user` 下面**带角色语义**的字段路径。
 *
 * ⛔ 只登记这几条，⛔ 不是「`user.` 开头都算」——`user.username` / `user.email`
 *    不是权限读，登记了只会造噪声。
 *
 * 这一条也是跨仪器对账翻出来的：`calibration/CalibrationListView.vue` 写的是
 * `authStore.user?.role === 'platform_admin'`（⛔ 不是 `authStore.currentRole`），
 * 而 `user` 当时在「故意不登记」名单里 ⇒ 那个文件**一条读不出来**。
 */
export const USER_ROLE_FIELDS: Record<string, (u: UserLike) => string | undefined> = {
  'user.role': (u) => u?.role,
  'user.roleCode': (u) => u?.roleCode,
  'user.userType': (u) => u?.userType,
  'user.factoryUser.role': (u) => u?.factoryUser?.role,
  'user.factoryUser.factoryType': (u) => u?.factoryUser?.factoryType,
  'user.platformUser.role': (u) => u?.platformUser?.role,
};

interface UserLike {
  role?: string;
  roleCode?: string;
  userType?: string;
  factoryUser?: { role?: string; factoryType?: string };
  platformUser?: { role?: string };
}

/**
 * ⛔ 字面量对象的 `obj[key]` 会**走原型链**：`BOOL_MEMBERS['toString']` 会返回
 * `Object.prototype.toString`，于是 `form.qty?.toString()` 被当成权限谓词。
 * RN 侧因为这个一口气造出过 20 条假阳性。⇒ 一切按名字查表一律走这个自有属性检查。
 */
function pick<T>(table: Record<string, T>, key: string | null | undefined): T | undefined {
  if (!key) return undefined;
  return Object.prototype.hasOwnProperty.call(table, key) ? table[key] : undefined;
}

// ==================== 三值表达式 IR ====================

export type Tri = 'T' | 'F' | 'U';

export type Expr =
  | { k: 'atom'; canon: string; run: (p: Principal) => boolean }
  | { k: 'runtime-configured'; canon: string }
  | { k: 'const'; value: boolean }
  | { k: 'not'; a: Expr }
  | { k: 'and'; a: Expr; b: Expr }
  | { k: 'or'; a: Expr; b: Expr }
  /** 三元当值用。⛔ 不展开成 or(and(c,a), and(!c,b)) —— 展开后 core() 会把它折成恒真式。 */
  | { k: 'ite'; c: Expr; a: Expr; b: Expr }
  | { k: 'unknown'; canon: string };

export function canon(e: Expr): string {
  switch (e.k) {
    case 'atom':
      return e.canon;
    case 'runtime-configured':
      return e.canon;
    case 'const':
      return String(e.value);
    case 'not':
      return '!' + canon(e.a);
    case 'and':
      return '(' + canon(e.a) + ' && ' + canon(e.b) + ')';
    case 'or':
      return '(' + canon(e.a) + ' || ' + canon(e.b) + ')';
    case 'ite':
      return '(' + canon(e.c) + ' ? ' + canon(e.a) + ' : ' + canon(e.b) + ')';
    case 'unknown':
      return '?{' + e.canon + '}';
  }
}

/** Kleene 三值。`and(U, F) = F` 是本分析器能对含运行时数据的条件出结论的原因。 */
export function evalExpr(e: Expr, p: Principal): Tri {
  switch (e.k) {
    case 'atom':
      try {
        return e.run(p) ? 'T' : 'F';
      } catch {
        return 'U';
      }
    case 'runtime-configured':
      return 'U';
    case 'const':
      return e.value ? 'T' : 'F';
    case 'unknown':
      return 'U';
    case 'not': {
      const v = evalExpr(e.a, p);
      return v === 'U' ? 'U' : v === 'T' ? 'F' : 'T';
    }
    case 'and': {
      const l = evalExpr(e.a, p);
      const r = evalExpr(e.b, p);
      if (l === 'F' || r === 'F') return 'F';
      if (l === 'U' || r === 'U') return 'U';
      return 'T';
    }
    case 'or': {
      const l = evalExpr(e.a, p);
      const r = evalExpr(e.b, p);
      if (l === 'T' || r === 'T') return 'T';
      if (l === 'U' || r === 'U') return 'U';
      return 'F';
    }
    case 'ite': {
      const c = evalExpr(e.c, p);
      if (c === 'T') return evalExpr(e.a, p);
      if (c === 'F') return evalExpr(e.b, p);
      const a = evalExpr(e.a, p);
      const b = evalExpr(e.b, p);
      return a === b ? a : 'U';
    }
  }
}

/**
 * 权限骨架：把表达式投影到它**角色可判定**的那部分。
 * 从 and / or 链里摘掉 unknown 操作数；整棵树都没有可判定内容时返回 null。
 *
 * ⚠️ `or(isAdmin, ?{flag})` 的骨架是 `isAdmin`。这在**布尔值**上不等价
 *    （`or(F,U)=U` 而骨架给 F），但本闸问的不是「这个分支会不会变」，
 *    是「**这个分支里的权限判断**会不会变」—— 后者才是一道权限闸该问的。
 */
export function core(e: Expr): Expr | null {
  switch (e.k) {
    case 'atom':
      return e;
    case 'runtime-configured':
      return e;
    case 'const':
      return e;
    case 'unknown':
      return null;
    case 'not': {
      const a = core(e.a);
      return a ? { k: 'not', a } : null;
    }
    case 'and':
    case 'or': {
      const a = core(e.a);
      const b = core(e.b);
      if (a && b) return { k: e.k, a, b };
      return a ?? b ?? null;
    }
    case 'ite': {
      const a = core(e.a);
      const b = core(e.b);
      // 两个分支都没有权限内容 ⇒ 这个三元**作为值**没有权限内容。
      // （它的**条件**已经作为独立的分支点单独记过一次了。）
      if (!a && !b) return null;
      const c = core(e.c);
      if (!c) return null;
      return { k: 'ite', c, a: a ?? { k: 'const', value: true }, b: b ?? { k: 'const', value: false } };
    }
  }
}

/*
 * ⛔ 这里**没有** RN 侧那个 `hasRoleAtom(expr)`。
 *
 * RN 用它当收录规则：「解析出可判定的角色叶子才记一条」。
 * 本闸的收录规则换成了 `mentionsCarrier`（提到权限载体就记，判不出来就进「判不了」），
 * 于是 `hasRoleAtom` 变成一个只调用自己、没有任何外部调用点的函数。
 * 留着它是「看起来在守什么、其实是死代码」—— 本仓记过这个形状，所以删掉。
 */

// ==================== 文件解析（SFC + TS） ====================

export interface ViewSource {
  file: string;
  /** `<script setup>` / `<script>` 合并后的 TS AST（可能为 null） */
  script: ts.SourceFile | null;
  /** script 块在 .vue 文件里的起始行（用于把行号换算回 .vue 的真实行） */
  scriptStartLine: number;
  /** `<template>` 的 Vue AST（可能为 null） */
  template: unknown;
  templateStartLine: number;
}

const viewCache = new Map<string, ViewSource | null>();

export function parseView(file: string): ViewSource | null {
  if (viewCache.has(file)) return viewCache.get(file) ?? null;
  let text: string;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    viewCache.set(file, null);
    return null;
  }

  let out: ViewSource;
  if (file.endsWith('.vue')) {
    const { descriptor } = parseSFC(text, { filename: file });
    const block = descriptor.scriptSetup ?? descriptor.script;
    out = {
      file,
      script: block
        ? ts.createSourceFile(file + '.ts', block.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
        : null,
      scriptStartLine: block ? block.loc.start.line : 1,
      template: descriptor.template?.ast ?? null,
      templateStartLine: descriptor.template ? descriptor.template.loc.start.line : 1,
    };
  } else {
    out = {
      file,
      script: ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX),
      scriptStartLine: 1,
      template: null,
      templateStartLine: 1,
    };
  }
  viewCache.set(file, out);
  return out;
}

function lineOfNode(node: ts.Node, base: number): number {
  const sf = node.getSourceFile();
  return sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + base;
}

// ==================== 文件内绑定环境 ====================

type Binding =
  | { kind: 'permStore' }
  | { kind: 'authStore' }
  | { kind: 'expr'; node: ts.Expression };

export interface FileEnv {
  file: string;
  bindings: Map<string, Binding>;
  /**
   * 名字 → 它（传递地）指向权限载体吗。
   *
   * ⛔ 这一张表是**先算好的不动点**，不是在遍历时现场递归展开别名。
   * 实测：现场递归会指数爆炸 —— 每访问一个节点就把别名体整棵再走一遍，
   * 而别名体里还有别名。第一版就是这么写的，250 个视图文件跑了 10 分钟没跑完，
   * 而且**症状是「卡住」不是「报错」**（硬约束 1 的那种：看起来像死循环）。
   */
  carriers: Set<string>;
}

const envCache = new Map<string, FileEnv>();

function unwrap(node: ts.Node): ts.Node {
  let n = node;
  for (;;) {
    if (ts.isParenthesizedExpression(n) || ts.isNonNullExpression(n)) {
      n = n.expression;
      continue;
    }
    if (ts.isAsExpression(n) || ts.isSatisfiesExpression(n)) {
      n = n.expression;
      continue;
    }
    return n;
  }
}

function calleeName(call: ts.CallExpression): string | null {
  const c = unwrap(call.expression);
  if (ts.isIdentifier(c)) return c.text;
  if (ts.isPropertyAccessExpression(c)) return c.name.text;
  return null;
}

/**
 * `computed(() => X)` / `computed((): T => X)` / `() => X` 一律剥到 X。
 *
 * ⛔ **`ref(...)` / `reactive(...)` 不剥** —— 那是**可变状态**，它的初始值不是它的值。
 *
 * 实测（剥了 `ref` 的那一版）：`const dialogMode = ref('add')` 被当成常量 `'add'`，
 * 于是 `v-if="dialogMode === 'edit' && canWrite"` 变成 `('add' === 'edit' && …)` ——
 * 一条**恒假**的假阳性。同一个成因造出 9 条假「空转」
 * （hr 的四个列表页、`ar-ap` 的 `activeTab`、两块 `ref(false)` 的开关…），
 * 占当时 27 条空转的三分之一。**恒假的假阳性最难看出来**：它长得跟真的空转一模一样。
 */
function unwrapThunk(node: ts.Expression, depth = 0): ts.Expression {
  if (depth > 6) return node;
  const n = unwrap(node) as ts.Expression;
  if (ts.isCallExpression(n)) {
    const name = calleeName(n);
    if (name === 'computed' && n.arguments.length >= 1) {
      const arg = n.arguments[0];
      if (arg) return unwrapThunk(arg as ts.Expression, depth + 1);
    }
    return n;
  }
  if (ts.isArrowFunction(n) || ts.isFunctionExpression(n)) {
    if (!ts.isBlock(n.body)) return unwrapThunk(n.body as ts.Expression, depth + 1);
    const ret = soleReturnOf(n.body);
    if (ret) return unwrapThunk(ret, depth + 1);
  }
  return n;
}

/**
 * 块体里**只有一条出口**时取那条 `return` 的表达式。
 *
 * 条件严格：`return` 必须是最后一条，且它前面**只能是变量声明**。
 * 有 `if` 提前 return 的（`if (canWrite) return true; …`）一律不剥 ——
 * 那种控制流本分析器判不了，剥了会得出一个错的结论，⛔ 宁可让它进「判不了」。
 *
 * ⚠️ 前面那些 `const` 不会丢：`fileEnv` 是**全文件**遍历收集变量声明的，
 *    嵌套作用域里的 `const r = currentRole.value` 同样在环境里。
 *
 * 实测（不剥的那一版）：`workflow/admin-running.vue` 的
 * `const isAdminEligible = computed(() => { const r = currentRole.value;
 *  return r === 'factory_super_admin' || r === 'platform_admin'; })`
 * 整个文件**一条读不出来**（4 处使用点全部看不见）。
 * 这是跨仪器对账（grep 说有、分析器说没有）翻出来的三处解析缺陷之一。
 */
function soleReturnOf(block: ts.Block): ts.Expression | null {
  const stmts = block.statements;
  if (stmts.length === 0) return null;
  const last = stmts[stmts.length - 1];
  if (!last || !ts.isReturnStatement(last) || !last.expression) return null;
  for (let i = 0; i < stmts.length - 1; i += 1) {
    if (!ts.isVariableStatement(stmts[i] as ts.Statement)) return null;
  }
  return last.expression;
}

export function fileEnv(file: string): FileEnv {
  const cached = envCache.get(file);
  if (cached) return cached;
  const bindings = new Map<string, Binding>();
  const view = parseView(file);
  if (view?.script) {
    const visit = (n: ts.Node): void => {
      if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name) && n.initializer) {
        const name = n.name.text;
        if (!bindings.has(name)) {
          const init = unwrap(n.initializer);
          if (ts.isCallExpression(init)) {
            const callee = calleeName(init);
            if (callee === 'usePermissionStore') {
              bindings.set(name, { kind: 'permStore' });
            } else if (callee === 'useAuthStore') {
              bindings.set(name, { kind: 'authStore' });
            } else {
              bindings.set(name, { kind: 'expr', node: unwrapThunk(n.initializer) });
            }
          } else {
            bindings.set(name, { kind: 'expr', node: unwrapThunk(n.initializer) });
          }
        }
      }
      ts.forEachChild(n, visit);
    };
    visit(view.script);
  }
  const env: FileEnv = { file, bindings, carriers: new Set<string>() };
  computeCarriers(env);
  envCache.set(file, env);
  return env;
}

/** 一个名字**直接**碰到权限载体吗（store 成员访问 / store 方法调用）。 */
function touchesStoreMember(node: ts.Node, env: FileEnv): boolean {
  const segs = pathSegments(node);
  if (segs && segs.length >= 1) {
    const head = segs[0] as string;
    const b = env.bindings.get(head);
    if ((b?.kind === 'permStore' || b?.kind === 'authStore') && !isShadowed(node, head)) {
      const rest = stripValue(segs).slice(1);
      // ⛔ 这里**没有** `rest.length === 0 -> true` 这一条。
      //    第一版有，理由是「bare `authStore` 当值用也算碰了 store」——
      //    但 `authStore.factoryId` 这棵树里**必然**含一个裸的 `authStore` 标识符子节点，
      //    而遍历会走到它 ⇒ 任何 store 属性访问都被判成「碰了权限成员」。
      //    实测：`?{authStore.factoryId}` 一族 220 条假的「判不了」全部由这一条产生。
      if (rest.length === 1 && (pick(BOOL_MEMBERS, rest[0]) || pick(STRING_MEMBERS, rest[0]))) return true;
      if (b.kind === 'authStore' && pick(USER_ROLE_FIELDS, rest.join('.'))) return true;
    }
    return false;
  }
  if (ts.isCallExpression(node)) {
    const target = unwrap(node.expression);
    if (ts.isPropertyAccessExpression(target)) {
      const recv = pathSegments(target.expression);
      const head = recv && recv.length === 1 ? (recv[0] as string) : null;
      if (!head || isShadowed(node, head)) return false;
      const b = env.bindings.get(head);
      return (
        (b?.kind === 'permStore' || b?.kind === 'authStore') &&
        (pick(BOOL_MEMBERS, target.name.text) !== undefined ||
          pick(STRING_MEMBERS, target.name.text) !== undefined)
      );
    }
  }
  return false;
}

/** 把「哪些名字（传递地）指向权限载体」一次算成不动点。 */
function computeCarriers(env: FileEnv): void {
  /** name -> 它体内引用到的其它顶层名字 */
  const refs = new Map<string, Set<string>>();
  for (const [name, b] of env.bindings) {
    if (b.kind !== 'expr') continue;
    const referenced = new Set<string>();
    let direct = false;
    const visit = (n: ts.Node): void => {
      if (touchesStoreMember(n, env)) {
        direct = true;
        return; // 已经确定，⛔ 不必再往下走
      }
      const segs = pathSegments(n);
      if (segs && segs.length >= 1) {
        const head = segs[0] as string;
        if (env.bindings.get(head)?.kind === 'expr' && !isShadowed(n, head)) referenced.add(head);
      }
      ts.forEachChild(n, visit);
    };
    visit(b.node);
    if (direct) env.carriers.add(name);
    refs.set(name, referenced);
  }
  // 传播：`const canEdit = canWrite.value && x`，`canWrite` 是载体 ⇒ `canEdit` 也算。
  //
  // ⛔ 传播**只走布尔形态**的别名。顺着任意值流传播试过一次：
  //    `const res = await getList({ factoryId })` 让 `res` 成了载体，
  //    于是 `if (res.success)` 被记成权限分支，一口气造出 ~300 条假的「判不了」。
  for (let i = 0; i < env.bindings.size + 1; i += 1) {
    let grew = false;
    for (const [name, referenced] of refs) {
      if (env.carriers.has(name)) continue;
      const b = env.bindings.get(name);
      // 布尔形态（`const canEdit = canWrite.value && x`）
      // 或**纯别名**（`const r = currentRole.value` —— 角色值的另一个名字）才传播。
      if (b?.kind !== 'expr') continue;
      if (!isBooleanShaped(b.node, env) && stringReader(b.node, env, 0) === null) continue;
      for (const r of referenced) {
        if (env.carriers.has(r)) {
          env.carriers.add(name);
          grew = true;
          break;
        }
      }
    }
    if (!grew) break;
  }
}

/** 标识符在这一点上是不是被局部形参遮蔽了（`v-for` 的 item、回调参数……）。 */
function isShadowed(node: ts.Node, name: string): boolean {
  let cur: ts.Node | undefined = node;
  while (cur) {
    if (ts.isArrowFunction(cur) || ts.isFunctionExpression(cur) || ts.isFunctionDeclaration(cur)) {
      for (const p of cur.parameters) {
        if (bindingBinds(p.name, name)) return true;
      }
    }
    cur = cur.parent;
  }
  return false;
}

function bindingBinds(nameNode: ts.BindingName, name: string): boolean {
  if (ts.isIdentifier(nameNode)) return nameNode.text === name;
  if (ts.isObjectBindingPattern(nameNode) || ts.isArrayBindingPattern(nameNode)) {
    for (const el of nameNode.elements) {
      if (ts.isBindingElement(el) && bindingBinds(el.name, name)) return true;
    }
  }
  return false;
}

// ==================== 路径归一化 ====================

/**
 * `a?.b!.c` → `['a','b','c']`；模板里的 `x.value` 与 script 里的 `x` 视作同一件事
 * （模板对 ref 自动解包）。
 */
function pathSegments(node: ts.Node): string[] | null {
  const parts: string[] = [];
  let cur: ts.Node = node;
  for (;;) {
    if (ts.isNonNullExpression(cur) || ts.isParenthesizedExpression(cur)) {
      cur = cur.expression;
      continue;
    }
    if (ts.isPropertyAccessExpression(cur)) {
      parts.unshift(cur.name.text);
      cur = cur.expression;
      continue;
    }
    if (ts.isIdentifier(cur)) {
      parts.unshift(cur.text);
      return parts;
    }
    return null;
  }
}

/**
 * 模板里 ref 自动解包（`canWrite`），script 里要写 `.value`（`canWrite.value`）——
 * 同一件事，规范成同一条路径。
 *
 * ⚠️ **必须喂完整路径**。第一版调用点写的是 `stripValue(segs.slice(1))`，
 * 于是 `canWrite.value` 切完是 `['value']`，`i > 0` 不成立、`value` 没被剥掉，
 * `rest.length === 1` 却是 `'value'` 不在成员表里 ⇒ **整条判不了**。
 * 实测代价：`canViewPrice.value` / `canWrite.value` / `isRestaurant.value`
 * 一共 21 条本该判得出的分支掉进「判不了」，而它**不报错**。
 */
function stripValue(segs: string[]): string[] {
  return segs.filter((s, i) => !(s === 'value' && i > 0));
}

// ==================== 表达式：AST → IR ====================

const UNKNOWN_TEXT_LIMIT = 70;

function unknownOf(node: ts.Node): Expr {
  let text: string;
  try {
    text = node.getText().replace(/\s+/g, ' ').trim();
  } catch {
    text = '<expr>';
  }
  if (text.length > UNKNOWN_TEXT_LIMIT) text = text.slice(0, UNKNOWN_TEXT_LIMIT) + '…';
  return { k: 'unknown', canon: text };
}

function storeOf(p: Principal, which: 'perm' | 'auth'): PiniaPermissionStore | PiniaAuthStore {
  return which === 'perm' ? p.perm : p.auth;
}

/** 表达式 → 「读一个字符串」的函数。读不出来返回 null。 */
function stringReader(
  node: ts.Node,
  env: FileEnv,
  depth: number,
): { canon: string; run: (p: Principal) => string | undefined } | null {
  if (depth > 12) return null;
  const n = unwrap(node);

  if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) {
    const lit = n.text;
    return { canon: "'" + lit + "'", run: () => lit };
  }

  // store 上的属性：authStore.currentRole / authStore.factoryType …
  const segs = pathSegments(n);
  if (segs && segs.length >= 1) {
    const head = segs[0] as string;
    if (isShadowed(n, head)) return null;
    const b = env.bindings.get(head);
    const rest = stripValue(segs).slice(1);
    if (b?.kind === 'permStore' || b?.kind === 'authStore') {
      if (rest.length === 1) {
        const member = pick(STRING_MEMBERS, rest[0]);
        if (member && member.kind === 'prop') {
          const which = member.store;
          const key = rest[0] as string;
          return {
            canon: which + '.' + key,
            run: (p) => storeOf(p, which)[key] as string | undefined,
          };
        }
      }
      if (b.kind === 'authStore') {
        const joined = rest.join('.');
        const reader = pick(USER_ROLE_FIELDS, joined);
        if (reader) {
          return { canon: 'auth.' + joined, run: (p) => reader(p.auth.user as UserLike) };
        }
      }
      return null;
    }
    // 局部别名：const currentRole = computed(() => authStore.currentRole)
    if (b?.kind === 'expr' && rest.length === 0) {
      return stringReader(b.node, env, depth + 1);
    }
    return null;
  }

  // store 上的取值方法：permissionStore.getPermissionLevel('finance')
  if (ts.isCallExpression(n)) {
    const target = unwrap(n.expression);
    if (ts.isPropertyAccessExpression(target)) {
      const recv = pathSegments(target.expression);
      const head = recv && recv.length === 1 ? (recv[0] as string) : null;
      const b = head ? env.bindings.get(head) : undefined;
      if ((b?.kind === 'permStore' || b?.kind === 'authStore') && !isShadowed(n, head as string)) {
        const member = pick(STRING_MEMBERS, target.name.text);
        if (member && member.kind === 'method1' && n.arguments.length === 1) {
          const arg = literalArg(n.arguments[0] as ts.Expression, env);
          if (arg !== null) {
            const which = member.store;
            const key = target.name.text;
            return {
              canon: which + '.' + key + "('" + arg + "')",
              run: (p) => (storeOf(p, which)[key] as (m: string) => string)(arg),
            };
          }
        }
      }
    }
    return null;
  }

  // `a || 'viewer'` / `a ?? 'viewer'`
  if (ts.isBinaryExpression(n)) {
    const op = n.operatorToken.kind;
    const isNullish = op === ts.SyntaxKind.QuestionQuestionToken;
    if (op === ts.SyntaxKind.BarBarToken || isNullish) {
      const left = stringReader(n.left, env, depth + 1);
      const right = stringReader(n.right, env, depth + 1);
      if (left && right) {
        return {
          canon: '(' + left.canon + (isNullish ? ' ?? ' : ' || ') + right.canon + ')',
          run: (p) => {
            const v = left.run(p);
            if (isNullish) return v ?? right.run(p);
            return v ? v : right.run(p);
          },
        };
      }
    }
    return null;
  }

  return null;
}

/** 参数是字面量（或指向字面量的局部别名）时取出它的值。 */
function literalArg(node: ts.Expression, env: FileEnv, depth = 0): string | null {
  if (depth > 6) return null;
  const n = unwrap(node);
  if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) return n.text;
  const segs = pathSegments(n);
  if (segs && segs.length === 1) {
    const b = env.bindings.get(segs[0] as string);
    if (b?.kind === 'expr') return literalArg(b.node, env, depth + 1);
  }
  return null;
}

function arrayOfStringLiterals(node: ts.Node, env: FileEnv, depth = 0): string[] | null {
  if (depth > 6) return null;
  let n = unwrap(node);
  const segs = pathSegments(n);
  if (segs && segs.length === 1) {
    const b = env.bindings.get(segs[0] as string);
    if (b?.kind !== 'expr') return null;
    return arrayOfStringLiterals(b.node, env, depth + 1);
  }
  if (ts.isNewExpression(n) && n.arguments && n.arguments.length === 1) {
    const inner = n.arguments[0];
    if (inner) n = unwrap(inner);
  }
  if (!ts.isArrayLiteralExpression(n)) return null;
  const out: string[] = [];
  for (const el of n.elements) {
    const e = unwrap(el);
    if (ts.isStringLiteral(e) || ts.isNoSubstitutionTemplateLiteral(e)) out.push(e.text);
    else return null;
  }
  return out;
}

/** store 上的布尔成员 → atom。 */
function boolMemberAtom(node: ts.Node, env: FileEnv): Expr | null {
  const n = unwrap(node);

  // 属性形态：permissionStore.canViewPrice / authStore.isAuthenticated
  const segs = pathSegments(n);
  if (segs && segs.length >= 2) {
    const head = segs[0] as string;
    if (isShadowed(n, head)) return null;
    const b = env.bindings.get(head);
    const rest = stripValue(segs).slice(1);
    if ((b?.kind === 'permStore' || b?.kind === 'authStore') && rest.length === 1) {
      const member = pick(BOOL_MEMBERS, rest[0]);
      if (member && member.kind === 'prop') {
        const which = member.store;
        const key = rest[0] as string;
        return {
          k: 'atom',
          canon: which + '.' + key,
          run: (p) => !!storeOf(p, which)[key],
        };
      }
    }
    return null;
  }

  // 方法形态：permissionStore.canWrite('finance') / permissionStore.canAccessSmartBI()
  if (ts.isCallExpression(n)) {
    const target = unwrap(n.expression);
    if (!ts.isPropertyAccessExpression(target)) return null;
    const recv = pathSegments(target.expression);
    const head = recv && recv.length === 1 ? (recv[0] as string) : null;
    if (!head || isShadowed(n, head)) return null;
    const b = env.bindings.get(head);
    if (b?.kind !== 'permStore' && b?.kind !== 'authStore') return null;
    const name = target.name.text;
    const member = pick(BOOL_MEMBERS, name);
    if (!member) return null;
    const which = member.store;
    if (member.kind === 'method0' && n.arguments.length === 0) {
      return {
        k: 'atom',
        canon: which + '.' + name + '()',
        run: (p) => !!(storeOf(p, which)[name] as () => boolean)(),
      };
    }
    if (member.kind === 'method1' && n.arguments.length >= 1) {
      const arg = literalArg(n.arguments[0] as ts.Expression, env);
      if (arg === null) return null; // 动态模块名 —— 判不了，⛔ 不猜
      if (RUNTIME_CONFIGURABLE_PERMISSION_MODULES.has(arg)) {
        return {
          k: 'runtime-configured',
          canon: which + '.' + name + "('" + arg + "')",
        };
      }
      return {
        k: 'atom',
        canon: which + '.' + name + "('" + arg + "')",
        run: (p) => !!(storeOf(p, which)[name] as (m: string) => boolean)(arg),
      };
    }
  }
  return null;
}

/** 布尔表达式 AST → 三值 IR。读不懂的子树落成 `unknown`，⛔ 不猜。 */
export function toExpr(node: ts.Node, env: FileEnv, depth = 0): Expr {
  if (depth > 14) return unknownOf(node);
  const n = unwrap(node);

  if (n.kind === ts.SyntaxKind.TrueKeyword) return { k: 'const', value: true };
  if (n.kind === ts.SyntaxKind.FalseKeyword) return { k: 'const', value: false };

  if (ts.isPrefixUnaryExpression(n) && n.operator === ts.SyntaxKind.ExclamationToken) {
    return { k: 'not', a: toExpr(n.operand, env, depth + 1) };
  }

  const memberAtom = boolMemberAtom(n, env);
  if (memberAtom) return memberAtom;

  if (ts.isBinaryExpression(n)) {
    const op = n.operatorToken.kind;
    if (op === ts.SyntaxKind.AmpersandAmpersandToken) {
      return { k: 'and', a: toExpr(n.left, env, depth + 1), b: toExpr(n.right, env, depth + 1) };
    }
    if (op === ts.SyntaxKind.BarBarToken || op === ts.SyntaxKind.QuestionQuestionToken) {
      return { k: 'or', a: toExpr(n.left, env, depth + 1), b: toExpr(n.right, env, depth + 1) };
    }
    const eq = op === ts.SyntaxKind.EqualsEqualsEqualsToken || op === ts.SyntaxKind.EqualsEqualsToken;
    const ne =
      op === ts.SyntaxKind.ExclamationEqualsEqualsToken || op === ts.SyntaxKind.ExclamationEqualsToken;
    if (eq || ne) {
      const cmp = comparisonAtom(n.left, n.right, env, depth);
      if (cmp) return ne ? { k: 'not', a: cmp } : cmp;
      return unknownOf(n);
    }
    return unknownOf(n);
  }

  if (ts.isConditionalExpression(n)) {
    return {
      k: 'ite',
      c: toExpr(n.condition, env, depth + 1),
      a: toExpr(n.whenTrue, env, depth + 1),
      b: toExpr(n.whenFalse, env, depth + 1),
    };
  }

  if (ts.isCallExpression(n)) {
    const callee = calleeName(n);
    if (callee === 'Boolean' && n.arguments.length === 1) {
      const first = n.arguments[0];
      return first ? toExpr(first, env, depth + 1) : unknownOf(n);
    }
    // ['a','b'].includes(authStore.currentRole) / new Set([...]).has(role)
    const calleeNode = unwrap(n.expression);
    const isMembership = callee === 'includes' || callee === 'has';
    if (isMembership && n.arguments.length === 1 && ts.isPropertyAccessExpression(calleeNode)) {
      const arr = arrayOfStringLiterals(calleeNode.expression, env);
      const reader = stringReader(n.arguments[0] as ts.Expression, env, depth + 1);
      if (arr && reader) {
        return {
          k: 'atom',
          canon: '[' + arr.join('|') + '].' + String(callee) + '(' + reader.canon + ')',
          run: (p) => {
            const v = reader.run(p);
            return v !== undefined && arr.includes(v);
          },
        };
      }
    }
    return unknownOf(n);
  }

  // 标识符 / 属性链：展开局部别名
  const segs = pathSegments(n);
  if (segs) {
    const head = segs[0] as string;
    const rest = stripValue(segs).slice(1);
    if (!isShadowed(n, head)) {
      const b = env.bindings.get(head);
      if (b?.kind === 'expr' && rest.length === 0) return toExpr(b.node, env, depth + 1);
    }
    return unknownOf(n);
  }

  return unknownOf(n);
}

function comparisonAtom(left: ts.Node, right: ts.Node, env: FileEnv, depth: number): Expr | null {
  const l = unwrap(left);
  const r = unwrap(right);
  const lLit = ts.isStringLiteral(l) || ts.isNoSubstitutionTemplateLiteral(l);
  const rLit = ts.isStringLiteral(r) || ts.isNoSubstitutionTemplateLiteral(r);
  if (lLit === rLit) return null; // 两边都是字面量或两边都不是 —— 都判不了
  const litNode = (lLit ? l : r) as ts.StringLiteralLike;
  const other = lLit ? r : l;
  const lit = litNode.text;
  const reader = stringReader(other, env, depth + 1);
  if (!reader) return null;
  return {
    k: 'atom',
    canon: reader.canon + " === '" + lit + "'",
    run: (p) => reader.run(p) === lit,
  };
}

// ==================== 「这看着像不像一条权限分支」 ====================

const BOOL_SHAPE_OPS = new Set<ts.SyntaxKind>([
  ts.SyntaxKind.AmpersandAmpersandToken,
  ts.SyntaxKind.BarBarToken,
  ts.SyntaxKind.EqualsEqualsEqualsToken,
  ts.SyntaxKind.EqualsEqualsToken,
  ts.SyntaxKind.ExclamationEqualsEqualsToken,
  ts.SyntaxKind.ExclamationEqualsToken,
]);

/** 「长得像布尔条件」——⛔ 不是「类型是 boolean」，分析器没有类型检查器。 */
function isBooleanShaped(node: ts.Node, env: FileEnv, depth = 0): boolean {
  if (depth > 6) return false;
  const n = unwrap(node);
  if (n.kind === ts.SyntaxKind.TrueKeyword || n.kind === ts.SyntaxKind.FalseKeyword) return true;
  if (ts.isPrefixUnaryExpression(n) && n.operator === ts.SyntaxKind.ExclamationToken) return true;
  if (ts.isBinaryExpression(n) && BOOL_SHAPE_OPS.has(n.operatorToken.kind)) return true;
  if (boolMemberAtom(n, env)) return true;
  if (ts.isCallExpression(n)) {
    const c = calleeName(n);
    return c === 'Boolean' || c === 'includes' || c === 'has';
  }
  const segs = pathSegments(n);
  // ⚠️ 先剥 `.value` 再看长度。写成 `segs.length === 1` 时 `canWrite.value`（长度 2）
  //    走不到「展开局部别名」这一支，于是 script 里所有 `xxx.value` 形态的权限别名
  //    全部不算布尔形态 —— 与 `stripValue` 那处是同一个 off-by-one 的两副面孔。
  const flat = segs ? stripValue(segs) : null;
  if (flat && flat.length === 1) {
    const b = env.bindings.get(flat[0] as string);
    if (b?.kind === 'expr') return isBooleanShaped(b.node, env, depth + 1);
  }
  if (flat && flat.length >= 2) {
    const b = env.bindings.get(flat[0] as string);
    if ((b?.kind === 'permStore' || b?.kind === 'authStore') && flat.length === 2) {
      return pick(BOOL_MEMBERS, flat[1]) !== undefined;
    }
  }
  return false;
}

/**
 * 这个条件**提到了权限载体**吗 —— 提到了就要记一条，哪怕分析器判不出来。
 *
 * 🔴 这是本闸与 RN 侧最实质的一处不同。RN 的规则是「解析出可判定的角色叶子才记」，
 *    于是**读不懂的权限条件会被静默跳过** ——「没有」和「我没看到」混成一件事。
 *    这里改成「提到载体就记，判不出来就进『判不了』」，让闸对自己的盲区**可见**。
 */
function mentionsCarrier(node: ts.Node, env: FileEnv): boolean {
  let found = false;
  const visit = (n: ts.Node): void => {
    if (found) return;
    // 直接碰 store 的**权限成员**（含 `canAccess(item.module)` 这种参数动态、判不出来的）
    if (touchesStoreMember(n, env)) {
      found = true;
      return;
    }
    // 引用了一个（传递地）指向权限成员的顶层名字 —— 查**先算好的**表，⛔ 不现场展开
    //
    // ⚠️ 只认**裸名**（`canWrite` / `canWrite.value`），⛔ 不认 `kpiData.customerCount`
    //    这种「在权限派生对象上再取字段」。见盲区 9：那些字段是**数据**，不是权限读。
    //    实测：`Dashboard.vue` 里一个 `computed` 内部提到 `canViewPrice`，
    //    于是它返回的对象上 13 个字段读全被记成「判不了」—— 30 条里占 13 条，
    //    而它们既不是缺陷、也不是分析器瞎了。
    const segs = pathSegments(n);
    if (segs) {
      const flat = stripValue(segs);
      if (flat.length === 1 && env.carriers.has(flat[0] as string) && !isShadowed(n, flat[0] as string)) {
        found = true;
      }
      // ⛔ 到此为止，**不再往下走**。`a.b.c` 的接收者 `a.b` / `a` 是同一条链的一部分，
      //    不是独立的「读」。往下走会让 `kpiData.customerCount` 命中裸名 `kpiData`——
      //    与 `authStore.factoryId` 命中裸名 `authStore` 是同一个形状，第二次踩。
      return;
    }
    ts.forEachChild(n, visit);
  };
  visit(node);
  return found;
}

/**
 * 记不记这一条：**能解析出权限叶子**，或者**明明碰了权限成员却解析不出来**。
 *
 * 🔴 后半句是本闸与 RN 侧最实质的一处不同。RN 的规则是「解析出可判定的角色叶子才记」，
 *    于是**读不懂的权限条件被静默跳过** ——「没有」和「我没看到」混成一件事。
 *    这里让它进「判不了」，闸对自己的盲区**可见**。
 *
 * ⚠️ 但「碰了权限成员」必须是**直接**的，⛔ 不能顺着任意值流传播。
 *    第一版顺着传播：`const res = await getList({ factoryId: factoryId.value })`
 *    让 `res` 成了「载体」，于是 `if (res.success)` 被记成权限分支 ——
 *    一口气造出 ~300 条假的「判不了」（1545 条里 1333 条判不了，86%）。
 */
function shouldRecord(node: ts.Node, env: FileEnv): boolean {
  return mentionsCarrier(node, env);
}

// ==================== 分支采集 ====================

export interface BranchSite {
  line: number;
  kind: string;
  text: string;
}

export interface Branch {
  file: string;
  key: string;
  expr: Expr;
  sites: BranchSite[];
}

function snippet(text: string): string {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length > 120 ? t.slice(0, 120) + '…' : t;
}

/** 去掉顶层取反：`canWrite` 与 `!canWrite` 是同一条分支（同一个问题，不是两处）。 */
function stripNot(e: Expr): Expr {
  let cur = e;
  while (cur.k === 'not') cur = cur.a;
  return cur;
}

function dedupeKey(e: Expr): string {
  const c = core(e);
  return canon(stripNot(c ?? e));
}

/** 把模板里的一段表达式字符串解析成 TS 表达式节点。 */
export function parseTemplateExpression(code: string): ts.Expression | null {
  const wrapped = '(' + code + ')';
  const sf = ts.createSourceFile('__tmpl__.ts', wrapped, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const first = sf.statements[0];
  if (!first || !ts.isExpressionStatement(first)) return null;
  // 语法错误时 TS 也会给一棵树；这里只要拿得到表达式就行
  return unwrap(first.expression) as ts.Expression;
}

interface TemplateNode {
  type: number;
  props?: Array<{
    type: number;
    name?: string;
    arg?: { type: number; content?: string; isStatic?: boolean } | null;
    exp?: { type: number; content?: string; loc?: { start?: { line?: number } } } | null;
    loc?: { start?: { line?: number } };
  }>;
  children?: TemplateNode[];
  content?: { type: number; content?: string; loc?: { start?: { line?: number } } };
  loc?: { start?: { line?: number } };
}

/** 一个视图文件里所有**提到权限载体**的门控条件，按语义去重。 */
export function branchesIn(file: string): Branch[] {
  const view = parseView(file);
  if (!view) return [];
  const env = fileEnv(file);
  const byKey = new Map<string, Branch>();

  const record = (node: ts.Node, kind: string, line: number, text: string): void => {
    if (!shouldRecord(node, env)) return;
    const expr = toExpr(node, env);
    const key = dedupeKey(expr);
    const site: BranchSite = { line, kind, text: snippet(text) };
    const existing = byKey.get(key);
    if (existing) {
      if (!existing.sites.some((s) => s.line === site.line && s.kind === site.kind)) {
        existing.sites.push(site);
      }
      // 已有条目优先保留**有权限内容**的那棵树（core 非空）
      if (!core(existing.expr) && core(expr)) existing.expr = expr;
      return;
    }
    byKey.set(key, { file, key, expr, sites: [site] });
  };

  // ---------- <script setup> ----------
  if (view.script) {
    const base = view.scriptStartLine;
    const visit = (n: ts.Node): void => {
      if (ts.isIfStatement(n)) {
        record(n.expression, 'if', lineOfNode(n.expression, base), n.expression.getText());
      } else if (ts.isConditionalExpression(n)) {
        record(n.condition, 'ternary', lineOfNode(n.condition, base), n.condition.getText());
      } else if (ts.isVariableDeclaration(n) && n.initializer) {
        const body = unwrapThunk(n.initializer);
        // ① 布尔形态 —— 正常的权限判断
        // ② ⛔ 不是布尔形态，但**权限值流进了一个读不出来的东西**（外部 helper、
        //    块体函数…）：也记一条，让它进「判不了」。
        //    纯别名（`const currentRole = computed(() => authStore.currentRole)`）
        //    排除掉 —— 它本身没有可判的东西，记了只是噪声。
        //    这一条是跨仪器对账翻出来的：`restaurant/staffing/index.vue` 的
        //    `computed(() => staffingPerspective(permission.currentRole))` 原本
        //    整个文件零条目，闸对它**无声地**失明。
        //    ⚠️ ②只认「可能藏着判断的东西」：调用 / 块体函数。
        //    ⛔ 对象字面量、数组、模板串、三元一律排除 —— 它们是**数据**不是闸，
        //       而三元的**条件**已经由下面的 'ternary' 规则单独记过一次了。
        //       不排除的话：光是 `procurement/requisitions` 两页的文案三元
        //       （`factoryType === 'RESTAURANT' ? '报货单' : '请购单'`）就重复贡献 13 条，
        //       `{ canViewFinance: canViewPrice.value }` 这类图表参数再贡献 5 条。
        const couldHideADecision =
          ts.isCallExpression(body) || ts.isArrowFunction(body) || ts.isFunctionExpression(body);
        const isPlainAlias = stringReader(body, env, 0) !== null;
        if (
          isBooleanShaped(body, env) ||
          (couldHideADecision && !isPlainAlias && mentionsCarrier(body, env))
        ) {
          record(body, 'const', lineOfNode(n, base), n.getText());
        }
      } else if (ts.isReturnStatement(n) && n.expression) {
        if (isBooleanShaped(n.expression, env)) {
          record(n.expression, 'return', lineOfNode(n.expression, base), n.expression.getText());
        }
      } else if ((ts.isArrowFunction(n) || ts.isFunctionExpression(n)) && !ts.isBlock(n.body)) {
        if (isBooleanShaped(n.body, env)) {
          record(n.body, 'arrow', lineOfNode(n.body, base), n.body.getText());
        }
      }
      ts.forEachChild(n, visit);
    };
    visit(view.script);
  }

  // ---------- <template> ----------
  const root = view.template as TemplateNode | null;
  if (root) {
    const DIRECTIVE_ALWAYS = new Set(['if', 'else-if', 'show']);
    const walk = (node: TemplateNode): void => {
      if (node.props) {
        for (const prop of node.props) {
          if (prop.type !== VUE_NODE.DIRECTIVE) continue;
          const exp = prop.exp;
          if (!exp || exp.type !== VUE_NODE.SIMPLE_EXPRESSION || !exp.content) continue;
          const dirName = prop.name ?? '';
          const argName = prop.arg && prop.arg.isStatic ? String(prop.arg.content ?? '') : '?';
          const always = DIRECTIVE_ALWAYS.has(dirName);
          const isBind = dirName === 'bind';
          if (!always && !isBind) continue;
          const parsed = parseTemplateExpression(exp.content);
          if (!parsed) continue;
          if (!always && !isBooleanShaped(parsed, env)) continue;
          const line = exp.loc?.start?.line ?? prop.loc?.start?.line ?? node.loc?.start?.line ?? 0;
          const kind = always ? 'v-' + dirName : 'bind:' + argName;
          record(parsed, kind, line, exp.content);
        }
      }
      // {{ cond ? a : b }}
      if (node.type === VUE_NODE.INTERPOLATION && node.content && node.content.content) {
        const parsed = parseTemplateExpression(node.content.content);
        if (parsed && ts.isConditionalExpression(parsed)) {
          const line = node.content.loc?.start?.line ?? node.loc?.start?.line ?? 0;
          record(parsed.condition, 'interp', line, node.content.content);
        }
      }
      if (node.children) for (const c of node.children) walk(c);
    };
    walk(root);
  }

  return [...byKey.values()];
}

// ==================== 路由表 → 视图文件 ====================

export interface RouteEntry {
  name: string;
  path: string;
  meta: Record<string, unknown>;
  file: string | null;
  isRedirect: boolean;
}

function routerFiles(): string[] {
  const mods = path.join(ROUTER_DIR, 'modules');
  const list = [path.join(ROUTER_DIR, 'index.ts')];
  if (fs.existsSync(mods)) {
    for (const f of fs.readdirSync(mods)) {
      if (f.endsWith('.ts')) list.push(path.join(mods, f));
    }
  }
  return list;
}

function dynamicImportSpec(node: ts.Node): string | null {
  let found: string | null = null;
  const visit = (n: ts.Node): void => {
    if (found) return;
    if (ts.isCallExpression(n) && n.expression.kind === ts.SyntaxKind.ImportKeyword) {
      const a = n.arguments[0];
      if (a && ts.isStringLiteral(a)) found = a.text;
    }
    ts.forEachChild(n, visit);
  };
  visit(node);
  return found;
}

function resolveAlias(spec: string): string | null {
  if (!spec.startsWith('@/')) return null;
  const base = path.join(SRC_DIR, spec.slice(2));
  for (const cand of [base, base + '.vue', base + '.ts', base + '.tsx']) {
    if (fs.existsSync(cand) && fs.statSync(cand).isFile()) return cand;
  }
  for (const ext of ['.vue', '.ts']) {
    const idx = path.join(base, 'index' + ext);
    if (fs.existsSync(idx)) return idx;
  }
  return null;
}

/**
 * 路由 `name` → 视图文件。
 *
 * ⚠️ 只用 AST 找 `name` + `component`，**不**在这里重算父子路径拼接 ——
 *    完整路径与 meta 直接问**真的 router 实例**要（`router.getRoutes()`），
 *    两边按 `name` 对上。隔壁那道屏级闸的五个错读数（62/83/84/92/91）里有三个
 *    就出在手写的路径拼接与分隔符归一化上；这里从物理上不做那件事。
 */
export function routeNameToFile(): { map: Map<string, string>; unresolved: string[] } {
  const map = new Map<string, string>();
  const unresolved: string[] = [];
  for (const file of routerFiles()) {
    const sf = ts.createSourceFile(
      file,
      fs.readFileSync(file, 'utf8'),
      ts.ScriptTarget.Latest,
      true,
      ts.ScriptKind.TS,
    );
    const visit = (n: ts.Node): void => {
      if (ts.isObjectLiteralExpression(n)) {
        let name: string | null = null;
        let comp: ts.Node | null = null;
        for (const p of n.properties) {
          if (!ts.isPropertyAssignment(p)) continue;
          const key = ts.isIdentifier(p.name) || ts.isStringLiteral(p.name) ? p.name.text : null;
          if (key === 'name' && ts.isStringLiteral(p.initializer)) name = p.initializer.text;
          if (key === 'component') comp = p.initializer;
        }
        if (name && comp) {
          const spec = dynamicImportSpec(comp);
          const resolved = spec ? resolveAlias(spec) : null;
          if (resolved) map.set(name, resolved);
          else unresolved.push(name + ' -> ' + String(spec));
        }
      }
      ts.forEachChild(n, visit);
    };
    visit(sf);
  }
  return { map, unresolved };
}

// ==================== 守卫驱动 ====================

type GuardFn = (
  to: Record<string, unknown>,
  from: Record<string, unknown>,
  next: (arg?: unknown) => void,
) => unknown;

export interface Harness {
  principals: Principal[];
  routes: RouteEntry[];
  guard: GuardFn;
}

let harnessPromise: Promise<Harness> | null = null;

/**
 * 建起「真守卫 + 真 store」的台架。
 *
 * 显式跳过权限加载，隔离真实接口与网络失败的拒绝行为。
 *    模型状态保持 `isDbLoaded === false && dbPermissions === null`，
 *    `buildHarness` 会逐个主体断言这一点。
 */
export function buildHarness(): Promise<Harness> {
  if (harnessPromise) return harnessPromise;
  harnessPromise = (async (): Promise<Harness> => {
    const authTypes = await import('@/types/auth');
    const permMod = await import('@/store/modules/permission');
    const authMod = await import('@/store/modules/auth');
    const routerMod = await import('@/router');

    const platformRoles = Object.values(authTypes.PLATFORM_ROLES) as string[];
    const factoryRoles = Object.values(authTypes.FACTORY_ROLES) as string[];
    // 角色宇宙 = 两处**权威**的并集。任一处新增角色都会自动进来。
    const matrixRoles = Object.keys(permMod.PERMISSION_MATRIX);
    const allFactoryRoles = [...new Set([...factoryRoles, ...matrixRoles])]
      .filter((r) => !platformRoles.includes(r))
      .sort();

    const principals: Principal[] = [];
    const mk = (role: string, factoryType: string, isPlatform: boolean): void => {
      const pinia = createPinia();
      setActivePinia(pinia);
      const auth = authMod.useAuthStore() as unknown as PiniaAuthStore;
      const perm = permMod.usePermissionStore() as unknown as PiniaPermissionStore;
      auth.user = syntheticUser(role, factoryType, isPlatform);
      // This analyzer models local role defaults, not an authoritative API load.
      perm.setRole(role, isPlatform ? '' : PROBE_FACTORY_ID, factoryType, 1, { skipDbLoad: true });
      const setIdentity = perm.setRole.bind(perm);
      perm.setRole = (nextRole, factory, type, user) => setIdentity(nextRole, factory, type, user, { skipDbLoad: true });
      // Guards also request refresh. Keep this static-default model isolated there;
      // permission predicates and the production guard itself remain real.
      perm.loadFromDb = async () => undefined;
      principals.push({
        id: role + '@' + (factoryType || '-'),
        role,
        factoryType,
        isPlatform,
        auth,
        perm,
        pinia,
      });
    };

    for (const role of platformRoles) mk(role, '', true);
    for (const role of allFactoryRoles) {
      for (const ft of FACTORY_TYPES) mk(role, ft, false);
    }

    // 确认每个主体都停在「hardcoded 矩阵」这个明确隔离的模型上。
    await new Promise((r) => setTimeout(r, 0));
    for (const p of principals) {
      if (p.perm.isDbLoaded || p.perm.dbPermissions !== null) {
        throw new Error(
          '主体 ' + p.id + ' 的 permission store 拿到了 DB 权限 —— ' +
            '本分析器的模型是「按角色默认权限」(盲区 2)，读数口径变了。',
        );
      }
    }

    // 截获**生产的**守卫：把 setupRouterGuards 挂到一个假 router 上。
    let captured: GuardFn | null = null;
    routerMod.setupRouterGuards({
      beforeEach: (cb: GuardFn) => {
        captured = cb;
      },
    } as never);
    if (!captured) throw new Error('没有截获到 beforeEach —— setupRouterGuards 的形态变了');

    const { map: nameFile } = routeNameToFile();
    const routes: RouteEntry[] = routerMod.default.getRoutes().map((r) => {
      const name = typeof r.name === 'string' ? r.name : '';
      const rec = r as unknown as { redirect?: unknown; components?: unknown };
      return {
        name,
        path: r.path,
        meta: (r.meta ?? {}) as Record<string, unknown>,
        file: name ? nameFile.get(name) ?? null : null,
        isRedirect: rec.redirect !== undefined,
      };
    });

    return { principals, routes, guard: captured as GuardFn };
  })();
  return harnessPromise;
}

/** 跑一次**生产守卫**，回答「这个主体能不能进这条路由」。 */
export async function guardAllows(h: Harness, p: Principal, route: RouteEntry): Promise<boolean> {
  setActivePinia(p.pinia);
  // MOBILE_ONLY_ROLES 那一支会 clearAuth() —— 每次调用前把身份放回去，
  // 否则同一主体的后续路由会被「未登录」而不是「被闸拦下」解释。
  p.auth.user = syntheticUser(p.role, p.factoryType, p.isPlatform);
  let arg: unknown = '__NOT_CALLED__';
  await h.guard(
    { path: route.path, fullPath: route.path, meta: route.meta, query: {}, params: {}, name: route.name },
    { path: '/', fullPath: '/', meta: {}, query: {}, params: {} },
    (x?: unknown) => {
      if (arg === '__NOT_CALLED__') arg = x;
    },
  );
  return arg === undefined;
}

// ==================== 判定 ====================

export type Verdict = 'VACUOUS' | 'EFFECTIVE' | 'UNDECIDED';

export interface Judgement {
  verdict: Verdict;
  detail: string;
  values: Record<string, Tri>;
}

export function judge(expr: Expr, principals: Principal[]): Judgement {
  const values: Record<string, Tri> = {};
  for (const p of principals) values[p.id] = evalExpr(expr, p);

  if (principals.length === 0) {
    return { verdict: 'UNDECIDED', detail: '零主体可达 —— 页面级可达性问题, 归 routeReachability 那道闸', values };
  }

  let runtimeAxis = expr;
  while (runtimeAxis.k === 'not') runtimeAxis = runtimeAxis.a;
  if (runtimeAxis.k === 'runtime-configured') {
    return {
      verdict: 'EFFECTIVE',
      detail: 'L1/L2 运行时配置可使同一可达主体取真或假: ' + runtimeAxis.canon,
      values,
    };
  }

  const raw = principals.map((p) => values[p.id] as Tri);
  if (!raw.includes('U')) {
    const distinct = new Set(raw);
    if (distinct.size === 1) {
      return {
        verdict: 'VACUOUS',
        detail: distinct.has('T')
          ? '对全部 ' + principals.length + ' 个可达主体恒为真 —— 这道判断从不拒绝任何人'
          : '对全部 ' + principals.length + ' 个可达主体恒为假 —— 被它守着的分支从不执行',
        values,
      };
    }
    const t = principals.find((p) => values[p.id] === 'T');
    const f = principals.find((p) => values[p.id] === 'F');
    return { verdict: 'EFFECTIVE', detail: '举得出反例: ' + String(t?.id) + '=真 / ' + String(f?.id) + '=假', values };
  }

  // Kleene 之后仍有 U ⇒ 退一步只问「它的权限部分」
  const c = core(expr);
  if (!c) {
    const who = principals.filter((p) => values[p.id] === 'U').map((p) => p.id);
    return {
      verdict: 'UNDECIDED',
      detail: '判不出权限内容(动态参数/读不懂的别名): ' + who.slice(0, 3).join(', ') + '…',
      values,
    };
  }
  const coreVals: Record<string, Tri> = {};
  for (const p of principals) coreVals[p.id] = evalExpr(c, p);
  const cv = principals.map((p) => coreVals[p.id] as Tri);
  if (cv.includes('U')) {
    return { verdict: 'UNDECIDED', detail: '权限骨架 ' + canon(c) + ' 仍判不出', values: coreVals };
  }
  const distinct = new Set(cv);
  if (distinct.size === 1) {
    return {
      verdict: 'VACUOUS',
      detail:
        '权限部分 ' + canon(c) + ' 对全部 ' + principals.length + ' 个可达主体恒为' +
        (distinct.has('T') ? '真' : '假') + '（其余部分取决于运行时数据）',
      values: coreVals,
    };
  }
  const t = principals.find((p) => coreVals[p.id] === 'T');
  const f = principals.find((p) => coreVals[p.id] === 'F');
  return {
    verdict: 'EFFECTIVE',
    detail: '权限部分举得出反例: ' + String(t?.id) + '=真 / ' + String(f?.id) + '=假',
    values: coreVals,
  };
}

// ==================== 总装 ====================

export interface Finding {
  file: string;
  relFile: string;
  key: string;
  sites: BranchSite[];
  principals: string[];
  values: Record<string, Tri>;
  verdict: Verdict;
  detail: string;
}

export interface AnalysisResult {
  findings: Finding[];
  vacuous: Finding[];
  effective: Finding[];
  undecided: Finding[];
  stats: {
    principals: number;
    routes: number;
    routesWithFile: number;
    redirectRoutes: number;
    reachableViewFiles: number;
    guardCalls: number;
    allowedPairs: number;
    branchesExamined: number;
    /** 路由表里有 name 却没被 AST 认出来、且**不是** redirect 的 —— 应当恒为 0 */
    namedRoutesMissingFile: string[];
    unresolvedComponentSpecs: string[];
  };
  principalsByFile: Map<string, string[]>;
}

export function relative(file: string): string {
  return path.relative(SRC_DIR, file).split(path.sep).join('/');
}

let analysisPromise: Promise<AnalysisResult> | null = null;

export function analyze(): Promise<AnalysisResult> {
  if (analysisPromise) return analysisPromise;
  analysisPromise = (async (): Promise<AnalysisResult> => {
    const h = await buildHarness();
    const { unresolved } = routeNameToFile();

    const principalsByFile = new Map<string, Set<string>>();
    let guardCalls = 0;
    let allowedPairs = 0;

    const routesWithFile = h.routes.filter((r) => r.file !== null);
    for (const p of h.principals) {
      for (const route of routesWithFile) {
        guardCalls += 1;
        // eslint-disable-next-line no-await-in-loop
        const ok = await guardAllows(h, p, route);
        if (!ok) continue;
        allowedPairs += 1;
        const set = principalsByFile.get(route.file as string) ?? new Set<string>();
        set.add(p.id);
        principalsByFile.set(route.file as string, set);
      }
    }

    const byId = new Map(h.principals.map((p) => [p.id, p]));
    const findings: Finding[] = [];
    let branchesExamined = 0;

    for (const [file, idSet] of principalsByFile) {
      const ids = [...idSet].sort();
      const ps = ids.map((id) => byId.get(id) as Principal);
      for (const branch of branchesIn(file)) {
        branchesExamined += 1;
        const j = judge(branch.expr, ps);
        findings.push({
          file,
          relFile: relative(file),
          key: branch.key,
          sites: branch.sites.sort((a, b) => a.line - b.line),
          principals: ids,
          values: j.values,
          verdict: j.verdict,
          detail: j.detail,
        });
      }
    }

    findings.sort((a, b) => (a.relFile + '::' + a.key).localeCompare(b.relFile + '::' + b.key));

    const namedRoutesMissingFile = h.routes
      .filter((r) => r.name && !r.file && !r.isRedirect)
      .map((r) => r.name + ' (' + r.path + ')');

    return {
      findings,
      vacuous: findings.filter((f) => f.verdict === 'VACUOUS'),
      effective: findings.filter((f) => f.verdict === 'EFFECTIVE'),
      undecided: findings.filter((f) => f.verdict === 'UNDECIDED'),
      stats: {
        principals: h.principals.length,
        routes: h.routes.length,
        routesWithFile: routesWithFile.length,
        redirectRoutes: h.routes.filter((r) => r.isRedirect).length,
        reachableViewFiles: principalsByFile.size,
        guardCalls,
        allowedPairs,
        branchesExamined,
        namedRoutesMissingFile,
        unresolvedComponentSpecs: unresolved,
      },
      principalsByFile: new Map(
        [...principalsByFile].map(([f, s]) => [f, [...s].sort()] as [string, string[]]),
      ),
    };
  })();
  return analysisPromise;
}

// ==================== 对照用：直接分析一段源码 ====================

/**
 * 阴性对照专用：不经过路由/守卫，直接对一段 SFC 源码 + 一组给定的主体做判定。
 *
 * ⚠️ 它走的是**同一条** `branchesIn` / `toExpr` / `judge` 路径 ——
 *    这样对照才在守真正的那段逻辑，而不是守一份复制品（形态 D）。
 */
export function judgeSource(
  h: Harness,
  code: string,
  principalIds: string[],
  fileName = '__probe__.vue',
): Array<{ key: string; verdict: Verdict; detail: string; values: Record<string, Tri> }> {
  const virtual = path.join(VIEWS_DIR, '__gate_probe__', fileName);
  const { descriptor } = parseSFC(code, { filename: virtual });
  const block = descriptor.scriptSetup ?? descriptor.script;
  viewCache.set(virtual, {
    file: virtual,
    script: block
      ? ts.createSourceFile(virtual + '.ts', block.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
      : null,
    scriptStartLine: block ? block.loc.start.line : 1,
    template: descriptor.template?.ast ?? null,
    templateStartLine: descriptor.template ? descriptor.template.loc.start.line : 1,
  });
  envCache.delete(virtual);
  const byId = new Map(h.principals.map((p) => [p.id, p]));
  const ps = principalIds.map((id) => {
    const p = byId.get(id);
    if (!p) throw new Error('未知主体: ' + id + '（可用示例: ' + h.principals.slice(0, 3).map((x) => x.id).join(', ') + '）');
    return p;
  });
  const out = branchesIn(virtual).map((branch) => {
    const j = judge(branch.expr, ps);
    return { key: branch.key, verdict: j.verdict, detail: j.detail, values: j.values };
  });
  viewCache.delete(virtual);
  envCache.delete(virtual);
  return out;
}
