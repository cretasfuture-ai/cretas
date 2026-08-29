/**
 * 逐屏**点按钮**走查 —— 前六轮都只做了「打开屏用眼睛看」，一个业务按钮都没点过。
 *
 * <h3>判据（唯一的一条）</h3>
 *
 * ▎**屏上说的** 和 **真的发出去的请求** 对不对得上。
 *
 * 今晚已经抓到三处「弹成功但什么都没提交」（仓储预警处理 / 车间启动设备 / 车间开始生产），
 * 所以判据必须是「有没有真的发出非 GET 请求」，⛔ 不是「屏上说没说成功」。
 *
 * 每次点击记录三件事：
 *   ① 点了什么（按钮文字）
 *   ② 点完之后发出的**非 GET** 请求（方法 / 路径 / 状态码）
 *   ③ 屏上新出现的文字（toast / 弹窗 / 页面变化）
 *
 * 然后交叉：
 *   说了成功 + 0 个非 GET  → 🔴 假成功
 *   说了成功 + 非 2xx      → 🔴 谎报
 *   没说话   + 有非 GET    → ⚠️ 静默写入
 *
 * <h3>安全</h3>
 *
 * F006 是测试租户（owner 明确说过测试数据出现在里面是它的用途），但仍然：
 *   ⛔ 不点任何**破坏性**动作 —— 删除/作废/驳回/停用/清空/重置/退回/撤销/解绑
 *   · 每一条非 GET 都逐条打印，⛔ 不做汇总
 */
import { test, expect } from '@playwright/test';
import { createHash } from 'node:crypto';
import * as fs from 'node:fs';
import * as path from 'node:path';
import {
  waitForApp, navRefReady, login, authSummary, navigateTo, currentPath,
  screenText, rootState, collectRouteNames, navAlive,
} from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_admin';
const OUT = process.env.VSHOT_OUT ?? 'D:/Temp/vshots-buttons';
const MODE = process.env.VSHOT_MODE ?? 'click';
const RESULT = process.env.VSHOT_RESULT ?? path.join(OUT, `button-sweep-${USER}.json`);
const DISCOVERY_SETTLE_MS = Number(process.env.VSHOT_DISCOVERY_SETTLE_MS ?? 900);
const ROUTE_SETTLE_MS = Number(process.env.VSHOT_ROUTE_SETTLE_MS ?? 3500);
const INTERMEDIATE_SETTLE_MS = Number(process.env.VSHOT_INTERMEDIATE_SETTLE_MS ?? 1200);
const SCROLL_SETTLE_MS = Number(process.env.VSHOT_SCROLL_SETTLE_MS ?? 700);
const LOADING_WAIT_MS = Number(process.env.VSHOT_LOADING_WAIT_MS ?? 7000);

/** ⛔ 含这些词的一律不点 —— 不可逆或影响他人。 */
const DESTRUCTIVE = [
  '删除', '作废', '驳回', '停用', '清空', '重置', '退回', '撤销', '解绑',
  '注销', '关单', '报废', '取消订单', '退出', '登出', '召回', '冻结',
];
/** 只在**短文本**上判破坏性 —— 长文本里出现「已取消」这种状态字不该挡住整张卡。 */
const SHORT = 14;

type Fired = { method: string; url: string; status?: number };
type ButtonResult = {
  label: string;
  submitLike: boolean;
  destructive: boolean;
  clicked: boolean;
  navigatedAway?: boolean;
  mutations?: Fired[];
  dialogs?: string[];
  verdict?: string;
};
type RouteResult = {
  target: string;
  landed: string;
  renderText: string;
  rendered: boolean;
  navAlive: boolean;
  screenErrorBoundary: boolean;
  visibleProgressIndicators: number;
  loadingText: boolean;
  screenshot: string;
  screenshotBytes: number;
  screenshotSha256: string;
  visibleI18nKeys: string[];
  buttons: ButtonResult[];
  invalidReason?: string;
  duplicateOf?: string;
};

test('逐屏点按钮：屏上说的 vs 真的发出去的请求', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });
  // 路由来源两种：显式传 VSHOT_ROUTES，或留空让它自己把整棵树走一遍。
  // ⚠️ 手写清单永远只覆盖「我想到的那些屏」——而目标是「所有的按钮」，
  //    所以默认走自动发现（与 walkthrough 用的是同一套 collectRouteNames）。
  const routes = (process.env.VSHOT_ROUTES ?? '').split(';').filter(Boolean);
  const SKIP = (process.env.VSHOT_SKIP ?? '').split(';').filter(Boolean);

  const fired: Fired[] = [];
  page.on('request', (r) => {
    if (r.method() !== 'GET' && r.url().includes('/api/') && !r.url().includes('/auth/')) {
      fired.push({ method: r.method(), url: r.url().split('?')[0] });
    }
  });
  page.on('response', (r) => {
    const m = r.request().method();
    if (m !== 'GET' && r.url().includes('/api/') && !r.url().includes('/auth/')) {
      const hit = fired.find((f) => f.url === r.url().split('?')[0] && f.status === undefined);
      if (hit) hit.status = r.status();
    }
  });

  // 🔴 RN 的 Alert.alert 在 web 上走**浏览器原生对话框** —— Playwright 会自动关掉，
  //    文字根本不进 DOM。而今晚要抓的「弹成功但什么都没提交」正是用 Alert.alert 弹的。
  //    不接这个事件，判据里「说了成功」那一侧对整整一类实现是瞎的。
  const dialogs: string[] = [];
  page.on('dialog', async (d) => {
    dialogs.push(`${d.type()}: ${d.message().replace(/\s+/g, ' ')}`);
    await d.dismiss().catch(() => {});
  });

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, '123456');
  await navRefReady(page);
  await page.waitForTimeout(4000);
  const auth = await authSummary(page);
  expect(auth?.username, '登录后的 auth store 没有用户，整轮读数作废').toBe(USER);
  expect(auth?.factoryId, '登录后的 auth store 没有租户，整轮读数作废').toBe('F006');
  const routeResults: RouteResult[] = [];
  const renderedFingerprints = new Map<string, string>();
  const flush = () => fs.writeFileSync(
    RESULT,
    JSON.stringify({ user: USER, auth, mode: MODE, routes: routeResults, fired }, null, 2),
    'utf8',
  );

  if (routes.length === 0) {
    // 逐步 BFS: 每走一步重新采一次。
    // ⚠️ 不能按「轮」采 —— 崩溃恢复要整页 reload, 而 reload 会把已挂载的 tab 全清空,
    //    第一版因此只发现 12 条就收敛了, 看起来像「这棵树就这么大」。
    const seen = new Set<string>();
    const collect = async (): Promise<string[][]> => {
      const m = collectRouteNames(await rootState(page));
      const fresh: string[][] = [];
      for (const [prefix, names] of m) {
        for (const n of names as string[]) {
          const p2 = [...prefix.split('/').filter(Boolean), n];
          const k = p2.join('/');
          if (!seen.has(k)) { seen.add(k); fresh.push(p2); }
        }
      }
      return fresh;
    };
    const queue = await collect();
    const leaves: string[] = [];
    const containers = new Set<string>();
    while (queue.length) {
      const t = queue.shift() as string[];
      await navigateTo(page, t).catch(() => {});
      await page.waitForTimeout(DISCOVERY_SETTLE_MS);
      const fresh = await collect();
      if (fresh.length) { containers.add(t.join('/')); queue.push(...fresh); }
      leaves.push(t.join('/'));
    }
    const uniqueLeaves = [...new Set(leaves)];
    let discovered = uniqueLeaves.filter((r: string) =>
      !containers.has(r) && !uniqueLeaves.some((other: string) => other !== r && other.startsWith(`${r}/`)),
    );
    if (SKIP.length) {
      discovered = discovered.filter((r: string) => !SKIP.some((sk: string) => r.includes(sk)));
    }
    routes.push(...discovered);
    console.log(`自动发现 ${routes.length} 条叶子路由（容器 ${containers.size} 条已折叠）`);
  }
  expect(routes.length, '一条路由都没发现 —— 仪器坏了，读数作废').toBeGreaterThan(0);

  for (const route of routes) {
    const routePath = route.split('/');
    // 逐级挂载：懒加载 tab 不先挂上，内层 stack 不存在
    for (let i = 2; i <= routePath.length; i += 1) {
      await navigateTo(page, routePath.slice(0, i));
      await page.waitForTimeout(i === routePath.length ? ROUTE_SETTLE_MS : INTERMEDIATE_SETTLE_MS);
    }

    // 错误边界顶着的话先恢复，否则后面每屏都读到同一张错误页
    const retry = page.getByTestId('app-error-boundary-retry');
    if ((await retry.count()) > 0) {
      await retry.click().catch(() => {});
      await page.waitForTimeout(2000);
      await navigateTo(page, routePath);
      await page.waitForTimeout(3000);
    }

    // 钻进一条真实记录再扫 —— 列表屏上能点的多半只是导航和筛选,
    // 真正的提交按钮在**进到一条单据之后**(实测仓储 5 屏 35 次点击, 0 个非 GET)。
    const drill = process.env.VSHOT_DRILL;
    if (drill) {
      // ⛔ 不要点文字节点 —— RN Web 的 Pressable 接不住(实测钻入后屏还停在列表)。
      //    点它外层的 [role=button]。
      const target = page.locator('[role="button"], button').filter({ hasText: drill }).first();
      if ((await target.count()) > 0) {
        await target.click({ timeout: 8000 }).catch(() => {});
        await page.waitForTimeout(3500);
        console.log(`     ↳ 已钻入「${drill}」`);
      } else {
        console.log(`     ⚠️ 找不到钻入点「${drill}」—— 这一屏的读数只覆盖列表层`);
      }
    }

    // React Navigation 会保留上一屏的滚动容器；不归零会把下一屏标题裁掉，
    // 截图看起来像“页面本来就没有标题”。所有可滚动容器与 window 一并复位。
    await page.evaluate(() => {
      window.scrollTo(0, 0);
      document.querySelectorAll<HTMLElement>('*').forEach((el) => {
        if (el.scrollTop > 0) el.scrollTop = 0;
        if (el.scrollLeft > 0) el.scrollLeft = 0;
      });
    });
    await page.waitForTimeout(150);

    let before = await screenText(page);
    let visibleProgressIndicators = 0;
    let loadingText = false;
    const loadingDeadline = Date.now() + LOADING_WAIT_MS;
    do {
      visibleProgressIndicators = await page.evaluate(() => {
        const vh = window.innerHeight;
        const vw = window.innerWidth;
        return Array.from(document.querySelectorAll<HTMLElement>('[role="progressbar"]'))
          .filter((el) => {
            const r = el.getBoundingClientRect();
            if (r.width < 4 || r.height < 4) return false;
            if (r.bottom <= 0 || r.top >= vh || r.right <= 0 || r.left >= vw) return false;
            for (let a: HTMLElement | null = el; a; a = a.parentElement) {
              const st = getComputedStyle(a);
              if (st.display === 'none' || st.visibility === 'hidden' || Number(st.opacity) === 0) return false;
              if (a.getAttribute('aria-hidden') === 'true') return false;
            }
            return true;
          }).length;
      });
      loadingText = /加载中|正在加载|\bLoading\b/i.test(before.visible);
      if (visibleProgressIndicators === 0 && !loadingText) break;
      await page.waitForTimeout(300);
      before = await screenText(page);
    } while (Date.now() < loadingDeadline);
    const landed = await currentPath(page);
    const alive = await navAlive(page);
    const screenErrorBoundary = await page.evaluate(() => {
      const vh = window.innerHeight;
      const vw = window.innerWidth;
      return Array.from(document.querySelectorAll<HTMLElement>('[data-testid="screen-error-boundary"]'))
        .some((el) => {
          const r = el.getBoundingClientRect();
          if (r.width < 8 || r.height < 8) return false;
          if (r.bottom <= 0 || r.top >= vh || r.right <= 0 || r.left >= vw) return false;
          for (let a: HTMLElement | null = el; a; a = a.parentElement) {
            const st = getComputedStyle(a);
            if (st.display === 'none' || st.visibility === 'hidden' || Number(st.opacity) === 0) return false;
            if (a.getAttribute('aria-hidden') === 'true') return false;
          }
          return true;
        });
    });
    const safeRoute = route.replace(/[^\w.-]+/g, '_');
    const screenshot = path.join(OUT, `${safeRoute}.png`);
    await page.screenshot({ path: screenshot });
    const screenshotBytes = fs.statSync(screenshot).size;
    const screenshotSha256 = createHash('sha256').update(fs.readFileSync(screenshot)).digest('hex');
    const visibleI18nKeys = Array.from(new Set(
      before.visible.match(/\b[a-z][A-Za-z0-9]*(?:\.[A-Za-z][A-Za-z0-9]*)+\b/g) ?? [],
    ));
    const rendered = alive && !screenErrorBoundary && visibleProgressIndicators === 0 && !loadingText
      && before.visible.trim().length >= 20 && screenshotBytes >= 8_000;
    const visibleFingerprint = createHash('sha256')
      .update(before.visible.replace(/\s+/g, ' ').trim())
      .update('\0')
      .update(screenshotSha256)
      .digest('hex');
    const duplicateOf = renderedFingerprints.get(visibleFingerprint);
    const routeResult: RouteResult = {
      target: route,
      landed,
      renderText: before.visible.slice(0, 240).replace(/\s+/g, ' '),
      rendered,
      navAlive: alive,
      screenErrorBoundary,
      visibleProgressIndicators,
      loadingText,
      screenshot,
      screenshotBytes,
      screenshotSha256,
      visibleI18nKeys,
      buttons: [],
    };
    console.log(`\n════ ${route}`);
    console.log(`     实际落点: ${landed}`);
    console.log(`     进屏: ${before.visible.slice(0, 110).replace(/\s+/g, ' ')}`);
    if (landed !== route || !rendered || duplicateOf || visibleI18nKeys.length > 0) {
      routeResult.duplicateOf = duplicateOf;
      routeResult.invalidReason = landed !== route
        ? `目标 ${route}，实际停在 ${landed}`
        : !rendered
          ? `真实渲染门禁失败: alive=${alive} screenError=${screenErrorBoundary} progress=${visibleProgressIndicators} loadingText=${loadingText} text=${before.visible.trim().length} bytes=${screenshotBytes}`
          : duplicateOf
            ? `目标 ${route} 与 ${duplicateOf} 的可见文字和截图完全相同，无法证明目标屏真实渲染`
            : `页面直接显示未翻译 i18n key: ${visibleI18nKeys.join(', ')}`;
      routeResults.push(routeResult);
      flush();
      console.log(`     ⛔ 读数作废: ${routeResult.invalidReason}`);
      continue;
    }
    renderedFingerprints.set(visibleFingerprint, route);

    // ⚠️ 只看首屏会漏掉折叠线以下的按钮 —— 提交按钮往往正在那儿。
    //    实测：阳性对照那一屏只枚举到 1 个（返回箭头），提交按钮在下面。
    //    ⇒ 逐段滚动枚举，按文字去重。
    const collectVisible = async () =>
      page.evaluate((SHORT_LEN) => {
      const out: { idx: number; text: string }[] = [];
      // ⚠️ React Navigation 在 web 上把**已挂载但不在前台**的屏留在 DOM 里。
      //    直接 querySelectorAll 会把首页卡片、别的 tab 的按钮一起数进来 ——
      //    与第二轮 `innerText` 串屏是同一个坑。⇒ 只要**当前视口内可见**的。
      const vh = window.innerHeight;
      const vw = window.innerWidth;
      const els = Array.from(document.querySelectorAll<HTMLElement>('[role="button"], button, [tabindex]'));
      els.forEach((el, i) => {
        const st = getComputedStyle(el);
        if (st.display === 'none' || st.visibility === 'hidden' || Number(st.opacity) === 0) return;
        const r = el.getBoundingClientRect();
        if (r.width < 8 || r.height < 8) return;
        // 必须真的落在视口里（上一屏留在 DOM 里的通常被移出视口或零尺寸）
        if (r.bottom <= 0 || r.top >= vh || r.right <= 0 || r.left >= vw) return;
        // ⛔ 底部 tab 栏是导航不是业务动作 —— 跳过视口最下面那条
        if (r.top > vh - 70) return;
        // 祖先里有 aria-hidden / display:none 的也算不可见
        let a: HTMLElement | null = el.parentElement;
        while (a) {
          const as = getComputedStyle(a);
          if (as.display === 'none' || as.visibility === 'hidden' || a.getAttribute('aria-hidden') === 'true') return;
          a = a.parentElement;
        }
        const txt = (el.innerText || '').trim().replace(/\s+/g, ' ');
        if (!txt || txt.length > 40) return;
        out.push({ idx: i, text: txt });
      });
      // 去重：同文字只点一次
      const seen = new Set<string>();
      return out.filter((b) => (seen.has(b.text) ? false : (seen.add(b.text), true)));
    }, SHORT);

    // 逐段滚动，把整屏的按钮都收上来
    const byText = new Map<string, { idx: number; text: string }>();
    for (let pass = 0; pass < 6; pass += 1) {
      for (const b of await collectVisible()) if (!byText.has(b.text)) byText.set(b.text, b);
      // ⛔ 不能用 window.scrollBy —— RN Web 的 ScrollView 是**内层**滚动容器，
      //    window 根本不动。实测那样滚 6 次仍然只枚举到 1 个按钮。
      //    走查工具里用的是 mouse.wheel（派发真实滚轮事件给光标下的容器）。
      const sizeBefore = byText.size;
      await page.mouse.move(200, 400);
      await page.mouse.wheel(0, 600);
      await page.waitForTimeout(SCROLL_SETTLE_MS);
      for (const b of await collectVisible()) if (!byText.has(b.text)) byText.set(b.text, b);
      if (byText.size === sizeBefore && pass > 0) break; // 连续两轮没有新按钮 = 到底了
    }
    await page.mouse.wheel(0, -6000);
    await page.waitForTimeout(600);
    const buttons = [...byText.values()].slice(0, 14);

    console.log(`     可点按钮 ${buttons.length} 个: ${buttons.map((b) => b.text).join(' | ')}`);

    for (const b of buttons) {
      const isDestructive =
        b.text.length <= SHORT && DESTRUCTIVE.some((w) => b.text.includes(w));
      const label = b.text.replace(/^[-\s]+/, '');
      const isSubmitLike = /^(提交|确认|保存|创建|发起|新增|确定|完成|应用|同步)/.test(label)
        || /(提交|保存|确认)$/.test(label);
      const buttonResult: ButtonResult = {
        label: b.text,
        submitLike: isSubmitLike,
        destructive: isDestructive,
        clicked: false,
      };
      routeResult.buttons.push(buttonResult);
      if (isDestructive) {
        console.log(`     ⛔ 跳过(破坏性): ${b.text}`);
        continue;
      }
      if (MODE === 'inventory') continue;
      const mark = fired.length;
      const dlgMark = dialogs.length;
      const el = page.locator('[role="button"], button, [tabindex]').nth(b.idx);
      await el.click({ timeout: 6000 }).catch(() => {});
      await page.waitForTimeout(2500);

      const after = await screenText(page);
      // 点完屏整个换了 = 这是个导航按钮, 不发请求是正常的, ⛔ 不该报红
      const navigatedAway = after.visible.slice(0, 60) !== before.visible.slice(0, 60);
      const newText = after.visible.replace(before.visible, '').trim().replace(/\s+/g, ' ');
      const mutations = fired.slice(mark);

      const newDialogs = dialogs.slice(dlgMark);
      // 🔴 判据只看**请求侧** —— 因为「屏上说了成功」这一侧在 web 上不可信：
      //    react-native-web 的 `Alert.alert()` 是个**空函数**（node_modules 里就是
      //    `class Alert { static alert() {} }`），全仓 1697 处直接调用它的地方
      //    在 web 上一个字都不显示。
      //    ⚠️ 这不是产品缺陷 —— OTA 只推 android/ios，web 不是交付目标；
      //       但它意味着**这套 web 走查看不见 Alert 传达的任何信息**。
      //
      // ⇒ 可信的问法换成：**一个「提交类」按钮点下去，到底有没有发出非 GET 请求。**
      // ⚠️ 第一版把「入库/出库/完成/新建」也算成提交类 —— 结果「扫码入库」「生产成品入库」
      //    被报红，而它们是纯 `navigation.navigate`，不发请求本来就是对的。
      //    ▎一道会误报的闸最终会被人忽略（本仓形态 E：宁可窄而可信）。
      //    ⇒ 只认**真正表示写入**的动词，⛔ 不认出现在导航标签里的业务名词。
      // ⚠️ 按钮文字常以**图标字形**开头(如 ` 提交入库 (1 行)`), 直接加 `^` 锚点会漏掉 ——
      //    实测「提交入库 (1 行)」因此没被报红, 是一次**静默假阴性**。
      //    ⇒ 先剥掉开头的私有区字形和空白, 再判。
      // ⛔ 不再用「屏上有没有成功字样」当判据。实测它把筛选 tab 的「已完成/历史(20)」
      //    当成了成功提示 —— 而在 web 上 Alert 又是空函数, 这一侧本来就读不全。
      //    ▎一个既漏又误报的判据, 比没有判据更糟。
      const saysOk = newDialogs.some((d) => /成功|已提交|已保存|已创建|已确认/.test(d));
      let verdict = '';
      if (isSubmitLike && mutations.length === 0 && !navigatedAway) {
        verdict = '🔴 提交类按钮, 但一个非 GET 都没发, 也没跳走';
      } else if (mutations.some((m) => (m.status ?? 0) >= 400)) {
        verdict = `🔴 请求失败 ${mutations.find((m) => (m.status ?? 0) >= 400)?.status}`;
      } else if (!saysOk && mutations.length > 0) {
        verdict = '⚠️ 没说话但发生了写入';
      }
      buttonResult.clicked = true;
      buttonResult.navigatedAway = navigatedAway;
      buttonResult.mutations = mutations;
      buttonResult.dialogs = newDialogs;
      buttonResult.verdict = verdict;

      console.log(`     · 点「${b.text}」 → 非GET ${mutations.length} 条${verdict ? '  ' + verdict : ''}`);
      mutations.forEach((m) => console.log(`         ${m.method} ${m.status ?? '?'} ${m.url}`));
      newDialogs.forEach((d) => console.log(`         弹窗: ${d.slice(0, 160)}`));
      if (newText) console.log(`         屏上新增: ${newText.slice(0, 140)}`);

      // 回到这一屏（点击可能跳走了）
      await navigateTo(page, routePath);
      await page.waitForTimeout(1600);
      if (drill) {
        const t = page.locator('[role="button"], button').filter({ hasText: drill }).first();
        if ((await t.count()) > 0) {
          await t.click({ timeout: 6000 }).catch(() => {});
          await page.waitForTimeout(2600);
        }
      }
    }
    routeResults.push(routeResult);
    flush();
  }

  console.log(`\n════ 全程非 GET 请求合计 ${fired.length} 条`);
  fired.forEach((m) => console.log(`   ${m.method} ${m.status ?? '?'} ${m.url}`));
  flush();
  const invalidRoutes = routeResults.filter((r) => r.invalidReason);
  const brokenButtons = routeResults.flatMap((r) => r.buttons)
    .filter((b) => b.verdict?.startsWith('🔴'));
  expect(invalidRoutes, '存在无法证明真实渲染的目标屏，整轮不得报绿').toEqual([]);
  expect(brokenButtons, '存在提交假成功或请求失败按钮，整轮不得报绿').toEqual([]);
});
