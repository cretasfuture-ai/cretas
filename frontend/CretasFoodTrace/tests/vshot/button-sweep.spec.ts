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
import { waitForApp, navRefReady, login, navigateTo, screenText } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_admin';
const OUT = process.env.VSHOT_OUT ?? 'D:/Temp/vshots-buttons';

/** ⛔ 含这些词的一律不点 —— 不可逆或影响他人。 */
const DESTRUCTIVE = [
  '删除', '作废', '驳回', '停用', '清空', '重置', '退回', '撤销', '解绑',
  '注销', '关单', '报废', '取消订单', '退出', '登出', '召回', '冻结',
];
/** 只在**短文本**上判破坏性 —— 长文本里出现「已取消」这种状态字不该挡住整张卡。 */
const SHORT = 14;

type Fired = { method: string; url: string; status?: number };

test('逐屏点按钮：屏上说的 vs 真的发出去的请求', async ({ page }) => {
  const routes = (process.env.VSHOT_ROUTES ?? '').split(';').filter(Boolean);
  expect(routes.length, '要走的路由由 VSHOT_ROUTES 传入（分号分隔，斜杠分层）').toBeGreaterThan(0);

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

  for (const route of routes) {
    const path = route.split('/');
    // 逐级挂载：懒加载 tab 不先挂上，内层 stack 不存在
    for (let i = 2; i <= path.length; i += 1) {
      await navigateTo(page, path.slice(0, i));
      await page.waitForTimeout(i === path.length ? 3500 : 1200);
    }

    // 错误边界顶着的话先恢复，否则后面每屏都读到同一张错误页
    const retry = page.getByTestId('app-error-boundary-retry');
    if ((await retry.count()) > 0) {
      await retry.click().catch(() => {});
      await page.waitForTimeout(2000);
      await navigateTo(page, path);
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

    const before = await screenText(page);
    console.log(`\n════ ${route}`);
    console.log(`     进屏: ${before.visible.slice(0, 110).replace(/\s+/g, ' ')}`);

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
      await page.waitForTimeout(700);
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
      if (isDestructive) {
        console.log(`     ⛔ 跳过(破坏性): ${b.text}`);
        continue;
      }
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
      const label = b.text.replace(/^[-\s]+/, '');
      const isSubmitLike = /^(提交|确认|保存|创建|发起|新增|确定|完成|应用|同步)/.test(label)
        || /(提交|保存|确认)$/.test(label);
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

      console.log(`     · 点「${b.text}」 → 非GET ${mutations.length} 条${verdict ? '  ' + verdict : ''}`);
      mutations.forEach((m) => console.log(`         ${m.method} ${m.status ?? '?'} ${m.url}`));
      newDialogs.forEach((d) => console.log(`         弹窗: ${d.slice(0, 160)}`));
      if (newText) console.log(`         屏上新增: ${newText.slice(0, 140)}`);

      // 回到这一屏（点击可能跳走了）
      await navigateTo(page, path);
      await page.waitForTimeout(1600);
      if (drill) {
        const t = page.locator('[role="button"], button').filter({ hasText: drill }).first();
        if ((await t.count()) > 0) {
          await t.click({ timeout: 6000 }).catch(() => {});
          await page.waitForTimeout(2600);
        }
      }
    }
    await page.screenshot({ path: `${OUT}/${route.replace(/\//g, '_')}.png`, fullPage: true });
  }

  console.log(`\n════ 全程非 GET 请求合计 ${fired.length} 条`);
  fired.forEach((m) => console.log(`   ${m.method} ${m.status ?? '?'} ${m.url}`));
});
