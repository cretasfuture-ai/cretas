/**
 * Login + navigation-tree probe. Run this before any full walkthrough.
 * It answers: does login work from localhost against the gateway, is the nav
 * ref reachable, and what does the mounted route tree actually look like.
 */
import { test } from '@playwright/test';
import * as fs from 'node:fs';
import { waitForApp, navRefReady, login, authSummary, rootState, collectRouteNames } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_admin';
const PASS = process.env.VSHOT_PASS ?? '123456';
const OUT = process.env.VSHOT_OUT ?? 'vshot-out';

test('probe login and navigation tree', async ({ page }) => {
  const errs: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errs.push(m.text().slice(0, 400));
  });
  page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message.slice(0, 400)));

  const failedReqs: string[] = [];
  page.on('requestfailed', (r) => failedReqs.push(`${r.method()} ${r.url()} :: ${r.failure()?.errorText}`));

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  fs.mkdirSync(OUT, { recursive: true });
  await page.screenshot({ path: `${OUT}/probe-01-login.png`, fullPage: true });

  await login(page, USER, PASS);
  const auth = await authSummary(page);
  console.log('AUTH: ' + JSON.stringify(auth));

  await navRefReady(page);
  // Let the role navigator settle; feature flags load right after login.
  await page.waitForTimeout(6000);
  await page.screenshot({ path: `${OUT}/probe-02-home.png`, fullPage: true });

  const st = await rootState(page);
  fs.writeFileSync(`${OUT}/probe-rootstate.json`, JSON.stringify(st, null, 2), 'utf8');
  const names = collectRouteNames(st);
  console.log('--- MOUNTED NAVIGATORS ---');
  for (const [path, routes] of names) {
    console.log(`  [${path || '<root>'}] (${routes.length}) ${routes.join(', ')}`);
  }

  console.log('--- CONSOLE ERRORS (' + errs.length + ') ---');
  errs.slice(0, 25).forEach((e) => console.log('  ' + e));
  console.log('--- FAILED REQUESTS (' + failedReqs.length + ') ---');
  failedReqs.slice(0, 25).forEach((e) => console.log('  ' + e));
});
