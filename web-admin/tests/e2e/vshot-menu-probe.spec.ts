import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect } from '@playwright/test';

/**
 * 把 f006_admin **实际渲染出来的**侧边栏菜单项抓下来。
 *
 * ⚠️ 这一步是为了回答一个二选一的问题，而两个答案的性质完全相反：
 *
 * <pre>
 *   34 条路由返回 403。
 *     · 若它们**在菜单里** ⇒ 34 个「点了就撞墙」的死入口, 是缺陷
 *     · 若它们**不在菜单里** ⇒ 我的路由清单收了菜单外的路由, 不是缺陷
 * </pre>
 *
 * ⛔ 不用「文件大小 ≈ 403」这种代理判据去猜，也不用 menuConfig 源码推 ——
 * menuConfig 里写着的条目会被 module 开关 / 权限再过滤一道，
 * **源码里有 ≠ 用户看得见**（本仓「屏可达 ≠ 分支可达」记过同一形状）。
 * 只有 DOM 里真实渲染出来的那一份说了算。
 */

const HERE = dirname(fileURLToPath(import.meta.url));
const BASE = process.env.VSHOT_BASE || 'http://139.196.165.140:8086';
const USER = process.env.VSHOT_USER || 'f006_admin';
const PASS = process.env.VSHOT_PASS || '123456';

test('dump rendered sidebar', async ({ page }) => {
  test.setTimeout(5 * 60 * 1000);
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.locator('input').first().fill(USER);
  await page.locator('input[type="password"]').first().fill(PASS);
  await page.getByRole('button', { name: /登\s*录|登录/ }).first().click();
  await page.waitForURL(u => !u.pathname.includes('/login'), { timeout: 30_000 });
  await page.waitForTimeout(3000);

  // 把所有折叠的分组展开 —— 折叠着的子项不在 DOM 里就会被漏数,
  // 而「漏数」和「菜单里没有」长得一模一样。
  for (let round = 0; round < 4; round++) {
    const groups = page.locator('.el-sub-menu__title');
    const n = await groups.count();
    for (let i = 0; i < n; i++) {
      try {
        const g = groups.nth(i);
        if (await g.isVisible()) await g.click({ timeout: 2000 });
      } catch { /* 已展开或不可点, 跳过 */ }
    }
    await page.waitForTimeout(800);
  }

  const items = await page.evaluate(() => {
    const out: Array<{ text: string; href: string | null }> = [];
    document.querySelectorAll('.el-menu-item').forEach(el => {
      const text = (el.textContent || '').trim().replace(/\s+/g, ' ');
      const a = el.querySelector('a') as HTMLAnchorElement | null;
      out.push({ text, href: a ? a.getAttribute('href') : null });
    });
    return out;
  });

  // 🔴 阳性对照: 抓不到菜单项就说明选择器或展开逻辑坏了,
  //   而「菜单是空的」和「我没抓到」在下面的比对里长得一模一样。
  expect(items.length, '一个菜单项都没抓到 ⇒ 这份读数无效, 不要拿它下结论').toBeGreaterThan(30);

  writeFileSync(
    resolve(HERE, '../../../../my-prototype-logistics/vshots2/_rendered-menu.json'),
    JSON.stringify(items, null, 1),
    'utf8',
  );
  console.log(`RENDERED_MENU_ITEMS=${items.length}`);
});
