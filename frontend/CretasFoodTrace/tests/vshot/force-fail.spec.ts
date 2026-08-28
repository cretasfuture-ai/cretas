/**
 * 把接口打成 404，看失败态**是不是真的出得来**。
 *
 * ## 为什么必须这么验
 *
 * 第一轮交接件的「坑③」：字段映射复核屏的修复内容是「接口失败时显示失败态，
 * 不许显示绿色对勾」。部署后重新截图 —— 显示的仍是绿勾、顶部没有红横幅。
 * 因为那次接口**通了**，走的是空态分支，**改的那条路径根本没被走到**。
 *
 * ▎接口通的时候，失败分支永远不会被执行。
 * ▎「看起来正常」和「失败时也正常」是两件事。
 *
 * 第二轮改了两处专门管失败的东西，当时都**没有**在失败下验过：
 *   · 考勤统计：catch 里那套虚构公司(45 人/96.5%/四个部门)换成失败态
 *   · 效率报表：catch 把四项设成 0 —— 「接口失败」和「效率真的是 0」屏上一样
 * 这一轮补上。
 *
 * ## ⛔ 拦截时别把页面导航也拦掉
 *
 * 第一轮踩过：`page.route(/mapping-review/)` 把 `goto('.../mapping-review')` 也拦了，
 * 浏览器直接渲染伪造的 JSON，截图上根本不是应用页面。
 * ⇒ 这里只拦 `/api/` 前缀，并跳过 `document` 类型的请求。
 */
import { test, expect } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { waitForApp, navRefReady, login, authSummary, navigateTo, screenText, navAlive } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const OUT = process.env.VSHOT_OUT ?? 'vshot-forcefail';

/** 只打业务 API，放过 bundle / HMR / 页面导航。 */
async function fail404(page: any, pattern: RegExp) {
  await page.route('**/*', async (route: any) => {
    const req = route.request();
    const url = req.url();
    if (req.resourceType() === 'document') return route.continue();
    if (!url.includes('/api/')) return route.continue();
    if (!pattern.test(url)) return route.continue();
    return route.fulfill({
      status: 404,
      contentType: 'application/json',
      body: JSON.stringify({ success: false, code: 404, message: 'forced-404-by-vshot' }),
    });
  });
}

async function boot(page: any, user: string) {
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, user, '123456');
  const auth = await authSummary(page);
  if (!auth?.isAuthenticated) throw new Error('login did not take for ' + user);
  await navRefReady(page);
  await page.waitForTimeout(5000);
}

test('考勤统计：接口 404 时必须说失败，⛔ 不许再显示那家 45 人的虚构公司', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });
  await boot(page, 'f006_hr_admin'); // 先正常登录, 拦截只针对之后的业务请求
  await fail404(page, /\/attendance|\/time-stats|\/statistics/);

  await navigateTo(page, ['Main', 'AttendanceStats']);
  await page.waitForTimeout(4500);
  await page.screenshot({ path: path.join(OUT, 'ff-01-attendance-404.png') });

  const txt = (await screenText(page)).visible;
  console.log('ATTENDANCE UNDER 404: ' + JSON.stringify(txt.slice(0, 400)));

  // 阳性对照：确实停在这一屏（否则「没有假数据」可能只是因为屏没渲染）
  expect(txt).toContain('统计分析');
  // 失败态真的出得来
  expect(txt).toContain('考勤统计没取到');
  // ⛔ 那套虚构数据一个都不许再出现
  expect(txt).not.toContain('45');
  expect(txt).not.toContain('96.5');
  expect(txt).not.toContain('生产一部');
  // ⛔ 也不许在「拿不到数据」的横幅底下继续给一排读数。
  //    上一版只加了横幅、指标区照旧渲染「0% 平均出勤率 / 0 迟到 / 0 早退 / 0 缺勤」——
  //    那还是编, 而且只有把接口打成 404 才看得出来。
  expect(txt).not.toContain('平均出勤率');
  expect(txt).not.toContain('异常统计');
  expect(txt).not.toContain('部门出勤排行');
  expect(await navAlive(page)).toBe(true);
});

test('成品库存：接口 404 时不许把空列表说成「没有库存」以外的东西', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });
  await boot(page, 'f006_admin');
  await fail404(page, /finished-goods/);

  await navigateTo(page, ['Main', 'BossOverviewTab', 'BossFinishedGoods']);
  await page.waitForTimeout(4500);
  await page.screenshot({ path: path.join(OUT, 'ff-02-finishedgoods-404.png') });

  const txt = (await screenText(page)).visible;
  console.log('FINISHED GOODS UNDER 404: ' + JSON.stringify(txt.slice(0, 400)));
  expect(txt).toContain('成品库存');
  // 接口挂了的时候不许再出现具体品名/数量 —— 那会是上一次的缓存或编出来的
  expect(txt).not.toContain('黄油鸡');
  expect(await navAlive(page)).toBe(true);
});
