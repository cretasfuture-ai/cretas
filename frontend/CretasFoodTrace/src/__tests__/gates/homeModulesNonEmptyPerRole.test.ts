/**
 * 首页「功能模块」宫格：**落在共享 HomeScreen 上的每个角色都得有卡，且每张卡点得动**。
 *
 * ## 这道闸守的两个行为（BUG-HOME-MODULES-EMPTY-FOR-SALES-20260906）
 *
 *  1. **不是空的**：`screens/main/HomeScreen.tsx` 被 5 个导航器复用，而它原来那张
 *     5 项的表全部要 `production/warehouse/trace/system` 前缀。后端给每个角色发的是
 *     `<prefix>:*` 一条（`f006_sales_mgr` 实测 `["sales:*"]`）⇒ 落在这屏的 9 个角色里
 *     **7 个筛完是空宫格**。
 *  2. 🔴 **点得动**：光加卡会加出二阶缺陷 —— `ProcessingTab` / `LogisticsTab` /
 *     `ManagementTab` **只在 MainNavigator 里注册过**。所以每一张卡的 `route`
 *     必须在**该角色那棵树的根导航器**里注册，`nestedScreen` 必须在 `route`
 *     指向的那个子导航器里注册。
 *
 * ## ⛔ 不硬编码任何角色名 / 路由名 / 导航器名
 *
 * 角色宇宙来自 `types/auth` 的 `FACTORY_ROLES` / `PLATFORM_ROLES`；
 * 角色 → 导航器来自 `AppNavigator.tsx` 的 AST（`parseRoleDispatch`）；
 * 路由名来自各导航器的 `*.Screen name=` AST（`screensIn`）；
 * 「这个导航器给首页派了哪个模块集」来自 `initialParams` 的 AST。
 * 全部复用 `gates/lib/roleReachability.ts` —— 那是本仓既有的可达性分析器，
 * ⛔ 不在这里再抄一份路由表（形态 D：同一个东西有两份，它一定会漂）。
 *
 * 筛选逻辑也不抄：测试调的 `resolveHomeModules` 就是 HomeScreen 渲染时调的那一个。
 *
 * ## ⚠️ 已登记的「故意为空」（⛔ 不是允许清单，是待办清单）
 *
 * `equipment_admin` / `quality_manager` 走 MainNavigator，而那棵树里
 * **一个 equipment / quality 屏都没注册**（除首页外只有考勤和我的）。
 * 给它们发卡只能发出点不动的卡 ⇒ 本轮不发，靠 HomeScreen 的
 * 「空则整段不渲染」兜住，并在这里显式登记。
 * 这张表是**精确匹配**的：多一个少一个都红 ⇒ 想再放一个角色进来必须改这里。
 */
import fs from 'fs';
import path from 'path';

import {
  ALL_ROLES,
  APP_NAVIGATOR,
  makeUser,
  parseRoleDispatch,
  screensIn,
  resolveComponent,
  evalExpr,
  type ScopeKey,
  type ScreenRegistration,
} from './lib/roleReachability';
import type { FactoryUser, User } from '../../types/auth';
import { getUserRole } from '../../types/auth';
import {
  HOME_MODULE_SETS,
  HOME_MODULE_SETS_MARKER,
  resolveHomeModules,
  toHomeModuleSetId,
  type HomeModuleSetId,
} from '../../screens/main/homeModules';

const HOME_SCREEN_FILE = path.resolve(__dirname, '../../screens/main/HomeScreen.tsx');

/**
 * 已登记的「这棵树里确实没有该角色能去的地方」。
 * ⛔ 精确匹配，往里加一个角色 = 承认又少了一处入口，必须在 PR 里说明。
 */
const KNOWN_EMPTY_ROLES = ['equipment_admin', 'quality_manager'];

/** 工厂特性开关在测试里一律视为启用（store 对未登记的 id 本来就返回 true）。 */
const allModulesEnabled = (): boolean => true;

/** i18n 在这里不求值 —— 只关心筛出来几张卡、指向哪里。 */
const identityT = (key: string): string => key;

/**
 * 改一个工厂用户的 `factoryUser` 字段。
 * ⚠️ `User` 是可辨识联合，直接 spread 会被 TS 判成两个分支的并 ⇒ 先收窄。
 * 收窄失败当场抛 —— 静默退化成 platform 用户会让下面的断言变成另一个东西。
 */
function patchFactoryUser(
  user: User,
  patch: Partial<FactoryUser['factoryUser']>
): FactoryUser {
  if (user.userType !== 'factory') {
    throw new Error('期望工厂用户, 实际是: ' + user.userType);
  }
  return { ...user, factoryUser: { ...user.factoryUser, ...patch } };
}

interface HomeMount {
  role: string;
  /** 该角色的根导航器（AppNavigator 分流到的那个组件） */
  rootNav: ScopeKey;
  /** 首页那条 Screen 注册（含 initialParams） */
  registration: ScreenRegistration;
  moduleSetId: HomeModuleSetId;
  user: User;
}

function isHomeScreen(reg: ScreenRegistration): boolean {
  const target = resolveComponent(reg.navFile, reg.componentRef);
  if (!target) return false;
  return path.resolve(target.file) === HOME_SCREEN_FILE;
}

/**
 * 每个角色：从它的根导航器里找到那条挂共享 HomeScreen 的 Screen。
 * 找不到 ⇒ 这个角色不落在这屏上，不在本闸射程内。
 */
function homeMounts(): HomeMount[] {
  const dispatch = parseRoleDispatch();
  const out: HomeMount[] = [];
  for (const role of ALL_ROLES) {
    const entry = dispatch.find((d) => d.role === role);
    if (!entry) continue;
    const user = makeUser(role);
    for (const rootNav of entry.navigators) {
      for (const reg of screensIn(rootNav)) {
        if (evalExpr(reg.guard, user) === 'F') continue;
        if (!isHomeScreen(reg)) continue;
        out.push({
          role,
          rootNav,
          registration: reg,
          moduleSetId: toHomeModuleSetId(reg.initialParams.moduleSet),
          user,
        });
      }
    }
  }
  return out;
}

/** 某个导航器**根部**、对该 user 不被守卫剪掉的 Screen 名 → 它指向的组件。 */
function rootScreens(nav: ScopeKey, user: User): Map<string, ScopeKey | null> {
  const out = new Map<string, ScopeKey | null>();
  for (const reg of screensIn(nav)) {
    if (evalExpr(reg.guard, user) === 'F') continue;
    out.set(reg.screenName, resolveComponent(reg.navFile, reg.componentRef));
  }
  return out;
}

/** 从某个组件出发，向下把所有子导航器里的 Screen 名收全（对该 user 可达的）。 */
function screensUnder(start: ScopeKey | null, user: User): Set<string> {
  const out = new Set<string>();
  if (!start) return out;
  const seen = new Set<string>();
  const queue: ScopeKey[] = [start];
  while (queue.length > 0) {
    const nav = queue.shift();
    if (!nav) break;
    const key = nav.file + '::' + nav.scope;
    if (seen.has(key)) continue;
    seen.add(key);
    for (const reg of screensIn(nav)) {
      if (evalExpr(reg.guard, user) === 'F') continue;
      out.add(reg.screenName);
      const target = resolveComponent(reg.navFile, reg.componentRef);
      if (target) queue.push(target);
    }
  }
  return out;
}

const MOUNTS = homeMounts();

describe('首页功能模块（每个落在共享 HomeScreen 上的角色都得有卡, 且卡指向本树注册过的路由）', () => {
  it('阳性对照：仪器确实读到了这屏、读到了角色分流、读到了模块集', () => {
    // 🔴 任何一项读空，下面每一条断言都会静默变成恒真式。
    expect(fs.existsSync(HOME_SCREEN_FILE)).toBe(true);
    expect(fs.existsSync(APP_NAVIGATOR)).toBe(true);
    expect(ALL_ROLES.length).toBeGreaterThanOrEqual(20);

    // 共享 HomeScreen 被 5 个导航器注册（源码里数得到），
    // 落到它上面的角色是 9 个。这两个数一起掉下来 = 分析器瞎了。
    const navFiles = new Set(MOUNTS.map((m) => path.basename(m.registration.navFile)));
    // ⚠️ 用 warn 不用 log：`__tests__/setup.ts` 把 console.log 静默了，
    //    读数打不出来 = 形态 C″ 里「分不清断言在守空气还是变异没生效」。
    // eslint-disable-next-line no-console
    console.warn(
      '[home-modules] marker=' + HOME_MODULE_SETS_MARKER +
      ' 挂载导航器=' + [...navFiles].sort().join(',') +
      ' 角色数=' + MOUNTS.length
    );
    expect(navFiles.size).toBe(5);
    expect(MOUNTS.length).toBe(9);
  });

  it('每个挂载点都显式声明了 moduleSet（⛔ 不许靠默认值蒙混）', () => {
    const missing = MOUNTS.filter((m) => !m.registration.initialParams.moduleSet);
    expect(
      missing.map((m) => m.role + ' @ ' + path.basename(m.registration.navFile))
    ).toEqual([]);
    // 声明的值必须是真的模块集 id，写错一个字母会被 toHomeModuleSetId 兜回 'main'
    for (const m of MOUNTS) {
      expect(Object.keys(HOME_MODULE_SETS)).toContain(m.registration.initialParams.moduleSet);
    }
  });

  it('🔴 每个角色至少解析出 1 张卡（已登记的空角色除外）', () => {
    const counts: string[] = [];
    const empty: string[] = [];
    for (const m of MOUNTS) {
      const modules = resolveHomeModules(m.moduleSetId, m.user, allModulesEnabled, identityT);
      counts.push(
        m.role + '(' + m.moduleSetId + ')=' + modules.length +
        ' [' + modules.map((x) => x.id).join(',') + ']'
      );
      if (modules.length === 0) empty.push(m.role);
    }
    // 形态 C″：先把读数打出来 —— 变异不红时要分得清「断言在守空气」还是「变异没生效」。
    // ⚠️ 用 warn 不用 log：`__tests__/setup.ts` 把 console.log 静默了，
    //    读数打不出来 = 形态 C″ 里「分不清断言在守空气还是变异没生效」。
    // eslint-disable-next-line no-console
    console.warn('[home-modules] 每角色卡片数:\n  ' + counts.join('\n  '));
    expect(empty.sort()).toEqual([...KNOWN_EMPTY_ROLES].sort());
  });

  it('🔴 每张卡的 route / nestedScreen 都在该角色那棵树里注册过', () => {
    const problems: string[] = [];
    const checked: string[] = [];
    for (const m of MOUNTS) {
      const roots = rootScreens(m.rootNav, m.user);
      const modules = resolveHomeModules(m.moduleSetId, m.user, allModulesEnabled, identityT);
      for (const mod of modules) {
        if (!mod.route) continue; // 无路由的卡（settings）走 Alert，不导航
        if (!roots.has(mod.route)) {
          problems.push(
            m.role + ' / ' + mod.id + ': route "' + mod.route +
            '" 不在 ' + m.rootNav.scope + ' 的根部（有的是: ' + [...roots.keys()].join(',') + '）'
          );
          continue;
        }
        checked.push(m.role + ':' + mod.id + '->' + mod.route);
        if (!mod.nestedScreen) continue;
        const under = screensUnder(roots.get(mod.route) ?? null, m.user);
        if (!under.has(mod.nestedScreen)) {
          problems.push(
            m.role + ' / ' + mod.id + ': nestedScreen "' + mod.nestedScreen +
            '" 不在 ' + mod.route + ' 底下（有的是: ' + [...under].join(',') + '）'
          );
        }
      }
    }
    // ⚠️ 用 warn 不用 log：`__tests__/setup.ts` 把 console.log 静默了，
    //    读数打不出来 = 形态 C″ 里「分不清断言在守空气还是变异没生效」。
    // eslint-disable-next-line no-console
    console.warn('[home-modules] 校验过的 route 数=' + checked.length);
    // 阳性对照：不能一条都没校验到 —— 那时候 `problems` 空得毫无意义。
    expect(checked.length).toBeGreaterThanOrEqual(20);
    expect(problems).toEqual([]);
  });

  it('阳性对照：permission_admin(*:*) 在 MainNavigator 上拿到全部 5 张卡（无生产能力时 4 张）', () => {
    const m = MOUNTS.find((x) => x.role === 'permission_admin');
    expect(m).toBeDefined();
    if (!m) return;
    expect(m.moduleSetId).toBe('main');
    expect(getUserRole(m.user)).toBe('permission_admin');

    const full = resolveHomeModules('main', m.user, allModulesEnabled, identityT);
    expect(full).toHaveLength(5);

    // 餐饮/总部/分店没有生产能力 ⇒ processing 那张掉出去，其余 4 张还在。
    const restaurant = patchFactoryUser(m.user, { factoryType: 'RESTAURANT' });
    const reduced = resolveHomeModules('main', restaurant, allModulesEnabled, identityT);
    expect(reduced).toHaveLength(4);
    expect(reduced.map((x) => x.id)).not.toContain('processing');
  });

  it('阴性对照：权限为空的用户在任何模块集上都是 0 张卡', () => {
    for (const setId of Object.keys(HOME_MODULE_SETS) as HomeModuleSetId[]) {
      const stripped = patchFactoryUser(makeUser('sales_manager'), { permissions: [] });
      const modules = resolveHomeModules(setId, stripped, allModulesEnabled, identityT);
      expect({ setId, count: modules.length }).toEqual({ setId, count: 0 });
    }
    // 阴性对照的阴性对照：同一个仪器在权限齐全时**必须**数出非 0，
    // 否则上面那圈 0 只证明 resolveHomeModules 坏了（硬约束 9）。
    const ok = resolveHomeModules('sales', makeUser('sales_manager'), allModulesEnabled, identityT);
    expect(ok.length).toBeGreaterThan(0);
  });

  it('采购主管仅有采购权限时不显示需要仓储或财务权限的送货签收入口', () => {
    const user = patchFactoryUser(makeUser('procurement_manager'), {
      modulePermissions: { procurement: 'rw', warehouse: '-', finance: '-' },
    });
    const ids = resolveHomeModules('procurement', user, allModulesEnabled, identityT).map(m => m.id);
    expect(ids).toContain('purchaseOrders');
    expect(ids).not.toContain('supplierDelivery');
  });

  it.each(['warehouse', 'finance'])('采购主管显式有%s读权时仍可看送货签收入口', module => {
    const user = patchFactoryUser(makeUser('procurement_manager'), {
      modulePermissions: { procurement: 'rw', [module]: 'r' },
    });
    expect(resolveHomeModules('procurement', user, allModulesEnabled, identityT).map(m => m.id))
      .toContain('supplierDelivery');
  });

  it('阴性对照：工厂特性关掉某个模块时它不出现（⛔ 证明这个开关不是摆设）', () => {
    const user = makeUser('sales_manager');
    const all = resolveHomeModules('sales', user, allModulesEnabled, identityT);
    expect(all.length).toBeGreaterThan(0); // 阳性对照：没卡可关等于没测
    const victim = all[0]!.id;
    const withoutVictim = resolveHomeModules(
      'sales',
      user,
      (id) => id !== victim,
      identityT
    );
    expect(withoutVictim.map((x) => x.id)).not.toContain(victim);
    expect(withoutVictim).toHaveLength(all.length - 1);
  });

  it('每个模块的 i18n key 在 zh-CN 与 en-US 的 home.json 里都取得到', () => {
    // ⚠️ `i18nKeysResolve` 那道闸只认**字面量** `t('…')` 且只查 zh-CN；
    //    这里的 key 存在配置对象里 ⇒ 它看不见。所以两个 locale 在这里各查一遍。
    const bundles: Record<string, unknown> = {};
    for (const locale of ['zh-CN', 'en-US']) {
      const p = path.resolve(__dirname, '../../i18n/locales/' + locale + '/home.json');
      expect(fs.existsSync(p)).toBe(true);
      bundles[locale] = JSON.parse(fs.readFileSync(p, 'utf8'));
    }
    const lookup = (bundle: unknown, key: string): unknown =>
      key.split('.').reduce<unknown>(
        (acc, part) =>
          acc && typeof acc === 'object' ? (acc as Record<string, unknown>)[part] : undefined,
        bundle
      );

    const missing: string[] = [];
    let checked = 0;
    for (const [setId, specs] of Object.entries(HOME_MODULE_SETS)) {
      for (const spec of specs) {
        for (const key of [spec.nameKey, spec.descriptionKey]) {
          for (const locale of ['zh-CN', 'en-US']) {
            checked += 1;
            const v = lookup(bundles[locale], key);
            if (typeof v !== 'string' || v.length === 0) {
              missing.push(locale + ' ' + setId + '/' + spec.id + ' ' + key);
            }
          }
        }
      }
    }
    // ⚠️ 用 warn 不用 log：`__tests__/setup.ts` 把 console.log 静默了，
    //    读数打不出来 = 形态 C″ 里「分不清断言在守空气还是变异没生效」。
    // eslint-disable-next-line no-console
    console.warn('[home-modules] i18n 查了 ' + checked + ' 次, 缺 ' + missing.length + ' 条');
    expect(checked).toBeGreaterThanOrEqual(80); // 阳性对照：不能一次都没查
    expect(missing).toEqual([]);
  });
});
