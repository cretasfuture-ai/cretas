import { expect, test } from '@playwright/test';
import { login, waitForApp } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_admin';
const PASS = process.env.VSHOT_PASS ?? '123456';
const EXPECTED_PERMISSION = process.env.VSHOT_EXPECTED_PERMISSION;

test('stores the backend permission array without replacing it', async ({ page }) => {
  expect(EXPECTED_PERMISSION, 'VSHOT_EXPECTED_PERMISSION is required').toBeTruthy();

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, PASS);

  const stored = await page.evaluate(() => {
    for (let index = 0; index < globalThis.localStorage.length; index += 1) {
      const key = globalThis.localStorage.key(index);
      if (!key?.includes('auth-storage')) continue;
      const state = JSON.parse(globalThis.localStorage.getItem(key) as string).state;
      const user = state?.user;
      if (!user) continue;
      return user.userType === 'platform'
        ? user.platformUser?.permissions
        : user.factoryUser?.permissions;
    }
    return null;
  });

  expect(stored).toEqual([EXPECTED_PERMISSION]);
});
