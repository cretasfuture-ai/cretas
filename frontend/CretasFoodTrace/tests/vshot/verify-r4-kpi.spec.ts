/**
 * 第四轮：KPI「没有数据」的空态，必须把接口打成**返回 null**才验证得了。
 *
 * 接口通、有数据的时候，这条分支根本没被走到 —— 与第二/三轮的 404 强制失败同一个道理。
 * 这里不是打 404（那验的是错误态），是让它 200 + 每个指标字段都是 null，
 * 也就是**后端改完之后一家零数据工厂真实会收到的东西**。
 */
import { test, expect } from '@playwright/test';
import { waitForApp, navRefReady, login, navigateTo, screenText } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const USER = process.env.VSHOT_USER ?? 'f006_admin';
const OUT = process.env.VSHOT_OUT ?? 'D:/Temp/vshots-r4';

/** 后端 2026-08-28 之后，一家一条事实都没有的工厂真实返回的形状。 */
const ALL_NULL_KPI = {
  success: true,
  message: '操作成功',
  data: {
    factoryId: 'F006',
    reportDate: '2026-08-28',
    oee: null, outputCompletionRate: null, capacityUtilization: null,
    throughput: null, fpy: null, overallQualityRate: null, scrapRate: null,
    bomVarianceRate: null, materialCostRatio: null, laborCostRatio: null,
    overheadCostRatio: null, unitCost: null, avgCycleTime: null, avgLeadTime: null,
    reworkRate: null, orderFulfillmentRate: null, otif: null, onTimeDeliveryRate: null,
    equipmentAvailability: null, mtbf: null, mttr: null, outputPerWorker: null,
    attendanceRate: null,
    overallScore: null, scoreGrade: 'N/A',
  },
};

test('零数据工厂的 KPI 屏说「暂无数据」，不画一个 0 分的红表盘', async ({ page }) => {
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, '123456');
  await navRefReady(page);

  // 只劫持 KPI 那一个接口，其它照常走真后端 —— 免得把「屏打不开」误读成「空态生效」。
  let served = 0;
  await page.route('**/reports/kpi-metrics*', async (route) => {
    served += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(ALL_NULL_KPI),
    });
  });

  const seen: string[] = [];
  page.on('request', (r) => { if (r.url().includes('/api/')) seen.push(r.url()); });

  // 两步走：懒加载的 tab 要先挂载, 它的内层 stack 才存在。
  // 一步深链过去会「成功返回」但停在原地 —— 实测踩过, 而且 navigate() 不报错。
  await navigateTo(page, ['Main', 'BossReportsTab']);
  await page.waitForTimeout(4000);
  await navigateTo(page, ['Main', 'BossReportsTab', 'KPIReport']);
  await page.waitForTimeout(12000);
  console.log('=== /api/ requests after navigating (' + seen.length + ')');
  [...new Set(seen)].slice(0, 20).forEach((u) => console.log('   ' + u));
  console.log('=== 落在哪一屏: ' + (await screenText(page)).visible.slice(0, 300));

  // 阳性对照：拦截真的发生了。否则下面看到的可能是真数据，而我会把它读成「空态对了」。
  expect(served, '劫持没有生效 —— 这一轮读数无效').toBeGreaterThan(0);

  const { visible } = await screenText(page);
  await page.screenshot({ path: `${OUT}/kpi-all-null.png`, fullPage: true });

  console.log('=== served =', served);
  console.log('=== 屏上文字(前 700 字) ===\n' + visible.slice(0, 700));

  // 该出现的
  expect(await page.getByTestId('kpi-empty-overall-score').count(),
    '综合评分应显示占位而不是 0 分表盘').toBeGreaterThan(0);
  for (const k of ['oee', 'fpy', 'otif', 'capacityUtilization']) {
    expect(await page.getByTestId(`kpi-empty-${k}`).count(), `${k} 应显示「暂无数据」`).toBe(1);
  }

  // ⛔ 阴性对照：不许出现编出来的数，也不许把「没有」画成 0
  // '+0.0%' 是我自己看截图才发现的：环比原来 `|| 0`，把「没有上期可比」画成绿色的「持平」。
  for (const forbidden of ['85.0%', '96.0%', '95.0%', '0.0分', '+0.0%']) {
    expect(visible, `屏上不该出现 ${forbidden}`).not.toContain(forbidden);
  }
  expect(visible).toContain('N/A');
});

test('阴性对照：接口有数时照样画表盘 —— ⛔ 防「一律显示暂无数据」也能过', async ({ page }) => {
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, USER, '123456');
  await navRefReady(page);

  let served = 0;
  await page.route('**/reports/kpi-metrics*', async (route) => {
    served += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...ALL_NULL_KPI,
        data: { ...ALL_NULL_KPI.data, oee: 71.5, fpy: 88.25, otif: 93, capacityUtilization: 64.5, overallScore: 77.25, scoreGrade: 'C' },
      }),
    });
  });

  await navigateTo(page, ['Main', 'BossReportsTab']);
  await page.waitForTimeout(4000);
  await navigateTo(page, ['Main', 'BossReportsTab', 'KPIReport']);
  await page.waitForTimeout(12000);
  expect(served, '劫持没有生效').toBeGreaterThan(0);

  await page.screenshot({ path: `${OUT}/kpi-with-data.png`, fullPage: true });

  // 有数的那四个不该再出现占位
  for (const k of ['oee', 'fpy', 'otif', 'capacityUtilization']) {
    expect(await page.getByTestId(`kpi-empty-${k}`).count(), `${k} 有数时不该显示占位`).toBe(0);
  }
  expect(await page.getByTestId('kpi-empty-overall-score').count()).toBe(0);

  const { visible } = await screenText(page);
  expect(visible).toContain('C');
});
