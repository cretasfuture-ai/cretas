/**
 * SmartBI routes that do not exist must read as "not launched", not as a
 * transient outage.  The 404 is injected before navigation so this test
 * exercises the otherwise unreachable catch branch even after backend rollout.
 */
import { expect, test } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';

import {
  authSummary,
  collectRouteNames,
  login,
  navigateTo,
  navRefReady,
  rootState,
  screenText,
  waitForApp,
} from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3022';
const USER = process.env.VSHOT_USER;
const PASS = process.env.VSHOT_PASS;
const OUT = process.env.VSHOT_OUT ?? 'vshot-out/smartbi-404-unavailable';

test.beforeAll(() => {
  if (!USER || !PASS) {
    throw new Error('VSHOT_USER and VSHOT_PASS are required');
  }
});

test('sales and finance 404 states say not launched and never ask for retry', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER!, PASS!);
  await navRefReady(page);

  const auth = await authSummary(page);
  expect(auth?.factoryId, 'login must prove the F006 test tenant before acceptance').toBe('F006');

  let bossPrefix: string[] | null = null;
  await expect.poll(async () => {
    const routes = collectRouteNames(await rootState(page));
    const match = [...routes].find(([, names]) => names.includes('BossAnalysisTab'));
    bossPrefix = match ? match[0].split('/').filter(Boolean) : null;
    return bossPrefix !== null;
  }, { timeout: 30_000 }).toBe(true);

  const hits: Record<'sales' | 'finance', number> = { sales: 0, finance: 0 };
  await page.route(/\/api\/mobile\/F006\/smart-bi\/analysis\/(sales|finance)(?:\?|$)/, async (route) => {
    const feature = /\/analysis\/(sales|finance)/.exec(route.request().url())?.[1] as 'sales' | 'finance';
    hits[feature] += 1;
    await route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({ success: false, message: '请求的资源不存在' }),
    });
  });

  for (const scenario of [
    { route: 'SalesAnalysis', expected: '销售分析还没上线', file: 'sales-404.png', key: 'sales' as const },
    { route: 'FinanceAnalysis', expected: '财务分析还没上线', file: 'finance-404.png', key: 'finance' as const },
  ]) {
    const result = await navigateTo(page, [...bossPrefix!, 'BossAnalysisTab', scenario.route]);
    expect(result.ok, `navigation to ${scenario.route} must succeed`).toBe(true);
    await page.waitForTimeout(3000);

    const text = (await screenText(page)).visible;
    expect(hits[scenario.key], `${scenario.key} 404 route must actually be exercised`).toBeGreaterThan(0);
    expect(text).toContain(scenario.expected);
    expect(text).not.toContain('请重试');
    fs.writeFileSync(path.join(OUT, `${scenario.key}.txt`), text, 'utf8');
    await page.screenshot({ path: path.join(OUT, scenario.file), fullPage: true });
  }
});
