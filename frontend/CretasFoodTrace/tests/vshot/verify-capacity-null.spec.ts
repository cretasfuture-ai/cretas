/**
 * 产能利用率屏：**没有真实产能时，屏上要说「算不出」，⛔ 不是 0.0%**。
 *
 * <h3>为什么必须打桩才验得到（走查三条约束之一）</h3>
 *
 * ▎接口通的时候那条路径根本没被走到。
 *
 * F006 恰好配了产能（产线小时产能 50 × 工厂工时 10 = 500），所以线上永远走
 * 「算得出」那一支。要验「算不出」那一支，只能把响应改成 `null` 再看屏。
 *
 * <h3>背景：这一屏原来在编数</h3>
 *
 * 后端 `getCapacityUtilizationReport` 里写死了 <b>三个</b>数：
 * <ul>
 *   <li>日产能 {@code new BigDecimal("1000")} —— 与这家工厂无关。
 *       F006 真值 500 ⇒ 线上 2026-08-24 output=21 报 <b>2.1%</b>，真值是 <b>4.2%</b></li>
 *   <li>设备计划工时 {@code totalDays * 8} —— F006 真实工时是 10</li>
 *   <li>目标利用率 {@code 80} —— 库里<b>没有任何来源</b></li>
 * </ul>
 * 前端另有一份自己的 {@code TARGET_UTILIZATION = 80}（形态 D：同一个东西两份）。
 *
 * <h3>判据</h3>
 *
 * 每条正向断言都配一条能让它红的对照 —— 只断言「出现了『算不出』」的话，
 * 一屏白板也能过。
 */
import { test, expect } from '@playwright/test';
import { waitForApp, navRefReady, login, navigateTo, screenText } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_admin';
const ROUTE = ['Main', 'BossReportsTab', 'CapacityUtilizationReport'];

/** 把 capacity-utilization 的响应改写成 `body -> body`，其余接口原样放行。 */
async function stubCapacity(page: import('@playwright/test').Page, mutate: (d: any) => any) {
  await page.route('**/reports/capacity-utilization*', async (route) => {
    const res = await route.fetch();
    const json = await res.json().catch(() => null);
    if (!json) return route.fulfill({ response: res });
    json.data = mutate(json.data ?? {});
    await route.fulfill({ response: res, body: JSON.stringify(json) });
  });
}

test('没有产能时说「算不出」，⛔ 不显示 0.0%', async ({ page }) => {
  test.setTimeout(10 * 60_000);
  await stubCapacity(page, (d) => ({
    ...d,
    averageUtilization: null,
    utilizationTarget: null,
    dailyCapacity: null,
    capacityBasis: '未配置产能: 需要产线小时产能 + 工厂工时, 缺一不可',
    dailyUtilization: (d.dailyUtilization ?? []).map((x: any) => ({ ...x, utilization: null })),
    equipmentUtilization: (d.equipmentUtilization ?? []).map((x: any) => ({ ...x, utilization: null })),
  }));

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, '123456');
  await navRefReady(page);
  await page.waitForTimeout(4000);
  for (let i = 2; i <= ROUTE.length; i += 1) {
    await navigateTo(page, ROUTE.slice(0, i));
    await page.waitForTimeout(i === ROUTE.length ? 6000 : 1200);
  }
  const body = (await screenText(page)).visible;
  console.log('NULL 分支屏上文本:\n' + body.slice(0, 700));

  // 阳性对照：确认我确实在这一屏上，⛔ 否则下面每条断言都在量别的屏
  expect(body).toContain('产能利用率');
  expect(body).toContain('算不出');
  expect(body).toContain('未配置产能');
  expect(body).toContain('未设定');
  // ⛔ 不许把「不知道」画成一个 0
  expect(body).not.toMatch(/(?<![\d.])0\.0%/);
  expect(body).not.toContain('距目标差');
});

test('阴性对照：有真实产能时照旧出数（否则上面那条可能只是屏白了）', async ({ page }) => {
  test.setTimeout(10 * 60_000);
  await stubCapacity(page, (d) => ({
    ...d,
    averageUtilization: 42.5,
    utilizationTarget: 80,
    dailyCapacity: 500,
    capacityBasis: '产线小时产能合计 50 × 工厂工时 10 小时',
  }));

  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, '123456');
  await navRefReady(page);
  await page.waitForTimeout(4000);
  for (let i = 2; i <= ROUTE.length; i += 1) {
    await navigateTo(page, ROUTE.slice(0, i));
    await page.waitForTimeout(i === ROUTE.length ? 6000 : 1200);
  }
  const body = (await screenText(page)).visible;
  console.log('有数分支屏上文本:\n' + body.slice(0, 700));

  expect(body).toContain('42.5%');
  expect(body).toContain('80%');
  expect(body).toContain('距目标差');
  // 分母出处要露出来 —— 读数要自己说清它是怎么算出来的
  expect(body).toContain('工厂工时');
  expect(body).not.toContain('算不出');
});
