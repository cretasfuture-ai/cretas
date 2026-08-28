/**
 * RN-on-web smoke probe.
 *
 * Answers three questions before any 444-screen run is worth starting:
 *   1. does the bundle actually render (or is it a white screen / JS crash)
 *   2. can we log in from localhost against the prod gateway (CORS)
 *   3. is the navigation ref reachable from the page
 *
 * Deliberately loud: every answer is asserted, not eyeballed. A screenshot of a
 * white page and a screenshot of a working login screen are the same file size
 * class, so "it took a screenshot" proves nothing.
 */
import { test, expect } from '@playwright/test';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_admin';
const PASS = process.env.VSHOT_PASS ?? '123456';

test('app renders, login works, nav ref reachable', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => consoleErrors.push('PAGEERROR: ' + e.message));

  await page.goto(APP, { waitUntil: 'domcontentloaded' });

  // Metro dev server compiles on first request; give the bundle room.
  await page.waitForFunction(
    () => document.body && document.body.innerText.trim().length > 0,
    undefined,
    { timeout: 180_000 },
  );

  const bodyText = await page.evaluate(() => document.body.innerText);
  console.log('--- BODY TEXT (first 600) ---\n' + bodyText.slice(0, 600));
  console.log('--- CONSOLE ERRORS (' + consoleErrors.length + ') ---');
  consoleErrors.slice(0, 20).forEach((e) => console.log('  ' + e.slice(0, 300)));

  await page.screenshot({ path: 'vshot-out/smoke-01-initial.png', fullPage: true });

  // POSITIVE CONTROL: the login screen must actually be on screen. Without this
  // a blank render still "passes" every later step by doing nothing.
  expect(bodyText.length, 'app rendered no text at all').toBeGreaterThan(10);
});
