/**
 * 逐**角色**把整棵路由树走一遍，数「不带参数进这一屏会不会把整个 App 打崩」。
 *
 * <h3>量的是什么</h3>
 *
 * ⚠️ 不是「用户会这么走」—— 用户从列表点进去总是带 id 的。
 *    量的是**爆炸半径**：一屏出错之后，错误边界是兜住那一屏，
 *    还是把整棵导航树端掉、整个 App 只能重来。
 *    实测（2026-08-29）是后者，所以这一类值得逐屏数一遍。
 *
 * <h3>为什么必须逐角色</h3>
 *
 * `AppNavigator` 按角色分派到不同的 navigator，一个账号只能看到自己那棵树。
 * 只扫 `f006_admin` 得到 43 条，而全仓注册的路由是它的好几倍 ——
 * ▎**「我扫过了」和「我扫全了」是两回事**，分母要跟着角色一起报。
 *
 * <h3>仪器上已经踩过的三个坑（都长成「结论」）</h3>
 *
 * 1. 崩溃恢复要整页 reload，而 reload 会把已挂载的 tab 全清空 ——
 *    按「轮」采集会只发现 12 条就收敛，看起来像「这棵树就这么大」。
 *    ⇒ **每走一步重采一次**，并在 reload 之后把已知容器重挂一遍。
 * 2. Metro 少了 `ENVFILE` 时 `@env` 会空掉 → 登录失败 → 走查停在登录栈，
 *    读数是「5 条全绿」。⇒ 登录后断言**不在登录栈**，否则抛「读数作废」。
 * 3. `login(...).catch(() => {})` 正是把第 2 条藏住的东西。⛔ 不许吞。
 *
 * 起 Metro：`CI=1 ENVFILE=.env.production npx expo start --web --port 3021 --clear`
 */
import { test } from '@playwright/test';
import {
  waitForApp, navRefReady, login, rootState, collectRouteNames, navigateTo,
} from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
// F006 生产库里**真实存在且 is_active** 的角色账号, 逐个走一遍。
//
// 🔴 第一版这个清单是我凭印象写的 10 个, 其中 `f006_operator` **根本不存在**
//    (线上 401), 而漏掉了 cashier / department_admin / dispatcher /
//    equipment_admin / quality_manager / viewer / warehouse_worker 七个角色。
//    ▎「我扫过了」和「我扫全了」是两回事 —— 名单本身也要有出处。
//    出处: SELECT role_code, username FROM users WHERE factory_id='F006' AND is_active;
//
// ⚠️ yield_operator 在 F006 **一个活账号都没有** ⇒ 走不了, 这里显式登记,
//    ⛔ 不要因为它不在清单里就以为它被覆盖了。
const USERS = (process.env.VSHOT_USERS ?? [
  'f006_admin',            // factory_super_admin
  'f006_operator1',        // operator (⚠️ 不是 f006_operator)
  'f006_warehouse_mgr',    // warehouse_manager
  'f006_warehouse_worker', // warehouse_worker
  'f006_workshop',         // workshop_supervisor
  'f006_quality_insp',     // quality_inspector
  'f006_quality_mgr',      // quality_manager
  'f006_production_mgr',   // production_manager
  'f006_procurement_mgr',  // procurement_manager
  'f006_sales_mgr',        // sales_manager
  'f006_finance_mgr',      // finance_manager
  'f006_cashier',          // cashier
  'f006_hr_admin',         // hr_admin
  'f006_dept_admin',       // department_admin
  'f006_dispatcher',       // dispatcher
  'f006_equipment_admin',  // equipment_admin
  'f006_viewer',           // viewer
].join(',')).split(',').map((u: string) => u.trim()).filter(Boolean);

/** 出错的**爆炸半径**：掀掉整棵导航树 vs 只有这一屏。两者必须分开数。 */
type Blast = 'tree' | 'screen';
type Crash = { user: string; route: string; err: string; blast: Blast };

test('逐角色: 不带参数进屏会不会打崩整个 App', async ({ page }) => {
  test.setTimeout(90 * 60_000);

  const errs: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 400)); });
  page.on('dialog', (d) => d.dismiss().catch(() => {}));

  const allCrashes: Crash[] = [];
  const perUser: { user: string; walked: number; ok: number; crashed: number; failed?: string }[] = [];

  for (const user of USERS) {
    const containers: string[] = [];
    const seen = new Set<string>();
    const crashed: Crash[] = [];
    const okRoutes: string[] = [];

    const boot = async () => {
      // 换账号必须清干净 —— 否则拿的是上一个角色的会话，整段读数是错人的树。
      await page.context().clearCookies();
      await page.goto(APP, { waitUntil: 'domcontentloaded' });
      await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); }).catch(() => {});
      await page.goto(APP, { waitUntil: 'domcontentloaded' });
      await waitForApp(page);
      await login(page, user, '123456'); // ⛔ 不许 catch
      await navRefReady(page);
      await page.waitForTimeout(4500);
      const st = await rootState(page);
      const names: string[] = (st && st.routeNames) || [];
      if (names.includes('Login') || names.includes('EnhancedLogin')) {
        throw new Error(`登录后仍在登录栈 —— 读数作废。routeNames=${names.join(',')}`);
      }
    };

    try {
      await boot();
    } catch (e: unknown) {
      const msg = String((e as Error)?.message ?? e).replace(/\s+/g, ' ').slice(0, 160);
      console.log(`\n▓▓ ${user} —— 进不去，这个角色一条都没量到: ${msg}`);
      perUser.push({ user, walked: 0, ok: 0, crashed: 0, failed: msg });
      continue;
    }

    const remount = async () => {
      for (const c of containers) {
        await navigateTo(page, c.split('/')).catch(() => {});
        await page.waitForTimeout(450);
      }
    };
    const recover = async () => {
      const retry = page.getByTestId('app-error-boundary-retry');
      if ((await retry.count()) > 0) { await retry.click().catch(() => {}); await page.waitForTimeout(1800); }
      if ((await rootState(page)) === null) { await boot(); await remount(); }
    };
    const harvest = async (): Promise<string[][]> => {
      const m = collectRouteNames(await rootState(page));
      const fresh: string[][] = [];
      for (const [prefix, names] of m) {
        if (prefix && !containers.includes(prefix)) containers.push(prefix);
        for (const n of names as string[]) {
          const p = [...prefix.split('/').filter(Boolean), n];
          if (!seen.has(p.join('/'))) { seen.add(p.join('/')); fresh.push(p); }
        }
      }
      return fresh;
    };

    console.log(`\n▓▓ ${user}`);
    const queue: string[][] = await harvest();
    let n = 0;
    while (queue.length && n < 400) {
      const t = queue.shift() as string[];
      n += 1;
      const key = t.join('/');
      const mark = errs.length;
      await navigateTo(page, t).catch(() => {});
      await page.waitForTimeout(1000);
      const dead = (await rootState(page)) === null;
      const appBoundary = await page.getByTestId('app-error-boundary-retry').count();
      // ⚠️ 屏级边界接住之后导航树是**活的** —— 只看 app 级边界的话，
      //    这一整类会从读数里消失，「89 → 0」看起来像修好了，其实只是被兜住了。
      //
      // 🔴 必须只数**当前可见**的那一个。React Navigation 在 web 上把已挂载的屏
      //    留在 DOM 里，`getByTestId(...).count()` 会把**上一屏**那个边界一起数上：
      //    实测 operator1 因此从「2 条出错」虚涨成「9 条」，而多出来的 7 条
      //    全是它后面走的屏。—— 与第二轮 `innerText` 串屏是同一个坑。
      // ⛔ `:visible` 不够：react-navigation 把上一屏留在 DOM 里，
      //    它有尺寸、没有 display:none，Playwright 认为它 visible。
      //    用按钮走查里验证过的那套判据：**视口相交 + 祖先没有 aria-hidden**。
      const screenBoundary = await page.evaluate(() => {
        const vh = window.innerHeight;
        const vw = window.innerWidth;
        return Array.from(
          document.querySelectorAll<HTMLElement>('[data-testid="screen-error-boundary"]'),
        ).filter((el) => {
          const r = el.getBoundingClientRect();
          if (r.width < 8 || r.height < 8) return false;
          if (r.bottom <= 0 || r.top >= vh || r.right <= 0 || r.left >= vw) return false;
          for (let a: HTMLElement | null = el; a; a = a.parentElement) {
            const st = getComputedStyle(a);
            if (st.display === 'none' || st.visibility === 'hidden' || Number(st.opacity) === 0) return false;
            if (a.getAttribute('aria-hidden') === 'true') return false;
          }
          return true;
        }).length;
      });
      if (dead || appBoundary > 0 || screenBoundary > 0) {
        const blast: Blast = dead || appBoundary > 0 ? 'tree' : 'screen';
        const e = (errs.slice(mark).find((x) => /TypeError|Cannot|is not a function|undefined is not/.test(x))
          ?? errs.slice(mark)[0] ?? '(没抓到 console error)').replace(/\s+/g, ' ').slice(0, 200);
        crashed.push({ user, route: key, err: e, blast });
        console.log(`  ${blast === 'tree' ? '☠️ 整树' : '▫️ 单屏'} ${key}\n     ${e}`);
        await recover();
      } else {
        okRoutes.push(key);
        queue.push(...(await harvest()));
      }
    }
    const treeN = crashed.filter((c) => c.blast === 'tree').length;
    console.log(`  ${user}: 走过 ${n} | 正常 ${okRoutes.length} | 出错 ${crashed.length}`
      + ` (整树 ${treeN} / 单屏 ${crashed.length - treeN})`);
    perUser.push({ user, walked: n, ok: okRoutes.length, crashed: crashed.length });
    allCrashes.push(...crashed);
  }

  // ⚠️ 分母和读数一起报 —— 「0 崩」和「一条都没走到」长得一模一样。
  console.log('\n══════ 汇总（分母跟着一起看）');
  perUser.forEach((r) => {
    console.log(r.failed
      ? `  ${r.user.padEnd(22)} ⚠️ 没量到: ${r.failed}`
      : `  ${r.user.padEnd(22)} 走过 ${String(r.walked).padStart(3)} | 崩 ${r.crashed}`);
  });
  const walkedTotal = perUser.reduce((a, b) => a + b.walked, 0);
  const tree = allCrashes.filter((c) => c.blast === 'tree');
  const screenOnly = allCrashes.filter((c) => c.blast === 'screen');
  console.log(`\n合计走过 ${walkedTotal} 条 | 出错 ${allCrashes.length} 条`);
  console.log(`  ☠️ 掀掉整棵导航树 ${tree.length} 条 | ▫️ 被屏级边界兜住 ${screenOnly.length} 条`);
  console.log('⚠️ 「被兜住」不等于「修好了」—— 那一屏仍然打不开，只是不再连累别的屏。');
  allCrashes.forEach((c, i) => console.log(
    `${String(i + 1).padStart(2)}. [${c.blast === 'tree' ? '整树' : '单屏'}][${c.user}] ${c.route}\n    ${c.err}`));
});
