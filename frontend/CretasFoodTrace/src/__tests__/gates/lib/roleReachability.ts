/**
 * 角色可达性分析器 —— 二阶可达性。
 *
 * 屏级可达（`screenReachability.test.ts`，存量 35）问的是「有没有人走得到这个屏」。
 * 这里问的是下一层：**走到这个屏的那些角色，能不能让这个权限分支两边都取到值？**
 *
 * 举不出一个「走得到 + 条件为假」的角色 ⇒ 那个 `if (!canX)` / `granted={!locked}`
 * 从来没有执行过 —— 屏是可达的，**分支的状态不可达**。
 *
 * ## 算的是这条链（不靠人追）
 *
 *   角色 → 走哪个 Navigator          AppNavigator.tsx 的 RoleBasedNavigator 分流
 *        → 那棵树注册了哪些屏         Stack.Screen / Tab.Screen，**含条件渲染的守卫**
 *        → 那些屏里有哪些权限分支     if / 三元 / {cond && …} / granted={…} / const isX = …
 *        → 该角色的权限是什么         **调用真的** permissionHelper / types/auth
 *
 * ## ⛔ 权限语义不复制一份
 *
 * 分析器**不重写** PERMISSION_MATRIX，也不重写 `hasPermission` 的兜底规则。
 * 它把条件解析成一棵表达式树，叶子是**真函数的引用**，然后拿每个角色的合成 user
 * 去调它们。形态 D（同一个东西有两份必然漂）在这里从物理上不成立。
 *
 * 代价是多了一张**成员表**（哪些函数算权限谓词），那张表由
 * `roleReachability.test.ts` 里一道完备性断言钉住：permissionHelper 里任何
 * `is*` / `can*` / `has*` 导出没登记进来就红。
 *
 * ## 三态，⛔ 不许二分
 *
 *   空转 (VACUOUS)     能走到这屏的角色里，条件恒真或恒假 —— 举不出反例
 *   有效 (EFFECTIVE)   举得出反例：有角色为真、有角色为假
 *   判不了 (UNDECIDED)  条件依赖运行时数据 / 分析器读不懂 / 屏本身零角色可达
 *
 * 「判不了」单列。把不确定折叠进「有效」会让闸看起来干净而实际失明。
 *
 * ## Kleene 三值逻辑（这一条是本分析器能出结论的关键）
 *
 * `locked = Boolean(item.adminOnly) && !isAdmin` 里 `item.adminOnly` 是**运行时数据**，
 * 单看它是 U。但 `and(U, F) = F` —— 只要 `!isAdmin` 恒假，整条恒假，
 * **不需要知道 adminOnly 是什么**。二值逻辑在这里只能报「判不了」。
 *
 * ## ⚠️ 已知盲区（写明，⛔ 不靠加启发式逼近）
 *
 *  1. **导航守卫里非角色相关的条件一律假设可满足**（`isScreenEnabled('X')` 工厂特性、
 *     `isRestaurant(user)` 工厂类型、`SIMPLIFIED_WAREHOUSE_NAV` 常量…）。
 *     方向是**保守的**：它只会让可达角色集**变大** ⇒ 更容易举出反例 ⇒
 *     只会把「空转」误判成「有效」，不会反过来。棘轮量的是空转数，所以宁可少报。
 *  2. **合成 user 的 `permissions` 来自 F006 真实登录快照**。这不是前端授权
 *     默认值；它只是让静态分析器能用同一个 `hasPermission` 复现当前后端权限。
 *     后端角色权限变更时，必须用新的真实登录结果同步此测试夹具。
 *  3. **只看屏文件自己**。屏里渲染的子组件里的权限分支不在本分析器视野内。
 *  4. **`navigate()` 冒泡不影响本闸**：屏级可达由另一道闸负责；这里只沿注册树走。
 *     一个屏若被别的 Stack 通过冒泡进入，它在本闸里的角色集只会**偏小** ——
 *     偏小的方向是「更容易判成空转」，⚠️ 这一条与盲区 1 方向相反，
 *     所以判成空转的条目仍要人读一眼再修（棘轮不硬红，正是为此）。
 *  5. **同名局部变量跨作用域**取文件里第一个声明。文件内重名会误解析。
 *  6. **动态屏名 / 高阶组件包装**读不出来 ⇒ 那条链在这里断掉，下游屏不计入。
 */
import fs from 'fs';
import path from 'path';
import ts from 'typescript';
import {
  FACTORY_ROLES,
  PLATFORM_ROLES,
  ROLE_METADATA,
  DEPARTMENTS,
  hasPermission as authHasPermission,
  isPlatformUser as authIsPlatformUser,
  isFactoryUser as authIsFactoryUser,
  getUserRole as authGetUserRole,
} from '../../../types/auth';
import type { User, FactoryRole, PlatformRole, Department } from '../../../types/auth';
import * as ph from '../../../utils/permissionHelper';

// ==================== 目录常量 ====================

export const SRC_DIR = path.resolve(__dirname, '../../..');
export const NAV_DIR = path.join(SRC_DIR, 'navigation');
export const APP_NAVIGATOR = path.join(NAV_DIR, 'AppNavigator.tsx');
/** ⚠️ 用 path.sep 拼，⛔ 不写任何反斜杠转义（隔壁那道闸就是被转义吃掉过一次）。 */
const NAV_MARKER = path.sep + 'navigation' + path.sep;

// ==================== 角色宇宙 + 合成 user ====================

export const ALL_ROLES: string[] = [
  ...Object.values(PLATFORM_ROLES),
  ...Object.values(FACTORY_ROLES),
];

const VALID_DEPARTMENTS: string[] = Object.values(DEPARTMENTS);

const PERMISSIONS_BY_ROLE: Record<string, string[]> = {
  platform_admin: ['*:*'],
  factory_super_admin: ['*:*'],
  hr_admin: ['hr:*'],
  procurement_manager: ['procurement:*'],
  sales_manager: ['sales:*'],
  operations_coordinator: ['production:*'],
  production_manager: ['production:*'],
  warehouse_manager: ['warehouse:*'],
  equipment_admin: ['equipment:*'],
  quality_manager: ['quality:*'],
  finance_manager: ['finance:*'],
  cashier: ['finance:*'],
  dispatcher: ['production:*'],
  workshop_supervisor: ['production:*'],
  quality_inspector: ['quality:*'],
  operator: ['production:*'],
  yield_operator: ['production:*'],
  warehouse_worker: ['warehouse:*'],
  viewer: ['view:*'],
  // 🔴 2026-09-06 订正：`permission_admin` 原来写的是 `[]`，而后端
  // `FactoryUserRole.getPermissionPrefix()` 把它和 platform_admin /
  // factory_super_admin 放在同一个 case 里返回 `"*"` ⇒ 实发 `*:*`。
  // 写成空数组等于让分析器以为这个角色**什么权限都没有**，
  // 所有 `hasPermission(user, …)` 分支在它身上恒假 —— 而它恰好是
  // MainNavigator 那棵树上少数几个可达角色之一。
  permission_admin: ['*:*'],
  // ⚠️ `department_admin` 命中 `default: return "none"` ⇒ 实发 `none:*`，
  // 与 `[]` 在 `hasPermission` 下行为一致（都匹配不上任何业务前缀）。
  // 故意保持原值，⛔ 不顺手改：它走 WorkshopSupervisorNavigator，
  // 不在本次改动的射程内，改了会平白扰动棘轮读数。
  department_admin: [],
};

/** 每个角色一个合成 user，权限只用于静态分析的真实后端快照。 */
export function makeUser(role: string): User {
  const metaDept = ROLE_METADATA[role]?.department;
  const department = (metaDept && VALID_DEPARTMENTS.includes(metaDept) ? metaDept : undefined) as
    | Department
    | undefined;

  const base = {
    id: 1,
    username: 'probe_' + role,
    email: 'probe@example.com',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    isActive: true,
    role,
    department,
  };

  if ((Object.values(PLATFORM_ROLES) as string[]).includes(role)) {
    return {
      ...base,
      roleCode: role as PlatformRole,
      userType: 'platform',
      platformUser: { role: role as PlatformRole, permissions: PERMISSIONS_BY_ROLE[role] ?? [] },
    };
  }
  return {
    ...base,
    roleCode: role as FactoryRole,
    factoryId: 'PROBE_FACTORY',
    userType: 'factory',
    factoryUser: {
      role: role as FactoryRole,
      factoryId: 'PROBE_FACTORY',
      factoryType: 'FACTORY',
      department,
      permissions: PERMISSIONS_BY_ROLE[role] ?? [],
    },
  };
}

const USER_BY_ROLE = new Map<string, User>(ALL_ROLES.map((r) => [r, makeUser(r)]));

function userOf(role: string): User {
  const u = USER_BY_ROLE.get(role);
  /* istanbul ignore next -- 只在调用方传了不存在的角色时发生 */
  if (!u) throw new Error('未知角色: ' + role);
  return u;
}

// ==================== 权限谓词成员表 ====================

/**
 * 名字 → **真函数**。⛔ 这里只登记「谁是权限谓词」，不复制任何权限语义。
 *
 * 别名（`checkUserPermission`）来自 `MainNavigator.tsx` 的
 * `import { hasPermission as checkUserPermission }`，本表按**调用点写的名字**匹配。
 */
export const BOOL_HELPERS: Record<string, (...args: never[]) => boolean> = {
  // types/auth
  hasPermission: authHasPermission as (...a: never[]) => boolean,
  checkUserPermission: authHasPermission as (...a: never[]) => boolean,
  isPlatformUser: authIsPlatformUser as (...a: never[]) => boolean,
  isFactoryUser: authIsFactoryUser as (...a: never[]) => boolean,
  // permissionHelper
  isPlatformAdmin: ph.isPlatformAdmin as (...a: never[]) => boolean,
  isSuperAdmin: ph.isSuperAdmin as (...a: never[]) => boolean,
  isManager: ph.isManager as (...a: never[]) => boolean,
  isWorker: ph.isWorker as (...a: never[]) => boolean,
  canManageRole: ph.canManageRole as (...a: never[]) => boolean,
  isProductionManager: ph.isProductionManager as (...a: never[]) => boolean,
  isQualityManager: ph.isQualityManager as (...a: never[]) => boolean,
  isWorkshopSupervisor: ph.isWorkshopSupervisor as (...a: never[]) => boolean,
  isQualityInspector: ph.isQualityInspector as (...a: never[]) => boolean,
  isOperator: ph.isOperator as (...a: never[]) => boolean,
  isWarehouseRole: ph.isWarehouseRole as (...a: never[]) => boolean,
  isHrAdmin: ph.isHrAdmin as (...a: never[]) => boolean,
  isEquipmentAdmin: ph.isEquipmentAdmin as (...a: never[]) => boolean,
  isPermissionAdmin: ph.isPermissionAdmin as (...a: never[]) => boolean,
  isDepartmentAdmin: ph.isDepartmentAdmin as (...a: never[]) => boolean,
  hasModulePermission: ph.hasModulePermission as (...a: never[]) => boolean,
  canAccessModule: ph.canAccessModule as (...a: never[]) => boolean,
  canManageBasicData: ph.canManageBasicData as (...a: never[]) => boolean,
  canManageUsers: ph.canManageUsers as (...a: never[]) => boolean,
  canManageDepartments: ph.canManageDepartments as (...a: never[]) => boolean,
  canManagePermissions: ph.canManagePermissions as (...a: never[]) => boolean,
  canViewReports: ph.canViewReports as (...a: never[]) => boolean,
  canManageProduction: ph.canManageProduction as (...a: never[]) => boolean,
  canManageWarehouse: ph.canManageWarehouse as (...a: never[]) => boolean,
  canManageQuality: ph.canManageQuality as (...a: never[]) => boolean,
  canManageEquipment: ph.canManageEquipment as (...a: never[]) => boolean,
  canManageHR: ph.canManageHR as (...a: never[]) => boolean,
  canAccessWhitelist: ph.canAccessWhitelist as (...a: never[]) => boolean,
  canAccessSmartBI: ph.canAccessSmartBI as (...a: never[]) => boolean,
  canUploadExcel: ph.canUploadExcel as (...a: never[]) => boolean,
  canAccessSalesAnalysis: ph.canAccessSalesAnalysis as (...a: never[]) => boolean,
  canAccessFinanceAnalysis: ph.canAccessFinanceAnalysis as (...a: never[]) => boolean,
  canAccessExecutiveDashboard: ph.canAccessExecutiveDashboard as (...a: never[]) => boolean,
  canUseAIQuery: ph.canUseAIQuery as (...a: never[]) => boolean,
  canViewAlerts: ph.canViewAlerts as (...a: never[]) => boolean,
};

/** 返回**字符串**的角色取值函数（用于 `userRole === 'x'` 这类比较）。 */
const STRING_HELPERS: Record<string, (u: User) => string | undefined> = {
  getUserRole: (u) => authGetUserRole(u),
  getRoleCode: (u) => ph.getRoleCode(u),
  getRoleDepartment: (u) => ph.getRoleDepartment(ph.getRoleCode(u)),
  getRoleName: (u) => ph.getRoleName(u),
  getFactoryId: (u) => ph.getFactoryId(u),
};

/**
 * ⚠️ 只认这三个名字，⛔ **不要**再往里加 `u` / `item` 这种短名。
 *
 * 第一版把 `u` 也算成登录用户，于是 `users.filter(u => u.roleCode === 'platform_admin')`
 * ——一个统计**别人**角色分布的表达式——被判成「当前用户的角色恒为 platform_admin」，
 * 一口气造出 4 条假阳性。⇒ 仪器把「提到 role」当成了「按 role 分支」。
 */
const USER_IDENTIFIERS = new Set(['user', 'currentUser', 'authUser']);

/**
 * ⛔ 字面量对象的 `obj[key]` 会**走原型链**：`BOOL_HELPERS['toString']` 返回
 * `Object.prototype.toString`，于是 `formData.hourlyRate?.toString()` 被当成
 * 权限谓词，一口气造出 20 条假阳性（`toString(user)` 恒为真）。
 * ⇒ 一切按名字查表一律走这个自有属性检查。
 */
function pick<T>(table: Record<string, T>, key: string | null | undefined): T | undefined {
  if (!key) return undefined;
  return Object.prototype.hasOwnProperty.call(table, key) ? table[key] : undefined;
}

/**
 * 标识符在这一点上是不是被**局部形参/局部声明**遮蔽了。
 * 遮蔽了就不是登录用户，读它的 `.role` 得到的是别人的角色。
 */
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

/** `user?.userType` / `user.factoryUser?.role` … 归一化后能直接读出来的字段路径。 */
const ROLE_FIELD_PATHS: Record<string, (u: User) => string | undefined> = {
  userType: (u) => u.userType,
  'factoryUser.role': (u) => (u.userType === 'factory' ? u.factoryUser?.role : undefined),
  'platformUser.role': (u) => (u.userType === 'platform' ? u.platformUser?.role : undefined),
  role: (u) => u.role,
  roleCode: (u) => u.roleCode,
  'factoryUser.department': (u) =>
    u.userType === 'factory' ? u.factoryUser?.department : undefined,
  'factoryUser.factoryId': (u) => (u.userType === 'factory' ? u.factoryUser?.factoryId : undefined),
};

// ==================== 三值表达式 IR ====================

export type Tri = 'T' | 'F' | 'U';

export type Expr =
  | { k: 'atom'; canon: string; run: (u: User) => boolean }
  | { k: 'const'; value: boolean }
  | { k: 'not'; a: Expr }
  | { k: 'and'; a: Expr; b: Expr }
  | { k: 'or'; a: Expr; b: Expr }
  | { k: 'unknown'; canon: string };

export function canon(e: Expr): string {
  switch (e.k) {
    case 'atom':
      return e.canon;
    case 'const':
      return String(e.value);
    case 'not':
      return '!' + canon(e.a);
    case 'and':
      return '(' + canon(e.a) + ' && ' + canon(e.b) + ')';
    case 'or':
      return '(' + canon(e.a) + ' || ' + canon(e.b) + ')';
    case 'unknown':
      return '?{' + e.canon + '}';
  }
}

/** Kleene 三值。`and(U, F) = F` 是本分析器能对含运行时数据的条件出结论的原因。 */
export function evalExpr(e: Expr, u: User): Tri {
  switch (e.k) {
    case 'atom':
      try {
        return e.run(u) ? 'T' : 'F';
      } catch {
        return 'U';
      }
    case 'const':
      return e.value ? 'T' : 'F';
    case 'unknown':
      return 'U';
    case 'not': {
      const v = evalExpr(e.a, u);
      return v === 'U' ? 'U' : v === 'T' ? 'F' : 'T';
    }
    case 'and': {
      const l = evalExpr(e.a, u);
      const r = evalExpr(e.b, u);
      if (l === 'F' || r === 'F') return 'F';
      if (l === 'U' || r === 'U') return 'U';
      return 'T';
    }
    case 'or': {
      const l = evalExpr(e.a, u);
      const r = evalExpr(e.b, u);
      if (l === 'T' || r === 'T') return 'T';
      if (l === 'U' || r === 'U') return 'U';
      return 'F';
    }
  }
}

/** 有没有一个**可判定的**角色叶子。没有就不是「权限分支」，整条跳过。 */
export function hasRoleAtom(e: Expr): boolean {
  switch (e.k) {
    case 'atom':
      return true;
    case 'const':
    case 'unknown':
      return false;
    case 'not':
      return hasRoleAtom(e.a);
    case 'and':
    case 'or':
      return hasRoleAtom(e.a) || hasRoleAtom(e.b);
  }
}

// ==================== 源文件解析 ====================

const sourceCache = new Map<string, ts.SourceFile>();

export function parseFile(file: string): ts.SourceFile | null {
  const cached = sourceCache.get(file);
  if (cached) return cached;
  let text: string;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    return null;
  }
  const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  sourceCache.set(file, sf);
  return sf;
}

export function lineOf(node: ts.Node): number {
  const sf = node.getSourceFile();
  return sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1;
}

const EXTENSIONS = ['.tsx', '.ts', '.jsx', '.js'];

/** 相对路径 import → 真实文件。node_modules 一律返回 null（分析器不跨出仓库）。 */
function resolveModule(fromFile: string, spec: string): string | null {
  if (!spec.startsWith('.')) return null;
  const base = path.resolve(path.dirname(fromFile), spec);
  for (const ext of EXTENSIONS) {
    if (fs.existsSync(base + ext)) return base + ext;
  }
  for (const ext of EXTENSIONS) {
    const idx = path.join(base, 'index' + ext);
    if (fs.existsSync(idx)) return idx;
  }
  return null;
}

interface ImportBinding {
  file: string;
  exported: string; // 'default' 或具名
}

const importCache = new Map<string, Map<string, ImportBinding>>();

/** 本地标识符 → 它 import 自哪个文件的哪个导出。 */
function importsOf(file: string): Map<string, ImportBinding> {
  const cached = importCache.get(file);
  if (cached) return cached;
  const out = new Map<string, ImportBinding>();
  const sf = parseFile(file);
  if (!sf) {
    importCache.set(file, out);
    return out;
  }
  for (const stmt of sf.statements) {
    if (!ts.isImportDeclaration(stmt) || !stmt.importClause) continue;
    if (!ts.isStringLiteral(stmt.moduleSpecifier)) continue;
    const target = resolveModule(file, stmt.moduleSpecifier.text);
    if (!target) continue;
    const clause = stmt.importClause;
    if (clause.name) out.set(clause.name.text, { file: target, exported: 'default' });
    const bindings = clause.namedBindings;
    if (bindings && ts.isNamedImports(bindings)) {
      for (const el of bindings.elements) {
        out.set(el.name.text, {
          file: target,
          exported: (el.propertyName ?? el.name).text,
        });
      }
    }
  }
  importCache.set(file, out);
  return out;
}

/**
 * 顺着 re-export 把「某文件的某导出」追到**真正声明它的文件**。
 * 本仓大量用 `screens/xxx/index.ts` 做 barrel，不追就全部断在 index 上。
 */
export function resolveExport(
  file: string,
  exported: string,
  depth = 0,
): { file: string; local: string } | null {
  if (depth > 8) return null;
  const sf = parseFile(file);
  if (!sf) return null;

  const starTargets: string[] = [];

  for (const stmt of sf.statements) {
    // export { A, B as C } from './x'   /   export { A }
    if (ts.isExportDeclaration(stmt)) {
      const spec = stmt.moduleSpecifier;
      const target =
        spec && ts.isStringLiteral(spec) ? resolveModule(file, spec.text) : null;
      const clause = stmt.exportClause;
      if (clause && ts.isNamedExports(clause)) {
        for (const el of clause.elements) {
          if (el.name.text !== exported) continue;
          const inner = (el.propertyName ?? el.name).text;
          if (target) return resolveExport(target, inner, depth + 1);
          const viaImport = importsOf(file).get(inner);
          if (viaImport) return resolveExport(viaImport.file, viaImport.exported, depth + 1);
          return { file, local: inner };
        }
      } else if (!clause && target) {
        starTargets.push(target);
      }
    }
    // export default X
    if (exported === 'default' && ts.isExportAssignment(stmt) && !stmt.isExportEquals) {
      const e = stmt.expression;
      if (ts.isIdentifier(e)) {
        const viaImport = importsOf(file).get(e.text);
        if (viaImport) return resolveExport(viaImport.file, viaImport.exported, depth + 1);
        return { file, local: e.text };
      }
      return { file, local: 'default' };
    }
    // export default function Foo() {}
    if (
      exported === 'default' &&
      ts.isFunctionDeclaration(stmt) &&
      hasModifier(stmt, ts.SyntaxKind.DefaultKeyword)
    ) {
      return { file, local: stmt.name?.text ?? 'default' };
    }
    // export function Foo() {}  /  export const Foo = ...
    if (ts.isFunctionDeclaration(stmt) && stmt.name?.text === exported) {
      return { file, local: exported };
    }
    if (ts.isVariableStatement(stmt)) {
      for (const d of stmt.declarationList.declarations) {
        if (ts.isIdentifier(d.name) && d.name.text === exported) return { file, local: exported };
      }
    }
  }

  // `export * from './x'` —— 逐个追
  for (const target of starTargets) {
    const found = resolveExport(target, exported, depth + 1);
    if (found) return found;
  }

  // 本地声明但没 export 关键字（同文件内引用）
  const localNames = localDeclarationNames(sf);
  if (localNames.has(exported)) return { file, local: exported };
  return null;
}

function hasModifier(node: ts.Node, kind: ts.SyntaxKind): boolean {
  const mods = ts.canHaveModifiers(node) ? ts.getModifiers(node) : undefined;
  return !!mods && mods.some((m) => m.kind === kind);
}

function localDeclarationNames(sf: ts.SourceFile): Set<string> {
  const names = new Set<string>();
  for (const stmt of sf.statements) {
    if (ts.isFunctionDeclaration(stmt) && stmt.name) names.add(stmt.name.text);
    if (ts.isVariableStatement(stmt)) {
      for (const d of stmt.declarationList.declarations) {
        if (ts.isIdentifier(d.name)) names.add(d.name.text);
      }
    }
    if (ts.isClassDeclaration(stmt) && stmt.name) names.add(stmt.name.text);
  }
  return names;
}

// ==================== 表达式：AST → IR ====================

/** 去掉 `?.` 与空白，得到 `user.factoryUser.role` 这样的规范路径。 */
function normalizedPath(node: ts.Node): string | null {
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
      return parts.join('.');
    }
    return null;
  }
}

/** 表达式 → 「读一个字符串」的函数。读不出来返回 null。 */
function stringReader(
  node: ts.Node,
  ctx: FileCtx,
  depth: number,
): { canon: string; run: (u: User) => string | undefined } | null {
  if (depth > 12) return null;
  const n = unwrap(node);

  const p = normalizedPath(n);
  if (p) {
    const segs = p.split('.');
    const head = segs[0] ?? '';
    if (USER_IDENTIFIERS.has(head)) {
      if (isShadowed(n, head)) return null;
      const rest = segs.slice(1).join('.');
      const reader = pick(ROLE_FIELD_PATHS, rest);
      if (reader) return { canon: 'user.' + rest, run: reader };
      return null;
    }
    // 局部别名：const userRole = getUserRole(user)
    if (segs.length === 1) {
      if (isShadowed(n, head)) return null;
      const init = ctx.consts.get(head);
      if (init) return stringReader(init, ctx, depth + 1);
    }
    return null;
  }

  if (ts.isCallExpression(n)) {
    const callee = calleeName(n);
    const fn = pick(STRING_HELPERS, callee);
    if (fn && callee) {
      // getUserRole(user)
      if (n.arguments.length > 0 && n.arguments.every((a) => isUserArg(a))) {
        return { canon: callee + '(user)', run: fn };
      }
      // getUserRole()  —— 从 authStore 解构出来的同名取值函数
      if (n.arguments.length === 0) return { canon: callee + '()', run: fn };
    }
    // useAuthStore(state => state.getUserRole())
    if (callee === 'useAuthStore' && n.arguments.length === 1) {
      const arg0 = n.arguments[0];
      const arg = arg0 ? unwrap(arg0) : undefined;
      if (arg && ts.isArrowFunction(arg) && !ts.isBlock(arg.body)) {
        const inner = unwrap(arg.body);
        if (ts.isCallExpression(inner) && inner.arguments.length === 0) {
          const innerName = calleeName(inner);
          const innerFn = pick(STRING_HELPERS, innerName);
          if (innerFn && innerName) return { canon: innerName + '()', run: innerFn };
        }
      }
    }
    return null;
  }

  /**
   * `user?.factoryUser?.role || 'viewer'` —— 本仓最常见的取角色写法。
   * 不认它，`const roleCode = …` 这条链就断在第一步，整份屏的权限分支全部看不见。
   * ⚠️ 实测：加这一条之前，`WorkTypeManagementScreen` 这一族 15 个文件一条都读不出来。
   */
  if (ts.isBinaryExpression(n)) {
    const op = n.operatorToken.kind;
    const isNullish = op === ts.SyntaxKind.QuestionQuestionToken;
    if (op === ts.SyntaxKind.BarBarToken || isNullish) {
      const left = stringReader(n.left, ctx, depth + 1);
      const right = stringReader(n.right, ctx, depth + 1) ?? literalReader(n.right);
      if (left && right) {
        return {
          canon: '(' + left.canon + (isNullish ? ' ?? ' : ' || ') + right.canon + ')',
          run: (u) => {
            const v = left.run(u);
            if (isNullish) return v ?? right.run(u);
            return v ? v : right.run(u);
          },
        };
      }
    }
    return null;
  }

  /** `user?.userType === 'platform' ? user.platformUser?.role : user?.factoryUser?.role` */
  if (ts.isConditionalExpression(n)) {
    const cond = toExpr(n.condition, ctx, depth + 1);
    const a = stringReader(n.whenTrue, ctx, depth + 1);
    const b = stringReader(n.whenFalse, ctx, depth + 1);
    if (a && b && hasRoleAtom(cond)) {
      return {
        canon: '(' + canon(cond) + ' ? ' + a.canon + ' : ' + b.canon + ')',
        run: (u) => {
          const v = evalExpr(cond, u);
          if (v === 'T') return a.run(u);
          if (v === 'F') return b.run(u);
          return undefined;
        },
      };
    }
  }
  return null;
}

/** 字符串字面量当成一个「读出常量」的 reader，让 `a || b || 'viewer'` 这类链能整条求值。 */
function literalReader(
  node: ts.Node,
): { canon: string; run: (u: User) => string | undefined } | null {
  const n = unwrap(node);
  if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) {
    const lit = n.text;
    return { canon: "'" + lit + "'", run: () => lit };
  }
  return null;
}

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

function isUserArg(a: ts.Node): boolean {
  const p = normalizedPath(a);
  const head = p ? p.split('.')[0] ?? '' : '';
  return !!p && USER_IDENTIFIERS.has(head) && !isShadowed(a, head);
}

export interface FileCtx {
  file: string;
  /** 文件内第一个同名 const/let 的初始化表达式（盲区 5）。 */
  consts: Map<string, ts.Expression>;
}

const ctxCache = new Map<string, FileCtx>();

export function fileCtx(file: string): FileCtx {
  const cached = ctxCache.get(file);
  if (cached) return cached;
  const consts = new Map<string, ts.Expression>();
  const sf = parseFile(file);
  if (sf) {
    const visit = (n: ts.Node): void => {
      if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name) && n.initializer) {
        if (!consts.has(n.name.text)) consts.set(n.name.text, n.initializer);
      }
      ts.forEachChild(n, visit);
    };
    visit(sf);
  }
  const ctx: FileCtx = { file, consts };
  ctxCache.set(file, ctx);
  return ctx;
}

const UNKNOWN_TEXT_LIMIT = 60;

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

/** 布尔表达式 AST → 三值 IR。读不懂的子树落成 `unknown`，⛔ 不猜。 */
export function toExpr(node: ts.Node, ctx: FileCtx, depth = 0): Expr {
  if (depth > 12) return unknownOf(node);
  const n = unwrap(node);

  if (n.kind === ts.SyntaxKind.TrueKeyword) return { k: 'const', value: true };
  if (n.kind === ts.SyntaxKind.FalseKeyword) return { k: 'const', value: false };

  if (ts.isPrefixUnaryExpression(n) && n.operator === ts.SyntaxKind.ExclamationToken) {
    const inner = toExpr(n.operand, ctx, depth + 1);
    return { k: 'not', a: inner };
  }

  if (ts.isBinaryExpression(n)) {
    const op = n.operatorToken.kind;
    if (op === ts.SyntaxKind.AmpersandAmpersandToken) {
      return { k: 'and', a: toExpr(n.left, ctx, depth + 1), b: toExpr(n.right, ctx, depth + 1) };
    }
    if (op === ts.SyntaxKind.BarBarToken || op === ts.SyntaxKind.QuestionQuestionToken) {
      return { k: 'or', a: toExpr(n.left, ctx, depth + 1), b: toExpr(n.right, ctx, depth + 1) };
    }
    const eq =
      op === ts.SyntaxKind.EqualsEqualsEqualsToken || op === ts.SyntaxKind.EqualsEqualsToken;
    const ne =
      op === ts.SyntaxKind.ExclamationEqualsEqualsToken ||
      op === ts.SyntaxKind.ExclamationEqualsToken;
    if (eq || ne) {
      const cmp = comparisonAtom(n.left, n.right, ctx, depth);
      if (cmp) return ne ? { k: 'not', a: cmp } : cmp;
      return unknownOf(n);
    }
    return unknownOf(n);
  }

  if (ts.isConditionalExpression(n)) {
    const c = toExpr(n.condition, ctx, depth + 1);
    const a = toExpr(n.whenTrue, ctx, depth + 1);
    const b = toExpr(n.whenFalse, ctx, depth + 1);
    return { k: 'or', a: { k: 'and', a: c, b: a }, b: { k: 'and', a: { k: 'not', a: c }, b } };
  }

  if (ts.isCallExpression(n)) {
    const callee = calleeName(n);
    // Boolean(x) / !!x 只是包一层
    if (callee === 'Boolean' && n.arguments.length === 1) {
      const first = n.arguments[0];
      return first ? toExpr(first, ctx, depth + 1) : unknownOf(n);
    }
    // ['a','b'].includes(userRole)  /  new Set([...]).has(roleCode)
    const calleeNode = unwrap(n.expression);
    const isMembership = callee === 'includes' || callee === 'has';
    if (isMembership && n.arguments.length === 1 && ts.isPropertyAccessExpression(calleeNode)) {
      const arr = arrayOfStringLiterals(unwrap(calleeNode.expression), ctx);
      const first = n.arguments[0];
      const reader = first ? stringReader(first, ctx, depth + 1) : null;
      if (arr && reader) {
        return {
          k: 'atom',
          canon: '[' + arr.join('|') + '].' + String(callee) + '(' + reader.canon + ')',
          run: (u) => {
            const v = reader.run(u);
            return v !== undefined && arr.includes(v);
          },
        };
      }
      return unknownOf(n);
    }
    const helper = pick(BOOL_HELPERS, callee);
    if (helper) {
      const literals: string[] = [];
      let ok = true;
      for (const a of n.arguments) {
        if (isUserArg(a)) continue;
        const ua = unwrap(a);
        if (ts.isStringLiteral(ua) || ts.isNoSubstitutionTemplateLiteral(ua)) {
          literals.push(ua.text);
          continue;
        }
        // 局部别名指向字面量
        const p = normalizedPath(ua);
        const aliased = p && !p.includes('.') ? ctx.consts.get(p) : undefined;
        const ual = aliased ? unwrap(aliased) : undefined;
        if (ual && (ts.isStringLiteral(ual) || ts.isNoSubstitutionTemplateLiteral(ual))) {
          literals.push(ual.text);
          continue;
        }
        ok = false;
        break;
      }
      if (ok) {
        const args = literals;
        return {
          k: 'atom',
          canon: callee + '(user' + args.map((s) => ", '" + s + "'").join('') + ')',
          run: (u) => {
            const fn = helper as unknown as (...a: unknown[]) => boolean;
            return !!fn(u, ...args);
          },
        };
      }
    }
    return unknownOf(n);
  }

  // 标识符：先当角色字符串读（`if (userRole)` 罕见），否则展开局部 const
  if (ts.isIdentifier(n)) {
    const init = ctx.consts.get(n.text);
    if (init) return toExpr(init, ctx, depth + 1);
    return unknownOf(n);
  }

  return unknownOf(n);
}

function arrayOfStringLiterals(node: ts.Node, ctx: FileCtx): string[] | null {
  let n = unwrap(node);
  if (ts.isIdentifier(n)) {
    const init = ctx.consts.get(n.text);
    if (!init) return null;
    n = unwrap(init);
  }
  // new Set(['a','b']) 与 ['a','b'] 在这里等价
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

function comparisonAtom(
  left: ts.Node,
  right: ts.Node,
  ctx: FileCtx,
  depth: number,
): Expr | null {
  const l = unwrap(left);
  const r = unwrap(right);
  const lLit = ts.isStringLiteral(l) || ts.isNoSubstitutionTemplateLiteral(l) ? l.text : null;
  const rLit = ts.isStringLiteral(r) || ts.isNoSubstitutionTemplateLiteral(r) ? r.text : null;
  if (lLit !== null && rLit !== null) return null;
  if (lLit === null && rLit === null) return null;
  const lit = lLit ?? rLit;
  const other = lLit === null ? l : r;
  const reader = stringReader(other, ctx, depth + 1);
  if (!reader || lit === null) return null;
  return {
    k: 'atom',
    canon: reader.canon + " === '" + lit + "'",
    run: (u) => reader.run(u) === lit,
  };
}

// ==================== 导航树 ====================

export interface ScreenRegistration {
  screenName: string;
  navFile: string;
  line: number;
  /** 该 Screen 外面所有条件渲染守卫的合取。 */
  guard: Expr;
  componentRef: ts.Expression | null;
  /**
   * `initialParams={{ … }}` 里的**字符串字面量**键值对。
   *
   * 只收字面量：`initialParams={x}` / 非字符串值一律不进来（读不出来就是读不出来，
   * ⛔ 不猜）。`homeModulesNonEmptyPerRole.test.ts` 用它把「哪个导航器给首页派了
   * 哪个模块集」从**导航器源码**里读出来，而不是在测试里再抄一份对照表（形态 D）。
   */
  initialParams: Record<string, string>;
}

export interface ScopeKey {
  file: string;
  scope: string;
}

/** 找到文件里名为 `scope` 的组件函数体（函数声明或 const 箭头）。 */
export function findScopeBody(sf: ts.SourceFile, scope: string): ts.Node | null {
  let found: ts.Node | null = null;
  const visit = (n: ts.Node): void => {
    if (found) return;
    if (ts.isFunctionDeclaration(n) && n.name?.text === scope && n.body) {
      found = n.body;
      return;
    }
    if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name) && n.name.text === scope) {
      const init = n.initializer ? unwrap(n.initializer) : undefined;
      if (init && (ts.isArrowFunction(init) || ts.isFunctionExpression(init))) {
        found = init.body;
        return;
      }
    }
    ts.forEachChild(n, visit);
  };
  visit(sf);
  return found;
}

/** 从 JSX 节点往上走，收集 `{cond && …}` / `{cond ? … : …}` 的守卫。 */
export function guardsAbove(node: ts.Node, stopAt: ts.Node, ctx: FileCtx): Expr {
  let acc: Expr | null = null;
  let child: ts.Node = node;
  let parent: ts.Node | undefined = node.parent;
  while (parent && parent !== stopAt) {
    if (ts.isBinaryExpression(parent)) {
      const op = parent.operatorToken.kind;
      if (op === ts.SyntaxKind.AmpersandAmpersandToken && parent.right === child) {
        const g = toExpr(parent.left, ctx);
        acc = acc ? { k: 'and', a: g, b: acc } : g;
      }
    } else if (ts.isConditionalExpression(parent)) {
      const g = toExpr(parent.condition, ctx);
      if (parent.whenTrue === child) acc = acc ? { k: 'and', a: g, b: acc } : g;
      else if (parent.whenFalse === child) {
        const ng: Expr = { k: 'not', a: g };
        acc = acc ? { k: 'and', a: ng, b: acc } : ng;
      }
    }
    child = parent;
    parent = parent.parent;
  }
  return acc ?? { k: 'const', value: true };
}

const screensCache = new Map<string, ScreenRegistration[]>();

/** 某个 navigator 组件（file + 组件名）里注册的全部 Screen。 */
export function screensIn(key: ScopeKey): ScreenRegistration[] {
  const cacheKey = key.file + '::' + key.scope;
  const cached = screensCache.get(cacheKey);
  if (cached) return cached;
  const out: ScreenRegistration[] = [];
  const sf = parseFile(key.file);
  if (!sf) {
    screensCache.set(cacheKey, out);
    return out;
  }
  const body = findScopeBody(sf, key.scope);
  if (!body) {
    screensCache.set(cacheKey, out);
    return out;
  }
  const ctx = fileCtx(key.file);

  const handleJsx = (open: ts.JsxSelfClosingElement | ts.JsxOpeningElement, node: ts.Node) => {
    const tag = open.tagName.getText();
    if (!tag.endsWith('.Screen')) return;
    let screenName: string | null = null;
    let componentRef: ts.Expression | null = null;
    const initialParams: Record<string, string> = {};
    for (const attr of open.attributes.properties) {
      if (!ts.isJsxAttribute(attr) || !ts.isIdentifier(attr.name)) continue;
      const an = attr.name.text;
      const init = attr.initializer;
      if (an === 'name' && init && ts.isStringLiteral(init)) screenName = init.text;
      if (an === 'name' && init && ts.isJsxExpression(init) && init.expression) {
        const e = unwrap(init.expression);
        if (ts.isStringLiteral(e)) screenName = e.text;
      }
      if (an === 'component' && init && ts.isJsxExpression(init) && init.expression) {
        componentRef = init.expression;
      }
      if (an === 'initialParams' && init && ts.isJsxExpression(init) && init.expression) {
        const obj = unwrap(init.expression);
        if (ts.isObjectLiteralExpression(obj)) {
          for (const prop of obj.properties) {
            if (!ts.isPropertyAssignment(prop)) continue;
            const pname = ts.isIdentifier(prop.name)
              ? prop.name.text
              : ts.isStringLiteral(prop.name)
                ? prop.name.text
                : null;
            if (!pname) continue;
            const pval = unwrap(prop.initializer);
            if (ts.isStringLiteral(pval) || ts.isNoSubstitutionTemplateLiteral(pval)) {
              initialParams[pname] = pval.text;
            }
          }
        }
      }
    }
    if (!screenName) return;
    out.push({
      screenName,
      navFile: key.file,
      line: lineOf(open),
      guard: guardsAbove(node, body, ctx),
      componentRef,
      initialParams,
    });
  };

  const visit = (n: ts.Node): void => {
    if (ts.isJsxSelfClosingElement(n)) handleJsx(n, n);
    if (ts.isJsxElement(n)) handleJsx(n.openingElement, n);
    ts.forEachChild(n, visit);
  };
  visit(body);
  screensCache.set(cacheKey, out);
  return out;
}

/** `component={X}` → 目标（另一个文件的组件，或同文件里的局部组件）。 */
export function resolveComponent(navFile: string, ref: ts.Expression | null): ScopeKey | null {
  if (!ref) return null;
  const r = unwrap(ref);
  if (!ts.isIdentifier(r)) return null;
  const name = r.text;
  const imp = importsOf(navFile).get(name);
  if (imp) {
    const resolved = resolveExport(imp.file, imp.exported);
    if (resolved) return { file: resolved.file, scope: resolved.local };
    return { file: imp.file, scope: name };
  }
  const sf = parseFile(navFile);
  if (sf && localDeclarationNames(sf).has(name)) return { file: navFile, scope: name };
  return null;
}

function isNavigatorFile(file: string): boolean {
  return file.includes(NAV_MARKER);
}

// ==================== 角色 → 根导航器 ====================

export interface RoleDispatch {
  role: string;
  navigators: ScopeKey[];
}

/**
 * 解析 `AppNavigator.tsx` 的 `RoleBasedNavigator`：一串
 * `if (userRole === 'x' || …) return <XNavigator/>`，末尾一个兜底 return。
 *
 * ⚠️ 返回值可能是三元（`isRestaurant(user) ? <A/> : <B/>`）—— 那是**工厂类型**
 *    不是角色，两棵树都算可达（盲区 1 的方向：宁可多，不可少）。
 */
export function parseRoleDispatch(): RoleDispatch[] {
  const sf = parseFile(APP_NAVIGATOR);
  if (!sf) return [];
  const body = findScopeBody(sf, 'RoleBasedNavigator');
  if (!body || !ts.isBlock(body)) return [];

  const assigned = new Map<string, string[]>(); // role -> component names
  let fallback: string[] = [];
  const known = new Set(ALL_ROLES);

  const jsxNames = (n: ts.Node): string[] => {
    const names: string[] = [];
    const visit = (x: ts.Node): void => {
      if (ts.isJsxSelfClosingElement(x)) names.push(x.tagName.getText());
      else if (ts.isJsxElement(x)) names.push(x.openingElement.tagName.getText());
      ts.forEachChild(x, visit);
    };
    visit(n);
    return names;
  };

  const literalsIn = (n: ts.Node): string[] => {
    const lits: string[] = [];
    const visit = (x: ts.Node): void => {
      if (ts.isStringLiteral(x) || ts.isNoSubstitutionTemplateLiteral(x)) lits.push(x.text);
      ts.forEachChild(x, visit);
    };
    visit(n);
    return lits;
  };

  for (const stmt of body.statements) {
    if (ts.isIfStatement(stmt)) {
      const roles = literalsIn(stmt.expression).filter((s) => known.has(s));
      const comps = jsxNames(stmt.thenStatement);
      for (const role of roles) {
        const prev = assigned.get(role) ?? [];
        assigned.set(role, [...new Set([...prev, ...comps])]);
      }
      continue;
    }
    if (ts.isReturnStatement(stmt) && stmt.expression) {
      fallback = jsxNames(stmt.expression);
    }
  }

  const imports = importsOf(APP_NAVIGATOR);
  const toScope = (compName: string): ScopeKey | null => {
    const imp = imports.get(compName);
    if (!imp) return null;
    const resolved = resolveExport(imp.file, imp.exported);
    return resolved ? { file: resolved.file, scope: resolved.local } : null;
  };

  return ALL_ROLES.map((role) => {
    const comps = assigned.get(role) ?? fallback;
    const navigators = comps
      .map(toScope)
      .filter((x): x is ScopeKey => x !== null);
    return { role, navigators };
  });
}

// ==================== 角色 → 可达屏文件 ====================

export interface ReachEntry {
  /** 屏组件所在文件 */
  file: string;
  /** 走到它的路由名（去重） */
  routes: Set<string>;
}

/** 某角色能走到的全部屏文件。守卫为 F 剪枝；U 视为可满足（盲区 1）。 */
export function reachableScreensFor(role: string, dispatch: RoleDispatch[]): Map<string, Set<string>> {
  const user = userOf(role);
  const entry = dispatch.find((d) => d.role === role);
  const out = new Map<string, Set<string>>();
  if (!entry) return out;

  const visited = new Set<string>();
  const queue: ScopeKey[] = [...entry.navigators];

  while (queue.length > 0) {
    const nav = queue.shift();
    if (!nav) break;
    const navKey = nav.file + '::' + nav.scope;
    if (visited.has(navKey)) continue;
    visited.add(navKey);

    for (const reg of screensIn(nav)) {
      if (evalExpr(reg.guard, user) === 'F') continue;
      const target = resolveComponent(reg.navFile, reg.componentRef);
      if (!target) continue;
      if (isNavigatorFile(target.file)) {
        queue.push(target);
        continue;
      }
      const set = out.get(target.file) ?? new Set<string>();
      set.add(reg.screenName);
      out.set(target.file, set);
    }
  }
  return out;
}

// ==================== 屏文件里的权限分支 ====================

export interface BranchSite {
  line: number;
  /** 出现位置的语法角色，便于人读 */
  kind: string;
  text: string;
}

export interface Branch {
  file: string;
  key: string;
  expr: Expr;
  sites: BranchSite[];
}

const BOOL_SHAPE_OPS = new Set<ts.SyntaxKind>([
  ts.SyntaxKind.AmpersandAmpersandToken,
  ts.SyntaxKind.BarBarToken,
  ts.SyntaxKind.EqualsEqualsEqualsToken,
  ts.SyntaxKind.EqualsEqualsToken,
  ts.SyntaxKind.ExclamationEqualsEqualsToken,
  ts.SyntaxKind.ExclamationEqualsToken,
]);

/** 「长得像布尔条件」——⛔ 不是「类型是 boolean」，分析器没有类型检查器。 */
function isBooleanShaped(node: ts.Node, ctx: FileCtx, depth = 0): boolean {
  if (depth > 6) return false;
  const n = unwrap(node);
  if (ts.isPrefixUnaryExpression(n) && n.operator === ts.SyntaxKind.ExclamationToken) return true;
  if (ts.isBinaryExpression(n) && BOOL_SHAPE_OPS.has(n.operatorToken.kind)) return true;
  if (ts.isCallExpression(n)) {
    const c = calleeName(n);
    return (
      !!c &&
      (pick(BOOL_HELPERS, c) !== undefined || c === 'Boolean' || c === 'includes' || c === 'has')
    );
  }
  if (ts.isIdentifier(n)) {
    const init = ctx.consts.get(n.text);
    return init ? isBooleanShaped(init, ctx, depth + 1) : false;
  }
  return false;
}

function snippet(node: ts.Node): string {
  let t: string;
  try {
    t = node.getText().replace(/\s+/g, ' ').trim();
  } catch {
    return '<expr>';
  }
  return t.length > 120 ? t.slice(0, 120) + '…' : t;
}

/** 去掉顶层的取反，`locked` 与 `!locked` 归成同一个分支（同一个问题，不是两处）。 */
function dedupeKey(e: Expr): string {
  let cur = e;
  while (cur.k === 'not') cur = cur.a;
  return canon(cur);
}

/** 一个屏文件里所有**角色相关**的门控条件，按语义去重。 */
export function branchesIn(file: string): Branch[] {
  const sf = parseFile(file);
  if (!sf) return [];
  const ctx = fileCtx(file);
  const byKey = new Map<string, Branch>();

  const record = (node: ts.Node, kind: string): void => {
    const expr = toExpr(node, ctx);
    if (!hasRoleAtom(expr)) return;
    const key = dedupeKey(expr);
    const site: BranchSite = { line: lineOf(node), kind, text: snippet(node) };
    const existing = byKey.get(key);
    if (existing) {
      if (!existing.sites.some((s) => s.line === site.line && s.kind === site.kind)) {
        existing.sites.push(site);
      }
      return;
    }
    byKey.set(key, { file, key, expr, sites: [site] });
  };

  const visit = (n: ts.Node): void => {
    if (ts.isIfStatement(n)) record(n.expression, 'if');
    else if (ts.isConditionalExpression(n)) record(n.condition, 'ternary');
    else if (ts.isJsxExpression(n) && n.expression) {
      const e = unwrap(n.expression);
      if (
        ts.isBinaryExpression(e) &&
        (e.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken ||
          e.operatorToken.kind === ts.SyntaxKind.BarBarToken)
      ) {
        record(e.left, 'jsx-guard');
      }
    } else if (ts.isJsxAttribute(n)) {
      const init = n.initializer;
      if (init && ts.isJsxExpression(init) && init.expression) {
        if (isBooleanShaped(init.expression, ctx)) {
          const attrName = ts.isIdentifier(n.name) ? n.name.text : 'prop';
          record(init.expression, 'prop:' + attrName);
        }
      }
    } else if (ts.isVariableDeclaration(n) && n.initializer) {
      if (isBooleanShaped(n.initializer, ctx)) record(n.initializer, 'const');
    } else if (ts.isReturnStatement(n) && n.expression) {
      if (isBooleanShaped(n.expression, ctx)) record(n.expression, 'return');
    } else if (ts.isArrowFunction(n) && !ts.isBlock(n.body)) {
      if (isBooleanShaped(n.body, ctx)) record(n.body, 'arrow');
    }
    ts.forEachChild(n, visit);
  };
  visit(sf);
  return [...byKey.values()];
}

// ==================== 判定 ====================

export type Verdict = 'VACUOUS' | 'EFFECTIVE' | 'UNDECIDED';

export interface Finding {
  file: string;
  relFile: string;
  key: string;
  sites: BranchSite[];
  /** 能走到这屏的角色（已排序） */
  roles: string[];
  /** role -> T/F/U */
  values: Record<string, Tri>;
  verdict: Verdict;
  /** 空转时是恒真还是恒假；有效时给出反例角色对 */
  detail: string;
}

export interface AnalysisResult {
  findings: Finding[];
  vacuous: Finding[];
  effective: Finding[];
  undecided: Finding[];
  /** 仪器自证用 */
  stats: {
    navFiles: number;
    rolesDispatched: number;
    rolesWithNavigator: number;
    reachableScreenFiles: number;
    screensExamined: number;
    branchesExamined: number;
  };
  /** 屏文件 -> 可达角色 */
  rolesByScreenFile: Map<string, string[]>;
}

export function relative(file: string): string {
  return path.relative(SRC_DIR, file).split(path.sep).join('/');
}

export function analyze(): AnalysisResult {
  const dispatch = parseRoleDispatch();
  const rolesByScreenFile = new Map<string, Set<string>>();
  const routesByScreenFile = new Map<string, Set<string>>();
  let rolesWithNavigator = 0;

  for (const d of dispatch) {
    if (d.navigators.length > 0) rolesWithNavigator += 1;
    const reach = reachableScreensFor(d.role, dispatch);
    for (const [file, routes] of reach) {
      const roleSet = rolesByScreenFile.get(file) ?? new Set<string>();
      roleSet.add(d.role);
      rolesByScreenFile.set(file, roleSet);
      const routeSet = routesByScreenFile.get(file) ?? new Set<string>();
      for (const r of routes) routeSet.add(r);
      routesByScreenFile.set(file, routeSet);
    }
  }

  const findings: Finding[] = [];
  let branchesExamined = 0;

  for (const [file, roleSet] of rolesByScreenFile) {
    const roles = [...roleSet].sort();
    for (const branch of branchesIn(file)) {
      branchesExamined += 1;
      const values: Record<string, Tri> = {};
      for (const role of roles) values[role] = evalExpr(branch.expr, userOf(role));
      const distinct = new Set(Object.values(values));
      let verdict: Verdict;
      let detail: string;
      if (roles.length === 0) {
        verdict = 'UNDECIDED';
        detail = '零角色可达 —— 屏级可达性问题, 归另一道闸';
      } else if (distinct.has('U')) {
        verdict = 'UNDECIDED';
        const who = roles.filter((r) => values[r] === 'U');
        detail = '依赖运行时数据或分析器读不懂: ' + who.slice(0, 4).join(', ');
      } else if (distinct.size === 1) {
        verdict = 'VACUOUS';
        detail = distinct.has('T')
          ? '对全部 ' + roles.length + ' 个可达角色恒为真 —— 这道判断从不拒绝任何人'
          : '对全部 ' + roles.length + ' 个可达角色恒为假 —— 被它守着的分支从不执行';
      } else {
        verdict = 'EFFECTIVE';
        const t = roles.find((r) => values[r] === 'T');
        const f = roles.find((r) => values[r] === 'F');
        detail = '举得出反例: ' + String(t) + '=真 / ' + String(f) + '=假';
      }
      findings.push({
        file,
        relFile: relative(file),
        key: branch.key,
        sites: branch.sites.sort((a, b) => a.line - b.line),
        roles,
        values,
        verdict,
        detail,
      });
    }
  }

  findings.sort((a, b) => (a.relFile + a.key).localeCompare(b.relFile + b.key));

  const navFiles = fs.existsSync(NAV_DIR) ? countTsFiles(NAV_DIR) : 0;
  const screensExamined = [...routesByScreenFile.values()].reduce((n, s) => n + s.size, 0);

  return {
    findings,
    vacuous: findings.filter((f) => f.verdict === 'VACUOUS'),
    effective: findings.filter((f) => f.verdict === 'EFFECTIVE'),
    undecided: findings.filter((f) => f.verdict === 'UNDECIDED'),
    stats: {
      navFiles,
      rolesDispatched: dispatch.length,
      rolesWithNavigator,
      reachableScreenFiles: rolesByScreenFile.size,
      screensExamined,
      branchesExamined,
    },
    rolesByScreenFile: new Map(
      [...rolesByScreenFile].map(([f, s]) => [f, [...s].sort()] as [string, string[]]),
    ),
  };
}

function countTsFiles(dir: string): number {
  let n = 0;
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    if (fs.statSync(full).isDirectory()) n += countTsFiles(full);
    else if (/\.tsx?$/.test(name)) n += 1;
  }
  return n;
}

// ==================== 对照用：直接分析一段源码 ====================

/**
 * 阴性对照专用：不经过导航树，直接对一段源码 + 一个给定的可达角色集做判定。
 *
 * ⚠️ 它走的是**同一条** `toExpr` / `evalExpr` / 判定路径 ——
 *    这样对照才在守真正的那段逻辑，而不是守一份复制品（形态 D）。
 */
export function judgeSource(
  code: string,
  roles: string[],
  fileName = '__probe__.tsx',
): Array<{ key: string; verdict: Verdict; values: Record<string, Tri>; detail: string }> {
  const virtual = path.join(SRC_DIR, '__gate_probe__', fileName);
  sourceCache.set(
    virtual,
    ts.createSourceFile(virtual, code, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX),
  );
  ctxCache.delete(virtual);
  const out = branchesIn(virtual).map((branch) => {
    const values: Record<string, Tri> = {};
    for (const role of roles) values[role] = evalExpr(branch.expr, userOf(role));
    const distinct = new Set(Object.values(values));
    let verdict: Verdict;
    let detail: string;
    if (roles.length === 0) {
      verdict = 'UNDECIDED';
      detail = '零角色';
    } else if (distinct.has('U')) {
      verdict = 'UNDECIDED';
      detail = '含 U';
    } else if (distinct.size === 1) {
      verdict = 'VACUOUS';
      detail = distinct.has('T') ? '恒真' : '恒假';
    } else {
      verdict = 'EFFECTIVE';
      detail = '有反例';
    }
    return { key: branch.key, verdict, values, detail };
  });
  sourceCache.delete(virtual);
  ctxCache.delete(virtual);
  return out;
}
