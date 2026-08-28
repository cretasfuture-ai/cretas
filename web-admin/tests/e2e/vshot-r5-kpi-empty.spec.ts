/**
 * web-admin 的 KPI 页：后端返回 null 时必须显示「—」，⛔ 不是「0.0%」。
 *
 * 为什么必须劫持接口：F006 有数据，正常路径下这条分支根本不会被走到。
 * 2026-08-28 后端已改成算不出来就返 null（原来兜底成 85/96/95），
 * 而前端旧代码是 `pct = (v ?? 0)/100` —— 两个各自正确的改动叠加出「0%」，
 * 比原来的 85% 更像一个灾难性的真实读数。
 */
import { test, expect } from '@playwright/test';

const BASE = process.env.VSHOT_BASE || 'http://139.196.165.140:8086';
const USER = process.env.VSHOT_USER || 'f006_admin';
const PASS = process.env.VSHOT_PASS || '123456';
const OUT = process.env.VSHOT_OUT || 'D:/Temp/vshots-r5';

const NULL_KPI = {
  success: true, message: '操作成功',
  data: {
    factoryId: 'F006', reportDate: '2026-08-28',
    oee: null, overallQualityRate: null, avgCycleTime: null, throughput: null,
    fpy: null, reworkRate: null, scrapRate: null,
    onTimeDeliveryRate: null, otif: null, avgLeadTime: null,
    inFullDeliveryRate: null, orderFulfillmentRate: null,
    unitCost: null, materialCostRatio: null, laborCostRatio: null, overheadCostRatio: null,
    overallScore: null, scoreGrade: 'N/A',
  },
};

/**
 * ⚠️ 两个坑，都实测踩过：
 * ① `/analytics/kpi` 的菜单项已被合并进 `/smart-bi/analysis-hub?tab=kpi`（WS4），
 *    直接敲旧路径会停在别处，接口一次都不调。
 * ② 固定 `waitForTimeout` 会截到 SPA 的「正在加载应用…」启动闪屏 ——
 *    那张图在每一项判据上都长得像「页面是空的」。⇒ 等一个**真实元素**出现。
 */
async function gotoKpi(page: import('@playwright/test').Page) {
  await page.goto(`${BASE}/smart-bi/analysis-hub?tab=kpi`, { waitUntil: 'domcontentloaded' });
  await page.locator('text=正在加载应用').waitFor({ state: 'detached', timeout: 60_000 }).catch(() => {});
  await page.getByText('综合评分', { exact: false }).first().waitFor({ timeout: 60_000 }).catch(() => {});
  await page.waitForTimeout(3000);
}

async function login(page: import('@playwright/test').Page) {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.locator('input').first().fill(USER);
  await page.locator('input[type="password"]').first().fill(PASS);
  // ⚠️ 按钮文字是「登 录」（中间有空格）——`has-text("登录")` 匹配不上。用仓里已有走查的正则。
  await page.getByRole('button', { name: /登\s*录|登录/ }).first().click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 30_000 });
  // 阳性对照：真的登进去了，⛔ 不是「登录页消失了」
  const blob = await page.evaluate(() =>
    Object.keys(localStorage).map((k) => `${k}=${localStorage.getItem(k)}`).join('\n'));
  expect(blob, '登录后 localStorage 里应能看到 F006').toContain('F006');
}

test('KPI 页：后端 null 时显示「—」，不是 0.0%', async ({ page }) => {
  await login(page);

  let served = 0;
  await page.route('**/reports/kpi-metrics*', async (route) => {
    served += 1;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(NULL_KPI) });
  });

  await gotoKpi(page);
  await page.screenshot({ path: `${OUT}/kpi-null.png`, fullPage: true });

  expect(served, '劫持没有生效 —— 这一轮读数无效').toBeGreaterThan(0);

  const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ');
  console.log('=== 屏上文字(前 500): ' + body.slice(0, 500));

  expect(body, '算不出来时应显示「—」').toContain('—');
  // ⛔ 阴性对照：不许把「没有数据」画成 0
  // '0%' 是 el-progress 自己打的 —— 看截图才发现它紧挨着「当前: —」。
  for (const bad of ['0.0%', '0.00%', '0%']) {
    expect(body, `屏上不该出现 ${bad}`).not.toContain(bad);
  }
});

test('阴性对照：接口有数时照常显示百分比（⛔ 防「一律显示 —」也能过）', async ({ page }) => {
  await login(page);

  let served = 0;
  await page.route('**/reports/kpi-metrics*', async (route) => {
    served += 1;
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ ...NULL_KPI, data: { ...NULL_KPI.data, oee: 71.5, fpy: 88.2, overallQualityRate: 93.4 } }),
    });
  });

  await gotoKpi(page);
  await page.screenshot({ path: `${OUT}/kpi-with-data.png`, fullPage: true });
  expect(served).toBeGreaterThan(0);

  const body = (await page.locator('body').innerText()).replace(/\s+/g, ' ');
  expect(body).toContain('71.5%');
  expect(body).toContain('88.2%');
});
