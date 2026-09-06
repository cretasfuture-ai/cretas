/**
 * 权限矩阵只读页的视觉验收 (ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906)。
 *
 * ⚠️ 这是一次性验收脚本, 需要一个真在跑的后端 (加拿大 Mac mini 测试环境),
 * 不在 CI 里跑 —— CI 上没有那个后端, 它会红得毫无信息量。
 * 跑法: PM_BASE=http://127.0.0.1:5199 npx playwright test tests/e2e/permission-matrix-acceptance.spec.ts --headed
 */
import { test, expect, type Page } from '@playwright/test';

const BASE = process.env.PM_BASE || 'http://127.0.0.1:5199';
const SHOTS = process.env.PM_SHOTS || 'D:/Temp/claude/pm-shots';

async function login(page: Page, username: string): Promise<void> {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.locator('.login-form input').nth(0).fill(username);
  await page.locator('.login-form input[type="password"], .login-form input').nth(1).fill('123456');
  await Promise.all([
    page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 45000 }).catch(() => null),
    page.locator('.login-button, button').filter({ hasText: /登|录|Login/i }).first().click(),
  ]);
  await expect(page, `${username} 应该离开 /login`).not.toHaveURL(/\/login/);
  await page.waitForTimeout(2500);
}

test('正向: f006_admin 能打开权限矩阵页并看到分歧高亮', async ({ page }) => {
  await login(page, 'f006_admin');

  await page.goto(`${BASE}/system/permission-matrix`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('[data-testid="pm-table"]', { timeout: 45000 });
  await page.waitForTimeout(1500);

  const cells = await page.locator('td.pm-cell').count();
  const diffs = await page.locator('td.pm-cell--diff').count();
  const diffCount = await page.locator('[data-testid="pm-diff-count"]').innerText();
  const offAxis = await page.locator('[data-testid="pm-offaxis-diff-count"]').innerText();
  const cols = await page.locator('thead th.pm-module-col').count();
  // eslint-disable-next-line no-console
  console.log(`PM_CELLS=${cells} PM_DIFF_CELLS=${diffs} PM_DIFF_COUNT=${diffCount} `
    + `PM_OFFAXIS=${offAxis} PM_MODULE_COLS=${cols}`);

  // Y: 一定会发生的读数 —— 表渲染出来了
  expect(cells).toBeGreaterThan(100);
  expect(cols).toBe(20);
  // X: 想知道的读数 —— 高亮真的出现了, 且与页面自己报的条数一致
  expect(diffs).toBeGreaterThan(0);
  expect(diffs).toBe(Number(diffCount));

  await page.screenshot({ path: `${SHOTS}/01-admin-matrix.png`, fullPage: true });

  // 侧边栏里有这一项
  await page.goto(`${BASE}/dashboard`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  await expect(page.locator('a[href="#/system/permission-matrix"], .el-menu-item')
    .filter({ hasText: '权限矩阵' }).first()).toHaveCount(1);
  await page.screenshot({ path: `${SHOTS}/02-admin-menu.png`, fullPage: true });
});

test('阴性对照: f006_sales_mgr 侧边栏没有这一项, 且端点 403', async ({ page }) => {
  await login(page, 'f006_sales_mgr');

  await page.waitForTimeout(2000);
  const entries = await page.locator('.el-menu-item, .el-sub-menu__title')
    .filter({ hasText: '权限矩阵' }).count();
  // eslint-disable-next-line no-console
  console.log(`SALES_MGR_MENU_ENTRIES=${entries}`);
  expect(entries).toBe(0);

  // 阳性对照: 这个账号的侧边栏**不是空的** —— 否则「没有那一项」可能只是没渲染
  const anyEntry = await page.locator('.el-menu-item, .el-sub-menu__title').count();
  // eslint-disable-next-line no-console
  console.log(`SALES_MGR_TOTAL_MENU_ENTRIES=${anyEntry}`);
  expect(anyEntry).toBeGreaterThan(3);

  await page.screenshot({ path: `${SHOTS}/03-sales-no-entry.png`, fullPage: true });

  const resp = await page.request.get(
    `${BASE}/api/mobile/F006/permissions/matrix`,
    { headers: { Authorization: `Bearer ${await page.evaluate(() => localStorage.getItem('token') || localStorage.getItem('accessToken') || '')}` } },
  );
  const body = await resp.json();
  // eslint-disable-next-line no-console
  console.log(`SALES_MGR_ENDPOINT=${resp.status()} body=${JSON.stringify(body).slice(0, 400)}`);
  expect(resp.status()).toBe(403);
  expect(body.meta.module).toBe('system');
  expect(body.meta.requiredPermissions).toHaveLength(2);

  // 直敲 URL 也进不去 (路由守卫)。
  // ⚠️ goto 是**硬刷新**, SPA 要先启动路由守卫才跑 —— 2.5s 定时等待截到的是启动闪屏
  //    (本仓已有「287 屏验收全是闪屏」的教训)。所以先等应用真的挂载出来, 再读 URL。
  await page.goto(`${BASE}/system/permission-matrix`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(
    () => !document.body.innerText.includes('正在加载应用'),
    undefined,
    { timeout: 60000 },
  );
  await page.waitForTimeout(3000);
  await page.screenshot({ path: `${SHOTS}/04-sales-direct-url.png`, fullPage: true });
  const bodyText = await page.locator('body').innerText();
  // eslint-disable-next-line no-console
  console.log(`SALES_MGR_DIRECT_URL=${page.url()} BODY_HEAD=${bodyText.slice(0, 120).replace(/\s+/g, ' ')}`);
  expect(page.url()).not.toContain('/system/permission-matrix');
});
