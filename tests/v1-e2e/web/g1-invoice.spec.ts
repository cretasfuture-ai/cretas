/**
 * G1 自动税率分组开票边界 — @pr-gate
 *
 * 真正的正向链由 F006 role-chain 覆盖：全量发货后轮询唯一 REQUESTED 记录，
 * 再审核和开具。本文件固定旧 PROCESSING fixture 的负向边界，防止手工入口复活
 * 或尚未全部发货的订单被提前申请开票。
 */
import { test, expect } from '@playwright/test';
import { setupAuthBeforeAll, restoreAuth } from '../helpers/auth-cache';
import { S } from '../helpers/selectors';

const ORDER_ID = 'e2e-so-g1-00000000000000000000001';
const ORDER_NUMBER = 'DEMO_SO_G1';

async function authenticatedPost(page: import('@playwright/test').Page, pathname: string, body: object) {
  return page.evaluate(async ({ pathname, body }) => {
    const token = localStorage.getItem('cretas_access_token');
    if (!token) throw new Error('missing authenticated token');
    const response = await fetch(pathname, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    return { status: response.status, body: await response.json().catch(() => null) };
  }, { pathname, body });
}

test.describe('G1 自动税率分组开票边界 @pr-gate', () => {
  test.beforeAll(async ({ browser }) => setupAuthBeforeAll(browser, 'sales_mgr'));
  test.beforeEach(async ({ page, context }) => restoreAuth(context, page, 'sales_mgr'));

  test('销售列表和详情均不再暴露手工开票入口', async ({ page }) => {
    await page.goto('/sales/orders');
    await page.waitForSelector('.el-table', { timeout: 15_000 });
    const row = page.locator(S.table.rowByText(ORDER_NUMBER));
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(row.getByRole('button', { name: /税率分组开票|申请开票|手工开票/ })).toHaveCount(0);

    await page.goto(`/sales/orders/${ORDER_ID}`);
    await expect(page.getByText(ORDER_NUMBER, { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('button', { name: /一键开票申请|申请开票|手工开票/ })).toHaveCount(0);
  });

  test('旧 REST 手工申请端点不再暴露手工能力', async ({ page }) => {
    await page.goto('/sales/orders');
    const result = await authenticatedPost(page,
      '/api/mobile/F_E2E_TEST/finance/invoices/request-from-order',
      { salesOrderId: ORDER_ID, invoiceType: 'NORMAL' });

    // The compatibility tombstone is 410 after the finance module/permission
    // gates.  A disabled module or unauthorized role may be rejected earlier
    // with 403; neither outcome exposes a manual mutation capability.
    expect([403, 410]).toContain(result.status);
    if (result.status === 410) {
      expect(result.body?.message || result.body?.error || '').toContain('手工');
    } else {
      expect(result.body?.code || '').toBe('FORBIDDEN');
    }
  });
});
