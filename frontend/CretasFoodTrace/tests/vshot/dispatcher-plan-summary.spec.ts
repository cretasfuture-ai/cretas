/**
 * 调度员「生产计划」列表: 底部汇总跟着搜索 / 页签走 (2026-11-12 验收: 原来筛选后仍是全部 156 条)。
 * 全打桩, 没有请求到达后端。
 *
 * 运行: VSHOT_APP=http://localhost:3021 npx playwright test -c tests/vshot/vshot.config.ts dispatcher-plan-summary
 */
import { test, expect, type Page } from '@playwright/test';
import { waitForApp, login, navRefReady } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const SHOTS = process.env.VSHOT_OUT ?? 'test-results/dispatcher-plan-summary';
const ok = (data: unknown) => ({ code: 200, success: true, message: 'ok', data });
const today = new Date().toISOString().slice(0, 10);

const PLANS = [
  { id: 'QA-P1', planNumber: 'QA-PLAN-001', productTypeName: 'QA-卤猪蹄 200g', plannedQuantity: 100, actualQuantity: 40, plannedUnit: 'kg', status: 'IN_PROGRESS', plannedDate: today, createdAt: `${today}T08:00:00` },
  { id: 'QA-P2', planNumber: 'QA-PLAN-002', productTypeName: 'QA-卤猪蹄 500g', plannedQuantity: 50, actualQuantity: 10, plannedUnit: 'kg', status: 'PENDING', plannedDate: today, createdAt: `${today}T08:10:00` },
  { id: 'QA-P3', planNumber: 'QA-PLAN-003', productTypeName: 'QA-酱牛肉', plannedQuantity: 20, actualQuantity: 20, plannedUnit: '盒', status: 'COMPLETED', plannedDate: today, createdAt: `${today}T08:20:00` },
  { id: 'QA-P4', planNumber: 'QA-PLAN-004', productTypeName: 'QA-鸭脖', plannedQuantity: 30, actualQuantity: 0, plannedUnit: 'kg', status: 'PENDING', plannedDate: today, createdAt: `${today}T08:30:00` },
];

async function stub(page: Page) {
  const writes: string[] = [];
  await page.route(/\/api\//, async (route) => {
    const req = route.request();
    const p = new URL(req.url()).pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    if (p.endsWith('/auth/unified-login')) {
      return json(ok({
        userId: 9101, username: 'qa_dispatcher', role: 'dispatcher', userType: 'factory',
        token: 'qa-token', accessToken: 'qa-token', refreshToken: 'qa-refresh', expiresIn: 86400,
        factoryId: 'F006', factoryName: 'QA 六膳门食品科技', factoryType: 'FACTORY',
        permissions: [], modulePermissions: { dashboard: 'r', production: 'rw', scheduling: 'rw' },
        profile: { name: 'QA 调度员' }, createdAt: `${today}T00:00:00`, updatedAt: `${today}T00:00:00`,
      }));
    }
    if (/\/production-plans$/.test(p) && req.method() === 'GET') {
      return json(ok({ content: PLANS, totalElements: PLANS.length, totalPages: 1 }));
    }
    if (/\/list-summary\/productionPlan$/.test(p)) {
      // 旧行为的来源: 后端汇总不知道屏上的搜索, 恒为全部。新代码不该再用它。
      return json(ok({ entityType: 'productionPlan', stats: [{ label: '共', value: 156, format: 'number', unit: '条' }] }));
    }
    if (req.method() !== 'GET') writes.push(`${req.method()} ${p}`);
    return json(ok([]));
  });
  return writes;
}

test('搜索「猪蹄」后底部汇总只算这两条', async ({ page }) => {
  test.setTimeout(10 * 60_000);
  const writes = await stub(page);
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, 'qa_dispatcher', 'stub');
  await navRefReady(page);
  // 底部「计划」页签 (DispatcherTabNavigator PlanTab → DSPlanStackNavigator PlanList)
  await page.evaluate(() => (globalThis as unknown as { __CRETAS_NAV__: { navigate: (n: string, p?: unknown) => void } })
    .__CRETAS_NAV__.navigate('PlanTab', { screen: 'PlanList' }));
  const footer = page.getByTestId('sticky-footer-summary');
  await expect(page.getByText('QA-鸭脖')).toBeVisible({ timeout: 60_000 });
  await expect(footer).toContainText('4');
  await expect(footer).not.toContainText('156');
  await page.screenshot({ path: `${SHOTS}/01-all-plans.png` });

  await page.getByPlaceholder(/搜索/).fill('猪蹄');
  await expect(page.getByText('QA-鸭脖')).toHaveCount(0);
  await expect(footer).toContainText('2');
  await expect(footer).toContainText('150');   // 计划 100 + 50 kg
  await expect(footer).toContainText('50');    // 完成 40 + 10 kg
  await page.screenshot({ path: `${SHOTS}/02-search-pork.png` });
  expect(writes.filter((w) => !w.includes('/auth/'))).toEqual([]);
});

test('150 条计划: 先显示「已加载 100 / 共 150」, 加载剩余后汇总算全部', async ({ page }) => {
  test.setTimeout(10 * 60_000);
  const many = Array.from({ length: 150 }, (_, i) => ({
    id: `QA-M${i + 1}`, planNumber: `QA-MANY-${String(i + 1).padStart(3, '0')}`, productTypeName: `QA-分页样本-${i + 1}`,
    plannedQuantity: 1, actualQuantity: 0, plannedUnit: 'kg', status: 'PENDING', plannedDate: today,
    createdAt: new Date(Date.now() - i * 60000).toISOString(),
  }));
  const pagesAsked: string[] = [];
  await stub(page);
  await page.route(/\/production-plans(\?.*)?$/, async (route) => {
    const url = new URL(route.request().url());
    const p = Number(url.searchParams.get('page') ?? '1');
    const size = Number(url.searchParams.get('size') ?? '20');
    pagesAsked.push(`${p}/${size}`);
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify(ok({ content: many.slice((p - 1) * size, p * size), totalElements: many.length })),
    });
  });
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, 'qa_dispatcher', 'stub');
  await navRefReady(page);
  await page.evaluate(() => (globalThis as unknown as { __CRETAS_NAV__: { navigate: (n: string, p?: unknown) => void } })
    .__CRETAS_NAV__.navigate('PlanTab', { screen: 'PlanList' }));
  const coverage = page.getByTestId('plan-list-coverage');
  await expect(coverage).toContainText('已加载 100 / 共 150 条计划', { timeout: 60_000 });
  await expect(page.getByTestId('sticky-footer-summary')).toContainText('100');
  await page.screenshot({ path: `${SHOTS}/03-150-plans-first-page.png` });

  await page.getByTestId('plan-list-load-all').click();
  await expect(coverage).toHaveCount(0);
  await expect(page.getByTestId('sticky-footer-summary')).toContainText('150');
  // 调度首页自己也读一次计划 (size=200), 只看列表页的请求 (size=100)
  expect(pagesAsked.filter((x) => x.endsWith('/100'))).toEqual(['1/100', '2/100']);
  await page.screenshot({ path: `${SHOTS}/04-150-plans-all-loaded.png` });
});
