import { test } from '@playwright/test';
import { waitForApp, navRefReady, login, rootState, collectRouteNames, navigateTo } from './vshot-lib';
const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
test('dump 全部路由', async ({ page }) => {
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, process.env.VSHOT_USER ?? 'f006_admin', '123456');
  await navRefReady(page);
  await page.waitForTimeout(3000);
  // 先把顶层 tab 逐个挂载, 内层 stack 才会出现
  let map = collectRouteNames(await rootState(page));
  for (const [prefix, names] of [...map]) {
    for (const n of names as string[]) {
      await navigateTo(page, [...prefix.split('/').filter(Boolean), n]).catch(() => {});
      await page.waitForTimeout(700);
    }
  }
  map = collectRouteNames(await rootState(page));
  for (const [prefix, names] of map) {
    console.log(`[${prefix || '<root>'}] ${(names as string[]).join(', ')}`);
  }
});
