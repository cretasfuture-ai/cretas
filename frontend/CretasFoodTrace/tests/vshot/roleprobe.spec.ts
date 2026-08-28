/**
 * Log in as one account and report what the app actually gives that role.
 *
 * Exists because a walkthrough that finds 0 routes reports "DONE screens=0",
 * which reads exactly like "this role has nothing to walk". It could equally be
 * a crash on the landing screen. This probe tells them apart: it screenshots
 * whatever is on the page and dumps the console errors and the raw nav state.
 */
import { test } from '@playwright/test';
import * as fs from 'node:fs';
import { waitForApp, login, authSummary, rootState, screenText } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_warehouse_mgr';
const PASS = process.env.VSHOT_PASS ?? '123456';
const OUT = process.env.VSHOT_OUT ?? 'vshot-out';

test('what does this role land on', async ({ page }) => {
  const errs: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errs.push(m.text().slice(0, 400));
  });
  page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message.slice(0, 400)));

  fs.mkdirSync(OUT, { recursive: true });
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, PASS);
  console.log('AUTH ' + JSON.stringify(await authSummary(page)));

  for (const wait of [3000, 6000, 12000]) {
    await page.waitForTimeout(wait === 3000 ? 3000 : 3000);
    const st = await rootState(page);
    const txt = await screenText(page).catch(() => ({ visible: '<unreadable>', raw: '' }));
    console.log(`--- after ~${wait}ms ---`);
    console.log('  rootState: ' + (st ? JSON.stringify(st).slice(0, 400) : 'NULL (nav ref not ready)'));
    console.log('  visible text: ' + JSON.stringify(txt.visible.slice(0, 300)));
    await page.screenshot({ path: `${OUT}/${USER}-t${wait}.png` });
  }

  console.log('--- CONSOLE ERRORS (' + errs.length + ') ---');
  errs.slice(0, 25).forEach((e) => console.log('  ' + e));
});
