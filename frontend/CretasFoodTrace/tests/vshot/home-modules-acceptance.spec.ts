/**
 * 首页「功能模块」宫格行为验收 —— 在 Mac mini 测试环境 (100.85.234.108:10010) 上真跑，⛔ 不写 prod。
 * 对应 BUG-HOME-MODULES-EMPTY-FOR-SALES-20260906。
 *
 * 正向：f006_sales_mgr / f006_finance_mgr 登录后首页有卡（改之前这两个角色都是空宫格）；
 *       随机抽 2 张销售卡点下去，用 **currentPath 证明真的换了屏**（⛔ 不看「点了没报错」）。
 * 阴性：f006_procurement_mgr 也在射程内，一并读一次。
 *
 * ⚠️ 判「卡在不在」不看截图 —— 截图可能截到启动闪屏（2026-08-28 实测 289 张只有 96 个不同图像）。
 *    判据是 testID `home-module-*` 的 DOM 计数 + 一个「一定会发生」的读数：
 *    `home-modules-section` 在有卡时必须存在。
 *
 * 跑法（先起 metro，⚠️ 改了源码必须 --clear 重启，否则拿到旧 bundle）：
 *   ENVFILE=.env.test.local npx expo start --web --port 3021 --clear
 *   npx playwright test tests/vshot/home-modules-acceptance.spec.ts -c tests/vshot/vshot.config.ts
 */
import { test, expect } from '@playwright/test';
import * as fs from 'node:fs';
import { waitForApp, login, authSummary, navRefReady, currentPath } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const OUT = process.env.VSHOT_OUT ?? 'vshot-out/home-modules';
const PASS = process.env.VSHOT_PASS ?? '123456';

interface RoleCase {
  user: string;
  role: string;
  /** 期望能在首页看到的卡（模块 id），来自 screens/main/homeModules.ts */
  expect: string[];
}

const CASES: RoleCase[] = [
  {
    user: 'f006_sales_mgr',
    role: 'sales_manager',
    expect: ['salesOrders', 'customers', 'arAp', 'priceList', 'returnOrders'],
  },
  { user: 'f006_finance_mgr', role: 'finance_manager', expect: ['todoApprovals'] },
  {
    user: 'f006_procurement_mgr',
    role: 'procurement_manager',
    expect: ['purchaseOrders', 'suppliers', 'supplierDelivery', 'purchaseExceptions', 'priceList'],
  },
];

/** 首页上真的渲染出来的模块卡 id（读 DOM 的 testID，⛔ 不读截图）。 */
async function renderedModuleIds(page: any): Promise<string[]> {
  return page.evaluate(() => {
    const out: string[] = [];
    const nodes = document.querySelectorAll('[data-testid^="home-module-"]');
    nodes.forEach((n) => {
      const id = (n as HTMLElement).dataset.testid ?? '';
      out.push(id.replace('home-module-', ''));
    });
    return out;
  });
}

async function sectionPresent(page: any): Promise<boolean> {
  return page.evaluate(
    () => !!document.querySelector('[data-testid="home-modules-section"]')
  );
}

test.beforeAll(() => {
  fs.mkdirSync(OUT, { recursive: true });
});

for (const c of CASES) {
  test(`首页模块卡 — ${c.user} (${c.role})`, async ({ page }) => {
    page.on('console', (m) => {
      if (m.type() === 'error') console.log('BROWSER_ERROR ' + m.text());
    });

    await page.goto(APP, { waitUntil: 'domcontentloaded' });
    await waitForApp(page);
    await login(page, c.user, PASS);
    await navRefReady(page);
    await page.waitForTimeout(4000);

    // 阳性对照：证明登录真的落在这个角色上（⛔ 不靠「登录页消失了」）
    const auth = await authSummary(page);
    console.log(`AUTH ${c.user} -> ${JSON.stringify(auth)}`);
    expect(auth?.isAuthenticated).toBe(true);
    expect(auth?.role).toBe(c.role);

    const path0 = await currentPath(page);
    const ids = await renderedModuleIds(page);
    const hasSection = await sectionPresent(page);
    console.log(`HOME ${c.user} path=${path0} section=${hasSection} cards=${JSON.stringify(ids)}`);
    await page.screenshot({ path: `${OUT}/${c.user}-home.png`, fullPage: true });

    // 🔴 这次缺陷的核心：改之前这里是 0 张。
    expect(ids.length).toBeGreaterThan(0);
    expect(hasSection).toBe(true);
    for (const want of c.expect) expect(ids).toContain(want);
  });
}

test('点两张销售卡 — 必须真的换屏（currentPath 变了）', async ({ page }) => {
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, 'f006_sales_mgr', PASS);
  await navRefReady(page);
  await page.waitForTimeout(4000);

  // (卡片 testID, 点完之后 currentPath 应该以什么结尾)
  const taps: Array<[string, string]> = [
    ['home-module-salesOrders', 'SalesOrderList'],
    ['home-module-customers', 'CustomerManagement'],
  ];

  for (const [testid, expectedScreen] of taps) {
    // 每次都从首页出发
    await page.evaluate(() => {
      const ref = (globalThis as any).__CRETAS_NAV__;
      ref?.navigate('SMHomeTab' as never);
    });
    await page.waitForTimeout(1500);
    const before = await currentPath(page);

    const card = page.getByTestId(testid);
    await expect(card).toHaveCount(1);
    await card.click();
    await page.waitForTimeout(3000);

    const after = await currentPath(page);
    console.log(`TAP ${testid} before=${before} after=${after}`);
    await page.screenshot({ path: `${OUT}/tap-${expectedScreen}.png`, fullPage: true });

    // 「点了没报错」不是判据 —— 要求屏真的变了，且变成了那一屏。
    expect(after).not.toBe(before);
    expect(after).toContain(expectedScreen);
  }
});
