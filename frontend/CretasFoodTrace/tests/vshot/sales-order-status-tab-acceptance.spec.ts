/**
 * BUG-SALES-ORDER-LIST-STATUS-TAB 验收 —— 在 Mac mini 测试环境 (100.85.234.108:10010)
 * 上真点一遍销售单列表的状态 tab, ⛔ 不写 prod (全程只读 GET/POST list-summary)。
 *
 * 判据(每个 tab 两个数一起贴, 缺一个都不算验收):
 *   a. 列表里渲染出来的卡片, **状态全部等于**当前 tab;
 *   b. 卡片数 == 底部合计「共 N 条」—— 这正是用户眼睛做的那次读数;
 *   c. 阴性对照: 同一个 token 直接打修复前那个 URL (/orders?status=X),
 *      它必须仍然返回全量 —— 证明「tab 现在筛对了」不是因为服务端本来就筛,
 *      而是因为**换了端点**。⛔ 少了这条, 42→11 也可能是数据碰巧变了。
 *
 * 跑法 (metro 必须先起, 且改完源码要 --clear 重启, 否则拿到的是旧 bundle):
 *   CI=1 ENVFILE=.env.test.local npx expo start --web --port 3027 --clear
 *   VSHOT_APP=http://localhost:3027 npx playwright test \
 *     tests/vshot/sales-order-status-tab-acceptance.spec.ts -c tests/vshot/vshot.config.ts
 */
import { test, expect } from '@playwright/test';
import * as fs from 'node:fs';
import { waitForApp, login, authSummary, navRefReady, screenText, currentPath } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3027';
const OUT = process.env.VSHOT_OUT ?? 'vshot-out/sales-status-tab';
const API = process.env.VSHOT_API ?? 'http://100.85.234.108:10010';
const NAV = '__CRETAS_NAV__';

type TabSpec = { key: string; label: string; status: string | null };

const TABS: TabSpec[] = [
  { key: 'all', label: '全部', status: null },
  { key: 'COMPLETED', label: '已完成', status: 'COMPLETED' },
  { key: 'CONFIRMED', label: '已确认', status: 'CONFIRMED' },
];

/**
 * 跳到销售单列表。
 * ⚠️ 与 short-close 那份同一个坑: 每个角色落在不同的 navigator 里, 顶层没有这个屏名,
 * 而 react-navigation **不报错** —— navigate 返回 ok=true 而屏幕没动, 读数长得像
 * 「屏是空的」。所以逐个 tab 试, 并用 currentPath 证明真到了。
 */
async function gotoSalesOrderList(page: any): Promise<string> {
  await navRefReady(page);
  await page.waitForFunction(
    (nav: string) => {
      const ref = (globalThis as any)[nav];
      if (!ref?.isReady?.()) return false;
      const st = ref.getRootState();
      const main = (st.routes ?? []).find((r: any) => r.name === 'Main');
      return !!(main?.state?.routeNames?.length);
    },
    NAV,
    { timeout: 120_000 },
  );
  const tabs: string[] = await page.evaluate((nav: string) => {
    const ref = (globalThis as any)[nav];
    const st = ref.getRootState();
    const main = (st.routes ?? []).find((r: any) => r.name === 'Main');
    return main?.state?.routeNames ?? [];
  }, NAV);
  console.log('TABS ' + JSON.stringify(tabs));

  for (const tab of tabs) {
    const r = await page.evaluate(
      ({ nav, tab }: any) => {
        const ref = (globalThis as any)[nav];
        try {
          ref.navigate(tab as never, { screen: 'SalesOrderList' } as never);
          return { ok: true };
        } catch (e: any) {
          return { ok: false, error: String(e?.message ?? e) };
        }
      },
      { nav: NAV, tab },
    );
    await page.waitForTimeout(2000);
    const path = await currentPath(page);
    console.log(`TRY tab=${tab} navigate=${JSON.stringify(r)} currentPath=${path}`);
    if (path.endsWith('SalesOrderList')) {
      await page.waitForTimeout(2500);
      return path;
    }
  }
  return await currentPath(page);
}

/**
 * 底部合计里「共 N 条」的 N。
 * ⚠️ 同样只读**看得见的那一个** —— 失焦的旧屏也把自己的合计留在 DOM 里,
 * 全文档取第一个会读到上一屏的数(读数会自洽地错)。
 */
async function readFooterCount(page: any): Promise<number | null> {
  return await page.evaluate(() => {
    const visible = (el: Element) => {
      const r = (el as HTMLElement).getBoundingClientRect();
      if (r.width === 0 || r.height === 0) return false;
      let node: HTMLElement | null = el as HTMLElement;
      while (node) {
        const st = getComputedStyle(node);
        if (st.display === 'none' || st.visibility === 'hidden') return false;
        if (node.getAttribute('aria-hidden') === 'true') return false;
        node = node.parentElement;
      }
      return true;
    };
    const hit = Array.from(document.querySelectorAll('div,span'))
      .filter((el) => /^[\d,]+条$/.test((el.textContent ?? '').trim()))
      .find((el) => visible(el));
    if (!hit) return null;
    return Number((hit.textContent ?? '').replace(/[^\d]/g, ''));
  });
}

/**
 * 当前**可见窗口**里的卡片: { id, status }。
 *
 * 这个仪器坏过两次, 两次的读数都长得像产品缺陷 (实测, 第一轮验收就红在这上面):
 *
 *  ① RN Web 的 Card 把同一个 testID 渲染成**两个**节点: `sales-order-card-<id>`
 *     和 `sales-order-card-<id>-container`。直接数节点得到 76, 而当时可见的只有 38 张
 *     —— 那个 76 不是「多了 38 张卡」, 是仪器在数 DOM 节点。⇒ 这里剔掉 -container。
 *  ② FlatList 在 web 上**虚拟化**: 42 条数据当时只渲染了 38 条 (接口返回的 42 个 id 里
 *     有 4 个在 DOM 中根本不存在)。⇒ 「DOM 里有几张卡」≠「这一档有几条」,
 *     必须滚一遍把窗口挪过去, 取并集 (见 collectCardsByScrolling)。
 */
async function readCards(page: any): Promise<{
  cards: Array<{ id: string; status: string }>;
  rawNodes: number;
  roots: Array<{ visible: boolean; cards: number }>;
}> {
  const LABELS = ['草稿', '已确认', '待财务审核', '财务已批准', '财务已驳回', '处理中', '部分发货', '已完成', '已取消', '短交结案'];
  return await page.evaluate((labels: string[]) => {
    const isVisible = (el: Element) => {
      const r = (el as HTMLElement).getBoundingClientRect();
      if (r.width === 0 || r.height === 0) return false;
      // react-navigation 把失焦的屏留在 DOM 里, 用 display:none / aria-hidden 藏起来
      let node: HTMLElement | null = el as HTMLElement;
      while (node) {
        const st = getComputedStyle(node);
        if (st.display === 'none' || st.visibility === 'hidden') return false;
        if (node.getAttribute('aria-hidden') === 'true') return false;
        node = node.parentElement;
      }
      return true;
    };

    const rootNodes = Array.from(document.querySelectorAll('[data-testid="sales-order-list-screen"]'));
    const cardsIn = (scope: ParentNode) => {
      const byId = new Map<string, string>();
      for (const node of Array.from(scope.querySelectorAll('[data-testid^="sales-order-card-"]'))) {
        const raw = node.getAttribute('data-testid') as string;
        if (raw.endsWith('-container')) continue; // RN Web 每张卡的第二个节点, 数它就翻倍
        const id = raw.replace('sales-order-card-', '');
        const texts = Array.from(node.querySelectorAll('div,span')).map((el) => (el.textContent ?? '').trim());
        const hit = texts.find((t) => labels.includes(t));
        if (!byId.has(id) || (byId.get(id) === '<no-status-chip>' && hit)) {
          byId.set(id, hit ?? '<no-status-chip>');
        }
      }
      return byId;
    };

    const roots = rootNodes.map((r) => ({ visible: isVisible(r), cards: cardsIn(r).size }));
    // ⚠️ 只数**用户看得见的那一屏**。react-navigation 会把之前访问过的屏留在 DOM 里,
    // 全文档数出来的是 76 而当前这屏只有 42 —— 那个 76 是「两屏之和」, 不是用户看到的数。
    const visibleRoot = rootNodes.find((r) => isVisible(r));
    const scope: ParentNode = visibleRoot ?? document;
    const byId = cardsIn(scope);
    return {
      cards: Array.from(byId.entries()).map(([id, status]) => ({ id, status })),
      rawNodes: document.querySelectorAll('[data-testid^="sales-order-card-"]').length,
      roots,
    };
  }, LABELS);
}

/**
 * 滚一遍列表, 把每一屏看到的卡片取并集 —— 这才是「这一档到底有几条」。
 *
 * ⚠️ 形态 A(我看到的是全部吗): 不滚就只能看到虚拟化窗口里的 38 条,
 * 而那个 38 会被当成「列表只有 38 条」写进结论。返回值同时给出
 * firstWindow(第一屏看到几张) 和 total(并集), 两个数一起贴才看得出有没有截断。
 */
async function collectCardsByScrolling(page: any): Promise<{
  cards: Array<{ id: string; status: string }>;
  firstWindow: number;
  steps: number;
}> {
  const seen = new Map<string, string>();
  const first = await readCards(page);
  first.cards.forEach((c) => seen.set(c.id, c.status));
  const firstWindow = first.cards.length;

  let steps = 0;
  let stagnant = 0;
  await page.mouse.move(206, 500);
  for (let i = 0; i < 40; i++) {
    const before = seen.size;
    await page.mouse.wheel(0, 1400);
    await page.waitForTimeout(350);
    const batch = await readCards(page);
    batch.cards.forEach((c) => seen.set(c.id, c.status));
    steps++;
    stagnant = seen.size === before ? stagnant + 1 : 0;
    if (stagnant >= 4) break; // 连续 4 屏没有新卡片 ⇒ 到底了
  }
  // 滚回顶部, 截图才是用户打开这一档时看到的样子
  for (let i = 0; i < 60; i++) await page.mouse.wheel(0, -1400);
  await page.waitForTimeout(800);

  return { cards: Array.from(seen.entries()).map(([id, status]) => ({ id, status })), firstWindow, steps };
}

test.describe.configure({ mode: 'serial' });

test('销售单列表: 每个状态 tab 的卡片状态与底部合计都对得上', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });
  const errs: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 300)); });
  page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message.slice(0, 300)));
  // 硬约束 2/9: 屏幕上的数不能只看屏幕 —— 把列表那条真实请求打出来,
  // 它证明的是「点 tab 之后打的是 by-status 而不是 /orders」。
  const listRequests: string[] = [];
  page.on('request', (r) => {
    const u = r.url();
    if (u.includes('/sales/orders')) { listRequests.push(u); console.log('REQ ' + r.method() + ' ' + u); }
  });

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, 'f006_sales_mgr', '123456');
  console.log('AUTH ' + JSON.stringify(await authSummary(page)));

  const path = await gotoSalesOrderList(page);
  expect(path).toContain('SalesOrderList');
  await page.waitForTimeout(3000);

  const results: Array<Record<string, unknown>> = [];

  for (const tab of TABS) {
    listRequests.length = 0;
    await page.getByTestId(`sales-order-list-status-${tab.key}`).click();
    // 等到那一档的请求真的发出去(否则读到的是上一档的画面)
    await page.waitForTimeout(4000);

    const { roots } = await readCards(page);
    const { cards: cardList, firstWindow, steps } = await collectCardsByScrolling(page);
    const cards = cardList.length;
    const footer = await readFooterCount(page);
    const text = await screenText(page);
    const empty = (text.visible ?? '').includes('暂无销售单');

    await page.screenshot({ path: `${OUT}/${tab.key}.png`, fullPage: true });

    const uniq = Array.from(new Set(cardList.map((c) => c.status)));
    console.log(
      `TAB ${tab.label} cards=${cards} (firstWindow=${firstWindow} scrollSteps=${steps} roots=${JSON.stringify(roots)}) ` +
      `footer=${footer} empty=${empty} statuses=${JSON.stringify(uniq)} reqs=${JSON.stringify(listRequests.slice(-2))}`,
    );
    results.push({ tab: tab.label, cards, firstWindow, steps, roots, footer, empty, statuses: uniq, reqs: [...listRequests] });

    // (b) 卡片数 == 底部「共 N 条」
    expect(footer, `tab=${tab.label} 底部合计读不到`).not.toBeNull();
    expect(cards, `tab=${tab.label} 卡片数 != 底部合计`).toBe(footer);

    if (tab.status) {
      // 点了状态档 ⇒ 打的必须是 by-status
      expect(listRequests.some((u) => u.includes('/orders/by-status') && u.includes(`status=${tab.status}`)),
        `tab=${tab.label} 没打 by-status: ${JSON.stringify(listRequests)}`).toBe(true);
      // (a) 渲染出来的状态全等于该 tab
      for (const s of uniq) {
        expect(s, `tab=${tab.label} 出现了别的状态`).toBe(tab.label);
      }
      // 0 条时必须是空态, ⛔ 不能「共 0 条」下面还摆着卡片(这正是线上那个现象)
      if (footer === 0) {
        expect(cards).toBe(0);
        expect(empty, 'footer=0 时应显示空态「暂无销售单」').toBe(true);
      }
    }
  }

  fs.writeFileSync(`${OUT}/results.json`, JSON.stringify({ results, errs }, null, 2), 'utf8');
  console.log('CONSOLE_ERRORS ' + JSON.stringify(errs.slice(0, 10)));
});

test('阴性对照: 修复前那个 URL (/orders?status=X) 在同一台服务器上仍然返回全量', async ({ request }) => {
  const loginRes = await request.post(`${API}/api/mobile/auth/unified-login`, {
    data: {
      username: 'f006_sales_mgr',
      password: '123456',
      deviceInfo: { deviceId: 'vshot-status-tab', deviceModel: 'probe', platform: 'web', osVersion: '1' },
    },
  });
  expect(loginRes.status()).toBe(200);
  const token = (await loginRes.json()).data.token as string;
  const H = { Authorization: `Bearer ${token}` };

  const all = await (await request.get(`${API}/api/mobile/F006/sales/orders?page=1&size=50`, { headers: H })).json();
  const buggy = await (await request.get(`${API}/api/mobile/F006/sales/orders?status=COMPLETED&page=1&size=50`, { headers: H })).json();
  const fixed = await (await request.get(`${API}/api/mobile/F006/sales/orders/by-status?status=COMPLETED&page=1&size=50`, { headers: H })).json();

  const n = (r: any) => ((r.data && r.data.content) || []).length;
  console.log(`NEGATIVE-CONTROL /orders=${n(all)} /orders?status=COMPLETED=${n(buggy)} /orders/by-status=${n(fixed)}`);

  // 服务端**至今**忽略 /orders 上的 status —— 所以 UI 里那个筛选只可能来自换端点。
  expect(n(buggy)).toBe(n(all));
  expect(n(fixed)).toBeLessThan(n(all));
});
