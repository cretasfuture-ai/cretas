/**
 * 「这个账号实际能看到哪些路由」—— 深链导航失败时先跑这个，⛔ 不要照着源码猜路径。
 *
 * 2026-08-28 实测：我按台账写 `Main/WHProfileTab/WHRecallManage` 一路导不过去，
 * 屏幕停在仓储首页。真实路径中间还有一层 `WarehouseManagerMain`：
 *   Main -> WarehouseManagerMain -> WHProfileTab -> WHRecallManage
 *
 * ⚠️ 两件事：
 * ① `navigate()` 对不存在的深链**不报错**，只是停在原地 ——
 *    所以验收脚本里必须有一条「我真的到了那一屏」的阳性对照，
 *    否则「假数据不在屏上」只是因为压根没打开那一屏。
 * ② 懒加载的 tab 要先挂载，它的内层 stack 才会出现在 routeNames 里。
 *    所以这个探针会先逐个 navigate 一遍 tab，再 dump。
 *
 * 换账号：VSHOT_USER=<username> npx playwright test ... routeprobe.spec.ts
 */
import { test } from '@playwright/test';
import { waitForApp, navRefReady, login, rootState, collectRouteNames, navigateTo } from './vshot-lib';
const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
test('这个账号实际能看到哪些路由', async ({ page }) => {
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, process.env.VSHOT_USER ?? 'f006_warehouse_mgr', '123456');
  await navRefReady(page);
  await page.waitForTimeout(3000);
  for (const tab of ['WHProfileTab', 'WHHomeTab', 'WHInboundTab', 'WHOutboundTab']) {
    await navigateTo(page, ['Main', tab]).catch(() => {});
    await page.waitForTimeout(1200);
  }
  const map = collectRouteNames(await rootState(page));
  console.log('=== 导航器 ' + map.size);
  for (const [prefix, names] of map) {
    console.log('   [' + (prefix || '<root>') + '] ' + (names as string[]).join(', '));
  }
});
