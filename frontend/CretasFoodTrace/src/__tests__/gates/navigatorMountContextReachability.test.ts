/**
 * 挂载上下文可达性 —— 「屏可达 ≠ 分支可达」的机制化。
 *
 * ## 为什么需要这一条（2026-08-27 实测）
 *
 * `FAManagementStackNavigator.tsx:160-362` 有一整块注册，注释写着
 * 「工厂专属路由 - 餐饮版不注册，防止路由泄露」，包在 `{!isRestaurantMode && (…)}` 里。
 *
 * 而这个 navigator 的**唯一**挂载路径是
 *
 *     AppNavigator:92   isRestaurant(user) ? <FactoryAdminNavigator /> : <BossNavigator />
 *     FactoryAdminNavigator:15   <Stack.Screen component={FactoryAdminTabNavigator} />
 *     FactoryAdminTabNavigator:112  <Tab.Screen component={FAManagementStackNavigator} />
 *
 * ⇒ 进到这棵子树里，`isRestaurant(user)` **已经被钉成 true**，
 *   于是 `!isRestaurantMode` 恒为假，那 203 行**一次都没渲染过**。
 *   块里 33 条注册中 **18 个屏名在整个 `src` 里别处 0 处注册** ——
 *   约 1.4 万行真实屏代码（设备管理 / IoT / 质检项配置 / 报工审批），没有任何角色走得到。
 *
 * 这是「功能做好了但用户碰不到」的**二阶**形态：屏注册了，注册所在的**分支**不可达。
 *
 * ## 既有两道闸为什么是绿的
 *
 *  · `screenReachability` 判据 3 是「非 navigation 目录里出现过这个名字字面量」。
 *    `FAManagementScreen.tsx` 里确实有 `navigate('UnifiedDeviceManagement')` ——
 *    **而那行也在一个恒假分支里**。数的是「有没有人提到」，不是「有没有人到得了」。
 *  · `roleReachability` 的 `reachableScreensFor` 会按 `evalExpr(guard, user) === 'F'` 剪枝，
 *    但 `isRestaurant` 不在 `BOOL_HELPERS` 里 ⇒ 守卫求成 `U`（未知）⇒ 不剪。
 *    它的 `parseRoleDispatch` 还**显式**写明了这个盲区：三元的两支都算可达。
 *
 * ## 本闸的判据（与上面两道都不同）
 *
 * **纯矛盾检测，不做用户建模。** 从 `RoleBasedNavigator` 出发按图走，
 * 每条边把「这条边要求为真的守卫」里的原子钉进上下文；到一条注册时，
 * 如果它的守卫在**当前上下文**下可判为 `F`，这条注册在这条路径上是死的。
 * **所有到达它的路径都判 F** ⇒ 这条注册对任何用户都不渲染。
 *
 * ⇒ 因此**没有**「helper 没建模所以误报」这一类假阳性：
 *   没被钉住的原子一律 `U`，`U` 不产生结论。
 *
 * ## ⚠️ 代理性与已知盲区（写明，⛔ 不靠加启发式逼近）
 *
 *  · **同名即同物**：两个文件里的 `isRestaurant(user)` 被 `canon` 成同一个串，
 *    本闸就认为是同一个原子。本仓的 `user` 都来自 `useAuthStore`，成立；
 *    但这是**代理判据**，不是证明。
 *  · **只走合取骨架**：`(A || B)` 里的原子不钉进上下文（钉了就不 sound）。
 *  · **起点是 `RoleBasedNavigator`**：它上面那层（Loading / Auth）不在本闸范围内。
 *  · **动态屏名 / 高阶组件包装**读不出来 —— 与 lib 的盲区 6 同源，那条链在这里断掉。
 *  · **不判权限**：注册了、分支也活着，但当前角色看不到 —— 那是 `roleReachability` 的事。
 *
 * ## 🔴 建闸时定的两条自检（⛔ 不要删）
 *
 * 「没查出死注册」和「仪器根本没跑起来」读数一模一样。所以除了棘轮，
 * 另有两条**必发生**读数：走到的注册数 / 走到的 navigator 数。
 * 它们掉到地板以下 = 仪器死了，与「代码变干净了」完全不同。
 */
import ts from 'typescript';
import {
  APP_NAVIGATOR,
  canon,
  fileCtx,
  findScopeBody,
  guardsAbove,
  parseFile,
  relative,
  resolveComponent,
  screensIn,
} from './lib/roleReachability';
import type { Expr, ScopeKey } from './lib/roleReachability';

/**
 * 死注册预算（棘轮，只许调小）。
 *
 * ## 🔴 2026-08-27 第二次：35 → 1
 *
 * 下面记的那 34 条已经**删掉了**（不是豁免）：
 *
 *   · `FAManagementStackNavigator` 里 `{!isRestaurantMode && (…)}` 整块 33 条注册（203 行）
 *   · `FactoryAdminTabNavigator` 的 `FAReportsTab` 1 条
 *   · 连带 `FAManagementScreen` 里三个同样恒假的入口（设备中心 / 生产报工 / 工序任务）
 *
 * ⛔ **屏文件一个都没删** —— 将来真启用 IoT 时代码还在。删的只是那些永不渲染的注册与入口。
 *
 * 依据是逐个对过的生产数据与 web 端入口：
 *   设备台账 2 屏 → web-admin `/equipment/list` 已有（`factory_equipment` 8 行）
 *   IoT/摄像头/发现/配网/秤/NFC/标签识别 13 屏 → 相关表**生产全 0 行**，web 端也没有
 *   质检项 2 屏 → web-admin `/quality/standards` 已有
 *   报工审批 1 屏 → web-admin `/production/approval` 已有，且 `legacyWorkReportingRatchet`
 *                   写明「第 5 步整屏删掉」
 *
 * ⚠️ 删 `FAReportsTab` 顺带让**整套报表（17 屏 + 2 分析屏）显形为无人可达** ——
 *    它是 `FAReportsStackNavigator` 的唯一挂载点，而那个挂载点恒假。
 *    ⛔ 那 19 个屏不是被这次删掉的，是本来就走不到，此前被 `roleReachability` 的盲区盖着。
 *    详见该闸下调下限处的记录。**报表要不要给工厂用户，是没解决的产品问题。**
 *
 * ---
 *
 * 以下是首次建闸（存量 35）时的记录，留作口径参照：
 *
 * 存量 **35**，两类：
 *
 * ### 34 条 —— `isRestaurant(user)` 恒真的子树里写着 `!isRestaurantMode`
 *
 *     FAManagementStackNavigator.tsx:163-356   33 条（`{!isRestaurantMode && …}` 整块）
 *     FactoryAdminTabNavigator.tsx:83           1 条（`isScreenEnabled('Reports') && !isRestaurantMode`）
 *
 * 其中 **18 个屏名在整个 `src` 里别处 0 处注册**（去重后约 1.4 万行真实屏代码，
 * 零 TODO 占位、多数真调 API）：设备管理 / IoT 设备 / 扫码秤 / 摄像头接入 /
 * 标签识别 / NFC / 质检项配置 / 报工审批。**没有任何角色走得到。**
 *
 * ⛔ 这一版**只登记不擅自接线**。往哪儿接是产品决定：
 *    工厂用户（`factory_super_admin` 非餐饮）当前拿到的是 `BossNavigator`，
 *    那里概览/分析/我的三个 Tab **没有**设备管理、质检项配置、报工审批任何入口。
 *    ⚠️ 另有先例可参考：同一块里的 `ConversionRate` / `MaterialConversionDetail` /
 *    `SopConfig` 已经在 `ManagementStackNavigator` 里另行注册过 ——
 *    **这批迁移做了一半**，15 个有了工厂侧的家，18 个没有。
 *
 * ### 1 条 —— 有意隐藏
 *
 *     WarehouseManagerTabNavigator.tsx:142  WHInventoryTab   `{!SIMPLIFIED_WAREHOUSE_NAV && …}`
 *
 * 那个常量就是 `true`，注释写着「收敛时隐藏 (owner: 不让仓管在 App 上查库存)」。
 * ⛔ **没有为它开白名单** —— 白名单是藏东西的地方；预算里多一条写明理由的已知项，
 *    比多一个没人看的白名单诚实（与 `screenReachability` 对 `Loading` 的处理同一条纪律）。
 *
 * 接好一批就把这个数调小。⛔ 不许调大 —— 调大等于又把一批屏藏起来。
 */
const DEAD_BUDGET = 1;

/** 仪器活着的地板值。⛔ 不是判据，是「必发生」读数（硬约束 9）。 */
const MIN_REGISTRATIONS = 200;
const MIN_NAVIGATORS = 20;

// ==================== 三值 + 上下文 ====================

type Tri = 'T' | 'F' | 'U';
/** canon(原子) -> 在当前挂载上下文里被钉成的值 */
type Ctx = ReadonlyMap<string, boolean>;

/**
 * 可钉的叶子：`atom`（lib 建模过的 helper）**和** `unknown`（没建模的表达式）。
 *
 * 🔴 `unknown` 必须一起钉，否则本闸量不到它要量的东西 —— 实测过：
 *    `isRestaurant` 不在 lib 的 `BOOL_HELPERS` 里，于是 `toExpr` 把
 *    `isRestaurant(user)` 产成 `{k:'unknown', canon:'?{isRestaurant(user)}'}`。
 *    第一版只钉 `atom`，跑出来 **dead=1** —— 手工核实过的那 33 条一条没抓到。
 *
 * ⚠️ 这引入本闸最主要的代理判据：**canon 串相同即同一个值**。
 *    两侧确实都是 `?{isRestaurant(user)}`，`user` 都来自 `useAuthStore`，成立；
 *    但对一般的 `unknown` 这是**假设**不是证明（同名局部 state 会被当成同一个东西）。
 *    ⇒ 所以本闸是**棘轮**、报出的每条都要人过一眼，⛔ 不是硬红。
 */
function leafCanon(e: Expr): string | null {
  if (e.k === 'atom') return e.canon;
  if (e.k === 'unknown') return canon(e);
  return null;
}

/** 把「必须为真」的守卫里的叶子钉进上下文。⛔ 只走合取骨架，`or` 一律不钉。 */
function pinFrom(e: Expr, out: Map<string, boolean>): void {
  if (e.k === 'and') {
    pinFrom(e.a, out);
    pinFrom(e.b, out);
    return;
  }
  const self = leafCanon(e);
  if (self !== null) {
    out.set(self, true);
    return;
  }
  if (e.k === 'not') {
    const neg = leafCanon(e.a);
    if (neg !== null) out.set(neg, false);
  }
}

function evalWithCtx(e: Expr, ctx: Ctx): Tri {
  switch (e.k) {
    case 'const':
      return e.value ? 'T' : 'F';
    case 'atom':
    case 'unknown': {
      const v = ctx.get(leafCanon(e) as string);
      return v === undefined ? 'U' : v ? 'T' : 'F';
    }
    case 'not': {
      const a = evalWithCtx(e.a, ctx);
      return a === 'T' ? 'F' : a === 'F' ? 'T' : 'U';
    }
    case 'and': {
      const a = evalWithCtx(e.a, ctx);
      const b = evalWithCtx(e.b, ctx);
      if (a === 'F' || b === 'F') return 'F';
      if (a === 'T' && b === 'T') return 'T';
      return 'U';
    }
    case 'or': {
      const a = evalWithCtx(e.a, ctx);
      const b = evalWithCtx(e.b, ctx);
      if (a === 'T' || b === 'T') return 'T';
      if (a === 'F' && b === 'F') return 'F';
      return 'U';
    }
    case 'unknown':
      return 'U';
  }
}

/** 上下文的稳定签名，用于图上去重（同一个 navigator 在不同上下文下要各走一遍）。 */
function ctxKey(ctx: Ctx): string {
  return [...ctx.entries()]
    .map(([k, v]) => k + '=' + (v ? '1' : '0'))
    .sort()
    .join(',');
}

// ==================== 起点：角色分流处的裸 JSX ====================

interface Seed {
  nav: ScopeKey;
  ctx: Ctx;
}

/**
 * `RoleBasedNavigator` 里是一串 `return <XNavigator />`（含三元），
 * 不是 `Stack.Screen`，所以 `screensIn` 看不见 —— 这里单独扫一次。
 */
function seeds(): Seed[] {
  const sf = parseFile(APP_NAVIGATOR);
  if (!sf) return [];
  const body = findScopeBody(sf, 'RoleBasedNavigator');
  if (!body) return [];
  const ctx = fileCtx(APP_NAVIGATOR);
  const out: Seed[] = [];

  const take = (open: ts.JsxSelfClosingElement | ts.JsxOpeningElement, node: ts.Node) => {
    const tag = open.tagName;
    if (!ts.isIdentifier(tag)) return;
    if (!/Navigator$/.test(tag.text)) return;
    const target = resolveComponent(APP_NAVIGATOR, tag);
    if (!target) return;
    const pinned = new Map<string, boolean>();
    pinFrom(guardsAbove(node, body, ctx), pinned);
    out.push({ nav: target, ctx: pinned });
  };

  const visit = (n: ts.Node): void => {
    if (ts.isJsxSelfClosingElement(n)) take(n, n);
    if (ts.isJsxElement(n)) take(n.openingElement, n);
    ts.forEachChild(n, visit);
  };
  visit(body);
  return out;
}

// ==================== 图上传播 ====================

interface DeadReg {
  screenName: string;
  where: string;
  guard: string;
  /** 让它判 F 的那个已钉原子 */
  because: string;
}

interface Analysis {
  dead: DeadReg[];
  totalRegistrations: number;
  navigatorsVisited: number;
}

/** 找出上下文里让 `guard` 判 F 的那个原子（报错信息用，取第一个）。 */
function blamedAtom(e: Expr, ctx: Ctx): string | null {
  if (e.k === 'const') return e.value ? null : '字面 false';
  const self = leafCanon(e);
  if (self !== null) {
    return ctx.get(self) === false ? self + ' 被钉成 false' : null;
  }
  if (e.k === 'not') {
    const neg = leafCanon(e.a);
    if (neg !== null) return ctx.get(neg) === true ? neg + ' 被钉成 true' : null;
    if (e.a.k === 'const') return e.a.value ? '守卫写成了 !true（字面恒假）' : null;
    return blamedAtom(e.a, ctx);
  }
  if (e.k === 'and') return blamedAtom(e.a, ctx) ?? blamedAtom(e.b, ctx);
  return null;
}

function analyze(): Analysis {
  /** 注册 id -> 各条到达路径上的判定 */
  const verdicts = new Map<string, Tri[]>();
  const meta = new Map<string, DeadReg>();

  const visited = new Set<string>();
  const queue: Seed[] = seeds();
  let navigatorsVisited = 0;

  while (queue.length > 0) {
    const cur = queue.shift();
    if (!cur) break;
    const key = cur.nav.file + '::' + cur.nav.scope + '||' + ctxKey(cur.ctx);
    if (visited.has(key)) continue;
    visited.add(key);
    navigatorsVisited += 1;

    for (const reg of screensIn(cur.nav)) {
      const id = relative(reg.navFile) + ':' + reg.line + ' ' + reg.screenName;
      const verdict = evalWithCtx(reg.guard, cur.ctx);
      const list = verdicts.get(id) ?? [];
      list.push(verdict);
      verdicts.set(id, list);

      if (verdict === 'F') {
        if (!meta.has(id)) {
          meta.set(id, {
            screenName: reg.screenName,
            where: relative(reg.navFile) + ':' + reg.line,
            guard: canon(reg.guard),
            because: blamedAtom(reg.guard, cur.ctx) ?? '(未定位到具体原子)',
          });
        }
        // ⛔ 这条边是死的，不往下走 —— 走下去会给下游 navigator 造出不存在的上下文
        continue;
      }

      const target = resolveComponent(reg.navFile, reg.componentRef);
      if (!target) continue;
      if (screensIn(target).length === 0) continue; // 叶子屏，不是 navigator

      const next = new Map(cur.ctx);
      pinFrom(reg.guard, next);
      queue.push({ nav: target, ctx: next });
    }
  }

  const dead: DeadReg[] = [];
  for (const [id, list] of verdicts) {
    if (list.length > 0 && list.every((v) => v === 'F')) {
      const m = meta.get(id);
      if (m) dead.push(m);
    }
  }
  dead.sort((a, b) => a.where.localeCompare(b.where));

  return { dead, totalRegistrations: verdicts.size, navigatorsVisited };
}

const RESULT = analyze();

// 🔴 把读数打出来。⛔ 没有这一行，「一条都没查到」和「预算没超」长得一模一样，
//    而前者是仪器死了、后者是代码干净 —— 两个相反的结论共用一个绿。
//
// ⚠️ ⛔ 不要改回 `console.log` —— 本仓 `src/__tests__/setup.ts:7` 把
//    `log/debug/info` 全静默了（只留 error/warn）。第一版就是 console.log，
//    跑出来一个字都没有：**一行讲「不打印就分不清两种绿」的代码，自己没打印出来。**
//    `process.stdout.write` 绕开那个 mock。
process.stdout.write(
  '[NAV_MOUNT_CTX] navigators=' +
    RESULT.navigatorsVisited +
    ' registrations=' +
    RESULT.totalRegistrations +
    ' dead=' +
    RESULT.dead.length +
    ' budget=' +
    DEAD_BUDGET +
    '\n' +
    RESULT.dead
      .map((d) => '    ' + d.where + '  ' + d.screenName + '  [' + d.because + ']')
      .join('\n') +
    '\n',
);

// ==================== 断言 ====================

describe('挂载上下文可达性', () => {
  it('阳性对照 ①：上下文钉住原子后，矛盾的守卫判得出 F（不是 U）', () => {
    const atom: Expr = { k: 'atom', canon: 'isRestaurant(user)', run: () => true };
    const pinnedTrue: Ctx = new Map([['isRestaurant(user)', true]]);

    expect(evalWithCtx({ k: 'not', a: atom }, pinnedTrue)).toBe('F');
    expect(evalWithCtx(atom, pinnedTrue)).toBe('T');
    // 没钉住 -> 不许出结论
    expect(evalWithCtx({ k: 'not', a: atom }, new Map())).toBe('U');
    // and(U, F) = F —— 这是本分析器能对含运行时数据的守卫出结论的原因
    const runtime: Expr = { k: 'unknown', canon: 'someRuntimeFlag' };
    expect(evalWithCtx({ k: 'and', a: runtime, b: { k: 'not', a: atom } }, pinnedTrue)).toBe('F');
    expect(evalWithCtx({ k: 'and', a: runtime, b: atom }, pinnedTrue)).toBe('U');
  });

  it('阳性对照 ②：只钉合取骨架 —— `or` 里的原子不许被钉进上下文', () => {
    const a: Expr = { k: 'atom', canon: 'A', run: () => true };
    const b: Expr = { k: 'atom', canon: 'B', run: () => true };
    const pinned = new Map<string, boolean>();
    pinFrom({ k: 'or', a, b }, pinned);
    expect(pinned.size).toBe(0);

    pinFrom({ k: 'and', a, b: { k: 'not', a: b } }, pinned);
    expect(pinned.get('A')).toBe(true);
    expect(pinned.get('B')).toBe(false);
  });

  it('阳性对照 ③：`unknown`（lib 没建模的表达式）也必须被钉 —— 第一版就是漏了它', () => {
    // 🔴 这条守的是本闸 v1 的那个缺陷: 只钉 `atom` 不钉 `unknown`,
    //    于是 `isRestaurant(user)`（没建模 ⇒ unknown）一个都没钉进上下文,
    //    全闸空转, 跑出 dead=1 而真值是 35。
    //    ⛔ 删掉这条 = 那个缺陷可以无声回来。
    const u: Expr = { k: 'unknown', canon: 'isRestaurant(user)' };
    const pinned = new Map<string, boolean>();
    pinFrom(u, pinned);
    expect(pinned.get(canon(u))).toBe(true);
    expect(evalWithCtx({ k: 'not', a: u }, pinned)).toBe('F');

    // 同一个表达式在另一处被 `toExpr` 产出时 canon 必须相同 —— 本闸的同一性就靠它
    const same: Expr = { k: 'unknown', canon: 'isRestaurant(user)' };
    expect(canon(same)).toBe(canon(u));
  });

  it('阴性对照：没被钉住的东西不许出结论 —— 本闸不做用户建模', () => {
    // 一个从没在任何挂载边上出现过的表达式, 求值必须是 U（而不是 F）。
    // ⛔ 这条守住「没建模的 helper 不产生假阳性」这个设计承诺。
    const never: Expr = { k: 'unknown', canon: 'someFlagNobodyPinned' };
    const ctx: Ctx = new Map([['isRestaurant(user)', true]]);
    expect(evalWithCtx(never, ctx)).toBe('U');
    expect(evalWithCtx({ k: 'not', a: never }, ctx)).toBe('U');
    expect(blamedAtom(never, ctx)).toBeNull();
  });

  it(`仪器活着：走到的注册 ≥ ${MIN_REGISTRATIONS} 条、navigator ≥ ${MIN_NAVIGATORS} 个`, () => {
    // 🔴「一条死注册都没查到」和「图根本没走起来」读数一模一样。
    //    这两个数是那条「必发生」的读数，掉到地板以下就是仪器死了。
    expect(RESULT.navigatorsVisited).toBeGreaterThanOrEqual(MIN_NAVIGATORS);
    expect(RESULT.totalRegistrations).toBeGreaterThanOrEqual(MIN_REGISTRATIONS);
  });

  it(`恒不渲染的屏注册不超过 ${DEAD_BUDGET} 条（只许变少）`, () => {
    if (RESULT.dead.length > DEAD_BUDGET) {
      throw new Error(
        '恒不渲染的屏注册 ' +
          RESULT.dead.length +
          ' 条（预算 ' +
          DEAD_BUDGET +
          '）：\n\n' +
          RESULT.dead
            .map(
              (d) =>
                '  ' +
                d.where +
                '  ' +
                d.screenName +
                '\n    守卫: ' +
                d.guard +
                '\n    死因: ' +
                d.because,
            )
            .join('\n') +
          '\n\n它们注册了，但**注册所在的分支**在任何挂载路径下都不渲染 ⇒ 没有角色走得到。\n' +
          '接好一批就把 DEAD_BUDGET 调小。⛔ 不许调大。',
      );
    }
    expect(RESULT.dead.length).toBeLessThanOrEqual(DEAD_BUDGET);
  });
});
