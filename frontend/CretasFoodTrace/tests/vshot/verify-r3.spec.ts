/**
 * 第三轮改动的**屏上**核对。判据不是「合并了」，是「重新截图看到了」。
 *
 * 覆盖四条：
 *  1. 顶层 ErrorBoundary —— 用第二轮那 46 次白屏的同一个触发条件（无参数进详情屏），
 *     现在应当看到「这一页打不开」而不是一片白。**这一条是本轮最重要的核对**：
 *     它把「整个 App 没了」降级成「这一屏打不开」。
 *  2. 销售单详情的产品名不再是 UUID
 *  3. 质检记录列表不再对没存过评分的记录印「0 分」
 *  4. 本月入职为空时说清是「没人入职」还是「没人填过入职日期」
 */
import { test, expect } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { waitForApp, navRefReady, login, authSummary, navigateTo, currentPath, screenText, navAlive } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const OUT = process.env.VSHOT_OUT ?? 'vshot-r3';

async function boot(page: any, user: string) {
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, user, '123456');
  const auth = await authSummary(page);
  if (!auth?.isAuthenticated) throw new Error('login did not take for ' + user);
  await navRefReady(page);
  await page.waitForTimeout(5000);
  return auth;
}

test('1. 无参数进详情屏：以前整屏白，现在应当出现错误边界', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });
  await boot(page, 'f006_admin');

  // 第二轮就是这条路径把整棵树带走的
  await navigateTo(page, ['Main', 'BossOverviewTab', 'SalesOrderDetail']);
  await page.waitForTimeout(3000);
  await page.screenshot({ path: path.join(OUT, 'r3-01-errorboundary.png') });

  const txt = (await screenText(page)).visible;
  console.log('SCREEN TEXT: ' + JSON.stringify(txt.slice(0, 300)));

  // 阳性对照：边界 UI 真的出现了（⛔ 不是「页面还有点字」就算过）
  expect(txt).toContain('这一页打不开');
  expect(txt).toContain('orderId'); // 原始错误信息带出来了, 便于报障

  // ⚠️ 这里**不能**断言 navAlive()：边界一旦接住，NavigationContainer 就在
  // 被替换掉的那棵子树里，`navigationRef.isReady()` 本来就该是 false。
  // 第一版写成 `expect(await navAlive(page)).toBe(true)` —— 红的是判据不是行为。
  // 真正要证明的是「**能回来**」：按「再试一次」之后 App 恢复可用。
  await page.getByTestId('app-error-boundary-retry').click();
  await page.waitForTimeout(4000);
  const after = (await screenText(page)).visible;
  console.log('AFTER RETRY: ' + JSON.stringify(after.slice(0, 200)));
  expect(after).not.toContain('这一页打不开');
  expect(await navAlive(page)).toBe(true);
});

test('2. 销售单详情的产品名不再是 UUID', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });
  await boot(page, 'f006_admin');
  await navigateTo(page, ['Main', 'BossOverviewTab', 'BossSalesOrders']);
  await page.waitForTimeout(3500);

  // 从列表点进第一张卡（和真实用户一样），而不是按路由名跳
  const before = await currentPath(page);
  await page.evaluate(() => {
    const card = Array.from(document.querySelectorAll<HTMLElement>('*')).find((el) => {
      const r = el.getBoundingClientRect();
      return getComputedStyle(el).cursor === 'pointer' && r.width > 250 && r.height > 80 && (el.innerText ?? '').includes('SO-');
    });
    card?.click();
  });
  await page.waitForTimeout(3500);
  const landed = await currentPath(page);
  expect(landed).not.toBe(before);

  await page.screenshot({ path: path.join(OUT, 'r3-02-salesorderdetail.png') });
  const txt = (await screenText(page)).visible;
  console.log('DETAIL TEXT: ' + JSON.stringify(txt.slice(0, 400)));
  expect(txt).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/);
  expect(txt).toContain('黄油鸡');
});

test('3. 质检记录列表不再给没存过评分的记录印 0 分', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });
  await boot(page, 'f006_quality_insp');
  await navigateTo(page, ['Main', 'QIRecordsTab']);
  await page.waitForTimeout(3500);
  await page.screenshot({ path: path.join(OUT, 'r3-03-qirecords.png') });
  const txt = (await screenText(page)).visible;
  console.log('QIRECORDS TEXT: ' + JSON.stringify(txt.slice(0, 400)));
  // 阳性对照：确实在质检记录屏上（否则 "没有 0 分" 可能只是因为没渲染）
  expect(txt).toContain('检验记录');
  expect(txt).not.toContain('0\n分');
});

test('4. 本月入职为空时说清原因', async ({ page }) => {
  fs.mkdirSync(OUT, { recursive: true });
  await boot(page, 'f006_hr_admin');
  await navigateTo(page, ['Main', 'NewHires']);
  await page.waitForTimeout(3500);
  await page.screenshot({ path: path.join(OUT, 'r3-04-newhires.png') });
  const txt = (await screenText(page)).visible;
  console.log('NEWHIRES TEXT: ' + JSON.stringify(txt.slice(0, 400)));
  expect(txt).toContain('新入职员工');
  // F006 全员 hireDate 为 null ⇒ 不许再拿账号创建时间冒充入职日期
  expect(txt).toContain('没有填入职日期');
});
